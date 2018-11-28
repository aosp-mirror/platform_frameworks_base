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

import java.io.FileDescriptor;

/**
 * @hide
 * Structure for data source descriptor.
 *
 * Used by {@link MediaPlayer2#setDataSource(DataSourceDesc)}
 * to set data source for playback.
 *
 * <p>Users should use {@link Builder} to create {@link FileDataSourceDesc}.
 *
 */
public class FileDataSourceDesc extends DataSourceDesc {
    /**
     * Used when the length of file descriptor is unknown.
     *
     * @see #getLength()
     */
    public static final long FD_LENGTH_UNKNOWN = LONG_MAX;

    private FileDescriptor mFD;
    private long mOffset = 0;
    private long mLength = FD_LENGTH_UNKNOWN;

    private FileDataSourceDesc() {
    }

    /**
     * Return the FileDescriptor of this data source.
     * @return the FileDescriptor of this data source
     */
    public FileDescriptor getFileDescriptor() {
        return mFD;
    }

    /**
     * Return the offset associated with the FileDescriptor of this data source.
     * It's meaningful only when it has been set by the {@link Builder}.
     * @return the offset associated with the FileDescriptor of this data source
     */
    public long getOffset() {
        return mOffset;
    }

    /**
     * Return the content length associated with the FileDescriptor of this data source.
     * {@link #FD_LENGTH_UNKNOWN} means same as the length of source content.
     * @return the content length associated with the FileDescriptor of this data source
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
     *         .setDataSource(fd, 0, srcLength)
     *         .setStartPosition(1000)
     *         .setEndPosition(15000)
     *         .build();
     * mediaplayer2.setDataSourceDesc(newDSD);
     * </pre>
     */
    public static class Builder extends BuilderBase<Builder> {
        private FileDescriptor mFD;
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
        public Builder(FileDataSourceDesc dsd) {
            super(dsd);
            if (dsd == null) {
                return;  // use default
            }
            mFD = dsd.mFD;
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
            FileDataSourceDesc dsd = new FileDataSourceDesc();
            super.build(dsd);
            dsd.mFD = mFD;
            dsd.mOffset = mOffset;
            dsd.mLength = mLength;

            return dsd;
        }

        /**
         * Sets the data source (FileDescriptor) to use. The FileDescriptor must be
         * seekable (N.B. a LocalSocket is not seekable). It is the caller's responsibility
         * to close the file descriptor after the source has been used.
         *
         * @param fd the FileDescriptor for the file to play
         * @return the same Builder instance.
         * @throws NullPointerException if fd is null.
         */
        public @NonNull Builder setDataSource(@NonNull FileDescriptor fd) {
            Media2Utils.checkArgument(fd != null, "fd cannot be null.");
            resetDataSource();
            mFD = fd;
            return this;
        }

        /**
         * Sets the data source (FileDescriptor) to use. The FileDescriptor must be
         * seekable (N.B. a LocalSocket is not seekable). It is the caller's responsibility
         * to close the file descriptor after the source has been used.
         *
         * Any negative number for offset is treated as 0.
         * Any negative number for length is treated as maximum length of the data source.
         *
         * @param fd the FileDescriptor for the file to play
         * @param offset the offset into the file where the data to be played starts, in bytes
         * @param length the length in bytes of the data to be played
         * @return the same Builder instance.
         * @throws NullPointerException if fd is null.
         */
        public @NonNull Builder setDataSource(
                @NonNull FileDescriptor fd, long offset, long length) {
            Media2Utils.checkArgument(fd != null, "fd cannot be null.");
            if (offset < 0) {
                offset = 0;
            }
            if (length < 0) {
                length = FD_LENGTH_UNKNOWN;
            }
            resetDataSource();
            mFD = fd;
            mOffset = offset;
            mLength = length;
            return this;
        }

        private void resetDataSource() {
            mFD = null;
            mOffset = 0;
            mLength = FD_LENGTH_UNKNOWN;
        }
    }
}
