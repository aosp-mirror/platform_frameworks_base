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

package android.net.wifi.wificond;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * RadioChainInfo for wificond
 *
 * @hide
 */
public class RadioChainInfo implements Parcelable {
    private static final String TAG = "RadioChainInfo";

    public int chainId;
    public int level;


    /** public constructor */
    public RadioChainInfo() { }

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
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(chainId);
        out.writeInt(level);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<RadioChainInfo> CREATOR =
            new Parcelable.Creator<RadioChainInfo>() {
        /**
         * Caller is responsible for providing a valid parcel.
         */
        @Override
        public RadioChainInfo createFromParcel(Parcel in) {
            RadioChainInfo result = new RadioChainInfo();
            result.chainId = in.readInt();
            result.level = in.readInt();
            return result;
        }

        @Override
        public RadioChainInfo[] newArray(int size) {
            return new RadioChainInfo[size];
        }
    };
}
