/*
 * Copyright 2018 The Android Open Source Project
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
import android.annotation.TestApi;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;

/**
 * Structure of data source descriptor for sources using file descriptor.
 *
 * Used by {@link MediaPlayer2#setDataSource}, {@link MediaPlayer2#setNextDataSource} and
 * {@link MediaPlayer2#setNextDataSources} to set data source for playback.
 *
 * <p>Users should use {@link Builder} to create {@link FileDataSourceDesc}.
 * @hide
 */
@TestApi
public class FileDataSourceDesc extends DataSourceDesc {
    private static final String TAG = "FileDataSourceDesc";

    /**
     * Used when the length of file descriptor is unknown.
     *
     * @see #getLength()
     */
    public static final long FD_LENGTH_UNKNOWN = LONG_MAX;

    private ParcelFileDescriptor mPFD;
    private long mOffset = 0;
    private long mLength = FD_LENGTH_UNKNOWN;
    private int mCount = 0;
    private boolean mClosed = false;

    FileDataSourceDesc(String mediaId, long startPositionMs, long endPositionMs,
            ParcelFileDescriptor pfd, long offset, long length) {
        super(mediaId, startPositionMs, endPositionMs);
        mPFD = pfd;
        mOffset = offset;
        mLength = length;
    }

    /**
     * Releases the resources held by this {@code FileDataSourceDesc} object.
     */
    @Override
    void close() {
        super.close();
        decCount();
    }

    /**
     * Decrements usage count by {@link MediaPlayer2}.
     * If this is the last usage, also releases the file descriptor held by this
     * {@code FileDataSourceDesc} object.
     */
    void decCount() {
        synchronized (this) {
            --mCount;
            if (mCount > 0) {
                return;
            }

            try {
                mPFD.close();
                mClosed = true;
            } catch (IOException e) {
                Log.e(TAG, "failed to close pfd: " + e);
            }
        }
    }

    /**
     * Increments usage count by {@link MediaPlayer2} if PFD has not been closed.
     */
    void incCount() {
        synchronized (this) {
            if (!mClosed) {
                ++mCount;
            }
        }
    }

    /**
     * Return the status of underline ParcelFileDescriptor
     * @return true if underline ParcelFileDescriptor is closed, false otherwise.
     */
    boolean isPFDClosed() {
        synchronized (this) {
            return mClosed;
        }
    }

    /**
     * Return the ParcelFileDescriptor of this data source.
     * @return the ParcelFileDescriptor of this data source
     */
    public @NonNull ParcelFileDescriptor getParcelFileDescriptor() {
        return mPFD;
    }

    /**
     * Return the offset associated with the ParcelFileDescriptor of this data source.
     * It's meaningful only when it has been set by the {@link Builder}.
     * @return the offset associated with the ParcelFileDescriptor of this data source
     */
    public long getOffset() {
        return mOffset;
    }

    /**
     * Return the content length associated with the ParcelFileDescriptor of this data source.
     * {@link #FD_LENGTH_UNKNOWN} means same as the length of source content.
     * @return the content length associated with the ParcelFileDescriptor of this data source
     */
    public long getLength() {
        return mLength;
    }
}
