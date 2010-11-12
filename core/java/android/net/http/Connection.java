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

package android.net.http;

import android.content.Context;
import android.os.SystemClock;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ListIterator;
import java.util.LinkedList;

import javax.net.ssl.SSLHandshakeException;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpVersion;
import org.apache.http.ParseException;
import org.apache.http.ProtocolVersion;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.BasicHttpContext;

/**
 * {@hide}
 */
abstract class Connection {

    /**
     * Allow a TCP connection 60 idle seconds before erroring out
     */
    static final int SOCKET_TIMEOUT = 60000;

    private static final int SEND = 0;
    private static final int READ = 1;
    private static final int DRAIN = 2;
    private static final int DONE = 3;
    private static final String[] states = {"SEND",  "READ", "DRAIN", "DONE"};

    Context mContext;

    /** The low level connection */
    protected AndroidHttpClientConnection mHttpClientConnection = null;

    /**
     * The server SSL certificate associated with this connection
     * (null if the connection is not secure)
     * It would be nice to store the whole certificate chain, but
     * we want to keep things as light-weight as possible
     */
    protected SslCertificate mCertificate = null;

    /**
     * The host this connection is connected to.  If using proxy,
     * this is set to the proxy address
     */
    HttpHost mHost;

    /** true if the connection can be reused for sending more requests */
    private boolean mCanPersist;

    /** context required by ConnectionReuseStrategy. */
    private HttpContext mHttpContext;

    /** set when cancelled */
    private static int STATE_NORMAL = 0;
    private static int STATE_CANCEL_REQUESTED = 1;
    private int mActive = STATE_NORMAL;

    /** The number of times to try to re-connect (if connect fails). */
    private final static int RETRY_REQUEST_LIMIT = 2;

    private static final int MIN_PIPE = 2;
    private static final int MAX_PIPE = 3;

    /**
     * Doesn't seem to exist anymore in the new HTTP client, so copied here.
     */
    private static final String HTTP_CONNECTION = "http.connection";

    RequestFeeder mRequestFeeder;

    /**
     * Buffer for feeding response blocks to webkit.  One block per
     * connection reduces memory churn.
     */
    private byte[] mBuf;

    protected Connection(Context context, HttpHost host,
                         RequestFeeder requestFeeder) {
        mContext = context;
        mHost = host;
        mRequestFeeder = requestFeeder;

        mCanPersist = false;
        mHttpContext = new BasicHttpContext(null);
    }

    HttpHost getHost() {
        return mHost;
    }

    /**
     * connection factory: returns an HTTP or HTTPS connection as
     * necessary
     */
    static Connection getConnection(
            Context context, HttpHost host, HttpHost proxy,
            RequestFeeder requestFeeder) {

        if (host.getSchemeName().equals("http")) {
            return new HttpConnection(context, host, requestFeeder);
        }

        // Otherwise, default to https
        return new HttpsConnection(context, host, proxy, requestFeeder);
    }

    /**
     * @return The server SSL certificate associated with this
     * connection (null if the connection is not secure)
     */
    /* package */ SslCertificate getCertificate() {
        return mCertificate;
    }

    /**
     * Close current network connection
     * Note: this runs in non-network thread
     */
    void cancel() {
        mActive = STATE_CANCEL_REQUESTED;
        closeConnection();
        if (HttpLog.LOGV) HttpLog.v(
            "Connection.cancel(): connection closed " + mHost);
    }

    /**
     * Process requests in queue
     * pipelines requests
     */
    void processRequests(Request firstRequest) {
        Request req = null;
        boolean empty;
        int error = EventHandler.OK;
        Exception exception = null;

        LinkedList<Request> pipe = new LinkedList<Request>();

        int minPipe = MIN_PIPE, maxPipe = MAX_PIPE;
        int state = SEND;

        while (state != DONE) {
            if (HttpLog.LOGV) HttpLog.v(
                    states[state] + " pipe " + pipe.size());

            /* If a request was cancelled, give other cancel requests
               some time to go through so we don't uselessly restart
               connections */
            if (mActive == STATE_CANCEL_REQUESTED) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException x) { /* ignore */ }
                mActive = STATE_NORMAL;
            }

