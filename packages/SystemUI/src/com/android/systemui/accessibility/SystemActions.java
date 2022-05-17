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

import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS;

import static com.android.internal.accessibility.common.ShortcutConstants.CHOOSER_PACKAGE_NAME;

import android.accessibilityservice.AccessibilityService;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
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
import com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity;
import com.android.internal.util.ScreenshotHelper;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.recents.Recents;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.StatusBarWindowCallback;
import com.android.systemui.util.Assert;

import java.util.Locale;
import java.util.Optional;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Class to register system actions with accessibility framework.
 */
@SysUISingleton
public class SystemActions extends CoreStartable {
    private static final String TAG = "SystemActions";

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
     * Action ID to send the KEYCODE_HEADSETHOOK KeyEvent, which is used to answer/hang up calls and
     * play/stop media
     */
    private static final int SYSTEM_ACTION_ID_KEYCODE_HEADSETHOOK =
            AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK; // = 10

    /**
     * Action ID to trigger the accessibility button
     */
    public static final int SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON =
            AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON; // 11

    /**
     * Action ID to show accessibility button's menu of services
     */
    public static final int SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON_CHOOSER =
            AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON_CHOOSER; // 12

    public static final int SYSTEM_ACTION_ID_ACCESSIBILITY_SHORTCUT =
            AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT; // 13

    public static final int SYSTEM_ACTION_ID_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE =
            AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE; // 15

    /**
     * Action ID to trigger the dpad up button
     */
    private static final int SYSTEM_ACTION_ID_DPAD_UP =
            AccessibilityService.GLOBAL_ACTION_DPAD_UP; // 16

    /**
     * Action ID to trigger the dpad down button
     */
    private static final int SYSTEM_ACTION_ID_DPAD_DOWN =
            AccessibilityService.GLOBAL_ACTION_DPAD_DOWN; // 17

    /**
     * Action ID to trigger the dpad left button
     */
    private static final int SYSTEM_ACTION_ID_DPAD_LEFT =
            AccessibilityService.GLOBAL_ACTION_DPAD_LEFT; // 18

    /**
     * Action ID to trigger the dpad right button
     */
    private static final int SYSTEM_ACTION_ID_DPAD_RIGHT =
            AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT; // 19

    /**
     * Action ID to trigger dpad center keyevent
     */
    private static final int SYSTEM_ACTION_ID_DPAD_CENTER =
            AccessibilityService.GLOBAL_ACTION_DPAD_CENTER; // 20

    private static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";

    private final SystemActionsBroadcastReceiver mReceiver;
    private final Optional<Recents> mRecentsOptional;
    private Locale mLocale;
    private final AccessibilityManager mA11yManager;
    private final Lazy<Optional<CentralSurfaces>> mCentralSurfacesOptionalLazy;
    private final NotificationShadeWindowController mNotificationShadeController;
    private final StatusBarWindowCallback mNotificationShadeCallback;
    private boolean mDismissNotificationShadeActionRegistered;

    @Inject
    public SystemActions(Context context,
            NotificationShadeWindowController notificationShadeController,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            Optional<Recents> recentsOptional) {
        super(context);
        mRecentsOptional = recentsOptional;
        mReceiver = new SystemActionsBroadcastReceiver();
        mLocale = mContext.getResources().getConfiguration().getLocales().get(0);
        mA11yManager = (AccessibilityManager) mContext.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        mNotificationShadeController = notificationShadeController;
        // Saving in instance variable since to prevent GC since
        // NotificationShadeWindowController.registerCallback() only keeps weak references.
        mNotificationShadeCallback =
                (keyguardShowing, keyguardOccluded, bouncerShowing, mDozing, panelExpanded) ->
                        registerOrUnregisterDismissNotificationShadeAction();
        mCentralSurfacesOptionalLazy = centralSurfacesOptionalLazy;
    }

    @Override
    public void start() {
        mNotificationShadeController.registerCallback(mNotificationShadeCallback);
        mContext.registerReceiverForAllUsers(
                mReceiver,
                mReceiver.createIntentFilter(),
                PERMISSION_SELF,
                null,
                Context.RECEIVER_EXPORTED);
        registerActions();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final Locale locale = mContext.getResources().getConfiguration().getLocales().get(0);
        if (!locale.equals(mLocale)) {
            mLocale = locale;
            registerActions();
        }
    }

