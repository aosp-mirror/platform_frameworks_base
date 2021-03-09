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
import android.content.ComponentName;
import android.graphics.Rect;
import android.hardware.biometrics.IBiometricSysuiReceiver;
import android.hardware.biometrics.PromptInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import com.android.internal.statusbar.IStatusBar;
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
    void disableForUser(int what, IBinder token, String pkg, int userId);
    void disable2(int what, IBinder token, String pkg);
    void disable2ForUser(int what, IBinder token, String pkg, int userId);
    int[] getDisableFlags(IBinder token, int userId);
    void setIcon(String slot, String iconPackage, int iconId, int iconLevel, String contentDescription);
    @UnsupportedAppUsage
    void setIconVisibility(String slot, boolean visible);
    @UnsupportedAppUsage
    void removeIcon(String slot);
    void setImeWindowStatus(int displayId, in IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher, boolean isMultiClientImeEnabled);
    void expandSettingsPanel(String subPanel);

    // ---- Methods below are for use by the status bar policy services ----
    // You need the STATUS_BAR_SERVICE permission
    RegisterStatusBarResult registerStatusBar(IStatusBar callbacks);
    void onPanelRevealed(boolean clearNotificationEffects, int numItems);
    void onPanelHidden();
    // Mark current notifications as "seen" and stop ringing, vibrating, blinking.
    void clearNotificationEffects();
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
    void onBubbleNotificationSuppressionChanged(String key, boolean isNotifSuppressed, boolean isBubbleSuppressed);
    void hideCurrentInputMethodForBubbles();
    void grantInlineReplyUriPermission(String key, in Uri uri, in UserHandle user, String packageName);
    void clearInlineReplyUriPermissions(String key);
    void onNotificationFeedbackReceived(String key, in Bundle feedback);

    void onGlobalActionsShown();
    void onGlobalActionsHidden();

    /**
     * These methods are needed for global actions control which the UI is shown in sysui.
     */
    void shutdown();
    void reboot(boolean safeMode);

    void addTile(in ComponentName tile);
    void remTile(in ComponentName tile);
    void clickTile(in ComponentName tile);
    @UnsupportedAppUsage
    void handleSystemKey(in int key);

    /**
     * Methods to show toast messages for screen pinning
     */
    void showPinningEnterExitToast(boolean entering);
    void showPinningEscapeToast();

    // Used to show the authentication dialog (Biometrics, Device Credential)
    void showAuthenticationDialog(in PromptInfo promptInfo, IBiometricSysuiReceiver sysuiReceiver,
            in int[] sensorIds, boolean credentialAllowed, boolean requireConfirmation,
            int userId, String opPackageName,long operationId);

    // Used to notify the authentication dialog that a biometric has been authenticated
    void onBiometricAuthenticated();
    // Used to set a temporary message, e.g. fingerprint not recognized, finger moved too fast, etc
    void onBiometricHelp(String message);
    // Used to show an error - the dialog will dismiss after a certain amount of time
    void onBiometricError(int modality, int error, int vendorCode);
    // Used to hide the authentication dialog, e.g. when the application cancels authentication
    void hideAuthenticationDialog();

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
}
