/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.os.IBinder;
import android.os.StrictMode;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

/** @hide */
public class MediaHTTPConnection extends IMediaHTTPConnection.Stub {
    private static final String TAG = "MediaHTTPConnection";
    private static final boolean VERBOSE = false;

    private long mCurrentOffset = -1;
    private URL mURL = null;
    private Map<String, String> mHeaders = null;
    private HttpURLConnection mConnection = null;
    private long mTotalSize = -1;
    private InputStream mInputStream = null;

    public MediaHTTPConnection() {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(new CookieManager());
        }

        native_setup();
    }

    @Override
    public IBinder connect(String uri, String headers) {
        if (VERBOSE) {
            Log.d(TAG, "connect: uri=" + uri + ", headers=" + headers);
        }

        try {
            disconnect();
            mURL = new URL(uri);
            mHeaders = convertHeaderStringToMap(headers);
        } catch (MalformedURLException e) {
            return null;
        }

        return native_getIMemory();
    }

    private Map<String, String> convertHeaderStringToMap(String headers) {
        HashMap<String, String> map = new HashMap<String, String>();

        String[] pairs = headers.split("\r\n");
        for (String pair : pairs) {
            int colonPos = pair.indexOf(":");
            if (colonPos >= 0) {
                String key = pair.substring(0, colonPos);
                String val = pair.substring(colonPos + 1);

                map.put(key, val);
            }
        }

        return map;
    }

    @Override
    public void disconnect() {
        teardownConnection();
        mHeaders = null;
        mURL = null;
    }

    private void teardownConnection() {
        if (mConnection != null) {
            mInputStream = null;

            mConnection.disconnect();
            mConnection = null;

            mCurrentOffset = -1;
        }
    }

    private void seekTo(long offset) throws IOException {
        teardownConnection();

        try {
            mConnection = (HttpURLConnection)mURL.openConnection();

            if (mHeaders != null) {
                for (Map.Entry<String, String> entry : mHeaders.entrySet()) {
                    mConnection.setRequestProperty(
                            entry.getKey(), entry.getValue());
                }
            }

            if (offset > 0) {
                mConnection.setRequestProperty(
                        "Range", "bytes=" + offset + "-");
            }

            int response = mConnection.getResponseCode();
            // remember the current, possibly redirected URL
            mURL = mConnection.getURL();

            if (response == HttpURLConnection.HTTP_PARTIAL) {
                // Partial content, we cannot just use getContentLength
                // because what we want is not just the length of the range
                // returned but the size of the full content if available.

                String contentRange =
                    mConnection.getHeaderField("Content-Range");

                mTotalSize = -1;
                if (contentRange != null) {
                    // format is "bytes xxx-yyy/zzz
                    // where "zzz" is the total number of bytes of the
                    // content or '*' if unknown.

                    int lastSlashPos = contentRange.lastIndexOf('/');
                    if (lastSlashPos >= 0) {
                        String total =
                            contentRange.substring(lastSlashPos + 1);

                        try {
                            mTotalSize = Long.parseLong(total);
                        } catch (NumberFormatException e) {
                        }
                    }
                }
            } else if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException();
            } else {
                mTotalSize = mConnection.getContentLength();
            }

            if (offset > 0 && response != HttpURLConnection.HTTP_PARTIAL) {
                // Some servers simply ignore "Range" requests and serve
                // data from the start of the content.
                throw new IOException();
            }

            mInputStream =
                new BufferedInputStream(mConnection.getInputStream());

            mCurrentOffset = offset;
        } catch (IOException e) {
            mTotalSize = -1;
            mInputStream = null;
            mConnection = null;
            mCurrentOffset = -1;

            throw e;
        }
    }

    @Override
    public int readAt(long offset, int size) {
        return native_readAt(offset, size);
    }

    private int readAt(long offset, byte[] data, int size) {
        StrictMode.ThreadPolicy policy =
            new StrictMode.ThreadPolicy.Builder().permitAll().build();

        StrictMode.setThreadPolicy(policy);

        try {
            if (offset != mCurrentOffset) {
                seekTo(offset);
            }

            int n = mInputStream.read(data, 0, size);

            if (n == -1) {
                // InputStream signals EOS using a -1 result, our semantics
                // are to return a 0-length read.
                n = 0;
            }

            mCurrentOffset += n;

            if (VERBOSE) {
                Log.d(TAG, "readAt " + offset + " / " + size + " => " + n);
            }

            return n;
        } catch (IOException e) {
            if (VERBOSE) {
                Log.d(TAG, "readAt " + offset + " / " + size + " => -1");
            }
            return -1;
        } catch (Exception e) {
            if (VERBOSE) {
                Log.d(TAG, "unknown exception " + e);
                Log.d(TAG, "readAt " + offset + " / " + size + " => -1");
            }
            return -1;
        }
    }

    @Override
    public long getSize() {
        if (mConnection == null) {
            try {
                seekTo(0);
            } catch (IOException e) {
                return -1;
            }
        }

        return mTotalSize;
    }

    @Override
    public String getMIMEType() {
        if (mConnection == null) {
            try {
                seekTo(0);
            } catch (IOException e) {
                return "application/octet-stream";
            }
        }

        return mConnection.getContentType();
    }

    @Override
    public String getUri() {
        return mURL.toString();
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    private static native final void native_init();
    private native final void native_setup();
    private native final void native_finalize();

    private native final IBinder native_getIMemory();
    private native final int native_readAt(long offset, int size);

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private long mNativeContext;

}