    private void registerActions() {
        RemoteAction actionBack = createRemoteAction(
                R.string.accessibility_system_action_back_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_BACK);

        RemoteAction actionHome = createRemoteAction(
                R.string.accessibility_system_action_home_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_HOME);

        RemoteAction actionRecents = createRemoteAction(
                R.string.accessibility_system_action_recents_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_RECENTS);

        RemoteAction actionNotifications = createRemoteAction(
                R.string.accessibility_system_action_notifications_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_NOTIFICATIONS);

        RemoteAction actionQuickSettings = createRemoteAction(
                R.string.accessibility_system_action_quick_settings_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_QUICK_SETTINGS);

        RemoteAction actionPowerDialog = createRemoteAction(
                R.string.accessibility_system_action_power_dialog_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_POWER_DIALOG);

        RemoteAction actionLockScreen = createRemoteAction(
                R.string.accessibility_system_action_lock_screen_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_LOCK_SCREEN);

        RemoteAction actionTakeScreenshot = createRemoteAction(
                R.string.accessibility_system_action_screenshot_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_TAKE_SCREENSHOT);

        RemoteAction actionHeadsetHook = createRemoteAction(
                R.string.accessibility_system_action_headset_hook_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_HEADSET_HOOK);

        RemoteAction actionAccessibilityShortcut = createRemoteAction(
                R.string.accessibility_system_action_hardware_a11y_shortcut_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_ACCESSIBILITY_SHORTCUT);

        RemoteAction actionDpadUp = createRemoteAction(
                R.string.accessibility_system_action_dpad_up_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_UP);

        RemoteAction actionDpadDown = createRemoteAction(
                R.string.accessibility_system_action_dpad_down_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_DOWN);

        RemoteAction actionDpadLeft = createRemoteAction(
                R.string.accessibility_system_action_dpad_left_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_LEFT);

        RemoteAction actionDpadRight = createRemoteAction(
                R.string.accessibility_system_action_dpad_right_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_RIGHT);

        RemoteAction actionDpadCenter = createRemoteAction(
                R.string.accessibility_system_action_dpad_center_label,
                SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_CENTER);

        mA11yManager.registerSystemAction(actionBack, SYSTEM_ACTION_ID_BACK);
        mA11yManager.registerSystemAction(actionHome, SYSTEM_ACTION_ID_HOME);
        mA11yManager.registerSystemAction(actionRecents, SYSTEM_ACTION_ID_RECENTS);
        mA11yManager.registerSystemAction(actionNotifications, SYSTEM_ACTION_ID_NOTIFICATIONS);
        mA11yManager.registerSystemAction(actionQuickSettings, SYSTEM_ACTION_ID_QUICK_SETTINGS);
        mA11yManager.registerSystemAction(actionPowerDialog, SYSTEM_ACTION_ID_POWER_DIALOG);
        mA11yManager.registerSystemAction(actionLockScreen, SYSTEM_ACTION_ID_LOCK_SCREEN);
        mA11yManager.registerSystemAction(actionTakeScreenshot, SYSTEM_ACTION_ID_TAKE_SCREENSHOT);
        mA11yManager.registerSystemAction(actionHeadsetHook, SYSTEM_ACTION_ID_KEYCODE_HEADSETHOOK);
        mA11yManager.registerSystemAction(
                actionAccessibilityShortcut, SYSTEM_ACTION_ID_ACCESSIBILITY_SHORTCUT);
        mA11yManager.registerSystemAction(actionDpadUp, SYSTEM_ACTION_ID_DPAD_UP);
        mA11yManager.registerSystemAction(actionDpadDown, SYSTEM_ACTION_ID_DPAD_DOWN);
        mA11yManager.registerSystemAction(actionDpadLeft, SYSTEM_ACTION_ID_DPAD_LEFT);
        mA11yManager.registerSystemAction(actionDpadRight, SYSTEM_ACTION_ID_DPAD_RIGHT);
        mA11yManager.registerSystemAction(actionDpadCenter, SYSTEM_ACTION_ID_DPAD_CENTER);
        registerOrUnregisterDismissNotificationShadeAction();
    }

