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

import org.apache.http.protocol.HTTP;

import android.net.http.Headers;

import java.io.ByteArrayInputStream;

/**
 * This class is a concrete implementation of StreamLoader that uses the
 * content supplied as a URL as the source for the stream. The mimetype
 * optionally provided in the URL is extracted and inserted into the HTTP
 * response headers.
 */
class DataLoader extends StreamLoader {

    private String mContentType;  // Content mimetype, if supplied in URL

    /**
     * Constructor uses the dataURL as the source for an InputStream
     * @param dataUrl data: URL string optionally containing a mimetype
     * @param loadListener LoadListener to pass the content to
     */
    DataLoader(String dataUrl, LoadListener loadListener) {
        super(loadListener);

        String url = dataUrl.substring("data:".length());
        String content;
        int commaIndex = url.indexOf(',');
        if (commaIndex != -1) {
            mContentType = url.substring(0, commaIndex);
            content = url.substring(commaIndex + 1);
        } else {
            content = url;
        }
        mDataStream = new ByteArrayInputStream(content.getBytes());
        mContentLength = content.length();
    }

    @Override
    protected boolean setupStreamAndSendStatus() {
        mHandler.status(1, 1, 0, "OK");
        return true;
    }

    @Override
    protected void buildHeaders(Headers headers) {
        if (mContentType != null) {
            headers.setContentType(mContentType);
        }
    }

    /**
     * Construct a DataLoader and instruct it to start loading.
     *
     * @param url data: URL string optionally containing a mimetype
     * @param loadListener LoadListener to pass the content to
     */
    public static void requestUrl(String url, LoadListener loadListener) {
        DataLoader loader = new DataLoader(url, loadListener);
        loader.load();
    }

}
