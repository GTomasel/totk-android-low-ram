"""Run the Android crash memory parser against controlled proc and KGSL files."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


RUNTIME = Path(__file__).resolve().parents[1] / 'games/totk/runtime'


class AndroidMemoryDiagnosticsTests(unittest.TestCase):
    def test_periodic_and_fatal_samples_preserve_zero_and_unavailable(self):
        cc = shutil.which('cc')
        if not cc:
            self.skipTest('C compiler required')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixtures = {
                'proc/self/status': 'VmRSS:\t1234 kB\nVmHWM: 2000 kB\nVmSwap: 0 kB\nThreads: 42\n',
                'proc/self/smaps_rollup': 'Pss: 888 kB\nSwapPss: 0 kB\nPrivate_Dirty: 777 kB\n',
                'proc/meminfo': 'MemTotal: 8000000 kB\nMemAvailable: 900000 kB\nSwapFree: 0 kB\n',
                'proc/pressure/memory': 'some avg10=0.4 total=4321\nfull avg10=0.0 total=0\n',
                'kgsl/42/kernel': '9999\n',
                'kgsl/42/imported': '3210\n',
                'kgsl/42/gpumem_mapped': '2000\n',
            }
            for name, content in fixtures.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            test = root / 'probe.c'
            test.write_text('''
#include "android_diagnostics.h"
int main(int argc, char **argv) {
    if (argc != 3) return 1;
    android_memory_diagnostics(stdout, "sample", 5000, argv[1], argv[2], 42, ANDROID_MEMORY_FULL);
    android_memory_diagnostics(stdout, "vulkan-fatal", 7189, argv[1], argv[2], 42, ANDROID_MEMORY_FATAL);
    android_memory_diagnostics(stdout, "missing", 9000, "/nonexistent", "/nonexistent", 100, ANDROID_MEMORY_FULL);
    return 0;
}
''')
            probe = root / 'probe'
            subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror', '-I', str(RUNTIME), str(test),
                            str(RUNTIME / 'android_diagnostics.c'), '-o', str(probe)], check=True)
            output = subprocess.check_output([probe, root / 'proc', root / 'kgsl'], text=True)
        sample, fatal, missing = (list(line for line in output.splitlines() if 'phase=' + phase in line)
                                  for phase in ('sample', 'vulkan-fatal', 'missing'))
        self.assertIn('rss_kib=1234 peak_rss_kib=2000 anon_kib=na file_kib=na shared_kib=na swap_kib=0 threads=42', sample[0])
        self.assertIn('available_kib=900000 swap_total_kib=na swap_free_kib=0 psi_some_total_us=4321 psi_full_total_us=0', sample[1])
        self.assertIn('kernel_bytes=9999 imported_bytes=3210 mapped_bytes_overlaps_kernel=2000', sample[2])
        self.assertIn('rollup pss_kib=888 swap_pss_kib=0 private_dirty_kib=777', sample[3])
        self.assertEqual(3, len(fatal))
        self.assertIn('uptime_ms=7189 process rss_kib=1234', fatal[0])
        self.assertIn('kernel_bytes=9999 imported_bytes=na mapped_bytes_overlaps_kernel=2000', fatal[2])
        self.assertIn('available_kib=na', missing[1])
        self.assertIn('kernel_bytes=na imported_bytes=na', missing[2])
        self.assertIn('pss_kib=na', missing[3])
