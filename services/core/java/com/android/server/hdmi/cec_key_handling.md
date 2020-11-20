# CEC Key Handling

The mapping of CEC key codes to Android key codes are at
[HdmiCecKeycode](HdmiCecKeycode.java)

# Android TV

Android TV requires special handling of some keys.

The general action for key handling is described in the table

| Android Key | TV Panel                                               | OTT                        | Soundbar                                               |
| ----------- | -----------------                                      | -------------------        | -------------------                                    |
| general     | Send to active source                                  | handle on device           | handle on device                                       |
| POWER       | Toggle the device power state  | Toggle the OTT power state, TV power state follows | Toggle the soundbar power state, TV power state follows|
| TV_POWER    | Toggle the device power state  | Toggle the TV power state, OTT power state follows | Toggle the TV power state, soundbar power state follows|
| HOME        | Turn on TV, Set active Source to TV, go to home screen | OTP, and go to home screen | OTP, and go to home screen                             |
| volume keys | Handle on device or send to soundbar                   | Send to TV or soundbar     | Handle on device or send to TV                         |

Special cases and flags for each key are described below

## POWER

### TV Panel

TODO

### OTT

TODO

### Soundbar

TODO


