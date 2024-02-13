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

import android.app.Notification;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.graphics.drawable.Icon;
import android.graphics.Rect;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.hardware.fingerprint.IUdfpsRefreshRateRequestCallback;
import android.media.INearbyMediaDevicesProvider;
import android.media.MediaRoute2Info;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.KeyEvent;
import android.service.notification.StatusBarNotification;

import com.android.internal.logging.InstanceId;
import com.android.internal.statusbar.IAddTileResultCallback;
import com.android.internal.statusbar.ISessionListener;
import com.android.internal.statusbar.IStatusBar;
import com.android.internal.statusbar.IUndoMediaTransferCallback;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIconList;
import com.android.internal.statusbar.NotificationVisibility;

/** @hide */
interface IStatusBarService
{
    @UnsupportedAppUsage
    void expandNotificationsPanel();
    @UnsupportedAppUsage
    void collapsePanels();
    void togglePanel();
    @UnsupportedAppUsage
    void disable(int what, IBinder token, String pkg);
    void disable2(int what, IBinder token, String pkg);
    void disableForUser(in StatusBarManager.DisableInfo info, IBinder token, String pkg, int userId, String reason);

    int[] getDisableFlags(IBinder token, int userId);
    void setIcon(String slot, String iconPackage, int iconId, int iconLevel, String contentDescription);
    @UnsupportedAppUsage
    void setIconVisibility(String slot, boolean visible);
    @UnsupportedAppUsage
    void removeIcon(String slot);
    void setImeWindowStatus(int displayId, in IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher);
    void expandSettingsPanel(String subPanel);

    // ---- Methods below are for use by the status bar policy services ----
    // You need the STATUS_BAR_SERVICE permission
    RegisterStatusBarResult registerStatusBar(IStatusBar callbacks);
    void onPanelRevealed(boolean clearNotificationEffects, int numItems);
    void onPanelHidden();
    // Mark current notifications as "seen" and stop ringing, vibrating, blinking.
    oneway void clearNotificationEffects();
    void onNotificationClick(String key, in NotificationVisibility nv);
    void onNotificationActionClick(String key, int actionIndex, in Notification.Action action, in NotificationVisibility nv, boolean generatedByAssistant);
    void onNotificationError(String pkg, String tag, int id,
            int uid, int initialPid, String message, int userId);
    void onClearAllNotifications(int userId);
    void onNotificationClear(String pkg, int userId, String key,
            int dismissalSurface, int dismissalSentiment, in NotificationVisibility nv);
    void onNotificationVisibilityChanged( in NotificationVisibility[] newlyVisibleKeys,
            in NotificationVisibility[] noLongerVisibleKeys);
    void onNotificationExpansionChanged(in String key, in boolean userAction, in boolean expanded, in int notificationLocation);
    void onNotificationDirectReplied(String key);
    void onNotificationSmartSuggestionsAdded(String key, int smartReplyCount, int smartActionCount,
            boolean generatedByAsssistant, boolean editBeforeSending);
    void onNotificationSmartReplySent(in String key, in int replyIndex, in CharSequence reply,
            in int notificationLocation, boolean modifiedBeforeSending);
    void onNotificationSettingsViewed(String key);
    void onNotificationBubbleChanged(String key, boolean isBubble, int flags);
    void onBubbleMetadataFlagChanged(String key, int flags);
    void hideCurrentInputMethodForBubbles(int displayId);
    void grantInlineReplyUriPermission(String key, in Uri uri, in UserHandle user, String packageName);
    oneway void clearInlineReplyUriPermissions(String key);
    void onNotificationFeedbackReceived(String key, in Bundle feedback);

    void onGlobalActionsShown();
    void onGlobalActionsHidden();

    /**
     * These methods are needed for global actions control which the UI is shown in sysui.
     */
    void shutdown();
    void reboot(boolean safeMode);

    /** just restarts android without rebooting device. Used for some feature flags. */
    void restart();

    void addTile(in ComponentName tile);
    void remTile(in ComponentName tile);
    void clickTile(in ComponentName tile);
    @UnsupportedAppUsage
    void handleSystemKey(in KeyEvent key);
    int getLastSystemKey();

    /**
     * Methods to show toast messages for screen pinning
     */
    void showPinningEnterExitToast(boolean entering);
    void showPinningEscapeToast();

    // Used to show the authentication dialog (Biometrics, Device Credential)
    void showAuthenticationDialog(in PromptInfo promptInfo, IBiometricSysuiReceiver sysuiReceiver,
            in int[] sensorIds, boolean credentialAllowed, boolean requireConfirmation,
            int userId, long operationId, String opPackageName, long requestId);

    // Used to notify the authentication dialog that a biometric has been authenticated
    void onBiometricAuthenticated(int modality);
    // Used to set a temporary message, e.g. fingerprint not recognized, finger moved too fast, etc
    void onBiometricHelp(int modality, String message);
    // Used to show an error - the dialog will dismiss after a certain amount of time
    void onBiometricError(int modality, int error, int vendorCode);
    // Used to hide the authentication dialog, e.g. when the application cancels authentication
    void hideAuthenticationDialog(long requestId);
    // Used to notify the biometric service of events that occur outside of an operation.
    void setBiometicContextListener(in IBiometricContextListener listener);

    /**
     * Sets an instance of IUdfpsRefreshRateRequestCallback for UdfpsController.
     */
    void setUdfpsRefreshRateCallback(in IUdfpsRefreshRateRequestCallback callback);

    /**
     * Show a warning that the device is about to go to sleep due to user inactivity.
     */
    void showInattentiveSleepWarning();

    /**
     * Dismiss the warning that the device is about to go to sleep due to user inactivity.
     */
    void dismissInattentiveSleepWarning(boolean animated);

    /**
     * Notifies SystemUI to start tracing.
     */
    void startTracing();

    /**
     * Notifies SystemUI to stop tracing.
     */
    void stopTracing();

    /**
     * Returns whether SystemUI tracing is enabled.
     */
    boolean isTracing();

    /**
     * If true, suppresses the ambient display from showing. If false, re-enables the ambient
     * display.
     */
    void suppressAmbientDisplay(boolean suppress);

    /**
     * Send a request to SystemUI to put a given active tile in listening state
     */
    void requestTileServiceListeningState(in ComponentName componentName, int userId);

    void requestAddTile(in ComponentName componentName, in CharSequence label, in Icon icon, int userId, in IAddTileResultCallback callback);
    void cancelRequestAddTile(in String packageName);

    /**
    * Sets the navigation bar mode.
    *
    * @param navBarMode the mode of the navigation bar to be set.
    *
    * @hide
    */
    void setNavBarMode(int navBarMode);

    /**
    * Gets the navigation bar mode.
    *
    * @hide
    */
    int getNavBarMode();

    /**
    * Register a listener for certain sessions. Each session may be guarded by its own permission.
    */
    void registerSessionListener(int sessionFlags, in ISessionListener listener);
    void unregisterSessionListener(int sessionFlags, in ISessionListener listener);

    /**
    * Informs all registered listeners that a session has begun and has the following instanceId.
    * Can only be set by callers with certain permission based on the session type being updated.
    */
    void onSessionStarted(int sessionType, in InstanceId instanceId);
    void onSessionEnded(int sessionType, in InstanceId instanceId);

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

    /** Shows rear display educational dialog */
    void showRearDisplayDialog(int currentBaseState);
}
