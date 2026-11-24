"""Stage compatibility under a compute-only Vulkan subgroup capability mask."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GFX = ROOT / 'games/totk/android/gfx'
COUNTER = '8396359da421826d7825d0750f0bc816d4a1a99dd890246459286c510f95b3e5-fixture'

PROBE = r'''
#include <assert.h>
#include <stdio.h>
#include "vk_subgroup_stage.h"
int main(int argc, char **argv) {
    assert(argc == 5);
    FILE *f = fopen(argv[1], "rb");
    assert(f && !fseek(f, 0, SEEK_END));
    long bytes = ftell(f);
    assert(bytes >= 20 && bytes % 4 == 0);
    rewind(f);
    uint32_t *w = malloc((size_t)bytes), *original = w;
    assert(w && fread(w, 1, (size_t)bytes, f) == (size_t)bytes && !fclose(f));
    size_t count = (size_t)bytes / 4, original_count = count;
    uint32_t required = 0;
    int status = vk_spirv_stage_subgroups(&w, &count, (uint32_t)strtoul(argv[3], NULL, 0),
                                        vk_sg_counter_for_variant(argv[4]), &required);
    if (status <= 0) assert(w == original && count == original_count);
    fprintf(stderr, "status=%d required=0x%x\n", status, required);
    if (status >= 0) {
        f = fopen(argv[2], "wb");
        assert(f && fwrite(w, 4, count, f) == count && !fclose(f));
    }
    free(w);
    return status < 0 ? 2 : 0;
}
'''


@unittest.skipUnless(all(shutil.which(x) for x in ('cc', 'spirv-as', 'spirv-val', 'spirv-dis')),
                     'needs C compiler and SPIRV-Tools')
class SubgroupStageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.path = Path(cls.temp.name)
        cls.probe = cls.path / 'probe'
        source = cls.path / 'probe.c'
        source.write_text(PROBE)
        subprocess.run(['cc', '-std=c11', '-Wall', '-Wextra', '-Werror', f'-I{GFX}',
                        str(source), '-o', str(cls.probe)], check=True)
        cls.counter = (GFX / 'subgroup_counter_test.spvasm').read_text()

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def rewrite(self, assembly, allowed=0, variant=COUNTER, expected=1):
        asm, original = self.path / 'fixture.spvasm', self.path / 'fixture.spv'
        output = self.path / 'rewritten.spv'
        output.unlink(missing_ok=True)
        asm.write_text(assembly)
        subprocess.run(['spirv-as', '--target-env', 'vulkan1.2', str(asm), '-o', str(original)], check=True)
        subprocess.run(['spirv-val', '--target-env', 'vulkan1.2', str(original)], check=True)
        result = subprocess.run([str(self.probe), str(original), str(output), str(allowed), variant],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 2 if expected < 0 else 0, result.stderr)
        self.assertIn(f'status={expected} ', result.stderr)
        if expected < 0:
            self.assertFalse(output.exists())
            return ''
        subprocess.run(['spirv-val', '--target-env', 'vulkan1.2', str(output)], check=True)
        if expected == 0:
            self.assertEqual(original.read_bytes(), output.read_bytes())
        return subprocess.check_output(['spirv-dis', str(output)], text=True)

    def test_compute_only_device_rewrites_fragment_counter(self):
        output = self.rewrite(self.counter)
        self.assertNotIn('Subgroup', output)
        self.assertIn('OpAtomicIAdd', output)
        self.assertIn('OpConstantComposite', output)
        self.assertIn('FindUMsb', output)
        self.assertIn('EarlyFragmentTests', output)
        self.assertIn('HelperInvocation', output)

    def test_literal_ballot_and_exact_shader_key_required(self):
        self.rewrite(self.counter, variant='unrecognised', expected=-1)
        self.rewrite(self.counter.replace('%scope %true', '%scope %is_helper'), expected=-1)
        self.rewrite(self.counter.replace('FindUMsb', 'FindILsb'), expected=-1)

    def test_supported_stage_keeps_native_modules(self):
        self.rewrite(self.counter, allowed=9, expected=0)
        compute = (GFX / 'subgroup_test.spvasm').read_text()
        self.rewrite(compute, allowed=0x67f, expected=0)

    def test_refuses_unproven_cross_lane_shuffles_and_changed_instruction_count(self):
        compute = (GFX / 'subgroup_test.spvasm').read_text()
        self.rewrite(compute, expected=-1)
        self.rewrite(self.counter.replace('%one %zero %number', '%one %zero %one'), expected=-1)
        self.rewrite(self.counter.replace('OpBranch %done\n%done',
                     'OpStore %ptr %result\nOpBranch %done\n%done'), expected=-1)
        self.rewrite(self.counter.replace('OpBranch %done\n%done',
                     '%extra = OpAtomicIAdd %uint %ptr %one %zero %one\nOpBranch %done\n%done'), expected=-1)

    def test_unused_stage_interface_is_removed(self):
        assembly = self.counter.split('%main = OpFunction')[0]
        assembly += '%main = OpFunction %void None %fn\n%entry = OpLabel\nOpReturn\nOpFunctionEnd\n'
        output = self.rewrite(assembly, expected=2)
        self.assertNotIn('Subgroup', output)
        self.assertNotIn('lane', output)
        self.assertIn('HelperInvocation', output)

    def test_counter_model(self):
        # For a 32-lane masked warp, the old elected lane adds popcount.
        # The scalar rewrite elects each passing lane and adds one per lane.
        for mask in (0, 1, 0x80000000, 0x90002100, 0xffffffff):
            bits = [int(bool(mask & (1 << lane))) for lane in range(32)]
            last = mask.bit_length() - 1
            native = sum(sum(bits) for lane in range(32) if lane == last)
            scalar = sum(bits)
            self.assertEqual(native, scalar)


if __name__ == '__main__':
    unittest.main()
