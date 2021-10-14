# CEC Key Handling

The mapping of CEC keycodes to Android keycodes is done at [HdmiCecKeycode](HdmiCecKeycode.java).

# Android TV

Android TV (ATV) requires special handling of some keys.

The general action for key handling is described in the table below.

| Android Key | TV Panel                                               | OTT                        | Soundbar                                               |
| ----------- | -----------------                                      | -------------------        | -------------------                                    |
| POWER       | Toggle the device power state  | Toggle the OTT power state, TV power state follows | Toggle the soundbar power state, TV power state follows|
| TV_POWER    | Toggle the device power state  | Toggle the TV power state, OTT power state follows | Toggle the TV power state, soundbar power state follows|
| HOME        | Turn on TV, Set active Source to TV, go to home screen | OTP, and go to home screen | OTP, and go to home screen                             |
| Volume keys | Handle on device or send to soundbar                   | Send to TV or soundbar     | Handle on device or send to TV                         |
| Other keys  | Forward to active source                               | Handle on device           | Handle on device                                       |

Special cases and flags per key are described below.

## TV_POWER

### TV Panel

For ATV TV panel devices, TV_POWER is an alias of POWER.

### Source Devices (OTT and Soundbar)

For ATV source devices with POWER_CONTROL_MODE set to none or CEC control disabled, TV_POWER is an alias of POWER.

For all other source devices, TV_POWER toggles the TV power state and makes the OTT power state follow.

### Other Devices

For any device that is not connected to a TV via HDMI and not an ATV device, TV_POWER is ignored.


