package com.aure.totk.setup;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/** Launch-only diagnostics. An empty preference defers to the ordinary game's profile and runtime defaults. */
final class AdvancedSettings {
    enum Group { COMPATIBILITY, RENDERING, PERFORMANCE, LOGGING }

    static final class Option {
        final Group group;
        final String key, label, description;
        final String[] values, labels;
        final boolean requiresNoFrameGen;
        private final String envKey;

        Option(Group group, String key, String envKey, String label, String description,
               boolean requiresNoFrameGen, String... choices) {
            this.group = group;
            this.key = key;
            this.envKey = envKey;
            this.label = label;
            this.description = description;
            this.requiresNoFrameGen = requiresNoFrameGen;
            values = new String[1 + choices.length / 2];
            labels = new String[values.length];
            values[0] = "";
            labels[0] = "Default";
            for (int i = 1; i < values.length; i++) {
                values[i] = choices[(i - 1) * 2];
                labels[i] = choices[(i - 1) * 2 + 1];
            }
        }

        private boolean accepts(Object candidate) {
            return candidate instanceof String && Arrays.asList(values).contains(candidate);
        }
    }

    private static final Group COMPATIBILITY = Group.COMPATIBILITY, RENDERING = Group.RENDERING,
            PERFORMANCE = Group.PERFORMANCE, LOGGING = Group.LOGGING;
    private static final String TURNIP = "TOTK_MENU_TU_DEBUG", OPTIMIZATIONS = "TOTK_VK_OFF";

