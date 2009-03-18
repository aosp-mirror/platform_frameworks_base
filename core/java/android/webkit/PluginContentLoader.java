/*
 * Copyright (C) 2009 The Android Open Source Project
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

import java.io.InputStream;
import java.util.*;

import org.apache.http.util.CharArrayBuffer;

/**
 * This class is a concrete implementation of StreamLoader that uses a
 * PluginData object as the source for the stream.
 */
class PluginContentLoader extends StreamLoader {

    private PluginData mData;  // Content source

    /**
     * Constructs a PluginDataLoader for use when loading content from
     * a plugin.
     *
     * @param loadListener LoadListener to pass the content to
     * @param data PluginData used as the source for the content.
     */
    PluginContentLoader(LoadListener loadListener, PluginData data) {
        super(loadListener);
        mData = data;
    }

    @Override
    protected boolean setupStreamAndSendStatus() {
        mDataStream = mData.getInputStream();
        mContentLength = mData.getContentLength();
        mHandler.status(1, 1, mData.getStatusCode(), "OK");
        return true;
    }

    @Override
    protected void buildHeaders(Headers headers) {
        // Crate a CharArrayBuffer with an arbitrary initial capacity.
        CharArrayBuffer buffer = new CharArrayBuffer(100);
        Iterator<Map.Entry<String, String[]>> responseHeadersIt =
                mData.getHeaders().entrySet().iterator();
        while (responseHeadersIt.hasNext()) {
            Map.Entry<String, String[]> entry = responseHeadersIt.next();
            // Headers.parseHeader() expects lowercase keys, so keys
            // such as "Accept-Ranges" will fail to parse.
            //
            // UrlInterceptHandler instances supply a mapping of
            // lowercase key to [ unmodified key, value ], so for
            // Headers.parseHeader() to succeed, we need to construct
            // a string using the key (i.e. entry.getKey()) and the
            // element denoting the header value in the
            // [ unmodified key, value ] pair (i.e. entry.getValue()[1).
            //
            // The reason why UrlInterceptHandler instances supply such a
            // mapping in the first place is historical. Early versions of
            // the Gears plugin used java.net.HttpURLConnection, which always
            // returned headers names as capitalized strings. When these were
            // fed back into webkit, they failed to parse.
            //
            // Mewanwhile, Gears was modified to use Apache HTTP library
            // instead, so this design is now obsolete. Changing it however,
            // would require changes to the Gears C++ codebase and QA-ing and
            // submitting a new binary to the Android tree. Given the
            // timelines for the next Android release, we will not do this
            // for now.
            //
            // TODO: fix C++ Gears to remove the need for this
            // design.
            String keyValue = entry.getKey() + ": " + entry.getValue()[1];
            buffer.ensureCapacity(keyValue.length());
            buffer.append(keyValue);
            // Parse it into the header container.
            headers.parseHeader(buffer);
            // Clear the buffer
            buffer.clear();
        }
    }
}
