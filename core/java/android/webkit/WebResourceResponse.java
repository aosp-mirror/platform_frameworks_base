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
 * Encapsulates a resource response. Applications can return an instance of this
 * class from {@link WebViewClient#shouldInterceptRequest} to provide a custom
 * response when the WebView requests a particular resource.
 */
public class WebResourceResponse {
    // Accessed by jni, do not rename without modifying the jni code.
    private String mMimeType;
    private String mEncoding;
    private InputStream mInputStream;

    /**
     * Constructs a resource response with the given MIME type, encoding, and
     * input stream. Callers must implement
     * {@link InputStream#read(byte[]) InputStream.read(byte[])} for the input
     * stream.
     *
     * @param mimeType the resource response's MIME type, for example text/html
     * @param encoding the resource response's encoding
     * @param data the input stream that provides the resource response's data
     */
    public WebResourceResponse(String mimeType, String encoding,
            InputStream data) {
        mMimeType = mimeType;
        mEncoding = encoding;
        mInputStream = data;
    }

    /**
     * Sets the resource response's MIME type, for example text/html.
     *
     * @param mimeType the resource response's MIME type
     */
    public void setMimeType(String mimeType) {
        mMimeType = mimeType;
    }

    /**
     * Gets the resource response's MIME type.
     *
     * @return the resource response's MIME type
     */
    public String getMimeType() {
        return mMimeType;
    }

    /**
     * Sets the resource response's encoding, for example UTF-8. This is used
     * to decode the data from the input stream.
     *
     * @param encoding the resource response's encoding
     */
    public void setEncoding(String encoding) {
        mEncoding = encoding;
    }

    /**
     * Gets the resource response's encoding.
     *
     * @return the resource response's encoding
     */
    public String getEncoding() {
        return mEncoding;
    }

    /**
     * Sets the input stream that provides the resource respone's data. Callers
     * must implement {@link InputStream#read(byte[]) InputStream.read(byte[])}.
     *
     * @param data the input stream that provides the resource response's data
     */
    public void setData(InputStream data) {
        mInputStream = data;
    }

    /**
     * Gets the input stream that provides the resource respone's data.
     *
     * @return the input stream that provides the resource response's data
     */
    public InputStream getData() {
        return mInputStream;
    }
}
