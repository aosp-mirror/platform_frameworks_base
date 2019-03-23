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
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data source descriptor.
 *
 * Used by {@link MediaPlayer2#setDataSource}, {@link MediaPlayer2#setNextDataSource} and
 * {@link MediaPlayer2#setNextDataSources} to set data source for playback.
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

    DataSourceDesc(String mediaId, long startPositionMs, long endPositionMs) {
        mMediaId = mediaId;
        mStartPositionMs = startPositionMs;
        mEndPositionMs = endPositionMs;
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
    public @Nullable String getMediaId() {
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
     * Builder for {@link DataSourceDesc}.
     * <p>
     * Here is an example where <code>Builder</code> is used to define the
     * {@link DataSourceDesc} to be used by a {@link MediaPlayer2} instance:
     *
     * <pre class="prettyprint">
     * DataSourceDesc newDSD = new DataSourceDesc.Builder()
     *         .setDataSource(context, uri, headers, cookies)
     *         .setStartPosition(1000)
     *         .setEndPosition(15000)
     *         .build();
     * mediaplayer2.setDataSourceDesc(newDSD);
     * </pre>
     */
    public static final class Builder {
        private static final int SOURCE_TYPE_UNKNOWN = 0;
        private static final int SOURCE_TYPE_URI = 1;
        private static final int SOURCE_TYPE_FILE = 2;

        private int mSourceType = SOURCE_TYPE_UNKNOWN;
        private String mMediaId;
        private long mStartPositionMs = 0;
        private long mEndPositionMs = POSITION_UNKNOWN;

        // For UriDataSourceDesc
        private Uri mUri;
        private Map<String, String> mHeader;
        private List<HttpCookie> mCookies;

        // For FileDataSourceDesc
        private ParcelFileDescriptor mPFD;
        private long mOffset = 0;
        private long mLength = FileDataSourceDesc.FD_LENGTH_UNKNOWN;

        /**
         * Constructs a new BuilderBase with the defaults.
         */
        public Builder() {
        }

        /**
         * Constructs a new BuilderBase from a given {@link DataSourceDesc} instance
         * @param dsd the {@link DataSourceDesc} object whose data will be reused
         * in the new BuilderBase.
         */
        public Builder(@Nullable DataSourceDesc dsd) {
            if (dsd == null) {
                return;
            }
            mMediaId = dsd.mMediaId;
            mStartPositionMs = dsd.mStartPositionMs;
            mEndPositionMs = dsd.mEndPositionMs;
            if (dsd instanceof FileDataSourceDesc) {
                mSourceType = SOURCE_TYPE_FILE;
                mPFD = ((FileDataSourceDesc) dsd).getParcelFileDescriptor();
                mOffset = ((FileDataSourceDesc) dsd).getOffset();
                mLength = ((FileDataSourceDesc) dsd).getLength();
            } else if (dsd instanceof UriDataSourceDesc) {
                mSourceType = SOURCE_TYPE_URI;
                mUri = ((UriDataSourceDesc) dsd).getUri();
                mHeader = ((UriDataSourceDesc) dsd).getHeaders();
                mCookies = ((UriDataSourceDesc) dsd).getCookies();
            } else {
                throw new IllegalStateException("Unknown source type:" + mSourceType);
            }
        }

        /**
         * Sets all fields that have been set in the {@link DataSourceDesc} object.
         * <code>IllegalStateException</code> will be thrown if there is conflict between fields.
         *
         * @return {@link DataSourceDesc}
         */
        @NonNull
        public DataSourceDesc build() {
            if (mSourceType == SOURCE_TYPE_UNKNOWN) {
                throw new IllegalStateException("Source is not set.");
            }
            if (mStartPositionMs > mEndPositionMs) {
                throw new IllegalStateException("Illegal start/end position: "
                    + mStartPositionMs + " : " + mEndPositionMs);
            }

            DataSourceDesc desc;
            if (mSourceType == SOURCE_TYPE_FILE) {
                desc = new FileDataSourceDesc(
                        mMediaId, mStartPositionMs, mEndPositionMs, mPFD, mOffset, mLength);
            } else if (mSourceType == SOURCE_TYPE_URI) {
                desc = new UriDataSourceDesc(
                        mMediaId, mStartPositionMs, mEndPositionMs, mUri, mHeader, mCookies);
            } else {
                throw new IllegalStateException("Unknown source type:" + mSourceType);
            }
            return desc;
        }

        /**
         * Sets the media Id of this data source.
         *
         * @param mediaId the media Id of this data source
         * @return the same Builder instance.
         */
        @NonNull
        public Builder setMediaId(@Nullable String mediaId) {
            mMediaId = mediaId;
            return this;
        }

        /**
         * Sets the start position in milliseconds at which the playback will start.
         * Any negative number is treated as 0.
         *
         * @param position the start position in milliseconds at which the playback will start
         * @return the same Builder instance.
         *
         */
        @NonNull
        public Builder setStartPosition(long position) {
            if (position < 0) {
                position = 0;
            }
            mStartPositionMs = position;
            return this;
        }

        /**
         * Sets the end position in milliseconds at which the playback will end.
         * Any negative number is treated as maximum duration {@link #LONG_MAX_TIME_MS}
         * of the data source
         *
         * @param position the end position in milliseconds at which the playback will end
         * @return the same Builder instance.
         */
        @NonNull
        public Builder setEndPosition(long position) {
            if (position < 0) {
                position = LONG_MAX_TIME_MS;
            }
            mEndPositionMs = position;
            return this;
        }

        /**
         * Sets the data source as a content Uri.
         *
         * @param uri the Content URI of the data you want to play
         * @return the same Builder instance.
         * @throws NullPointerException if context or uri is null.
         */
        @NonNull
        public Builder setDataSource(@NonNull Uri uri) {
            setSourceType(SOURCE_TYPE_URI);
            Media2Utils.checkArgument(uri != null, "uri cannot be null");
            mUri = uri;
            return this;
        }

        /**
         * Sets the data source as a content Uri.
         *
         * To provide cookies for the subsequent HTTP requests, you can install your own default
         * cookie handler and use other variants of setDataSource APIs instead. Alternatively, you
         * can use this API to pass the cookies as a list of HttpCookie. If the app has not
         * installed a CookieHandler already, {@link MediaPlayer2} will create a CookieManager
         * and populates its CookieStore with the provided cookies when this data source is passed
         * to {@link MediaPlayer2}. If the app has installed its own handler already, the handler
         * is required to be of CookieManager type such that {@link MediaPlayer2} can update the
         * manager’s CookieStore.
         *
         *  <p><strong>Note</strong> that the cross domain redirection is allowed by default,
         * but that can be changed with key/value pairs through the headers parameter with
         * "android-allow-cross-domain-redirect" as the key and "0" or "1" as the value to
         * disallow or allow cross domain redirection.
         *
         * @param uri the Content URI of the data you want to play
         * @param headers the headers to be sent together with the request for the data
         *                The headers must not include cookies. Instead, use the cookies param.
         * @param cookies the cookies to be sent together with the request
         * @return the same Builder instance.
         * @throws NullPointerException if context or uri is null.
         * @throws IllegalArgumentException if the cookie handler is not of CookieManager type
         *                                  when cookies are provided.
         */
        @NonNull
        public Builder setDataSource(@NonNull Uri uri, @Nullable Map<String, String> headers,
                @Nullable List<HttpCookie> cookies) {
            setSourceType(SOURCE_TYPE_URI);
            Media2Utils.checkArgument(uri != null, "uri cannot be null");
            if (cookies != null) {
                CookieHandler cookieHandler = CookieHandler.getDefault();
                if (cookieHandler != null && !(cookieHandler instanceof CookieManager)) {
                    throw new IllegalArgumentException(
                            "The cookie handler has to be of CookieManager type "
                                    + "when cookies are provided.");
                }
            }

            mUri = uri;
            if (headers != null) {
                mHeader = new HashMap<String, String>(headers);
            }
            if (cookies != null) {
                mCookies = new ArrayList<HttpCookie>(cookies);
            }
            return this;
        }

        /**
         * Sets the data source (ParcelFileDescriptor) to use. The ParcelFileDescriptor must be
         * seekable (N.B. a LocalSocket is not seekable). When the {@link DataSourceDesc}
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
        @NonNull
        public Builder setDataSource(@NonNull ParcelFileDescriptor pfd) {
            setSourceType(SOURCE_TYPE_FILE);
            Media2Utils.checkArgument(pfd != null, "pfd cannot be null.");
            mPFD = pfd;
            return this;
        }

        /**
         * Sets the data source (ParcelFileDescriptor) to use. The ParcelFileDescriptor must be
         * seekable (N.B. a LocalSocket is not seekable). When the {@link DataSourceDesc}
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
        @NonNull
        public Builder setDataSource(
                @NonNull ParcelFileDescriptor pfd, long offset, long length) {
            setSourceType(SOURCE_TYPE_FILE);
            if (pfd == null) {
                throw new NullPointerException("pfd cannot be null.");
            }
            if (offset < 0) {
                offset = 0;
            }
            if (length < 0) {
                length = FileDataSourceDesc.FD_LENGTH_UNKNOWN;
            }
            mPFD = pfd;
            mOffset = offset;
            mLength = length;
            return this;
        }

        private void setSourceType(int type) {
            if (mSourceType != SOURCE_TYPE_UNKNOWN) {
                throw new IllegalStateException("Source is already set. type=" + mSourceType);
            }
            mSourceType = type;
        }
    }
}
