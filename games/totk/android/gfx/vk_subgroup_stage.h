/* Capability guard for translated shaders and the analysed TOTK visibility reductions. */
#ifndef TOTK_VK_SUBGROUP_STAGE_H
#define TOTK_VK_SUBGROUP_STAGE_H
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

enum {
    SG_NAME = 5, SG_EXT_IMPORT = 11, SG_EXT_INST = 12, SG_ENTRY = 15, SG_CAP = 17,
    SG_INT = 21, SG_VECTOR = 23, SG_TRUE = 41, SG_CONSTANT = 43, SG_CONSTANT_VECTOR = 44,
    SG_FUNCTION = 54, SG_VARIABLE = 59, SG_LOAD = 61, SG_STORE = 62, SG_DECORATE = 71,
    SG_MEMBER_DECORATE = 72, SG_EXTRACT = 81, SG_COPY = 83, SG_BITCAST = 124,
    SG_EQUAL = 170, SG_COUNT = 205, SG_ATOMIC_ADD = 234, SG_LABEL = 248, SG_BRANCH_IF = 250, SG_BALLOT = 339,
    SG_BUILTIN = 11, SG_HELPER = 23, SG_LANE = 41, SG_FRAG = 4, SG_LOCAL = 7,
};

/* These masks match VkSubgroupFeatureFlagBits; the parser does not use Vulkan headers. */
static inline uint32_t vk_subgroup_opcode_features(uint32_t op, const uint32_t *inst, uint32_t n) {
    if (op == 333) return 1;                        /* Elect */
    if (op >= 334 && op <= 336) return 2;            /* Vote */
    if (op >= 337 && op <= 344) return 8;            /* Ballot, broadcast */
    if (op == 345 || op == 346) return 16;           /* Shuffle, shuffle xor */
    if (op == 347 || op == 348) return 32;           /* Relative shuffle */
    if (op >= 349 && op <= 364) {
        if (n < 6) return UINT32_MAX;
        if (inst[4] == 3) return 4 | 64;             /* ClusteredReduce */
        if (inst[4] >= 6) return 4 | 256;            /* Partitioned operations */
        return 4;
    }
    if (op >= 365 && op <= 366) return 128;          /* Quad */
    if (op == 4421 || op == 4422 || op == 4432) return 8; /* Legacy KHR ballot */
    if (op >= 4428 && op <= 4430) return 2;          /* Legacy KHR vote */
    if (op == 4431) return n >= 7 ? 512 | 1024 : 512; /* RotateKHR */
    if (op == 5296) return 256;                     /* PartitionNV */
    return 0;
}

static inline uint32_t vk_subgroup_builtin_features(uint32_t value) {
    if (value >= 36 && value <= 41) return 1;
    if (value >= 4416 && value <= 4420) return 8;
    return 0;
}

typedef struct {
    const char *key;
    unsigned ballots, atomics;
} vk_sg_counter_contract;

/* The source key hashes Maxwell code and its control bytes. Each generated
   binding variant of these AGL fragment shaders performs the same masked
   counter reduction. Non-helper invocations ballot(true), elect the highest
   passing lane, and atomically add popcount to a common SSBO word. The result
   of that atomic is discarded. Native depth/discard behavior remains intact.
   Dependency variants alter texture or output types, not the reduction. The
   emitted instruction fingerprint below also guards translator drift. */
static inline const vk_sg_counter_contract *vk_sg_counter_for_variant(const char *variant) {
    static const vk_sg_counter_contract contracts[] = {
        {"8396359da421826d7825d0750f0bc816d4a1a99dd890246459286c510f95b3e5", 1, 1},
        {"9a5e3335514e4cbb1d08464239bc329a4991fa879d9c9a0ba9ec1b7e172dcf14", 2, 6},
        {"abbbf48f9b5e65987fbff7e4d9bdde656a8e20830cf0a5b33c8a4d5a0dbf8600", 2, 6},
        {"d04f1df4d9ba04719444bfe1405b8493192d11fbb8aa8f730af67f677abbdbbc", 1, 1},
    };
    if (!variant || strlen(variant) <= 64 || variant[64] != '-') return NULL;
    for (size_t i = 0; i < sizeof contracts / sizeof *contracts; i++)
        if (!memcmp(variant, contracts[i].key, 64)) return &contracts[i];
    return NULL;
}

