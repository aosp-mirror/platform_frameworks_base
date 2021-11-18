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

package android.companion.virtual;

import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

/**
 * Params that can be configured when creating virtual devices.
 *
 * @hide
 */
// TODO(b/194949534): Unhide this API
public final class VirtualDeviceParams implements Parcelable {

    /** @hide */
    @IntDef(prefix = "LOCK_STATE_",
            value = {LOCK_STATE_ALWAYS_LOCKED, LOCK_STATE_ALWAYS_UNLOCKED})
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    public @interface LockState {}

    /**
     * Indicates that the lock state of the virtual device should be always locked.
     *
     * @hide  // TODO(b/194949534): Unhide this API
     */
    public static final int LOCK_STATE_ALWAYS_LOCKED = 0;

    /**
     * Indicates that the lock state of the virtual device should be always unlocked.
     *
     * @hide  // TODO(b/194949534): Unhide this API
     */
    public static final int LOCK_STATE_ALWAYS_UNLOCKED = 1;

    private final int mLockState;

    private VirtualDeviceParams(@LockState int lockState) {
        mLockState = lockState;
    }

    private VirtualDeviceParams(Parcel parcel) {
        mLockState = parcel.readInt();
    }

    @LockState
    public int getLockState() {
        return mLockState;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mLockState);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VirtualDeviceParams)) {
            return false;
        }
        VirtualDeviceParams that = (VirtualDeviceParams) o;
        return mLockState == that.mLockState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mLockState);
    }

    @Override
    public String toString() {
        return "VirtualDeviceParams("
                + " mLockState=" + mLockState
                + ")";
    }

    public static final Parcelable.Creator<VirtualDeviceParams> CREATOR =
            new Parcelable.Creator<VirtualDeviceParams>() {
                public VirtualDeviceParams createFromParcel(Parcel in) {
                    return new VirtualDeviceParams(in);
                }

                public VirtualDeviceParams[] newArray(int size) {
                    return new VirtualDeviceParams[size];
                }
            };

    /**
     * Builder for {@link VirtualDeviceParams}.
     */
    public static final class Builder {

        private @LockState int mLockState = LOCK_STATE_ALWAYS_LOCKED;

        /**
         * Sets the lock state of the device. The permission {@code ADD_ALWAYS_UNLOCKED_DISPLAY}
         * is required if this is set to {@link #LOCK_STATE_ALWAYS_UNLOCKED}.
         * The default is {@link #LOCK_STATE_ALWAYS_LOCKED}.
         *
         * @param lockState The lock state, either {@link #LOCK_STATE_ALWAYS_LOCKED} or
         *   {@link #LOCK_STATE_ALWAYS_UNLOCKED}.
         */
        @RequiresPermission(value = ADD_ALWAYS_UNLOCKED_DISPLAY, conditional = true)
        @NonNull
        public Builder setLockState(@LockState int lockState) {
            mLockState = lockState;
            return this;
        }

        /**
         * Builds the {@link VirtualDeviceParams} instance.
         */
        @NonNull
        public VirtualDeviceParams build() {
            return new VirtualDeviceParams(mLockState);
        }
    }
}