            switch (state) {
                case SEND: {
                    if (pipe.size() == maxPipe) {
                        state = READ;
                        break;
                    }
                    /* get a request */
                    if (firstRequest == null) {
                        req = mRequestFeeder.getRequest(mHost);
                    } else {
                        req = firstRequest;
                        firstRequest = null;
                    }
                    if (req == null) {
                        state = DRAIN;
                        break;
                    }
                    req.setConnection(this);

                    /* Don't work on cancelled requests. */
                    if (req.mCancelled) {
                        if (HttpLog.LOGV) HttpLog.v(
                                "processRequests(): skipping cancelled request "
                                + req);
                        req.complete();
                        break;
                    }

                    if (mHttpClientConnection == null ||
                        !mHttpClientConnection.isOpen()) {
                        /* If this call fails, the address is bad or
                           the net is down.  Punt for now.

                           FIXME: blow out entire queue here on
                           connection failure if net up? */

                        if (!openHttpConnection(req)) {
                            state = DONE;
                            break;
                        }
                    }

                    /* we have a connection, let the event handler
                     * know of any associated certificate,
                     * potentially none.
                     */
                    req.mEventHandler.certificate(mCertificate);

                    try {
                        /* FIXME: don't increment failure count if old
                           connection?  There should not be a penalty for
                           attempting to reuse an old connection */
                        req.sendRequest(mHttpClientConnection);
                    } catch (HttpException e) {
                        exception = e;
                        error = EventHandler.ERROR;
                    } catch (IOException e) {
                        exception = e;
                        error = EventHandler.ERROR_IO;
                    } catch (IllegalStateException e) {
                        exception = e;
                        error = EventHandler.ERROR_IO;
                    }
                    if (exception != null) {
                        if (httpFailure(req, error, exception) &&
                            !req.mCancelled) {
                            /* retry request if not permanent failure
                               or cancelled */
                            pipe.addLast(req);
                        }
                        exception = null;
                        state = clearPipe(pipe) ? DONE : SEND;
                        minPipe = maxPipe = 1;
                        break;
                    }

                    pipe.addLast(req);
                    if (!mCanPersist) state = READ;
                    break;

                }
                case DRAIN:
                case READ: {
                    empty = !mRequestFeeder.haveRequest(mHost);
                    int pipeSize = pipe.size();
                    if (state != DRAIN && pipeSize < minPipe &&
                        !empty && mCanPersist) {
                        state = SEND;
                        break;
                    } else if (pipeSize == 0) {
                        /* Done if no other work to do */
                        state = empty ? DONE : SEND;
                        break;
                    }

                    req = (Request)pipe.removeFirst();
                    if (HttpLog.LOGV) HttpLog.v(
                            "processRequests() reading " + req);

                    try {
                        req.readResponse(mHttpClientConnection);
                    } catch (ParseException e) {
                        exception = e;
                        error = EventHandler.ERROR_IO;
                    } catch (IOException e) {
                        exception = e;
                        error = EventHandler.ERROR_IO;
                    } catch (IllegalStateException e) {
                        exception = e;
                        error = EventHandler.ERROR_IO;
                    }
                    if (exception != null) {
                        if (httpFailure(req, error, exception) &&
                            !req.mCancelled) {
                            /* retry request if not permanent failure
                               or cancelled */
                            req.reset();
                            pipe.addFirst(req);
                        }
                        exception = null;
                        mCanPersist = false;
                    }
                    if (!mCanPersist) {
                        if (HttpLog.LOGV) HttpLog.v(
                                "processRequests(): no persist, closing " +
                                mHost);

                        closeConnection();

                        mHttpContext.removeAttribute(HTTP_CONNECTION);
                        clearPipe(pipe);
                        minPipe = maxPipe = 1;
                        state = SEND;
                    }
                    break;
                }
            }
        }
    }

    /**
     * After a send/receive failure, any pipelined requests must be
     * cleared back to the mRequest queue
     * @return true if mRequests is empty after pipe cleared
     */
    private boolean clearPipe(LinkedList<Request> pipe) {
        boolean empty = true;
        if (HttpLog.LOGV) HttpLog.v(
                "Connection.clearPipe(): clearing pipe " + pipe.size());
        synchronized (mRequestFeeder) {
            Request tReq;
            while (!pipe.isEmpty()) {
                tReq = (Request)pipe.removeLast();
                if (HttpLog.LOGV) HttpLog.v(
                        "clearPipe() adding back " + mHost + " " + tReq);
                mRequestFeeder.requeueRequest(tReq);
                empty = false;
            }
            if (empty) empty = !mRequestFeeder.haveRequest(mHost);
        }
        return empty;
    }

    /**
     * @return true on success
     */
    private boolean openHttpConnection(Request req) {

        long now = SystemClock.uptimeMillis();
        int error = EventHandler.OK;
        Exception exception = null;

        try {
            // reset the certificate to null before opening a connection
            mCertificate = null;
            mHttpClientConnection = openConnection(req);
            if (mHttpClientConnection != null) {
                mHttpClientConnection.setSocketTimeout(SOCKET_TIMEOUT);
                mHttpContext.setAttribute(HTTP_CONNECTION,
                                          mHttpClientConnection);
            } else {
                // we tried to do SSL tunneling, failed,
                // and need to drop the request;
                // we have already informed the handler
                req.mFailCount = RETRY_REQUEST_LIMIT;
                return false;
            }
        } catch (UnknownHostException e) {
            if (HttpLog.LOGV) HttpLog.v("Failed to open connection");
            error = EventHandler.ERROR_LOOKUP;
            exception = e;
        } catch (IllegalArgumentException e) {
            if (HttpLog.LOGV) HttpLog.v("Illegal argument exception");
            error = EventHandler.ERROR_CONNECT;
            req.mFailCount = RETRY_REQUEST_LIMIT;
            exception = e;
        } catch (SSLConnectionClosedByUserException e) {
            // hack: if we have an SSL connection failure,
            // we don't want to reconnect
            req.mFailCount = RETRY_REQUEST_LIMIT;
            // no error message
            return false;
        } catch (SSLHandshakeException e) {
            // hack: if we have an SSL connection failure,
            // we don't want to reconnect
            req.mFailCount = RETRY_REQUEST_LIMIT;
            if (HttpLog.LOGV) HttpLog.v(
                    "SSL exception performing handshake");
            error = EventHandler.ERROR_FAILED_SSL_HANDSHAKE;
            exception = e;
        } catch (IOException e) {
            error = EventHandler.ERROR_CONNECT;
            exception = e;
        }

        if (HttpLog.LOGV) {
            long now2 = SystemClock.uptimeMillis();
            HttpLog.v("Connection.openHttpConnection() " +
                      (now2 - now) + " " + mHost);
        }

        if (error == EventHandler.OK) {
            return true;
        } else {
            if (req.mFailCount < RETRY_REQUEST_LIMIT) {
                // requeue
                mRequestFeeder.requeueRequest(req);
                req.mFailCount++;
            } else {
                httpFailure(req, error, exception);
            }
            return error == EventHandler.OK;
        }
    }

    /**
     * Helper.  Calls the mEventHandler's error() method only if
     * request failed permanently.  Increments mFailcount on failure.
     *
     * Increments failcount only if the network is believed to be
     * connected
     *
     * @return true if request can be retried (less than
     * RETRY_REQUEST_LIMIT failures have occurred).
     */
    private boolean httpFailure(Request req, int errorId, Exception e) {
        boolean ret = true;

        // e.printStackTrace();
        if (HttpLog.LOGV) HttpLog.v(
                "httpFailure() ******* " + e + " count " + req.mFailCount +
                " " + mHost + " " + req.getUri());

        if (++req.mFailCount >= RETRY_REQUEST_LIMIT) {
            ret = false;
            String error;
            if (errorId < 0) {
                error = ErrorStrings.getString(errorId, mContext);
            } else {
                Throwable cause = e.getCause();
                error = cause != null ? cause.toString() : e.getMessage();
            }
            req.mEventHandler.error(errorId, error);
            req.complete();
        }

        closeConnection();
        mHttpContext.removeAttribute(HTTP_CONNECTION);

        return ret;
    }

    HttpContext getHttpContext() {
        return mHttpContext;
    }

    /**
     * Use same logic as ConnectionReuseStrategy
     * @see ConnectionReuseStrategy
     */
    private boolean keepAlive(HttpEntity entity,
            ProtocolVersion ver, int connType, final HttpContext context) {
        org.apache.http.HttpConnection conn = (org.apache.http.HttpConnection)
            context.getAttribute(ExecutionContext.HTTP_CONNECTION);

        if (conn != null && !conn.isOpen())
            return false;
        // do NOT check for stale connection, that is an expensive operation

        if (entity != null) {
            if (entity.getContentLength() < 0) {
                if (!entity.isChunked() || ver.lessEquals(HttpVersion.HTTP_1_0)) {
                    // if the content length is not known and is not chunk
                    // encoded, the connection cannot be reused
                    return false;
                }
            }
        }
        // Check for 'Connection' directive
        if (connType == Headers.CONN_CLOSE) {
            return false;
        } else if (connType == Headers.CONN_KEEP_ALIVE) {
            return true;
        }
        // Resorting to protocol version default close connection policy
        return !ver.lessEquals(HttpVersion.HTTP_1_0);
    }

    void setCanPersist(HttpEntity entity, ProtocolVersion ver, int connType) {
        mCanPersist = keepAlive(entity, ver, connType, mHttpContext);
    }

    void setCanPersist(boolean canPersist) {
        mCanPersist = canPersist;
    }

    boolean getCanPersist() {
        return mCanPersist;
    }

    /** typically http or https... set by subclass */
    abstract String getScheme();
    abstract void closeConnection();
    abstract AndroidHttpClientConnection openConnection(Request req) throws IOException;

    /**
     * Prints request queue to log, for debugging.
     * returns request count
     */
    public synchronized String toString() {
        return mHost.toString();
    }

    byte[] getBuf() {
        if (mBuf == null) mBuf = new byte[8192];
        return mBuf;
    }

}