static inline bool vk_sg_unused_input(const uint32_t *w, size_t nw, uint32_t input) {
    bool function = false;
    for (size_t i = 5; i < nw; ) {
        uint32_t n = w[i] >> 16, op = w[i] & 65535;
        if (op == SG_FUNCTION) function = true;
        if (function) for (uint32_t j = 1; j < n; j++) if (w[i+j] == input) return false;
        i += n;
    }
    return true;
}

typedef struct {
    const uint32_t *inst;
    uint32_t stored, loads;
} vk_sg_def;

/* Resolve the translator's SSA values and single-assignment Function locals.
   A local with multiple writes is deliberately opaque. */
static inline const uint32_t *vk_sg_def_of(const vk_sg_def *defs, uint32_t bound, uint32_t id) {
    for (unsigned hop = 0; hop < 16 && id < bound; hop++) {
        const uint32_t *inst = defs[id].inst;
        if (!inst) return NULL;
        uint32_t op = inst[0] & 65535, n = inst[0] >> 16;
        if ((op == SG_COPY || op == SG_BITCAST) && n == 4) id = inst[3];
        else if (op == SG_LOAD && n == 4 && inst[3] < bound && defs[inst[3]].stored &&
            defs[inst[3]].stored != UINT32_MAX) id = defs[inst[3]].stored;
        else return inst;
    }
    return NULL;
}

static inline uint32_t vk_sg_ballot_word(const vk_sg_def *defs, uint32_t bound, uint32_t id) {
    const uint32_t *word = vk_sg_def_of(defs, bound, id);
    if (!word || word[0] != (5u << 16 | SG_EXTRACT) || word[4]) return 0;
    const uint32_t *ballot = vk_sg_def_of(defs, bound, word[3]);
    return ballot && ballot[0] == (5u << 16 | SG_BALLOT) ? ballot[2] : 0;
}

/* The analysed Maxwell keys justify the invariant descriptor destination and
   helper/depth conditions. Check that every translated ballot still feeds
   exactly one highest-lane election and its population-count atomic add.
   The atomic result may be saved only to an unread local. */
static inline bool vk_sg_reductions(const uint32_t *w, size_t nw, uint32_t lane, uint32_t glsl, unsigned expected) {
    uint32_t bound = w[3];
    vk_sg_def *defs = calloc(bound, sizeof *defs);
    if (!defs) return false;
    bool valid = false;
    for (size_t i = 5; i < nw; ) {
        const uint32_t *inst = w + i;
        uint32_t n = inst[0] >> 16, op = inst[0] & 65535;
        i += n;
        switch (op) {
        case SG_VARIABLE: case SG_LOAD: case SG_COPY: case SG_BITCAST: case SG_EXTRACT:
        case SG_BALLOT: case SG_EXT_INST: case SG_COUNT: case SG_EQUAL:
            if (n < 3 || !inst[2] || inst[2] >= bound || defs[inst[2]].inst) goto done;
            defs[inst[2]].inst = inst;
            if (op == SG_LOAD && n >= 4 && inst[3] < bound) defs[inst[3]].loads++;
            break;
        case SG_STORE:
            if (n < 3 || inst[1] >= bound || inst[2] >= bound) goto done;
            if (!defs[inst[1]].inst || (defs[inst[1]].inst[0] & 65535) != SG_VARIABLE ||
                defs[inst[1]].inst[0] >> 16 < 4 || defs[inst[1]].inst[3] != SG_LOCAL) break;
            defs[inst[1]].stored = defs[inst[1]].stored ? UINT32_MAX : inst[2];
            break;
        default:
            break;
        }
    }
    uint32_t elected = 0, elected_ballot = 0, label = 0;
    unsigned matched = 0;
    uint32_t matched_ballots[4];
    for (size_t i = 5; i < nw; ) {
        const uint32_t *inst = w + i;
        uint32_t n = inst[0] >> 16, op = inst[0] & 65535;
        i += n;
        if (op == SG_LABEL && n == 2) label = inst[1];
        if (op == SG_BRANCH_IF && n >= 4) {
            const uint32_t *cond = vk_sg_def_of(defs, bound, inst[1]);
            if (!cond || cond[0] != (5u << 16 | SG_EQUAL)) continue;
            for (unsigned j = 3; j <= 4; j++) {
                const uint32_t *own = vk_sg_def_of(defs, bound, cond[j]);
                const uint32_t *max = vk_sg_def_of(defs, bound, cond[7-j]);
                if (!own || own[0] != (4u << 16 | SG_LOAD) || own[3] != lane ||
                    !max || max[0] != (6u << 16 | SG_EXT_INST) || max[3] != glsl || max[4] != 75) continue;
                elected_ballot = vk_sg_ballot_word(defs, bound, max[5]);
                elected = inst[2];
            }
        }
        if (op != SG_ATOMIC_ADD || n != 7) continue;
        const uint32_t *popcount = vk_sg_def_of(defs, bound, inst[6]);
        if (!popcount || popcount[0] != (4u << 16 | SG_COUNT)) continue;
        uint32_t ballot = vk_sg_ballot_word(defs, bound, popcount[3]);
        if (!ballot) continue;
        if (ballot != elected_ballot || label != elected || matched >= sizeof matched_ballots / sizeof *matched_ballots)
            goto done;
        for (unsigned j = 0; j < matched; j++) if (matched_ballots[j] == ballot) goto done;
        matched_ballots[matched++] = ballot;
        /* These generated IDs are used by at most a dead Function store. */
        for (size_t j = i; j < nw; ) {
            const uint32_t *use = w + j;
            uint32_t width = use[0] >> 16, use_op = use[0] & 65535;
            j += width;
            if (use_op == SG_STORE && width == 3 && use[2] == inst[2] && use[1] < bound &&
                defs[use[1]].stored == inst[2] && !defs[use[1]].loads) continue;
            for (uint32_t p = 1; p < width; p++) if (use[p] == inst[2]) goto done;
        }
    }
    valid = matched == expected;
done:
    free(defs);
    return valid;
}