    static final Option[] OPTIONS = {
        option(COMPATIBILITY, "TOTK_VK_PRESENT", "Presenter",
                "Copy uses GPU readback instead of handing a shared buffer to Android. Useful for black screens.", true,
                "copy", "Copy", "ahb", "Shared buffer"),
        flag(COMPATIBILITY, "turnip_render", TURNIP, "Turnip render path",
                "Sysmem forces rendering in system memory. Try this on Adreno 650 if the game loses the GPU.",
                "sysmem", "Sysmem", "gmem", "GMEM"),
        flag(COMPATIBILITY, "turnip_binning", TURNIP, "Turnip binning",
                "Disable hardware binning to test the driver's tile setup path.", "nobin", "Off"),
        flag(COMPATIBILITY, "turnip_lrz", TURNIP, "Turnip depth rejection",
                "Disable early depth rejection (LRZ) to diagnose missing geometry or driver faults.", "nolrz", "Off"),
        flag(COMPATIBILITY, "turnip_cs_slabs", TURNIP, "Turnip command stream pooling",
                "Use dedicated command buffers in bundled Turnip to test KGSL command-stream storage.", "noslabcs", "Off"),
        toggle(COMPATIBILITY, "TOTK_VK_SUBGROUP_FIX", "32-lane subgroups",
                "Translate subgroup operations to the game's 32-lane model. Unsupported shader stages can be skipped."),
        toggle(COMPATIBILITY, "TOTK_VK_VARYING_FIX", "Missing shader outputs",
                "Supply the game's default inputs when a vertex shader leaves them unwritten. Affects wind effects."),
        toggle(COMPATIBILITY, "TOTK_VK_NAN_MINMAX", "Shader NaN handling",
                "Apply the game's min/max behavior for invalid floating-point values. Changes the shader cache policy."),
        toggle(COMPATIBILITY, "TOTK_VK_INT_FOLD", "Shader integer folding",
                "Simplify proven bits of shader integer arithmetic. Turn it off to compare with the original instructions."),
        option(COMPATIBILITY, "TOTK_VK_REC_THREADS", "Recorder workers",
                "Off uses the single-thread recording path. Try it to isolate parallel recording.", false,
                "0", "Off", "1", "1", "2", "2", "3", "3", "4", "4"),
        timing(COMPATIBILITY, "TOTK_VK_SYNC", "Wait for every GPU submission",
                "Waits for GPU work instead of overlapping submissions. It can slow the game considerably."),
        option(COMPATIBILITY, "TOTK_VK_FRAMES_AHEAD", "Queued frames",
                "Limit how far the renderer can queue ahead to isolate synchronization problems.", true, "1", "One frame"),
        flag(COMPATIBILITY, "descriptor_reuse", OPTIMIZATIONS, "Descriptor reuse",
                "Disable reuse of matching descriptor sets to test stale resource bindings.", "ds", "Off"),
        flag(COMPATIBILITY, "settled_writeback", OPTIMIZATIONS, "Settled write-back",
                "Disable the deferred GPU-to-guest texture write-back optimization.", "settledwb", "Off"),

        timing(RENDERING, "TOTK_FRAME_LAG", "Render frame lag",
                "Allow graphics recording to overlap the guest CPU with protected guest buffers."),
        timing(RENDERING, "TOTK_PRESENT_SLOTS", "Fixed present slots",
                "Schedule output on display slots. Off uses the legacy presenter timing."),
        option(RENDERING, "TOTK_ACQUIRE_CLOCK", "Texture acquire clock",
                "Use a display-vsync clock for texture acquisition. Off uses the game's present-based timing.", true,
                "0", "Off", "vsync", "Display vsync"),
        option(RENDERING, "TOTK_GPU_TIME", "GPU timing reported to the game",
                "Latency reports elapsed time. Hardware uses GPU timestamps when the driver supports them.", true,
                "latency", "Latency", "hardware", "Hardware"),
        toggle(RENDERING, "TOTK_PRESENT_PREROTATE", "Pre-rotate shared output",
                "Rotate shared buffers before presenting to Android on a compatible display."),
        toggle(RENDERING, "TOTK_PRESENT_UBWC", "Compressed shared output",
                "Request UBWC for Android shared buffers on qualified drivers. The renderer can fall back to linear."),
        toggle(RENDERING, "TOTK_VK_PIPELINE_LIBRARY", "Graphics pipeline library",
                "Allow the driver to compile graphics pipelines from reusable parts when supported."),
        timing(RENDERING, "TOTK_VK_FEEDBACK_CUT", "Render-target feedback handling",
                "Allow a narrower image view when a pass samples its own attachment."),
        option(RENDERING, "TOTK_VK_SPLIT_PASS_DRAWS", "Long render-pass splitting",
                "Split long passes at a draw threshold to keep recording work moving. This can reduce GPU tile reuse.", true,
                "0", "Off", "128", "128 draws", "256", "256 draws", "512", "512 draws"),

        option(PERFORMANCE, "TOTK_VK_PIPELINE_CACHE", "Pipeline cache",
                "Off skips the normal saved Vulkan pipeline cache. Existing cache files are kept.", false,
                "0", "Off"),
        toggle(PERFORMANCE, "TOTK_PSO_PREWARM", "Graphics pipeline prewarm",
                "Build known pipelines before play. Can lengthen startup or increase memory use."),
        option(PERFORMANCE, "TOTK_VK_COMPILE_THREADS", "Pipeline compile workers",
                "Off builds missing graphics pipelines on demand. More workers can use more CPU and memory.", false,
                "0", "Off", "1", "1", "2", "2", "4", "4"),
        toggle(PERFORMANCE, "TOTK_VK_IMAGE_FREE_EMPTY", "Release empty image blocks",
                "Return fully unused Vulkan memory blocks to the driver."),
        toggle(PERFORMANCE, "TOTK_VK_IMAGE_RECLAIM_BEFORE_GROW", "Reclaim before image allocations",
                "Try to release eligible images before requesting a larger allocation."),
        toggle(PERFORMANCE, "TOTK_VK_IMAGE_PACK", "Pack image allocations",
                "Try to fit images into existing blocks. This can change memory use and loading times."),
        option(PERFORMANCE, "TOTK_VK_IMAGE_BLOCK_MB", "Image block size",
                "Try smaller Vulkan image-memory blocks when memory is fragmented.", false,
                "16", "16 MiB", "32", "32 MiB", "64", "64 MiB"),
        option(PERFORMANCE, "TOTK_VK_STAGING_RETAIN_MB", "Upload buffers to keep",
                "Limit how much idle upload staging each submission slot retains.", false,
                "4", "4 MiB", "8", "8 MiB", "16", "16 MiB", "32", "32 MiB"),
        toggle(PERFORMANCE, "TOTK_VK_STAGING_BURST_TRIM", "Trim burst upload buffers",
                "Return eligible upload staging from bursts to the system sooner."),
        toggle(PERFORMANCE, "TOTK_VK_DESC_COMPACT", "Compact descriptor pools",
                "Use smaller descriptor pools when possible to reduce retained memory."),
        toggle(PERFORMANCE, "TOTK_VK_DESC_BATCH", "Batch descriptor allocation",
                "Allocate sets in small batches to reduce calls into the graphics driver."),
        toggle(PERFORMANCE, "TOTK_WB_ALIAS", "Private write-back mapping",
                "Use a private writable mapping for supported GPU-to-guest copies. Experimental."),

        toggle(LOGGING, "TOTK_LOGCAT", "Mirror game log to logcat",
                "Copy native diagnostics to Android logcat as well as totk.log."),
        toggle(LOGGING, "TOTK_FRAME_TIMELINE", "Frame timeline",
                "Record frame-by-frame timing and contention summaries in totk.log."),
        toggle(LOGGING, "TOTK_VK_REC_PROFILE", "Recorder CPU timing",
                "Record detailed command recording times. This can affect performance."),
        toggle(LOGGING, "TOTK_VK_XLATE_STATS", "Shader translation statistics",
                "Record timing and activity of shader translation and loading."),
        toggle(LOGGING, "TOTK_VK_IMAGE_PEAK_LOG", "Image memory peaks",
                "Log notable increases in committed Vulkan image memory."),
        toggle(LOGGING, "TOTK_THREAD_SAMPLER", "Sample active threads",
                "Record threads active around long frames. This can affect timing."),
        toggle(LOGGING, "TOTK_INPUT_LOG", "Controller shortcut log",
                "Record the game's performance-overlay shortcut decisions.")
    };

