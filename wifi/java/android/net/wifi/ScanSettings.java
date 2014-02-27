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

import java.util.ArrayList;
import java.util.Collection;

/**
 * Bundle of customized scan settings
 *
 * @see WifiManager#startCustomizedScan
 *
 * @hide
 */
public class ScanSettings implements Parcelable {

    /** channel set to scan. this can be null or empty, indicating a full scan */
    public Collection<WifiChannel> channelSet;

    /** public constructor */
    public ScanSettings() { }

    /** copy constructor */
    public ScanSettings(ScanSettings source) {
        if (source.channelSet != null)
            channelSet = new ArrayList<WifiChannel>(source.channelSet);
    }

    /** check for validity */
    public boolean isValid() {
        for (WifiChannel channel : channelSet)
            if (!channel.isValid()) return false;
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
        out.writeInt(channelSet == null ? 0 : channelSet.size());
        if (channelSet != null)
            for (WifiChannel channel : channelSet) channel.writeToParcel(out, flags);
    }

    /** implement Parcelable interface */
    public static final Parcelable.Creator<ScanSettings> CREATOR =
            new Parcelable.Creator<ScanSettings>() {
        @Override
        public ScanSettings createFromParcel(Parcel in) {
            ScanSettings settings = new ScanSettings();
            int size = in.readInt();
            if (size > 0) {
                settings.channelSet = new ArrayList<WifiChannel>(size);
                while (size-- > 0)
                    settings.channelSet.add(WifiChannel.CREATOR.createFromParcel(in));
            }
            return settings;
        }

        @Override
        public ScanSettings[] newArray(int size) {
            return new ScanSettings[size];
        }
    };
}
