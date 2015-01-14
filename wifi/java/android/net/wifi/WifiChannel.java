/*
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Wifi Channel
 *
 * @see ScanSettings
 *
 * @hide
 */
public class WifiChannel implements Parcelable {

    private static final int MIN_FREQ_MHZ = 2412;
    private static final int MAX_FREQ_MHZ = 5825;

    private static final int MIN_CHANNEL_NUM = 1;
    private static final int MAX_CHANNEL_NUM = 196;

    /** frequency */
    public int freqMHz;

    /** channel number */
    public int channelNum;

    /** is it a DFS channel? */
    public boolean isDFS;

    /** is IBSS allowed? */
    public boolean ibssAllowed;

    /** public constructor */
    public WifiChannel() { }

    /** check for validity */
    public boolean isValid() {
        if (freqMHz < MIN_FREQ_MHZ || freqMHz > MAX_FREQ_MHZ) return false;
        if (channelNum < MIN_CHANNEL_NUM || channelNum > MAX_CHANNEL_NUM) return false;
        return true;
    }

    /** implement Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** implement Parcelable interface */
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(freqMHz);
        out.writeInt(channelNum);
        out.writeInt(isDFS ? 1 : 0);
        out.writeInt(ibssAllowed ? 1 : 0);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<WifiChannel> CREATOR =
            new Parcelable.Creator<WifiChannel>() {
        @Override
        public WifiChannel createFromParcel(Parcel in) {
            WifiChannel channel = new WifiChannel();
            channel.freqMHz = in.readInt();
            channel.channelNum = in.readInt();
            channel.isDFS = in.readInt() != 0;
            channel.ibssAllowed = in.readInt() != 0;
            return channel;
        }

        @Override
        public WifiChannel[] newArray(int size) {
            return new WifiChannel[size];
        }
    };
}
