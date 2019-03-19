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

import android.annotation.UnsupportedAppUsage;
import android.net.NetworkUtils;
import android.os.IBinder;
import android.os.StrictMode;
import android.util.Log;

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
import java.util.concurrent.atomic.AtomicReference;

/** @hide */
public class MediaHTTPConnection extends IMediaHTTPConnection.Stub {
    private static final String TAG = "MediaHTTPConnection";
    private static final boolean VERBOSE = false;

    // connection timeout - 30 sec
    private static final int CONNECT_TIMEOUT_MS = 30 * 1000;

    // from com.squareup.okhttp.internal.http
    private final static int HTTP_TEMP_REDIRECT = 307;
    private final static int MAX_REDIRECTS = 20;

    class ConnectionState {
        public HttpURLConnection mConnection = null;
        public InputStream mInputStream = null;
        public long mCurrentOffset = -1;
        public Map<String, String> mHeaders = null;
        public URL mURL = null;
        public long mTotalSize = -1;
        public boolean mAllowCrossDomainRedirect = true;
        public boolean mAllowCrossProtocolRedirect = true;
    }
    private final AtomicReference<ConnectionState> mConnectionStateHolder =
            new AtomicReference<ConnectionState>();

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
    public IBinder connect(String uri, String headers) {
        if (VERBOSE) {
            Log.d(TAG, "connect: uri=" + uri + ", headers=" + headers);
        }

        ConnectionState connectionState = mConnectionStateHolder.get();
        synchronized (this) {
            if (connectionState == null) {
                connectionState = new ConnectionState();
                mConnectionStateHolder.set(connectionState);
            }
        }

        try {
            disconnect();
            connectionState.mAllowCrossDomainRedirect = true;
            connectionState.mURL = new URL(uri);
            connectionState.mHeaders = convertHeaderStringToMap(headers, connectionState);
        } catch (MalformedURLException e) {
            return null;
        } finally {
            mConnectionStateHolder.set(connectionState);
        }

        return native_getIMemory();
    }

    private boolean parseBoolean(String val) {
        try {
            return Long.parseLong(val) != 0;
        } catch (NumberFormatException e) {
            return "true".equalsIgnoreCase(val) ||
                "yes".equalsIgnoreCase(val);
        }
    }

    /* returns true iff header is internal */
    private boolean filterOutInternalHeaders(
            String key, String val, ConnectionState connectionState) {
        if ("android-allow-cross-domain-redirect".equalsIgnoreCase(key)) {
            connectionState.mAllowCrossDomainRedirect = parseBoolean(val);
            // cross-protocol redirects are also controlled by this flag
            connectionState.mAllowCrossProtocolRedirect =
                    connectionState.mAllowCrossDomainRedirect;
        } else {
            return false;
        }
        return true;
    }

    private Map<String, String> convertHeaderStringToMap(String headers,
            ConnectionState connectionState) {
        HashMap<String, String> map = new HashMap<String, String>();

        String[] pairs = headers.split("\r\n");
        for (String pair : pairs) {
            int colonPos = pair.indexOf(":");
            if (colonPos >= 0) {
                String key = pair.substring(0, colonPos);
                String val = pair.substring(colonPos + 1);

                if (!filterOutInternalHeaders(key, val, connectionState)) {
                    map.put(key, val);
                }
            }
        }

        return map;
    }

    @Override
    @UnsupportedAppUsage
    public void disconnect() {
        ConnectionState connectionState = mConnectionStateHolder.getAndSet(null);
        if (connectionState != null) {
            teardownConnection(connectionState);
            connectionState.mHeaders = null;
            connectionState.mURL = null;
        }
    }

    private void teardownConnection(ConnectionState connectionState) {
        if (connectionState.mConnection != null) {
            if (connectionState.mInputStream != null) {
                try {
                    connectionState.mInputStream.close();
                } catch (IOException e) {
                }
                connectionState.mInputStream = null;
            }

            connectionState.mConnection.disconnect();
            connectionState.mConnection = null;

            connectionState.mCurrentOffset = -1;
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
            if (NetworkUtils.numericToInetAddress(host).isLoopbackAddress()) {
                return true;
            }
        } catch (IllegalArgumentException iex) {
        }
        return false;
    }

