/* MIT. Real Linux mappings and production pool operations, with observed Vulkan handles. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define VK_CALLOC calloc
#define VK_REALLOC realloc
#define LOGV(...) ((void)0)
#define DIE(...) do { fprintf(stderr, __VA_ARGS__); abort(); } while (0)
#define HK_ADD(...) ((void)0)
typedef unsigned VkBuffer, VkDeviceMemory;
enum { POOLS_HOST = 1, POOLS_EXPORT, VK_MEMORY_POOL };
static const int g_pool_mode = POOLS_EXPORT;
static uint64_t g_host_align, g_wb_alias_maps, g_wb_alias_bytes;
static int g_wb_alias_enabled;
static _Atomic uint64_t g_owner_gen;
static void span_changed(void) {}

#include "pool_registry.inc"

static pthread_barrier_t retirement_started, replacement_ready;
enum { OLD_BUFFER = 1, NEW_BUFFER, BUFFER_COUNT };
static unsigned created;
static int pending_view[BUFFER_COUNT], destroyed[BUFFER_COUNT], freed[BUFFER_COUNT];
static void *g_dev;

static int back_pool(pool_ent *owner) {
    size_t size = owner->end - owner->base;
    int fd = memfd_create("pool-reregister", MFD_CLOEXEC);
    assert(fd >= 0 && !ftruncate(fd, (off_t)size));
    void *mirror = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    assert(mirror != MAP_FAILED);
    void *guest = (void *)owner->base;
    memcpy(mirror, guest, size);
    assert(mmap(guest, size, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_FIXED, fd, 0) == guest);
    owner->fd = fd;
    assert(++created < BUFFER_COUNT);
    owner->buf = owner->mem = created;
    pending_view[owner->buf] = 1;
    if (g_wb_alias_enabled) {
        owner->wb_alias = mirror;
        g_wb_alias_maps++;
        g_wb_alias_bytes += size;
    } else {
        assert(!munmap(mirror, size));
    }
    return 1;
}

static void rendezvous(pthread_barrier_t *barrier) {
    int rc = pthread_barrier_wait(barrier);
    assert(rc == 0 || rc == PTHREAD_BARRIER_SERIAL_THREAD);
}

void vk_res_buffer_released(VkBuffer buffer) {
    assert(buffer && buffer < BUFFER_COUNT && pending_view[buffer] && !destroyed[buffer]);
    assert(!pthread_mutex_trylock(&g_pool_mu));
    assert(!pthread_mutex_unlock(&g_pool_mu));
    if (buffer == OLD_BUFFER) {
        rendezvous(&retirement_started);
        rendezvous(&replacement_ready);
    }
    pending_view[buffer] = 0;
}

static void vkDestroyBuffer(void *device, VkBuffer buffer, const void *allocator) {
    (void)device;
    (void)allocator;
    assert(buffer && buffer < BUFFER_COUNT && !pending_view[buffer] && !destroyed[buffer]);
    destroyed[buffer] = 1;
}

static void vk_memory_release(VkDeviceMemory memory, int purpose) {
    assert(purpose == VK_MEMORY_POOL && memory && memory < BUFFER_COUNT);
    assert(destroyed[memory] && !freed[memory]);
    freed[memory] = 1;
}

static void mtl_init(void) {}
static void vk_gpu_idle(void) { abort(); }
static int pool_gpu_quiet(void) { return 1; }
static void vk_wb_range_gone(uintptr_t lo, uintptr_t hi) { assert(lo < hi); }
static void vk_tex_pool_gone(uintptr_t lo, uintptr_t hi) { assert(lo < hi); }

static void *restore_under_pool_lock(void *address, size_t old_size, size_t new_size,
                                     int flags, void *destination) {
    assert(pthread_mutex_trylock(&g_pool_mu) == EBUSY);
    return mremap(address, old_size, new_size, flags, destination);
}

#define mremap restore_under_pool_lock
#include "pool_retirement.inc"
#undef mremap

static void *remove_pool(void *guest) {
    del_range(guest, 0);
    return NULL;
}

int main(int argc, char **argv) {
    assert(argc == 2);
    g_wb_alias_enabled = !strcmp(argv[1], "1");
    size_t size = page_sz();
    uint8_t *guest = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    assert(guest != MAP_FAILED);
    memset(guest, 0x35, size);
    mtl_pool_add(guest, size);
    assert(g_npool == 1 && g_pools[0]->kind == K_OWN);
    uint64_t old_id = g_pools[0]->identity;
    uint8_t *old = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, g_pools[0]->fd, 0);
    assert(old != MAP_FAILED);

    assert(!pthread_barrier_init(&retirement_started, NULL, 2));
    assert(!pthread_barrier_init(&replacement_ready, NULL, 2));
    pthread_t remover;
    assert(!pthread_create(&remover, NULL, remove_pool, guest));
    rendezvous(&retirement_started);
    assert(!g_npool && !g_pool_bytes && !destroyed[OLD_BUFFER]);
    old[0] = 0x66;
    int restored = guest[0] == 0x35;
    assert(guest[size - 1] == 0x35);

    mtl_pool_add(guest, size);
    assert(g_npool == 1 && g_pools[0]->kind == K_OWN && g_pools[0]->identity != old_id);
    assert(g_pools[0]->buf == NEW_BUFFER && pending_view[NEW_BUFFER]);
    uint8_t *replacement = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, g_pools[0]->fd, 0);
    assert(replacement != MAP_FAILED && replacement[size - 1] == 0x35);
    rendezvous(&replacement_ready);
    assert(!pthread_join(remover, NULL));
    assert(!pthread_barrier_destroy(&retirement_started) && !pthread_barrier_destroy(&replacement_ready));

    replacement[0] = 0x79;
    int shared = guest[0] == 0x79;
    assert(old[0] == 0x66 && freed[OLD_BUFFER] && !destroyed[NEW_BUFFER] && pending_view[NEW_BUFFER]);
    assert(g_npool == 1 && g_pool_bytes == size);
    assert(g_wb_alias_maps == (unsigned)g_wb_alias_enabled);
    assert(g_wb_alias_bytes == (g_wb_alias_enabled ? size : 0));
    del_range(guest, 0);
    assert(freed[NEW_BUFFER] && !g_pool_bytes && !g_npool && !g_wb_alias_maps && !g_wb_alias_bytes);
    assert(!munmap(old, size) && !munmap(replacement, size) && !munmap(guest, size));
    free(g_pools);
    printf("restored=%d shared=%d\n", restored, shared);
    return 0;
}
