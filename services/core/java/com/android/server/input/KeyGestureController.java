/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input;

import static com.android.server.flags.Flags.newBugreportKeyboardShortcut;

import android.annotation.BinderThread;
import android.annotation.MainThread;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.input.AidlKeyGestureEvent;
import android.hardware.input.IKeyGestureEventListener;
import android.hardware.input.IKeyGestureHandler;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing callbacks when a
 * key gesture event occurs.
 */
final class KeyGestureController {

    private static final String TAG = "KeyGestureController";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.KeyGestureController DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Maximum key gesture events that are tracked and will be available in input dump.
    private static final int MAX_TRACKED_EVENTS = 10;

    private static final int MSG_NOTIFY_KEY_GESTURE_EVENT = 1;

    // must match: config_settingsKeyBehavior in config.xml
    private static final int SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY = 0;
    private static final int SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL = 1;
    private static final int SETTINGS_KEY_BEHAVIOR_NOTHING = 2;
    private static final int LAST_SETTINGS_KEY_BEHAVIOR = SETTINGS_KEY_BEHAVIOR_NOTHING;

    // Must match: config_searchKeyBehavior in config.xml
    private static final int SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH = 0;
    private static final int SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY = 1;
    private static final int LAST_SEARCH_KEY_BEHAVIOR = SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY;

    private final Context mContext;
    private final Handler mHandler;
    private final int mSystemPid;

    // Pending actions
    private boolean mPendingMetaAction;
    private boolean mPendingCapsLockToggle;
    private boolean mPendingHideRecentSwitcher;

    // Key behaviors
    private boolean mEnableBugReportKeyboardShortcut;
    private int mSearchKeyBehavior;
    private int mSettingsKeyBehavior;

    // List of currently registered key gesture event listeners keyed by process pid
    @GuardedBy("mKeyGestureEventListenerRecords")
    private final SparseArray<KeyGestureEventListenerRecord>
            mKeyGestureEventListenerRecords = new SparseArray<>();

    // List of currently registered key gesture event handler keyed by process pid. The map sorts
    // in the order of preference of the handlers, and we prioritize handlers in system server
    // over external handlers..
    @GuardedBy("mKeyGestureHandlerRecords")
    private final TreeMap<Integer, KeyGestureHandlerRecord> mKeyGestureHandlerRecords;

    private final ArrayDeque<KeyGestureEvent> mLastHandledEvents = new ArrayDeque<>();

    /** Currently fully consumed key codes per device */
    private final SparseArray<Set<Integer>> mConsumedKeysForDevice = new SparseArray<>();

    KeyGestureController(Context context, Looper looper) {
        mContext = context;
        mHandler = new Handler(looper, this::handleMessage);
        mSystemPid = Process.myPid();
        mKeyGestureHandlerRecords = new TreeMap<>((p1, p2) -> {
            if (Objects.equals(p1, p2)) {
                return 0;
            }
            if (p1 == mSystemPid) {
                return -1;
            } else if (p2 == mSystemPid) {
                return 1;
            } else {
                return Integer.compare(p1, p2);
            }
        });
        initBehaviors();
    }

    private void initBehaviors() {
        mEnableBugReportKeyboardShortcut = "1".equals(SystemProperties.get("ro.debuggable"));

        Resources res = mContext.getResources();
        mSearchKeyBehavior = res.getInteger(R.integer.config_searchKeyBehavior);
        if (mSearchKeyBehavior < SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH
                || mSearchKeyBehavior > LAST_SEARCH_KEY_BEHAVIOR) {
            mSearchKeyBehavior = SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH;
        }
        mSettingsKeyBehavior = res.getInteger(R.integer.config_settingsKeyBehavior);
        if (mSettingsKeyBehavior < SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY
                || mSettingsKeyBehavior > LAST_SETTINGS_KEY_BEHAVIOR) {
            mSettingsKeyBehavior = SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY;
        }
    }

