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
import android.annotation.Nullable;
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
 *
 */
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

    private FileDataSourceDesc() {
        super();
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

    /**
     * Builder class for {@link FileDataSourceDesc} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link FileDataSourceDesc} to be used by a {@link MediaPlayer2} instance:
     *
     * <pre class="prettyprint">
     * FileDataSourceDesc newDSD = new FileDataSourceDesc.Builder()
     *         .setDataSource(pfd, 0, srcLength)
     *         .setStartPosition(1000)
     *         .setEndPosition(15000)
     *         .build();
     * mediaplayer2.setDataSourceDesc(newDSD);
     * </pre>
     */
    public static class Builder extends BuilderBase<Builder> {
        private ParcelFileDescriptor mPFD;
        private long mOffset = 0;
        private long mLength = FD_LENGTH_UNKNOWN;

        /**
         * Constructs a new Builder with the defaults.
         */
        public Builder() {
            super();
        }

        /**
         * Constructs a new Builder from a given {@link FileDataSourceDesc} instance
         * @param dsd the {@link FileDataSourceDesc} object whose data will be reused
         * in the new Builder.
         */
        public Builder(@Nullable FileDataSourceDesc dsd) {
            super(dsd);
            if (dsd == null) {
                return;  // use default
            }
            mPFD = dsd.mPFD;
            mOffset = dsd.mOffset;
            mLength = dsd.mLength;
        }

        /**
         * Combines all of the fields that have been set and return a new
         * {@link FileDataSourceDesc} object. <code>IllegalStateException</code> will be
         * thrown if there is conflict between fields.
         *
         * @return a new {@link FileDataSourceDesc} object
         */
        public @NonNull FileDataSourceDesc build() {
            if (mPFD == null) {
                throw new IllegalStateException(
                        "underline ParcelFileDescriptor should not be null");
            }
            try {
                mPFD.getFd();
            } catch (IllegalStateException e) {
                throw new IllegalStateException("ParcelFileDescriptor has been closed");
            }

            FileDataSourceDesc dsd = new FileDataSourceDesc();
            super.build(dsd);
            dsd.mPFD = mPFD;
            dsd.mOffset = mOffset;
            dsd.mLength = mLength;

            return dsd;
        }

        /**
         * Sets the data source (ParcelFileDescriptor) to use. The ParcelFileDescriptor must be
         * seekable (N.B. a LocalSocket is not seekable). When the {@link FileDataSourceDesc}
         * created by this builder is passed to {@link MediaPlayer2} via
         * {@link MediaPlayer2#setDataSource},
         * {@link MediaPlayer2#setNextDataSource} or
         * {@link MediaPlayer2#setNextDataSources}, MediaPlayer2 will
         * close the ParcelFileDescriptor.
         *
         * @param pfd the ParcelFileDescriptor for the file to play
         * @return the same Builder instance.
         * @throws NullPointerException if pfd is null.
         */
        public @NonNull Builder setDataSource(@NonNull ParcelFileDescriptor pfd) {
            Media2Utils.checkArgument(pfd != null, "pfd cannot be null.");
            resetDataSource();
            mPFD = pfd;
            return this;
        }

        /**
         * Sets the data source (ParcelFileDescriptor) to use. The ParcelFileDescriptor must be
         * seekable (N.B. a LocalSocket is not seekable). When the {@link FileDataSourceDesc}
         * created by this builder is passed to {@link MediaPlayer2} via
         * {@link MediaPlayer2#setDataSource},
         * {@link MediaPlayer2#setNextDataSource} or
         * {@link MediaPlayer2#setNextDataSources}, MediaPlayer2 will
         * close the ParcelFileDescriptor.
         *
         * Any negative number for offset is treated as 0.
         * Any negative number for length is treated as maximum length of the data source.
         *
         * @param pfd the ParcelFileDescriptor for the file to play
         * @param offset the offset into the file where the data to be played starts, in bytes
         * @param length the length in bytes of the data to be played
         * @return the same Builder instance.
         * @throws NullPointerException if pfd is null.
         */
        public @NonNull Builder setDataSource(
                @NonNull ParcelFileDescriptor pfd, long offset, long length) {
            Media2Utils.checkArgument(pfd != null, "pfd cannot be null.");
            if (offset < 0) {
                offset = 0;
            }
            if (length < 0) {
                length = FD_LENGTH_UNKNOWN;
            }
            resetDataSource();
            mPFD = pfd;
            mOffset = offset;
            mLength = length;
            return this;
        }

        private void resetDataSource() {
            mPFD = null;
            mOffset = 0;
            mLength = FD_LENGTH_UNKNOWN;
        }
    }
}
