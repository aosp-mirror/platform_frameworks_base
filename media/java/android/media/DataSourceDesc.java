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

/**
 * Base class of data source descriptor.
 *
 * Used by {@link MediaPlayer2#setDataSource}, {@link MediaPlayer2#setNextDataSource} and
 * {@link MediaPlayer2#setNextDataSources} to set data source for playback.
 *
 * <p>Users should use subclasses' builder to change {@link DataSourceDesc}.
 *
 */
public class DataSourceDesc {
    // intentionally less than long.MAX_VALUE
    static final long LONG_MAX = 0x7ffffffffffffffL;

    // keep consistent with native code
    public static final long LONG_MAX_TIME_MS = LONG_MAX / 1000;
    /**
     * @hide
     */
    public static final long LONG_MAX_TIME_US = LONG_MAX_TIME_MS * 1000;

    public static final long POSITION_UNKNOWN = LONG_MAX_TIME_MS;

    private String mMediaId;
    private long mStartPositionMs = 0;
    private long mEndPositionMs = POSITION_UNKNOWN;

    DataSourceDesc() {
    }

    /**
     * Releases the resources held by this {@code DataSourceDesc} object.
     */
    void close() {
    }

    // Have to declare protected for finalize() since it is protected
    // in the base class Object.
    @Override
    protected void finalize() throws Throwable {
        close();
    }

    /**
     * Return the media Id of data source.
     * @return the media Id of data source
     */
    public String getMediaId() {
        return mMediaId;
    }

    /**
     * Return the position in milliseconds at which the playback will start.
     * @return the position in milliseconds at which the playback will start
     */
    public long getStartPosition() {
        return mStartPositionMs;
    }

    /**
     * Return the position in milliseconds at which the playback will end.
     * {@link #POSITION_UNKNOWN} means ending at the end of source content.
     * @return the position in milliseconds at which the playback will end
     */
    public long getEndPosition() {
        return mEndPositionMs;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("DataSourceDesc{");
        sb.append("mMediaId=").append(mMediaId);
        sb.append(", mStartPositionMs=").append(mStartPositionMs);
        sb.append(", mEndPositionMs=").append(mEndPositionMs);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Base class for Builders in the subclasses of {@link DataSourceDesc}.
     */
    protected static class BuilderBase<T extends BuilderBase> {
        private String mMediaId;
        private long mStartPositionMs = 0;
        private long mEndPositionMs = POSITION_UNKNOWN;

        /**
         * Constructs a new BuilderBase with the defaults.
         */
        BuilderBase() {
        }

        /**
         * Constructs a new BuilderBase from a given {@link DataSourceDesc} instance
         * @param dsd the {@link DataSourceDesc} object whose data will be reused
         * in the new BuilderBase.
         */
        BuilderBase(DataSourceDesc dsd) {
            if (dsd == null) {
                return;
            }
            mMediaId = dsd.mMediaId;
            mStartPositionMs = dsd.mStartPositionMs;
            mEndPositionMs = dsd.mEndPositionMs;
        }

        /**
         * Sets all fields that have been set in the {@link DataSourceDesc} object.
         * <code>IllegalStateException</code> will be thrown if there is conflict between fields.
         *
         * @param dsd an instance of subclass of {@link DataSourceDesc} whose data will be set
         * @return the same instance of subclass of {@link DataSourceDesc}
         */
        void build(@NonNull DataSourceDesc dsd) {
            Media2Utils.checkArgument(dsd != null,  "dsd cannot be null.");

            if (mStartPositionMs > mEndPositionMs) {
                throw new IllegalStateException("Illegal start/end position: "
                    + mStartPositionMs + " : " + mEndPositionMs);
            }

            dsd.mMediaId = mMediaId;
            dsd.mStartPositionMs = mStartPositionMs;
            dsd.mEndPositionMs = mEndPositionMs;
        }

        /**
         * Sets the media Id of this data source.
         *
         * @param mediaId the media Id of this data source
         * @return the same Builder instance.
         */
        public @NonNull T setMediaId(String mediaId) {
            mMediaId = mediaId;
            return (T) this;
        }

        /**
         * Sets the start position in milliseconds at which the playback will start.
         * Any negative number is treated as 0.
         *
         * @param position the start position in milliseconds at which the playback will start
         * @return the same Builder instance.
         *
         */
        public @NonNull T setStartPosition(long position) {
            if (position < 0) {
                position = 0;
            }
            mStartPositionMs = position;
            return (T) this;
        }

        /**
         * Sets the end position in milliseconds at which the playback will end.
         * Any negative number is treated as maximum duration {@link #LONG_MAX_TIME_MS}
         * of the data source
         *
         * @param position the end position in milliseconds at which the playback will end
         * @return the same Builder instance.
         */
        public @NonNull T setEndPosition(long position) {
            if (position < 0) {
                position = LONG_MAX_TIME_MS;
            }
            mEndPositionMs = position;
            return (T) this;
        }
    }
}
