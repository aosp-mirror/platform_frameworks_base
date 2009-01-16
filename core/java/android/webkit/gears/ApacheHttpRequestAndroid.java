// Copyright 2008, The Android Open Source Project
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
//  1. Redistributions of source code must retain the above copyright notice,
//     this list of conditions and the following disclaimer.
//  2. Redistributions in binary form must reproduce the above copyright notice,
//     this list of conditions and the following disclaimer in the documentation
//     and/or other materials provided with the distribution.
//  3. Neither the name of Google Inc. nor the names of its contributors may be
//     used to endorse or promote products derived from this software without
//     specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
// WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
// EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
// PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
// OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
// WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
// OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
// ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package android.webkit.gears;

import android.net.http.Headers;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Config;
import android.util.Log;
import android.webkit.CacheManager;
import android.webkit.CacheManager.CacheResult;
import android.webkit.CookieManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.lang.StringBuilder;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.HttpResponse;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.client.*;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.conn.ssl.StrictHostnameVerifier;
import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.util.CharArrayBuffer;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Performs the underlying HTTP/HTTPS GET, POST, HEAD, PUT, DELETE requests.
 * <p> These are performed synchronously (blocking). The caller should
 * ensure that it is in a background thread if asynchronous behavior
 * is required. All data is pushed, so there is no need for JNI native
 * callbacks.
 * <p> This uses Apache's HttpClient framework to perform most
 * of the underlying network activity. The Android brower's cache,
 * android.webkit.CacheManager, is also used when caching is enabled,
 * and updated with new data. The android.webkit.CookieManager is also
 * queried and updated as necessary.
 * <p> The public interface is designed to be called by native code
 * through JNI, and to simplify coding none of the public methods will
 * surface a checked exception. Unchecked exceptions may still be
 * raised but only if the system is in an ill state, such as out of
 * memory.
 * <p> TODO: This isn't plumbed into LocalServer yet. Mutually
 * dependent on LocalServer - will attach the two together once both
 * are submitted.
 */
public final class ApacheHttpRequestAndroid {
    /** Debug logging tag. */
    private static final String LOG_TAG = "Gears-J";
    /** HTTP response header line endings are CR-LF style. */
    private static final String HTTP_LINE_ENDING = "\r\n";
    /** Safe MIME type to use whenever it isn't specified. */
    private static final String DEFAULT_MIME_TYPE = "text/plain";
    /** Case-sensitive header keys */
    public static final String KEY_CONTENT_LENGTH = "Content-Length";
    public static final String KEY_EXPIRES = "Expires";
    public static final String KEY_LAST_MODIFIED = "Last-Modified";
    public static final String KEY_ETAG = "ETag";
    public static final String KEY_LOCATION = "Location";
    public static final String KEY_CONTENT_TYPE = "Content-Type";
    /** Number of bytes to send and receive on the HTTP connection in
     * one go. */
    private static final int BUFFER_SIZE = 4096;

    /** The first element of the String[] value in a headers map is the
     * unmodified (case-sensitive) key. */
    public static final int HEADERS_MAP_INDEX_KEY = 0;
    /** The second element of the String[] value in a headers map is the
     * associated value. */
    public static final int HEADERS_MAP_INDEX_VALUE = 1;

    /** Request headers, as key -> value map. */
    // TODO: replace this design by a simpler one (the C++ side has to
    // be modified too), where we do not store both the original header
    // and the lowercase one.
    private Map<String, String[]> mRequestHeaders =
        new HashMap<String, String[]>();
    /** Response headers, as a lowercase key -> value map. */
    private Map<String, String[]> mResponseHeaders =
        new HashMap<String, String[]>();
    /** The URL used for createCacheResult() */
    private String mCacheResultUrl;
    /** CacheResult being saved into, if inserting a new cache entry. */
    private CacheResult mCacheResult;
    /** Initialized by initChildThread(). Used to target abort(). */
    private Thread mBridgeThread;

    /** Our HttpClient */
    private AbstractHttpClient mClient;
    /** The HttpMethod associated with this request */
    private HttpRequestBase mMethod;
    /** The complete response line e.g "HTTP/1.0 200 OK" */
    private String mResponseLine;
    /** HTTP body stream, setup after connection. */
    private InputStream mBodyInputStream;

