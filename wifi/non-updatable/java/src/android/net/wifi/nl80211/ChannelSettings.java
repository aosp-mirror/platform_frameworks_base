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

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.util.Objects;

/**
 * ChannelSettings for wificond
 *
 * @hide
 */
public class ChannelSettings implements Parcelable {
    private static final String TAG = "ChannelSettings";

    public int frequency;

    /** public constructor */
    public ChannelSettings() { }

    /** override comparator */
    @Override
    public boolean equals(Object rhs) {
        if (this == rhs) return true;
        if (!(rhs instanceof ChannelSettings)) {
            return false;
        }
        ChannelSettings channel = (ChannelSettings) rhs;
        if (channel == null) {
            return false;
        }
        return frequency == channel.frequency;
    }

    /** override hash code */
    @Override
    public int hashCode() {
        return Objects.hash(frequency);
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * implement Parcelable interface
     * |flags| is ignored.
     **/
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(frequency);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<ChannelSettings> CREATOR =
            new Parcelable.Creator<ChannelSettings>() {
        /**
         * Caller is responsible for providing a valid parcel.
         */
        @Override
        public ChannelSettings createFromParcel(Parcel in) {
            ChannelSettings result = new ChannelSettings();
            result.frequency = in.readInt();
            if (in.dataAvail() != 0) {
                Log.e(TAG, "Found trailing data after parcel parsing.");
            }

            return result;
        }

        @Override
        public ChannelSettings[] newArray(int size) {
            return new ChannelSettings[size];
        }
    };
}
