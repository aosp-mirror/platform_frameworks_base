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
import android.os.SystemClock;
import android.view.InputEvent;
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

    /**
     * The set of allowed keycodes.
     * @hide
     */
    @IntDef(prefix = { "KEYCODE_" }, value = {
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_4,
            KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6,
            KeyEvent.KEYCODE_7,
            KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_B,
            KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_G,
            KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_I,
            KeyEvent.KEYCODE_J,
            KeyEvent.KEYCODE_K,
            KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_M,
            KeyEvent.KEYCODE_N,
            KeyEvent.KEYCODE_O,
            KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_Q,
            KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_T,
            KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_V,
            KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_Y,
            KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_F2,
            KeyEvent.KEYCODE_F3,
            KeyEvent.KEYCODE_F4,
            KeyEvent.KEYCODE_F5,
            KeyEvent.KEYCODE_F6,
            KeyEvent.KEYCODE_F7,
            KeyEvent.KEYCODE_F8,
            KeyEvent.KEYCODE_F9,
            KeyEvent.KEYCODE_F10,
            KeyEvent.KEYCODE_F11,
            KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_NUMPAD_0,
            KeyEvent.KEYCODE_NUMPAD_1,
            KeyEvent.KEYCODE_NUMPAD_2,
            KeyEvent.KEYCODE_NUMPAD_3,
            KeyEvent.KEYCODE_NUMPAD_4,
            KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_NUMPAD_6,
            KeyEvent.KEYCODE_NUMPAD_7,
            KeyEvent.KEYCODE_NUMPAD_8,
            KeyEvent.KEYCODE_NUMPAD_9,
            KeyEvent.KEYCODE_NUMPAD_DIVIDE,
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
            KeyEvent.KEYCODE_NUMPAD_ADD,
            KeyEvent.KEYCODE_NUMPAD_DOT,
            KeyEvent.KEYCODE_NUMPAD_COMMA,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_NUMPAD_EQUALS,
            KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN,
            KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN,
            KeyEvent.KEYCODE_GRAVE,
            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_EQUALS,
            KeyEvent.KEYCODE_LEFT_BRACKET,
            KeyEvent.KEYCODE_RIGHT_BRACKET,
            KeyEvent.KEYCODE_BACKSLASH,
            KeyEvent.KEYCODE_SEMICOLON,
            KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_COMMA,
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_SLASH,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.KEYCODE_SCROLL_LOCK,
            KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_INSERT,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BREAK,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_FORWARD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SupportedKeycode {
    }

    private final @Action int mAction;
    private final int mKeyCode;
    private final long mEventTimeNanos;

    private VirtualKeyEvent(@Action int action, int keyCode, long eventTimeNanos) {
        mAction = action;
        mKeyCode = keyCode;
        mEventTimeNanos = eventTimeNanos;
    }

    private VirtualKeyEvent(@NonNull Parcel parcel) {
        mAction = parcel.readInt();
        mKeyCode = parcel.readInt();
        mEventTimeNanos = parcel.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeInt(mAction);
        parcel.writeInt(mKeyCode);
        parcel.writeLong(mEventTimeNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "VirtualKeyEvent("
                + " action=" + KeyEvent.actionToString(mAction)
                + " keyCode=" + KeyEvent.keyCodeToString(mKeyCode)
                + " eventTime(ns)=" + mEventTimeNanos;
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
     * Returns the time this event occurred, in the {@link SystemClock#uptimeMillis()} time base but
     * with nanosecond (instead of millisecond) precision.
     *
     * @see InputEvent#getEventTime()
     */
    public long getEventTimeNanos() {
        return mEventTimeNanos;
    }

    /**
     * Builder for {@link VirtualKeyEvent}.
     */
    public static final class Builder {

        private @Action int mAction = ACTION_UNKNOWN;
        private int mKeyCode = -1;
        private long mEventTimeNanos = 0L;

        /**
         * Creates a {@link VirtualKeyEvent} object with the current builder configuration.
         */
        public @NonNull VirtualKeyEvent build() {
            if (mAction == ACTION_UNKNOWN || mKeyCode == -1) {
                throw new IllegalArgumentException(
                        "Cannot build virtual key event with unset fields");
            }
            return new VirtualKeyEvent(mAction, mKeyCode, mEventTimeNanos);
        }

        /**
         * Sets the Android key code of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setKeyCode(@SupportedKeycode int keyCode) {
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

        /**
         * Sets the time (in nanoseconds) when this specific event was generated. This may be
         * obtained from {@link SystemClock#uptimeMillis()} (with nanosecond precision instead of
         * millisecond), but can be different depending on the use case.
         * This field is optional and can be omitted.
         *
         * @return this builder, to allow for chaining of calls
         * @see InputEvent#getEventTime()
         */
        public @NonNull Builder setEventTimeNanos(long eventTimeNanos) {
            if (eventTimeNanos < 0L) {
                throw new IllegalArgumentException("Event time cannot be negative");
            }
            mEventTimeNanos = eventTimeNanos;
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
