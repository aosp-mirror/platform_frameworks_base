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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @hide
 * Structure for data source descriptor.
 *
 * Used by {@link MediaPlayer2#setDataSource(DataSourceDesc)}
 * to set data source for playback.
 *
 * <p>Users should use {@link Builder} to change {@link DataSourceDesc}.
 *
 */
public final class DataSourceDesc {
    /* No data source has been set yet */
    public static final int TYPE_NONE     = 0;
    /* data source is type of MediaDataSource */
    public static final int TYPE_CALLBACK = 1;
    /* data source is type of FileDescriptor */
    public static final int TYPE_FD       = 2;
    /* data source is type of Uri */
    public static final int TYPE_URI      = 3;

    // intentionally less than long.MAX_VALUE
    public static final long LONG_MAX = 0x7ffffffffffffffL;

    private int mType = TYPE_NONE;

    private Media2DataSource mMedia2DataSource;

    private FileDescriptor mFD;
    private long mFDOffset = 0;
    private long mFDLength = LONG_MAX;

    private Uri mUri;
    private Map<String, String> mUriHeader;
    private List<HttpCookie> mUriCookies;
    private Context mUriContext;

    private String mMediaId;
    private long mStartPositionMs = 0;
    private long mEndPositionMs = LONG_MAX;

    private DataSourceDesc() {
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
     * -1 means ending at the end of source content.
     * @return the position in milliseconds at which the playback will end
     */
    public long getEndPosition() {
        return mEndPositionMs;
    }

    /**
     * Return the type of data source.
     * @return the type of data source
     */
    public int getType() {
        return mType;
    }

    /**
     * Return the Media2DataSource of this data source.
     * It's meaningful only when {@code getType} returns {@link #TYPE_CALLBACK}.
     * @return the Media2DataSource of this data source
     */
    public Media2DataSource getMedia2DataSource() {
        return mMedia2DataSource;
    }

    /**
     * Return the FileDescriptor of this data source.
     * It's meaningful only when {@code getType} returns {@link #TYPE_FD}.
     * @return the FileDescriptor of this data source
     */
    public FileDescriptor getFileDescriptor() {
        return mFD;
    }

    /**
     * Return the offset associated with the FileDescriptor of this data source.
     * It's meaningful only when {@code getType} returns {@link #TYPE_FD} and it has
     * been set by the {@link Builder}.
     * @return the offset associated with the FileDescriptor of this data source
     */
    public long getFileDescriptorOffset() {
        return mFDOffset;
    }

    /**
     * Return the content length associated with the FileDescriptor of this data source.
     * It's meaningful only when {@code getType} returns {@link #TYPE_FD}.
     * -1 means same as the length of source content.
     * @return the content length associated with the FileDescriptor of this data source
     */
    public long getFileDescriptorLength() {
        return mFDLength;
    }

    /**
     * Return the Uri of this data source.
     * It's meaningful only when {@code getType} returns {@link #TYPE_URI}.
     * @return the Uri of this data source
     */
    public Uri getUri() {
        return mUri;
    }

    /**
     * Return the Uri headers of this data source.
     * It's meaningful only when {@code getType} returns {@link #TYPE_URI}.
     * @return the Uri headers of this data source
     */
    public Map<String, String> getUriHeaders() {
        if (mUriHeader == null) {
            return null;
        }
        return new HashMap<String, String>(mUriHeader);
    }

    /**
     * Return the Uri cookies of this data source.
     * It's meaningful only when {@code getType} returns {@link #TYPE_URI}.
     * @return the Uri cookies of this data source
     */
    public List<HttpCookie> getUriCookies() {
        if (mUriCookies == null) {
            return null;
        }
        return new ArrayList<HttpCookie>(mUriCookies);
    }

    /**
     * Return the Context used for resolving the Uri of this data source.
     * It's meaningful only when {@code getType} returns {@link #TYPE_URI}.
     * @return the Context used for resolving the Uri of this data source
     */
    public Context getUriContext() {
        return mUriContext;
    }

    /**
     * Builder class for {@link DataSourceDesc} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link DataSourceDesc} to be used by a {@link MediaPlayer2} instance:
     *
     * <pre class="prettyprint">
     * DataSourceDesc oldDSD = mediaplayer2.getDataSourceDesc();
     * DataSourceDesc newDSD = new DataSourceDesc.Builder(oldDSD)
     *         .setStartPosition(1000)
     *         .setEndPosition(15000)
     *         .build();
     * mediaplayer2.setDataSourceDesc(newDSD);
     * </pre>
     */
    public static class Builder {
        private int mType = TYPE_NONE;

        private Media2DataSource mMedia2DataSource;

        private FileDescriptor mFD;
        private long mFDOffset = 0;
        private long mFDLength = LONG_MAX;

        private Uri mUri;
        private Map<String, String> mUriHeader;
        private List<HttpCookie> mUriCookies;
        private Context mUriContext;

        private String mMediaId;
        private long mStartPositionMs = 0;
        private long mEndPositionMs = LONG_MAX;

        /**
         * Constructs a new Builder with the defaults.
         */
        public Builder() {
        }

        /**
         * Constructs a new Builder from a given {@link DataSourceDesc} instance
         * @param dsd the {@link DataSourceDesc} object whose data will be reused
         * in the new Builder.
         */
        public Builder(DataSourceDesc dsd) {
            mType = dsd.mType;
            mMedia2DataSource = dsd.mMedia2DataSource;
            mFD = dsd.mFD;
            mFDOffset = dsd.mFDOffset;
            mFDLength = dsd.mFDLength;
            mUri = dsd.mUri;
            mUriHeader = dsd.mUriHeader;
            mUriCookies = dsd.mUriCookies;
            mUriContext = dsd.mUriContext;

            mMediaId = dsd.mMediaId;
            mStartPositionMs = dsd.mStartPositionMs;
            mEndPositionMs = dsd.mEndPositionMs;
        }

        /**
         * Combines all of the fields that have been set and return a new
         * {@link DataSourceDesc} object. <code>IllegalStateException</code> will be
         * thrown if there is conflict between fields.
         *
         * @return a new {@link DataSourceDesc} object
         */
        public DataSourceDesc build() {
            if (mType != TYPE_CALLBACK
                && mType != TYPE_FD
                && mType != TYPE_URI) {
                throw new IllegalStateException("Illegal type: " + mType);
            }
            if (mStartPositionMs > mEndPositionMs) {
                throw new IllegalStateException("Illegal start/end position: "
                    + mStartPositionMs + " : " + mEndPositionMs);
            }

            DataSourceDesc dsd = new DataSourceDesc();
            dsd.mType = mType;
            dsd.mMedia2DataSource = mMedia2DataSource;
            dsd.mFD = mFD;
            dsd.mFDOffset = mFDOffset;
            dsd.mFDLength = mFDLength;
            dsd.mUri = mUri;
            dsd.mUriHeader = mUriHeader;
            dsd.mUriCookies = mUriCookies;
            dsd.mUriContext = mUriContext;

            dsd.mMediaId = mMediaId;
            dsd.mStartPositionMs = mStartPositionMs;
            dsd.mEndPositionMs = mEndPositionMs;

            return dsd;
        }

        /**
         * Sets the media Id of this data source.
         *
         * @param mediaId the media Id of this data source
         * @return the same Builder instance.
         */
        public Builder setMediaId(String mediaId) {
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
        public Builder setStartPosition(long position) {
            if (position < 0) {
                position = 0;
            }
            mStartPositionMs = position;
            return this;
        }

        /**
         * Sets the end position in milliseconds at which the playback will end.
         * Any negative number is treated as maximum length of the data source.
         *
         * @param position the end position in milliseconds at which the playback will end
         * @return the same Builder instance.
         */
        public Builder setEndPosition(long position) {
            if (position < 0) {
                position = LONG_MAX;
            }
            mEndPositionMs = position;
            return this;
        }

        /**
         * Sets the data source (Media2DataSource) to use.
         *
         * @param m2ds the Media2DataSource for the media you want to play
         * @return the same Builder instance.
         * @throws NullPointerException if m2ds is null.
         */
        public Builder setDataSource(Media2DataSource m2ds) {
            Preconditions.checkNotNull(m2ds);
            resetDataSource();
            mType = TYPE_CALLBACK;
            mMedia2DataSource = m2ds;
            return this;
        }

        /**
         * Sets the data source (FileDescriptor) to use. The FileDescriptor must be
         * seekable (N.B. a LocalSocket is not seekable). It is the caller's responsibility
         * to close the file descriptor after the source has been used.
         *
         * @param fd the FileDescriptor for the file you want to play
         * @return the same Builder instance.
         * @throws NullPointerException if fd is null.
         */
        public Builder setDataSource(FileDescriptor fd) {
            Preconditions.checkNotNull(fd);
            resetDataSource();
            mType = TYPE_FD;
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
         * @param fd the FileDescriptor for the file you want to play
         * @param offset the offset into the file where the data to be played starts, in bytes
         * @param length the length in bytes of the data to be played
         * @return the same Builder instance.
         * @throws NullPointerException if fd is null.
         */
        public Builder setDataSource(FileDescriptor fd, long offset, long length) {
            Preconditions.checkNotNull(fd);
            if (offset < 0) {
                offset = 0;
            }
            if (length < 0) {
                length = LONG_MAX;
            }
            resetDataSource();
            mType = TYPE_FD;
            mFD = fd;
            mFDOffset = offset;
            mFDLength = length;
            return this;
        }

        /**
         * Sets the data source as a content Uri.
         *
         * @param context the Context to use when resolving the Uri
         * @param uri the Content URI of the data you want to play
         * @return the same Builder instance.
         * @throws NullPointerException if context or uri is null.
         */
        public Builder setDataSource(@NonNull Context context, @NonNull Uri uri) {
            Preconditions.checkNotNull(context, "context cannot be null");
            Preconditions.checkNotNull(uri, "uri cannot be null");
            resetDataSource();
            mType = TYPE_URI;
            mUri = uri;
            mUriContext = context;
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
         * @param context the Context to use when resolving the Uri
         * @param uri the Content URI of the data you want to play
         * @param headers the headers to be sent together with the request for the data
         *                The headers must not include cookies. Instead, use the cookies param.
         * @param cookies the cookies to be sent together with the request
         * @return the same Builder instance.
         * @throws NullPointerException if context or uri is null.
         * @throws IllegalArgumentException if the cookie handler is not of CookieManager type
         *                                  when cookies are provided.
         */
        public Builder setDataSource(@NonNull Context context, @NonNull Uri uri,
                @Nullable Map<String, String> headers, @Nullable List<HttpCookie> cookies) {
            Preconditions.checkNotNull(context, "context cannot be null");
            Preconditions.checkNotNull(uri);
            if (cookies != null) {
                CookieHandler cookieHandler = CookieHandler.getDefault();
                if (cookieHandler != null && !(cookieHandler instanceof CookieManager)) {
                    throw new IllegalArgumentException(
                            "The cookie handler has to be of CookieManager type "
                            + "when cookies are provided.");
                }
            }

            resetDataSource();
            mType = TYPE_URI;
            mUri = uri;
            if (headers != null) {
                mUriHeader = new HashMap<String, String>(headers);
            }
            if (cookies != null) {
                mUriCookies = new ArrayList<HttpCookie>(cookies);
            }
            mUriContext = context;
            return this;
        }

        private void resetDataSource() {
            mType = TYPE_NONE;
            mMedia2DataSource = null;
            mFD = null;
            mFDOffset = 0;
            mFDLength = LONG_MAX;
            mUri = null;
            mUriHeader = null;
            mUriCookies = null;
            mUriContext = null;
        }
    }
}
