/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data object of params for Keyguard related {@link WindowContainerTransaction} operation.
 *
 * @hide
 */
public final class KeyguardState implements Parcelable {

    private final boolean mKeyguardShowing;

    private final boolean mAodShowing;


    private KeyguardState(boolean keyguardShowing, boolean aodShowing) {
        mKeyguardShowing = keyguardShowing;
        mAodShowing = aodShowing;
    }

    private KeyguardState(Parcel in) {
        mKeyguardShowing = in.readBoolean();
        mAodShowing = in.readBoolean();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mKeyguardShowing);
        dest.writeBoolean(mAodShowing);
    }

    @NonNull
    public static final Creator<KeyguardState> CREATOR =
            new Creator<KeyguardState>() {
                @Override
                public KeyguardState createFromParcel(Parcel in) {
                    return new KeyguardState(in);
                }

                @Override
                public KeyguardState[] newArray(int size) {
                    return new KeyguardState[size];
                }
            };

    /** Returns the keyguard showing value. */
    public boolean getKeyguardShowing() {
        return mKeyguardShowing;
    }

    /** Returns the aod showing value. */
    public boolean getAodShowing() {
        return mAodShowing;
    }

    @Override
    public String toString() {
        return "KeyguardState{ keyguardShowing=" + mKeyguardShowing
                + ", aodShowing=" + mAodShowing
                + '}';
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKeyguardShowing, mAodShowing);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof KeyguardState other)) {
            return false;
        }
        return mKeyguardShowing == other.mKeyguardShowing
                && mAodShowing == other.mAodShowing;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Builder to construct the {@link KeyguardState}. */
    public static final class Builder {
        private boolean mKeyguardShowing;

        private boolean mAodShowing;

        public Builder() {
        }

        /**
         * Sets the boolean value for this operation.
         */
        @NonNull
        public Builder setKeyguardShowing(boolean keyguardShowing) {
            mKeyguardShowing = keyguardShowing;
            return this;
        }

        /**
         * Sets the boolean value for this operation.
         */
        @NonNull
        public Builder setAodShowing(boolean aodShowing) {
            mAodShowing = aodShowing;
            return this;
        }

        /**
         * Constructs the {@link KeyguardState}.
         */
        @NonNull
        public KeyguardState build() {
            return new KeyguardState(mKeyguardShowing, mAodShowing);
        }
    }
}
