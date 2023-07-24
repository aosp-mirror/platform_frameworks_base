# Usage

There are two ways to use the `uinput` command:

* **Recommended:** `uinput -` reads commands from standard input until End-of-File (Ctrl+D) is sent.
  This mode can be used interactively from a terminal or used to control uinput from another program
  or app (such as the CTS tests via [`UinputDevice`][UinputDevice]).
* `uinput <filename>` reads commands from a file instead of standard input.

[UinputDevice]: https://cs.android.com/android/platform/superproject/main/+/main:cts/libs/input/src/com/android/cts/input/UinputDevice.java

## Command format

Input commands should be in JSON format, though the parser is in [lenient mode] to allow comments,
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

## Command reference

### `register`

Register a new uinput device

| Field            | Type           | Description                |
|:----------------:|:--------------:|:-------------------------- |
| `id`             | integer        | Device ID                  |
| `command`        | string         | Must be set to "register"  |
| `name`           | string         | Device name                |
| `vid`            | 16-bit integer | Vendor ID                  |
| `pid`            | 16-bit integer | Product ID                 |
| `bus`            | string         | Bus that device should use |
| `configuration`  | object array   | uinput device configuration|
| `ff_effects_max` | integer        | `ff_effects_max` value     |
| `abs_info`       | array          | Absolute axes information  |

`id` is used for matching the subsequent commands to a specific device to avoid ambiguity when
multiple devices are registered.

`bus` is used to determine how the uinput device is connected to the host. The options are `"usb"`
and `"bluetooth"`.

Device configuration is used to configure the uinput device. The `type` field provides a `UI_SET_*`
control code, and data is a vector of control values to be sent to the uinput device, which depends
on the control code.

| Field         |     Type      | Description                |
|:-------------:|:-------------:|:-------------------------- |
| `type`        | integer       | `UI_SET_` control type     |
| `data`        | integer array | control values             |

`ff_effects_max` must be provided if `UI_SET_FFBIT` is used in `configuration`.

`abs_info` fields are provided to set the device axes information. It is an array of below objects:

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| `code`        | integer       | Axis code                  |
| `info`        | object        | Axis information object    |

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
        {"type":100, "data":[1, 21]},         // UI_SET_EVBIT : EV_KEY and EV_FF
        {"type":101, "data":[11, 2, 3, 4]},   // UI_SET_KEYBIT : KEY_0 KEY_1 KEY_2 KEY_3
        {"type":107, "data":[80]}             // UI_SET_FFBIT : FF_RUMBLE
  ],
  "ff_effects_max" : 1,
  "abs_info": [
        {"code":1, "info": {"value":20, "minimum":-255,
                            "maximum":255, "fuzz":0, "flat":0, "resolution":1}
        },
        {"code":8, "info": {"value":-50, "minimum":-255,
                            "maximum":255, "fuzz":0, "flat":0, "resolution":1}
        }
  ]
}
```

[struct input_absinfo]: https://cs.android.com/android/platform/superproject/main/+/main:bionic/libc/kernel/uapi/linux/input.h?q=%22struct%20input_absinfo%22

#### Waiting for registration

After the command is sent, there will be a delay before the device is set up by the Android input
stack, and `uinput` does not wait for that process to finish. Any commands sent to the device during
that time will be dropped. If you are controlling `uinput` by sending commands through standard
input from an app, you need to wait for [`onInputDeviceAdded`][onInputDeviceAdded] to be called on
an `InputDeviceListener` before issuing commands to the device. If you are passing a file to
`uinput`, add a `delay` after the `register` command to let registration complete.

[onInputDeviceAdded]: https://developer.android.com/reference/android/hardware/input/InputManager.InputDeviceListener.html

#### Unregistering the device

As soon as EOF is reached (either in interactive mode, or in file mode), the device that was created
will be unregistered. There is no explicit command for unregistering a device.

### `delay`

Add a delay to command processing

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

### `inject`

Send an array of uinput event packets to the uinput device

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| `id`          | integer       | Device ID                  |
| `command`     | string        | Must be set to "inject"    |
| `events`      | integer array | events to inject           |

The `events` parameter is an array of integers in sets of three: a type, an axis code, and an axis
value, like you'd find in Linux's `struct input_event`. For example, sending presses of the 0 and 1
keys would look like this:

```json5
{
  "id": 1,
  "command": "inject",
  "events": [0x01, 0xb,  0x1,   // EV_KEY, KEY_0, DOWN
             0x00, 0x00, 0x00,  // EV_SYN, SYN_REPORT, 0
             0x01, 0x0b, 0x00,  // EV_KEY, KEY_0, UP
             0x00, 0x00, 0x00,  // EV_SYN, SYN_REPORT, 0
             0x01, 0x2,  0x1,   // EV_KEY, KEY_1, DOWN
             0x00, 0x00, 0x01,  // EV_SYN, SYN_REPORT, 0
             0x01, 0x02, 0x00,  // EV_KEY, KEY_1, UP
             0x00, 0x00, 0x01   // EV_SYN, SYN_REPORT, 0
            ]
}
```

## Notes

The `getevent` utility can used to print out the key events for debugging purposes.