    public long interceptKeyBeforeDispatching(IBinder focusedToken, KeyEvent event,
            int policyFlags) {
        // TODO(b/358569822): Handle shortcuts trigger logic here and pass it to appropriate
        //  KeyGestureHandler (PWM is one of the handlers)
        final int keyCode = event.getKeyCode();
        final int deviceId = event.getDeviceId();
        final long keyConsumed = -1;
        final long keyNotConsumed = 0;

        Set<Integer> consumedKeys = mConsumedKeysForDevice.get(deviceId);
        if (consumedKeys == null) {
            consumedKeys = new HashSet<>();
            mConsumedKeysForDevice.put(deviceId, consumedKeys);
        }

        if (interceptSystemKeysAndShortcuts(focusedToken, event)
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            consumedKeys.add(keyCode);
            return keyConsumed;
        }

        boolean needToConsumeKey = consumedKeys.contains(keyCode);
        if (event.getAction() == KeyEvent.ACTION_UP || event.isCanceled()) {
            consumedKeys.remove(keyCode);
            if (consumedKeys.isEmpty()) {
                mConsumedKeysForDevice.remove(deviceId);
            }
        }

        return needToConsumeKey ? keyConsumed : keyNotConsumed;
    }

    @SuppressLint("MissingPermission")
    private boolean interceptSystemKeysAndShortcuts(IBinder focusedToken, KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int repeatCount = event.getRepeatCount();
        final int metaState = event.getMetaState();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean canceled = event.isCanceled();
        final int displayId = event.getDisplayId();
        final int deviceId = event.getDeviceId();
        final boolean firstDown = down && repeatCount == 0;

        // Cancel any pending meta actions if we see any other keys being pressed between the
        // down of the meta key and its corresponding up.
        if (mPendingMetaAction && !KeyEvent.isMetaKey(keyCode)) {
            mPendingMetaAction = false;
        }
        // Any key that is not Alt or Meta cancels Caps Lock combo tracking.
        if (mPendingCapsLockToggle && !KeyEvent.isMetaKey(keyCode) && !KeyEvent.isAltKey(keyCode)) {
            mPendingCapsLockToggle = false;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_A:
                if (firstDown && event.isMetaPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, KeyEvent.META_META_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_RECENT_APPS:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_APP_SWITCH:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                            KeyGestureEvent.ACTION_GESTURE_START, displayId,
                            focusedToken, /* flags = */0);
                } else if (!down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, canceled ? KeyGestureEvent.FLAG_CANCELLED : 0);
                }
                return true;
            case KeyEvent.KEYCODE_H:
            case KeyEvent.KEYCODE_ENTER:
                if (firstDown && event.isMetaPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, KeyEvent.META_META_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_I:
                if (firstDown && event.isMetaPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, KeyEvent.META_META_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_L:
                if (firstDown && event.isMetaPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, KeyEvent.META_META_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_N:
                if (firstDown && event.isMetaPressed()) {
                    if (event.isCtrlPressed()) {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    } else {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_META_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    }
                }
                break;
            case KeyEvent.KEYCODE_S:
                if (firstDown && event.isMetaPressed() && event.isCtrlPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode},
                            KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_DEL:
                if (newBugreportKeyboardShortcut()) {
                    if (firstDown && mEnableBugReportKeyboardShortcut && event.isMetaPressed()
                            && event.isCtrlPressed()) {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    }
                }
                // fall through
            case KeyEvent.KEYCODE_ESCAPE:
                if (firstDown && event.isMetaPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, KeyEvent.META_META_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_BACK,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (firstDown && event.isMetaPressed() && event.isCtrlPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode},
                            KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (firstDown && event.isMetaPressed() && event.isCtrlPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode},
                            KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (firstDown && event.isMetaPressed()) {
                    if (event.isCtrlPressed()) {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    } else if (event.isAltPressed()) {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    } else {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_META_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_BACK,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (firstDown && event.isMetaPressed()) {
                    if (event.isCtrlPressed()) {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    } else if (event.isAltPressed()) {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    }
                }
                break;
            case KeyEvent.KEYCODE_SLASH:
                if (firstDown && event.isMetaPressed()) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, KeyEvent.META_META_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                break;
            case KeyEvent.KEYCODE_BRIGHTNESS_UP:
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
                if (down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            keyCode == KeyEvent.KEYCODE_BRIGHTNESS_UP
                                    ? KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_UP
                                    : KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN:
                if (down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP:
                if (down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE:
                // TODO: Add logic
                if (!down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_ALL_APPS:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_NOTIFICATION:
                if (!down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                if (firstDown && mSearchKeyBehavior == SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);

                }
                break;
            case KeyEvent.KEYCODE_SETTINGS:
                if (firstDown) {
                    if (mSettingsKeyBehavior == SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY) {
                        handleKeyGesture(deviceId,
                                new int[]{keyCode}, /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    } else if (mSettingsKeyBehavior == SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL) {
                        handleKeyGesture(deviceId,
                                new int[]{keyCode}, /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_LANGUAGE_SWITCH:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode},
                            event.isShiftPressed() ? KeyEvent.META_SHIFT_ON : 0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                // Just logging/notifying purposes
                // Caps lock is already handled in inputflinger native
                if (!down) {
                    AidlKeyGestureEvent eventToNotify = createKeyGestureEvent(deviceId,
                            new int[]{keyCode}, metaState,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId, /* flags = */0);
                    Message msg = Message.obtain(mHandler, MSG_NOTIFY_KEY_GESTURE_EVENT,
                            eventToNotify);
                    mHandler.sendMessage(msg);
                }
                break;
            case KeyEvent.KEYCODE_SCREENSHOT:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0);
                }
                return true;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
                if (down) {
                    if (event.isAltPressed()) {
                        mPendingCapsLockToggle = true;
                        mPendingMetaAction = false;
                    } else {
                        mPendingCapsLockToggle = false;
                        mPendingMetaAction = true;
                    }
                } else {
                    // Toggle Caps Lock on META-ALT.
                    if (mPendingCapsLockToggle) {
                        mPendingCapsLockToggle = false;
                        handleKeyGesture(deviceId, new int[]{KeyEvent.KEYCODE_META_LEFT,
                                        KeyEvent.KEYCODE_ALT_LEFT}, /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);

                    } else if (mPendingMetaAction) {
                        mPendingMetaAction = false;
                        if (!canceled) {
                            handleKeyGesture(deviceId, new int[]{keyCode},
                                    /* modifierState = */0,
                                    KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS,
                                    KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                    focusedToken, /* flags = */0);
                        }
                    }
                }
                return true;
            case KeyEvent.KEYCODE_TAB:
                if (firstDown) {
                    if (event.isMetaPressed()) {
                        return handleKeyGesture(deviceId, new int[]{keyCode}, KeyEvent.META_META_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    } else if (!mPendingHideRecentSwitcher) {
                        final int shiftlessModifiers =
                                event.getModifiers() & ~KeyEvent.META_SHIFT_MASK;
                        if (KeyEvent.metaStateHasModifiers(
                                shiftlessModifiers, KeyEvent.META_ALT_ON)) {
                            mPendingHideRecentSwitcher = true;
                            return handleKeyGesture(deviceId, new int[]{keyCode},
                                    KeyEvent.META_ALT_ON,
                                    KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER,
                                    KeyGestureEvent.ACTION_GESTURE_START, displayId,
                                    focusedToken, /* flags = */0);
                        }
                    }
                }
                break;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                if (down) {
                    if (event.isMetaPressed()) {
                        mPendingCapsLockToggle = true;
                        mPendingMetaAction = false;
                    } else {
                        mPendingCapsLockToggle = false;
                    }
                } else {
                    if (mPendingHideRecentSwitcher) {
                        mPendingHideRecentSwitcher = false;
                        return handleKeyGesture(deviceId, new int[]{KeyEvent.KEYCODE_TAB},
                                KeyEvent.META_ALT_ON,
                                KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    }

                    // Toggle Caps Lock on META-ALT.
                    if (mPendingCapsLockToggle) {
                        mPendingCapsLockToggle = false;
                        return handleKeyGesture(deviceId, new int[]{KeyEvent.KEYCODE_META_LEFT,
                                        KeyEvent.KEYCODE_ALT_LEFT}, /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0);
                    }
                }
                break;
            case KeyEvent.KEYCODE_ASSIST:
                Slog.wtf(TAG, "KEYCODE_ASSIST should be handled in interceptKeyBeforeQueueing");
                return true;
            case KeyEvent.KEYCODE_VOICE_ASSIST:
                Slog.wtf(TAG, "KEYCODE_VOICE_ASSIST should be handled in"
                        + " interceptKeyBeforeQueueing");
                return true;
            case KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY:
            case KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL:
                Slog.wtf(TAG, "KEYCODE_STYLUS_BUTTON_* should be handled in"
                        + " interceptKeyBeforeQueueing");
                return true;
        }
        return false;
    }

    @VisibleForTesting
    boolean handleKeyGesture(int deviceId, int[] keycodes, int modifierState,
            @KeyGestureEvent.KeyGestureType int gestureType, int action, int displayId,
            IBinder focusedToken, int flags) {
        return handleKeyGesture(createKeyGestureEvent(deviceId, keycodes,
                modifierState, gestureType, action, displayId, flags), focusedToken);
    }

    private boolean handleKeyGesture(AidlKeyGestureEvent event, @Nullable IBinder focusedToken) {
        synchronized (mKeyGestureHandlerRecords) {
            for (KeyGestureHandlerRecord handler : mKeyGestureHandlerRecords.values()) {
                if (handler.handleKeyGesture(event, focusedToken)) {
                    Message msg = Message.obtain(mHandler, MSG_NOTIFY_KEY_GESTURE_EVENT, event);
                    mHandler.sendMessage(msg);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isKeyGestureSupported(@KeyGestureEvent.KeyGestureType int gestureType) {
        synchronized (mKeyGestureHandlerRecords) {
            for (KeyGestureHandlerRecord handler : mKeyGestureHandlerRecords.values()) {
                if (handler.isKeyGestureSupported(gestureType)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void notifyKeyGestureCompleted(int deviceId, int[] keycodes, int modifierState,
            @KeyGestureEvent.KeyGestureType int gestureType) {
        // TODO(b/358569822): Once we move the gesture detection logic to IMS, we ideally
        //  should not rely on PWM to tell us about the gesture start and end.
        AidlKeyGestureEvent event = createKeyGestureEvent(deviceId, keycodes, modifierState,
                gestureType, KeyGestureEvent.ACTION_GESTURE_COMPLETE, Display.DEFAULT_DISPLAY, 0);
        mHandler.obtainMessage(MSG_NOTIFY_KEY_GESTURE_EVENT, event).sendToTarget();
    }

    public void handleKeyGesture(int deviceId, int[] keycodes, int modifierState,
            @KeyGestureEvent.KeyGestureType int gestureType) {
        AidlKeyGestureEvent event = createKeyGestureEvent(deviceId, keycodes, modifierState,
                gestureType, KeyGestureEvent.ACTION_GESTURE_COMPLETE, Display.DEFAULT_DISPLAY, 0);
        handleKeyGesture(event, null /*focusedToken*/);
    }

    @MainThread
    private void notifyKeyGestureEvent(AidlKeyGestureEvent event) {
        InputDevice device = getInputDevice(event.deviceId);
        if (device == null || device.isVirtual()) {
            return;
        }
        if (event.action == KeyGestureEvent.ACTION_GESTURE_COMPLETE) {
            KeyboardMetricsCollector.logKeyboardSystemsEventReportedAtom(device, event.keycodes,
                    event.modifierState,
                    KeyGestureEvent.keyGestureTypeToLogEvent(event.gestureType));
        }
        notifyAllListeners(event);
        while (mLastHandledEvents.size() >= MAX_TRACKED_EVENTS) {
            mLastHandledEvents.removeFirst();
        }
        mLastHandledEvents.addLast(new KeyGestureEvent(event));
    }

    @MainThread
    private void notifyAllListeners(AidlKeyGestureEvent event) {
        if (DEBUG) {
            Slog.d(TAG, "Key gesture event occurred, event = " + event);
        }

        synchronized (mKeyGestureEventListenerRecords) {
            for (int i = 0; i < mKeyGestureEventListenerRecords.size(); i++) {
                mKeyGestureEventListenerRecords.valueAt(i).onKeyGestureEvent(event);
            }
        }
    }

    @MainThread
    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_NOTIFY_KEY_GESTURE_EVENT:
                AidlKeyGestureEvent event = (AidlKeyGestureEvent) msg.obj;
                notifyKeyGestureEvent(event);
                break;
        }
        return true;
    }

    /** Register the key gesture event listener for a process. */
    @BinderThread
    public void registerKeyGestureEventListener(IKeyGestureEventListener listener, int pid) {
        synchronized (mKeyGestureEventListenerRecords) {
            if (mKeyGestureEventListenerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a KeyGestureEventListener.");
            }
            KeyGestureEventListenerRecord record = new KeyGestureEventListenerRecord(pid, listener);
            try {
                listener.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mKeyGestureEventListenerRecords.put(pid, record);
        }
    }

    /** Unregister the key gesture event listener for a process. */
    @BinderThread
    public void unregisterKeyGestureEventListener(IKeyGestureEventListener listener, int pid) {
        synchronized (mKeyGestureEventListenerRecords) {
            KeyGestureEventListenerRecord record =
                    mKeyGestureEventListenerRecords.get(pid);
            if (record == null) {
                throw new IllegalStateException("The calling process has no registered "
                        + "KeyGestureEventListener.");
            }
            if (record.mListener.asBinder() != listener.asBinder()) {
                throw new IllegalStateException("The calling process has a different registered "
                        + "KeyGestureEventListener.");
            }
            record.mListener.asBinder().unlinkToDeath(record, 0);
            mKeyGestureEventListenerRecords.remove(pid);
        }
    }

    private void onKeyGestureEventListenerDied(int pid) {
        synchronized (mKeyGestureEventListenerRecords) {
            mKeyGestureEventListenerRecords.remove(pid);
        }
    }

    // A record of a registered key gesture event listener from one process.
    private class KeyGestureEventListenerRecord implements IBinder.DeathRecipient {
        public final int mPid;
        public final IKeyGestureEventListener mListener;

        KeyGestureEventListenerRecord(int pid, IKeyGestureEventListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Key gesture event listener for pid " + mPid + " died.");
            }
            onKeyGestureEventListenerDied(mPid);
        }

        public void onKeyGestureEvent(AidlKeyGestureEvent event) {
            try {
                mListener.onKeyGestureEvent(event);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that key gesture event occurred, assuming it died.", ex);
                binderDied();
            }
        }
    }

    /** Register the key gesture event handler for a process. */
    @BinderThread
    public void registerKeyGestureHandler(IKeyGestureHandler handler, int pid) {
        synchronized (mKeyGestureHandlerRecords) {
            if (mKeyGestureHandlerRecords.get(pid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a KeyGestureHandler.");
            }
            KeyGestureHandlerRecord record = new KeyGestureHandlerRecord(pid, handler);
            try {
                handler.asBinder().linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mKeyGestureHandlerRecords.put(pid, record);
        }
    }

    /** Unregister the key gesture event handler for a process. */
    @BinderThread
    public void unregisterKeyGestureHandler(IKeyGestureHandler handler, int pid) {
        synchronized (mKeyGestureHandlerRecords) {
            KeyGestureHandlerRecord record = mKeyGestureHandlerRecords.get(pid);
            if (record == null) {
                throw new IllegalStateException("The calling process has no registered "
                        + "KeyGestureHandler.");
            }
            if (record.mKeyGestureHandler.asBinder() != handler.asBinder()) {
                throw new IllegalStateException("The calling process has a different registered "
                        + "KeyGestureHandler.");
            }
            record.mKeyGestureHandler.asBinder().unlinkToDeath(record, 0);
            mKeyGestureHandlerRecords.remove(pid);
        }
    }

    private void onKeyGestureHandlerDied(int pid) {
        synchronized (mKeyGestureHandlerRecords) {
            mKeyGestureHandlerRecords.remove(pid);
        }
    }

    // A record of a registered key gesture event listener from one process.
    private class KeyGestureHandlerRecord implements IBinder.DeathRecipient {
        public final int mPid;
        public final IKeyGestureHandler mKeyGestureHandler;

        KeyGestureHandlerRecord(int pid, IKeyGestureHandler keyGestureHandler) {
            mPid = pid;
            mKeyGestureHandler = keyGestureHandler;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Key gesture event handler for pid " + mPid + " died.");
            }
            onKeyGestureHandlerDied(mPid);
        }

        public boolean handleKeyGesture(AidlKeyGestureEvent event, IBinder focusedToken) {
            try {
                return mKeyGestureHandler.handleKeyGesture(event, focusedToken);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to send key gesture to process " + mPid
                        + ", assuming it died.", ex);
                binderDied();
            }
            return false;
        }

        public boolean isKeyGestureSupported(@KeyGestureEvent.KeyGestureType int gestureType) {
            try {
                return mKeyGestureHandler.isKeyGestureSupported(gestureType);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to identify if key gesture type is supported by the "
                        + "process " + mPid + ", assuming it died.", ex);
                binderDied();
            }
            return false;
        }
    }

    @Nullable
    private InputDevice getInputDevice(int deviceId) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDevice(deviceId) : null;
    }

    private AidlKeyGestureEvent createKeyGestureEvent(int deviceId, int[] keycodes,
            int modifierState, @KeyGestureEvent.KeyGestureType int gestureType, int action,
            int displayId, int flags) {
        AidlKeyGestureEvent event = new AidlKeyGestureEvent();
        event.deviceId = deviceId;
        event.keycodes = keycodes;
        event.modifierState = modifierState;
        event.gestureType = gestureType;
        event.action = action;
        event.displayId = displayId;
        event.flags = flags;
        return event;
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("KeyGestureController:");
        ipw.increaseIndent();
        ipw.println("mSystemPid = " + mSystemPid);
        ipw.println("mPendingMetaAction = " + mPendingMetaAction);
        ipw.println("mPendingCapsLockToggle = " + mPendingCapsLockToggle);
        ipw.println("mPendingHideRecentSwitcher = " + mPendingHideRecentSwitcher);
        ipw.println("mSearchKeyBehavior = " + mSearchKeyBehavior);
        ipw.println("mSettingsKeyBehavior = " + mSettingsKeyBehavior);
        ipw.print("mKeyGestureEventListenerRecords = {");
        synchronized (mKeyGestureEventListenerRecords) {
            int size = mKeyGestureEventListenerRecords.size();
            for (int i = 0; i < size; i++) {
                ipw.print(mKeyGestureEventListenerRecords.keyAt(i));
                if (i < size - 1) {
                    ipw.print(", ");
                }
            }
        }
        ipw.println("}");
        ipw.print("mKeyGestureHandlerRecords = {");
        synchronized (mKeyGestureHandlerRecords) {
            int i = mKeyGestureHandlerRecords.size() - 1;
            for (int processId : mKeyGestureHandlerRecords.keySet()) {
                ipw.print(processId);
                if (i > 0) {
                    ipw.print(", ");
                }
                i--;
            }
        }
        ipw.println("}");
        ipw.decreaseIndent();
        ipw.println("Last handled KeyGestureEvents: ");
        ipw.increaseIndent();
        for (KeyGestureEvent ev : mLastHandledEvents) {
            ipw.println(ev);
        }
        ipw.decreaseIndent();
    }
}
