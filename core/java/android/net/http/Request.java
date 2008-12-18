/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.net.http;

import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;

import org.apache.http.entity.InputStreamEntity;
import org.apache.http.Header;
import org.apache.http.HttpClientConnection;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ParseException;
import org.apache.http.ProtocolVersion;

import org.apache.http.StatusLine;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.protocol.RequestContent;

/**
 * Represents an HTTP request for a given host.
 * 
 * {@hide}
 */

class Request {

    /** The eventhandler to call as the request progresses */
    EventHandler mEventHandler;

    private Connection mConnection;

    /** The Apache http request */
    BasicHttpRequest mHttpRequest;

    /** The path component of this request */
    String mPath;

    /** Host serving this request */
    HttpHost mHost;

    /** Set if I'm using a proxy server */
    HttpHost mProxyHost;

    /** True if request is .html, .js, .css */
    boolean mHighPriority;

    /** True if request has been cancelled */
    volatile boolean mCancelled = false;

    int mFailCount = 0;

    private InputStream mBodyProvider;
    private int mBodyLength;

    private final static String HOST_HEADER = "Host";
    private final static String ACCEPT_ENCODING_HEADER = "Accept-Encoding";
    private final static String CONTENT_LENGTH_HEADER = "content-length";

    /* Used to synchronize waitUntilComplete() requests */
    private final Object mClientResource = new Object();

    /**
     * Processor used to set content-length and transfer-encoding
     * headers.
     */
    private static RequestContent requestContentProcessor =
            new RequestContent();

    /**
     * Instantiates a new Request.
     * @param method GET/POST/PUT
     * @param host The server that will handle this request
     * @param path path part of URI
     * @param bodyProvider InputStream providing HTTP body, null if none
     * @param bodyLength length of body, must be 0 if bodyProvider is null
     * @param eventHandler request will make progress callbacks on
     * this interface
     * @param headers reqeust headers
     * @param highPriority true for .html, css, .cs
     */
    Request(String method, HttpHost host, HttpHost proxyHost, String path,
            InputStream bodyProvider, int bodyLength,
            EventHandler eventHandler,
            Map<String, String> headers, boolean highPriority) {
        mEventHandler = eventHandler;
        mHost = host;
        mProxyHost = proxyHost;
        mPath = path;
        mHighPriority = highPriority;
        mBodyProvider = bodyProvider;
        mBodyLength = bodyLength;

        if (bodyProvider == null) {
            mHttpRequest = new BasicHttpRequest(method, getUri());
        } else {
            mHttpRequest = new BasicHttpEntityEnclosingRequest(
                    method, getUri());
            setBodyProvider(bodyProvider, bodyLength);
        }
        addHeader(HOST_HEADER, getHostPort());

        /* FIXME: if webcore will make the root document a
           high-priority request, we can ask for gzip encoding only on
           high priority reqs (saving the trouble for images, etc) */
        addHeader(ACCEPT_ENCODING_HEADER, "gzip");
        addHeaders(headers);
    }

    /**
     * @param connection Request served by this connection
     */
    void setConnection(Connection connection) {
        mConnection = connection;
    }

    /* package */ EventHandler getEventHandler() {
        return mEventHandler;
    }

    /**
     * Add header represented by given pair to request.  Header will
     * be formatted in request as "name: value\r\n".
     * @param name of header
     * @param value of header
     */
    void addHeader(String name, String value) {
        if (name == null) {
            String damage = "Null http header name";
            HttpLog.e(damage);
            throw new NullPointerException(damage);
        }
        if (value == null || value.length() == 0) {
            String damage = "Null or empty value for header \"" + name + "\"";
            HttpLog.e(damage);
            throw new RuntimeException(damage);
        }
        mHttpRequest.addHeader(name, value);
    }