/* This is an identity change for an unused input, or a singleton lane for a
   proven counter. Remove the builtin and interface declarations as well as its
   use, so validation and drivers see no unsupported stage instructions. */
static inline int vk_sg_lower(uint32_t **words, size_t *count, uint32_t lane, uint32_t scalar, uint32_t vector) {
    const uint32_t *w = *words;
    size_t nw = *count, k = 5;
    uint32_t id = w[3], zero = id, one = id + 1, mask = id + 2;
    if (vector) id += 3;
    if (nw > SIZE_MAX / sizeof(uint32_t) - 15) return -1;
    uint32_t *out = malloc((nw + 15) * sizeof *out);
    if (!out) return -1;
    memcpy(out, w, k * sizeof *w);
#define SG_EMIT(op, ...) do { \
    const uint32_t args[] = {__VA_ARGS__}; size_t len = sizeof args / sizeof *args; \
    out[k++] = ((uint32_t)(len + 1) << 16) | (op); \
    memcpy(out + k, args, sizeof args); k += len; \
} while (0)
    bool declared = false;
    for (size_t i = 5; i < nw; ) {
        const uint32_t *inst = w + i;
        uint32_t n = inst[0] >> 16, op = inst[0] & 65535;
        i += n;
        if (vector && !declared && op == SG_FUNCTION) {
            SG_EMIT(SG_CONSTANT, scalar, zero, 0);
            SG_EMIT(SG_CONSTANT, scalar, one, 1);
            SG_EMIT(SG_CONSTANT_VECTOR, vector, mask, one, zero, zero, zero);
            declared = true;
        }
        if ((op == SG_DECORATE || op == SG_NAME) && n >= 2 && inst[1] == lane) continue;
        if (op == SG_VARIABLE && n >= 4 && inst[2] == lane) continue;
        if (op == SG_CAP && n == 2 && inst[1] >= 61 && inst[1] <= 68) continue;
        if (op == SG_LOAD && n == 4 && inst[3] == lane) {
            SG_EMIT(SG_COPY, inst[1], inst[2], zero);
        } else if (op == SG_BALLOT) {
            SG_EMIT(SG_COPY, inst[1], inst[2], mask);
        } else if (op == SG_ENTRY) {
            size_t start = k++;
            uint32_t j = 1;
            for (; j < 3; j++) out[k++] = inst[j];
            for (; j < n; ) {
                uint32_t word = out[k++] = inst[j++];
                if (!(word & 0xff) || !(word & 0xff00) || !(word & 0xff0000) || !(word & 0xff000000)) break;
            }
            for (; j < n; j++) if (inst[j] != lane) out[k++] = inst[j];
            out[start] = ((uint32_t)(k - start) << 16) | op;
        } else {
            memcpy(out + k, inst, n * sizeof *out);
            k += n;
        }
    }
#undef SG_EMIT
    out[3] = id;
    free(*words);
    *words = out;
    *count = k;
    return vector ? 1 : 2;
}

/* 0: native, 1: analysed visibility reduction, 2: unused input removed,
   -1: unsupported or malformed. Stage mismatch supplies allowed=0. */
