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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;

import java.util.Arrays;

/**
 * Class that embodies one timed metadata access unit, including
 *
 * <ul>
 * <li> a time stamp, and </li>
 * <li> raw uninterpreted byte-array extracted directly from the container. </li>
 * </ul>
 *
 * @see MediaPlayer#setOnTimedMetaDataAvailableListener(android.media.MediaPlayer.OnTimedMetaDataAvailableListener)
 */
public final class TimedMetaData {
    private static final String TAG = "TimedMetaData";

    private long mTimestampUs;
    private byte[] mMetaData;

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

    /**
     * @hide
     */
    public TimedMetaData(long timestampUs, @NonNull byte[] metaData) {
        if (metaData == null) {
            throw new IllegalArgumentException("null metaData is not allowed");
        }
        mTimestampUs = timestampUs;
        mMetaData = metaData;
    }

    /**
     * @return the timestamp associated with this metadata access unit in microseconds;
     * 0 denotes playback start.
     */
    public long getTimestamp() {
        return mTimestampUs;
    }

    /**
     * @return raw, uninterpreted content of this metadata access unit; for ID3 tags this includes
     * everything starting from the 3 byte signature "ID3".
     */
    public byte[] getMetaData() {
        return mMetaData;
    }

    private boolean parseParcel(Parcel parcel) {
        parcel.setDataPosition(0);
        if (parcel.dataAvail() == 0) {
            return false;
        }

        mTimestampUs = parcel.readLong();
        mMetaData = new byte[parcel.readInt()];
        parcel.readByteArray(mMetaData);

        return true;
    }

    /**
     * Builder class for {@link TimedMetaData} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link TimedMetaData}:
     *
     * <pre class="prettyprint">
     * TimedMetaData tmd = new TimedMetaData.Builder()
     *         .setTimedMetaData(timestamp, metaData)
     *         .build();
     * </pre>
     * @hide
     */
    @SystemApi
    public static class Builder {
        private long mTimestampUs;
        private byte[] mMetaData = new byte[0];

        /**
         * Constructs a new Builder with the defaults.
         */
        public Builder() {
        }

        /**
         * Constructs a new Builder from a given {@link TimedMetaData} instance
         * @param tmd the {@link TimedMetaData} object whose data will be reused
         * in the new Builder. It should not be null. The metadata array is copied.
         */
        public Builder(@NonNull TimedMetaData tmd) {
            if (tmd == null) {
                throw new IllegalArgumentException("null TimedMetaData is not allowed");
            }
            mTimestampUs = tmd.mTimestampUs;
            if (tmd.mMetaData != null) {
                mMetaData = Arrays.copyOf(tmd.mMetaData, tmd.mMetaData.length);
            }
        }

        /**
         * Combines all of the fields that have been set and return a new
         * {@link TimedMetaData} object. <code>IllegalStateException</code> will be
         * thrown if there is conflict between fields.
         *
         * @return a new {@link TimedMetaData} object
         */
        public @NonNull TimedMetaData build() {
            return new TimedMetaData(mTimestampUs, mMetaData);
        }

        /**
         * Sets the info of timed metadata.
         *
         * @param timestamp the timestamp in microsecond for the timed metadata
         * @param metaData the metadata array for the timed metadata. No data copying is made.
         *     It should not be null.
         * @return the same Builder instance.
         */
        public @NonNull Builder setTimedMetaData(long timestamp, @NonNull byte[] metaData) {
            if (metaData == null) {
                throw new IllegalArgumentException("null metaData is not allowed");
            }
            mTimestampUs = timestamp;
            mMetaData = metaData;
            return this;
        }
    }
}
