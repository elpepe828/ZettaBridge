# General compatibility

ZettaBridge aims to run a broad range of Android apps and games on ARM64-only phones,
especially apps with ARM32 native libraries. It does this inside the launcher, without root
or system changes. It is not a system-wide Android native bridge or a replacement for
installing a real package.

Judge support by the Android behaviors exercised, not by the number of APKs that reach
their first screen. A named app can reveal a gap; it is not the scope of the fix.

## Priorities and acceptance probes

These rows are work areas, not a claim that every case is supported today.

| Area | What to verify | Starting point |
|---|---|---|
| APK import and architecture | A standalone APK imports intact. Java-only, ARM32, ARM64 and unsupported ABIs are distinguished. An incomplete split package fails with a useful message. Split APK support is separate work. | [Launcher contracts](../tests/launcher/LauncherContractsTest.java) |
| App startup and resources | App and provider startup order, activity lifecycle, service and app manifest metadata, and resources from derived contexts. An SDK that discovers a component by metadata is one example, not a special case. | [Launcher design](phase0-launcher.md) |
| Permissions and package identity | Distinguish behavior the launcher can reproduce from behavior that needs a real installed identity or OS permission. Do not report a grant purely to make an app continue. | [Launcher design](phase0-launcher.md) |
| ARM32 runtime and JNI | Load libraries, resolve symbols, make calls in both directions, and exercise multiple threads. Test with a small guest probe before relying on a proprietary app. | [Guest suite](../tools/run_guest_tests.sh), [JNI device acceptance](phase4-acceptance.md) |
| Graphics, input and lifecycle | Exercise GLES/EGL rendering, touch and pause/resume in ordinary activities and `NativeActivity`. Test continued interaction, not just the first frame. Vulkan is not supported yet. | [GLES device acceptance](phase5-acceptance.md) |

## How to turn a failure into broader support

1. Identify the first failing layer: import, Java startup, an Android API, native translation,
   or graphics/input. Keep the affected app version and build ID with the result.
2. Reproduce the missing behavior in a small test that uses public Android or native
   interfaces, without loading a proprietary APK in CI. For startup metadata, a useful first
   fixture is a sample provider querying its own service's manifest metadata before
   `Application.onCreate`, followed by an activity that checks the same metadata.
3. Fix the shared behavior. Do not suppress exceptions or branch on an app's package name
   to mark the app as compatible.
4. Verify the regression, then test on a real ARM64-only phone. Where possible, confirm with
   a second app that uses the same behavior through a different code path.

Record the deepest milestone reached for each app version: imports, opens, usable, or
blocked. An app is usable only after its main workflow has been exercised. Record the device
and OS, APK format and ABI, build ID, tested workflow, and observed limitation. A test on one
version of an app, one ROM, or one graphics driver does not establish support on all of them.

For phone testers, distribute a signed test APK and give them a short reproduction and report
path. They should not need a compiler, USB debugging, or private app files to give an initial
result. The [device capture guide](unity-exit-capture.md) shows the existing phone-only report
path and an optional deeper capture for developers.

Features that require system privileges, a separately installed package identity, or bypassing
app integrity checks are not promised by this rootless design.
