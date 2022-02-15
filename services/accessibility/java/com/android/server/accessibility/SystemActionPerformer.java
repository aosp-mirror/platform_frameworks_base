/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.accessibility;

import static android.view.WindowManager.ScreenshotSource.SCREENSHOT_ACCESSIBILITY_ACTIONS;

import android.accessibilityservice.AccessibilityService;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.app.StatusBarManager;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ScreenshotHelper;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Handle the back-end of system AccessibilityAction.
 *
 * This class should support three use cases with combined usage of new API and legacy API:
 *
 * Use case 1: SystemUI doesn't use the new system action registration API. Accessibility
 *             service doesn't use the new system action API to obtain action list. Accessibility
 *             service uses legacy global action id to perform predefined system actions.
 * Use case 2: SystemUI uses the new system action registration API to register available system
 *             actions. Accessibility service doesn't use the new system action API to obtain action
 *             list. Accessibility service uses legacy global action id to trigger the system
 *             actions registered by SystemUI.
 * Use case 3: SystemUI doesn't use the new system action registration API.Accessibility service
 *             obtains the available system actions using new AccessibilityService API and trigger
 *             the predefined system actions.
 */
public class SystemActionPerformer {
    private static final String TAG = "SystemActionPerformer";

    interface SystemActionsChangedListener {
        void onSystemActionsChanged();
    }
    private final SystemActionsChangedListener mListener;

    private final Object mSystemActionLock = new Object();
    // Resource id based ActionId -> RemoteAction
    @GuardedBy("mSystemActionLock")
    private final Map<Integer, RemoteAction> mRegisteredSystemActions = new ArrayMap<>();

    // Legacy system actions.
    private final AccessibilityAction mLegacyHomeAction;
    private final AccessibilityAction mLegacyBackAction;
    private final AccessibilityAction mLegacyRecentsAction;
    private final AccessibilityAction mLegacyNotificationsAction;
    private final AccessibilityAction mLegacyQuickSettingsAction;
    private final AccessibilityAction mLegacyPowerDialogAction;
    private final AccessibilityAction mLegacyLockScreenAction;
    private final AccessibilityAction mLegacyTakeScreenshotAction;

    private final WindowManagerInternal mWindowManagerService;
    private final Context mContext;
    private Supplier<ScreenshotHelper> mScreenshotHelperSupplier;

    public SystemActionPerformer(
            Context context,
            WindowManagerInternal windowManagerInternal) {
      this(context, windowManagerInternal, null, null);
    }

    // Used to mock ScreenshotHelper
    @VisibleForTesting
    public SystemActionPerformer(
            Context context,
            WindowManagerInternal windowManagerInternal,
            Supplier<ScreenshotHelper> screenshotHelperSupplier) {
        this(context, windowManagerInternal, screenshotHelperSupplier, null);
    }

    public SystemActionPerformer(
            Context context,
            WindowManagerInternal windowManagerInternal,
            Supplier<ScreenshotHelper> screenshotHelperSupplier,
            SystemActionsChangedListener listener) {
        mContext = context;
        mWindowManagerService = windowManagerInternal;
        mListener = listener;
        mScreenshotHelperSupplier = screenshotHelperSupplier;

        mLegacyHomeAction = new AccessibilityAction(
                AccessibilityService.GLOBAL_ACTION_HOME,
                mContext.getResources().getString(
                        R.string.accessibility_system_action_home_label));
        mLegacyBackAction = new AccessibilityAction(
                AccessibilityService.GLOBAL_ACTION_BACK,
                mContext.getResources().getString(
                        R.string.accessibility_system_action_back_label));
        mLegacyRecentsAction = new AccessibilityAction(
                AccessibilityService.GLOBAL_ACTION_RECENTS,
                mContext.getResources().getString(
                        R.string.accessibility_system_action_recents_label));
        mLegacyNotificationsAction = new AccessibilityAction(
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                mContext.getResources().getString(
                        R.string.accessibility_system_action_notifications_label));
        mLegacyQuickSettingsAction = new AccessibilityAction(
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
                mContext.getResources().getString(
                        R.string.accessibility_system_action_quick_settings_label));
        mLegacyPowerDialogAction = new AccessibilityAction(
                AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
                mContext.getResources().getString(
                        R.string.accessibility_system_action_power_dialog_label));
        mLegacyLockScreenAction = new AccessibilityAction(
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN,
                mContext.getResources().getString(
                        R.string.accessibility_system_action_lock_screen_label));
        mLegacyTakeScreenshotAction = new AccessibilityAction(
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT,
                mContext.getResources().getString(
                        R.string.accessibility_system_action_screenshot_label));
    }

