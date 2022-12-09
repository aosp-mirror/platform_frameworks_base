# Debugging in the Shell

---

## Logging & ProtoLogs

The interactions in the Shell can be pretty complicated, so having good logging is crucial to
debugging problems that arise (especially in dogfood).  The Shell uses the same efficient Protolog
mechanism as WM Core, which can be enabled at runtime on debug devices.

**TLDR**&nbsp; Don’t use Logs or Slogs except for error cases, Protologs are much more flexible,
easy to add and easy to use

### Adding a new ProtoLog
Update `ShellProtoLogGroup` to include a new log group (ie. NEW_FEATURE) for the content you want to
log.  ProtoLog log calls mirror Log.v/d/e(), and take a format message and arguments:
```java
ProtoLog.v(NEW_FEATURE, "Test log w/ params: %d %s", 1, “a”)
```
This code itself will not compile by itself, but the `protologtool` will preprocess the file when
building to check the log state (is enabled) before printing the print format style log.

**Notes**
- ProtoLogs currently only work from soong builds (ie. via make/mp). We need to reimplement the
  tool for use with SysUI-studio
- Non-text ProtoLogs are not currently supported with the Shell library (you can't view them with
  traces in Winscope)

### Enabling ProtoLog command line logging
Run these commands to enable protologs for both WM Core and WM Shell to print to logcat.
```shell
adb shell wm logging enable-text NEW_FEATURE
adb shell wm logging disable-text NEW_FEATURE
```

## Winscope Tracing

The Winscope tool is extremely useful in determining what is happening on-screen in both
WindowManager and SurfaceFlinger.  Follow [go/winscope](http://go/winscope-help) to learn how to
use the tool.

In addition, there is limited preliminary support for Winscope tracing componetns in the Shell,
which involves adding trace fields to [wm_shell_trace.proto](frameworks/base/libs/WindowManager/Shell/proto/wm_shell_trace.proto)
file and ensure it is updated as a part of `WMShell#writeToProto`.

Tracing can be started via the shell command (to be added to the Winscope tool as needed):
```shell
adb shell cmd statusbar tracing start
adb shell cmd statusbar tracing stop
```

## Dumps

Because the Shell library is built as a part of SystemUI, dumping the state is currently done as a
part of dumping the SystemUI service.  Dumping the Shell specific data can be done by specifying the
WMShell SysUI service:

```shell
adb shell dumpsys activity service SystemUIService WMShell
```

If information should be added to the dump, either:
- Update `WMShell` if you are dumping SysUI state
- Inject `ShellCommandHandler` into your Shell class, and add a dump callback

## Debugging in Android Studio

If you are using the [go/sysui-studio](http://go/sysui-studio) project, then you can debug Shell
code directly from Android Studio like any other app.