    private void registerOrUnregisterDismissNotificationShadeAction() {
        Assert.isMainThread();

        // Saving state in instance variable since this callback is called quite often to avoid
        // binder calls
        final Optional<CentralSurfaces> centralSurfacesOptional =
                mCentralSurfacesOptionalLazy.get();
        if (centralSurfacesOptional.map(CentralSurfaces::isPanelExpanded).orElse(false)
                && !centralSurfacesOptional.get().isKeyguardShowing()) {
            if (!mDismissNotificationShadeActionRegistered) {
                mA11yManager.registerSystemAction(
                        createRemoteAction(
                                R.string.accessibility_system_action_dismiss_notification_shade,
                                SystemActionsBroadcastReceiver
                                        .INTENT_ACTION_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE),
                        SYSTEM_ACTION_ID_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE);
                mDismissNotificationShadeActionRegistered = true;
            }
        } else {
            if (mDismissNotificationShadeActionRegistered) {
                mA11yManager.unregisterSystemAction(
                        SYSTEM_ACTION_ID_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE);
                mDismissNotificationShadeActionRegistered = false;
            }
        }
    }

    /**
     * Register a system action.
     * @param actionId the action ID to register.
     */
    public void register(int actionId) {
        int labelId;
        String intent;
        switch (actionId) {
            case SYSTEM_ACTION_ID_BACK:
                labelId = R.string.accessibility_system_action_back_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_BACK;
                break;
            case SYSTEM_ACTION_ID_HOME:
                labelId = R.string.accessibility_system_action_home_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_HOME;
                break;
            case SYSTEM_ACTION_ID_RECENTS:
                labelId = R.string.accessibility_system_action_recents_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_RECENTS;
                break;
            case SYSTEM_ACTION_ID_NOTIFICATIONS:
                labelId = R.string.accessibility_system_action_notifications_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_NOTIFICATIONS;
                break;
            case SYSTEM_ACTION_ID_QUICK_SETTINGS:
                labelId = R.string.accessibility_system_action_quick_settings_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_QUICK_SETTINGS;
                break;
            case SYSTEM_ACTION_ID_POWER_DIALOG:
                labelId = R.string.accessibility_system_action_power_dialog_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_POWER_DIALOG;
                break;
            case SYSTEM_ACTION_ID_LOCK_SCREEN:
                labelId = R.string.accessibility_system_action_lock_screen_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_LOCK_SCREEN;
                break;
            case SYSTEM_ACTION_ID_TAKE_SCREENSHOT:
                labelId = R.string.accessibility_system_action_screenshot_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_TAKE_SCREENSHOT;
                break;
            case SYSTEM_ACTION_ID_KEYCODE_HEADSETHOOK:
                labelId = R.string.accessibility_system_action_headset_hook_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_HEADSET_HOOK;
                break;
            case SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON:
                labelId = R.string.accessibility_system_action_on_screen_a11y_shortcut_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_ACCESSIBILITY_BUTTON;
                break;
            case SYSTEM_ACTION_ID_ACCESSIBILITY_BUTTON_CHOOSER:
                labelId =
                        R.string.accessibility_system_action_on_screen_a11y_shortcut_chooser_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_ACCESSIBILITY_BUTTON_CHOOSER;
                break;
            case SYSTEM_ACTION_ID_ACCESSIBILITY_SHORTCUT:
                labelId = R.string.accessibility_system_action_hardware_a11y_shortcut_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_ACCESSIBILITY_SHORTCUT;
                break;
            case SYSTEM_ACTION_ID_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE:
                labelId = R.string.accessibility_system_action_dismiss_notification_shade;
                intent = SystemActionsBroadcastReceiver
                        .INTENT_ACTION_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE;
                break;
            case SYSTEM_ACTION_ID_DPAD_UP:
                labelId = R.string.accessibility_system_action_dpad_up_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_UP;
                break;
            case SYSTEM_ACTION_ID_DPAD_DOWN:
                labelId = R.string.accessibility_system_action_dpad_down_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_DOWN;
                break;
            case SYSTEM_ACTION_ID_DPAD_LEFT:
                labelId = R.string.accessibility_system_action_dpad_left_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_LEFT;
                break;
            case SYSTEM_ACTION_ID_DPAD_RIGHT:
                labelId = R.string.accessibility_system_action_dpad_right_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_RIGHT;
                break;
            case SYSTEM_ACTION_ID_DPAD_CENTER:
                labelId = R.string.accessibility_system_action_dpad_center_label;
                intent = SystemActionsBroadcastReceiver.INTENT_ACTION_DPAD_CENTER;
                break;
            default:
                return;
        }
        mA11yManager.registerSystemAction(createRemoteAction(labelId, intent), actionId);
    }

