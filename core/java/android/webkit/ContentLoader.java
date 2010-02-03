/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.net.http.Headers;
import android.net.Uri;

/**
 * This class is a concrete implementation of StreamLoader that loads
 * "content:" URIs
 */
class ContentLoader extends StreamLoader {

    private String mUrl;
    private String mContentType;

    /**
     * Construct a ContentLoader with the specified content URI
     *
     * @param rawUrl "content:" url pointing to content to be loaded. This url
     *               is the same url passed in to the WebView.
     * @param loadListener LoadListener to pass the content to
     */
    ContentLoader(String rawUrl, LoadListener loadListener) {
        super(loadListener);

        /* strip off mimetype */
        int mimeIndex = rawUrl.lastIndexOf('?');
        if (mimeIndex != -1) {
            mUrl = rawUrl.substring(0, mimeIndex);
            mContentType = rawUrl.substring(mimeIndex + 1);
        } else {
            mUrl = rawUrl;
        }

    }

    private String errString(Exception ex) {
        String exMessage = ex.getMessage();
        String errString = mContext.getString(
                com.android.internal.R.string.httpErrorFileNotFound);
        if (exMessage != null) {
            errString += " " + exMessage;
        }
        return errString;
    }

    @Override
    protected boolean setupStreamAndSendStatus() {
        Uri uri = Uri.parse(mUrl);
        if (uri == null) {
            mLoadListener.error(
                    EventHandler.FILE_NOT_FOUND_ERROR,
                    mContext.getString(
                            com.android.internal.R.string.httpErrorBadUrl) +
                    " " + mUrl);
            return false;
        }

        try {
            mDataStream = mContext.getContentResolver().openInputStream(uri);
            mLoadListener.status(1, 1, 200, "OK");
        } catch (java.io.FileNotFoundException ex) {
            mLoadListener.error(EventHandler.FILE_NOT_FOUND_ERROR, errString(ex));
            return false;
        } catch (RuntimeException ex) {
            // readExceptionWithFileNotFoundExceptionFromParcel in DatabaseUtils
            // can throw a serial of RuntimeException. Catch them all here.
            mLoadListener.error(EventHandler.FILE_ERROR, errString(ex));
            return false;
        }
        return true;
    }

    @Override
    protected void buildHeaders(Headers headers) {
        if (mContentType != null) {
            headers.setContentType("text/html");
        }
        // content can change, we don't want WebKit to cache it
        headers.setCacheControl("no-store, no-cache");
    }
}
