#include "android_diagnostics.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>

typedef struct {
    const char *name;
    const char *label;
    unsigned long long value;
    int valid;
} MemoryValue;

static int read_number(const char *text, unsigned long long *value) {
    while (*text == ' ' || *text == '\t') text++;
    if (*text < '0' || *text > '9') return 0;
    errno = 0;
    *value = strtoull(text, NULL, 10);
    return errno != ERANGE;
}

static void read_values(const char *root, const char *file, MemoryValue *fields, size_t count) {
    char path[256], line[256];
    int length = snprintf(path, sizeof path, "%s/%s", root, file);
    if (length < 0 || (size_t)length >= sizeof path) return;
    FILE *in = fopen(path, "r");
    if (!in) return;
    while (fgets(line, sizeof line, in)) {
        char *colon = strchr(line, ':');
        if (!colon) continue;
        *colon = 0;
        for (size_t i = 0; i < count; i++)
            if (!strcmp(line, fields[i].name))
                fields[i].valid = read_number(colon + 1, &fields[i].value);
    }
    fclose(in);
}

static int read_scalar(const char *root, pid_t pid, MemoryValue *field) {
    char path[256], line[80];
    int length = snprintf(path, sizeof path, "%s/%ld/%s", root, (long)pid, field->name);
    if (length < 0 || (size_t)length >= sizeof path) return 0;
    FILE *in = fopen(path, "r");
    if (!in) return 0;
    int valid = fgets(line, sizeof line, in) && read_number(line, &field->value);
    fclose(in);
    return valid;
}

static void print_values(FILE *out, const char *phase, unsigned long long uptime_ms,
                         const char *source, const MemoryValue *fields, size_t count) {
    char line[768];
    int length = snprintf(line, sizeof line, "[totk-memory] phase=%s uptime_ms=%llu %s", phase, uptime_ms, source);
    if (length < 0 || (size_t)length >= sizeof line) return;
    size_t used = (size_t)length;
    for (size_t i = 0; i < count; i++) {
        if (fields[i].valid)
            length = snprintf(line + used, sizeof line - used, " %s=%llu", fields[i].label, fields[i].value);
        else
            length = snprintf(line + used, sizeof line - used, " %s=na", fields[i].label);
        if (length < 0 || (size_t)length >= sizeof line - used) break;
        used += (size_t)length;
    }
    fprintf(out, "%s\n", line);
}

static void read_pressure(const char *proc, MemoryValue *some, MemoryValue *full) {
    char path[256], line[192];
    int length = snprintf(path, sizeof path, "%s/pressure/memory", proc);
    if (length < 0 || (size_t)length >= sizeof path) return;
    FILE *in = fopen(path, "r");
    if (!in) return;
    while (fgets(line, sizeof line, in)) {
        MemoryValue *field = !strncmp(line, "some ", 5) ? some : !strncmp(line, "full ", 5) ? full : NULL;
        const char *total = strstr(line, " total=");
        if (field && total) field->valid = read_number(total + 7, &field->value);
    }
    fclose(in);
}

#define COUNT(fields) (sizeof(fields) / sizeof((fields)[0]))
void android_memory_diagnostics(FILE *out, const char *phase, unsigned long long uptime_ms,
                                const char *proc, const char *kgsl, pid_t pid, AndroidMemoryDetail detail) {
    MemoryValue process[] = {
        {"VmRSS", "rss_kib", 0, 0}, {"VmHWM", "peak_rss_kib", 0, 0}, {"RssAnon", "anon_kib", 0, 0},
        {"RssFile", "file_kib", 0, 0}, {"RssShmem", "shared_kib", 0, 0}, {"VmSwap", "swap_kib", 0, 0},
        {"Threads", "threads", 0, 0}, {NULL, "major_faults", 0, 0}, {NULL, "minor_faults", 0, 0},
    };
    read_values(proc, "self/status", process, 7);
    struct rusage usage;
    if (!getrusage(RUSAGE_SELF, &usage)) {
        process[7].value = (unsigned long long)usage.ru_majflt;
        process[8].value = (unsigned long long)usage.ru_minflt;
        process[7].valid = process[8].valid = 1;
    }
    print_values(out, phase, uptime_ms, "process", process, COUNT(process));

    MemoryValue system[] = {
        {"MemTotal", "total_kib", 0, 0}, {"MemAvailable", "available_kib", 0, 0},
        {"SwapTotal", "swap_total_kib", 0, 0}, {"SwapFree", "swap_free_kib", 0, 0},
        {NULL, "psi_some_total_us", 0, 0}, {NULL, "psi_full_total_us", 0, 0},
    };
    read_values(proc, "meminfo", system, 4);
    read_pressure(proc, &system[4], &system[5]);
    print_values(out, phase, uptime_ms, "system", system, COUNT(system));

    MemoryValue gpu[] = {
        {"kernel", "kernel_bytes", 0, 0}, {"imported_mem", "imported_bytes", 0, 0},
        {"gpumem_mapped", "mapped_bytes_overlaps_kernel", 0, 0},
    };
    gpu[0].valid = read_scalar(kgsl, pid, &gpu[0]);
    gpu[2].valid = read_scalar(kgsl, pid, &gpu[2]);
    /* Vendor imported_mem can walk every buffer under the KGSL allocation lock. */
    if (detail != ANDROID_MEMORY_FATAL) {
        gpu[1].valid = read_scalar(kgsl, pid, &gpu[1]);
        if (!gpu[1].valid) {
            gpu[1].name = "imported";
            gpu[1].valid = read_scalar(kgsl, pid, &gpu[1]);
        }
    }
    print_values(out, phase, uptime_ms, "kgsl", gpu, COUNT(gpu));

    if (detail == ANDROID_MEMORY_FULL) {
        MemoryValue pss[] = {
            {"Pss", "pss_kib", 0, 0}, {"SwapPss", "swap_pss_kib", 0, 0},
            {"Private_Dirty", "private_dirty_kib", 0, 0},
        };
        read_values(proc, "self/smaps_rollup", pss, COUNT(pss));
        print_values(out, phase, uptime_ms, "rollup", pss, COUNT(pss));
    }
}
