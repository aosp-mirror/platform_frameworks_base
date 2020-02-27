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
 * Information required to request a Tuner Frontend.
 *
 * @hide
 */
public final class TunerFrontendRequest implements Parcelable {
    static final String TAG = "TunerFrontendRequest";

    public static final
            @NonNull
            Parcelable.Creator<TunerFrontendRequest> CREATOR =
            new Parcelable.Creator<TunerFrontendRequest>() {
                @Override
                public TunerFrontendRequest createFromParcel(Parcel source) {
                    try {
                        return new TunerFrontendRequest(source);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception creating TunerFrontendRequest from parcel", e);
                        return null;
                    }
                }

                @Override
                public TunerFrontendRequest[] newArray(int size) {
                    return new TunerFrontendRequest[size];
                }
            };

    private final int mClientId;
    @Type
    private final int mFrontendType;

    private TunerFrontendRequest(@NonNull Parcel source) {
        mClientId = source.readInt();
        mFrontendType = source.readInt();
    }

    /**
     * Constructs a new {@link TunerFrontendRequest} with the given parameters.
     *
     * @param clientId the unique id of the client returned when registering profile.
     * @param frontendType the type of the requested frontend.
     */
    public TunerFrontendRequest(int clientId,
                                @Type int frontendType) {
        mClientId = clientId;
        mFrontendType = frontendType;
    }

    /**
     * Returns the client id that requests the tuner frontend resource.
     *
     * @return the value of the client id.
     */
    public int getClientId() {
        return mClientId;
    }

    /**
     * Returns the frontend type that the client requests for.
     *
     * @return the value of the requested frontend type.
     */
    @Type
    public int getFrontendType() {
        return mFrontendType;
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
        b.append("TunerFrontendRequest {clientId=").append(mClientId);
        b.append(", frontendType=").append(mFrontendType);
        b.append("}");
        return b.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mClientId);
        dest.writeInt(mFrontendType);
    }
}
