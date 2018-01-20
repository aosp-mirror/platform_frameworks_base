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

import android.content.ComponentName;
import android.graphics.Rect;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import com.android.internal.statusbar.StatusBarIcon;

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

    /**
     * Notifies System UI side of a visibility flag change on the specified display.
     *
     * @param displayId the id of the display to notify
     * @param vis the visibility flags except SYSTEM_UI_FLAG_LIGHT_STATUS_BAR which will be reported
     *            separately in fullscreenStackVis and dockedStackVis
     * @param fullscreenStackVis the flags which only apply in the region of the fullscreen stack,
     *                           which is currently only SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
     * @param dockedStackVis the flags that only apply in the region of the docked stack, which is
     *                       currently only SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
     * @param mask which flags to change
     * @param fullscreenBounds the current bounds of the fullscreen stack, in screen coordinates
     * @param dockedBounds the current bounds of the docked stack, in screen coordinates
     * @param navbarColorManagedByIme {@code true} if navigation bar color is managed by IME.
     */
    void setSystemUiVisibility(int displayId, int vis, int fullscreenStackVis, int dockedStackVis,
            int mask, in Rect fullscreenBounds, in Rect dockedBounds,
            boolean navbarColorManagedByIme);

    void topAppWindowChanged(int displayId, boolean menuVisible);
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

    void showShutdownUi(boolean isReboot, String reason, boolean rebootCustom);

    // Used to show the dialog when BiometricService starts authentication
    void showBiometricDialog(in Bundle bundle, IBiometricServiceReceiverInternal receiver, int type,
            boolean requireConfirmation, int userId);
    // Used to hide the dialog when a biometric is authenticated
    void onBiometricAuthenticated(boolean authenticated, String failureReason);
    // Used to set a temporary message, e.g. fingerprint not recognized, finger moved too fast, etc
    void onBiometricHelp(String message);
    // Used to set a message - the dialog will dismiss after a certain amount of time
    void onBiometricError(String error);
    // Used to hide the biometric dialog when the AuthenticationClient is stopped
    void hideBiometricDialog();
    // Used to show or hide in display fingerprint view
    void showInDisplayFingerprintView();
    void hideInDisplayFingerprintView();

    /**
     * Notifies System UI that the display is ready to show system decorations.
     */
    void onDisplayReady(int displayId);

    /**
     * Notifies System UI whether the recents animation is running or not.
     */
    void onRecentsAnimationStateChanged(boolean running);
}
