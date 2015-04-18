/*
 * Copyright 2015 The Android Open Source Project
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

package android.media;

import android.os.Parcel;

/**
 * Class that embodies a piece of timed metadata, including
 *
 * <ul>
 * <li> a time stamp, and </li>
 * <li> raw uninterpreted byte-array extracted directly from the container. </li>
 * </ul>
 *
 * @see MediaPlayer#setOnTimedMetaDataListener(android.media.MediaPlayer.OnTimedMetaDataListener)
 */

public class TimedMetaData {
    private static final String TAG = "TimedMetaData";

    private long mTimeUs;
    private byte[] mRawData;

    /**
     * @hide
     */
    static TimedMetaData createTimedMetaDataFromParcel(Parcel parcel) {
        return new TimedMetaData(parcel);
    }

    private TimedMetaData(Parcel parcel) {
        if (!parseParcel(parcel)) {
            throw new IllegalArgumentException("parseParcel() fails");
        }
    }

    public long getTimeUs() {
        return mTimeUs;
    }

    public byte[] getRawData() {
        return mRawData;
    }

    private boolean parseParcel(Parcel parcel) {
        parcel.setDataPosition(0);
        if (parcel.dataAvail() == 0) {
            return false;
        }

        mTimeUs = parcel.readLong();
        mRawData = new byte[parcel.readInt()];
        parcel.readByteArray(mRawData);

        return true;
    }
}
