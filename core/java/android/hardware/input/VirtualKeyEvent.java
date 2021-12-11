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
         * Sets the Android key code of the event. The set of allowed characters include digits 0-9,
         * characters A-Z, and standard punctuation, as well as numpad keys, function keys F1-F12,
         * and meta keys (caps lock, shift, etc.).
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
