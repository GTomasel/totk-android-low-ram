"""Check that pool retirement cannot replace newly registered fd-backed pages."""

from pathlib import Path
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
TOTK = ROOT / 'games/totk'


def section(source, start, end):
    first = source.index(start)
    return source[first:source.index(end, first + len(start))]


@unittest.skipUnless(sys.platform.startswith('linux'), 'needs Linux memfd and mremap')
class PoolReregister(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compiler = shlex.split(os.environ.get('CC', 'cc'))
        if not compiler or not shutil.which(compiler[0]):
            raise unittest.SkipTest('a host C compiler is required')

        work = tempfile.TemporaryDirectory(prefix='pool-reregister-')
        cls.addClassCleanup(work.cleanup)
        directory = Path(work.name)
        source = (TOTK / 'android/gfx/nvn_vk.c').read_text()
        registry = section(source, 'enum { K_OWN', 'static _Atomic uint64_t g_pool_shmem_copy_bytes')
        lookup = section(source, 'static uintptr_t page_sz(', 'static VkBuffer make_buffer(')
        aliases = section(source, 'static void alias_replace(uintptr_t va, uintptr_t end, pool_ent *owner, uint64_t off) {',
                          'static uint64_t pool_fd_offset(')
        (directory / 'pool_registry.inc').write_text(registry + lookup + aliases)
        subject = section(source, 'static void restore_pool_pages(', '/* Write-back retains a registration identity')

        # Move the real restoration after unlocked view retirement to
        # demonstrate that the rendezvous detects clobbered replacement pages.
        restore = '        restore_pool_pages(e);\n'
        retire = '        vk_res_buffer_released(e->buf);\n'
        if subject.count(restore) != 1 or subject.count(retire) != 1:
            raise AssertionError('update the late-restoration control for the current pool lifecycle')
        late_restore = (subject.replace(restore, '').replace(
            retire, retire + '        pthread_mutex_lock(&g_pool_mu);\n' +
            restore + '        pthread_mutex_unlock(&g_pool_mu);\n'))

        flags = [arg for name in ('CPPFLAGS', 'CFLAGS', 'LDFLAGS')
                 for arg in shlex.split(os.environ.get(name, ''))]
        cls.binaries = {}
        for label, code in (('production', subject), ('late-restore', late_restore)):
            (directory / 'pool_retirement.inc').write_text(code)
            binary = cls.binaries[label] = directory / label
            built = subprocess.run(
                [*compiler, '-std=gnu11', '-O2', '-Wall', '-Wextra', '-Werror', '-pthread',
                 *flags, '-UNDEBUG', '-I', str(directory),
                 str(TOTK / 'tests/pool_finalize/reregister.c'), '-o', str(binary)],
                capture_output=True, text=True, timeout=60)
            if built.returncode:
                raise AssertionError(built.stdout + built.stderr)

    def check_order(self, binary, expected_restored, expected_shared):
        for alias in ('0', '1'):
            with self.subTest(alias=alias):
                run = subprocess.run([str(self.binaries[binary]), alias],
                                     capture_output=True, text=True, timeout=30)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                self.assertEqual(run.stdout.strip(), f'restored={expected_restored} shared={expected_shared}')

    def test_restore_before_unlocked_view_retirement(self):
        self.check_order('production', 1, 1)

    def test_late_restoration_control_clobbers_replacement(self):
        self.check_order('late-restore', 0, 0)
