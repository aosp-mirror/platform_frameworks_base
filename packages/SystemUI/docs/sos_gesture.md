# How 5-tapping power launches Emergency Sos

_as of Jan 2021_

Note that the flow is a simplified version of the camera launch flow.


### Sequence of events


1. [PhoneWindowManager.java](/services/core/java/com/android/server/policy/PhoneWindowManager.java) is responsible for all power button presses (see `interceptPowerKeyDown`).
2. Even though PWMgr has a lot of logic to detect all manner of power button multipresses and gestures, it also checks with GestureLauncherService, which is also [offered the chance](/services/core/java/com/android/server/policy/PhoneWindowManager.java#943) to [intercept](/services/core/java/com/android/server/GestureLauncherService.java#358) the power key.
3. GLS is responsible for the emergoncy sos timeout, and if it detects one, it [forwards it to the StatusBarManagerService](/services/core/java/com/android/server/GestureLauncherService.java#475) (which hands it off to SystemUI).
4. Inside SystemUI, [onEmergencyActionLaunchGestureDetected](/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBar.java#4039) and determines
    1. If the gesture is enabled (else do nothing)
    2. If there is an app to handle the gesture (else do nothing)
    2. whether the screen is on; if not, we need to delay until that happens
5. Assuming there is an app, and the setting is one launch Emergengy Flow immediately. [Callsite](/packages/SystemUI/src/com/android/systemui/statusbar/phone/StatusBar.java#4077)
    1. Note that we cannot have an intent resolver, so we launch the default.

**Which intent launches?**

Due to the nature of the gesture, we need the flow to work behind the lockscreen, and without disambiguation.
Thus, we always launch the same intent, and verify that there is only one matching intent-filter in the system image.

[The emergengy sos intent action](packages/SystemUI/src/com/android/systemui/emergency/EmergencyGesture.java#36).
