/*
 * Copyright 2020 The Android Open Source Project
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

package android.media.tv.tunerresourcemanager;

import android.annotation.NonNull;
import android.media.tv.tuner.frontend.FrontendSettings.Type;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Simple container of the FrontendInfo struct defined in the TunerHAL 1.0 interface.
 *
 * <p>Note that this object is defined to pass necessary frontend info between the
 * Tuner Resource Manager and the client. It includes partial information in
 * {@link FrontendInfo}.
 *
 * @hide
 */
public final class TunerFrontendInfo implements Parcelable {
    static final String TAG = "TunerFrontendInfo";

    public static final
            @NonNull
            Parcelable.Creator<TunerFrontendInfo> CREATOR =
            new Parcelable.Creator<TunerFrontendInfo>() {
                @Override
                public TunerFrontendInfo createFromParcel(Parcel source) {
                    try {
                        return new TunerFrontendInfo(source);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception creating TunerFrontendInfo from parcel", e);
                        return null;
                    }
                }

                @Override
                public TunerFrontendInfo[] newArray(int size) {
                    return new TunerFrontendInfo[size];
                }
            };

    private final int mId;

    @Type
    private final int mFrontendType;

    /**
     * Frontends are assigned with the same exclusiveGroupId if they can't
     * function at same time. For instance, they share same hardware module.
     */
    private final int mExclusiveGroupId;

    private TunerFrontendInfo(@NonNull Parcel source) {
        mId = source.readInt();
        mFrontendType = source.readInt();
        mExclusiveGroupId = source.readInt();
    }

    /**
     * Constructs a new {@link TunerFrontendInfo} with the given parameters.
     *
     * @param frontendType the type of the frontend.
     * @param exclusiveGroupId the group id of the frontend. FE with the same
                               group id can't function at the same time.
     */
    public TunerFrontendInfo(int id,
                             @Type int frontendType,
                             int exclusiveGroupId) {
        mId = id;
        mFrontendType = frontendType;
        mExclusiveGroupId = exclusiveGroupId;
    }

    /**
     * Returns the frontend id.
     *
     * @return the value of the frontend id.
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the application id that requests the tuner frontend resource.
     *
     * @return the value of the frontend type.
     */
    @Type
    public int getFrontendType() {
        return mFrontendType;
    }

    /**
     * Returns the exclusiveGroupId. Frontends with the same exclusiveGroupId
     * can't function at same time.
     *
     * @return the value of the exclusive group id.
     */
    public int getExclusiveGroupId() {
        return mExclusiveGroupId;
    }

    // Parcelable
    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder b = new StringBuilder(128);
        b.append("TunerFrontendInfo {id=").append(mId);
        b.append(", frontendType=").append(mFrontendType);
        b.append(", exclusiveGroupId=").append(mExclusiveGroupId);
        b.append("}");
        return b.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mFrontendType);
        dest.writeInt(mExclusiveGroupId);
    }
}
