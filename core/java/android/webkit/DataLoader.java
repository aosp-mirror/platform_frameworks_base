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

import android.net.http.EventHandler;

import com.android.internal.R;

import java.io.ByteArrayInputStream;

import org.apache.harmony.luni.util.Base64;

/**
 * This class is a concrete implementation of StreamLoader that uses the
 * content supplied as a URL as the source for the stream. The mimetype
 * optionally provided in the URL is extracted and inserted into the HTTP
 * response headers.
 */
class DataLoader extends StreamLoader {

    /**
     * Constructor uses the dataURL as the source for an InputStream
     * @param dataUrl data: URL string optionally containing a mimetype
     * @param loadListener LoadListener to pass the content to
     */
    DataLoader(String dataUrl, LoadListener loadListener) {
        super(loadListener);

        String url = dataUrl.substring("data:".length());
        byte[] data = null;
        int commaIndex = url.indexOf(',');
        if (commaIndex != -1) {
            String contentType = url.substring(0, commaIndex);
            data = url.substring(commaIndex + 1).getBytes();
            loadListener.parseContentTypeHeader(contentType);
            if ("base64".equals(loadListener.transferEncoding())) {
                data = Base64.decode(data);
            }
        } else {
            data = url.getBytes();
        }
        if (data != null) {
            mDataStream = new ByteArrayInputStream(data);
            mContentLength = data.length;
        }
    }

    @Override
    protected boolean setupStreamAndSendStatus() {
        if (mDataStream != null) {
            mLoadListener.status(1, 1, 200, "OK");
            return true;
        } else {
            mLoadListener.error(EventHandler.ERROR,
                    mContext.getString(R.string.httpError));
            return false;
        }
    }

    @Override
    protected void buildHeaders(android.net.http.Headers h) {
    }
}
