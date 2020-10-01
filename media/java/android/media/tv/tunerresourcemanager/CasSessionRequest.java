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
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Information required to request a Cas Session.
 *
 * @hide
 */
public final class CasSessionRequest implements Parcelable {
    static final String TAG = "CasSessionRequest";

    public static final
                @NonNull
                Parcelable.Creator<CasSessionRequest> CREATOR =
                new Parcelable.Creator<CasSessionRequest>() {
                @Override
                public CasSessionRequest createFromParcel(Parcel source) {
                    try {
                        return new CasSessionRequest(source);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception creating CasSessionRequest from parcel", e);
                        return null;
                    }
                }

                @Override
                public CasSessionRequest[] newArray(int size) {
                    return new CasSessionRequest[size];
                }
            };

    /**
     * Client id of the client that sends the request.
     */
    private final int mClientId;

    /**
     * System id of the requested cas.
     */
    private final int mCasSystemId;

    private CasSessionRequest(@NonNull Parcel source) {
        mClientId = source.readInt();
        mCasSystemId = source.readInt();
    }

    /**
     * Constructs a new {@link CasSessionRequest} with the given parameters.
     *
     * @param clientId id of the client.
     * @param casSystemId the cas system id that the client is requesting.
     */
    public CasSessionRequest(int clientId,
                             int casSystemId) {
        mClientId = clientId;
        mCasSystemId = casSystemId;
    }

    /**
     * Returns the id of the client.
     */
    public int getClientId() {
        return mClientId;
    }

    /**
     * Returns the cas system id requested.
     */
    public int getCasSystemId() {
        return mCasSystemId;
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
        b.append("CasSessionRequest {clientId=").append(mClientId);
        b.append(", casSystemId=").append(mCasSystemId);
        b.append("}");
        return b.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mClientId);
        dest.writeInt(mCasSystemId);
    }
}
