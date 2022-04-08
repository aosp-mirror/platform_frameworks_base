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
 * Information required to request a Tuner Descrambler.
 *
 * @hide
 */
public final class TunerDescramblerRequest implements Parcelable {
    static final String TAG = "TunerDescramblerRequest";

    public static final
                @NonNull
                Parcelable.Creator<TunerDescramblerRequest> CREATOR =
                new Parcelable.Creator<TunerDescramblerRequest>() {
                @Override
                public TunerDescramblerRequest createFromParcel(Parcel source) {
                    try {
                        return new TunerDescramblerRequest(source);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception creating TunerDescramblerRequest from parcel", e);
                        return null;
                    }
                }

                @Override
                public TunerDescramblerRequest[] newArray(int size) {
                    return new TunerDescramblerRequest[size];
                }
            };

    /**
     * Client id of the client that sends the request.
     */
    private final int mClientId;

    private TunerDescramblerRequest(@NonNull Parcel source) {
        mClientId = source.readInt();
    }

    /**
     * Constructs a new {@link TunerDescramblerRequest} with the given parameters.
     *
     * @param clientId id of the client.
     */
    public TunerDescramblerRequest(int clientId) {
        mClientId = clientId;
    }

    /**
     * Returns the id of the client.
     */
    public int getClientId() {
        return mClientId;
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
        b.append("TunerDescramblerRequest {clientId=").append(mClientId);
        b.append("}");
        return b.toString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mClientId);
    }
}
