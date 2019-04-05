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
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;

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
            boolean showImeSwitcher);
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
    void onNotificationClear(String pkg, String tag, int id, int userId, String key,
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
    void setSystemUiVisibility(int displayId, int vis, int mask, String cause);
    void onNotificationBubbleChanged(String key, boolean isBubble);

    void onGlobalActionsShown();
    void onGlobalActionsHidden();

    /**
     * These methods are needed for global actions control which the UI is shown in sysui.
     */
    void shutdown();
    void reboot(boolean safeMode, String reason);

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
}
