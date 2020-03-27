/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.accessibility;

import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_GLOBAL_ACTIONS;

import android.accessibilityservice.AccessibilityService;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUI;
import com.android.systemui.recents.Recents;
import com.android.systemui.statusbar.phone.StatusBar;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Class to register system actions with accessibility framework.
 */
@Singleton
public class SystemActions extends SystemUI {
    private static final String TAG = "SystemActions";
    // TODO(b/147916452): add implementation on launcher side to register this action.

    /**
     * Action ID to go back.
     */
    private static final int SYSTEM_ACTION_ID_BACK = AccessibilityService.GLOBAL_ACTION_BACK; // = 1

    /**
     * Action ID to go home.
     */
    private static final int SYSTEM_ACTION_ID_HOME = AccessibilityService.GLOBAL_ACTION_HOME; // = 2

    /**
     * Action ID to toggle showing the overview of recent apps. Will fail on platforms that don't
     * show recent apps.
     */
    private static final int SYSTEM_ACTION_ID_RECENTS =
            AccessibilityService.GLOBAL_ACTION_RECENTS; // = 3

    /**
     * Action ID to open the notifications.
     */
    private static final int SYSTEM_ACTION_ID_NOTIFICATIONS =
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; // = 4

    /**
     * Action ID to open the quick settings.
     */
    private static final int SYSTEM_ACTION_ID_QUICK_SETTINGS =
            AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; // = 5

    /**
     * Action ID to open the power long-press dialog.
     */
    private static final int SYSTEM_ACTION_ID_POWER_DIALOG =
            AccessibilityService.GLOBAL_ACTION_POWER_DIALOG; // = 6

    /**
     * Action ID to toggle docking the current app's window
     */
    private static final int SYSTEM_ACTION_ID_TOGGLE_SPLIT_SCREEN =
            AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN; // = 7

    /**
     * Action ID to lock the screen
     */
    private static final int SYSTEM_ACTION_ID_LOCK_SCREEN =
            AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN; // = 8

    /**
     * Action ID to take a screenshot
     */
    private static final int SYSTEM_ACTION_ID_TAKE_SCREENSHOT =
            AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT; // = 9

    /**
     * Action ID to show accessibility menu
     */
    private static final int SYSTEM_ACTION_ID_ACCESSIBILITY_MENU = 10;

    private Recents mRecents;
    private StatusBar mStatusBar;
    private SystemActionsBroadcastReceiver mReceiver;

    @Inject
    public SystemActions(Context context) {
        super(context);
        mRecents = Dependency.get(Recents.class);
        mStatusBar = Dependency.get(StatusBar.class);
        mReceiver = new SystemActionsBroadcastReceiver();
    }

