#pragma once
#include <stdio.h>
#include <sys/types.h>

typedef enum { ANDROID_MEMORY_FAST, ANDROID_MEMORY_FULL, ANDROID_MEMORY_FATAL } AndroidMemoryDetail;
/* Ordinary threads only. Root arguments also allow fixture tests with the same parser. */
void android_memory_diagnostics(FILE *out, const char *phase, unsigned long long uptime_ms,
                                const char *proc, const char *kgsl, pid_t pid, AndroidMemoryDetail detail);
