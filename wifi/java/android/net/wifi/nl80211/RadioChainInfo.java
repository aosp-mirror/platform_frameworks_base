/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.nl80211;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * A class representing the radio chains of the Wi-Fi modems. Use to provide raw information about
 * signals received on different radio chains.
 *
 * @hide
 */
@SystemApi
public final class RadioChainInfo implements Parcelable {
    private static final String TAG = "RadioChainInfo";

    /** @hide */
    @VisibleForTesting
    public int chainId;
    /** @hide */
    @VisibleForTesting
    public int level;

    /**
     * Return an identifier for this radio chain. This is an arbitrary ID which is consistent for
     * the same device.
     *
     * @return The radio chain ID.
     */
    public int getChainId() {
        return chainId;
    }

    /**
     * Returns the detected signal level on this radio chain in dBm (aka RSSI).
     *
     * @return A signal level in dBm.
     */
    public int getLevelDbm() {
        return level;
    }

    /**
     * Construct a RadioChainInfo.
     */
    public RadioChainInfo(int chainId, int level) {
        this.chainId = chainId;
        this.level = level;
    }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof RadioChainInfo)) {
            return false;
        }
        RadioChainInfo chainInfo = (RadioChainInfo) rhs;
        if (chainInfo == null) {
            return false;
        }
        return chainId == chainInfo.chainId && level == chainInfo.level;
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Objects.hash(chainId, level);
    }


    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * implement Parcelable interface
     * |flags| is ignored.
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(chainId);
        out.writeInt(level);
    }

    /** implement Parcelable interface */
    @NonNull public static final Parcelable.Creator<RadioChainInfo> CREATOR =
            new Parcelable.Creator<RadioChainInfo>() {
        /**
         * Caller is responsible for providing a valid parcel.
         */
        @Override
        public RadioChainInfo createFromParcel(Parcel in) {
            return new RadioChainInfo(in.readInt(), in.readInt());
        }

        @Override
        public RadioChainInfo[] newArray(int size) {
            return new RadioChainInfo[size];
        }
    };
}
