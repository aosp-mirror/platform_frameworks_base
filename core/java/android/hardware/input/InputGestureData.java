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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.KeyEvent;

import java.util.Objects;

/**
 * Data class to store input gesture data.
 *
 * <p>
 * All input gestures are of type Trigger -> Action(Key gesture type, app data). And currently types
 * of triggers supported are:
 * - KeyTrigger (Keycode + modifierState)
 * - TODO(b/365064144): Add Touchpad gesture based trigger
 * </p>
 * @hide
 */
public final class InputGestureData {

    @NonNull
    private final AidlInputGestureData mInputGestureData;

    public InputGestureData(AidlInputGestureData inputGestureData) {
        this.mInputGestureData = inputGestureData;
        validate();
    }

    /** Returns the trigger information for this input gesture */
    public Trigger getTrigger() {
        if (mInputGestureData.keycode != KeyEvent.KEYCODE_UNKNOWN) {
            return new KeyTrigger(mInputGestureData.keycode, mInputGestureData.modifierState);
        }
        throw new RuntimeException("InputGestureData is corrupted, invalid trigger type!");
    }

    /** Returns the action to perform for this input gesture */
    public Action getAction() {
        return new Action(mInputGestureData.gestureType, getAppLaunchData());
    }

    private void validate() {
        Trigger trigger = getTrigger();
        Action action = getAction();
        if (trigger == null) {
            throw new IllegalArgumentException("No trigger found");
        }
        if (action.keyGestureType == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
            throw new IllegalArgumentException("No system action found");
        }
        if (action.keyGestureType == KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION
                && action.appLaunchData == null) {
            throw new IllegalArgumentException(
                    "No app launch data for system action launch application");
        }
    }

    public AidlInputGestureData getAidlData() {
        return mInputGestureData;
    }

    @Nullable
    private AppLaunchData getAppLaunchData() {
        if (mInputGestureData.gestureType != KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION) {
            return null;
        }
        return AppLaunchData.createLaunchData(mInputGestureData.appLaunchCategory,
                mInputGestureData.appLaunchRole, mInputGestureData.appLaunchPackageName,
                mInputGestureData.appLaunchClassName);
    }

    /** Builder class for creating {@link InputGestureData} */
    public static class Builder {
        @Nullable
        private Trigger mTrigger = null;
        private int mKeyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED;
        @Nullable
        private AppLaunchData mAppLaunchData = null;

        /** Set input gesture trigger data for key based gestures */
        public Builder setTrigger(Trigger trigger) {
            mTrigger = trigger;
            return this;
        }

        /** Set input gesture system action */
        public Builder setKeyGestureType(@KeyGestureEvent.KeyGestureType int keyGestureType) {
            mKeyGestureType = keyGestureType;
            return this;
        }

        /** Set input gesture system action as launching a target app */
        public Builder setAppLaunchData(@NonNull AppLaunchData appLaunchData) {
            mKeyGestureType = KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION;
            mAppLaunchData = appLaunchData;
            return this;
        }

        /** Creates {@link android.hardware.input.InputGestureData} based on data provided */
        public InputGestureData build() throws IllegalArgumentException {
            if (mTrigger == null) {
                throw new IllegalArgumentException("No trigger found");
            }
            if (mKeyGestureType == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
                throw new IllegalArgumentException("No system action found");
            }
            if (mKeyGestureType == KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION
                    && mAppLaunchData == null) {
                throw new IllegalArgumentException(
                        "No app launch data for system action launch application");
            }
            AidlInputGestureData data = new AidlInputGestureData();
            if (mTrigger instanceof KeyTrigger keyTrigger) {
                data.keycode = keyTrigger.getKeycode();
                data.modifierState = keyTrigger.getModifierState();
            } else {
                throw new IllegalArgumentException("Invalid trigger type!");
            }
            data.gestureType = mKeyGestureType;
            if (mAppLaunchData != null) {
                if (mAppLaunchData instanceof AppLaunchData.CategoryData categoryData) {
                    data.appLaunchCategory = categoryData.getCategory();
                } else if (mAppLaunchData instanceof AppLaunchData.RoleData roleData) {
                    data.appLaunchRole = roleData.getRole();
                } else if (mAppLaunchData instanceof AppLaunchData.ComponentData componentData) {
                    data.appLaunchPackageName = componentData.getPackageName();
                    data.appLaunchClassName = componentData.getClassName();
                } else {
                    throw new IllegalArgumentException("AppLaunchData type is invalid!");
                }
            }
            return new InputGestureData(data);
        }
    }

