# Usage

There are two ways to use the `uinput` command:

* **Recommended:** `uinput -` reads commands from standard input until End-of-File (Ctrl+D) is sent.
  This mode can be used interactively from a terminal or used to control uinput from another program
  or app (such as the CTS tests via [`UinputDevice`][UinputDevice]).
* `uinput <filename>` reads commands from a file instead of standard input.

There are also two supported input formats, described in the sections below. The tool will
automatically detect which format is being used.

[UinputDevice]: https://cs.android.com/android/platform/superproject/main/+/main:cts/libs/input/src/com/android/cts/input/UinputDevice.java

## evemu recording format (recommended)

`uinput` supports the evemu format, as used by the [FreeDesktop project's evemu suite][FreeDesktop].
This is a simple text-based format compatible with recording and replay tools on other platforms.
However, it only supports playback of events from one device from a single recording. Recordings can
be made using the `evemu-record` command on Android or other Linux-based OSes.

[FreeDesktop]: https://gitlab.freedesktop.org/libevdev/evemu

## JSON-like format

The other supported format is JSON-based, though the parser is in [lenient mode] to allow comments,
and integers can be specified in hexadecimal (e.g. `0xABCD`). The input file (or standard input) can
contain multiple commands, which will be executed in sequence. Simply add multiple JSON objects to
the file, one after the other without separators:

```json5
{
  "id": 1,
  "command": "register",
  // ...
}
{
  "id": 1,
  "command": "delay",
  // ...
}
```

Many examples of command files can be found [in the CTS tests][cts-example-jsons].

[lenient mode]: https://developer.android.com/reference/android/util/JsonReader#setLenient(boolean)
[cts-example-jsons]: https://cs.android.com/android/platform/superproject/main/+/main:cts/tests/tests/hardware/res/raw/

### Command reference

#### `register`

Register a new uinput device

| Field            | Type           | Description                |
|:----------------:|:--------------:|:-------------------------- |
| `id`             | integer        | Device ID                  |
| `command`        | string         | Must be set to "register"  |
| `name`           | string         | Device name                |
| `vid`            | 16-bit integer | Vendor ID                  |
| `pid`            | 16-bit integer | Product ID                 |
| `bus`            | string         | Bus that device should use |
| `port`           | string         | `phys` value to report     |
| `configuration`  | object array   | uinput device configuration|
| `ff_effects_max` | integer        | `ff_effects_max` value     |
| `abs_info`       | array          | Absolute axes information  |

`id` is used for matching the subsequent commands to a specific device to avoid ambiguity when
multiple devices are registered.

`bus` is used to determine how the uinput device is connected to the host. The options are `"usb"`
and `"bluetooth"`.

Device configuration is used to configure the uinput device. The `type` field provides a `UI_SET_*`
control code as an integer value or a string label (e.g. `"UI_SET_EVBIT"`), and data is a vector of
control values to be sent to the uinput device, which depends on the control code.

| Field         |         Type          | Description            |
|:-------------:|:---------------------:|:-----------------------|
| `type`        |    integer\|string    | `UI_SET_` control type |
| `data`        | integer\|string array | control values         |

Due to the sequential nature in which this is parsed, the `type` field must be specified before
the `data` field in this JSON Object.

Every `register` command will need a `"UI_SET_EVBIT"` configuration entry that lists what types of
axes it declares. This entry should be the first in the list. For example, if the uinput device has
`"UI_SET_KEYBIT"` and `"UI_SET_RELBIT"` configuration entries, it will also need a `"UI_SET_EVBIT"`
entry with data of `["EV_KEY", "EV_REL"]` or the other configuration entries will be ignored.

`ff_effects_max` must be provided if `UI_SET_FFBIT` is used in `configuration`.

`abs_info` fields are provided to set the device axes information. It is an array of below objects:

| Field         |      Type       | Description             |
|:-------------:|:---------------:|:------------------------|
| `code`        | integer\|string | Axis code or label      |
| `info`        |     object      | Axis information object |

The axis information object is defined as below, with the fields having the same meaning as those
Linux's [`struct input_absinfo`][struct input_absinfo]:

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| `value`       | integer       | Latest reported value      |
| `minimum`     | integer       | Minimum value for the axis |
| `maximum`     | integer       | Maximum value for the axis |
| `fuzz`        | integer       | fuzz value for noise filter|
| `flat`        | integer       | values to be discarded     |
| `resolution`  | integer       | resolution of axis         |

Example:

```json5
{
  "id": 1,
  "command": "register",
  "name": "Keyboard (Test)",
  "vid": 0x18d2,
  "pid": 0x2c42,
  "bus": "usb",
  "configuration":[
        {"type":"UI_SET_EVBIT", "data":["EV_KEY", "EV_FF"]},
        {"type":"UI_SET_KEYBIT", "data":["KEY_0", "KEY_1", "KEY_2", "KEY_3"]},
        {"type":"UI_SET_ABSBIT", "data":["ABS_Y", "ABS_WHEEL"]},
        {"type":"UI_SET_FFBIT", "data":["FF_RUMBLE"]}
  ],
  "ff_effects_max" : 1,
  "abs_info": [
        {"code":"ABS_Y", "info": {"value":20, "minimum":-255,
                            "maximum":255, "fuzz":0, "flat":0, "resolution":1}
        },
        {"code":"ABS_WHEEL", "info": {"value":-50, "minimum":-255,
                            "maximum":255, "fuzz":0, "flat":0, "resolution":1}
        }
  ]
}
```

[struct input_absinfo]: https://cs.android.com/android/platform/superproject/main/+/main:bionic/libc/kernel/uapi/linux/input.h?q=%22struct%20input_absinfo%22

##### Waiting for registration

After the command is sent, there will be a delay before the device is set up by the Android input
stack, and `uinput` does not wait for that process to finish. Any commands sent to the device during
that time will be dropped. If you are controlling `uinput` by sending commands through standard
input from an app, you need to wait for [`onInputDeviceAdded`][onInputDeviceAdded] to be called on
an `InputDeviceListener` before issuing commands to the device. If you are passing a file to
`uinput`, add a `delay` after the `register` command to let registration complete. You can add a
`sync` in certain positions, like at the end of the file to get a response when all commands have
finished processing.

[onInputDeviceAdded]: https://developer.android.com/reference/android/hardware/input/InputManager.InputDeviceListener.html

##### Unregistering the device

As soon as EOF is reached (either in interactive mode, or in file mode), the device that was created
will be unregistered. There is no explicit command for unregistering a device.

#### `delay`

Add a delay between the processing of commands.

The delay will be timed relative to the time base, a reference time which is set when the device is
registered or by the `updateTimeBase` command. Take the following set of example commands:

1. `register` device
2. `delay` 500ms
3. `inject` some events
4. `delay` 10ms
5. `inject` more events

If the `register` command is executed at time _X_, the injection at step 3 will be scheduled for
time _X_+500ms. Since scheduling isn't precise, they might actually be injected a few milliseconds
later, but regardless of that the injection at step 5 will always be scheduled for _X_+510ms. This
prevents scheduling delays from building up over time and slowing down the playback of recordings.
However, it does mean that when you expect to wait for an indeterminate period of time, you should
send `updateTimeBase` afterwards to prevent following events being scheduled in the past — see that
command's section for an example.

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| `id`          | integer       | Device ID                  |
| `command`     | string        | Must be set to "delay"     |
| `duration`    | integer       | Delay in milliseconds      |

Example:

```json5
{
  "id": 1,
  "command": "delay",
  "duration": 10
}
```

#### `updateTimeBase`

Update the time base from which the following events are scheduled to the current time. When
controlling `uinput` over standard input, you should send this command if you want following events
to be scheduled relative to now, rather than the last injection. See the following example set of
commands and the times they will be scheduled to run at:

1. `register` (say this occurs at time _X_)
2. `delay` 500ms
3. `inject`: scheduled for _X_+500ms
4. `delay` 10ms
5. `inject`: scheduled for _X_+510ms
6. (wait a few seconds)
7. `updateTimeBase` (say this occurs at time _Y_)
8. `delay` 10ms
9. `inject`: scheduled for _Y_+10ms

Without the `updateTimeBase` command, the final injection would be scheduled for _X_+520ms, which
would be in the past.

This is useful if you are issuing commands in multiple stages with long or unknown delays in between
them. For example, say you have a test that does the following:

1. `register` a device
2. `inject` a few events that should launch an app
3. Wait for the app to launch (an indeterminate amount of time, possibly seconds)
4. 1000 `inject` commands separated by `delay` commands of 10ms

Without `updateTimeBase`, the `inject` commands of step 4 will be scheduled to start immediately
after the events from step 2. That time is probably in the past, so many of the 1000 injections will
be sent immediately. This will likely fill the kernel's event buffers, causing events to be dropped.
Sending `updateTimeBase` before the `inject` commands in step 4 will schedule them relative to the
current time, meaning that they will be all injected with the intended 10ms delays between them.

| Field         | Type          | Description                     |
|:-------------:|:-------------:|:------------------------------- |
| `id`          | integer       | Device ID                       |
| `command`     | string        | Must be set to "updateTimeBase" |

#### `inject`

Send an array of uinput event packets to the uinput device

| Field         |         Type          | Description                |
|:-------------:|:---------------------:|:-------------------------- |
| `id`          |        integer        | Device ID                  |
| `command`     |        string         | Must be set to "inject"    |
| `events`      | integer\|string array | events to inject           |

The `events` parameter is an array of integers in sets of three: a type, an axis code, and an axis
value, like you'd find in Linux's `struct input_event`. For example, sending presses of the 0 and 1
keys would look like this:

```json5
{
  "id": 1,
  "command": "inject",
  "events": ["EV_KEY", "KEY_0", 1,
             "EV_SYN", "SYN_REPORT", 0,
             "EV_KEY", "KEY_0", 0,
             "EV_SYN", "SYN_REPORT", 0,
             "EV_KEY", "KEY_1", 1,
             "EV_SYN", "SYN_REPORT", 0,
             "EV_KEY", "KEY_1", 0,
             "EV_SYN", "SYN_REPORT", 0
            ]
}
```

#### `sync`

A command used to get a response once the command is processed. When several `inject` and `delay`
commands are used in a row, the `sync` command can be used to track the progress of the command
queue.

|    Field    |  Type   | Description                                  |
|:-----------:|:-------:|:---------------------------------------------|
|    `id`     | integer | Device ID                                    |
|  `command`  | string  | Must be set to "sync"                        |
| `syncToken` | string  | The token used to identify this sync command |

Example:

```json5
{
  "id": 1,
  "command": "syncToken",
  "syncToken": "finished_injecting_events"
}
```

This command will result in the following response when it is processed:

```json5
{
  "id": 1,
  "result": "sync",
  "syncToken": "finished_injecting_events"
}
```

## Notes

The `getevent` utility can used to print out the key events for debugging purposes.
