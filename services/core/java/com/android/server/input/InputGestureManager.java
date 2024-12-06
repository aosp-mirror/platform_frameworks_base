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

import static android.hardware.input.InputGestureData.createKeyTrigger;

import static com.android.hardware.input.Flags.enableTalkbackAndMagnifierKeyGestures;
import static com.android.hardware.input.Flags.keyboardA11yShortcutControl;
import static com.android.server.flags.Flags.newBugreportKeyboardShortcut;
import static com.android.window.flags.Flags.enableMoveToNextDisplayShortcut;
import static com.android.window.flags.Flags.enableTaskResizingKeyboardShortcuts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.hardware.input.InputGestureData;
import android.hardware.input.InputManager;
import android.hardware.input.InputSettings;
import android.hardware.input.KeyGestureEvent;
import android.os.SystemProperties;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing pre-defined input
 * gestures and custom gestures defined by other system components using Input APIs.
 *
 * TODO(b/365064144): Add implementation to persist data.
 *
 */
final class InputGestureManager {
    private static final String TAG = "InputGestureManager";

    private static final int KEY_GESTURE_META_MASK =
            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON
                    | KeyEvent.META_META_ON;

    private final Context mContext;

    private static final Object mGestureLock = new Object();
    @GuardedBy("mGestureLock")
    private final SparseArray<Map<InputGestureData.Trigger, InputGestureData>>
            mCustomInputGestures = new SparseArray<>();

    @GuardedBy("mGestureLock")
    private final Map<InputGestureData.Trigger, InputGestureData> mSystemShortcuts =
            new HashMap<>();

    @GuardedBy("mGestureLock")
    private final Set<InputGestureData.Trigger> mBlockListedTriggers = new HashSet<>(Set.of(
            createKeyTrigger(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON),
            createKeyTrigger(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON),
            createKeyTrigger(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON),
            createKeyTrigger(KeyEvent.KEYCODE_SPACE,
                    KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON),
            createKeyTrigger(KeyEvent.KEYCODE_Z,
                    KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON),
            createKeyTrigger(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON),
            createKeyTrigger(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON),
            createKeyTrigger(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON),
            createKeyTrigger(KeyEvent.KEYCODE_X, KeyEvent.META_CTRL_ON),
            createKeyTrigger(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON),
            createKeyTrigger(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON)
    ));

    public InputGestureManager(Context context) {
        mContext = context;
    }

    public void systemRunning() {
        initSystemShortcuts();
        blockListBookmarkedTriggers();
    }

