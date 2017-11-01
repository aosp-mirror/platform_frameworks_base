# Usage
##  Two options to use the hid command:
### 1. Interactive through stdin:
type `hid -` into the terminal, then type/paste commands to send to the binary.
Use Ctrl+D to signal end of stream to the binary (EOF).

This mode can be also used from an app to send HID events.
For an example, see the cts test case at: [InputTestCase.java][2]

When using another program to control hid in interactive mode, registering a
new input device (for example, a bluetooth joystick) should be the first step.
After the device is added, you need to wait for the _onInputDeviceAdded_
(see [InputDeviceListener][1]) notification before issuing commands
to the device.
Failure to do so will cause missed events and inconsistent behaviour.
In the current implementation of the hid command, the hid binary will wait
for the file descriptor to the uhid node to send the UHID_START and UHID_OPEN
signals before returning. However, this is not sufficient. These signals
only notify the readiness of the kernel driver,
but do not take into account the inputflinger framework.


### 2. Using a file as an input:
type `hid <filename>`, and the file will be used an an input to the binary.
You must add a sufficient delay after a "register" command to ensure device
is ready. The interactive mode is the recommended method of communicating
with the hid binary.

All of the input commands should be in pseudo-JSON format as documented below.
See examples [here][3].

The file can have multiple commands one after the other (which is not strictly
legal JSON format, as this would imply multiple root elements).

## Command description

1. `register`
Register a new uhid device

| Field         | Type          | Description                |
|:-------------:|:-------------:|:--------------------------|
| id            | integer       | Device id                  |
| command       | string        | Must be set to "register"  |
| name          | string        | Device name                |
| vid           | 16-bit integer| Vendor id                  |
| pid           | 16-bit integer| Product id                 |
| descriptor    | byte array    | USB HID report descriptor  |

Device ID is used for matching the subsequent commands to a specific device
to avoid ambiguity when multiple devices are registered.

USB HID report descriptor should be generated according the the USB HID spec
and can be checked by reverse parsing using a variety of tools, for example
[usbdescreqparser][5].

Example:
```json
{
  "id": 1,
  "command": "register",
  "name": "Odie (Test)",
  "vid": 0x18d1,
  "pid": 0x2c40,
  "descriptor": [0x05, 0x01, 0x09, 0x05, 0xa1, 0x01, 0x85, 0x01, 0x05, 0x09, 0x0a, 0x01, 0x00,
    0x0a, 0x02, 0x00, 0x0a, 0x04, 0x00, 0x0a, 0x05, 0x00, 0x0a, 0x07, 0x00, 0x0a, 0x08, 0x00,
    0x0a, 0x0e, 0x00, 0x0a, 0x0f, 0x00, 0x0a, 0x0d, 0x00, 0x05, 0x0c, 0x0a, 0x24, 0x02, 0x0a,
    0x23, 0x02, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0b, 0x81, 0x02, 0x75, 0x01, 0x95,
    0x01, 0x81, 0x03, 0x05, 0x01, 0x75, 0x04, 0x95, 0x01, 0x25, 0x07, 0x46, 0x3b, 0x01, 0x66,
    0x14, 0x00, 0x09, 0x39, 0x81, 0x42, 0x66, 0x00, 0x00, 0x09, 0x01, 0xa1, 0x00, 0x09, 0x30,
    0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x05, 0x02, 0x09, 0xc5, 0x09, 0xc4, 0x15, 0x00, 0x26,
    0xff, 0x00, 0x35, 0x00, 0x46, 0xff, 0x00, 0x75, 0x08, 0x95, 0x06, 0x81, 0x02, 0xc0, 0x85,
    0x02, 0x05, 0x08, 0x0a, 0x01, 0x00, 0x0a, 0x02, 0x00, 0x0a, 0x03, 0x00, 0x0a, 0x04, 0x00,
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x04, 0x91, 0x02, 0x75, 0x04, 0x95, 0x01, 0x91,
    0x03, 0xc0, 0x05, 0x0c, 0x09, 0x01, 0xa1, 0x01, 0x85, 0x03, 0x05, 0x01, 0x09, 0x06, 0xa1,
    0x02, 0x05, 0x06, 0x09, 0x20, 0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x01, 0x81,
    0x02, 0x06, 0xbc, 0xff, 0x0a, 0xad, 0xbd, 0x75, 0x08, 0x95, 0x06, 0x81, 0x02, 0xc0, 0xc0]
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

3. `report`
Send a report to the HID device

| Field         | Type          | Description                |
|:-------------:|:-------------:|:-------------------------- |
| id            | integer       | Device id                  |
| command       | string        | Must be set to "report"    |
| report        | byte array    | Report data to send        |

Example:
```json
{
  "id": 1,
  "command": "report",
  "report": [0x01, 0x01, 0x80, 0x7f, 0x7f, 0x7f, 0x7f, 0x00, 0x00]
}
```

### Sending a joystick button press event
To send a button press event on a joystick device:
1. Register the joystick device
2. Send button down event with coordinates ABS_X, ABS_Y, ABS_Z, and ABS_RZ
at the center of the range. If the coordinates are not centered, this event
will generate a motion event within the input framework, in addition to the
button press event. The range can be determined from the uhid report descriptor.
3. Send the button up event with the same coordinates as in 2.
4. Check that the button press event was received.

### Notes
1. As soon as EOF is reached (either in interactive mode, or in file mode),
the device that was created will be unregistered. There is no
explicit command for unregistering a device.
2. The linux input subsystem does not generate events for those values
that remain unchanged. For example, if there are two events sent to the driver,
and both events have the same value of ABS_X, then ABS_X coordinate
will not be reported.
3. The description of joystick actions is available [here][6].
4. Joysticks are split axes. When an analog stick is in a resting state,
the reported coordinates are at the center of the range.
5. The `getevent` utility can used to print out the key events
for debugging purposes.


[1]: https://developer.android.com/reference/android/hardware/input/InputManager.InputDeviceListener.html
[2]: ../../../../cts/tests/tests/hardware/src/android/hardware/input/cts/tests/InputTestCase.java
[3]: ../../../../cts/tests/tests/hardware/res/raw/
[4]: https://developer.android.com/training/game-controllers/controller-input.html#button
[5]: http://eleccelerator.com/usbdescreqparser/
[6]: https://developer.android.com/training/game-controllers/controller-input.html