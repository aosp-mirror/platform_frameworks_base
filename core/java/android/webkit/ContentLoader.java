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

import android.content.Context;
import android.net.http.EventHandler;
import android.net.http.Headers;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;

/**
 * This class is a concrete implementation of StreamLoader that loads
 * "content:" URIs
 */
class ContentLoader extends StreamLoader {

    private String mUrl;
    private Context mContext;
    private String mContentType;

    /**
     * Construct a ContentLoader with the specified content URI
     *
     * @param rawUrl "content:" url pointing to content to be loaded. This url
     *               is the same url passed in to the WebView.
     * @param loadListener LoadListener to pass the content to
     * @param context Context to use to access the asset.
     */
    ContentLoader(String rawUrl, LoadListener loadListener, Context context) {
        super(loadListener);
        mContext = context;

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
            mHandler.error(
                    EventHandler.FILE_NOT_FOUND_ERROR,
                    mContext.getString(
                            com.android.internal.R.string.httpErrorBadUrl) +
                    " " + mUrl);
            return false;
        }

        try {
            mDataStream = mContext.getContentResolver().openInputStream(uri);
            mHandler.status(1, 1, 0, "OK");
        } catch (java.io.FileNotFoundException ex) {
            mHandler.error(EventHandler.FILE_NOT_FOUND_ERROR, errString(ex));
            return false;

        } catch (java.io.IOException ex) {
            mHandler.error(EventHandler.FILE_ERROR, errString(ex));
            return false;
        } catch (RuntimeException ex) {
            // readExceptionWithFileNotFoundExceptionFromParcel in DatabaseUtils
            // can throw a serial of RuntimeException. Catch them all here.
            mHandler.error(EventHandler.FILE_ERROR, errString(ex));
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

    /**
     * Construct a ContentLoader and instruct it to start loading.
     *
     * @param url "content:" url pointing to content to be loaded
     * @param loadListener LoadListener to pass the content to
     * @param context Context to use to access the asset.
     */
    public static void requestUrl(String url, LoadListener loadListener,
            Context context) {
        ContentLoader loader = new ContentLoader(url, loadListener, context);
        loader.load();
    }

}
