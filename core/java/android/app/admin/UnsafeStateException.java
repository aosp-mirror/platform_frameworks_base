/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.app.admin;

import static android.app.admin.DevicePolicyManager.UNSAFE_OPERATION_REASON_DRIVING_DISTRACTION;
import static android.app.admin.DevicePolicyManager.unsafeOperationReasonToString;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.app.admin.DevicePolicyManager.DevicePolicyOperation;
import android.app.admin.DevicePolicyManager.UnsafeOperationReason;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Exception thrown when a {@link android.app.admin.DevicePolicyManager} operation failed because it
 * was not safe to be executed at that moment.
 *
 * <p>For example, it can be thrown on
 * {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE automotive devices} when the vehicle
 * is moving.
 */
@SuppressWarnings("serial")
public final class UnsafeStateException extends IllegalStateException implements Parcelable {

    private final @DevicePolicyOperation int mOperation;
    private final @UnsafeOperationReason int mReason;

    /** @hide */
    @TestApi
    public UnsafeStateException(@DevicePolicyOperation int operation,
            @UnsafeOperationReason int reason) {
        super();
        Preconditions.checkArgument(reason == UNSAFE_OPERATION_REASON_DRIVING_DISTRACTION,
                "invalid reason %d, must be %d (%s)", reason,
                UNSAFE_OPERATION_REASON_DRIVING_DISTRACTION,
                unsafeOperationReasonToString(UNSAFE_OPERATION_REASON_DRIVING_DISTRACTION));
        mOperation = operation;
        mReason = reason;
    }

    /** @hide */
    @TestApi
    public @DevicePolicyOperation int getOperation() {
        return mOperation;
    }

    /**
     * Gets the reason the operation is unsafe.
     *
     * @return currently, only valid reason is
     * {@link android.app.admin.DevicePolicyManager#UNSAFE_OPERATION_REASON_DRIVING_DISTRACTION}.
     */
    public @UnsafeOperationReason int getReason() {
        return mReason;
    }

    /** @hide */
    @Override
    public String getMessage() {
        return DevicePolicyManager.unsafeOperationReasonToString(mReason);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mOperation);
        dest.writeInt(mReason);
    }

    @NonNull
    public static final Creator<UnsafeStateException> CREATOR =
            new Creator<UnsafeStateException>() {

        @Override
        public UnsafeStateException createFromParcel(Parcel source) {
            return new UnsafeStateException(source.readInt(), source.readInt());
        }

        @Override
        public UnsafeStateException[] newArray(int size) {
            return new UnsafeStateException[size];
        }
    };
}
