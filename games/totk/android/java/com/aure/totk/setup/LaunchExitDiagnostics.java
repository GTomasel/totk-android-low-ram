package com.aure.totk.setup;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Correlates Android's recent game process exits with the native launch headers copied to the report. */
final class LaunchExitDiagnostics {
    private static final String TAG = "[totk-android-exit] ";
    private static final String LAUNCH_PREFIX = "[totk-log] ===== Launch ";
    private static final String FATAL_PREFIX = "[totk-app] fatal error ";
    private static final byte[] LAUNCH_BYTES = LAUNCH_PREFIX.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FATAL_BYTES = FATAL_PREFIX.getBytes(StandardCharsets.US_ASCII);
    private static final Pattern HEADER = Pattern.compile(Pattern.quote(LAUNCH_PREFIX)
            + "([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z) \\(pid ([1-9][0-9]{0,9})\\) =====");
    private static final int MAX_LAUNCHES = 10, MAX_EXIT_RECORDS = 32;
    // Bound the last interval when the PID was recycled after this log stopped recording.
    private static final long LAST_EXIT_GRACE_MS = 5 * 60 * 1000L;
    private record Launch(int pid, long time) { }

    private final List<Launch> launches = new ArrayList<>(MAX_LAUNCHES);
    private final byte[] line = new byte[96];
    private int length;
    private boolean latestFatal;

    /** Reads bounded line prefixes across copy buffers, including when the last launch log is very large. */
    void scan(byte[] bytes, int count) {
        for (int i = 0; i < count; i++) {
            byte b = bytes[i];
            if (b == '\n') {
                scanLine();
                length = 0;
            } else if (length >= 0 && length < line.length) {
                // Only native log controls start with '['. Skip other lines until the next newline.
                if (length == 0 && b != '[') length = -1;
                else line[length++] = b;
            }
        }
    }

    private boolean hasPrefix(byte[] prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (line[i] != prefix[i]) return false;
        return true;
    }

    private void scanLine() {
        if (hasPrefix(FATAL_BYTES)) latestFatal = true;
        if (!hasPrefix(LAUNCH_BYTES)) return;
        // Even an unidentifiable heading (legacy or missing time) ends the previous fatal block.
        latestFatal = false;
        Matcher heading = HEADER.matcher(new String(line, 0, length, StandardCharsets.US_ASCII));
        if (!heading.matches()) return;
        try {
            Launch launch = new Launch(Integer.parseInt(heading.group(2)), Instant.parse(heading.group(1)).toEpochMilli());
            if (launches.size() == MAX_LAUNCHES) launches.remove(0);
            launches.add(launch);
        } catch (DateTimeException | NumberFormatException malformed) { }
    }

    private ApplicationExitInfo[] match(List<ApplicationExitInfo> records, String process, long modified) {
        ApplicationExitInfo[] matched = new ApplicationExitInfo[launches.size()];
        for (ApplicationExitInfo exit : records) {
            if (!process.equals(exit.getProcessName())) continue;
            long timestamp = exit.getTimestamp();
            for (int i = launches.size() - 1; i >= 0; i--) {
                Launch launch = launches.get(i);
                if (exit.getPid() != launch.pid() || timestamp < launch.time()) continue;
                // The native UTC launch timestamp has one-second precision.
                long end = i + 1 < launches.size() ? launches.get(i + 1).time() + 1000
                        : Math.max(launch.time(), modified) + LAST_EXIT_GRACE_MS;
                if (timestamp < end && (matched[i] == null || timestamp < matched[i].getTimestamp())) matched[i] = exit;
                break;
            }
        }
        return matched;
    }

    String report(Context context, long modified) {
        String section = "\n" + TAG;
        if (Build.VERSION.SDK_INT < 30) return section + "history=unsupported\n";
        if (launches.isEmpty()) return section + "history=no_identifiable_launches\n";
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return section + "history=unavailable\n";
            String process = context.getPackageName(); // The launcher runs in :setup. The game uses the package process.
            ApplicationExitInfo[] exits = match(manager.getHistoricalProcessExitReasons(process, 0, MAX_EXIT_RECORDS), process, modified);
            if (latestFatal && exits[exits.length - 1] == null) {
                // The game opens the menu before aborting, so Android may still be recording its exit.
                SystemClock.sleep(600);
                try {
                    ApplicationExitInfo[] newer = match(manager.getHistoricalProcessExitReasons(process, 0, MAX_EXIT_RECORDS), process, modified);
                    for (int i = 0; i < exits.length; i++) if (exits[i] == null) exits[i] = newer[i];
                } catch (RuntimeException unavailable) { /* Keep records already returned by Android. */ }
            }
            String lowMemoryReporting = "unavailable";
            try { lowMemoryReporting = Boolean.toString(ActivityManager.isLowMemoryKillReportSupported()); }
            catch (RuntimeException unavailable) { /* Exit records are still usable without this capability. */ }
            StringBuilder out = new StringBuilder(section).append("history=queried low_memory_kill_reporting=")
                    .append(lowMemoryReporting).append(" samples=last_android_rss_pss\n");
            for (int i = 0; i < exits.length; i++) {
                Launch launch = launches.get(i);
                ApplicationExitInfo exit = exits[i];
                out.append(TAG).append("launch_utc=").append(Instant.ofEpochMilli(launch.time())).append(" pid=").append(launch.pid());
                if (exit == null) {
                    out.append(" record=not_reported\n");
                } else {
                    out.append(" exit_utc=").append(Instant.ofEpochMilli(exit.getTimestamp()))
                            .append(" reason=").append(reason(exit.getReason())).append('(').append(exit.getReason()).append(')')
                            .append(" raw_status=").append(exit.getStatus()).append(" importance=").append(exit.getImportance())
                            .append(" rss_kib=").append(sample(exit.getRss())).append(" pss_kib=").append(sample(exit.getPss())).append('\n');
                }
            }
            return out.toString();
        } catch (RuntimeException unavailable) {
            // OEM exit-history services are best effort. Keep the copied native log usable on failure.
            return section + "history=unavailable\n";
        }
    }

    private static String sample(long kib) { return kib > 0 ? Long.toString(kib) : "unavailable"; }

    private static String reason(int code) {
        return switch (code) {
            case ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self";
            case ApplicationExitInfo.REASON_SIGNALED -> "signaled";
            case ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory";
            case ApplicationExitInfo.REASON_CRASH -> "java_crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash";
            case ApplicationExitInfo.REASON_ANR -> "anr";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested";
            case ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died";
            case ApplicationExitInfo.REASON_OTHER -> "other";
            case ApplicationExitInfo.REASON_FREEZER -> "freezer";
            default -> "unknown";
        };
    }
}