    private void seekTo(long offset, ConnectionState connectionState) throws IOException {
        teardownConnection(connectionState);

        try {
            int response;
            int redirectCount = 0;

            URL url = connectionState.mURL;

            // do not use any proxy for localhost (127.0.0.1)
            boolean noProxy = isLocalHost(url);

            while (true) {
                if (noProxy) {
                    connectionState.mConnection =
                            (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                } else {
                    connectionState.mConnection = (HttpURLConnection) url.openConnection();
                }
                connectionState.mConnection.setConnectTimeout(CONNECT_TIMEOUT_MS);

                // handle redirects ourselves if we do not allow cross-domain redirect
                connectionState.mConnection.setInstanceFollowRedirects(
                        connectionState.mAllowCrossDomainRedirect);

                if (connectionState.mHeaders != null) {
                    for (Map.Entry<String, String> entry : connectionState.mHeaders.entrySet()) {
                        connectionState.mConnection.setRequestProperty(
                                entry.getKey(), entry.getValue());
                    }
                }

                if (offset > 0) {
                    connectionState.mConnection.setRequestProperty(
                            "Range", "bytes=" + offset + "-");
                }

                response = connectionState.mConnection.getResponseCode();
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

                String method = connectionState.mConnection.getRequestMethod();
                if (response == HTTP_TEMP_REDIRECT &&
                        !method.equals("GET") && !method.equals("HEAD")) {
                    // "If the 307 status code is received in response to a
                    // request other than GET or HEAD, the user agent MUST NOT
                    // automatically redirect the request"
                    throw new NoRouteToHostException("Invalid redirect");
                }
                String location = connectionState.mConnection.getHeaderField("Location");
                if (location == null) {
                    throw new NoRouteToHostException("Invalid redirect");
                }
                url = new URL(connectionState.mURL /* TRICKY: don't use url! */, location);
                if (!url.getProtocol().equals("https") &&
                        !url.getProtocol().equals("http")) {
                    throw new NoRouteToHostException("Unsupported protocol redirect");
                }
                boolean sameProtocol =
                        connectionState.mURL.getProtocol().equals(url.getProtocol());
                if (!connectionState.mAllowCrossProtocolRedirect && !sameProtocol) {
                    throw new NoRouteToHostException("Cross-protocol redirects are disallowed");
                }
                boolean sameHost = connectionState.mURL.getHost().equals(url.getHost());
                if (!connectionState.mAllowCrossDomainRedirect && !sameHost) {
                    throw new NoRouteToHostException("Cross-domain redirects are disallowed");
                }

                if (response != HTTP_TEMP_REDIRECT) {
                    // update effective URL, unless it is a Temporary Redirect
                    connectionState.mURL = url;
                }
            }

            if (connectionState.mAllowCrossDomainRedirect) {
                // remember the current, potentially redirected URL if redirects
                // were handled by HttpURLConnection
                connectionState.mURL = connectionState.mConnection.getURL();
            }

            if (response == HttpURLConnection.HTTP_PARTIAL) {
                // Partial content, we cannot just use getContentLength
                // because what we want is not just the length of the range
                // returned but the size of the full content if available.

                String contentRange = connectionState.mConnection.getHeaderField("Content-Range");

                connectionState.mTotalSize = -1;
                if (contentRange != null) {
                    // format is "bytes xxx-yyy/zzz
                    // where "zzz" is the total number of bytes of the
                    // content or '*' if unknown.

                    int lastSlashPos = contentRange.lastIndexOf('/');
                    if (lastSlashPos >= 0) {
                        String total =
                            contentRange.substring(lastSlashPos + 1);

                        try {
                            connectionState.mTotalSize = Long.parseLong(total);
                        } catch (NumberFormatException e) {
                        }
                    }
                }
            } else if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException();
            } else {
                connectionState.mTotalSize = connectionState.mConnection.getContentLength();
            }

            if (offset > 0 && response != HttpURLConnection.HTTP_PARTIAL) {
                // Some servers simply ignore "Range" requests and serve
                // data from the start of the content.
                throw new ProtocolException();
            }

            connectionState.mInputStream =
                new BufferedInputStream(connectionState.mConnection.getInputStream());

            connectionState.mCurrentOffset = offset;
        } catch (IOException e) {
            connectionState.mTotalSize = -1;
            teardownConnection(connectionState);
            connectionState.mCurrentOffset = -1;

            throw e;
        }
    }

    @Override
    @UnsupportedAppUsage
    public int readAt(long offset, int size) {
        ConnectionState connectionState = mConnectionStateHolder.get();
        if (connectionState != null) {
            return native_readAt(offset, size, connectionState);
        }
        return -1;
    }

    private int readAt(long offset, byte[] data, int size, ConnectionState connectionState) {
        StrictMode.ThreadPolicy policy =
            new StrictMode.ThreadPolicy.Builder().permitAll().build();

        StrictMode.setThreadPolicy(policy);

        try {
            synchronized(this) {
                if (offset != connectionState.mCurrentOffset) {
                    seekTo(offset, connectionState);
                }
            }

            int n = connectionState.mInputStream.read(data, 0, size);

            if (n == -1) {
                // InputStream signals EOS using a -1 result, our semantics
                // are to return a 0-length read.
                n = 0;
            }

            connectionState.mCurrentOffset += n;

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
        ConnectionState connectionState = mConnectionStateHolder.get();
        if (connectionState != null) {
            if (connectionState.mConnection == null) {
                try {
                    seekTo(0, connectionState);
                } catch (IOException e) {
                    return -1;
                }
            }
            return connectionState.mTotalSize;
        }

        return -1;
    }

    @Override
    @UnsupportedAppUsage
    public synchronized String getMIMEType() {
        ConnectionState connectionState = mConnectionStateHolder.get();
        if (connectionState != null) {
            if (connectionState.mConnection == null) {
                try {
                    seekTo(0, connectionState);
                } catch (IOException e) {
                    return "application/octet-stream";
                }
            }
            return connectionState.mConnection.getContentType();
        }

        return null;
    }

    @Override
    @UnsupportedAppUsage
    public String getUri() {
        ConnectionState connectionState = mConnectionStateHolder.get();
        if (connectionState != null) {
            return connectionState.mURL.toString();
        }
        return null;
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    private static native final void native_init();
    private native final void native_setup();
    private native final void native_finalize();

    private native final IBinder native_getIMemory();
    private native int native_readAt(long offset, int size, ConnectionState connectionState);

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private long mNativeContext;

}
