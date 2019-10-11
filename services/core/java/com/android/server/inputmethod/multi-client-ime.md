<!-- Copyright (C) 2018 The Android Open Source Project

     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at

          http://www.apache.org/licenses/LICENSE-2.0

     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
-->

# Multi Client Input Method Editors

## History of Multi Client Input Method Editors (Multi Client IMEs)

An advanced multi-display support is requested for certain Android form-factors so that user(s) can type text on each display at the same time without losing software keyboard focus in other displays (hereafter called "multi-client scenario"). This is not possible in Android IMEs built on top of `InputMethodService` class. The assumption that a single IME client can be focused at the same time was made before Android IME APIs were introduced in Android 1.5 and many public APIs in `InputMethodService` have already relied heavily on that assumption (hereafter called "single-client scenario"). Updating `InputMethodService` class to support multi-client scenario is, however, quite challenging because:

 1. doing so would introduce an unacceptable amount of complexity into `InputMethodService`, which is already hard to maintain,
 2. IME developers still need to update their implementation to be able to support parallel requests from multiple focused IME client, which may require non-trivial redesign in their side (e.g. input decoder, typing history database, ...), and
 3. actual use cases for multi IME clients are expected to be evolved rapidly hence the new protocol is not yet stable and not yet ready to be exposed as public APIs.

Thus the first decision we made was that to support such special multi-display environments a new type of IME (hereafter called "multi-client IME") needs to be designed and implemented rather than reusing `InputMethodService` public class. On top of this decision, following decisions were also made:

 * Multi-client IME V1 will be built on top of private APIs. This means:
   * Multi-client IME must be pre-installed into the system. They cannot be distributed via application store since protocol compatibility is not guaranteed across devices and releases.
   * The system should trust multi-client IME to some extent. System integrators are responsible for making sure that the pre-installed multi-client IME works as expected.
 * Unlike `InputMethodService`, multiple multi-client IMEs cannot be enabled. The system pre-installs only one multi-client IME.
 * Punt some special features of Android IMEs (e.g. fullscreen mode, InputMethodSubtype, ...) from V1 goal unless someone actually requests those features for multi-client IME scenario.
 * Introduce `MultiClientInputMethodManagerService` (MCIMMS) for multi-client IME scenario and use it instead of `InputMethodManagerService` (IMMS) when a certain runtime flag is enabled at the device boot time. This means:
   * basically no risk for single-client scenario,
   * the feature can be easily deprecated, and
   * it forces us to rewrite IME system server, which is expected to be a good chance to reconsider what Android IME protocol should look like.
 * Most of form-factors such as Phones and TVs continue to use IMMS and support at most one focused IME client even under multi-display environment.


## How to test

For multi-client IME to properly work, an internal boolean resource `com.android.internal.R.bool.config_perDisplayFocusEnabled` needs to be `true`. Since this value cannot be overridden at the run time, you may need to rebuild the system image to enable per-display focus mode.

As for multi-client IME mode itself, you can enable multi-client IME mode just by setting a valid component name that supports multi-client IME protocol to the system property `persist.debug.multi_client_ime`, as long as `android.os.Build.IS_DEBUGGABLE` returns `true` and you can have root access. Reboot is required for this to take effect.

```shell
# Build and install a sample multi-client IME
make -j MultiClientInputMethod
adb install -r $OUT/system/priv-app/MultiClientInputMethod/MultiClientInputMethod.apk

# Enable multi-client IME for the side-loaded sample multi-client IME
adb root
adb shell setprop persist.debug.multi_client_ime com.example.android.multiclientinputmethod/.MultiClientInputMethod
adb reboot
```

To disable multi-client IME on non-supported devices again, just clear `persist.debug.multi_client_ime` as follows. Reboot is still required for this to take effect.

```shell
# Disable multi-client IME again
adb root
adb shell "setprop persist.debug.multi_client_ime ''"
adb reboot
```

## How to develop multi-client IMEs

There is a sample multi-client IME in `development/samples/MultiClientInputMethod/`.

## Versioning

Neither forward nor backward compatibility is guaranteed in multi-client IME APIs. The system integrator is responsible for making sure that both the system and pre-installed multi-client IME are compatible with each other every time the system image is updated.

## Implementation note

