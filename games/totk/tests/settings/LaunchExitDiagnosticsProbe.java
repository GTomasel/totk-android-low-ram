package com.aure.totk.setup;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class LaunchExitDiagnosticsProbe {
    private static final String GAME = "com.gtomasel.totk";
    private static final long T0 = Instant.parse("2026-11-24T19:49:04Z").toEpochMilli();

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }

    private static String header(long offset, int pid) {
        return "[totk-log] ===== Launch " + Instant.ofEpochMilli(T0 + offset) + " (pid " + pid + ") =====\n";
    }

    private static LaunchExitDiagnostics scan(String log) {
        LaunchExitDiagnostics diagnostics = new LaunchExitDiagnostics();
        byte[] bytes = log.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i += 5) {
            byte[] chunk = Arrays.copyOfRange(bytes, i, Math.min(i + 5, bytes.length));
            diagnostics.scan(chunk, chunk.length);
        }
        return diagnostics;
    }

    private static ApplicationExitInfo exit(String process, int pid, long offset, int reason, int status, long rss, long pss) {
        return new ApplicationExitInfo(process, pid, T0 + offset, reason, status, rss, pss);
    }

    public static void main(String[] args) {
        ActivityManager manager = new ActivityManager();
        Context context = new Context(manager);
        switch (args[0]) {
            case "correlate" -> {
                LaunchExitDiagnostics diagnostics = scan(header(0, 701) + "renderer\n" + header(4000, 702) + header(8000, 701));
                manager.first = List.of(exit(GAME, 701, 600_000, 6, 0, 21, 21),
                        exit(GAME, 702, 18_010, 6, 0, 20, 20), // PID reused after the following launch
                        exit(GAME, 701, 10_050, 3, 0, 3_081_000, 1_938_100),
                        exit(GAME + ":setup", 702, 7000, 4, 0, 2, 2),
                        exit(GAME, 702, 4100, 2, 9, 0, 0),
                        exit(GAME, 701, 2000, 5, 6, 1_930_000, 0),
                        exit(GAME, 701, -1, 1, 123, 1, 1),
                        exit(GAME, 703, 2000, 1, 123, 1, 1));
                String report = diagnostics.report(context, T0 + 15_000);
                require(report.contains("low_memory_kill_reporting=false samples=last_android_rss_pss"), report);
                require(report.contains("pid=701 exit_utc=2026-11-24T19:49:06Z reason=native_crash(5) raw_status=6 importance=100 rss_kib=1930000 pss_kib=unavailable"), report);
                require(report.contains("pid=702 exit_utc=2026-11-24T19:49:08.100Z reason=signaled(2) raw_status=9 importance=100 rss_kib=unavailable pss_kib=unavailable"), report);
                require(report.contains("pid=701 exit_utc=2026-11-24T19:49:14.050Z reason=low_memory(3) raw_status=0 importance=100 rss_kib=3081000 pss_kib=1938100"), report);
                require(!report.contains("reason=anr") && !report.contains("pid=703") && !report.contains("java_crash"), report);
                require(manager.calls == 1 && SystemClock.elapsed == 0, "ordinary export should not poll");
            }
            case "retry" -> {
                LaunchExitDiagnostics diagnostics = scan(header(0, 701) + "[totk-app] fatal error handed to the menu: GPU lost\n"
                        + "libunwind: Unsupported .eh_frame_hdr version\n");
                manager.retry = List.of(exit(GAME, 701, 123, 5, 6, 12, 12));
                String report = diagnostics.report(context, T0 + 1000);
                require(report.contains("reason=native_crash(5)"), report);
                require(manager.calls == 2 && SystemClock.elapsed == 600, "retry should be bounded");
                LaunchExitDiagnostics live = scan(header(0, 701) + "[totk-app] fatal error handed to the menu: GPU lost\n"
                        + header(2000, 801) + "still running\n");
                report = live.report(context, T0 + 3000);
                require(report.contains("pid=801 record=not_reported"), report);
                require(manager.calls == 3 && SystemClock.elapsed == 600, "a previous fatal exit should not trigger polling");
                manager.calls = 0;
                manager.first = List.of(exit(GAME, 701, 123, 5, 6, 12, 12));
                manager.failOnCall = 2;
                LaunchExitDiagnostics secondFatal = scan(header(0, 701) + header(2000, 801)
                        + "[totk-app] fatal error handed to the menu: GPU lost\n");
                report = secondFatal.report(context, T0 + 3000);
                require(report.contains("reason=native_crash(5)") && report.contains("pid=801 record=not_reported"), report);
                require(manager.calls == 2 && SystemClock.elapsed == 1200, "a failed retry should preserve the first query");
            }
            case "unavailable" -> {
                require(scan("[totk-log] ===== Launch time unknown (pid 701) =====\n").report(context, T0)
                        .contains("history=no_identifiable_launches"), "malformed legacy timestamps cannot correlate");
                require(manager.calls == 0, "must not query without an identifiable launch");
                manager.failOnCall = 1;
                String report = scan(header(0, 701)).report(context, T0);
                require(report.contains("history=unavailable") && !report.contains("private"), report);
                Build.VERSION.SDK_INT = 29;
                require(scan(header(0, 701)).report(context, T0).contains("history=unsupported"), "older Android");
            }
            case "bounded" -> {
                StringBuilder log = new StringBuilder("x".repeat(70_000)).append(header(0, 3)).append('\n');
                for (int i = 0; i < 12; i++) log.append(header(i * 1000, 100 + i));
                log.append(header(15_000, 123).stripTrailing()).append(" extra".repeat(80)).append('\n');
                log.append("[totk-log] ===== Launch 2026-11-24T19:50:00Z (pid 9999999999) =====\n");
                String report = scan(log.toString()).report(context, T0 + 16_000);
                require(!report.contains("pid=100") && !report.contains("pid=101") && report.contains("pid=102"), report);
                require(!report.contains("pid=123") && !report.contains("pid=3") && report.contains("pid=111"), report);
                require(report.lines().filter(line -> line.contains("record=not_reported")).count() == 10, report);
            }
            default -> throw new AssertionError(args[0]);
        }
    }
}
