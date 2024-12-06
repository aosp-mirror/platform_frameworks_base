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

import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.view.WindowManagerPolicyConstants.FLAG_INTERACTIVE;

import static com.android.hardware.input.Flags.enableNew25q2Keycodes;

import android.annotation.BinderThread;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.input.AidlInputGestureData;
import android.hardware.input.AidlKeyGestureEvent;
import android.hardware.input.AppLaunchData;
import android.hardware.input.IKeyGestureEventListener;
import android.hardware.input.IKeyGestureHandler;
import android.hardware.input.InputGestureData;
import android.hardware.input.InputManager;
import android.hardware.input.InputSettings;
import android.hardware.input.KeyGestureEvent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.IShortcutService;
import com.android.server.policy.KeyCombinationManager;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
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
    private static final int SHORTCUT_META_MASK =
            KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON
                    | KeyEvent.META_SHIFT_ON;

    private static final int MSG_NOTIFY_KEY_GESTURE_EVENT = 1;
    private static final int MSG_PERSIST_CUSTOM_GESTURES = 2;
    private static final int MSG_LOAD_CUSTOM_GESTURES = 3;

    // must match: config_settingsKeyBehavior in config.xml
    private static final int SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY = 0;
    private static final int SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL = 1;
    private static final int SETTINGS_KEY_BEHAVIOR_NOTHING = 2;
    private static final int LAST_SETTINGS_KEY_BEHAVIOR = SETTINGS_KEY_BEHAVIOR_NOTHING;

    // Must match: config_searchKeyBehavior in config.xml
    private static final int SEARCH_KEY_BEHAVIOR_DEFAULT_SEARCH = 0;
    private static final int SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY = 1;
    private static final int LAST_SEARCH_KEY_BEHAVIOR = SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY;

    // must match: config_keyChordPowerVolumeUp in config.xml
    static final int POWER_VOLUME_UP_BEHAVIOR_NOTHING = 0;
    static final int POWER_VOLUME_UP_BEHAVIOR_MUTE = 1;
    static final int POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS = 2;

    private final Context mContext;
    private final Handler mHandler;
    private final int mSystemPid;
    private final KeyCombinationManager mKeyCombinationManager;
    private final SettingsObserver mSettingsObserver;
    private final AppLaunchShortcutManager mAppLaunchShortcutManager;
    private final InputGestureManager mInputGestureManager;
    @GuardedBy("mInputDataStore")
    private final InputDataStore mInputDataStore;
    private static final Object mUserLock = new Object();
    @UserIdInt
    @GuardedBy("mUserLock")
    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    // Pending actions
    private boolean mPendingMetaAction;
    private boolean mPendingCapsLockToggle;
    private boolean mPendingHideRecentSwitcher;

    // Platform behaviors
    private boolean mHasFeatureWatch;
    private boolean mHasFeatureLeanback;

    // Key behaviors
    private int mSearchKeyBehavior;
    private int mSettingsKeyBehavior;

    // Settings behaviors
    private int mRingerToggleChord = Settings.Secure.VOLUME_HUSH_OFF;
    private int mPowerVolUpBehavior;


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

    KeyGestureController(Context context, Looper looper, InputDataStore inputDataStore) {
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
        mKeyCombinationManager = new KeyCombinationManager(mHandler);
        mSettingsObserver = new SettingsObserver(mHandler);
        mAppLaunchShortcutManager = new AppLaunchShortcutManager(mContext);
        mInputGestureManager = new InputGestureManager(mContext);
        mInputDataStore = inputDataStore;
        initBehaviors();
        initKeyCombinationRules();
    }

    private void initBehaviors() {
        PackageManager pm = mContext.getPackageManager();
        mHasFeatureWatch = pm.hasSystemFeature(FEATURE_WATCH);
        mHasFeatureLeanback = pm.hasSystemFeature(FEATURE_LEANBACK);

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

        mHandler.post(this::initBehaviorsFromSettings);
    }

    private void initBehaviorsFromSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mRingerToggleChord = Settings.Secure.getIntForUser(resolver,
                Settings.Secure.VOLUME_HUSH_GESTURE, Settings.Secure.VOLUME_HUSH_OFF,
                UserHandle.USER_CURRENT);

        mPowerVolUpBehavior = Settings.Global.getInt(resolver,
                Settings.Global.KEY_CHORD_POWER_VOLUME_UP,
                mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_keyChordPowerVolumeUp));
    }

    private void initKeyCombinationRules() {
        if (!InputSettings.doesKeyGestureEventHandlerSupportMultiKeyGestures()) {
            return;
        }
        // TODO(b/358569822): Handle Power, Back key properly since key combination gesture is
        //  captured here and rest of the Power, Back key behaviors are handled in PWM
        final boolean screenshotChordEnabled = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableScreenshotChord);

        if (screenshotChordEnabled) {
            mKeyCombinationManager.addRule(
                    new KeyCombinationManager.TwoKeysCombinationRule(KeyEvent.KEYCODE_VOLUME_DOWN,
                            KeyEvent.KEYCODE_POWER) {
                        @Override
                        public boolean preCondition() {
                            return isKeyGestureSupported(
                                    KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD);
                        }

                        @Override
                        public void execute() {
                            handleMultiKeyGesture(
                                    new int[]{KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER},
                                    KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD,
                                    KeyGestureEvent.ACTION_GESTURE_START, 0);
                        }

                        @Override
                        public void cancel() {
                            handleMultiKeyGesture(
                                    new int[]{KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER},
                                    KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD,
                                    KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                                    KeyGestureEvent.FLAG_CANCELLED);
                        }
                    });

            if (mHasFeatureWatch) {
                mKeyCombinationManager.addRule(
                        new KeyCombinationManager.TwoKeysCombinationRule(KeyEvent.KEYCODE_POWER,
                                KeyEvent.KEYCODE_STEM_PRIMARY) {
                            @Override
                            public boolean preCondition() {
                                return isKeyGestureSupported(
                                        KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD);
                            }

                            @Override
                            public void execute() {
                                handleMultiKeyGesture(new int[]{KeyEvent.KEYCODE_POWER,
                                                KeyEvent.KEYCODE_STEM_PRIMARY},
                                        KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD,
                                        KeyGestureEvent.ACTION_GESTURE_START, 0);
                            }
                            @Override
                            public void cancel() {
                                handleMultiKeyGesture(new int[]{KeyEvent.KEYCODE_POWER,
                                                KeyEvent.KEYCODE_STEM_PRIMARY},
                                        KeyGestureEvent.KEY_GESTURE_TYPE_SCREENSHOT_CHORD,
                                        KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                                        KeyGestureEvent.FLAG_CANCELLED);
                            }
                        });
            }
        }

        mKeyCombinationManager.addRule(
                new KeyCombinationManager.TwoKeysCombinationRule(KeyEvent.KEYCODE_VOLUME_DOWN,
                        KeyEvent.KEYCODE_VOLUME_UP) {
                    @Override
                    public boolean preCondition() {
                        return isKeyGestureSupported(
                                KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD);
                    }

                    @Override
                    public void execute() {
                        handleMultiKeyGesture(
                                new int[]{KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP},
                                KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD,
                                KeyGestureEvent.ACTION_GESTURE_START, 0);
                    }

                    @Override
                    public void cancel() {
                        handleMultiKeyGesture(
                                new int[]{KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP},
                                KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT_CHORD,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                                KeyGestureEvent.FLAG_CANCELLED);
                    }
                });

        // Volume up + power can either be the "ringer toggle chord" or as another way to
        // launch GlobalActions. This behavior can change at runtime so we must check behavior
        // inside the TwoKeysCombinationRule.
        mKeyCombinationManager.addRule(
                new KeyCombinationManager.TwoKeysCombinationRule(KeyEvent.KEYCODE_VOLUME_UP,
                        KeyEvent.KEYCODE_POWER) {
                    @Override
                    public boolean preCondition() {
                        if (!isKeyGestureSupported(getGestureType())) {
                            return false;
                        }
                        switch (mPowerVolUpBehavior) {
                            case POWER_VOLUME_UP_BEHAVIOR_MUTE:
                                return mRingerToggleChord != Settings.Secure.VOLUME_HUSH_OFF;
                            default:
                                return true;
                        }
                    }
                    @Override
                    public void execute() {
                        int gestureType = getGestureType();
                        if (gestureType == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
                            return;
                        }
                        handleMultiKeyGesture(
                                new int[]{KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_POWER},
                                gestureType, KeyGestureEvent.ACTION_GESTURE_START, 0);
                    }
                    @Override
                    public void cancel() {
                        int gestureType = getGestureType();
                        if (gestureType == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
                            return;
                        }
                        handleMultiKeyGesture(
                                new int[]{KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_POWER},
                                gestureType, KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                                KeyGestureEvent.FLAG_CANCELLED);
                    }

                    @KeyGestureEvent.KeyGestureType
                    private int getGestureType() {
                        switch (mPowerVolUpBehavior) {
                            case POWER_VOLUME_UP_BEHAVIOR_MUTE -> {
                                return KeyGestureEvent.KEY_GESTURE_TYPE_RINGER_TOGGLE_CHORD;
                            }
                            case POWER_VOLUME_UP_BEHAVIOR_GLOBAL_ACTIONS -> {
                                return KeyGestureEvent.KEY_GESTURE_TYPE_GLOBAL_ACTIONS;
                            }
                            default -> {
                                return KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED;
                            }
                        }
                    }
                });

        if (mHasFeatureLeanback) {
            mKeyCombinationManager.addRule(
                    new KeyCombinationManager.TwoKeysCombinationRule(KeyEvent.KEYCODE_BACK,
                            KeyEvent.KEYCODE_DPAD_DOWN) {
                        @Override
                        public boolean preCondition() {
                            return isKeyGestureSupported(
                                    KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD);
                        }

                        @Override
                        public void execute() {
                            handleMultiKeyGesture(
                                    new int[]{KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN},
                                    KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD,
                                    KeyGestureEvent.ACTION_GESTURE_START, 0);
                        }

                        @Override
                        public void cancel() {
                            handleMultiKeyGesture(
                                    new int[]{KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_DOWN},
                                    KeyGestureEvent.KEY_GESTURE_TYPE_TV_ACCESSIBILITY_SHORTCUT_CHORD,
                                    KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                                    KeyGestureEvent.FLAG_CANCELLED);
                        }
                        @Override
                        public long getKeyInterceptDelayMs() {
                            // Use a timeout of 0 to prevent additional latency in processing of
                            // this key. This will potentially cause some unwanted UI actions if the
                            // user does end up triggering the key combination later, but in most
                            // cases, the user will simply hit a single key, and this will allow us
                            // to process it without first waiting to see if the combination is
                            // going to be triggered.
                            return 0;
                        }
                    });

            mKeyCombinationManager.addRule(
                    new KeyCombinationManager.TwoKeysCombinationRule(KeyEvent.KEYCODE_BACK,
                            KeyEvent.KEYCODE_DPAD_CENTER) {
                        @Override
                        public boolean preCondition() {
                            return isKeyGestureSupported(
                                    KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT);
                        }

                        @Override
                        public void execute() {
                            handleMultiKeyGesture(
                                    new int[]{KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER},
                                    KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT,
                                    KeyGestureEvent.ACTION_GESTURE_START, 0);
                        }
                        @Override
                        public void cancel() {
                            handleMultiKeyGesture(
                                    new int[]{KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER},
                                    KeyGestureEvent.KEY_GESTURE_TYPE_TV_TRIGGER_BUG_REPORT,
                                    KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                                    KeyGestureEvent.FLAG_CANCELLED);
                        }
                        @Override
                        public long getKeyInterceptDelayMs() {
                            return 0;
                        }
                    });
        }
    }

    public void systemRunning() {
        mSettingsObserver.observe();
        mAppLaunchShortcutManager.systemRunning();
        mInputGestureManager.systemRunning();

        int userId;
        synchronized (mUserLock) {
            userId = mCurrentUserId;
        }
        // Load the system user's input gestures.
        mHandler.obtainMessage(MSG_LOAD_CUSTOM_GESTURES, userId).sendToTarget();
    }

    public boolean interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        final boolean interactive = (policyFlags & FLAG_INTERACTIVE) != 0;
        if (InputSettings.doesKeyGestureEventHandlerSupportMultiKeyGestures()
                && (event.getFlags() & KeyEvent.FLAG_FALLBACK) == 0) {
            return mKeyCombinationManager.interceptKey(event, interactive);
        }
        return false;
    }

    public long interceptKeyBeforeDispatching(IBinder focusedToken, KeyEvent event,
            int policyFlags) {
        // TODO(b/358569822): Handle shortcuts trigger logic here and pass it to appropriate
        //  KeyGestureHandler (PWM is one of the handlers)
        final int keyCode = event.getKeyCode();
        final int deviceId = event.getDeviceId();
        final int flags = event.getFlags();
        final long keyConsumed = -1;
        final long keyNotConsumed = 0;

        if (InputSettings.doesKeyGestureEventHandlerSupportMultiKeyGestures()) {
            if (mKeyCombinationManager.isKeyConsumed(event)) {
                return keyConsumed;
            }

            if ((flags & KeyEvent.FLAG_FALLBACK) == 0) {
                final long now = SystemClock.uptimeMillis();
                final long interceptTimeout = mKeyCombinationManager.getKeyInterceptTimeout(
                        keyCode);
                if (now < interceptTimeout) {
                    return interceptTimeout - now;
                }
            }
        }

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
        final int metaState = event.getMetaState() & SHORTCUT_META_MASK;
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

        // Handle App launch shortcuts
        AppLaunchShortcutManager.InterceptKeyResult result = mAppLaunchShortcutManager.interceptKey(
                event);
        if (result.consumed()) {
            return true;
        }
        if (result.appLaunchData() != null) {
            return handleKeyGesture(deviceId, new int[]{keyCode}, metaState,
                    KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION,
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                    focusedToken, /* flags = */0, result.appLaunchData());
        }

        // Handle system shortcuts
        if (firstDown) {
            InputGestureData systemShortcut = mInputGestureManager.getSystemShortcutForKeyEvent(
                    event);
            if (systemShortcut != null) {
                return handleKeyGesture(deviceId, new int[]{keyCode}, metaState,
                        systemShortcut.getAction().keyGestureType(),
                        KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                        displayId, focusedToken, /* flags = */0,
                        systemShortcut.getAction().appLaunchData());
            }
        }

        // Handle system keys
        switch (keyCode) {
            case KeyEvent.KEYCODE_RECENT_APPS:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_APP_SWITCH:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                            KeyGestureEvent.ACTION_GESTURE_START, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                } else if (!down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_APP_SWITCH,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, canceled ? KeyGestureEvent.FLAG_CANCELLED : 0,
                            /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_BRIGHTNESS_UP:
            case KeyEvent.KEYCODE_BRIGHTNESS_DOWN:
                if (down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            keyCode == KeyEvent.KEYCODE_BRIGHTNESS_UP
                                    ? KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_UP
                                    : KeyGestureEvent.KEY_GESTURE_TYPE_BRIGHTNESS_DOWN,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN:
                if (down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_DOWN,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP:
                if (down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_UP,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_TOGGLE:
                // TODO: Add logic
                if (!down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_KEYBOARD_BACKLIGHT_TOGGLE,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_ALL_APPS:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_NOTIFICATION:
                if (!down) {
                    handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                if (firstDown && mSearchKeyBehavior == SEARCH_KEY_BEHAVIOR_TARGET_ACTIVITY) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SEARCH,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);

                }
                break;
            case KeyEvent.KEYCODE_SETTINGS:
                if (firstDown) {
                    if (mSettingsKeyBehavior == SETTINGS_KEY_BEHAVIOR_SETTINGS_ACTIVITY) {
                        handleKeyGesture(deviceId,
                                new int[]{keyCode}, /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0, /* appLaunchData = */null);
                    } else if (mSettingsKeyBehavior == SETTINGS_KEY_BEHAVIOR_NOTIFICATION_PANEL) {
                        handleKeyGesture(deviceId,
                                new int[]{keyCode}, /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0, /* appLaunchData = */null);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_LANGUAGE_SWITCH:
                if (firstDown) {
                    handleKeyGesture(deviceId, new int[]{keyCode},
                            event.isShiftPressed() ? KeyEvent.META_SHIFT_ON : 0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                return true;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                // Just logging/notifying purposes
                // Caps lock is already handled in inputflinger native
                if (!down) {
                    AidlKeyGestureEvent eventToNotify = createKeyGestureEvent(deviceId,
                            new int[]{keyCode}, metaState,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId, /* flags = */0,
                            /* appLaunchData = */null);
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
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
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
                                focusedToken, /* flags = */0, /* appLaunchData = */null);

                    } else if (mPendingMetaAction) {
                        mPendingMetaAction = false;
                        if (!canceled) {
                            handleKeyGesture(deviceId, new int[]{keyCode},
                                    /* modifierState = */0,
                                    KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS,
                                    KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                    focusedToken, /* flags = */0, /* appLaunchData = */null);
                        }
                    }
                }
                return true;
            case KeyEvent.KEYCODE_TAB:
                if (firstDown) {
                    if (!mPendingHideRecentSwitcher) {
                        final int shiftlessModifiers =
                                event.getModifiers() & ~KeyEvent.META_SHIFT_MASK;
                        if (KeyEvent.metaStateHasModifiers(
                                shiftlessModifiers, KeyEvent.META_ALT_ON)) {
                            mPendingHideRecentSwitcher = true;
                            return handleKeyGesture(deviceId, new int[]{keyCode},
                                    KeyEvent.META_ALT_ON,
                                    KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS_SWITCHER,
                                    KeyGestureEvent.ACTION_GESTURE_START, displayId,
                                    focusedToken, /* flags = */0, /* appLaunchData = */null);
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
                                focusedToken, /* flags = */0, /* appLaunchData = */null);
                    }

                    // Toggle Caps Lock on META-ALT.
                    if (mPendingCapsLockToggle) {
                        mPendingCapsLockToggle = false;
                        return handleKeyGesture(deviceId, new int[]{KeyEvent.KEYCODE_META_LEFT,
                                        KeyEvent.KEYCODE_ALT_LEFT}, /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_CAPS_LOCK,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0, /* appLaunchData = */null);
                    }
                }
                break;
            case KeyEvent.KEYCODE_LOCK:
                if (enableNew25q2Keycodes()) {
                    if (firstDown) {
                        handleKeyGesture(deviceId, new int[]{KeyEvent.KEYCODE_LOCK},
                                /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId, focusedToken,
                                /* flags = */0, /* appLaunchData = */null);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_FULLSCREEN:
                if (enableNew25q2Keycodes()) {
                    if (firstDown) {
                        handleKeyGesture(deviceId, new int[]{KeyEvent.KEYCODE_FULLSCREEN},
                                /* modifierState = */0,
                                KeyGestureEvent.KEY_GESTURE_TYPE_MAXIMIZE_FREEFORM_WINDOW,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId, focusedToken,
                                /* flags = */0, /* appLaunchData = */null);
                    }
                }
                return true;
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
            case KeyEvent.KEYCODE_DO_NOT_DISTURB:
                // TODO(b/365920375): Implement 25Q2 keycode implementation in system
                return true;
        }

        // Handle shortcuts through shortcut services
        if (mAppLaunchShortcutManager.handleShortcutService(event)) {
            return true;
        }

        // Handle custom shortcuts
        if (firstDown) {
            InputGestureData customGesture;
            synchronized (mUserLock) {
                customGesture = mInputGestureManager.getCustomGestureForKeyEvent(mCurrentUserId,
                        event);
            }
            if (customGesture == null) {
                return false;
            }
            return handleKeyGesture(deviceId, new int[]{keyCode}, metaState,
                    customGesture.getAction().keyGestureType(),
                    KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                    displayId, focusedToken, /* flags = */0,
                    customGesture.getAction().appLaunchData());
        }
        return false;
    }

    boolean interceptUnhandledKey(KeyEvent event, IBinder focusedToken) {
        final int keyCode = event.getKeyCode();
        final int repeatCount = event.getRepeatCount();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final int metaState = event.getModifiers();
        final int deviceId = event.getDeviceId();
        final int displayId = event.getDisplayId();

        switch(keyCode) {
            case KeyEvent.KEYCODE_SPACE:
                if (down && repeatCount == 0) {
                    // Handle keyboard layout switching. (CTRL + SPACE)
                    if (KeyEvent.metaStateHasModifiers(metaState & ~KeyEvent.META_SHIFT_MASK,
                            KeyEvent.META_CTRL_ON)) {
                        return handleKeyGesture(deviceId, new int[]{keyCode},
                                KeyEvent.META_CTRL_ON | (event.isShiftPressed()
                                        ? KeyEvent.META_SHIFT_ON : 0),
                                KeyGestureEvent.KEY_GESTURE_TYPE_LANGUAGE_SWITCH,
                                KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                                focusedToken, /* flags = */0, /* appLaunchData = */null);
                    }
                }
                break;
            case KeyEvent.KEYCODE_Z:
                if (down && KeyEvent.metaStateHasModifiers(metaState,
                        KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON)) {
                    // Intercept the Accessibility keychord (CTRL + ALT + Z) for keyboard users.
                    return handleKeyGesture(deviceId, new int[]{keyCode},
                            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON,
                            KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_SHORTCUT,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                break;
            case KeyEvent.KEYCODE_SYSRQ:
                if (down && repeatCount == 0) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                break;
            case KeyEvent.KEYCODE_ESCAPE:
                if (down && KeyEvent.metaStateHasNoModifiers(metaState) && repeatCount == 0) {
                    return handleKeyGesture(deviceId, new int[]{keyCode}, /* modifierState = */0,
                            KeyGestureEvent.KEY_GESTURE_TYPE_CLOSE_ALL_DIALOGS,
                            KeyGestureEvent.ACTION_GESTURE_COMPLETE, displayId,
                            focusedToken, /* flags = */0, /* appLaunchData = */null);
                }
                break;
        }

        return false;
    }

    private void handleMultiKeyGesture(int[] keycodes,
            @KeyGestureEvent.KeyGestureType int gestureType, int action, int flags) {
        handleKeyGesture(KeyCharacterMap.VIRTUAL_KEYBOARD, keycodes, /* modifierState= */0,
                gestureType, action, Display.DEFAULT_DISPLAY, /* focusedToken = */null, flags,
                /* appLaunchData = */null);
    }

    private void handleTouchpadGesture(@KeyGestureEvent.KeyGestureType int keyGestureType,
            @Nullable AppLaunchData appLaunchData) {
        handleKeyGesture(KeyCharacterMap.VIRTUAL_KEYBOARD, new int[0], /* modifierState= */0,
                keyGestureType, KeyGestureEvent.ACTION_GESTURE_COMPLETE,
                Display.DEFAULT_DISPLAY, /* focusedToken = */null, /* flags = */0, appLaunchData);
    }

    @VisibleForTesting
    boolean handleKeyGesture(int deviceId, int[] keycodes, int modifierState,
            @KeyGestureEvent.KeyGestureType int gestureType, int action, int displayId,
            @Nullable IBinder focusedToken, int flags, @Nullable AppLaunchData appLaunchData) {
        return handleKeyGesture(createKeyGestureEvent(deviceId, keycodes,
                modifierState, gestureType, action, displayId, flags, appLaunchData), focusedToken);
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
                gestureType, KeyGestureEvent.ACTION_GESTURE_COMPLETE, Display.DEFAULT_DISPLAY,
                /* flags = */0, /* appLaunchData = */null);
        mHandler.obtainMessage(MSG_NOTIFY_KEY_GESTURE_EVENT, event).sendToTarget();
    }

    public void handleKeyGesture(int deviceId, int[] keycodes, int modifierState,
            @KeyGestureEvent.KeyGestureType int gestureType) {
        AidlKeyGestureEvent event = createKeyGestureEvent(deviceId, keycodes, modifierState,
                gestureType, KeyGestureEvent.ACTION_GESTURE_COMPLETE, Display.DEFAULT_DISPLAY,
                /* flags = */0, /* appLaunchData = */null);
        handleKeyGesture(event, null /*focusedToken*/);
    }

    public void handleTouchpadGesture(int touchpadGestureType) {
        // Handle custom shortcuts
        InputGestureData customGesture;
        synchronized (mUserLock) {
            customGesture = mInputGestureManager.getCustomGestureForTouchpadGesture(mCurrentUserId,
                    touchpadGestureType);
        }
        if (customGesture == null) {
            return;
        }
        handleTouchpadGesture(customGesture.getAction().keyGestureType(),
                customGesture.getAction().appLaunchData());
    }

    @MainThread
    public void setCurrentUserId(@UserIdInt int userId) {
        synchronized (mUserLock) {
            mCurrentUserId = userId;
        }
        mHandler.obtainMessage(MSG_LOAD_CUSTOM_GESTURES, userId).sendToTarget();
    }

    @MainThread
    private void notifyKeyGestureEvent(AidlKeyGestureEvent event) {
        InputDevice device = getInputDevice(event.deviceId);
        if (device == null) {
            return;
        }
        KeyGestureEvent keyGestureEvent = new KeyGestureEvent(event);
        if (event.action == KeyGestureEvent.ACTION_GESTURE_COMPLETE) {
            KeyboardMetricsCollector.logKeyboardSystemsEventReportedAtom(device, event.keycodes,
                    event.modifierState, keyGestureEvent.getLogEvent());
        }
        notifyAllListeners(event);
        while (mLastHandledEvents.size() >= MAX_TRACKED_EVENTS) {
            mLastHandledEvents.removeFirst();
        }
        mLastHandledEvents.addLast(keyGestureEvent);
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
            case MSG_PERSIST_CUSTOM_GESTURES: {
                final int userId = (Integer) msg.obj;
                persistInputGestures(userId);
                break;
            }
            case MSG_LOAD_CUSTOM_GESTURES: {
                final int userId = (Integer) msg.obj;
                loadInputGestures(userId);
                break;
            }

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

    @BinderThread
    @InputManager.CustomInputGestureResult
    public int addCustomInputGesture(@UserIdInt int userId,
            @NonNull AidlInputGestureData inputGestureData) {
        final int result = mInputGestureManager.addCustomInputGesture(userId,
                new InputGestureData(inputGestureData));
        if (result == InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS) {
            mHandler.obtainMessage(MSG_PERSIST_CUSTOM_GESTURES, userId).sendToTarget();
        }
        return result;
    }

    @BinderThread
    @InputManager.CustomInputGestureResult
    public int removeCustomInputGesture(@UserIdInt int userId,
            @NonNull AidlInputGestureData inputGestureData) {
        final int result = mInputGestureManager.removeCustomInputGesture(userId,
                new InputGestureData(inputGestureData));
        if (result == InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS) {
            mHandler.obtainMessage(MSG_PERSIST_CUSTOM_GESTURES, userId).sendToTarget();
        }
        return result;
    }

    @BinderThread
    public void removeAllCustomInputGestures(@UserIdInt int userId,
            @Nullable InputGestureData.Filter filter) {
        mInputGestureManager.removeAllCustomInputGestures(userId, filter);
        mHandler.obtainMessage(MSG_PERSIST_CUSTOM_GESTURES, userId).sendToTarget();
    }

    @BinderThread
    public AidlInputGestureData[] getCustomInputGestures(@UserIdInt int userId,
            @Nullable InputGestureData.Filter filter) {
        List<InputGestureData> customGestures = mInputGestureManager.getCustomInputGestures(userId,
                filter);
        AidlInputGestureData[] result = new AidlInputGestureData[customGestures.size()];
        for (int i = 0; i < customGestures.size(); i++) {
            result[i] = customGestures.get(i).getAidlData();
        }
        return result;
    }

    @BinderThread
    public AidlInputGestureData[] getAppLaunchBookmarks() {
        List<InputGestureData> bookmarks = mAppLaunchShortcutManager.getBookmarks();
        AidlInputGestureData[] result = new AidlInputGestureData[bookmarks.size()];
        for (int i = 0; i < bookmarks.size(); i++) {
            result[i] = bookmarks.get(i).getAidlData();
        }
        return result;
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

    public void registerShortcutKey(long shortcutCode, IShortcutService shortcutKeyReceiver)
            throws RemoteException {
        mAppLaunchShortcutManager.registerShortcutKey(shortcutCode, shortcutKeyReceiver);
    }

    public List<InputGestureData> getBookmarks() {
        return mAppLaunchShortcutManager.getBookmarks();
    }

    private void onKeyGestureHandlerDied(int pid) {
        synchronized (mKeyGestureHandlerRecords) {
            mKeyGestureHandlerRecords.remove(pid);
        }
    }

    private void persistInputGestures(int userId) {
        synchronized (mInputDataStore) {
            final List<InputGestureData> inputGestureDataList =
                    mInputGestureManager.getCustomInputGestures(userId,
                            null);
            mInputDataStore.saveInputGestures(userId, inputGestureDataList);
        }
    }

    private void loadInputGestures(int userId) {
        synchronized (mInputDataStore) {
            mInputGestureManager.removeAllCustomInputGestures(userId, null);
            final List<InputGestureData> inputGestureDataList = mInputDataStore.loadInputGestures(
                    userId);
            for (final InputGestureData inputGestureData : inputGestureDataList) {
                mInputGestureManager.addCustomInputGesture(userId, inputGestureData);
            }
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

    private class SettingsObserver extends ContentObserver {
        private SettingsObserver(Handler handler) {
            super(handler);
        }

        private void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.VOLUME_HUSH_GESTURE), false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(
                            Settings.Global.KEY_CHORD_POWER_VOLUME_UP), false, this,
                    UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            initBehaviorsFromSettings();
        }
    }

    @Nullable
    private InputDevice getInputDevice(int deviceId) {
        InputManager inputManager = mContext.getSystemService(InputManager.class);
        return inputManager != null ? inputManager.getInputDevice(deviceId) : null;
    }

    private AidlKeyGestureEvent createKeyGestureEvent(int deviceId, int[] keycodes,
            int modifierState, @KeyGestureEvent.KeyGestureType int gestureType, int action,
            int displayId, int flags, @Nullable AppLaunchData appLaunchData) {
        AidlKeyGestureEvent event = new AidlKeyGestureEvent();
        event.deviceId = deviceId;
        event.keycodes = keycodes;
        event.modifierState = modifierState;
        event.gestureType = gestureType;
        event.action = action;
        event.displayId = displayId;
        event.flags = flags;
        if (appLaunchData != null) {
            if (appLaunchData instanceof AppLaunchData.CategoryData categoryData) {
                event.appLaunchCategory = categoryData.getCategory();
            } else if (appLaunchData instanceof AppLaunchData.RoleData roleData) {
                event.appLaunchRole = roleData.getRole();
            } else if (appLaunchData instanceof AppLaunchData.ComponentData componentData) {
                event.appLaunchPackageName = componentData.getPackageName();
                event.appLaunchClassName = componentData.getClassName();
            } else {
                throw new IllegalArgumentException("AppLaunchData type is invalid!");
            }
        }
        return event;
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("KeyGestureController:");
        ipw.increaseIndent();
        ipw.println("mCurrentUserId = " + mCurrentUserId);
        ipw.println("mSystemPid = " + mSystemPid);
        ipw.println("mPendingMetaAction = " + mPendingMetaAction);
        ipw.println("mPendingCapsLockToggle = " + mPendingCapsLockToggle);
        ipw.println("mPendingHideRecentSwitcher = " + mPendingHideRecentSwitcher);
        ipw.println("mSearchKeyBehavior = " + mSearchKeyBehavior);
        ipw.println("mSettingsKeyBehavior = " + mSettingsKeyBehavior);
        ipw.println("mRingerToggleChord = " + mRingerToggleChord);
        ipw.println("mPowerVolUpBehavior = " + mPowerVolUpBehavior);
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
        mKeyCombinationManager.dump("", ipw);
        mAppLaunchShortcutManager.dump(ipw);
        mInputGestureManager.dump(ipw);
    }
}
