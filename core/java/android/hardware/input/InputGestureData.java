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

    public static final int TOUCHPAD_GESTURE_TYPE_UNKNOWN = 0;
    public static final int TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP = 1;

    @NonNull
    private final AidlInputGestureData mInputGestureData;

    public InputGestureData(@NonNull AidlInputGestureData inputGestureData) {
        this.mInputGestureData = inputGestureData;
        validate();
    }

    /** Returns the trigger information for this input gesture */
    public Trigger getTrigger() {
        return createTriggerFromAidlTrigger(mInputGestureData.trigger);
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
            data.trigger = mTrigger.getAidlTrigger();
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
        return Objects.equals(mInputGestureData, that.mInputGestureData);
    }

    @Override
    public int hashCode() {
        return mInputGestureData.hashCode();
    }

    public interface Trigger {
        AidlInputGestureData.Trigger getAidlTrigger();
    }

    /** Creates a input gesture trigger based on a key press */
    public static Trigger createKeyTrigger(int keycode, int modifierState) {
        return new KeyTrigger(keycode, modifierState);
    }

    /** Creates a input gesture trigger based on a touchpad gesture */
    public static Trigger createTouchpadTrigger(int touchpadGestureType) {
        return new TouchpadTrigger(touchpadGestureType);
    }

    public static Trigger createTriggerFromAidlTrigger(AidlInputGestureData.Trigger aidlTrigger) {
        switch (aidlTrigger.getTag()) {
            case AidlInputGestureData.Trigger.Tag.key: {
                AidlInputGestureData.KeyTrigger trigger = aidlTrigger.getKey();
                if (trigger == null) {
                    throw new RuntimeException("aidlTrigger is corrupted, null key trigger!");
                }
                return new KeyTrigger(trigger);
            }
            case AidlInputGestureData.Trigger.Tag.touchpadGesture: {
                AidlInputGestureData.TouchpadGestureTrigger trigger =
                        aidlTrigger.getTouchpadGesture();
                if (trigger == null) {
                    throw new RuntimeException(
                            "aidlTrigger is corrupted, null touchpad trigger!");
                }
                return new TouchpadTrigger(trigger);
            }
            default:
                throw new RuntimeException("aidlTrigger is corrupted, invalid trigger type!");

        }
    }

    /** Key based input gesture trigger */
    public static class KeyTrigger implements Trigger {

        AidlInputGestureData.KeyTrigger mAidlKeyTrigger;

        private KeyTrigger(@NonNull AidlInputGestureData.KeyTrigger aidlKeyTrigger) {
            mAidlKeyTrigger = aidlKeyTrigger;
        }

        private KeyTrigger(int keycode, int modifierState) {
            if (keycode <= KeyEvent.KEYCODE_UNKNOWN || keycode > KeyEvent.getMaxKeyCode()) {
                throw new IllegalArgumentException("Invalid keycode = " + keycode);
            }
            mAidlKeyTrigger = new AidlInputGestureData.KeyTrigger();
            mAidlKeyTrigger.keycode = keycode;
            mAidlKeyTrigger.modifierState = modifierState;
        }

        public int getKeycode() {
            return mAidlKeyTrigger.keycode;
        }

        public int getModifierState() {
            return mAidlKeyTrigger.modifierState;
        }

        public AidlInputGestureData.Trigger getAidlTrigger() {
            AidlInputGestureData.Trigger trigger = new AidlInputGestureData.Trigger();
            trigger.setKey(mAidlKeyTrigger);
            return trigger;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof KeyTrigger that)) return false;
            return Objects.equals(mAidlKeyTrigger, that.mAidlKeyTrigger);
        }

        @Override
        public int hashCode() {
            return mAidlKeyTrigger.hashCode();
        }

        @Override
        public String toString() {
            return "KeyTrigger{" +
                    "mKeycode=" + KeyEvent.keyCodeToString(mAidlKeyTrigger.keycode) +
                    ", mModifierState=" + mAidlKeyTrigger.modifierState +
                    '}';
        }
    }

    /** Touchpad based input gesture trigger */
    public static class TouchpadTrigger implements Trigger {
        AidlInputGestureData.TouchpadGestureTrigger mAidlTouchpadTrigger;

        private TouchpadTrigger(
                @NonNull AidlInputGestureData.TouchpadGestureTrigger aidlTouchpadTrigger) {
            mAidlTouchpadTrigger = aidlTouchpadTrigger;
        }

        private TouchpadTrigger(int touchpadGestureType) {
            if (touchpadGestureType != TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP) {
                throw new IllegalArgumentException(
                        "Invalid touchpadGestureType = " + touchpadGestureType);
            }
            mAidlTouchpadTrigger = new AidlInputGestureData.TouchpadGestureTrigger();
            mAidlTouchpadTrigger.gestureType = touchpadGestureType;
        }

        public int getTouchpadGestureType() {
            return mAidlTouchpadTrigger.gestureType;
        }

        public AidlInputGestureData.Trigger getAidlTrigger() {
            AidlInputGestureData.Trigger trigger = new AidlInputGestureData.Trigger();
            trigger.setTouchpadGesture(mAidlTouchpadTrigger);
            return trigger;
        }

        @Override
        public String toString() {
            return "TouchpadTrigger{" +
                    "mTouchpadGestureType=" + mAidlTouchpadTrigger.gestureType +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TouchpadTrigger that)) return false;
            return Objects.equals(mAidlTouchpadTrigger, that.mAidlTouchpadTrigger);
        }

        @Override
        public int hashCode() {
            return mAidlTouchpadTrigger.hashCode();
        }
    }

    /** Data for action to perform when input gesture is triggered */
    public record Action(@KeyGestureEvent.KeyGestureType int keyGestureType,
                         @Nullable AppLaunchData appLaunchData) {
    }

    /** Filter definition for InputGestureData */
    public enum Filter {
        KEY(AidlInputGestureData.Trigger.Tag.key),
        TOUCHPAD(AidlInputGestureData.Trigger.Tag.touchpadGesture);

        @AidlInputGestureData.Trigger.Tag
        private final int mTag;

        Filter(@AidlInputGestureData.Trigger.Tag int tag) {
            mTag = tag;
        }

        @Nullable
        public static Filter of(@AidlInputGestureData.Trigger.Tag int tag) {
            return switch (tag) {
                case AidlInputGestureData.Trigger.Tag.key -> KEY;
                case AidlInputGestureData.Trigger.Tag.touchpadGesture -> TOUCHPAD;
                default -> null;
            };
        }

        @AidlInputGestureData.Trigger.Tag
        public int getTag() {
            return mTag;
        }

        public boolean matches(@NonNull InputGestureData inputGestureData) {
            return mTag == inputGestureData.mInputGestureData.trigger.getTag();
        }
    }
}
