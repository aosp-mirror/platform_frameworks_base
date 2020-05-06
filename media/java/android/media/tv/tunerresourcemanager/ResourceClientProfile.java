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
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * A profile of a resource client. This profile is used to register the client info
 * with the Tuner Resource Manager(TRM).
 *
 * @hide
 */
public final class ResourceClientProfile implements Parcelable {
    static final String TAG = "ResourceClientProfile";

    public static final
                @NonNull
                Parcelable.Creator<ResourceClientProfile> CREATOR =
                new Parcelable.Creator<ResourceClientProfile>() {
                @Override
                public ResourceClientProfile createFromParcel(Parcel source) {
                    try {
                        return new ResourceClientProfile(source);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception creating ResourceClientProfile from parcel", e);
                        return null;
                    }
                }

                @Override
                public ResourceClientProfile[] newArray(int size) {
                    return new ResourceClientProfile[size];
                }
            };

    /**
     * This is used by TRM to get TV App’s processId from TIF.
     * The processId will be used to identify foreground applications.
     *
     * <p>MediaCas, Tuner and TvInputHardwareManager get tvInputSessionId from TIS.
     * If mTvInputSessionId is UNKNOWN, the client is always background.
     */
    private final String mTvInputSessionId;

    /**
     * Usage of the client.
     */
    private final int mUseCase;

    private ResourceClientProfile(@NonNull Parcel source) {
        mTvInputSessionId = source.readString();
        mUseCase = source.readInt();
    }

    /**
     * Constructs a new {@link ResourceClientProfile} with the given parameters.
     *
     * @param tvInputSessionId the unique id of the session owned by the client.
     * @param useCase the usage of the client. Suggested priority hints are
     *                {@link android.media.tv.TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK}
     *                {@link android.media.tv.TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE}
     *                {@link android.media.tv.TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD}.
     *                New [use case : priority value] pair can be defined in the manifest by the
     *                OEM. The id of the useCaseVendor should be passed through this parameter. Any
     *                undefined use case would cause IllegalArgumentException.
     */
    public ResourceClientProfile(@Nullable String tvInputSessionId,
                                 int useCase) {
        mTvInputSessionId = tvInputSessionId;
        mUseCase = useCase;
    }

    /**
     * Returns the tv input session id of the client.
     *
     * @return the value of the tv input session id.
     */
    @Nullable
    public String getTvInputSessionId() {
        return mTvInputSessionId;
    }

    /**
     * Returns the user usage of the client.
     *
     * @return the value of use case.
     */
    public int getUseCase() {
        return mUseCase;
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
        b.append("ResourceClientProfile {tvInputSessionId=").append(mTvInputSessionId);
        b.append(", useCase=").append(mUseCase);
        b.append("}");
        return b.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mTvInputSessionId);
        dest.writeInt(mUseCase);
    }
}