    private void initSystemShortcuts() {
        // Initialize all system shortcuts
        List<InputGestureData> systemShortcuts = new ArrayList<>(List.of(
                createKeyGesture(
                        KeyEvent.KEYCODE_A,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_H,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_HOME
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_HOME
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_I,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_SYSTEM_SETTINGS
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_L,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_LOCK_SCREEN
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_N,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_NOTIFICATION_PANEL
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_N,
                        KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_S,
                        KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_DEL,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_ESCAPE,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_MULTI_WINDOW_NAVIGATION
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_DESKTOP_MODE
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_BACK
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_LEFT
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_LEFT
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_SPLIT_SCREEN_NAVIGATION_RIGHT
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_CHANGE_SPLITSCREEN_FOCUS_RIGHT
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_SLASH,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_SHORTCUT_HELPER
                ),
                createKeyGesture(
                        KeyEvent.KEYCODE_TAB,
                        KeyEvent.META_META_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS
                )
        ));
        if (newBugreportKeyboardShortcut() && "1".equals(SystemProperties.get("ro.debuggable"))) {
            systemShortcuts.add(createKeyGesture(
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_TRIGGER_BUG_REPORT
            ));
        }
        if (enableMoveToNextDisplayShortcut()) {
            systemShortcuts.add(createKeyGesture(
                    KeyEvent.KEYCODE_D,
                    KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_MOVE_TO_NEXT_DISPLAY
            ));
        }
        if (enableTalkbackAndMagnifierKeyGestures()) {
            systemShortcuts.add(createKeyGesture(KeyEvent.KEYCODE_T,
                    KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_TALKBACK));
            systemShortcuts.add(createKeyGesture(KeyEvent.KEYCODE_MINUS,
                    KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_MAGNIFIER_ZOOM_OUT));
            systemShortcuts.add(createKeyGesture(KeyEvent.KEYCODE_EQUALS,
                    KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_MAGNIFIER_ZOOM_IN));
            systemShortcuts.add(createKeyGesture(KeyEvent.KEYCODE_M,
                    KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MAGNIFICATION));
            systemShortcuts.add(createKeyGesture(KeyEvent.KEYCODE_S,
                    KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK));
        }
        if (enableTaskResizingKeyboardShortcuts()) {
            systemShortcuts.add(createKeyGesture(
                    KeyEvent.KEYCODE_LEFT_BRACKET,
                    KeyEvent.META_META_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_SNAP_LEFT_FREEFORM_WINDOW
            ));
            systemShortcuts.add(createKeyGesture(
                    KeyEvent.KEYCODE_RIGHT_BRACKET,
                    KeyEvent.META_META_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_SNAP_RIGHT_FREEFORM_WINDOW
            ));
            systemShortcuts.add(createKeyGesture(
                    KeyEvent.KEYCODE_EQUALS,
                    KeyEvent.META_META_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MAXIMIZE_FREEFORM_WINDOW
            ));
            systemShortcuts.add(createKeyGesture(
                    KeyEvent.KEYCODE_MINUS,
                    KeyEvent.META_META_ON,
                    KeyGestureEvent.KEY_GESTURE_TYPE_MINIMIZE_FREEFORM_WINDOW
            ));
        }
        if (keyboardA11yShortcutControl()) {
            if (InputSettings.isAccessibilityBounceKeysFeatureEnabled()) {
                systemShortcuts.add(createKeyGesture(
                        KeyEvent.KEYCODE_3,
                        KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_BOUNCE_KEYS
                ));
            }
            if (InputSettings.isAccessibilityMouseKeysFeatureFlagEnabled()) {
                systemShortcuts.add(createKeyGesture(
                        KeyEvent.KEYCODE_4,
                        KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MOUSE_KEYS
                ));
            }
            if (InputSettings.isAccessibilityStickyKeysFeatureEnabled()) {
                systemShortcuts.add(createKeyGesture(
                        KeyEvent.KEYCODE_5,
                        KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_STICKY_KEYS
                ));
            }
            if (InputSettings.isAccessibilitySlowKeysFeatureFlagEnabled()) {
                systemShortcuts.add(createKeyGesture(
                        KeyEvent.KEYCODE_6,
                        KeyEvent.META_META_ON | KeyEvent.META_ALT_ON,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_SLOW_KEYS
                ));
            }
        }
        synchronized (mGestureLock) {
            for (InputGestureData systemShortcut : systemShortcuts) {
                mSystemShortcuts.put(systemShortcut.getTrigger(), systemShortcut);
            }
        }
    }

    private void blockListBookmarkedTriggers() {
        synchronized (mGestureLock) {
            InputManager im = Objects.requireNonNull(mContext.getSystemService(InputManager.class));
            for (InputGestureData bookmark : im.getAppLaunchBookmarks()) {
                mBlockListedTriggers.add(bookmark.getTrigger());
            }
        }
    }

    @InputManager.CustomInputGestureResult
    public int addCustomInputGesture(int userId, InputGestureData newGesture) {
        synchronized (mGestureLock) {
            if (mBlockListedTriggers.contains(newGesture.getTrigger())
                    || mSystemShortcuts.containsKey(newGesture.getTrigger())) {
                return InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE;
            }
            if (newGesture.getTrigger() instanceof InputGestureData.KeyTrigger keyTrigger) {
                if (KeyEvent.isModifierKey(keyTrigger.getKeycode()) ||
                        KeyEvent.isSystemKey(keyTrigger.getKeycode())) {
                    return InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_RESERVED_GESTURE;
                }
            }
            if (!mCustomInputGestures.contains(userId)) {
                mCustomInputGestures.put(userId, new HashMap<>());
            }
            Map<InputGestureData.Trigger, InputGestureData> customGestures =
                    mCustomInputGestures.get(userId);
            if (customGestures.containsKey(newGesture.getTrigger())) {
                return InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS;
            }
            customGestures.put(newGesture.getTrigger(), newGesture);
            return InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS;
        }
    }

    @InputManager.CustomInputGestureResult
    public int removeCustomInputGesture(int userId, InputGestureData data) {
        synchronized (mGestureLock) {
            if (!mCustomInputGestures.contains(userId)) {
                return InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST;
            }
            Map<InputGestureData.Trigger, InputGestureData> customGestures =
                    mCustomInputGestures.get(userId);
            InputGestureData customGesture = customGestures.get(data.getTrigger());
            if (!Objects.equals(data, customGesture)) {
                return InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST;
            }
            customGestures.remove(data.getTrigger());
            if (customGestures.isEmpty()) {
                mCustomInputGestures.remove(userId);
            }
            return InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS;
        }
    }