    private RemoteAction createRemoteAction(int labelId, String intent) {
        // TODO(b/148087487): update the icon used below to a valid one
        return new RemoteAction(
                Icon.createWithResource(mContext, R.drawable.ic_info),
                mContext.getString(labelId),
                mContext.getString(labelId),
                mReceiver.createPendingIntent(mContext, intent));
    }

    /**
     * Unregister a system action.
     * @param actionId the action ID to unregister.
     */
    public void unregister(int actionId) {
        mA11yManager.unregisterSystemAction(actionId);
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
        mRecentsOptional.ifPresent(Recents::toggleRecentApps);
    }

    private void handleNotifications() {
        mCentralSurfacesOptionalLazy.get().ifPresent(CentralSurfaces::animateExpandNotificationsPanel);
    }

    private void handleQuickSettings() {
        mCentralSurfacesOptionalLazy.get().ifPresent(
                centralSurfaces -> centralSurfaces.animateExpandSettingsPanel(null));
    }

    private void handlePowerDialog() {
        IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();

        try {
            windowManager.showGlobalActions();
        } catch (RemoteException e) {
            Log.e(TAG, "failed to display power dialog.");
        }
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
                SCREENSHOT_ACCESSIBILITY_ACTIONS, new Handler(Looper.getMainLooper()), null);
    }

    private void handleHeadsetHook() {
        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_HEADSETHOOK);
    }

    private void handleAccessibilityButton() {
        AccessibilityManager.getInstance(mContext).notifyAccessibilityButtonClicked(
                Display.DEFAULT_DISPLAY);
    }

    private void handleAccessibilityButtonChooser() {
        final Intent intent = new Intent(AccessibilityManager.ACTION_CHOOSE_ACCESSIBILITY_BUTTON);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        final String chooserClassName = AccessibilityButtonChooserActivity.class.getName();
        intent.setClassName(CHOOSER_PACKAGE_NAME, chooserClassName);
        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    private void handleAccessibilityShortcut() {
        mA11yManager.performAccessibilityShortcut();
    }

    private void handleAccessibilityDismissNotificationShade() {
        mCentralSurfacesOptionalLazy.get().ifPresent(
                centralSurfaces -> centralSurfaces.animateCollapsePanels(
                        CommandQueue.FLAG_EXCLUDE_NONE, false /* force */));
    }

    private void handleDpadUp() {
        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP);
    }

    private void handleDpadDown() {
        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN);
    }

    private void handleDpadLeft() {
        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
    }

    private void handleDpadRight() {
        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
    }

    private void handleDpadCenter() {
        sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_CENTER);
    }

    private class SystemActionsBroadcastReceiver extends BroadcastReceiver {
        private static final String INTENT_ACTION_BACK = "SYSTEM_ACTION_BACK";
        private static final String INTENT_ACTION_HOME = "SYSTEM_ACTION_HOME";
        private static final String INTENT_ACTION_RECENTS = "SYSTEM_ACTION_RECENTS";
        private static final String INTENT_ACTION_NOTIFICATIONS = "SYSTEM_ACTION_NOTIFICATIONS";
        private static final String INTENT_ACTION_QUICK_SETTINGS = "SYSTEM_ACTION_QUICK_SETTINGS";
        private static final String INTENT_ACTION_POWER_DIALOG = "SYSTEM_ACTION_POWER_DIALOG";
        private static final String INTENT_ACTION_LOCK_SCREEN = "SYSTEM_ACTION_LOCK_SCREEN";
        private static final String INTENT_ACTION_TAKE_SCREENSHOT = "SYSTEM_ACTION_TAKE_SCREENSHOT";
        private static final String INTENT_ACTION_HEADSET_HOOK = "SYSTEM_ACTION_HEADSET_HOOK";
        private static final String INTENT_ACTION_ACCESSIBILITY_BUTTON =
                "SYSTEM_ACTION_ACCESSIBILITY_BUTTON";
        private static final String INTENT_ACTION_ACCESSIBILITY_BUTTON_CHOOSER =
                "SYSTEM_ACTION_ACCESSIBILITY_BUTTON_MENU";
        private static final String INTENT_ACTION_ACCESSIBILITY_SHORTCUT =
                "SYSTEM_ACTION_ACCESSIBILITY_SHORTCUT";
        private static final String INTENT_ACTION_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE =
                "SYSTEM_ACTION_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE";
        private static final String INTENT_ACTION_DPAD_UP = "SYSTEM_ACTION_DPAD_UP";
        private static final String INTENT_ACTION_DPAD_DOWN = "SYSTEM_ACTION_DPAD_DOWN";
        private static final String INTENT_ACTION_DPAD_LEFT = "SYSTEM_ACTION_DPAD_LEFT";
        private static final String INTENT_ACTION_DPAD_RIGHT = "SYSTEM_ACTION_DPAD_RIGHT";
        private static final String INTENT_ACTION_DPAD_CENTER = "SYSTEM_ACTION_DPAD_CENTER";

        private PendingIntent createPendingIntent(Context context, String intentAction) {
            switch (intentAction) {
                case INTENT_ACTION_BACK:
                case INTENT_ACTION_HOME:
                case INTENT_ACTION_RECENTS:
                case INTENT_ACTION_NOTIFICATIONS:
                case INTENT_ACTION_QUICK_SETTINGS:
                case INTENT_ACTION_POWER_DIALOG:
                case INTENT_ACTION_LOCK_SCREEN:
                case INTENT_ACTION_TAKE_SCREENSHOT:
                case INTENT_ACTION_HEADSET_HOOK:
                case INTENT_ACTION_ACCESSIBILITY_BUTTON:
                case INTENT_ACTION_ACCESSIBILITY_BUTTON_CHOOSER:
                case INTENT_ACTION_ACCESSIBILITY_SHORTCUT:
                case INTENT_ACTION_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE:
                case INTENT_ACTION_DPAD_UP:
                case INTENT_ACTION_DPAD_DOWN:
                case INTENT_ACTION_DPAD_LEFT:
                case INTENT_ACTION_DPAD_RIGHT:
                case INTENT_ACTION_DPAD_CENTER: {
                    Intent intent = new Intent(intentAction);
                    intent.setPackage(context.getPackageName());
                    return PendingIntent.getBroadcast(context, 0, intent,
                            PendingIntent.FLAG_IMMUTABLE);
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
            intentFilter.addAction(INTENT_ACTION_LOCK_SCREEN);
            intentFilter.addAction(INTENT_ACTION_TAKE_SCREENSHOT);
            intentFilter.addAction(INTENT_ACTION_HEADSET_HOOK);
            intentFilter.addAction(INTENT_ACTION_ACCESSIBILITY_BUTTON);
            intentFilter.addAction(INTENT_ACTION_ACCESSIBILITY_BUTTON_CHOOSER);
            intentFilter.addAction(INTENT_ACTION_ACCESSIBILITY_SHORTCUT);
            intentFilter.addAction(INTENT_ACTION_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE);
            intentFilter.addAction(INTENT_ACTION_DPAD_UP);
            intentFilter.addAction(INTENT_ACTION_DPAD_DOWN);
            intentFilter.addAction(INTENT_ACTION_DPAD_LEFT);
            intentFilter.addAction(INTENT_ACTION_DPAD_RIGHT);
            intentFilter.addAction(INTENT_ACTION_DPAD_CENTER);
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
                case INTENT_ACTION_LOCK_SCREEN: {
                    handleLockScreen();
                    break;
                }
                case INTENT_ACTION_TAKE_SCREENSHOT: {
                    handleTakeScreenshot();
                    break;
                }
                case INTENT_ACTION_HEADSET_HOOK: {
                    handleHeadsetHook();
                    break;
                }
                case INTENT_ACTION_ACCESSIBILITY_BUTTON: {
                    handleAccessibilityButton();
                    break;
                }
                case INTENT_ACTION_ACCESSIBILITY_BUTTON_CHOOSER: {
                    handleAccessibilityButtonChooser();
                    break;
                }
                case INTENT_ACTION_ACCESSIBILITY_SHORTCUT: {
                    handleAccessibilityShortcut();
                    break;
                }
                case INTENT_ACTION_ACCESSIBILITY_DISMISS_NOTIFICATION_SHADE: {
                    handleAccessibilityDismissNotificationShade();
                    break;
                }
                case INTENT_ACTION_DPAD_UP: {
                    handleDpadUp();
                    break;
                }
                case INTENT_ACTION_DPAD_DOWN: {
                    handleDpadDown();
                    break;
                }
                case INTENT_ACTION_DPAD_LEFT: {
                    handleDpadLeft();
                    break;
                }
                case INTENT_ACTION_DPAD_RIGHT: {
                    handleDpadRight();
                    break;
                }
                case INTENT_ACTION_DPAD_CENTER: {
                    handleDpadCenter();
                    break;
                }
                default:
                    break;
            }
        }
    }
}