    @Override
    public String toString() {
        return "InputGestureData { "
                + "trigger = " + getTrigger()
                + ", action = " + getAction()
                + " }";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InputGestureData that = (InputGestureData) o;
        return mInputGestureData.keycode == that.mInputGestureData.keycode
                && mInputGestureData.modifierState == that.mInputGestureData.modifierState
                && mInputGestureData.gestureType == that.mInputGestureData.gestureType
                && Objects.equals(mInputGestureData.appLaunchCategory, that.mInputGestureData.appLaunchCategory)
                && Objects.equals(mInputGestureData.appLaunchRole, that.mInputGestureData.appLaunchRole)
                && Objects.equals(mInputGestureData.appLaunchPackageName, that.mInputGestureData.appLaunchPackageName)
                && Objects.equals(mInputGestureData.appLaunchPackageName, that.mInputGestureData.appLaunchPackageName);
    }

    @Override
    public int hashCode() {
        int _hash = 1;
        _hash = 31 * _hash + mInputGestureData.keycode;
        _hash = 31 * _hash + mInputGestureData.modifierState;
        _hash = 31 * _hash + mInputGestureData.gestureType;
        _hash = 31 * _hash + (mInputGestureData.appLaunchCategory != null
                ? mInputGestureData.appLaunchCategory.hashCode() : 0);
        _hash = 31 * _hash + (mInputGestureData.appLaunchRole != null
                ? mInputGestureData.appLaunchRole.hashCode() : 0);
        _hash = 31 * _hash + (mInputGestureData.appLaunchPackageName != null
                ? mInputGestureData.appLaunchPackageName.hashCode() : 0);
        _hash = 31 * _hash + (mInputGestureData.appLaunchPackageName != null
                ? mInputGestureData.appLaunchPackageName.hashCode() : 0);
        return _hash;
    }

    public interface Trigger {
    }

    /** Creates a input gesture trigger based on a key press */
    public static Trigger createKeyTrigger(int keycode, int modifierState) {
        return new KeyTrigger(keycode, modifierState);
    }

    /** Key based input gesture trigger */
    public static class KeyTrigger implements Trigger {
        private static final int SHORTCUT_META_MASK =
                KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON
                        | KeyEvent.META_SHIFT_ON;
        private final int mKeycode;
        private final int mModifierState;

        private KeyTrigger(int keycode, int modifierState) {
            if (keycode <= KeyEvent.KEYCODE_UNKNOWN || keycode > KeyEvent.getMaxKeyCode()) {
                throw new IllegalArgumentException("Invalid keycode = " + keycode);
            }
            mKeycode = keycode;
            mModifierState = modifierState;
        }

        public int getKeycode() {
            return mKeycode;
        }

        public int getModifierState() {
            return mModifierState;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof KeyTrigger that)) return false;
            return mKeycode == that.mKeycode && mModifierState == that.mModifierState;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mKeycode, mModifierState);
        }

        @Override
        public String toString() {
            return "KeyTrigger{" +
                    "mKeycode=" + KeyEvent.keyCodeToString(mKeycode) +
                    ", mModifierState=" + mModifierState +
                    '}';
        }
    }

    /** Data for action to perform when input gesture is triggered */
    public record Action(@KeyGestureEvent.KeyGestureType int keyGestureType,
                         @Nullable AppLaunchData appLaunchData) {
    }
}
