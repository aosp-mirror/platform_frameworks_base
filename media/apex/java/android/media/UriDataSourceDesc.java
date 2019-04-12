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
 * @hide
 */
public class UriDataSourceDesc extends DataSourceDesc {
    private Uri mUri;
    private Map<String, String> mHeader;
    private List<HttpCookie> mCookies;

    UriDataSourceDesc(String mediaId, long startPositionMs, long endPositionMs,
            Uri uri, Map<String, String> header, List<HttpCookie> cookies) {
        super(mediaId, startPositionMs, endPositionMs);
        mUri = uri;
        mHeader = header;
        mCookies = cookies;
    }

    /**
     * Return the Uri of this data source.
     * @return the Uri of this data source
     */
    public @NonNull Uri getUri() {
        return mUri;
    }

    /**
     * Return the Uri headers of this data source.
     * @return the Uri headers of this data source
     */
    public @Nullable Map<String, String> getHeaders() {
        if (mHeader == null) {
            return null;
        }
        return new HashMap<String, String>(mHeader);
    }

    /**
     * Return the Uri cookies of this data source.
     * @return the Uri cookies of this data source
     */
    public @Nullable List<HttpCookie> getCookies() {
        if (mCookies == null) {
            return null;
        }
        return new ArrayList<HttpCookie>(mCookies);
    }
}
