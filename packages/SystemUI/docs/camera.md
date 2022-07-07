# How double-click power launches the camera
_Last update: July 2022_

## Sequence of events
1. [PhoneWindowManager.java](/services/core/java/com/android/server/policy/PhoneWindowManager.java) is responsible for all power button presses (see `interceptPowerKeyDown`) 
2. Even though `PhoneWindowManager` has a lot of logic to detect all manner of power button multi-presses and gestures, it also checks with `GestureLauncherService`, which is also [offered the chance](/services/core/java/com/android/server/policy/PhoneWindowManager.java#943) to [intercept](/services/core/java/com/android/server/GestureLauncherService.java) the power key
3. `GestureLauncherService` is responsible for the camera timeout, and if it detects one, it [forwards it to the StatusBarManagerService](/services/core/java/com/android/server/GestureLauncherService.java) (which hands it off to SystemUI)
4. Inside SystemUI, `onCameraLaunchDetected` in [CentralSurfacesCommandQueueCallbacks.java](/packages/SystemUI/src/com/android/systemui/statusbar/phone/CentralSurfacesCommandQueueCallbacks.java) looks at the keyguard state and determines
    1. whether the camera is even allowed 
    2. whether the screen is on; if not, we need to delay until that happens
    3. whether the device is locked (defined as "keyguard is showing").
5. If the device is unlocked (no keyguard), the camera is launched immediately
6. If the keyguard is up, however, [NotificationPanelViewController.launchCamera](/packages/SystemUI/src/com/android/systemui/statusbar/phone/NotificationPanelViewController.java) takes over to handle the "secure camera" (a different intent, usually directing to the same app, but giving that app the cue to not allow access to the photo roll, etc).
7. If the intent would have to launch a resolver (because the user has multiple camera apps installed and has not chosen one to always launch for the `SECURE_CAMERA_INTENT`, then - in order to show the resolver, the lockscreen "bouncer" (authentication method) is first presented
8. Otherwise (just one secure camera), it is launched

## Which intent launches the camera app?
[CameraGestureHelper](/packages/SystemUI/src/com/android/systemui/camera/CameraGestureHelper.kt) encapsulate this logic. Roughly:
* If the keyguard is not showing (device is unlocked)
    *   `CameraIntents.getInsecureCameraIntent()`, defined to be `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA`.
* If the keyguard is showing (device is locked)
    *   one of `CameraIntents.getInsecureCameraIntent()` or `CameraIntents.getSecureCameraIntent()`, which are `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA` and `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE`, respectively
* Note that starting in Android 12, as required by some OEMs, if the special string resource `config_cameraGesturePackage` is nonempty, this will be treated as a package name to be added to the insecure camera intent, constraining the invocation to that single app and typically preventing implicit intent resolution. This package must be on the device or the camera gesture will no longer work properly