    /** HTTP Response Entity */
    private HttpResponse mResponse;

    /** Post Entity, used to stream the request to the server */
    private StreamEntity mPostEntity = null;
    /** Content lenght, mandatory when using POST */
    private long mContentLength;

    /** The request executes in a parallel thread */
    private Thread mHttpThread = null;
    /** protect mHttpThread, if interrupt() is called concurrently */
    private Lock mHttpThreadLock = new ReentrantLock();
    /** Flag set to true when the request thread is joined */
    private boolean mConnectionFinished = false;
    /** Flag set to true by interrupt() and/or connection errors */
    private boolean mConnectionFailed = false;
    /** Lock protecting the access to mConnectionFailed */
    private Lock mConnectionFailedLock = new ReentrantLock();

    /** Lock on the loop in StreamEntity */
    private Lock mStreamingReadyLock = new ReentrantLock();
    /** Condition variable used to signal the loop is ready... */
    private Condition mStreamingReady = mStreamingReadyLock.newCondition();

    /** Used to pass around the block of data POSTed */
    private Buffer mBuffer = new Buffer();
    /** Used to signal that the block of data has been written */
    private SignalConsumed mSignal = new SignalConsumed();

    // inner classes

    /**
     * Implements the http request
     */
    class Connection implements Runnable {
        public void run() {
            boolean problem = false;
            try {
                if (Config.LOGV) {
                    Log.i(LOG_TAG, "REQUEST : " + mMethod.getRequestLine());
                }
                mResponse = mClient.execute(mMethod);
                if (mResponse != null) {
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "response (status line): "
                              + mResponse.getStatusLine());
                    }
                    mResponseLine = "" + mResponse.getStatusLine();
                } else {
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "problem, response == null");
                    }
                    problem = true;
                }
            } catch (IOException e) {
                Log.e(LOG_TAG, "Connection IO exception ", e);
                problem = true;
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "Connection runtime exception ", e);
                problem = true;
            }

            if (!problem) {
                if (Config.LOGV) {
                    Log.i(LOG_TAG, "Request complete ("
                          + mMethod.getRequestLine() + ")");
                }
            } else {
                mConnectionFailedLock.lock();
                mConnectionFailed = true;
                mConnectionFailedLock.unlock();
                if (Config.LOGV) {
                    Log.i(LOG_TAG, "Request FAILED ("
                          + mMethod.getRequestLine() + ")");
                }
                // We abort the execution in order to shutdown and release
                // the underlying connection
                mMethod.abort();
                if (mPostEntity != null) {
                    // If there is a post entity, we need to wake it up from
                    // a potential deadlock
                    mPostEntity.signalOutputStream();
                }
            }
        }
    }

    /**
     * simple buffer class implementing a producer/consumer model
     */
    class Buffer {
        private DataPacket mPacket;
        private boolean mEmpty = true;
        public synchronized void put(DataPacket packet) {
            while (!mEmpty) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "InterruptedException while putting " +
                            "a DataPacket in the Buffer: " + e);
                    }
                }
            }
            mPacket = packet;
            mEmpty = false;
            notify();
        }
        public synchronized DataPacket get() {
            while (mEmpty) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    if (Config.LOGV) {
                      Log.i(LOG_TAG, "InterruptedException while getting " +
                          "a DataPacket in the Buffer: " + e);
                    }
                }
            }
            mEmpty = true;
            notify();
            return mPacket;
        }
    }

    /**
     * utility class used to block until the packet is signaled as being
     * consumed
     */
    class SignalConsumed {
        private boolean mConsumed = false;
        public synchronized void waitUntilPacketConsumed() {
            while (!mConsumed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "InterruptedException while waiting " +
                            "until a DataPacket is consumed: " + e);
                    }
                }
            }
            mConsumed = false;
            notify();
        }
        public synchronized void packetConsumed() {
            while (mConsumed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "InterruptedException while indicating "
                              + "that the DataPacket has been consumed: " + e);
                    }
                }
            }
            mConsumed = true;
            notify();
        }
    }

    /**
     * Utility class encapsulating a packet of data
     */
    class DataPacket {
        private byte[] mContent;
        private int mLength;
        public DataPacket(byte[] content, int length) {
            mContent = content;
            mLength = length;
        }
        public byte[] getBytes() {
            return mContent;
        }
        public int getLength() {
            return mLength;
        }
    }

    /**
     * HttpEntity class to write the bytes received by the C++ thread
     * on the connection outputstream, in a streaming way.
     * This entity is executed in the request thread.
     * The writeTo() method is automatically called by the
     * HttpPost execution; upon reception, we loop while receiving
     * the data packets from the main thread, until completion
     * or error. When done, we flush the outputstream.
     * The main thread (sendPostData()) also blocks until the
     * outputstream is made available (or an error happens)
     */
    class StreamEntity implements HttpEntity {
        private OutputStream mOutputStream;

        // HttpEntity interface methods

        public boolean isRepeatable() {
            return false;
        }

        public boolean isChunked() {
            return false;
        }

        public long getContentLength() {
            return mContentLength;
        }

        public Header getContentType() {
            return null;
        }

        public Header getContentEncoding() {
            return null;
        }

        public InputStream getContent() throws IOException {
            return null;
        }

        public void writeTo(final OutputStream out) throws IOException {
            // We signal that the outputstream is available
            mStreamingReadyLock.lock();
            mOutputStream = out;
            mStreamingReady.signal();
            mStreamingReadyLock.unlock();

            // We then loop waiting on messages to process.
            boolean finished = false;
            while (!finished) {
                DataPacket packet = mBuffer.get();
                if (packet == null) {
                    finished = true;
                } else {
                    write(packet);
                }
                mSignal.packetConsumed();
                mConnectionFailedLock.lock();
                if (mConnectionFailed) {
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "stopping loop on error");
                    }
                    finished = true;
                }
                mConnectionFailedLock.unlock();
            }
            if (Config.LOGV) {
                Log.i(LOG_TAG, "flushing the outputstream...");
            }
            mOutputStream.flush();
        }

        public boolean isStreaming() {
            return true;
        }

        public void consumeContent() throws IOException {
            // Nothing to release
        }

        // local methods

        private void write(DataPacket packet) {
            try {
                if (mOutputStream == null) {
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "NO OUTPUT STREAM !!!");
                    }
                    return;
                }
                mOutputStream.write(packet.getBytes(), 0, packet.getLength());
                mOutputStream.flush();
            } catch (IOException e) {
                if (Config.LOGV) {
                    Log.i(LOG_TAG, "exc: " + e);
                }
                mConnectionFailedLock.lock();
                mConnectionFailed = true;
                mConnectionFailedLock.unlock();
            }
        }

        public boolean isReady() {
            mStreamingReadyLock.lock();
            try {
                if (mOutputStream == null) {
                    mStreamingReady.await();
                }
            } catch (InterruptedException e) {
                if (Config.LOGV) {
                    Log.i(LOG_TAG, "InterruptedException in "
                          + "StreamEntity::isReady() : ", e);
                }
            } finally {
                mStreamingReadyLock.unlock();
            }
            if (mOutputStream == null) {
                return false;
            }
            return true;
        }

        public void signalOutputStream() {
            mStreamingReadyLock.lock();
            mStreamingReady.signal();
            mStreamingReadyLock.unlock();
        }
    }

    /**
     * Initialize mBridgeThread using the TLS value of
     * Thread.currentThread(). Called on start up of the native child
     * thread.
     */
    public synchronized void initChildThread() {
        mBridgeThread = Thread.currentThread();
    }

    public void setContentLength(long length) {
        mContentLength = length;
    }

    /**
     * Analagous to the native-side HttpRequest::open() function. This
     * initializes an underlying HttpClient method, but does
     * not go to the wire. On success, this enables a call to send() to
     * initiate the transaction.
     *
     * @param method    The HTTP method, e.g GET or POST.
     * @param url       The URL to open.
     * @return          True on success with a complete HTTP response.
     *                  False on failure.
     */
    public synchronized boolean open(String method, String url) {
        if (Config.LOGV) {
            Log.i(LOG_TAG, "open " + method + " " + url);
        }
        // Create the client
        if (mConnectionFailed) {
            // interrupt() could have been called even before open()
            return false;
        }
        mClient = new DefaultHttpClient();
        mClient.setHttpRequestRetryHandler(
            new DefaultHttpRequestRetryHandler(0, false));
        mBodyInputStream = null;
        mResponseLine = null;
        mResponseHeaders = null;
        mPostEntity = null;
        mHttpThread = null;
        mConnectionFailed = false;
        mConnectionFinished = false;

        // Create the method. We support everything that
        // Apache HttpClient supports, apart from TRACE.
        if ("GET".equalsIgnoreCase(method)) {
            mMethod = new HttpGet(url);
        } else if ("POST".equalsIgnoreCase(method)) {
            mMethod = new HttpPost(url);
            mPostEntity = new StreamEntity();
            ((HttpPost)mMethod).setEntity(mPostEntity);
        } else if ("HEAD".equalsIgnoreCase(method)) {
            mMethod = new HttpHead(url);
        } else if ("PUT".equalsIgnoreCase(method)) {
            mMethod = new HttpPut(url);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            mMethod = new HttpDelete(url);
        } else {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "Method " + method + " not supported");
            }
            return false;
        }
        HttpParams params = mClient.getParams();
        // We handle the redirections C++-side
        HttpClientParams.setRedirecting(params, false);
        HttpProtocolParams.setUseExpectContinue(params, false);
        return true;
    }

    /**
     * We use this to start the connection thread (doing the method execute).
     * We usually always return true here, as the connection will run its
     * course in the thread.
     * We only return false if interrupted beforehand -- if a connection
     * problem happens, we will thus fail in either sendPostData() or
     * parseHeaders().
     */
    public synchronized boolean connectToRemote() {
        boolean ret = false;
        applyRequestHeaders();
        mConnectionFailedLock.lock();
        if (!mConnectionFailed) {
            mHttpThread = new Thread(new Connection());
            mHttpThread.start();
        }
        ret = mConnectionFailed;
        mConnectionFailedLock.unlock();
        return !ret;
    }

    /**
     * Get the complete response line of the HTTP request. Only valid on
     * completion of the transaction.
     * @return The complete HTTP response line, e.g "HTTP/1.0 200 OK".
     */
    public synchronized String getResponseLine() {
        return mResponseLine;
    }

    /**
     * Wait for the request thread completion
     * (unless already finished)
     */
    private void waitUntilConnectionFinished() {
        if (Config.LOGV) {
            Log.i(LOG_TAG, "waitUntilConnectionFinished("
                  + mConnectionFinished + ")");
        }
        if (!mConnectionFinished) {
            if (mHttpThread != null) {
                try {
                    mHttpThread.join();
                    mConnectionFinished = true;
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "http thread joined");
                    }
                } catch (InterruptedException e) {
                    if (Config.LOGV) {
                        Log.i(LOG_TAG, "interrupted: " + e);
                    }
                }
            } else {
                Log.e(LOG_TAG, ">>> Trying to join on mHttpThread " +
                      "when it does not exist!");
            }
        }
    }

    // Headers handling

    /**
     * Receive all headers from the server and populate
     * mResponseHeaders.
     * @return True if headers are successfully received, False on
     *         connection error.
     */
    public synchronized boolean parseHeaders() {
        mConnectionFailedLock.lock();
        if (mConnectionFailed) {
            mConnectionFailedLock.unlock();
            return false;
        }
        mConnectionFailedLock.unlock();
        waitUntilConnectionFinished();
        mResponseHeaders = new HashMap<String, String[]>();
        if (mResponse == null)
            return false;

        Header[] headers = mResponse.getAllHeaders();
        for (int i = 0; i < headers.length; i++) {
            Header header = headers[i];
            if (Config.LOGV) {
                Log.i(LOG_TAG, "header " + header.getName()
                      + " -> " + header.getValue());
            }
            setResponseHeader(header.getName(), header.getValue());
        }

        return true;
    }

    /**
     * Set a header to send with the HTTP request. Will not take effect
     * on a transaction already in progress. The key is associated
     * case-insensitive, but stored case-sensitive.
     * @param name  The name of the header, e.g "Set-Cookie".
     * @param value The value for this header, e.g "text/html".
     */
    public synchronized void setRequestHeader(String name, String value) {
        String[] mapValue = { name, value };
        if (Config.LOGV) {
            Log.i(LOG_TAG, "setRequestHeader: " + name + " => " + value);
        }
        if (name.equalsIgnoreCase(KEY_CONTENT_LENGTH)) {
            setContentLength(Long.parseLong(value));
        } else {
            mRequestHeaders.put(name.toLowerCase(), mapValue);
        }
    }

    /**
     * Returns the value associated with the given request header.
     * @param name The name of the request header, non-null, case-insensitive.
     * @return The value associated with the request header, or null if
     *         not set, or error.
     */
    public synchronized String getRequestHeader(String name) {
        String[] value = mRequestHeaders.get(name.toLowerCase());
        if (value != null) {
            return value[HEADERS_MAP_INDEX_VALUE];
        } else {
            return null;
        }
    }

    private void applyRequestHeaders() {
        if (mMethod == null)
            return;
        Iterator<String[]> it = mRequestHeaders.values().iterator();
        while (it.hasNext()) {
            // Set the key case-sensitive.
            String[] entry = it.next();
            if (Config.LOGV) {
                Log.i(LOG_TAG, "apply header " + entry[HEADERS_MAP_INDEX_KEY] +
                    " => " + entry[HEADERS_MAP_INDEX_VALUE]);
            }
            mMethod.setHeader(entry[HEADERS_MAP_INDEX_KEY],
                                     entry[HEADERS_MAP_INDEX_VALUE]);
        }
    }

    /**
     * Returns the value associated with the given response header.
     * @param name The name of the response header, non-null, case-insensitive.
     * @return The value associated with the response header, or null if
     *         not set or error.
     */
    public synchronized String getResponseHeader(String name) {
        if (mResponseHeaders != null) {
            String[] value = mResponseHeaders.get(name.toLowerCase());
            if (value != null) {
                return value[HEADERS_MAP_INDEX_VALUE];
            } else {
                return null;
            }
        } else {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "getResponseHeader() called but "
                      + "response not received");
            }
            return null;
        }
    }

    /**
     * Return all response headers, separated by CR-LF line endings, and
     * ending with a trailing blank line. This mimics the format of the
     * raw response header up to but not including the body.
     * @return A string containing the entire response header.
     */
    public synchronized String getAllResponseHeaders() {
        if (mResponseHeaders == null) {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "getAllResponseHeaders() called but "
                      + "response not received");
            }
            return null;
        }
        StringBuilder result = new StringBuilder();
        Iterator<String[]> it = mResponseHeaders.values().iterator();
        while (it.hasNext()) {
            String[] entry = it.next();
            // Output the "key: value" lines.
            result.append(entry[HEADERS_MAP_INDEX_KEY]);
            result.append(": ");
            result.append(entry[HEADERS_MAP_INDEX_VALUE]);
            result.append(HTTP_LINE_ENDING);
        }
        result.append(HTTP_LINE_ENDING);
        return result.toString();
    }


    /**
     * Set a response header and associated value. The key is associated
     * case-insensitively, but stored case-sensitively.
     * @param name  Case sensitive request header key.
     * @param value The associated value.
     */
    private void setResponseHeader(String name, String value) {
        if (Config.LOGV) {
            Log.i(LOG_TAG, "Set response header " + name + ": " + value);
        }
        String mapValue[] = { name, value };
        mResponseHeaders.put(name.toLowerCase(), mapValue);
    }

    // Cookie handling

    /**
     * Get the cookie for the given URL.
     * @param url The fully qualified URL.
     * @return A string containing the cookie for the URL if it exists,
     *         or null if not.
     */
    public static String getCookieForUrl(String url) {
        // Get the cookie for this URL, set as a header
        return CookieManager.getInstance().getCookie(url);
    }

    /**
     * Set the cookie for the given URL.
     * @param url    The fully qualified URL.
     * @param cookie The new cookie value.
     * @return A string containing the cookie for the URL if it exists,
     *         or null if not.
     */
    public static void setCookieForUrl(String url, String cookie) {
        // Get the cookie for this URL, set as a header
        CookieManager.getInstance().setCookie(url, cookie);
    }

    // Cache handling

    /**
     * Perform a request using LocalServer if possible. Initializes
     * class members so that receive() will obtain data from the stream
     * provided by the response.
     * @param url The fully qualified URL to try in LocalServer.
     * @return True if the url was found and is now setup to receive.
     *         False if not found, with no side-effect.
     */
    public synchronized boolean useLocalServerResult(String url) {
        UrlInterceptHandlerGears handler =
            UrlInterceptHandlerGears.getInstance();
        if (handler == null) {
            return false;
        }
        UrlInterceptHandlerGears.ServiceResponse serviceResponse =
            handler.getServiceResponse(url, mRequestHeaders);
        if (serviceResponse == null) {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "No response in LocalServer");
            }
            return false;
        }
        // LocalServer will handle this URL. Initialize stream and
        // response.
        mBodyInputStream = serviceResponse.getInputStream();
        mResponseLine = serviceResponse.getStatusLine();
        mResponseHeaders = serviceResponse.getResponseHeaders();
        if (Config.LOGV) {
            Log.i(LOG_TAG, "Got response from LocalServer: " + mResponseLine);
        }
        return true;
    }

    /**
     * Perform a request using the cache result if present. Initializes
     * class members so that receive() will obtain data from the cache.
     * @param url The fully qualified URL to try in the cache.
     * @return True is the url was found and is now setup to receive
     *         from cache. False if not found, with no side-effect.
     */
    public synchronized boolean useCacheResult(String url) {
        // Try the browser's cache. CacheManager wants a Map<String, String>.
        Map<String, String> cacheRequestHeaders = new HashMap<String, String>();
        Iterator<Map.Entry<String, String[]>> it =
            mRequestHeaders.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String[]> entry = it.next();
            cacheRequestHeaders.put(
                entry.getKey(),
                entry.getValue()[HEADERS_MAP_INDEX_VALUE]);
        }
        CacheResult mCacheResult =
            CacheManager.getCacheFile(url, cacheRequestHeaders);
        if (mCacheResult == null) {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "No CacheResult for " + url);
            }
            return false;
        }
        if (Config.LOGV) {
            Log.i(LOG_TAG, "Got CacheResult from browser cache");
        }
        // Check for expiry. -1 is "never", otherwise milliseconds since 1970.
        // Can be compared to System.currentTimeMillis().
        long expires = mCacheResult.getExpires();
        if (expires >= 0 && System.currentTimeMillis() >= expires) {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "CacheResult expired "
                    + (System.currentTimeMillis() - expires)
                    + " milliseconds ago");
            }
            // Cache hit has expired. Do not return it.
            return false;
        }
        // Setup the mBodyInputStream to come from the cache.
        mBodyInputStream = mCacheResult.getInputStream();
        if (mBodyInputStream == null) {
            // Cache result may have gone away.
            if (Config.LOGV) {
                Log.i(LOG_TAG, "No mBodyInputStream for CacheResult " + url);
            }
            return false;
        }
        // Cache hit. Parse headers.
        synthesizeHeadersFromCacheResult(mCacheResult);
        return true;
    }

    /**
     * Take the limited set of headers in a CacheResult and synthesize
     * response headers.
     * @param cacheResult A CacheResult to populate mResponseHeaders with.
     */
    private void synthesizeHeadersFromCacheResult(CacheResult cacheResult) {
        int statusCode = cacheResult.getHttpStatusCode();
        // The status message is informal, so we can greatly simplify it.
        String statusMessage;
        if (statusCode >= 200 && statusCode < 300) {
            statusMessage = "OK";
        } else if (statusCode >= 300 && statusCode < 400) {
            statusMessage = "MOVED";
        } else {
            statusMessage = "UNAVAILABLE";
        }
        // Synthesize the response line.
        mResponseLine = "HTTP/1.1 " + statusCode + " " + statusMessage;
        if (Config.LOGV) {
            Log.i(LOG_TAG, "Synthesized " + mResponseLine);
        }
        // Synthesize the returned headers from cache.
        mResponseHeaders = new HashMap<String, String[]>();
        String contentLength = Long.toString(cacheResult.getContentLength());
        setResponseHeader(KEY_CONTENT_LENGTH, contentLength);
        long expires = cacheResult.getExpires();
        if (expires >= 0) {
            // "Expires" header is valid and finite. Milliseconds since 1970
            // epoch, formatted as RFC-1123.
            String expiresString = DateUtils.formatDate(new Date(expires));
            setResponseHeader(KEY_EXPIRES, expiresString);
        }
        String lastModified = cacheResult.getLastModified();
        if (lastModified != null) {
            // Last modification time of the page. Passed end-to-end, but
            // not used by us.
            setResponseHeader(KEY_LAST_MODIFIED, lastModified);
        }
        String eTag = cacheResult.getETag();
        if (eTag != null) {
            // Entity tag. A kind of GUID to identify identical resources.
            setResponseHeader(KEY_ETAG, eTag);
        }
        String location = cacheResult.getLocation();
        if (location != null) {
            // If valid, refers to the location of a redirect.
            setResponseHeader(KEY_LOCATION, location);
        }
        String mimeType = cacheResult.getMimeType();
        if (mimeType == null) {
            // Use a safe default MIME type when none is
            // specified. "text/plain" is safe to render in the browser
            // window (even if large) and won't be intepreted as anything
            // that would cause execution.
            mimeType = DEFAULT_MIME_TYPE;
        }
        String encoding = cacheResult.getEncoding();
        // Encoding may not be specified. No default.
        String contentType = mimeType;
        if (encoding != null) {
            if (encoding.length() > 0) {
                contentType += "; charset=" + encoding;
            }
        }
        setResponseHeader(KEY_CONTENT_TYPE, contentType);
    }

    /**
     * Create a CacheResult for this URL. This enables the repsonse body
     * to be sent in calls to appendCacheResult().
     * @param url          The fully qualified URL to add to the cache.
     * @param responseCode The response code returned for the request, e.g 200.
     * @param mimeType     The MIME type of the body, e.g "text/plain".
     * @param encoding     The encoding, e.g "utf-8". Use "" for unknown.
     */
    public synchronized boolean createCacheResult(
        String url, int responseCode, String mimeType, String encoding) {
        if (Config.LOGV) {
            Log.i(LOG_TAG, "Making cache entry for " + url);
        }
        // Take the headers and parse them into a format needed by
        // CacheManager.
        Headers cacheHeaders = new Headers();
        Iterator<Map.Entry<String, String[]>> it =
            mResponseHeaders.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String[]> entry = it.next();
            // Headers.parseHeader() expects lowercase keys.
            String keyValue = entry.getKey() + ": "
                + entry.getValue()[HEADERS_MAP_INDEX_VALUE];
            CharArrayBuffer buffer = new CharArrayBuffer(keyValue.length());
            buffer.append(keyValue);
            // Parse it into the header container.
            cacheHeaders.parseHeader(buffer);
        }
        mCacheResult = CacheManager.createCacheFile(
            url, responseCode, cacheHeaders, mimeType, true);
        if (mCacheResult != null) {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "Saving into cache");
            }
            mCacheResult.setEncoding(encoding);
            mCacheResultUrl = url;
            return true;
        } else {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "Couldn't create mCacheResult");
            }
            return false;
        }
    }

    /**
     * Add data from the response body to the CacheResult created with
     * createCacheResult().
     * @param data  A byte array of the next sequential bytes in the
     *              response body.
     * @param bytes The number of bytes to write from the start of
     *              the array.
     * @return True if all bytes successfully written, false on failure.
     */
    public synchronized boolean appendCacheResult(byte[] data, int bytes) {
        if (mCacheResult == null) {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "appendCacheResult() called without a "
                      + "CacheResult initialized");
            }
            return false;
        }
        try {
            mCacheResult.getOutputStream().write(data, 0, bytes);
        } catch (IOException ex) {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "Got IOException writing cache data: " + ex);
            }
            return false;
        }
        return true;
    }

    /**
     * Save the completed CacheResult into the CacheManager. This must
     * have been created first with createCacheResult().
     * @return Returns true if the entry has been successfully saved.
     */
    public synchronized boolean saveCacheResult() {
        if (mCacheResult == null || mCacheResultUrl == null) {
            if (Config.LOGV) {
                Log.i(LOG_TAG, "Tried to save cache result but "
                      + "createCacheResult not called");
            }
            return false;
        }

        if (Config.LOGV) {
            Log.i(LOG_TAG, "Saving cache result");
        }
        CacheManager.saveCacheFile(mCacheResultUrl, mCacheResult);
        mCacheResult = null;
        mCacheResultUrl = null;
        return true;
    }


   /**
     * Interrupt a blocking IO operation. This will cause the child
     * thread to expediently return from an operation if it was stuck at
     * the time. Note that this inherently races, and unfortunately
     * requires the caller to loop.
     */
    public synchronized void interrupt() {
        if (Config.LOGV) {
            Log.i(LOG_TAG, "INTERRUPT CALLED");
        }
        mConnectionFailedLock.lock();
        mConnectionFailed = true;
        mConnectionFailedLock.unlock();
        if (mMethod != null) {
            mMethod.abort();
        }
        if (mHttpThread != null) {
            waitUntilConnectionFinished();
        }
    }

    /**
     * Receive the next sequential bytes of the response body after
     * successful connection. This will receive up to the size of the
     * provided byte array. If there is no body, this will return 0
     * bytes on the first call after connection.
     * @param  buf A pre-allocated byte array to receive data into.
     * @return The number of bytes from the start of the array which
     *         have been filled, 0 on EOF, or negative on error.
     */
    public synchronized int receive(byte[] buf) {
        if (mBodyInputStream == null) {
            // If this is the first call, setup the InputStream. This may
            // fail if there were headers, but no body returned by the
            // server.
            try {
                if (mResponse != null) {
                    HttpEntity entity = mResponse.getEntity();
                    mBodyInputStream = entity.getContent();
                }
            } catch (IOException inputException) {
                if (Config.LOGV) {
                    Log.i(LOG_TAG, "Failed to connect InputStream: "
                          + inputException);
                }
                // Not unexpected. For example, 404 response return headers,
                // and sometimes a body with a detailed error.
            }
            if (mBodyInputStream == null) {
                // No error stream either. Treat as a 0 byte response.
                if (Config.LOGV) {
                    Log.i(LOG_TAG, "No InputStream");
                }
                return 0; // EOF.
            }
        }
        int ret;
        try {
            int got = mBodyInputStream.read(buf);
            if (got > 0) {
                // Got some bytes, not EOF.
                ret = got;
            } else {
                // EOF.
                mBodyInputStream.close();
                ret = 0;
            }
        } catch (IOException e) {
            // An abort() interrupts us by calling close() on our stream.
            if (Config.LOGV) {
                Log.i(LOG_TAG, "Got IOException in mBodyInputStream.read(): ", e);
            }
            ret = -1;
        }
        return ret;
    }

    /**
     * For POST method requests, send a stream of data provided by the
     * native side in repeated callbacks.
     * We put the data in mBuffer, and wait until it is consumed
     * by the StreamEntity in the request thread.
     * @param data  A byte array containing the data to sent, or null
     *              if indicating EOF.
     * @param bytes The number of bytes from the start of the array to
     *              send, or 0 if indicating EOF.
     * @return True if all bytes were successfully sent, false on error.
     */
    public boolean sendPostData(byte[] data, int bytes) {
        mConnectionFailedLock.lock();
        if (mConnectionFailed) {
            mConnectionFailedLock.unlock();
            return false;
        }
        mConnectionFailedLock.unlock();
        if (mPostEntity == null) return false;

        // We block until the outputstream is available
        // (or in case of connection error)
        if (!mPostEntity.isReady()) return false;

        if (data == null && bytes == 0) {
            mBuffer.put(null);
        } else {
            mBuffer.put(new DataPacket(data, bytes));
        }
        mSignal.waitUntilPacketConsumed();

        mConnectionFailedLock.lock();
        if (mConnectionFailed) {
            Log.e(LOG_TAG, "failure");
            mConnectionFailedLock.unlock();
            return false;
        }
        mConnectionFailedLock.unlock();
        return true;
    }

}