    /**
     * Add all headers in given map to this request.  This is a helper
     * method: it calls addHeader for each pair in the map.
     */
    void addHeaders(Map<String, String> headers) {
        if (headers == null) {
            return;
        }

        Entry<String, String> entry;
        Iterator<Entry<String, String>> i = headers.entrySet().iterator();
        while (i.hasNext()) {
            entry = i.next();
            addHeader(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Send the request line and headers
     */
    void sendRequest(AndroidHttpClientConnection httpClientConnection)
            throws HttpException, IOException {

        if (mCancelled) return; // don't send cancelled requests

        if (HttpLog.LOGV) {
            HttpLog.v("Request.sendRequest() " + mHost.getSchemeName() + "://" + getHostPort());
            // HttpLog.v(mHttpRequest.getRequestLine().toString());
            if (false) {
                Iterator i = mHttpRequest.headerIterator();
                while (i.hasNext()) {
                    Header header = (Header)i.next();
                    HttpLog.v(header.getName() + ": " + header.getValue());
                }
            }
        }

        requestContentProcessor.process(mHttpRequest,
                                        mConnection.getHttpContext());
        httpClientConnection.sendRequestHeader(mHttpRequest);
        if (mHttpRequest instanceof HttpEntityEnclosingRequest) {
            httpClientConnection.sendRequestEntity(
                    (HttpEntityEnclosingRequest) mHttpRequest);
        }

        if (HttpLog.LOGV) {
            HttpLog.v("Request.requestSent() " + mHost.getSchemeName() + "://" + getHostPort() + mPath);
        }
    }


    /**
     * Receive a single http response.
     *
     * @param httpClientConnection the request to receive the response for.
     */
    void readResponse(AndroidHttpClientConnection httpClientConnection)
            throws IOException, ParseException {

        if (mCancelled) return; // don't send cancelled requests

        StatusLine statusLine = null;
        boolean hasBody = false;
        boolean reuse = false;
        httpClientConnection.flush();
        int statusCode = 0;

        Headers header = new Headers();
        do {
            statusLine = httpClientConnection.parseResponseHeader(header);
            statusCode = statusLine.getStatusCode();
        } while (statusCode < HttpStatus.SC_OK);
        if (HttpLog.LOGV) HttpLog.v(
                "Request.readResponseStatus() " +
                statusLine.toString().length() + " " + statusLine);

        ProtocolVersion v = statusLine.getProtocolVersion();
        mEventHandler.status(v.getMajor(), v.getMinor(),
                statusCode, statusLine.getReasonPhrase());
        mEventHandler.headers(header);
        HttpEntity entity = null;
        hasBody = canResponseHaveBody(mHttpRequest, statusCode);

        if (hasBody)
            entity = httpClientConnection.receiveResponseEntity(header);

        if (entity != null) {
            InputStream is = entity.getContent();

            // process gzip content encoding
            Header contentEncoding = entity.getContentEncoding();
            InputStream nis = null;
            try {
                if (contentEncoding != null &&
                    contentEncoding.getValue().equals("gzip")) {
                    nis = new GZIPInputStream(is);
                } else {
                    nis = is;
                }

                /* accumulate enough data to make it worth pushing it
                 * up the stack */
                byte[] buf = mConnection.getBuf();
                int len = 0;
                int count = 0;
                int lowWater = buf.length / 2;
                while (len != -1) {
                    len = nis.read(buf, count, buf.length - count);
                    if (len != -1) {
                        count += len;
                    }
                    if (len == -1 || count >= lowWater) {
                        if (HttpLog.LOGV) HttpLog.v("Request.readResponse() " + count);
                        mEventHandler.data(buf, count);
                        count = 0;
                    }
                }
            } catch (EOFException e) {
                /* InflaterInputStream throws an EOFException when the
                   server truncates gzipped content.  Handle this case
                   as we do truncated non-gzipped content: no error */
                if (HttpLog.LOGV) HttpLog.v( "readResponse() handling " + e);
            } catch(IOException e) {
                // don't throw if we have a non-OK status code
                if (statusCode == HttpStatus.SC_OK) {
                    throw e;
                }
            } finally {
                if (nis != null) {
                    nis.close();
                }
            }
        }
        mConnection.setCanPersist(entity, statusLine.getProtocolVersion(),
                header.getConnectionType());
        mEventHandler.endData();
        complete();

        if (HttpLog.LOGV) HttpLog.v("Request.readResponse(): done " +
                                    mHost.getSchemeName() + "://" + getHostPort() + mPath);
    }

    /**
     * Data will not be sent to or received from server after cancel()
     * call.  Does not close connection--use close() below for that.
     *
     * Called by RequestHandle from non-network thread
     */
    void cancel() {
        if (HttpLog.LOGV) {
            HttpLog.v("Request.cancel(): " + getUri());
        }
        mCancelled = true;
        if (mConnection != null) {
            mConnection.cancel();
        }
    }

    String getHostPort() {
        String myScheme = mHost.getSchemeName();
        int myPort = mHost.getPort();

        // Only send port when we must... many servers can't deal with it
        if (myPort != 80 && myScheme.equals("http") ||
            myPort != 443 && myScheme.equals("https")) {
            return mHost.toHostString();
        } else {
            return mHost.getHostName();
        }
    }

    String getUri() {
        if (mProxyHost == null ||
            mHost.getSchemeName().equals("https")) {
            return mPath;
        }
        return mHost.getSchemeName() + "://" + getHostPort() + mPath;
    }

    /**
     * for debugging
     */
    public String toString() {
        return (mHighPriority ? "P*" : "") + mPath;
    }


    /**
     * If this request has been sent once and failed, it must be reset
     * before it can be sent again.
     */
    void reset() {
        /* clear content-length header */
        mHttpRequest.removeHeaders(CONTENT_LENGTH_HEADER);

        if (mBodyProvider != null) {
            try {
                mBodyProvider.reset();
            } catch (IOException ex) {
                if (HttpLog.LOGV) HttpLog.v(
                        "failed to reset body provider " +
                        getUri());
            }
            setBodyProvider(mBodyProvider, mBodyLength);
        }
    }

    /**
     * Pause thread request completes.  Used for synchronous requests,
     * and testing
     */
    void waitUntilComplete() {
        synchronized (mClientResource) {
            try {
                if (HttpLog.LOGV) HttpLog.v("Request.waitUntilComplete()");
                mClientResource.wait();
                if (HttpLog.LOGV) HttpLog.v("Request.waitUntilComplete() done waiting");
            } catch (InterruptedException e) {
            }
        }
    }

    void complete() {
        synchronized (mClientResource) {
            mClientResource.notifyAll();
        }
    }

    /**
     * Decide whether a response comes with an entity.
     * The implementation in this class is based on RFC 2616.
     * Unknown methods and response codes are supposed to
     * indicate responses with an entity.
     * <br/>
     * Derived executors can override this method to handle
     * methods and response codes not specified in RFC 2616.
     *
     * @param request   the request, to obtain the executed method
     * @param response  the response, to obtain the status code
     */

    private static boolean canResponseHaveBody(final HttpRequest request,
                                               final int status) {

        if ("HEAD".equalsIgnoreCase(request.getRequestLine().getMethod())) {
            return false;
        }
        return status >= HttpStatus.SC_OK
            && status != HttpStatus.SC_NO_CONTENT
            && status != HttpStatus.SC_NOT_MODIFIED
            && status != HttpStatus.SC_RESET_CONTENT;
    }

    /**
     * Supply an InputStream that provides the body of a request.  It's
     * not great that the caller must also provide the length of the data
     * returned by that InputStream, but the client needs to know up
     * front, and I'm not sure how to get this out of the InputStream
     * itself without a costly readthrough.  I'm not sure skip() would
     * do what we want.  If you know a better way, please let me know.
     */
    private void setBodyProvider(InputStream bodyProvider, int bodyLength) {
        if (!bodyProvider.markSupported()) {
            throw new IllegalArgumentException(
                    "bodyProvider must support mark()");
        }
        // Mark beginning of stream
        bodyProvider.mark(Integer.MAX_VALUE);

        ((BasicHttpEntityEnclosingRequest)mHttpRequest).setEntity(
                new InputStreamEntity(bodyProvider, bodyLength));
    }


    /**
     * Handles SSL error(s) on the way down from the user (the user
     * has already provided their feedback).
     */
    public void handleSslErrorResponse(boolean proceed) {
        HttpsConnection connection = (HttpsConnection)(mConnection);
        if (connection != null) {
            connection.restartConnection(proceed);
        }
    }

    /**
     * Helper: calls error() on eventhandler with appropriate message
     * This should not be called before the mConnection is set.
     */
    void error(int errorId, int resourceId) {
        mEventHandler.error(
                errorId,
                mConnection.mContext.getText(
                        resourceId).toString());
    }

}
