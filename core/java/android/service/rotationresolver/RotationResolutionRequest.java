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

package android.service.rotationresolver;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Surface;

/**
 * This class represents a request to an {@link RotationResolverService}. The request contains
 * information from the system that can help RotationResolverService to determine the appropriate
 * screen rotation.
 *
 * @see RotationResolverService#resolveRotation(IRotationResolverCallback,
 * RotationResolutionRequest, ICancellationSignal)
 *
 * @hide
 */
@SystemApi
public final class RotationResolutionRequest implements Parcelable {
    private final @NonNull String mPackageName;
    private final int mProposedRotation;
    private final int mCurrentRotation;
    private final long mTimeoutMillis;

    /**
     * @param proposedRotation The system proposed screen rotation.
     * @param currentRotation  The current screen rotation of the phone.
     * @param packageName The current package name of the activity that is running in
     *                    foreground.
     * @param timeoutMillis    The timeout in millisecond for the rotation request.
     * @hide
     */
    public RotationResolutionRequest(int proposedRotation, int currentRotation,
            @NonNull String packageName, long timeoutMillis) {
        mProposedRotation = proposedRotation;
        mCurrentRotation = currentRotation;
        mPackageName = packageName;
        mTimeoutMillis = timeoutMillis;
    }

    @Surface.Rotation public int getProposedRotation() {
        return mProposedRotation;
    }

    public int getCurrentRotation() {
        return mCurrentRotation;
    }

    public @NonNull String getPackageName() {
        return mPackageName;
    }

    public long getTimeoutMillis() {
        return mTimeoutMillis;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mProposedRotation);
        parcel.writeInt(mCurrentRotation);
        parcel.writeString(mPackageName);
        parcel.writeLong(mTimeoutMillis);
    }

    public static final @NonNull Creator<RotationResolutionRequest> CREATOR =
            new Creator<RotationResolutionRequest>() {
        @Override
        public RotationResolutionRequest createFromParcel(Parcel source) {
            return new RotationResolutionRequest(source.readInt(), source.readInt(),
                    source.readString(), source.readLong());
        }

        @Override
        public RotationResolutionRequest[] newArray(int size) {
            return new RotationResolutionRequest[size];
        }
    };
}
