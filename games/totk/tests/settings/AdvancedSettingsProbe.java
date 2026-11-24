package com.aure.totk.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class AdvancedSettingsProbe {
    public static void main(String[] args) throws Exception {
        if ("choices".equals(args[0])) {
            for (AdvancedSettings.Option option : AdvancedSettings.OPTIONS) {
                require(option.values.length == option.labels.length, option.key);
                require("".equals(option.values[0]), option.key);
                for (int i = 1; i < option.values.length; i++) {
                    AdvancedSettings advanced = new AdvancedSettings(Map.of(option.key, option.values[i]));
                    require(advanced.count() == 1, option.key);
                    require(advanced.valueLabel(option).equals(option.labels[i]), option.key);
                    System.out.print(advanced.applyTo("", false).stripLeading());
                }
            }
            return;
        }

        AdvancedSettings.Option presenter = option("TOTK_VK_PRESENT");
        AdvancedSettings.Option copyCache = option("TOTK_VK_PIPELINE_CACHE");
        AdvancedSettings empty = new AdvancedSettings(Map.of(
                "TU_DEBUG", "sysmem",
                presenter.key, "copy\nTOTK_VK_DRIVER=system",
                copyCache.key, "/untrusted/file",
                "TOTK_FRAME_TIMELINE", 1));
        Settings baseSettings = new Settings(Map.of(), null);
        String base = baseSettings.env("en-GB", "/volume/data");
        require(empty.count() == 0 && empty.applyTo(base, false).equals(base), "defaults and invalid preferences");
        require(empty.valueLabel(presenter).equals("Default"), "default choice");
        try {
            empty.set(presenter, "auto");
            throw new AssertionError("invalid value accepted");
        } catch (IllegalArgumentException expected) { }

        AdvancedSettings advanced = new AdvancedSettings(Map.of(
                presenter.key, "copy",
                "turnip_render", "sysmem",
                "turnip_binning", "nobin",
                "descriptor_reuse", "ds",
                "settled_writeback", "settledwb",
                "TOTK_FRAME_TIMELINE", "1"));
        require(advanced.count() == 6 && "Copy".equals(advanced.valueLabel(presenter)), "persisted choices");
        String combined = advanced.applyTo(base, false);
        require(combined.lines().filter(line -> !line.startsWith("#")).findFirst().get()
                .equals("TOTK_DATA_DIR=/volume/data"), "data directory remains first");
        require(combined.contains("\nTOTK_VK_PRESENT=copy\n"), "copy");
        require(combined.contains("\nTOTK_MENU_TU_DEBUG=sysmem,nobin\n"), "combined Turnip flags");
        require(combined.contains("\nTOTK_VK_OFF=ds,settledwb\n"), "combined disabled optimizations");
        require(!combined.contains("\nTU_DEBUG="), "menu-specific Turnip input");
        advanced.set(option("turnip_render"), "");
        require(advanced.applyTo(base, false).contains("\nTOTK_MENU_TU_DEBUG=nobin\n"), "individual reset");
        require(advanced.count() == 5, "reset count");

        advanced.set(option("TOTK_PRESENT_SLOTS"), "1");
        advanced.set(option("TOTK_GPU_TIME"), "latency");
        advanced.set(option("TOTK_VK_SPLIT_PASS_DRAWS"), "0");
        Settings fast = new Settings(Map.of("frame_rate", "60", "gpu_feed", "on"), null);
        String replaced = advanced.applyTo(fast.env("en-GB"), fast.frameGen());
        require(replaced.lines().filter(line -> line.startsWith("TOTK_GPU_TIME=")).toList()
                .equals(java.util.List.of("TOTK_GPU_TIME=latency")), "replace GPU time");
        require(replaced.lines().filter(line -> line.startsWith("TOTK_VK_SPLIT_PASS_DRAWS=")).toList()
                .equals(java.util.List.of("TOTK_VK_SPLIT_PASS_DRAWS=0")), "replace pass split");

        for (String mode : new String[]{"on", "lsfg"}) {
            Settings frameGen = new Settings(Map.of("frame_gen", mode), null);
            String locked = advanced.applyTo(frameGen.env("en-GB"), frameGen.frameGen());
            require(locked.lines().filter(line -> line.startsWith("TOTK_VK_PRESENT=")).toList()
                    .equals(java.util.List.of("TOTK_VK_PRESENT=ahb")), "frame generation uses its shared-buffer presenter");
            require(!locked.contains("TOTK_GPU_TIME=latency"), "locked GPU timing");
            require(!locked.contains("TOTK_VK_SPLIT_PASS_DRAWS="), "locked pass split");
            require(locked.contains("\nTOTK_FRAME_TIMELINE=1\n"), "frame generation keeps diagnostics");
        }
        require("copy".equals(advanced.get(presenter)) && advanced.applyTo(base, false).contains("TOTK_VK_PRESENT=copy"),
                "locked overrides retained");

        Path path = Path.of(args[1]);
        Settings.writeAtomic(path.toFile(), "settings.env", combined);
        require(Files.readString(path.resolve("settings.env")).equals(combined), "settings export");
        Settings.writeAtomic(path.toFile(), "settings.env", empty.applyTo(base, false));
        require(Files.readString(path.resolve("settings.env")).equals(base), "reset export");
        require(!Files.exists(path.resolve("settings.env.tmp")), "atomic export cleanup");
    }

    private static AdvancedSettings.Option option(String key) {
        for (AdvancedSettings.Option option : AdvancedSettings.OPTIONS) {
            if (option.key.equals(key)) return option;
        }
        throw new AssertionError("Unknown option " + key);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
