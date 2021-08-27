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

import static android.media.MediaPlayer.MEDIA_ERROR_UNSUPPORTED;

import android.compat.annotation.UnsupportedAppUsage;
import android.net.InetAddresses;
import android.os.IBinder;
import android.os.StrictMode;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.net.UnknownServiceException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** @hide */
public class MediaHTTPConnection extends IMediaHTTPConnection.Stub {
    private static final String TAG = "MediaHTTPConnection";
    private static final boolean VERBOSE = false;

    // connection timeout - 30 sec
    private static final int CONNECT_TIMEOUT_MS = 30 * 1000;

    @GuardedBy("this")
    @UnsupportedAppUsage
    private long mCurrentOffset = -1;

    @GuardedBy("this")
    @UnsupportedAppUsage
    private URL mURL = null;

    @GuardedBy("this")
    @UnsupportedAppUsage
    private Map<String, String> mHeaders = null;

    // volatile so that disconnect() can be called without acquiring a lock.
    // All other access is @GuardedBy("this").
    @UnsupportedAppUsage
    private volatile HttpURLConnection mConnection = null;

    @GuardedBy("this")
    @UnsupportedAppUsage
    private long mTotalSize = -1;

    @GuardedBy("this")
    private InputStream mInputStream = null;

    @GuardedBy("this")
    @UnsupportedAppUsage
    private boolean mAllowCrossDomainRedirect = true;

    @GuardedBy("this")
    @UnsupportedAppUsage
    private boolean mAllowCrossProtocolRedirect = true;

    // from com.squareup.okhttp.internal.http
    private final static int HTTP_TEMP_REDIRECT = 307;
    private final static int MAX_REDIRECTS = 20;

    // The number of threads that are currently running disconnect() (possibly
    // not yet holding the synchronized lock).
    private final AtomicInteger mNumDisconnectingThreads = new AtomicInteger(0);

    @UnsupportedAppUsage
    public MediaHTTPConnection() {
        CookieHandler cookieHandler = CookieHandler.getDefault();
        if (cookieHandler == null) {
            Log.w(TAG, "MediaHTTPConnection: Unexpected. No CookieHandler found.");
        }

        native_setup();
    }

    @Override
    @UnsupportedAppUsage
    public synchronized IBinder connect(String uri, String headers) {
        if (VERBOSE) {
            Log.d(TAG, "connect: uri=" + uri + ", headers=" + headers);
        }

        try {
            disconnect();
            mAllowCrossDomainRedirect = true;
            mURL = new URL(uri);
            mHeaders = convertHeaderStringToMap(headers);
        } catch (MalformedURLException e) {
            return null;
        }

        return native_getIMemory();
    }

    private static boolean parseBoolean(String val) {
        try {
            return Long.parseLong(val) != 0;
        } catch (NumberFormatException e) {
            return "true".equalsIgnoreCase(val) ||
                "yes".equalsIgnoreCase(val);
        }
    }

    /* returns true iff header is internal */
    private synchronized boolean filterOutInternalHeaders(String key, String val) {
        if ("android-allow-cross-domain-redirect".equalsIgnoreCase(key)) {
            mAllowCrossDomainRedirect = parseBoolean(val);
            // cross-protocol redirects are also controlled by this flag
            mAllowCrossProtocolRedirect = mAllowCrossDomainRedirect;
        } else {
            return false;
        }
        return true;
    }

    private synchronized Map<String, String> convertHeaderStringToMap(String headers) {
        HashMap<String, String> map = new HashMap<String, String>();

        String[] pairs = headers.split("\r\n");
        for (String pair : pairs) {
            int colonPos = pair.indexOf(":");
            if (colonPos >= 0) {
                String key = pair.substring(0, colonPos);
                String val = pair.substring(colonPos + 1);

                if (!filterOutInternalHeaders(key, val)) {
                    map.put(key, val);
                }
            }
        }

        return map;
    }

    @Override
    @UnsupportedAppUsage
    public void disconnect() {
        mNumDisconnectingThreads.incrementAndGet();
        try {
            HttpURLConnection connectionToDisconnect = mConnection;
            // Call disconnect() before blocking for the lock in order to ensure that any
            // other thread that is blocked in readAt() will return quickly.
            if (connectionToDisconnect != null) {
                connectionToDisconnect.disconnect();
            }
            synchronized (this) {
                // It's possible that while we were waiting to acquire the lock, another thread
                // concurrently started a new connection; if so, we're disconnecting that one
                // here, too.
                teardownConnection();
                mHeaders = null;
                mURL = null;
            }
        } finally {
            mNumDisconnectingThreads.decrementAndGet();
        }
    }

    private synchronized void teardownConnection() {
        if (mConnection != null) {
            if (mInputStream != null) {
                try {
                    mInputStream.close();
                } catch (IOException e) {
                }
                mInputStream = null;
            }

            mConnection.disconnect();
            mConnection = null;

            mCurrentOffset = -1;
        }
    }

    private static final boolean isLocalHost(URL url) {
        if (url == null) {
            return false;
        }

        String host = url.getHost();

        if (host == null) {
            return false;
        }

        try {
            if (host.equalsIgnoreCase("localhost")) {
                return true;
            }
            if (InetAddresses.parseNumericAddress(host).isLoopbackAddress()) {
                return true;
            }
        } catch (IllegalArgumentException iex) {
        }
        return false;
    }