    public void removeAllCustomInputGestures(int userId, @Nullable InputGestureData.Filter filter) {
        synchronized (mGestureLock) {
            Map<InputGestureData.Trigger, InputGestureData> customGestures =
                    mCustomInputGestures.get(userId);
            if (customGestures == null) {
                return;
            }
            if (filter == null) {
                mCustomInputGestures.remove(userId);
                return;
            }
            customGestures.entrySet().removeIf(entry -> filter.matches(entry.getValue()));
            if (customGestures.isEmpty()) {
                mCustomInputGestures.remove(userId);
            }
        }
    }

    @NonNull
    public List<InputGestureData> getCustomInputGestures(int userId,
            @Nullable InputGestureData.Filter filter) {
        synchronized (mGestureLock) {
            if (!mCustomInputGestures.contains(userId)) {
                return List.of();
            }
            Map<InputGestureData.Trigger, InputGestureData> customGestures =
                    mCustomInputGestures.get(userId);
            if (filter == null) {
                return new ArrayList<>(customGestures.values());
            }
            List<InputGestureData> result = new ArrayList<>();
            for (InputGestureData customGesture : customGestures.values()) {
                if (filter.matches(customGesture)) {
                    result.add(customGesture);
                }
            }
            return result;
        }
    }

    @Nullable
    public InputGestureData getCustomGestureForKeyEvent(@UserIdInt int userId, KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return null;
        }
        synchronized (mGestureLock) {
            Map<InputGestureData.Trigger, InputGestureData> customGestures =
                    mCustomInputGestures.get(userId);
            if (customGestures == null) {
                return null;
            }
            int modifierState = event.getMetaState() & KEY_GESTURE_META_MASK;
            return customGestures.get(InputGestureData.createKeyTrigger(keyCode, modifierState));
        }
    }

    @Nullable
    public InputGestureData getCustomGestureForTouchpadGesture(@UserIdInt int userId,
            int touchpadGestureType) {
        if (touchpadGestureType == InputGestureData.TOUCHPAD_GESTURE_TYPE_UNKNOWN) {
            return null;
        }
        synchronized (mGestureLock) {
            Map<InputGestureData.Trigger, InputGestureData> customGestures =
                    mCustomInputGestures.get(userId);
            if (customGestures == null) {
                return null;
            }
            return customGestures.get(InputGestureData.createTouchpadTrigger(touchpadGestureType));
        }
    }

    @Nullable
    public InputGestureData getSystemShortcutForKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return null;
        }
        synchronized (mGestureLock) {
            int modifierState = event.getMetaState() & KEY_GESTURE_META_MASK;
            return mSystemShortcuts.get(InputGestureData.createKeyTrigger(keyCode, modifierState));
        }
    }

    private static InputGestureData createKeyGesture(int keycode, int modifierState,
            int keyGestureType) {
        return new InputGestureData.Builder()
                .setTrigger(createKeyTrigger(keycode, modifierState))
                .setKeyGestureType(keyGestureType)
                .build();
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("InputGestureManager:");
        ipw.increaseIndent();
        synchronized (mGestureLock) {
            ipw.println("System Shortcuts:");
            ipw.increaseIndent();
            for (InputGestureData systemShortcut : mSystemShortcuts.values()) {
                ipw.println(systemShortcut);
            }
            ipw.decreaseIndent();
            ipw.println("Blocklisted Triggers:");
            ipw.increaseIndent();
            for (InputGestureData.Trigger blocklistedTrigger : mBlockListedTriggers) {
                ipw.println(blocklistedTrigger);
            }
            ipw.decreaseIndent();
            ipw.println("Custom Gestures:");
            ipw.increaseIndent();
            int size = mCustomInputGestures.size();
            for (int i = 0; i < size; i++) {
                Map<InputGestureData.Trigger, InputGestureData> customGestures =
                        mCustomInputGestures.valueAt(i);
                ipw.println("UserId = " + mCustomInputGestures.keyAt(i));
                ipw.increaseIndent();
                for (InputGestureData customGesture : customGestures.values()) {
                    ipw.println(customGesture);
                }
                ipw.decreaseIndent();
            }
        }
        ipw.decreaseIndent();
    }
}