    @Override
    public void start() {
        mContext.registerReceiverForAllUsers(mReceiver, mReceiver.createIntentFilter(), null, null);

        // TODO(b/148087487): update the icon used below to a valid one
        RemoteAction actionBack = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_back_label),
                mContext.getString(R.string.accessibility_system_action_back_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_BACK));
        RemoteAction actionHome = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_home_label),
                mContext.getString(R.string.accessibility_system_action_home_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_HOME));

        RemoteAction actionRecents = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_recents_label),
                mContext.getString(R.string.accessibility_system_action_recents_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_RECENTS));

        RemoteAction actionNotifications = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_notifications_label),
                mContext.getString(R.string.accessibility_system_action_notifications_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_NOTIFICATIONS));

        RemoteAction actionQuickSettings = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_quick_settings_label),
                mContext.getString(R.string.accessibility_system_action_quick_settings_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_QUICK_SETTINGS));

        RemoteAction actionPowerDialog = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_power_dialog_label),
                mContext.getString(R.string.accessibility_system_action_power_dialog_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_POWER_DIALOG));

        RemoteAction actionToggleSplitScreen = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_toggle_split_screen_label),
                mContext.getString(R.string.accessibility_system_action_toggle_split_screen_label),
                mReceiver.createPendingIntent(
                        mContext,
                        SystemActionsBroadcastReceiver.INTENT_ACTION_TOGGLE_SPLIT_SCREEN));

        RemoteAction actionLockScreen = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_lock_screen_label),
                mContext.getString(R.string.accessibility_system_action_lock_screen_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_LOCK_SCREEN));

        RemoteAction actionTakeScreenshot = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_screenshot_label),
                mContext.getString(R.string.accessibility_system_action_screenshot_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_TAKE_SCREENSHOT));

        RemoteAction actionAccessibilityMenu = new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(R.string.accessibility_system_action_accessibility_menu_label),
                mContext.getString(R.string.accessibility_system_action_accessibility_menu_label),
                mReceiver.createPendingIntent(
                        mContext, SystemActionsBroadcastReceiver.INTENT_ACTION_ACCESSIBILITY_MENU));

        AccessibilityManager am = (AccessibilityManager) mContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE);

        am.registerSystemAction(actionBack, SYSTEM_ACTION_ID_BACK);
        am.registerSystemAction(actionHome, SYSTEM_ACTION_ID_HOME);
        am.registerSystemAction(actionRecents, SYSTEM_ACTION_ID_RECENTS);
        am.registerSystemAction(actionNotifications, SYSTEM_ACTION_ID_NOTIFICATIONS);
        am.registerSystemAction(actionQuickSettings, SYSTEM_ACTION_ID_QUICK_SETTINGS);
        am.registerSystemAction(actionPowerDialog, SYSTEM_ACTION_ID_POWER_DIALOG);
        am.registerSystemAction(actionToggleSplitScreen, SYSTEM_ACTION_ID_TOGGLE_SPLIT_SCREEN);
        am.registerSystemAction(actionLockScreen, SYSTEM_ACTION_ID_LOCK_SCREEN);
        am.registerSystemAction(actionTakeScreenshot, SYSTEM_ACTION_ID_TAKE_SCREENSHOT);
        am.registerSystemAction(actionAccessibilityMenu, SYSTEM_ACTION_ID_ACCESSIBILITY_MENU);
    }

    private void handleBack() {
        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_BACK);
    }

    private void handleHome() {
        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_HOME);
    }

    private void sendDownAndUpKeyEvents(int keyCode) {
        final long downTime = SystemClock.uptimeMillis();
        sendKeyEventIdentityCleared(keyCode, KeyEvent.ACTION_DOWN, downTime, downTime);
        sendKeyEventIdentityCleared(
                keyCode, KeyEvent.ACTION_UP, downTime, SystemClock.uptimeMillis());
    }

    private void sendKeyEventIdentityCleared(int keyCode, int action, long downTime, long time) {
        KeyEvent event = KeyEvent.obtain(downTime, time, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD, null);
        InputManager.getInstance()
                .injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        event.recycle();
    }

    private void handleRecents() {
        mRecents.toggleRecentApps();
    }

    private void handleNotifications() {
        mStatusBar.animateExpandNotificationsPanel();
    }

    private void handleQuickSettings() {
        mStatusBar.animateExpandSettingsPanel(null);
    }

    private void handlePowerDialog() {
        IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();

        try {
            windowManager.showGlobalActions();
        } catch (RemoteException e) {
            Log.e(TAG, "failed to display power dialog.");
        }
    }

    private void handleToggleSplitScreen() {
        mStatusBar.toggleSplitScreen();
    }

    private void handleLockScreen() {
        IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();

        mContext.getSystemService(PowerManager.class).goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_ACCESSIBILITY, 0);
        try {
            windowManager.lockNow(null);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to lock screen.");
        }
    }

    private void handleTakeScreenshot() {
        ScreenshotHelper screenshotHelper = new ScreenshotHelper(mContext);
        screenshotHelper.takeScreenshot(WindowManager.TAKE_SCREENSHOT_FULLSCREEN, true, true,
                SCREENSHOT_GLOBAL_ACTIONS, new Handler(Looper.getMainLooper()), null);
    }

    private void handleAccessibilityMenu() {
        AccessibilityManager.getInstance(mContext).notifyAccessibilityButtonClicked(
                Display.DEFAULT_DISPLAY);
    }

    private class SystemActionsBroadcastReceiver extends BroadcastReceiver {
        private static final String INTENT_ACTION_BACK = "SYSTEM_ACTION_BACK";
        private static final String INTENT_ACTION_HOME = "SYSTEM_ACTION_HOME";
        private static final String INTENT_ACTION_RECENTS = "SYSTEM_ACTION_RECENTS";
        private static final String INTENT_ACTION_NOTIFICATIONS = "SYSTEM_ACTION_NOTIFICATIONS";
        private static final String INTENT_ACTION_QUICK_SETTINGS = "SYSTEM_ACTION_QUICK_SETTINGS";
        private static final String INTENT_ACTION_POWER_DIALOG = "SYSTEM_ACTION_POWER_DIALOG";
        private static final String INTENT_ACTION_TOGGLE_SPLIT_SCREEN =
                "SYSTEM_ACTION_TOGGLE_SPLIT_SCREEN";
        private static final String INTENT_ACTION_LOCK_SCREEN = "SYSTEM_ACTION_LOCK_SCREEN";
        private static final String INTENT_ACTION_TAKE_SCREENSHOT = "SYSTEM_ACTION_TAKE_SCREENSHOT";
        private static final String INTENT_ACTION_ACCESSIBILITY_MENU =
                "SYSTEM_ACTION_ACCESSIBILITY_MENU";

        private PendingIntent createPendingIntent(Context context, String intentAction) {
            switch (intentAction) {
                case INTENT_ACTION_BACK:
                case INTENT_ACTION_HOME:
                case INTENT_ACTION_RECENTS:
                case INTENT_ACTION_NOTIFICATIONS:
                case INTENT_ACTION_QUICK_SETTINGS:
                case INTENT_ACTION_POWER_DIALOG:
                case INTENT_ACTION_TOGGLE_SPLIT_SCREEN:
                case INTENT_ACTION_LOCK_SCREEN:
                case INTENT_ACTION_TAKE_SCREENSHOT:
                case INTENT_ACTION_ACCESSIBILITY_MENU: {
                    Intent intent = new Intent(intentAction);
                    return PendingIntent.getBroadcast(context, 0, intent, 0);
                }
                default:
                    break;
            }
            return null;
        }

        private IntentFilter createIntentFilter() {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(INTENT_ACTION_BACK);
            intentFilter.addAction(INTENT_ACTION_HOME);
            intentFilter.addAction(INTENT_ACTION_RECENTS);
            intentFilter.addAction(INTENT_ACTION_NOTIFICATIONS);
            intentFilter.addAction(INTENT_ACTION_QUICK_SETTINGS);
            intentFilter.addAction(INTENT_ACTION_POWER_DIALOG);
            intentFilter.addAction(INTENT_ACTION_TOGGLE_SPLIT_SCREEN);
            intentFilter.addAction(INTENT_ACTION_LOCK_SCREEN);
            intentFilter.addAction(INTENT_ACTION_TAKE_SCREENSHOT);
            intentFilter.addAction(INTENT_ACTION_ACCESSIBILITY_MENU);
            return intentFilter;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String intentAction = intent.getAction();
            switch (intentAction) {
                case INTENT_ACTION_BACK: {
                    handleBack();
                    break;
                }
                case INTENT_ACTION_HOME: {
                    handleHome();
                    break;
                }
                case INTENT_ACTION_RECENTS: {
                    handleRecents();
                    break;
                }
                case INTENT_ACTION_NOTIFICATIONS: {
                    handleNotifications();
                    break;
                }
                case INTENT_ACTION_QUICK_SETTINGS: {
                    handleQuickSettings();
                    break;
                }
                case INTENT_ACTION_POWER_DIALOG: {
                    handlePowerDialog();
                    break;
                }
                case INTENT_ACTION_TOGGLE_SPLIT_SCREEN: {
                    handleToggleSplitScreen();
                    break;
                }
                case INTENT_ACTION_LOCK_SCREEN: {
                    handleLockScreen();
                    break;
                }
                case INTENT_ACTION_TAKE_SCREENSHOT: {
                    handleTakeScreenshot();
                    break;
                }
                case INTENT_ACTION_ACCESSIBILITY_MENU: {
                    handleAccessibilityMenu();
                    break;
                }
                default:
                    break;
            }
        }
    }
}