    /**
     * This method is called to register a system action. If a system action is already registered
     * with the given id, the existing system action will be overwritten.
     *
     * This method is supposed to be package internal since this class is meant to be used by
     * AccessibilityManagerService only. But Mockito has a bug which requiring this to be public
     * to be mocked.
     */
    @VisibleForTesting
    public void registerSystemAction(int id, RemoteAction action) {
        synchronized (mSystemActionLock) {
            mRegisteredSystemActions.put(id, action);
        }
        if (mListener != null) {
            mListener.onSystemActionsChanged();
        }
    }

    /**
     * This method is called to unregister a system action previously registered through
     * registerSystemAction.
     *
     * This method is supposed to be package internal since this class is meant to be used by
     * AccessibilityManagerService only. But Mockito has a bug which requiring this to be public
     * to be mocked.
     */
    @VisibleForTesting
    public void unregisterSystemAction(int id) {
        synchronized (mSystemActionLock) {
            mRegisteredSystemActions.remove(id);
        }
        if (mListener != null) {
            mListener.onSystemActionsChanged();
        }
    }

    /**
     * This method returns the list of available system actions.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public List<AccessibilityAction> getSystemActions() {
        List<AccessibilityAction> systemActions = new ArrayList<>();
        synchronized (mSystemActionLock) {
            for (Map.Entry<Integer, RemoteAction> entry : mRegisteredSystemActions.entrySet()) {
                AccessibilityAction systemAction = new AccessibilityAction(
                        entry.getKey(),
                        entry.getValue().getTitle());
                systemActions.add(systemAction);
            }

            // add AccessibilitySystemAction entry for legacy system actions if not overwritten
            addLegacySystemActions(systemActions);
        }
        return systemActions;
    }

    private void addLegacySystemActions(List<AccessibilityAction> systemActions) {
        if (!mRegisteredSystemActions.containsKey(AccessibilityService.GLOBAL_ACTION_BACK)) {
            systemActions.add(mLegacyBackAction);
        }
        if (!mRegisteredSystemActions.containsKey(AccessibilityService.GLOBAL_ACTION_HOME)) {
            systemActions.add(mLegacyHomeAction);
        }
        if (!mRegisteredSystemActions.containsKey(AccessibilityService.GLOBAL_ACTION_RECENTS)) {
            systemActions.add(mLegacyRecentsAction);
        }
        if (!mRegisteredSystemActions.containsKey(
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) {
            systemActions.add(mLegacyNotificationsAction);
        }
        if (!mRegisteredSystemActions.containsKey(
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
            systemActions.add(mLegacyQuickSettingsAction);
        }
        if (!mRegisteredSystemActions.containsKey(
                AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)) {
            systemActions.add(mLegacyPowerDialogAction);
        }
        if (!mRegisteredSystemActions.containsKey(
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            systemActions.add(mLegacyLockScreenAction);
        }
        if (!mRegisteredSystemActions.containsKey(
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) {
            systemActions.add(mLegacyTakeScreenshotAction);
        }
    }

    /**
     * Trigger the registered action by the matching action id.
     */
    public boolean performSystemAction(int actionId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mSystemActionLock) {
                // If a system action is registered with the given actionId, call the corresponding
                // RemoteAction.
                RemoteAction registeredAction = mRegisteredSystemActions.get(actionId);
                if (registeredAction != null) {
                    try {
                        registeredAction.getActionIntent().send();
                        return true;
                    } catch (PendingIntent.CanceledException ex) {
                        Slog.e(TAG,
                                "canceled PendingIntent for global action "
                                        + registeredAction.getTitle(),
                                ex);
                    }
                    return false;
                }
            }

