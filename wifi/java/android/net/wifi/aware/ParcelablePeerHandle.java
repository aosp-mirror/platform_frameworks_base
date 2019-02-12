/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi.aware;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A Parcelable {@link PeerHandle}. Can be constructed from a {@code PeerHandle} and then passed
 * to any of the APIs which take a {@code PeerHandle} as inputs.
 */
public final class ParcelablePeerHandle extends PeerHandle implements Parcelable {
    /**
     * Construct a parcelable version of {@link PeerHandle}.
     *
     * @param peerHandle The {@link PeerHandle} to be made parcelable.
     */
    public ParcelablePeerHandle(PeerHandle peerHandle) {
        super(peerHandle.peerId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(peerId);
    }

    public static final Creator<ParcelablePeerHandle> CREATOR =
            new Creator<ParcelablePeerHandle>() {
                @Override
                public ParcelablePeerHandle[] newArray(int size) {
                    return new ParcelablePeerHandle[size];
                }

                @Override
                public ParcelablePeerHandle createFromParcel(Parcel in) {
                    int peerHandle = in.readInt();
                    return new ParcelablePeerHandle(new PeerHandle(peerHandle));
                }
            };
}
