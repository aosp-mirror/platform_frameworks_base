/*
 * Copyright (C) 2010 The Android Open Source Project
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

/**
 * A WebResourceResponse is return by
 * {@link WebViewClient#shouldInterceptRequest} and
 * contains the response information for a particular resource.
 */
public class WebResourceResponse {

    private class Loader extends StreamLoader {
        Loader(LoadListener loadListener) {
            super(loadListener);
            mDataStream = mInputStream;
        }
        @Override
        protected boolean setupStreamAndSendStatus() {
            mLoadListener.status(1, 1, mDataStream != null ? 200 : 404, "");
            return true;
        }
        @Override
        protected void buildHeaders(Headers headers) {
            headers.setContentType(mMimeType);
            headers.setContentEncoding(mEncoding);
        }
    }

    // Accessed by jni, do not rename without modifying the jni code.
    private String mMimeType;
    private String mEncoding;
    private InputStream mInputStream;

    /**
     * Construct a response with the given mime type, encoding, and data.
     * @param mimeType The mime type of the data (i.e. text/html).
     * @param encoding The encoding of the bytes read from data.
     * @param data An InputStream for reading custom data.  The implementation
     *             must implement {@link InputStream#read(byte[])}.
     */
    public WebResourceResponse(String mimeType, String encoding,
            InputStream data) {
        mMimeType = mimeType;
        mEncoding = encoding;
        mInputStream = data;
    }

    /**
     * Set the mime type of the response data (i.e. text/html).
     * @param mimeType
     */
    public void setMimeType(String mimeType) {
        mMimeType = mimeType;
    }

    /**
     * @see #setMimeType
     */
    public String getMimeType() {
        return mMimeType;
    }

    /**
     * Set the encoding of the response data (i.e. utf-8).  This will be used to
     * decode the raw bytes from the input stream.
     * @param encoding
     */
    public void setEncoding(String encoding) {
        mEncoding = encoding;
    }

    /**
     * @see #setEncoding
     */
    public String getEncoding() {
        return mEncoding;
    }

    /**
     * Set the input stream containing the data for this resource.
     * @param data An InputStream for reading custom data.  The implementation
     *             must implement {@link InputStream#read(byte[])}.
     */
    public void setData(InputStream data) {
        mInputStream = data;
    }

    /**
     * @see #setData
     */
    public InputStream getData() {
        return mInputStream;
    }

    StreamLoader loader(LoadListener listener) {
        return new Loader(listener);
    }
}
