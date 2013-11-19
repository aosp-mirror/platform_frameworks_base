/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * @hide
 *
 * Class to hold the subtitle track's data, including:
 * <ul>
 * <li> Track index</li>
 * <li> Start time (in microseconds) of the data</li>
 * <li> Duration (in microseconds) of the data</li>
 * <li> A byte-array of the data</li>
 * </ul>
 *
 * <p> To receive the subtitle data, applications need to do the following:
 *
 * <ul>
 * <li> Select a track of type MEDIA_TRACK_TYPE_SUBTITLE with {@link MediaPlayer.selectTrack(int)</li>
 * <li> Implement the {@link MediaPlayer.OnSubtitleDataListener} interface</li>
 * <li> Register the {@link MediaPlayer.OnSubtitleDataListener} callback on a MediaPlayer object</li>
 * </ul>
 *
 * @see android.media.MediaPlayer
 */
public final class SubtitleData
{
    private static final String TAG = "SubtitleData";

    private int mTrackIndex;
    private long mStartTimeUs;
    private long mDurationUs;
    private byte[] mData;

    public SubtitleData(Parcel parcel) {
        if (!parseParcel(parcel)) {
            throw new IllegalArgumentException("parseParcel() fails");
        }
    }

    public int getTrackIndex() {
        return mTrackIndex;
    }

    public long getStartTimeUs() {
        return mStartTimeUs;
    }

    public long getDurationUs() {
        return mDurationUs;
    }

    public byte[] getData() {
        return mData;
    }

    private boolean parseParcel(Parcel parcel) {
        parcel.setDataPosition(0);
        if (parcel.dataAvail() == 0) {
            return false;
        }

        mTrackIndex = parcel.readInt();
        mStartTimeUs = parcel.readLong();
        mDurationUs = parcel.readLong();
        mData = new byte[parcel.readInt()];
        parcel.readByteArray(mData);

        return true;
    }
}
