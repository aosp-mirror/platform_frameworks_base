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


/**
 * Callbacks in this interface are made as an HTTP request is
 * processed. The normal order of callbacks is status(), headers(),
 * then multiple data() then endData().  handleSslErrorRequest(), if
 * there is an SSL certificate error. error() can occur anywhere
 * in the transaction.
 * 
 * {@hide}
 */

public interface EventHandler {

    /**
     * Error codes used in the error() callback.  Positive error codes
     * are reserved for codes sent by http servers.  Negative error
     * codes are connection/parsing failures, etc.
     */

    /** Success */
    public static final int OK = 0;
    /** Generic error */
    public static final int ERROR = -1;
    /** Server or proxy hostname lookup failed */
    public static final int ERROR_LOOKUP = -2;
    /** Unsupported authentication scheme (ie, not basic or digest) */
    public static final int ERROR_UNSUPPORTED_AUTH_SCHEME = -3;
    /** User authentication failed on server */
    public static final int ERROR_AUTH = -4;
    /** User authentication failed on proxy */
    public static final int ERROR_PROXYAUTH = -5;
    /** Could not connect to server */
    public static final int ERROR_CONNECT = -6;
    /** Failed to write to or read from server */
    public static final int ERROR_IO = -7;
    /** Connection timed out */
    public static final int ERROR_TIMEOUT = -8;
    /** Too many redirects */
    public static final int ERROR_REDIRECT_LOOP = -9;
    /** Unsupported URI scheme (ie, not http, https, etc) */
    public static final int ERROR_UNSUPPORTED_SCHEME = -10;
    /** Failed to perform SSL handshake */
    public static final int ERROR_FAILED_SSL_HANDSHAKE = -11;
    /** Bad URL */
    public static final int ERROR_BAD_URL = -12;
    /** Generic file error for file:/// loads */
    public static final int FILE_ERROR = -13;
    /** File not found error for file:/// loads */
    public static final int FILE_NOT_FOUND_ERROR = -14;
    /** Too many requests queued */
    public static final int TOO_MANY_REQUESTS_ERROR = -15;

    final static int[] errorStringResources = {
        com.android.internal.R.string.httpErrorOk,
        com.android.internal.R.string.httpError,
        com.android.internal.R.string.httpErrorLookup,
        com.android.internal.R.string.httpErrorUnsupportedAuthScheme,
        com.android.internal.R.string.httpErrorAuth,
        com.android.internal.R.string.httpErrorProxyAuth,
        com.android.internal.R.string.httpErrorConnect,
        com.android.internal.R.string.httpErrorIO,
        com.android.internal.R.string.httpErrorTimeout,
        com.android.internal.R.string.httpErrorRedirectLoop,
        com.android.internal.R.string.httpErrorUnsupportedScheme,
        com.android.internal.R.string.httpErrorFailedSslHandshake,
        com.android.internal.R.string.httpErrorBadUrl,
        com.android.internal.R.string.httpErrorFile,
        com.android.internal.R.string.httpErrorFileNotFound,
        com.android.internal.R.string.httpErrorTooManyRequests
    };

    /**
     * Called after status line has been sucessfully processed.
     * @param major_version HTTP version advertised by server.  major
     * is the part before the "."
     * @param minor_version HTTP version advertised by server.  minor
     * is the part after the "."
     * @param code HTTP Status code.  See RFC 2616.
     * @param reason_phrase Textual explanation sent by server
     */
    public void status(int major_version,
                       int minor_version,
                       int code,
                       String reason_phrase);

    /**
     * Called after all headers are successfully processed.
     */
    public void headers(Headers headers);

    /**
     * An array containing all or part of the http body as read from
     * the server.
     * @param data A byte array containing the content
     * @param len The length of valid content in data
     *
     * Note: chunked and compressed encodings are handled within
     * android.net.http.  Decoded data is passed through this
     * interface.
     */
    public void data(byte[] data, int len);

    /**
     * Called when the document is completely read.  No more data()
     * callbacks will be made after this call
     */
    public void endData();

    /**
     * SSL certificate callback called before resource request is
     * made, which will be null for insecure connection.
     */
    public void certificate(SslCertificate certificate);

    /**
     * There was trouble.
     * @param id One of the error codes defined below
     * @param description of error
     */
    public void error(int id, String description);

    /**
     * SSL certificate error callback. Handles SSL error(s) on the way
     * up to the user. The callback has to make sure that restartConnection() is called,
     * otherwise the connection will be suspended indefinitely.
     * @return True if the callback can handle the error, which means it will
     *              call restartConnection() to unblock the thread later,
     *              otherwise return false.
     */
    public boolean handleSslErrorRequest(SslError error);

}
