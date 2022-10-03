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
import android.hardware.fingerprint.IUdfpsHbmListener;
import android.os.Bundle;
import android.os.IBinder;
import android.view.InsetsState.InternalInsetsType;
import android.view.InsetsVisibilities;
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
            @Behavior int behavior, InsetsVisibilities requestedVisibilities, String packageName,
            LetterboxDetails[] letterboxDetails);

    /** @see com.android.internal.statusbar.IStatusBar#showTransient */
    void showTransient(int displayId, @InternalInsetsType int[] types,
            boolean isGestureOnSystemBar);

    /** @see com.android.internal.statusbar.IStatusBar#abortTransient */
    void abortTransient(int displayId, @InternalInsetsType int[] types);

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
     * @see com.android.internal.statusbar.IStatusBar#requestWindowMagnificationConnection(boolean
     * request)
     */
    boolean requestWindowMagnificationConnection(boolean request);

    /**
     * @see com.android.internal.statusbar.IStatusBar#setNavigationBarLumaSamplingEnabled(int,
     * boolean)
     */
    void setNavigationBarLumaSamplingEnabled(int displayId, boolean enable);

    /**
     * Sets the system-wide listener for UDFPS HBM status changes.
     *
     * @see com.android.internal.statusbar.IStatusBar#setUdfpsHbmListener(IUdfpsHbmListener)
     */
    void setUdfpsHbmListener(IUdfpsHbmListener listener);
}
