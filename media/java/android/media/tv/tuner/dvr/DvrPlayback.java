/*
 * Copyright 2019 The Android Open Source Project
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

package android.media.tv.tuner.dvr;

import android.annotation.BytesLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.tv.tuner.V1_0.Constants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Digital Video Record (DVR) class which provides playback control on Demux's input buffer.
 *
 * <p>It's used to play recorded programs.
 *
 * @hide
 */
@SystemApi
public class DvrPlayback extends Dvr {


    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "PLAYBACK_STATUS_",
            value = {PLAYBACK_STATUS_EMPTY, PLAYBACK_STATUS_ALMOST_EMPTY,
                    PLAYBACK_STATUS_ALMOST_FULL, PLAYBACK_STATUS_FULL})
    @interface PlaybackStatus {}

    /**
     * The space of the playback is empty.
     */
    public static final int PLAYBACK_STATUS_EMPTY = Constants.PlaybackStatus.SPACE_EMPTY;
    /**
     * The space of the playback is almost empty.
     *
     * <p> the threshold is set in {@link DvrSettings}.
     */
    public static final int PLAYBACK_STATUS_ALMOST_EMPTY =
            Constants.PlaybackStatus.SPACE_ALMOST_EMPTY;
    /**
     * The space of the playback is almost full.
     *
     * <p> the threshold is set in {@link DvrSettings}.
     */
    public static final int PLAYBACK_STATUS_ALMOST_FULL =
            Constants.PlaybackStatus.SPACE_ALMOST_FULL;
    /**
     * The space of the playback is full.
     */
    public static final int PLAYBACK_STATUS_FULL = Constants.PlaybackStatus.SPACE_FULL;



    private native long nativeRead(long size);
    private native long nativeRead(byte[] bytes, long offset, long size);

    private DvrPlayback() {
        super(Dvr.TYPE_PLAYBACK);
    }


    /**
     * Reads data from the file for DVR playback.
     *
     * @param size the maximum number of bytes to read.
     * @return the number of bytes read.
     */
    @BytesLong
    public long read(@BytesLong long size) {
        return nativeRead(size);
    }

    /**
     * Reads data from the buffer for DVR playback and copies to the given byte array.
     *
     * @param bytes the byte array to store the data.
     * @param offset the index of the first byte in {@code bytes} to copy to.
     * @param size the maximum number of bytes to read.
     * @return the number of bytes read.
     */
    @BytesLong
    public long read(@NonNull byte[] bytes, @BytesLong long offset, @BytesLong long size) {
        if (size + offset > bytes.length) {
            throw new ArrayIndexOutOfBoundsException(
                    "Array length=" + bytes.length + ", offset=" + offset + ", size=" + size);
        }
        return nativeRead(bytes, offset, size);
    }
}
