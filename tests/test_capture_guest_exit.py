import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "capture_guest_exit", Path(__file__).resolve().parents[1] / "tools/capture_guest_exit.py"
)
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)


def result(stdout=b"", code=0):
    return subprocess.CompletedProcess([], code, stdout, b"")


class CaptureGuestExitTest(unittest.TestCase):
    def test_uses_saved_pid_and_writes_only_guest_files_by_default(self):
        log = b"11-18 20:10:01.000  1234  1240 E Unity: assertion failed\n"
        with tempfile.TemporaryDirectory() as temp, patch("builtins.input"), patch(
            "builtins.print"
        ), patch.object(tool.subprocess, "run", side_effect=[
            result(b"1795032000\r\n"), result(b"old\n"), result(b"1234\r\n"),
            result(b"guest-exit: guest exited with status 134\n"), result(log),
        ]) as run:
            output = Path(temp) / "run"
            self.assertEqual(tool.capture(["adb", "-s", "device"], output, False), 0)
            self.assertEqual({p.name for p in output.iterdir()},
                             {"runtime-report.txt", "guest-logcat.txt"})
            self.assertEqual((output / "guest-logcat.txt").read_bytes(), log)
            self.assertEqual(run.call_count, 5)
            self.assertEqual(run.call_args.args[0], [
                "adb", "-s", "device", "logcat", "-b", "main,system,crash", "-t",
                "1795032000.000", "-v", "threadtime", "--pid=1234", "*:V",
            ])

    def test_rejects_missing_pid_without_dumping_unfiltered_logs(self):
        with tempfile.TemporaryDirectory() as temp, patch("builtins.input"), patch.object(
            tool.subprocess, "run", side_effect=[result(b"1795032000\n"), result(code=1), result(code=1)]
        ) as run:
            output = Path(temp) / "run"
            with self.assertRaisesRegex(ValueError, "one running guest"):
                tool.capture(["adb"], output, True)
            self.assertFalse(output.exists())
            self.assertEqual(run.call_count, 3)

    def test_stale_report_and_header_only_logs_are_not_saved(self):
        with tempfile.TemporaryDirectory() as temp, patch("builtins.input"), patch(
            "builtins.print"
        ), patch.object(tool.subprocess, "run", side_effect=[
            result(b"1795032000\n"), result(b"old\n"), result(b"1234\n"),
            result(b"old\n"), result(b"--------- beginning of main\n"), result(b"device-wide\n"),
        ]) as run:
            output = Path(temp) / "run"
            self.assertEqual(tool.capture(["adb"], output, True), 1)
            self.assertEqual({p.name for p in output.iterdir()}, {"device-crash-private.txt"})
            self.assertEqual(run.call_args.args[0], [
                "adb", "logcat", "-b", "crash", "-t", "1795032000.000", "-v", "threadtime", "*:V",
            ])


if __name__ == "__main__":
    unittest.main()
