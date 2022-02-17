/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.KeyEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An event describing a keyboard interaction originating from a remote device.
 *
 * When the user presses a key, an {@code ACTION_DOWN} event should be reported. When the user
 * releases the key, an {@code ACTION_UP} event should be reported.
 *
 * See {@link android.view.KeyEvent}.
 *
 * @hide
 */
@SystemApi
public final class VirtualKeyEvent implements Parcelable {

    /** @hide */
    public static final int ACTION_UNKNOWN = -1;
    /** Action indicating the given key has been pressed. */
    public static final int ACTION_DOWN = KeyEvent.ACTION_DOWN;
    /** Action indicating the previously pressed key has been lifted. */
    public static final int ACTION_UP = KeyEvent.ACTION_UP;

    /** @hide */
    @IntDef(prefix = { "ACTION_" }, value = {
            ACTION_UNKNOWN,
            ACTION_DOWN,
            ACTION_UP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {
    }

    private final @Action int mAction;
    private final int mKeyCode;

    private VirtualKeyEvent(@Action int action, int keyCode) {
        mAction = action;
        mKeyCode = keyCode;
    }

    private VirtualKeyEvent(@NonNull Parcel parcel) {
        mAction = parcel.readInt();
        mKeyCode = parcel.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeInt(mAction);
        parcel.writeInt(mKeyCode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the key code associated with this event.
     */
    public int getKeyCode() {
        return mKeyCode;
    }

    /**
     * Returns the action associated with this event.
     */
    public @Action int getAction() {
        return mAction;
    }

    /**
     * Builder for {@link VirtualKeyEvent}.
     */
    public static final class Builder {

        private @Action int mAction = ACTION_UNKNOWN;
        private int mKeyCode = -1;

        /**
         * Creates a {@link VirtualKeyEvent} object with the current builder configuration.
         */
        public @NonNull VirtualKeyEvent build() {
            if (mAction == ACTION_UNKNOWN || mKeyCode == -1) {
                throw new IllegalArgumentException(
                        "Cannot build virtual key event with unset fields");
            }
            return new VirtualKeyEvent(mAction, mKeyCode);
        }

        /**
         * Sets the Android key code of the event. The set of allowed keys include digits
         *              {@link android.view.KeyEvent#KEYCODE_0} through
         *              {@link android.view.KeyEvent#KEYCODE_9}, characters
         *              {@link android.view.KeyEvent#KEYCODE_A} through
         *              {@link android.view.KeyEvent#KEYCODE_Z}, function keys
         *              {@link android.view.KeyEvent#KEYCODE_F1} through
         *              {@link android.view.KeyEvent#KEYCODE_F12}, numpad keys
         *              {@link android.view.KeyEvent#KEYCODE_NUMPAD_0} through
         *              {@link android.view.KeyEvent#KEYCODE_NUMPAD_RIGHT_PAREN},
         *              and these additional keys:
         *              {@link android.view.KeyEvent#KEYCODE_GRAVE}
         *              {@link android.view.KeyEvent#KEYCODE_MINUS}
         *              {@link android.view.KeyEvent#KEYCODE_EQUALS}
         *              {@link android.view.KeyEvent#KEYCODE_LEFT_BRACKET}
         *              {@link android.view.KeyEvent#KEYCODE_RIGHT_BRACKET}
         *              {@link android.view.KeyEvent#KEYCODE_BACKSLASH}
         *              {@link android.view.KeyEvent#KEYCODE_SEMICOLON}
         *              {@link android.view.KeyEvent#KEYCODE_APOSTROPHE}
         *              {@link android.view.KeyEvent#KEYCODE_COMMA}
         *              {@link android.view.KeyEvent#KEYCODE_PERIOD}
         *              {@link android.view.KeyEvent#KEYCODE_SLASH}
         *              {@link android.view.KeyEvent#KEYCODE_ALT_LEFT}
         *              {@link android.view.KeyEvent#KEYCODE_ALT_RIGHT}
         *              {@link android.view.KeyEvent#KEYCODE_CTRL_LEFT}
         *              {@link android.view.KeyEvent#KEYCODE_CTRL_RIGHT}
         *              {@link android.view.KeyEvent#KEYCODE_SHIFT_LEFT}
         *              {@link android.view.KeyEvent#KEYCODE_SHIFT_RIGHT}
         *              {@link android.view.KeyEvent#KEYCODE_META_LEFT}
         *              {@link android.view.KeyEvent#KEYCODE_META_RIGHT}
         *              {@link android.view.KeyEvent#KEYCODE_CAPS_LOCK}
         *              {@link android.view.KeyEvent#KEYCODE_SCROLL_LOCK}
         *              {@link android.view.KeyEvent#KEYCODE_NUM_LOCK}
         *              {@link android.view.KeyEvent#KEYCODE_ENTER}
         *              {@link android.view.KeyEvent#KEYCODE_TAB}
         *              {@link android.view.KeyEvent#KEYCODE_SPACE}
         *              {@link android.view.KeyEvent#KEYCODE_DPAD_DOWN}
         *              {@link android.view.KeyEvent#KEYCODE_DPAD_UP}
         *              {@link android.view.KeyEvent#KEYCODE_DPAD_LEFT}
         *              {@link android.view.KeyEvent#KEYCODE_DPAD_RIGHT}
         *              {@link android.view.KeyEvent#KEYCODE_MOVE_END}
         *              {@link android.view.KeyEvent#KEYCODE_MOVE_HOME}
         *              {@link android.view.KeyEvent#KEYCODE_PAGE_DOWN}
         *              {@link android.view.KeyEvent#KEYCODE_PAGE_UP}
         *              {@link android.view.KeyEvent#KEYCODE_DEL}
         *              {@link android.view.KeyEvent#KEYCODE_FORWARD_DEL}
         *              {@link android.view.KeyEvent#KEYCODE_INSERT}
         *              {@link android.view.KeyEvent#KEYCODE_ESCAPE}
         *              {@link android.view.KeyEvent#KEYCODE_BREAK}
         *              {@link android.view.KeyEvent#KEYCODE_BACK}
         *              {@link android.view.KeyEvent#KEYCODE_FORWARD}
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setKeyCode(int keyCode) {
            mKeyCode = keyCode;
            return this;
        }

        /**
         * Sets the action of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setAction(@Action int action) {
            if (action != ACTION_DOWN && action != ACTION_UP) {
                throw new IllegalArgumentException("Unsupported action type");
            }
            mAction = action;
            return this;
        }
    }

    public static final @NonNull Parcelable.Creator<VirtualKeyEvent> CREATOR =
            new Parcelable.Creator<VirtualKeyEvent>() {
        public VirtualKeyEvent createFromParcel(Parcel source) {
            return new VirtualKeyEvent(source);
        }

        public VirtualKeyEvent[] newArray(int size) {
            return new VirtualKeyEvent[size];
        }
    };
}
