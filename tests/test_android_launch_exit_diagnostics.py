"""Drive launch exit correlation against deterministic Android API doubles."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1] / 'games/totk'
SOURCE = ROOT / 'android/java/com/aure/totk/setup/LaunchExitDiagnostics.java'
PROBE = ROOT / 'tests/settings/LaunchExitDiagnosticsProbe.java'
STUBS = {
    'android/content/Context.java': '''package android.content;
public class Context {
    private final Object service;
    public Context(Object service) { this.service = service; }
    public <T> T getSystemService(Class<T> type) { return type.cast(service); }
    public String getPackageName() { return "com.gtomasel.totk"; }
}''',
    'android/os/Build.java': '''package android.os;
public class Build { public static class VERSION { public static int SDK_INT = 33; } }''',
    'android/os/SystemClock.java': '''package android.os;
public class SystemClock { public static long elapsed;
    public static void sleep(long millis) { elapsed += millis; }
}''',
    'android/app/ActivityManager.java': '''package android.app;
import java.util.List;
public class ActivityManager {
    public List<ApplicationExitInfo> first = List.of(), retry = List.of();
    public int calls;
    public int failOnCall;
    public List<ApplicationExitInfo> getHistoricalProcessExitReasons(String pkg, int pid, int max) {
        if (!"com.gtomasel.totk".equals(pkg) || pid != 0 || max != 32) throw new AssertionError("query scope");
        calls++;
        if (calls == failOnCall) throw new SecurityException("private exception details");
        return calls == 1 ? first : retry;
    }
    public static boolean isLowMemoryKillReportSupported() { return false; }
}''',
    'android/app/ApplicationExitInfo.java': '''package android.app;
public record ApplicationExitInfo(String process, int pid, long timestamp, int reason, int status, long rss, long pss) {
    public static final int REASON_EXIT_SELF = 1, REASON_SIGNALED = 2, REASON_LOW_MEMORY = 3, REASON_CRASH = 4,
        REASON_CRASH_NATIVE = 5, REASON_ANR = 6, REASON_INITIALIZATION_FAILURE = 7, REASON_PERMISSION_CHANGE = 8,
        REASON_EXCESSIVE_RESOURCE_USAGE = 9, REASON_USER_REQUESTED = 10, REASON_USER_STOPPED = 11,
        REASON_DEPENDENCY_DIED = 12, REASON_OTHER = 13, REASON_FREEZER = 14;
    public String getProcessName() { return process; }
    public int getPid() { return pid; }
    public long getTimestamp() { return timestamp; }
    public int getReason() { return reason; }
    public int getStatus() { return status; }
    public int getImportance() { return 100; }
    public long getRss() { return rss; }
    public long getPss() { return pss; }
    public String getDescription() { throw new AssertionError("private description read"); }
    public java.io.InputStream getTraceInputStream() { throw new AssertionError("private trace read"); }
}''',
}


class AndroidLaunchExitDiagnosticsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        javac, cls.java = shutil.which('javac'), shutil.which('java')
        if not javac or not cls.java:
            raise unittest.SkipTest('javac/java not found')
        temp = tempfile.TemporaryDirectory(prefix='totk-launch-exit-')
        cls.addClassCleanup(temp.cleanup)
        cls.classes = Path(temp.name)
        sources = [str(SOURCE), str(PROBE)]
        for relative, contents in STUBS.items():
            stub = cls.classes / relative
            stub.parent.mkdir(parents=True, exist_ok=True)
            stub.write_text(contents, encoding='utf-8')
            sources.append(str(stub))
        subprocess.run([javac, '--release', '17', '-d', str(cls.classes), *sources], check=True, capture_output=True, text=True)

    def probe(self, scenario):
        result = subprocess.run([self.java, '-cp', str(self.classes), 'com.aure.totk.setup.LaunchExitDiagnosticsProbe', scenario],
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_correlation_uses_exported_launch_bounds_pid_and_exact_game_process(self):
        self.probe('correlate')

    def test_just_reported_fatal_exit_retries_once_without_another_launch(self):
        self.probe('retry')

    def test_history_errors_legacy_logs_and_unsupported_api_keep_export_possible(self):
        self.probe('unavailable')

    def test_bounded_launch_retention_ignores_malformed_and_oversized_lines(self):
        self.probe('bounded')
