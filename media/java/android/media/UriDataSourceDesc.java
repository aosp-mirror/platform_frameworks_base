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
import android.content.Context;
import android.net.Uri;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structure of data source descriptor for sources using URI.
 *
 * Used by {@link MediaPlayer2#setDataSource}, {@link MediaPlayer2#setNextDataSource} and
 * {@link MediaPlayer2#setNextDataSources} to set data source for playback.
 *
 * <p>Users should use {@link Builder} to change {@link UriDataSourceDesc}.
 *
 */
public class UriDataSourceDesc extends DataSourceDesc {
    private Uri mUri;
    private Map<String, String> mHeader;
    private List<HttpCookie> mCookies;
    private Context mContext;

    private UriDataSourceDesc() {
    }

    /**
     * Return the Uri of this data source.
     * @return the Uri of this data source
     */
    public Uri getUri() {
        return mUri;
    }

    /**
     * Return the Uri headers of this data source.
     * @return the Uri headers of this data source
     */
    public Map<String, String> getHeaders() {
        if (mHeader == null) {
            return null;
        }
        return new HashMap<String, String>(mHeader);
    }

    /**
     * Return the Uri cookies of this data source.
     * @return the Uri cookies of this data source
     */
    public List<HttpCookie> getCookies() {
        if (mCookies == null) {
            return null;
        }
        return new ArrayList<HttpCookie>(mCookies);
    }

    /**
     * Return the Context used for resolving the Uri of this data source.
     * @return the Context used for resolving the Uri of this data source
     */
    public Context getContext() {
        return mContext;
    }

    /**
     * Builder class for {@link UriDataSourceDesc} objects.
     * <p> Here is an example where <code>Builder</code> is used to define the
     * {@link UriDataSourceDesc} to be used by a {@link MediaPlayer2} instance:
     *
     * <pre class="prettyprint">
     * UriDataSourceDesc newDSD = new UriDataSourceDesc.Builder()
     *         .setDataSource(context, uri, headers, cookies)
     *         .setStartPosition(1000)
     *         .setEndPosition(15000)
     *         .build();
     * mediaplayer2.setDataSourceDesc(newDSD);
     * </pre>
     */
    public static class Builder extends BuilderBase<Builder> {
        private Uri mUri;
        private Map<String, String> mHeader;
        private List<HttpCookie> mCookies;
        private Context mContext;

        /**
         * Constructs a new Builder with the defaults.
         */
        public Builder() {
            super();
        }

        /**
         * Constructs a new Builder from a given {@link UriDataSourceDesc} instance
         * @param dsd the {@link UriDataSourceDesc} object whose data will be reused
         * in the new Builder.
         */
        public Builder(UriDataSourceDesc dsd) {
            super(dsd);
            if (dsd == null) {
                return;  // use default
            }
            mUri = dsd.mUri;
            mHeader = dsd.mHeader;
            mCookies = dsd.mCookies;
            mContext = dsd.mContext;
        }

        /**
         * Combines all of the fields that have been set and return a new
         * {@link UriDataSourceDesc} object. <code>IllegalStateException</code> will be
         * thrown if there is conflict between fields.
         *
         * @return a new {@link UriDataSourceDesc} object
         */
        public @NonNull UriDataSourceDesc build() {
            UriDataSourceDesc dsd = new UriDataSourceDesc();
            super.build(dsd);
            dsd.mUri = mUri;
            dsd.mHeader = mHeader;
            dsd.mCookies = mCookies;
            dsd.mContext = mContext;

            return dsd;
        }

        /**
         * Sets the data source as a content Uri.
         *
         * @param context the Context to use when resolving the Uri
         * @param uri the Content URI of the data you want to play
         * @return the same Builder instance.
         * @throws NullPointerException if context or uri is null.
         */
        public @NonNull Builder setDataSource(@NonNull Context context, @NonNull Uri uri) {
            Media2Utils.checkArgument(context != null, "context cannot be null");
            Media2Utils.checkArgument(uri != null, "uri cannot be null");
            resetDataSource();
            mUri = uri;
            mContext = context;
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
        public @NonNull Builder setDataSource(@NonNull Context context, @NonNull Uri uri,
                @Nullable Map<String, String> headers, @Nullable List<HttpCookie> cookies) {
            Media2Utils.checkArgument(context != null, "context cannot be null");
            Media2Utils.checkArgument(uri != null, "uri cannot be null");
            if (cookies != null) {
                CookieHandler cookieHandler = CookieHandler.getDefault();
                if (cookieHandler != null && !(cookieHandler instanceof CookieManager)) {
                    throw new IllegalArgumentException(
                            "The cookie handler has to be of CookieManager type "
                            + "when cookies are provided.");
                }
            }

            resetDataSource();
            mUri = uri;
            if (headers != null) {
                mHeader = new HashMap<String, String>(headers);
            }
            if (cookies != null) {
                mCookies = new ArrayList<HttpCookie>(cookies);
            }
            mContext = context;
            return this;
        }

        private void resetDataSource() {
            mUri = null;
            mHeader = null;
            mCookies = null;
            mContext = null;
        }
    }
}
