"""Execute both production descriptor allocators with counted, capacity-enforcing Vulkan doubles."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GFX = ROOT / 'games/totk/android/gfx'


def section(source, start, end):
    begin = source.index(start)
    return source[begin:source.index(end, begin)]


class DescriptorMemoryTests(unittest.TestCase):
    def test_compact_capacity_batching_and_wide_layout_fallback(self):
        cc = shutil.which('cc')
        if not cc:
            self.skipTest('C compiler required')
        recorder = (GFX / 'vk_record.c').read_text()
        primary = (GFX / 'nvn_vk.c').read_text()
        source = r'''
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
typedef uint64_t VkDescriptorPool, VkDescriptorSetLayout, VkDescriptorSet;
typedef int VkResult;
enum { VK_DESCRIPTOR_TYPE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
 VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
 VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER,
 VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
 VK_DESC_POOL_TYPES = 8, VK_DESC_STOCK_BATCH = 128, REC_POOL_SETS = 1024, DPOOL_SETS = 4096,
 VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO = 1, VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO = 2,
 VK_ERROR_OUT_OF_POOL_MEMORY = -1, VK_ERROR_FRAGMENTED_POOL = -2, FT_COUNT_DESC_CREATES = 0 };
typedef struct { unsigned type, descriptorCount; } VkDescriptorPoolSize;
typedef struct { unsigned sType, maxSets, poolSizeCount; const VkDescriptorPoolSize *pPoolSizes; } VkDescriptorPoolCreateInfo;
typedef struct { unsigned sType; VkDescriptorPool descriptorPool; unsigned descriptorSetCount; const VkDescriptorSetLayout *pSetLayouts; } VkDescriptorSetAllocateInfo;
typedef struct { VkDescriptorPool *dp; unsigned curdp, ndp, sets, capdp; uint64_t descriptor_calls, descriptor_sets; } rec_context;
typedef struct { VkDescriptorPool *dpools; int curdpool, ndpool, curdsets, capdpool; } slot_t;
static struct { int dev; } V;
static _Atomic uint64_t memory_dpools;
static struct { unsigned max, sets, capacity[8], used[8]; int alive; } pool[2048];
static unsigned next_pool, destroyed, next_set, full_pools;
#define VK_REALLOC realloc
#define DIE(...) abort()
#define VK_DIE_RESULT(result, ...) abort()
#define VKCHECK(call) assert((call) == 0)
#define HK_ADD(...) ((void)0)
#define LOGV(...) ((void)0)
static void vk_record_descriptor_primary(unsigned a, unsigned b, unsigned c) { (void)a; (void)b; (void)c; }
static void vk_record_primary_pools(int n) { (void)n; }
static void totk_ft_count(int a, unsigned b) { (void)a; (void)b; }
static VkResult vkCreateDescriptorPool(int device, const VkDescriptorPoolCreateInfo *info, void *allocator, VkDescriptorPool *out) {
 (void)device; (void)allocator; assert(next_pool + 1 < 2048);
 *out = ++next_pool; pool[*out].alive = 1; pool[*out].max = info->maxSets;
 for (unsigned i = 0; i < info->poolSizeCount; i++) pool[*out].capacity[info->pPoolSizes[i].type] = info->pPoolSizes[i].descriptorCount;
 full_pools += pool[*out].capacity[VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER] == 32768;
 return 0;
}
static void vkDestroyDescriptorPool(int device, VkDescriptorPool p, void *allocator) {
 (void)device; (void)allocator; assert(pool[p].alive && !pool[p].sets);
 pool[p].alive = 0; destroyed++;
}
static VkResult vkAllocateDescriptorSets(int device, const VkDescriptorSetAllocateInfo *info, VkDescriptorSet *out) {
 (void)device; VkDescriptorPool p = info->descriptorPool; assert(pool[p].alive);
 unsigned need[8] = {0};
 for (unsigned i = 0; i < info->descriptorSetCount; i++) {
  if (info->pSetLayouts[i] == 1) need[VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER] += 64;
  else { assert(info->pSetLayouts[i] == 2); need[VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER] += 9000; }
 }
 if (pool[p].sets + info->descriptorSetCount > pool[p].max) return VK_ERROR_OUT_OF_POOL_MEMORY;
 for (unsigned i = 0; i < 8; i++) if (pool[p].used[i] + need[i] > pool[p].capacity[i]) return VK_ERROR_OUT_OF_POOL_MEMORY;
 for (unsigned i = 0; i < 8; i++) pool[p].used[i] += need[i];
 pool[p].sets += info->descriptorSetCount;
 for (unsigned i = 0; i < info->descriptorSetCount; i++) out[i] = ++next_set;
 return 0;
}
'''
        source += section(recorder, 'const VkDescriptorPoolSize vk_desc_pool_sizes', 'uint32_t vk_desc_idle_keep(')
        source += section(recorder, 'VkResult vk_desc_pool_create(', 'int vk_desc_layout_fits(')
        source += section(recorder, 'static uint32_t alloc_sets(', '/* Descriptor-set stock')
        source += section(primary, 'static uint32_t slot_alloc_sets(', 'VkDescriptorSet vk_alloc_set(')
        source += r'''
static void take(int primary, void *owner, VkDescriptorSetLayout layout, unsigned total) {
 VkDescriptorSet sets[VK_DESC_STOCK_BATCH];
 while (total) {
  unsigned request = total > VK_DESC_STOCK_BATCH ? VK_DESC_STOCK_BATCH : total;
  unsigned n = primary ? slot_alloc_sets(owner, layout, request, sets) : alloc_sets(owner, layout, request, sets);
  assert(n && n <= request); total -= n;
 }
}
int main(int argc, char **argv) {
 (void)vkDestroyDescriptorPool; // also compile the unmodified baseline control
 assert(argc == 2); int compact = !strcmp(argv[1], "compact");
 if (compact) setenv("TOTK_VK_DESC_COMPACT", "1", 1); else unsetenv("TOTK_VK_DESC_COMPACT");
 rec_context rec = {0}; slot_t slot = {0};
 take(0, &rec, 1, 900); take(1, &slot, 1, 900);
 assert(next_set == 1800 && !destroyed);
 assert(pool[rec.dp[0]].capacity[1] == (compact ? 8192u : 32768u));
 assert(pool[slot.dpools[0]].capacity[1] == (compact ? 8192u : 32768u));
 // Wide layouts still fit the original supported capacity. Only newly created,
 // never-used compact pools may be destroyed/replaced; live sets remain intact.
 unsigned before = full_pools;
 take(0, &rec, 2, 20); take(1, &slot, 2, 20);
 assert(next_set == 1840);
 assert(compact ? destroyed >= 2 && full_pools > before : destroyed == 0);
 for (unsigned p = 1; p <= next_pool; p++)
  if (!pool[p].alive) assert(pool[p].sets == 0);
 free(rec.dp); free(slot.dpools);
 puts("descriptor capacity enforced; batching and wide-layout fallback preserve live sets");
 return 0;
}
'''
        with tempfile.TemporaryDirectory() as directory:
            c, binary = Path(directory) / 'probe.c', Path(directory) / 'probe'
            c.write_text(source)
            result = subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror', '-pthread', str(c), '-o', str(binary)],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            for mode in ('default', 'compact'):
                with self.subTest(mode=mode):
                    result = subprocess.run([str(binary), mode], env=dict(os.environ), capture_output=True, text=True, timeout=30)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
