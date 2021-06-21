# Usage
##  Two options to use the uinput command:
### 1. Interactive through stdin:
type `uinput -` into the terminal, then type/paste commands to send to the binary.
Use Ctrl+D to signal end of stream to the binary (EOF).

This mode can be also used from an app to send uinput events.
For an example, see the cts test case at: [InputTestCase.java][2]

When using another program to control uinput in interactive mode, registering a
new input device (for example, a bluetooth joystick) should be the first step.
After the device is added, you need to wait for the _onInputDeviceAdded_
(see [InputDeviceListener][1]) notification before issuing commands
to the device.
Failure to do so will cause missed events and inconsistent behavior.

### 2. Using a file as an input:
type `uinput <filename>`, and the file will be used an an input to the binary.
You must add a sufficient delay after a "register" command to ensure device
is ready. The interactive mode is the recommended method of communicating
with the uinput binary.

All of the input commands should be in pseudo-JSON format as documented below.
See examples [here][3].

The file can have multiple commands one after the other (which is not strictly
legal JSON format, as this would imply multiple root elements).

## Command description

1. `register`
Register a new uinput device

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| id            | integer       | Device id                  |
| command       | string        | Must be set to "register"  |
| name          | string        | Device name                |
| vid           | 16-bit integer| Vendor id                  |
| pid           | 16-bit integer| Product id                 |
| bus           | string        | Bus that device should use |
| configuration | int array     | uinput device configuration|
| ff_effects_max| integer       | ff_effects_max value       |
| abs_info      | array         | ABS axes information       |

Device ID is used for matching the subsequent commands to a specific device
to avoid ambiguity when multiple devices are registered.

Device bus is used to determine how the uinput device is connected to the host.
The options are "usb" and "bluetooth".

Device configuration is used to configure uinput device.  "type" field provides the UI_SET_*
control code, and data is a vector of control values to be sent to uinput device, depends on
the control code.

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| type          | integer       | UI_SET_ control type       |
| data          | int array     | control values             |

Device ff_effects_max must be provided if FFBIT is set.

Device abs_info fields are provided to set the device axes information. It is an array of below
objects:
| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| code          | integer       | Axis code                  |
| info          | object        | ABS information object     |

ABS information object is defined as below:
| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| value         | integer       | Latest reported value      |
| minimum       | integer       | Minimum value for the axis |
| maximum       | integer       | Maximum value for the axis |
| fuzz          | integer       | fuzz value for noise filter|
| flat          | integer       | values to be discarded     |
| resolution    | integer       | resolution of axis         |

See [struct input_absinfo][4]) definitions.

Example:
```json

{
  "id": 1,
  "command": "register",
  "name": "Keyboard (Test)",
  "vid": 0x18d2,
  "pid": 0x2c42,
  "bus": "usb",
  "configuration":[
        {"type":100, "data":[1, 21]},  // UI_SET_EVBIT : EV_KEY and EV_FF
        {"type":101, "data":[11, 2, 3, 4]},   // UI_SET_KEYBIT : KEY_0 KEY_1 KEY_2 KEY_3
        {"type":107, "data":[80]}    //  UI_SET_FFBIT : FF_RUMBLE
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
2. `delay`
Add a delay to command processing

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| id            | integer       | Device id                  |
| command       | string        | Must be set to "delay"     |
| duration      | integer       | Delay in milliseconds      |

Example:
```json
{
  "id": 1,
  "command": "delay",
  "duration": 10
}
```

3. `inject`
Send an array of uinput event packets [type, code, value] to the uinput device

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| id            | integer       | Device id                  |
| command       | string        | Must be set to "inject"    |
| events        | integer array | events to inject           |

The "events" parameter is an array of integers, encapsulates evdev input_event type, code and value,
see the example below.

Example:
```json
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

### Notes
1. As soon as EOF is reached (either in interactive mode, or in file mode),
the device that was created will be unregistered. There is no
explicit command for unregistering a device.
2. The `getevent` utility can used to print out the key events
for debugging purposes.

[1]: https://developer.android.com/reference/android/hardware/input/InputManager.InputDeviceListener.html
[2]: ../../../../cts/tests/tests/hardware/src/android/hardware/input/cts/tests/InputTestCase.java
[3]: ../../../../cts/tests/tests/hardware/res/raw/
[4]: ../../../../bionic/libc/kernel/uapi/linux/input.h
