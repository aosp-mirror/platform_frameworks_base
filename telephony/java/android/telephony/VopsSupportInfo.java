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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.AccessNetworkConstants.AccessNetworkType;

/**
 * Abstract base class for the information related to network VoPS support.
 * This is the base class for XxVopsSupportInfo which represent VoPS support
 * information for specific network access techonology.
 * @hide
 */
@SuppressLint("ParcelNotFinal")
@SystemApi
public abstract class VopsSupportInfo implements Parcelable {

    /**
     * @hide
     */
    public VopsSupportInfo() {}

    /**
     * Returns whether VoPS is supported by the network
     */
    public abstract boolean isVopsSupported();

    /**
     * Returns whether emergency service is supported by the network
     */
    public abstract boolean isEmergencyServiceSupported();

    /**
     * Returns whether emergency service fallback is supported by the network
     */
    public abstract boolean isEmergencyServiceFallbackSupported();

    /**
     * Implement the Parcelable interface
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public abstract void writeToParcel(@NonNull Parcel dest, int flags);

    /**
     * Used by child classes for parceling.
     *
     * @hide
     */
    protected void writeToParcel(@NonNull Parcel dest, int flags, int type) {
        dest.writeInt(type);
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Creator<VopsSupportInfo> CREATOR =
            new Creator<VopsSupportInfo>() {
        @Override
        public VopsSupportInfo createFromParcel(Parcel in) {
                int type = in.readInt();
                switch (type) {
                    case AccessNetworkType.EUTRAN:
                        return LteVopsSupportInfo.createFromParcelBody(in);
                    case AccessNetworkType.NGRAN:
                        return NrVopsSupportInfo.createFromParcelBody(in);
                    default: throw new RuntimeException("Bad VopsSupportInfo Parcel");
                }
        }

        @Override
        public VopsSupportInfo[] newArray(int size) {
            return new VopsSupportInfo[size];
        }
    };

    @Override
    public abstract int hashCode();

    @Override
    public abstract boolean equals(Object o);
}