### Unsupported features

 * VR IME
   * `VrManager#setVrInputMethod()` system API is not supported.
 * InputMethodSubtype
   * Following APIs are not supported
     * `InputMethodManager#getEnabledInputMethodSubtypeList()`
     * `InputMethodManager#getCurrentInputMethodSubtype()`
     * `InputMethodManager#setCurrentInputMethodSubtype()`
     * `InputMethodManager#getShortcutInputMethodsAndSubtypes()`
     * `InputMethodManager#setAdditionalInputMethodSubtypes()`
     * `InputMethodManager#getLastInputMethodSubtype()`
     * `Settings.Secure#SELECTED_INPUT_METHOD_SUBTYPE`
 * IME switching
   * Following APIs are not supported
     * `InputMethodManager#showInputMethodPicker()`
     * `InputMethodManager#showInputMethodAndSubtypeEnabler()`
     * `InputMethodManager#setInputMethod()`
     * `InputMethodManager#setInputMethodAndSubtype()`
     * `InputMethodManager#switchToLastInputMethod()`
     * `InputMethodManager#switchToNextInputMethod()`
     * `InputMethodManager#shouldOfferSwitchingToNextInputMethod()`
     * `Settings.Secure#DEFAULT_INPUT_METHOD`
     * `Settings.Secure#ENABLED_INPUT_METHODS`
 * Direct-boot aware multi-client IME
   * Device manufacturer can work around this by integrating in-app keyboard into the initial unlock screen.
 * Full-screen mode
   * Following API always returns `false`.
     * `InputMethodManager#isFullscreenMode()`
 * Custom inset
   * For instance, floating IME cannot be implemented right now.
 * Custom touchable region (`InputMethodService.Insets#touchableRegion`)
 * Image Insertion API
   * `InputConnection#commitContent()` API is silently ignored.
 * `adb shell dumpsys` does not include any log from MCIMMS yet.

### Security

#### Root permission is required to enable MCIMMS on non-supported devices

In order to override `persist.debug.multi_client_ime` device property, an explicit root permission is needed.

#### Multi-client IME must be pre-installed

Multi-client IME must be pre-installed since it is considered as part of the system component. This is verified by checking `ApplicationInfo.FLAG_SYSTEM` bit. This security check can be bypassed when `Build.IS_DEBUGGABLE` is `true` so that IME developers can easily side-load their APKs during development phase.

```java
public final class MultiClientInputMethodManagerService {
    ...
    @Nullable
    private static InputMethodInfo queryInputMethod(Context context, @UserIdInt int userId,
            @Nullable ComponentName componentName) {

        ...

        if (! && (si.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            Slog.e(TAG, imeId + " must be pre-installed when Build.IS_DEBUGGABLE is false");
            return null;
        }
```
[services/core/java/com/android/server/inputmethod/MultiClientInputMethodManagerService.java](MultiClientInputMethodManagerService.java)


#### Integer handle vs IBinder token

Sometimes MCIMMS needs to issue certain types of identifiers to the multi-client IME so that the IME can later specify to which entity or resource it intends to access. A good example is the IME client identifier. Multi-client IME definitely need to be able to specify which IME client to be interacted with for certain operations. The problem is that MCIMMS cannot simply pass `IInputMethodClient` to the multi-client IME as an ID because it would allow the IME to make IPC calls to the IME client. For this kind of situations, we usually use `Binder` object just as a non-spoofable token. For instance, IMMS creates another 'Binder' token then pass it to the IME, instead of directly passing 'IWindow' Binder token.

```java
public class InputMethodManagerService extends IInputMethodManager.Stub
        implements ServiceConnection, Handler.Callback {
    ...
    @GuardedBy("mMethodMap")
    private final WeakHashMap<IBinder, IBinder> mImeTargetWindowMap = new WeakHashMap<>();

    ...

    @GuardedBy("mMethodMap")
    @NonNull
    InputBindResult attachNewInputLocked(@StartInputReason int startInputReason, boolean initial) {
        ...
        final Binder startInputToken = new Binder();
        final StartInputInfo info = new StartInputInfo(mCurToken, mCurId, startInputReason,
                !initial, mCurFocusedWindow, mCurAttribute, mCurFocusedWindowSoftInputMode,
                mCurSeq);
        mImeTargetWindowMap.put(startInputToken, mCurFocusedWindow);
        ...
    }

    ...

    @BinderThread
    private void reportStartInput(IBinder token, IBinder startInputToken) {
        if (!calledWithValidToken(token)) {
            return;
        }

        synchronized (mMethodMap) {
            final IBinder targetWindow = mImeTargetWindowMap.get(startInputToken);
            if (targetWindow != null && mLastImeTargetWindow != targetWindow) {
                mWindowManagerInternal.updateInputMethodTargetWindow(token, targetWindow);
            }
            mLastImeTargetWindow = targetWindow;
        }
    }
```
[services/core/java/com/android/server/inputmethod/InputMethodManagerService.java](InputMethodManagerService.java)

However, in MCIMMS, for certain cases we decided to use a simple integer token, which can be spoofable and can be messed up if integer overflow happens. This is because:

 * It does not make much sense to worry about malicious multi-client IMEs, because it is guaranteed to be a pre-installed system component.
 * Integer token is expected to be a more lightweight that `Binder` token.
 * For that use case, integer overflow is unrealistic.
 * Strict user separation is still enforced. Multi-client IMEs are still not allowed to interact with other users' resources by any means.

Currently the following IDs are implemented as integer tokens:

 * Client ID
 * Window Handle
   * Note that each IME client has its own Window Handle mapping table. Window Handle is valid only within the associated IME client.