            // No RemoteAction registered with the given actionId, try the default legacy system
            // actions.
            switch (actionId) {
                case AccessibilityService.GLOBAL_ACTION_BACK: {
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_BACK);
                    return true;
                }
                case AccessibilityService.GLOBAL_ACTION_HOME: {
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_HOME);
                    return true;
                }
                case AccessibilityService.GLOBAL_ACTION_RECENTS:
                    return openRecents();
                case AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS: {
                    expandNotifications();
                    return true;
                }
                case AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS: {
                    expandQuickSettings();
                    return true;
                }
                case AccessibilityService.GLOBAL_ACTION_POWER_DIALOG: {
                    showGlobalActions();
                    return true;
                }
                case AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN:
                    return lockScreen();
                case AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT:
                    return takeScreenshot();
                case AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK :
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_HEADSETHOOK);
                    return true;
                case AccessibilityService.GLOBAL_ACTION_DPAD_UP:
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP);
                    return true;
                case AccessibilityService.GLOBAL_ACTION_DPAD_DOWN:
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN);
                    return true;
                case AccessibilityService.GLOBAL_ACTION_DPAD_LEFT:
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
                    return true;
                case AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT:
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
                    return true;
                case AccessibilityService.GLOBAL_ACTION_DPAD_CENTER:
                    sendDownAndUpKeyEvents(KeyEvent.KEYCODE_DPAD_CENTER);
                    return true;
                default:
                    Slog.e(TAG, "Invalid action id: " + actionId);
                    return false;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void sendDownAndUpKeyEvents(int keyCode) {
        final long token = Binder.clearCallingIdentity();
        try {
            // Inject down.
            final long downTime = SystemClock.uptimeMillis();
            sendKeyEventIdentityCleared(keyCode, KeyEvent.ACTION_DOWN, downTime, downTime);
            sendKeyEventIdentityCleared(
                    keyCode, KeyEvent.ACTION_UP, downTime, SystemClock.uptimeMillis());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void sendKeyEventIdentityCleared(int keyCode, int action, long downTime, long time) {
        KeyEvent event = KeyEvent.obtain(downTime, time, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                InputDevice.SOURCE_KEYBOARD, null);
        InputManager.getInstance()
                .injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        event.recycle();
    }

    private void expandNotifications() {
        final long token = Binder.clearCallingIdentity();
        try {
            StatusBarManager statusBarManager = (StatusBarManager) mContext.getSystemService(
                    android.app.Service.STATUS_BAR_SERVICE);
            statusBarManager.expandNotificationsPanel();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void expandQuickSettings() {
        final long token = Binder.clearCallingIdentity();
        try {
            StatusBarManager statusBarManager = (StatusBarManager) mContext.getSystemService(
                    android.app.Service.STATUS_BAR_SERVICE);
            statusBarManager.expandSettingsPanel();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean openRecents() {
        final long token = Binder.clearCallingIdentity();
        try {
            StatusBarManagerInternal statusBarService = LocalServices.getService(
                    StatusBarManagerInternal.class);
            if (statusBarService == null) {
                return false;
            }
            statusBarService.toggleRecentApps();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return true;
    }

    private void showGlobalActions() {
        mWindowManagerService.showGlobalActions();
    }

    private boolean lockScreen() {
        mContext.getSystemService(PowerManager.class).goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_ACCESSIBILITY, 0);
        mWindowManagerService.lockNow();
        return true;
    }

    private boolean takeScreenshot() {
        ScreenshotHelper screenshotHelper = (mScreenshotHelperSupplier != null)
                ? mScreenshotHelperSupplier.get() : new ScreenshotHelper(mContext);
        screenshotHelper.takeScreenshot(android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                true, true, SCREENSHOT_ACCESSIBILITY_ACTIONS,
                new Handler(Looper.getMainLooper()), null);
        return true;
    }
}
