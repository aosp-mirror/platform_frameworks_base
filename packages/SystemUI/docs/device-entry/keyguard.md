# Keyguard (aka Lockscreen)

Keyguard is responsible for:

1. Handling authentication to allow the user to unlock the device, via biometrics or [KeyguardBouncer][1]
2. Displaying informational content such as the time, notifications, and smartspace
3. Always-on Display (AOD)

Keyguard is the first screen available when turning on the device, as long as the user has not specified a security method of NONE.

## Critical User Journeys

The journeys below generally refer to Keyguard's portion of the overall flow, especially regarding use of the power button. Power button key interpretation (short press, long press, very long press, multi press) is done in [PhoneWindowManager][4], with calls to [PowerManagerService][2] to sleep or wake up, if needed.

### Power On - AOD enabled or disabled

Begins with the device in low power mode, with the display active for [AOD][3] or inactive. [PowerManagerService][2] can be directed to wake up on various user-configurable signals, such as lift to wake, screen taps, among others. [AOD][2], whether visibly enabled or not, handles these signals to transition AOD to full Lockscreen content. See more in [AOD][3].

### Power Off

An indication to power off the device most likely comes from one of two signals: the user presses the power button or the screen timeout has passed. This may [lock the device](#How-the-device-locks)

#### On Lockscreen

#### On Lockscreen, occluded by an activity

#### Device unlocked, Keyguard has gone away

### Pulsing (Incoming notifications while dozing)

### How the device locks

### Quick Affordances

These are interactive UI elements that appear on the lockscreen when the device is locked. They allow the user to perform quick actions without unlocking their device. To learn more about them, please see [this dedicated document](quickaffordance.md)

## Debugging Tips
Enable verbose keyguard logs that will print to logcat. Should only be used temporarily for debugging. See [KeyguardConstants][5].
```
adb shell setprop log.tag.Keyguard DEBUG && adb shell am crash com.android.systemui
```

More coming
* Screen timeout
* Smart lock
* Device policy
* Power button instantly locks setting
* Lock timeout after screen timeout setting


[1]: /frameworks/base/packages/SystemUI/docs/device-entry/bouncer.md
[2]: /com/android/server/power/PowerManagerService.java
[3]: /frameworks/base/packages/SystemUI/docs/device-entry/doze.md
[4]: /com/android/server/policy/PhoneWindowManager.java
[5]: /frameworks/base/packages/SystemUI/src/com/android/keyguard/KeyguardConstants.java