    private synchronized void seekTo(long offset) throws IOException {
        teardownConnection();

        try {
            int response;
            int redirectCount = 0;

            URL url = mURL;

            // do not use any proxy for localhost (127.0.0.1)
            boolean noProxy = isLocalHost(url);

            while (true) {
                // If another thread is concurrently disconnect()ing, there's a race
                // between them and us. Therefore, we check mNumDisconnectingThreads shortly
                // (not atomically) before & after writing mConnection. This guarantees that
                // we won't "lose" a disconnect by creating a new connection that might
                // miss the disconnect.
                //
                // Note that throwing an instanceof IOException is also what this thread
                // would have done if another thread disconnect()ed the connection while
                // this thread was blocked reading from that connection further down in this
                // loop.
                if (mNumDisconnectingThreads.get() > 0) {
                    throw new IOException("concurrently disconnecting");
                }
                if (noProxy) {
                    mConnection = (HttpURLConnection)url.openConnection(Proxy.NO_PROXY);
                } else {
                    mConnection = (HttpURLConnection)url.openConnection();
                }
                // If another thread is concurrently disconnecting, throwing IOException will
                // cause us to release the lock, giving the other thread a chance to acquire
                // it. It also ensures that the catch block will run, which will tear down
                // the connection even if the other thread happens to already be on its way
                // out of disconnect().
                if (mNumDisconnectingThreads.get() > 0) {
                    throw new IOException("concurrently disconnecting");
                }
                // If we get here without having thrown, we know that other threads
                // will see our write to mConnection. Any disconnect() on that mConnection
                // instance will cause our read from/write to that connection instance below
                // to encounter an instanceof IOException.
                mConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);

                // handle redirects ourselves if we do not allow cross-domain redirect
                mConnection.setInstanceFollowRedirects(mAllowCrossDomainRedirect);

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

                response = mConnection.getResponseCode();
                if (response != HttpURLConnection.HTTP_MULT_CHOICE &&
                        response != HttpURLConnection.HTTP_MOVED_PERM &&
                        response != HttpURLConnection.HTTP_MOVED_TEMP &&
                        response != HttpURLConnection.HTTP_SEE_OTHER &&
                        response != HTTP_TEMP_REDIRECT) {
                    // not a redirect, or redirect handled by HttpURLConnection
                    break;
                }

                if (++redirectCount > MAX_REDIRECTS) {
                    throw new NoRouteToHostException("Too many redirects: " + redirectCount);
                }

                String method = mConnection.getRequestMethod();
                if (response == HTTP_TEMP_REDIRECT &&
                        !method.equals("GET") && !method.equals("HEAD")) {
                    // "If the 307 status code is received in response to a
                    // request other than GET or HEAD, the user agent MUST NOT
                    // automatically redirect the request"
                    throw new NoRouteToHostException("Invalid redirect");
                }
                String location = mConnection.getHeaderField("Location");
                if (location == null) {
                    throw new NoRouteToHostException("Invalid redirect");
                }
                url = new URL(mURL /* TRICKY: don't use url! */, location);
                if (!url.getProtocol().equals("https") &&
                        !url.getProtocol().equals("http")) {
                    throw new NoRouteToHostException("Unsupported protocol redirect");
                }
                boolean sameProtocol = mURL.getProtocol().equals(url.getProtocol());
                if (!mAllowCrossProtocolRedirect && !sameProtocol) {
                    throw new NoRouteToHostException("Cross-protocol redirects are disallowed");
                }
                boolean sameHost = mURL.getHost().equals(url.getHost());
                if (!mAllowCrossDomainRedirect && !sameHost) {
                    throw new NoRouteToHostException("Cross-domain redirects are disallowed");
                }

                if (response != HTTP_TEMP_REDIRECT) {
                    // update effective URL, unless it is a Temporary Redirect
                    mURL = url;
                }
            }

            if (mAllowCrossDomainRedirect) {
                // remember the current, potentially redirected URL if redirects
                // were handled by HttpURLConnection
                mURL = mConnection.getURL();
            }

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
                throw new ProtocolException();
            }

            mInputStream =
                new BufferedInputStream(mConnection.getInputStream());

            mCurrentOffset = offset;
        } catch (IOException e) {
            mTotalSize = -1;
            teardownConnection();
            mCurrentOffset = -1;

            throw e;
        }
    }

    @Override
    @UnsupportedAppUsage
    public synchronized int readAt(long offset, int size) {
        return native_readAt(offset, size);
    }

    private synchronized int readAt(long offset, byte[] data, int size) {
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
        } catch (ProtocolException e) {
            Log.w(TAG, "readAt " + offset + " / " + size + " => " + e);
            return MEDIA_ERROR_UNSUPPORTED;
        } catch (NoRouteToHostException e) {
            Log.w(TAG, "readAt " + offset + " / " + size + " => " + e);
            return MEDIA_ERROR_UNSUPPORTED;
        } catch (UnknownServiceException e) {
            Log.w(TAG, "readAt " + offset + " / " + size + " => " + e);
            return MEDIA_ERROR_UNSUPPORTED;
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
    public synchronized long getSize() {
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
    @UnsupportedAppUsage
    public synchronized String getMIMEType() {
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
    @UnsupportedAppUsage
    public synchronized String getUri() {
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
