/**
 * Copyright (c) 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.statusbar;

import android.app.ITransientNotificationCallback;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.graphics.Rect;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.fingerprint.IUdfpsHbmListener;
import android.media.INearbyMediaDevicesProvider;
import android.media.MediaRoute2Info;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.service.notification.StatusBarNotification;
import android.view.InsetsVisibilities;

import com.android.internal.statusbar.IAddTileResultCallback;
import com.android.internal.statusbar.IUndoMediaTransferCallback;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.view.AppearanceRegion;

/** @hide */
oneway interface IStatusBar
{
    void setIcon(String slot, in StatusBarIcon icon);
    void removeIcon(String slot);
    void disable(int displayId, int state1, int state2);
    void animateExpandNotificationsPanel();
    void animateExpandSettingsPanel(String subPanel);
    void animateCollapsePanels();
    void togglePanel();

    void showWirelessChargingAnimation(int batteryLevel);

    void setImeWindowStatus(int displayId, in IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher);
    void setWindowState(int display, int window, int state);

    void showRecentApps(boolean triggeredFromAltTab);
    void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);
    void toggleRecentApps();
    void toggleSplitScreen();
    void preloadRecentApps();
    void cancelPreloadRecentApps();
    void showScreenPinningRequest(int taskId);

    void dismissKeyboardShortcutsMenu();
    void toggleKeyboardShortcutsMenu(int deviceId);

    /**
     * Notifies System UI on the specified display that an app transition is pending to delay
     * applying some flags with visual impact until {@link #appTransitionReady} is called.
     *
     * @param displayId the id of the display to notify
     */
    void appTransitionPending(int displayId);

    /**
     * Notifies System UI on the specified display that a pending app transition has been cancelled.
     *
     * @param displayId the id of the display to notify
     */
    void appTransitionCancelled(int displayId);

    /**
     * Notifies System UI on the specified display that an app transition is now being executed.
     *
     * @param displayId the id of the display to notify
     * @param statusBarAnimationsStartTime the desired start time for all visual animations in the
     *        status bar caused by this app transition in uptime millis
     * @param statusBarAnimationsDuration the duration for all visual animations in the status
     *        bar caused by this app transition in millis
     */
    void appTransitionStarting(int displayId, long statusBarAnimationsStartTime,
            long statusBarAnimationsDuration);

    /**
     * Notifies System UI on the specified display that an app transition is done.
     *
     * @param displayId the id of the display to notify
     */
    void appTransitionFinished(int displayId);

    void showAssistDisclosure();
    void startAssist(in Bundle args);

    /**
     * Notifies the status bar that a camera launch gesture has been detected.
     *
     * @param source the identifier for the gesture, see {@link StatusBarManager}
     */
    void onCameraLaunchGestureDetected(int source);

    /**
     * Notifies the status bar that the Emergency Action launch gesture has been detected.
     *
     * TODO(b/169175022) Update method name and docs when feature name is locked.
     */
    void onEmergencyActionLaunchGestureDetected();

    /**
     * Shows the picture-in-picture menu if an activity is in picture-in-picture mode.
     */
    void showPictureInPictureMenu();

    /**
     * Shows the global actions menu.
     */
    void showGlobalActionsMenu();

    /**
     * Notifies the status bar that a new rotation suggestion is available.
     */
    void onProposedRotationChanged(int rotation, boolean isValid);

    /**
     * Set whether the top app currently hides the statusbar.
     *
     * @param hidesStatusBar whether it is being hidden
     */
    void setTopAppHidesStatusBar(boolean hidesStatusBar);

    void addQsTile(in ComponentName tile);
    void remQsTile(in ComponentName tile);
    void clickQsTile(in ComponentName tile);
    void handleSystemKey(in int key);

    /**
     * Methods to show toast messages for screen pinning
     */
    void showPinningEnterExitToast(boolean entering);
    void showPinningEscapeToast();

    void showShutdownUi(boolean isReboot, String reason);

    /**
    * Used to show the authentication dialog (Biometrics, Device Credential).
    */
    void showAuthenticationDialog(in PromptInfo promptInfo, IBiometricSysuiReceiver sysuiReceiver,
            in int[] sensorIds, boolean credentialAllowed, boolean requireConfirmation, int userId,
            long operationId, String opPackageName, long requestId, int multiSensorConfig);
    /**
    * Used to notify the authentication dialog that a biometric has been authenticated.
    */
    void onBiometricAuthenticated(int modality);
    /**
    * Used to set a temporary message, e.g. fingerprint not recognized, finger moved too fast, etc.
    */
    void onBiometricHelp(int modality, String message);
    /** Used to show an error - the dialog will dismiss after a certain amount of time. */
    void onBiometricError(int modality, int error, int vendorCode);
    /**
    * Used to hide the authentication dialog, e.g. when the application cancels authentication.
    */
    void hideAuthenticationDialog(long requestId);
    /* Used to notify the biometric service of events that occur outside of an operation. */
    void setBiometicContextListener(in IBiometricContextListener listener);

    /**
     * Sets an instance of IUdfpsHbmListener for UdfpsController.
     */
    void setUdfpsHbmListener(in IUdfpsHbmListener listener);

    /**
     * Notifies System UI that the display is ready to show system decorations.
     */
    void onDisplayReady(int displayId);

    /**
     * Notifies System UI whether the recents animation is running or not.
     */
    void onRecentsAnimationStateChanged(boolean running);

    /**
     * Notifies System UI side of system bar attribute change on the specified display.
     *
     * @param displayId the ID of the display to notify.
     * @param appearance the appearance of the focused window. The light top bar appearance is not
     *                   controlled here, but primaryAppearance and secondaryAppearance.
     * @param appearanceRegions a set of appearances which will be only applied in their own bounds.
     *                         This is for system bars which across multiple stack, e.g., status
     *                         bar, that the bar can have partial appearances in corresponding
     *                         stacks.
     * @param navbarColorManagedByIme {@code true} if navigation bar color is managed by IME.
     * @param behavior the behavior of the focused window.
     * @param requestedVisibilities the collection of the requested visibilities of system insets.
     * @param packageName the package name of the focused app.
     * @param letterboxDetails a set of letterbox details of apps visible on the screen.
     */
    void onSystemBarAttributesChanged(int displayId, int appearance,
            in AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            int behavior, in InsetsVisibilities requestedVisibilities, String packageName,
            in LetterboxDetails[] letterboxDetails);

    /**
     * Notifies System UI to show transient bars. The transient bars are system bars, e.g., status
     * bar and navigation bar which are temporarily visible to the user.
     *
     * @param displayId the ID of the display to notify.
     * @param types the internal insets types of the bars are about to show transiently.
     * @param isGestureOnSystemBar whether the gesture to show the transient bar was a gesture on
     *        one of the bars itself.
     */
    void showTransient(int displayId, in int[] types, boolean isGestureOnSystemBar);

    /**
     * Notifies System UI to abort the transient state of system bars, which prevents the bars being
     * hidden automatically. This is usually called when the app wants to show the permanent system
     * bars again.
     *
     * @param displayId the ID of the display to notify.
     * @param types the internal insets types of the bars are about to abort the transient state.
     */
    void abortTransient(int displayId, in int[] types);

    /**
     * Show a warning that the device is about to go to sleep due to user inactivity.
     */
    void showInattentiveSleepWarning();

    /**
     * Dismiss the warning that the device is about to go to sleep due to user inactivity.
     */
    void dismissInattentiveSleepWarning(boolean animated);

    /**
     * Displays a text toast.
     */
    void showToast(int uid, String packageName, IBinder token, CharSequence text,
            IBinder windowToken, int duration, @nullable ITransientNotificationCallback callback,
            int displayId);

    /**
     * Cancels toast with token {@code token} in {@code packageName}.
     */
    void hideToast(String packageName, IBinder token);

    /**
     * Notifies SystemUI to start tracing.
     */
    void startTracing();

    /**
     * Notifies SystemUI to stop tracing.
     */
    void stopTracing();

    /**
     * If true, suppresses the ambient display from showing. If false, re-enables the ambient
     * display.
     */
    void suppressAmbientDisplay(boolean suppress);

    /**
     * Requests {@link WindowMagnification} to set window magnification connection through
     * {@link AccessibilityManager#setWindowMagnificationConnection(IWindowMagnificationConnection)}
     *
     * @param connect {@code true} if needs connection, otherwise set the connection to null.
     */
    void requestWindowMagnificationConnection(boolean connect);

    /**
     * Allow for pass-through arguments from `adb shell cmd statusbar <args>`, and write to the
     * file descriptor passed in.
     */
     void passThroughShellCommand(in String[] args, in ParcelFileDescriptor pfd);

    /**
     * Enables/disables the navigation bar luma sampling.
     *
     * @param displayId the id of the display to notify.
     * @param enable {@code true} if enable, otherwise set to {@code false}.
     */
    void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable);

    /**
     * Triggers a GC in the system and status bar.
     */
    void runGcForTest();

    /**
     * Send a request to SystemUI to put a given active tile in listening state
     */
    void requestTileServiceListeningState(in ComponentName componentName);

    void requestAddTile(in ComponentName componentName, in CharSequence appName, in CharSequence label, in Icon icon, in IAddTileResultCallback callback);
    void cancelRequestAddTile(in String packageName);

    /** Notifies System UI about an update to the media tap-to-transfer sender state. */
    void updateMediaTapToTransferSenderDisplay(
        int displayState,
        in MediaRoute2Info routeInfo,
        in IUndoMediaTransferCallback undoCallback);

    /** Notifies System UI about an update to the media tap-to-transfer receiver state. */
    void updateMediaTapToTransferReceiverDisplay(
        int displayState,
        in MediaRoute2Info routeInfo,
        in Icon appIcon,
        in CharSequence appName);

    /** Registers a nearby media devices provider. */
    void registerNearbyMediaDevicesProvider(in INearbyMediaDevicesProvider provider);

    /** Unregisters a nearby media devices provider. */
    void unregisterNearbyMediaDevicesProvider(in INearbyMediaDevicesProvider provider);

    /** Dump protos from SystemUI. The proto definition is defined there */
    void dumpProto(in String[] args, in ParcelFileDescriptor pfd);

    /** Shows rear display educational dialog */
    void showRearDisplayDialog(int currentBaseState);

    /** Called when requested to go to fullscreen from the active split app. */
    void goToFullscreenFromSplit();

    /**
     * Enters stage split from a current running app.
     *
     * @param leftOrTop indicates where the stage split is.
     */
    void enterStageSplitFromRunningApp(boolean leftOrTop);

    /**
     * Shows the media output switcher dialog.
     *
     * @param packageName of the session for which the output switcher is shown.
     */
    void showMediaOutputSwitcher(String packageName);
}
