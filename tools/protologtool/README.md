# ProtoLogTool

Code transformation tool and viewer for ProtoLog.

## What does it do?

ProtoLogTool incorporates three different modes of operation:

### Code transformation

Command: `process <protolog class path> <protolog implementation class path>
 <protolog groups class path> <config.jar> [<input.java>] <output.srcjar>`

In this mode ProtoLogTool transforms every ProtoLog logging call in form of:
```java
ProtoLog.x(ProtoLogGroup.GROUP_NAME, "Format string %d %s", value1, value2);
```
into:
```java
if (GROUP_NAME.isLogToAny()) {
    ProtoLogImpl.x(ProtoLogGroup.GROUP_NAME, 123456, "Format string %d %s or null", value1, value2);
}
```
where `ProtoLog`, `ProtoLogImpl` and `ProtoLogGroup` are the classes provided as arguments
 (can be imported, static imported or full path, wildcard imports are not allowed) and, `x` is the
 logging method. The transformation is done on the source level. A hash is generated from the format
 string and log level and inserted after the `ProtoLogGroup` argument. The format string is replaced
 by `null` if `ProtoLogGroup.GROUP_NAME.isLogToLogcat()` returns false. If `ProtoLogGroup.GROUP_NAME.isEnabled()`
 returns false the log statement is removed entirely from the resultant code.

Input is provided as a list of java source file names. Transformed source is saved to a single
source jar file. The ProtoLogGroup class with all dependencies should be provided as a compiled
jar file (config.jar).

### Viewer config generation

Command: `viewerconf <protolog class path> <protolog implementation class path
<protolog groups class path> <config.jar> [<input.java>] <output.json>`

This command is similar in it's syntax to the previous one, only instead of creating a processed source jar
it writes a viewer configuration file with following schema:
```json
{
  "version": "1.0.0",
  "messages": {
    "123456": {
      "message": "Format string %d %s",
      "level": "ERROR",
      "group": "GROUP_NAME"
    },
  },
  "groups": {
    "GROUP_NAME": {
      "tag": "TestLog"
    }
  }
}

```

### Binary log viewing

Command: `read <viewer.json> <wm_log.pb>`

Reads the binary ProtoLog log file and outputs a human-readable LogCat-like text log.

## What is ProtoLog?

ProtoLog is a logging system created for the WindowManager project. It allows both binary and text logging
and is tunable in runtime. It consists of 3 different submodules:
* logging system built-in the Android app,
* log viewer for reading binary logs,
* a code processing tool.

ProtoLog is designed to reduce both application size (and by that memory usage) and amount of resources needed
for logging. This is achieved by replacing log message strings with their hashes and only loading to memory/writing
full log messages when necessary.

### Text logging

For text-based logs Android LogCat is used as a backend. Message strings are loaded from a viewer config
located on the device when needed.

### Binary logging

Binary logs are saved as Protocol Buffers file. They can be read using the ProtoLog tool or specialised
viewer like Winscope.

## How to use ProtoLog?

### Adding a new logging group or log statement

To add a new ProtoLogGroup simple create a new enum ProtoLogGroup member with desired parameters.

To add a new logging statement just add a new call to ProtoLog.x where x is a log level.

After doing any changes to logging groups or statements you should run `make update-protolog` to update
viewer configuration saved in the code repository.

## How to change settings on device in runtime?
Use the `adb shell su root cmd window logging` command. To get help just type
`adb shell su root cmd window logging help`.