static inline int vk_spirv_stage_subgroups(uint32_t **words, size_t *count, uint32_t allowed,
        const vk_sg_counter_contract *counter, uint32_t *required) {
    const uint32_t *w = *words;
    size_t nw = *count;
    uint32_t input = 0, builtin = 0, helper = 0, vector = 0, scalar = 0, glsl = 0;
    unsigned ballots = 0, finds = 0, counts = 0, atomics = 0, loads = 0, entries = 0;
    bool fragment = false, other_subgroups = false, valid_ballots = true, has_function = false;
    *required = 0;
    if (nw < 5 || w[0] != 0x07230203 || !w[3] || w[3] > (1u << 22)) return -1;
    for (size_t i = 5; i < nw; ) {
        const uint32_t *inst = w + i;
        uint32_t n = inst[0] >> 16, op = inst[0] & 65535;
        if (!n || n > nw - i) return -1;
        if (op == SG_ENTRY) {
            if (n < 4) return -1;
            entries++;
            fragment = inst[1] == SG_FRAG;
        }
        if (op == SG_EXT_IMPORT && n == 6 && !memcmp(inst + 2, "GLSL.std.450", sizeof "GLSL.std.450")) glsl = inst[1];
        if (op == SG_FUNCTION) has_function = true;
        if (op == SG_DECORATE && n == 4 && inst[2] == SG_BUILTIN) {
            uint32_t need = vk_subgroup_builtin_features(inst[3]);
            *required |= need;
            if (inst[3] == SG_HELPER) helper = inst[1];
            if (need) {
                if (input || !inst[1] || inst[1] >= w[3]) other_subgroups = true;
                else { input = inst[1]; builtin = inst[3]; }
            }
        }
        if (op == SG_MEMBER_DECORATE && n == 5 && inst[3] == SG_BUILTIN) {
            uint32_t need = vk_subgroup_builtin_features(inst[4]);
            *required |= need;
            if (need) other_subgroups = true;
        }
        uint32_t features = vk_subgroup_opcode_features(op, inst, n);
        *required |= features;
        if (features && op != SG_BALLOT) other_subgroups = true;
        if (op == SG_BALLOT) {
            if (n != 5) return -1;
            if (vector && vector != inst[1]) valid_ballots = false;
            vector = inst[1];
            ballots++;
        }
        if (op == SG_EXT_INST && n == 6 && glsl && inst[3] == glsl && inst[4] == 75) finds++;
        if (op == SG_COUNT) counts++;
        if (op == SG_ATOMIC_ADD) atomics++;
        if (op == SG_LOAD && n == 4 && input && inst[3] == input) loads++;
        i += n;
    }
    if (!(*required & ~allowed)) return 0;
    if (other_subgroups || !input || entries != 1 || !has_function) return -1;
    if (!ballots && vk_sg_unused_input(w, nw, input)) return vk_sg_lower(words, count, input, 0, 0);
    if (!counter || !fragment || !helper || builtin != SG_LANE || !valid_ballots ||
        ballots != counter->ballots || finds != ballots || counts != ballots ||
        atomics != counter->atomics || loads != ballots) return -1;
    /* Match predicate and vector layout, including translator variants with
       duplicate boolean constants. The second pass runs only on this allowlist. */
    uint32_t true_ids[8], true_count = 0;
    for (size_t i = 5; i < nw; ) {
        uint32_t n = w[i] >> 16, op = w[i] & 65535;
        if (op == SG_TRUE && n == 3) {
            if (true_count == sizeof true_ids / sizeof *true_ids) return -1;
            true_ids[true_count++] = w[i+2];
        }
        if (op == SG_VECTOR && n == 4 && w[i+1] == vector && w[i+3] == 4) scalar = w[i+2];
        if (op == SG_BALLOT) {
            bool literal_true = false;
            for (uint32_t j = 0; j < true_count; j++) if (true_ids[j] == w[i+4]) literal_true = true;
            if (!literal_true) return -1;
        }
        /* Reject unexpected uses of the subgroup input pointer. */
        if (op == SG_LOAD && n >= 4 && w[i+3] == input) {
            if (!scalar || w[i+1] != scalar || n != 4) return -1;
        }
        i += n;
    }
    if (!scalar || !vk_sg_reductions(w, nw, input, glsl, ballots)) return -1;
    for (size_t i = 5; i < nw; ) {
        uint32_t n = w[i] >> 16;
        if ((w[i] & 65535) == SG_INT && n == 4 && w[i+1] == scalar && w[i+2] == 32 && !w[i+3])
            return vk_sg_lower(words, count, input, scalar, vector);
        i += n;
    }
    return -1;
}
#endif