    private final Map<String, String> values = new LinkedHashMap<>();

    AdvancedSettings(Map<String, ?> stored) {
        if (stored == null) return;
        for (Option option : OPTIONS) {
            Object value = stored.get(option.key);
            if (option.accepts(value)) set(option, (String) value);
        }
    }

    String get(Option option) { return values.getOrDefault(option.key, ""); }

    void set(Option option, String value) {
        if (!option.accepts(value)) throw new IllegalArgumentException(option.key);
        if (value.isEmpty()) values.remove(option.key);
        else values.put(option.key, value);
    }

    int count() { return values.size(); }

    String valueLabel(Option option) {
        return option.labels[Arrays.asList(option.values).indexOf(get(option))];
    }

    /** Replace assignments already written by the graphics profile, preserving the file header and data directory. */
    String applyTo(String base, boolean frameGen) {
        Map<String, String> overrides = new LinkedHashMap<>();
        StringJoiner turnip = new StringJoiner(","), disabled = new StringJoiner(",");
        for (Option option : OPTIONS) {
            if (frameGen && option.requiresNoFrameGen) continue;
            String value = get(option);
            if (value.isEmpty()) continue;
            if (option.envKey.equals(TURNIP)) turnip.add(value);
            else if (option.envKey.equals(OPTIMIZATIONS)) disabled.add(value);
            else overrides.put(option.envKey, value);
        }
        if (turnip.length() != 0) overrides.put(TURNIP, turnip.toString());
        if (disabled.length() != 0) overrides.put(OPTIMIZATIONS, disabled.toString());
        if (overrides.isEmpty()) return base;

        StringBuilder out = new StringBuilder();
        for (String line : base.split("\\n")) {
            int eq = line.indexOf('=');
            String key = eq < 0 ? "" : line.substring(0, eq);
            if (overrides.containsKey(key)) line = key + "=" + overrides.remove(key);
            out.append(line).append('\n');
        }
        overrides.forEach((key, value) -> out.append(key).append('=').append(value).append('\n'));
        return out.toString();
    }

    private static Option option(Group group, String key, String label, String description,
                                 boolean requiresNoFrameGen, String... choices) {
        return new Option(group, key, key, label, description, requiresNoFrameGen, choices);
    }

    private static Option toggle(Group group, String key, String label, String description) {
        return option(group, key, label, description, false, "1", "On", "0", "Off");
    }

    private static Option timing(Group group, String key, String label, String description) {
        return option(group, key, label, description, true, "1", "On", "0", "Off");
    }

    private static Option flag(Group group, String key, String envKey, String label, String description,
                               String... choices) {
        return new Option(group, key, envKey, label, description, false, choices);
    }
}
