/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import android.net.http.Headers;

/**
 * This class is a concrete implementation of StreamLoader that uses a
 * CacheResult as the source for the stream. The CacheResult stored mimetype
 * and encoding is added to the HTTP response headers.
 */
class CacheLoader extends StreamLoader {

    CacheManager.CacheResult mCacheResult;  // Content source

    /**
     * Constructs a CacheLoader for use when loading content from the cache.
     *
     * @param loadListener LoadListener to pass the content to
     * @param result CacheResult used as the source for the content.
     */
    CacheLoader(LoadListener loadListener, CacheManager.CacheResult result) {
        super(loadListener);
        mCacheResult = result;
    }

    @Override
    protected boolean setupStreamAndSendStatus() {
        mDataStream = mCacheResult.inStream;
        mContentLength = mCacheResult.contentLength;
        mHandler.status(1, 1, mCacheResult.httpStatusCode, "OK");
        return true;
    }

    @Override
    protected void buildHeaders(Headers headers) {
        StringBuilder sb = new StringBuilder(mCacheResult.mimeType);
        if (mCacheResult.encoding != null &&
                mCacheResult.encoding.length() > 0) {
            sb.append(';');
            sb.append(mCacheResult.encoding);
        }
        headers.setContentType(sb.toString());

        if (mCacheResult.location != null &&
                mCacheResult.location.length() > 0) {
            headers.setLocation(mCacheResult.location);
        }
    }

}
