/**
 * Copyright (c) 2013, The Android Open Source Project
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

package com.android.server.statusbar;

import android.annotation.Nullable;
import android.app.ITransientNotificationCallback;
import android.content.ComponentName;
import android.hardware.fingerprint.IUdfpsRefreshRateRequestCallback;
import android.os.Bundle;
import android.os.IBinder;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;

import com.android.internal.statusbar.LetterboxDetails;
import com.android.internal.view.AppearanceRegion;
import com.android.server.notification.NotificationDelegate;

public interface StatusBarManagerInternal {
    void setNotificationDelegate(NotificationDelegate delegate);
    void showScreenPinningRequest(int taskId);
    void showAssistDisclosure();

    void preloadRecentApps();

    void cancelPreloadRecentApps();

    void showRecentApps(boolean triggeredFromAltTab);

    void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey);

    /** Collapses the notification shade. */
    void collapsePanels();

    void dismissKeyboardShortcutsMenu();
    void toggleKeyboardShortcutsMenu(int deviceId);

    /**
     * Used by InputMethodManagerService to notify the IME status.
     *
     * @param displayId The display to which the IME is bound to.
     * @param token The IME token.
     * @param vis Bit flags about the IME visibility.
     *            (e.g. {@link android.inputmethodservice.InputMethodService#IME_ACTIVE})
     * @param backDisposition Bit flags about the IME back disposition.
     *         (e.g. {@link android.inputmethodservice.InputMethodService#BACK_DISPOSITION_DEFAULT})
     * @param showImeSwitcher {@code true} when the IME switcher button should be shown.
     */
    void setImeWindowStatus(int displayId, IBinder token, int vis,
            int backDisposition, boolean showImeSwitcher);

    /**
     * See {@link android.app.StatusBarManager#setIcon(String, int, int, String)}.
     */
    void setIcon(String slot, String iconPackage, int iconId, int iconLevel,
            String contentDescription);

    /**
     * See {@link android.app.StatusBarManager#setIconVisibility(String, boolean)}.
     */
    void setIconVisibility(String slot, boolean visibility);

    void showChargingAnimation(int batteryLevel);

    /**
     * Show picture-in-picture menu.
     */
    void showPictureInPictureMenu();

    void setWindowState(int displayId, int window, int state);

    /**
     * Notifies the status bar that an app transition is pending to delay applying some flags with
     * visual impact until {@link #appTransitionReady} is called.
     *
     * @param displayId the ID of the display which has this event.
     */
    void appTransitionPending(int displayId);

    /**
     * Notifies the status bar that a pending app transition has been cancelled.
     *
     * @param displayId the ID of the display which has this event.
     */
    void appTransitionCancelled(int displayId);

    /**
     * Notifies the status bar that an app transition is now being executed.
     *
     * @param displayId the ID of the display which has this event.
     * @param statusBarAnimationsStartTime the desired start time for all visual animations in the
     *        status bar caused by this app transition in uptime millis
     * @param statusBarAnimationsDuration the duration for all visual animations in the status
     *        bar caused by this app transition in millis
     */
    void appTransitionStarting(int displayId, long statusBarAnimationsStartTime,
            long statusBarAnimationsDuration);

    void startAssist(Bundle args);
    void onCameraLaunchGestureDetected(int source);
    void setDisableFlags(int displayId, int flags, String cause);
    void toggleSplitScreen();
    void appTransitionFinished(int displayId);

    /**
     * Notifies the status bar that a Emergency Action launch gesture has been detected.
     *
     * TODO (b/169175022) Update method name and docs when feature name is locked.
     */
    void onEmergencyActionLaunchGestureDetected();

    /** Toggle the task bar stash state. */
    void toggleTaskbar();

    /** Toggle recents. */
    void toggleRecentApps();

    void setCurrentUser(int newUserId);

    /**
     * Set whether the top app currently hides the statusbar.
     *
     * @param hidesStatusBar whether it is being hidden
     */
    void setTopAppHidesStatusBar(boolean hidesStatusBar);

    boolean showShutdownUi(boolean isReboot, String requestString);

    /**
     * Notify system UI the immersive prompt should be dismissed as confirmed, and the confirmed
     * status should be saved without user clicking on the button. This could happen when a user
     * swipe on the edge with the confirmation prompt showing.
     */
    void confirmImmersivePrompt();

    /**
     * Notify System UI that the system get into or exit immersive mode.
     * @param rootDisplayAreaId The changed display area Id.
     * @param isImmersiveMode {@code true} if the display area get into immersive mode.
     */
    void immersiveModeChanged(int rootDisplayAreaId, boolean isImmersiveMode);

    /**
     * Show a rotation suggestion that a user may approve to rotate the screen.
     *
     * @param rotation rotation suggestion
     */
    void onProposedRotationChanged(int rotation, boolean isValid);

    /**
     * Notifies System UI that the display is ready to show system decorations.
     *
     * @param displayId display ID
     */
    void onDisplayReady(int displayId);

    /**
     * Notifies System UI whether the recents animation is running.
     */
    void onRecentsAnimationStateChanged(boolean running);

    /** @see com.android.internal.statusbar.IStatusBar#onSystemBarAttributesChanged */
    void onSystemBarAttributesChanged(int displayId, @Appearance int appearance,
            AppearanceRegion[] appearanceRegions, boolean navbarColorManagedByIme,
            @Behavior int behavior, @InsetsType int requestedVisibleTypes, String packageName,
            LetterboxDetails[] letterboxDetails);

    /** @see com.android.internal.statusbar.IStatusBar#showTransient */
    void showTransient(int displayId, @InsetsType int types, boolean isGestureOnSystemBar);

    /** @see com.android.internal.statusbar.IStatusBar#abortTransient */
    void abortTransient(int displayId, @InsetsType int types);

    /**
     * @see com.android.internal.statusbar.IStatusBar#showToast(String, IBinder, CharSequence,
     * IBinder, int, ITransientNotificationCallback, int)
     */
    void showToast(int uid, String packageName, IBinder token, CharSequence text,
            IBinder windowToken, int duration,
            @Nullable ITransientNotificationCallback textCallback, int displayId);

    /** @see com.android.internal.statusbar.IStatusBar#hideToast(String, IBinder)  */
    void hideToast(String packageName, IBinder token);

    /**
     * @see com.android.internal.statusbar.IStatusBar#requestMagnificationConnection(boolean
     * request)
     */
    boolean requestMagnificationConnection(boolean request);

    /**
     * @see com.android.internal.statusbar.IStatusBar#setNavigationBarLumaSamplingEnabled(int,
     * boolean)
     */
    void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable);

    /**
     * Sets the system-wide callback for UDFPS refresh rate changes.
     *
     * @see com.android.internal.statusbar.IStatusBar#setUdfpsRefreshRateCallback
     * (IUdfpsRefreshRateRequestCallback)
     */
    void setUdfpsRefreshRateCallback(IUdfpsRefreshRateRequestCallback callback);

    /**
     * Shows the rear display educational dialog
     *
     * @see com.android.internal.statusbar.IStatusBar#showRearDisplayDialog
     */
    void showRearDisplayDialog(int currentBaseState);

    /**
     * Called when requested to go to fullscreen from the focused app.
     *
     * @param displayId of the current display.
     */
    void moveFocusedTaskToFullscreen(int displayId);

    /**
     * Enters stage split from a current running app.
     *
     * @see com.android.internal.statusbar.IStatusBar#moveFocusedTaskToStageSplit
     */
    void moveFocusedTaskToStageSplit(int displayId, boolean leftOrTop);

    /**
     * Change the split screen focus to the left / top app or the right / bottom app based on
     * {@param leftOrTop}.
     *
     * @see com.android.internal.statusbar.IStatusBar#setSplitscreenFocus
     */
    void setSplitscreenFocus(boolean leftOrTop);

    /**
     * Shows the media output switcher dialog.
     *
     * @param packageName of the session for which the output switcher is shown.
     * @see com.android.internal.statusbar.IStatusBar#showMediaOutputSwitcher
     */
    void showMediaOutputSwitcher(String packageName);

    /**
     * Add a tile to the Quick Settings Panel
     * @param tile the ComponentName of the {@link android.service.quicksettings.TileService}
     * @param end if true, the tile will be added at the end. If false, at the beginning.
     */
    void addQsTileToFrontOrEnd(ComponentName tile, boolean end);

    /**
     * Remove the tile from the Quick Settings Panel
     * @param tile the ComponentName of the {@link android.service.quicksettings.TileService}
     */
    void removeQsTile(ComponentName tile);

    /**
     * Called when requested to enter desktop from an app.
     */
    void enterDesktop(int displayId);
}
