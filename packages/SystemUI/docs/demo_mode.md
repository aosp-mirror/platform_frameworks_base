# Demo Mode for the Android System UI
*Demo mode for the status bar allows you to force the status bar into a fixed state, useful for taking screenshots with a consistent status bar state, or testing different status icon permutations. Demo mode is available in recent versions of Android.*

## Enabling demo mode
Demo mode is protected behind a system setting. To enable it for a device, run:

```
adb shell settings put global sysui_demo_allowed 1
```

## Protocol
The protocol is based on broadcast intents, and thus can be driven via the command line (```adb shell am broadcast```) or an app (```Context.sendBroadcast```).

### Broadcast action
```
com.android.systemui.demo
```

### Commands
Commands and subcommands (below) are sent as string extras in the broadcast
intent.
<br/>
Commands are sent as string extras with key ```command``` (required). Possible values are:

| Command              | Subcommand                 | Argument       | Description
| ---                  | ---                        | ---            | ---
| ```enter```          |                            |                | Enters demo mode, bar state allowed to be modified (for convenience, any of the other non-exit commands will automatically flip demo mode on, no need to call this explicitly in practice)
| ```exit```           |                            |                | Exits demo mode, bars back to their system-driven state
| ```battery```        |                            |                | Control the battery display
|                      | ```level```                |                | Sets the battery level (0 - 100)
|                      | ```plugged```              |                | Sets charging state (```true```, ```false```)
|                      | ```powersave```            |                | Sets power save mode (```true```, ```anything else```)
| ```network```        |                            |                | Control the RSSI display
|                      | ```airplane```             |                | ```show``` to show icon, any other value to hide
|                      | ```fully```                |                | Sets MCS state to fully connected (```true```, ```false```)
|                      | ```wifi```                 |                | ```show``` to show icon, any other value to hide
|                      |                            | ```level```    | Sets wifi level (null or 0-4)
|                      | ```mobile```               |                | ```show``` to show icon, any other value to hide
|                      |                            | ```datatype``` | Values: ```1x```, ```3g```, ```4g```, ```e```, ```g```, ```h```, ```lte```, ```roam```, any other value to hide
|                      |                            | ```level```    | Sets mobile signal strength level (null or 0-4)
|                      | ```carriernetworkchange``` |                | Sets mobile signal icon to carrier network change UX when disconnected (```show``` to show icon, any other value to hide)
|                      | ```sims```                 |                | Sets the number of sims (1-8)
|                      | ```nosim```                |                | ```show``` to show icon, any other value to hide
| ```bars```           |                            |                | Control the visual style of the bars (opaque, translucent, etc)
|                      | ```mode```                 |                | Sets the bars visual style (opaque, translucent, semi-transparent)
| ```status```         |                            |                | Control the system status icons
|                      | ```volume```               |                | Sets the icon in the volume slot (```silent```, ```vibrate```, any other value to hide)
|                      | ```bluetooth```            |                | Sets the icon in the bluetooth slot (```connected```, ```disconnected```, any other value to hide)
|                      | ```location```             |                | Sets the icon in the location slot (```show```, any other value to hide)
|                      | ```alarm```                |                | Sets the icon in the alarm_clock slot (```show```, any other value to hide)
|                      | ```sync```                 |                | Sets the icon in the sync_active slot (```show```, any other value to hide)
|                      | ```tty```                  |                | Sets the icon in the tty slot (```show```, any other value to hide)
|                      | ```eri```                  |                | Sets the icon in the cdma_eri slot (```show```, any other value to hide)
|                      | ```mute```                 |                | Sets the icon in the mute slot (```show```, any other value to hide)
|                      | ```speakerphone```         |                | Sets the icon in the speakerphone slot (```show```, any other value to hide)
| ```notifications```  |                            |                | Control the notification icons
|                      | ```visible```              |                | ```false``` to hide the notification icons, any other value to show
| ```clock```          |                            |                | Control the clock display
|                      | ```millis```               |                | Sets the time in millis
|                      | ```hhmm```                 |                | Sets the time in hh:mm

## Examples
Enter demo mode

```
adb shell am broadcast -a com.android.systemui.demo -e command enter
```


Exit demo mode

```
adb shell am broadcast -a com.android.systemui.demo -e command exit
```


Set the clock to 12:31

```
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm
1231
```


Set the wifi level to max

```
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi
show -e level 4
```


Show the silent volume icon

```
adb shell am broadcast -a com.android.systemui.demo -e command status -e volume
silent
```


Empty battery, and not charging (red exclamation point)

```
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level
0 -e plugged false
```


Hide the notification icons

```
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e
visible false
```


Exit demo mode

```
adb shell am broadcast -a com.android.systemui.demo -e command exit
```


## Example demo controller app in AOSP
```
frameworks/base/tests/SystemUIDemoModeController
```


## Example script (for screenshotting purposes)
```bash
#!/bin/sh
CMD=$1

if [[ $ADB == "" ]]; then
  ADB=adb
fi

if [[ $CMD != "on" && $CMD != "off" ]]; then
  echo "Usage: $0 [on|off] [hhmm]" >&2
  exit
fi

if [[ "$2" != "" ]]; then
  HHMM="$2"
fi

$ADB root || exit
$ADB wait-for-devices
$ADB shell settings put global sysui_demo_allowed 1

if [ $CMD == "on" ]; then
  $ADB shell am broadcast -a com.android.systemui.demo -e command enter || exit
  if [[ "$HHMM" != "" ]]; then
    $ADB shell am broadcast -a com.android.systemui.demo -e command clock -e
hhmm ${HHMM}
  fi
  $ADB shell am broadcast -a com.android.systemui.demo -e command battery -e
plugged false
  $ADB shell am broadcast -a com.android.systemui.demo -e command battery -e
level 100
  $ADB shell am broadcast -a com.android.systemui.demo -e command network -e
wifi show -e level 4
  $ADB shell am broadcast -a com.android.systemui.demo -e command network -e
mobile show -e datatype none -e level 4
  $ADB shell am broadcast -a com.android.systemui.demo -e command notifications
-e visible false
elif [ $CMD == "off" ]; then
  $ADB shell am broadcast -a com.android.systemui.demo -e command exit
fi
```

