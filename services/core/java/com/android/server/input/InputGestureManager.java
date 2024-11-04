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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.hardware.input.InputGestureData;
import android.hardware.input.InputManager;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A thread-safe component of {@link InputManagerService} responsible for managing pre-defined input
 * gestures and custom gestures defined by other system components using Input APIs.
 *
 * TODO(b/365064144): Add implementation to persist data, identify clashes with existing shortcuts.
 *
 */
final class InputGestureManager {
    private static final String TAG = "InputGestureManager";

    private static final int KEY_GESTURE_META_MASK =
            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON
                    | KeyEvent.META_META_ON;

    @GuardedBy("mCustomInputGestures")
    private final SparseArray<Map<InputGestureData.Trigger, InputGestureData>>
            mCustomInputGestures = new SparseArray<>();

    @InputManager.CustomInputGestureResult
    public int addCustomInputGesture(int userId, InputGestureData newGesture) {
        synchronized (mCustomInputGestures) {
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
        synchronized (mCustomInputGestures) {
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
            if (customGestures.size() == 0) {
                mCustomInputGestures.remove(userId);
            }
            return InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS;
        }
    }

    public void removeAllCustomInputGestures(int userId) {
        synchronized (mCustomInputGestures) {
            mCustomInputGestures.remove(userId);
        }
    }

    @NonNull
    public List<InputGestureData> getCustomInputGestures(int userId) {
        synchronized (mCustomInputGestures) {
            if (!mCustomInputGestures.contains(userId)) {
                return List.of();
            }
            return new ArrayList<>(mCustomInputGestures.get(userId).values());
        }
    }

    @Nullable
    public InputGestureData getCustomGestureForKeyEvent(@UserIdInt int userId, KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            return null;
        }
        synchronized (mCustomInputGestures) {
            Map<InputGestureData.Trigger, InputGestureData> customGestures =
                    mCustomInputGestures.get(userId);
            if (customGestures == null) {
                return null;
            }
            int modifierState = event.getMetaState() & KEY_GESTURE_META_MASK;
            return customGestures.get(InputGestureData.createKeyTrigger(keyCode, modifierState));
        }
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("InputGestureManager:");
        ipw.increaseIndent();
        synchronized (mCustomInputGestures) {
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
