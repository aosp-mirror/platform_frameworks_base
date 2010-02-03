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

import com.android.internal.R;

import android.content.res.AssetManager;
import android.net.http.EventHandler;
import android.net.http.Headers;
import android.util.Log;
import android.util.TypedValue;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;

/**
 * This class is a concrete implementation of StreamLoader that uses a
 * file or asset as the source for the stream.
 *
 */
class FileLoader extends StreamLoader {

    private String mPath;  // Full path to the file to load
    private int mType;  // Indicates the type of the load
    private boolean mAllowFileAccess; // Allow/block file system access

    // used for files under asset directory
    static final int TYPE_ASSET = 1;
    // used for files under res directory
    static final int TYPE_RES = 2;
    // generic file
    static final int TYPE_FILE = 3;

    private static final String LOGTAG = "webkit";

    /**
     * Construct a FileLoader with the file URL specified as the content
     * source.
     *
     * @param url Full file url pointing to content to be loaded
     * @param loadListener LoadListener to pass the content to
     * @param asset true if url points to an asset.
     * @param allowFileAccess true if this WebView is allowed to access files
     *                        on the file system.
     */
    FileLoader(String url, LoadListener loadListener, int type,
            boolean allowFileAccess) {
        super(loadListener);
        mType = type;
        mAllowFileAccess = allowFileAccess;

        // clean the Url
        int index = url.indexOf('?');
        if (mType == TYPE_ASSET) {
            mPath = index > 0 ? URLUtil.stripAnchor(
                    url.substring(URLUtil.ASSET_BASE.length(), index)) :
                    URLUtil.stripAnchor(url.substring(
                            URLUtil.ASSET_BASE.length()));
        } else if (mType == TYPE_RES) {
            mPath = index > 0 ? URLUtil.stripAnchor(
                    url.substring(URLUtil.RESOURCE_BASE.length(), index)) :
                    URLUtil.stripAnchor(url.substring(
                            URLUtil.RESOURCE_BASE.length()));
        } else {
            mPath = index > 0 ? URLUtil.stripAnchor(
                    url.substring(URLUtil.FILE_BASE.length(), index)) :
                    URLUtil.stripAnchor(url.substring(
                            URLUtil.FILE_BASE.length()));
        }
    }

    private String errString(Exception ex) {
        String exMessage = ex.getMessage();
        String errString = mContext.getString(R.string.httpErrorFileNotFound);
        if (exMessage != null) {
            errString += " " + exMessage;
        }
        return errString;
    }

    @Override
    protected boolean setupStreamAndSendStatus() {
        try {
            if (mType == TYPE_ASSET) {
                try {
                    mDataStream = mContext.getAssets().open(mPath);
                } catch (java.io.FileNotFoundException ex) {
                    // try the rest files included in the package
                    mDataStream = mContext.getAssets().openNonAsset(mPath);
                }
            } else if (mType == TYPE_RES) {
                // get the resource id from the path. e.g. for the path like
                // drawable/foo.png, the id is located at field "foo" of class
                // "<package>.R$drawable"
                if (mPath == null || mPath.length() == 0) {
                    Log.e(LOGTAG, "Need a path to resolve the res file");
                    mLoadListener.error(EventHandler.FILE_ERROR, mContext
                            .getString(R.string.httpErrorFileNotFound));
                    return false;

                }
                int slash = mPath.indexOf('/');
                int dot = mPath.indexOf('.', slash);
                if (slash == -1 || dot == -1) {
                    Log.e(LOGTAG, "Incorrect res path: " + mPath);
                    mLoadListener.error(EventHandler.FILE_ERROR, mContext
                            .getString(R.string.httpErrorFileNotFound));
                    return false;
                }
                String subClassName = mPath.substring(0, slash);
                String fieldName = mPath.substring(slash + 1, dot);
                String errorMsg = null;
                try {
                    final Class<?> d = mContext.getApplicationContext()
                            .getClassLoader().loadClass(
                                    mContext.getPackageName() + ".R$"
                                            + subClassName);
                    final Field field = d.getField(fieldName);
                    final int id = field.getInt(null);
                    TypedValue value = new TypedValue();
                    mContext.getResources().getValue(id, value, true);
                    if (value.type == TypedValue.TYPE_STRING) {
                        mDataStream = mContext.getAssets().openNonAsset(
                                value.assetCookie, value.string.toString(),
                                AssetManager.ACCESS_STREAMING);
                    } else {
                        errorMsg = "Only support TYPE_STRING for the res files";
                    }
                } catch (ClassNotFoundException e) {
                    errorMsg = "Can't find class:  "
                            + mContext.getPackageName() + ".R$" + subClassName;
                } catch (SecurityException e) {
                    errorMsg = "Caught SecurityException: " + e;
                } catch (NoSuchFieldException e) {
                    errorMsg = "Can't find field:  " + fieldName + " in "
                            + mContext.getPackageName() + ".R$" + subClassName;
                } catch (IllegalArgumentException e) {
                    errorMsg = "Caught IllegalArgumentException: " + e;
                } catch (IllegalAccessException e) {
                    errorMsg = "Caught IllegalAccessException: " + e;
                }
                if (errorMsg != null) {
                    mLoadListener.error(EventHandler.FILE_ERROR, mContext
                            .getString(R.string.httpErrorFileNotFound));
                    return false;
                }
            } else {
                if (!mAllowFileAccess) {
                    mLoadListener.error(EventHandler.FILE_ERROR,
                            mContext.getString(R.string.httpErrorFileNotFound));
                    return false;
                }

                mDataStream = new FileInputStream(mPath);
                mContentLength = (new File(mPath)).length();
            }
            mLoadListener.status(1, 1, 200, "OK");

        } catch (java.io.FileNotFoundException ex) {
            mLoadListener.error(EventHandler.FILE_NOT_FOUND_ERROR, errString(ex));
            return false;

        } catch (java.io.IOException ex) {
            mLoadListener.error(EventHandler.FILE_ERROR, errString(ex));
            return false;
        }
        return true;
    }

    @Override
    protected void buildHeaders(Headers headers) {
        // do nothing.
    }
}
