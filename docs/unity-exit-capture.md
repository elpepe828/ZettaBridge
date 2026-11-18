# Capture the remaining Unity exit

Use Thomas Was Alone first. The recorded symptom is that the first control pressed works,
but another control ends the guest with status 134. That code alone does not distinguish
an explicit exit from a signal, and the Unity crash cause is not confirmed.

## Phone only

1. Launch Thomas Was Alone in ZettaBridge. Press one control that works, then a different one.
2. If it closes, do not launch another game or retry yet. In the ZettaBridge library, press and
   hold the game's row and select **Last run report**. The dialog attempts to copy the report
   to the clipboard. Save the report and note which controls were pressed, in which order.
3. Review it for personal information before sharing. A report with only an exit status is
   still useful, but it may not contain the actual abort message.

## Capture a report and Android logs

With Python 3 and [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
on the computer, connect the phone with USB debugging enabled. Verify that `adb devices`
lists it as `device`, then from the repo run:

```sh
python3 tools/capture_guest_exit.py
```

Follow the two prompts. The helper records a device timestamp before launch and saves the guest
PID while the game is still alive, so it can retrieve that run's buffered logs after the exit.
It reads but does not clear the logs and does not install or modify the launcher. Use
`--serial SERIAL` if there are multiple devices or `--output PATH` for a new local directory.
The default is a private temporary directory. If the report cannot be pulled, try the phone-only
steps above. Some ROMs suppress application logs even when the guest report is available.

If a full Android process crash is suspected, rerun with `--include-device-crash-buffer`.
Android can log native crash details under a separate debugger process, so the PID-filtered
log may not include them. The [Android logcat documentation](https://developer.android.com/tools/logcat?hl=en)
describes the separate crash buffer. This opt-in file can contain data from other apps: keep
it local unless you have extracted and reviewed the matching `com.zettabridge.launcher:guest`
section. Do not upload a complete device log to a public issue.

## What to compare

Start with `guest-exit:` in the runtime report. In the matching guest log look for
`guest terminated by signal`, `Abort message:`, `Fatal signal`, or an assertion immediately
before the exit. An empty crash buffer does not rule out a translated guest signal: the
bridge can stop the host process with an exit status without a native Android crash dump.
Do not change Unity, JNI, graphics, or signal behavior based on status 134 alone.
