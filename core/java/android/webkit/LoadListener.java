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

package android.webkit;

import android.content.Context;
import android.net.WebAddress;
import android.net.ParseException;
import android.net.http.EventHandler;
import android.net.http.Headers;
import android.net.http.HttpAuthHeader;
import android.net.http.RequestHandle;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.net.http.SslCertificate;

import android.os.Handler;
import android.os.Message;
import android.util.Config;
import android.util.Log;
import android.webkit.CacheManager.CacheResult;

import com.android.internal.R;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.commons.codec.binary.Base64;

class LoadListener extends Handler implements EventHandler {

    private static final String LOGTAG = "webkit";

    // Messages used internally to communicate state between the
    // Network thread and the WebCore thread.
    private static final int MSG_CONTENT_HEADERS = 100;
    private static final int MSG_CONTENT_DATA = 110;
    private static final int MSG_CONTENT_FINISHED = 120;
    private static final int MSG_CONTENT_ERROR = 130;
    private static final int MSG_LOCATION_CHANGED = 140;
    private static final int MSG_LOCATION_CHANGED_REQUEST = 150;
    private static final int MSG_STATUS = 160;
    private static final int MSG_SSL_CERTIFICATE = 170;
    private static final int MSG_SSL_ERROR = 180;

    // Standard HTTP status codes in a more representative format
    private static final int HTTP_OK = 200;
    private static final int HTTP_MOVED_PERMANENTLY = 301;
    private static final int HTTP_FOUND = 302;
    private static final int HTTP_SEE_OTHER = 303;
    private static final int HTTP_NOT_MODIFIED = 304;
    private static final int HTTP_TEMPORARY_REDIRECT = 307;
    private static final int HTTP_AUTH = 401;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_PROXY_AUTH = 407;

    private static int sNativeLoaderCount;

    private final ByteArrayBuilder mDataBuilder = new ByteArrayBuilder(8192);

    private String   mUrl;
    private WebAddress mUri;
    private boolean  mPermanent;
    private String   mOriginalUrl;
    private Context  mContext;
    private BrowserFrame mBrowserFrame;
    private int      mNativeLoader;
    private String   mMimeType;
    private String   mEncoding;
    private String   mTransferEncoding;
    private int      mStatusCode;
    private String   mStatusText;
    public long mContentLength; // Content length of the incoming data
    private boolean  mCancelled;  // The request has been cancelled.
    private boolean  mAuthFailed;  // indicates that the prev. auth failed
    private CacheLoader mCacheLoader;
    private CacheManager.CacheResult mCacheResult;
    private HttpAuthHeader mAuthHeader;
    private int      mErrorID = OK;
    private String   mErrorDescription;
    private SslError mSslError;
    private RequestHandle mRequestHandle;

    // Request data. It is only valid when we are doing a load from the
    // cache. It is needed if the cache returns a redirect
    private String mMethod;
    private Map<String, String> mRequestHeaders;
    private byte[] mPostData;
    private boolean mIsHighPriority;
    // Flag to indicate that this load is synchronous.
    private boolean mSynchronous;
    private Vector<Message> mMessageQueue;

    // Does this loader correspond to the main-frame top-level page?
    private boolean mIsMainPageLoader;

    private Headers mHeaders;

    // =========================================================================
    // Public functions
    // =========================================================================

    public static LoadListener getLoadListener(
            Context context, BrowserFrame frame, String url,
            int nativeLoader, boolean synchronous, boolean isMainPageLoader) {

        sNativeLoaderCount += 1;
        return new LoadListener(
            context, frame, url, nativeLoader, synchronous, isMainPageLoader);
    }

    public static int getNativeLoaderCount() {
        return sNativeLoaderCount;
    }

    LoadListener(Context context, BrowserFrame frame, String url,
            int nativeLoader, boolean synchronous, boolean isMainPageLoader) {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener constructor url=" + url);
        }
        mContext = context;
        mBrowserFrame = frame;
        setUrl(url);
        mNativeLoader = nativeLoader;
        mMimeType = "";
        mEncoding = "";
        mSynchronous = synchronous;
        if (synchronous) {
            mMessageQueue = new Vector<Message>();
        }
        mIsMainPageLoader = isMainPageLoader;
    }

    /**
     * We keep a count of refs to the nativeLoader so we do not create
     * so many LoadListeners that the GREFs blow up
     */
    private void clearNativeLoader() {
        sNativeLoaderCount -= 1;
        mNativeLoader = 0;
    }

    /*
     * This message handler is to facilitate communication between the network
     * thread and the browser thread.
     */
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_CONTENT_HEADERS:
                /*
                 * This message is sent when the LoadListener has headers
                 * available. The headers are sent onto WebCore to see what we
                 * should do with them.
                 */
                handleHeaders((Headers) msg.obj);
                break;

            case MSG_CONTENT_DATA:
                /*
                 * This message is sent when the LoadListener has data available
                 * in it's data buffer. This data buffer could be filled from a
                 * file (this thread) or from http (Network thread).
                 */
                if (mNativeLoader != 0 && !ignoreCallbacks()) {
                    commitLoad();
                }
                break;

            case MSG_CONTENT_FINISHED:
                /*
                 * This message is sent when the LoadListener knows that the
                 * load is finished. This message is not sent in the case of an
                 * error.
                 *
                 */
                handleEndData();
                break;

            case MSG_CONTENT_ERROR:
                /*
                 * This message is sent when a load error has occured. The
                 * LoadListener will clean itself up.
                 */
                handleError(msg.arg1, (String) msg.obj);
                break;

            case MSG_LOCATION_CHANGED:
                /*
                 * This message is sent from LoadListener.endData to inform the
                 * browser activity that the location of the top level page
                 * changed.
                 */
                doRedirect();
                break;

            case MSG_LOCATION_CHANGED_REQUEST:
                /*
                 * This message is sent from endData on receipt of a 307
                 * Temporary Redirect in response to a POST -- the user must
                 * confirm whether to continue loading. If the user says Yes,
                 * we simply call MSG_LOCATION_CHANGED. If the user says No,
                 * we call MSG_CONTENT_FINISHED.
                 */
                Message contMsg = obtainMessage(MSG_LOCATION_CHANGED);
                Message stopMsg = obtainMessage(MSG_CONTENT_FINISHED);
                mBrowserFrame.getCallbackProxy().onFormResubmission(
                        stopMsg, contMsg);
                break;

            case MSG_STATUS:
                /*
                 * This message is sent from the network thread when the http
                 * stack has received the status response from the server.
                 */
                HashMap status = (HashMap) msg.obj;
                handleStatus(((Integer) status.get("major")).intValue(),
                        ((Integer) status.get("minor")).intValue(),
                        ((Integer) status.get("code")).intValue(),
                        (String) status.get("reason"));
                break;

            case MSG_SSL_CERTIFICATE:
                /*
                 * This message is sent when the network thread receives a ssl
                 * certificate.
                 */
                handleCertificate((SslCertificate) msg.obj);
                break;

            case MSG_SSL_ERROR:
                /*
                 * This message is sent when the network thread encounters a
                 * ssl error.
                 */
                handleSslError((SslError) msg.obj);
                break;
        }
    }

    /**
     * @return The loader's BrowserFrame.
     */
    BrowserFrame getFrame() {
        return mBrowserFrame;
    }

    Context getContext() {
        return mContext;
    }

    /* package */ boolean isSynchronous() {
        return mSynchronous;
    }

    /**
     * @return True iff the load has been cancelled
     */
    public boolean cancelled() {
        return mCancelled;
    }

    /**
     * Parse the headers sent from the server.
     * @param headers gives up the HeaderGroup
     * IMPORTANT: as this is called from network thread, can't call native
     * directly
     */
    public void headers(Headers headers) {
        if (Config.LOGV) Log.v(LOGTAG, "LoadListener.headers");
        sendMessageInternal(obtainMessage(MSG_CONTENT_HEADERS, headers));
    }

    // Does the header parsing work on the WebCore thread.
    private void handleHeaders(Headers headers) {
        if (mCancelled) return;
        mHeaders = headers;
        mMimeType = "";
        mEncoding = "";

        ArrayList<String> cookies = headers.getSetCookie();
        for (int i = 0; i < cookies.size(); ++i) {
            CookieManager.getInstance().setCookie(mUri, cookies.get(i));
        }

        long contentLength = headers.getContentLength();
        if (contentLength != Headers.NO_CONTENT_LENGTH) {
            mContentLength = contentLength;
        } else {
            mContentLength = 0;
        }

        String contentType = headers.getContentType();
        if (contentType != null) {
            parseContentTypeHeader(contentType);

            // If we have one of "generic" MIME types, try to deduce
            // the right MIME type from the file extension (if any):
            if (mMimeType.equalsIgnoreCase("text/plain") ||
                    mMimeType.equalsIgnoreCase("application/octet-stream")) {

                String newMimeType = guessMimeTypeFromExtension();
                if (newMimeType != null) {
                    mMimeType = newMimeType;
                }
            } else if (mMimeType.equalsIgnoreCase("text/vnd.wap.wml")) {
                // As we don't support wml, render it as plain text
                mMimeType = "text/plain";
            } else {
                // XXX: Until the servers send us either correct xhtml or
                // text/html, treat application/xhtml+xml as text/html.
                // It seems that xhtml+xml and vnd.wap.xhtml+xml mime
                // subtypes are used interchangeably. So treat them the same.
                if (mMimeType.equalsIgnoreCase("application/xhtml+xml") ||
                        mMimeType.equals("application/vnd.wap.xhtml+xml")) {
                    mMimeType = "text/html";
                }
            }
        } else {
            /* Often when servers respond with 304 Not Modified or a
               Redirect, then they don't specify a MIMEType. When this
               occurs, the function below is called.  In the case of
               304 Not Modified, the cached headers are used rather
               than the headers that are returned from the server. */
            guessMimeType();
        }

        // is it an authentication request?
        boolean mustAuthenticate = (mStatusCode == HTTP_AUTH ||
                mStatusCode == HTTP_PROXY_AUTH);
        // is it a proxy authentication request?
        boolean isProxyAuthRequest = (mStatusCode == HTTP_PROXY_AUTH);
        // is this authentication request due to a failed attempt to
        // authenticate ealier?
        mAuthFailed = false;

        // if we tried to authenticate ourselves last time
        if (mAuthHeader != null) {
            // we failed, if we must to authenticate again now and
            // we have a proxy-ness match
            mAuthFailed = (mustAuthenticate &&
                    isProxyAuthRequest == mAuthHeader.isProxy());

            // if we did NOT fail and last authentication request was a
            // proxy-authentication request
            if (!mAuthFailed && mAuthHeader.isProxy()) {
                Network network = Network.getInstance(mContext);
                // if we have a valid proxy set
                if (network.isValidProxySet()) {
                    /* The proxy credentials can be read in the WebCore thread
                    */
                    synchronized (network) {
                        // save authentication credentials for pre-emptive proxy
                        // authentication
                        network.setProxyUsername(mAuthHeader.getUsername());
                        network.setProxyPassword(mAuthHeader.getPassword());
                    }
                }
            }
        }

        // it is only here that we can reset the last mAuthHeader object
        // (if existed) and start a new one!!!
        mAuthHeader = null;
        if (mustAuthenticate) {
            if (mStatusCode == HTTP_AUTH) {
                mAuthHeader = parseAuthHeader(
                        headers.getWwwAuthenticate());
            } else {
                mAuthHeader = parseAuthHeader(
                        headers.getProxyAuthenticate());
                // if successfully parsed the header
                if (mAuthHeader != null) {
                    // mark the auth-header object as a proxy
                    mAuthHeader.setProxy();
                }
            }
        }

        // Only create a cache file if the server has responded positively.
        if ((mStatusCode == HTTP_OK ||
                mStatusCode == HTTP_FOUND ||
                mStatusCode == HTTP_MOVED_PERMANENTLY ||
                mStatusCode == HTTP_TEMPORARY_REDIRECT) && 
                mNativeLoader != 0) {
            // Content arriving from a StreamLoader (eg File, Cache or Data)
            // will not be cached as they have the header:
            // cache-control: no-store
            mCacheResult = CacheManager.createCacheFile(mUrl, mStatusCode,
                    headers, mMimeType, false);
            if (mCacheResult != null) {
                mCacheResult.encoding = mEncoding;
            }
        }
        commitHeadersCheckRedirect();
    }

    /**
     * @return True iff this loader is in the proxy-authenticate state.
     */
    boolean proxyAuthenticate() {
        if (mAuthHeader != null) {
            return mAuthHeader.isProxy();
        }

        return false;
    }

    /**
     * Report the status of the response.
     * TODO: Comments about each parameter.
     * IMPORTANT: as this is called from network thread, can't call native
     * directly
     */
    public void status(int majorVersion, int minorVersion,
            int code, /* Status-Code value */ String reasonPhrase) {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener: from: " + mUrl
                    + " major: " + majorVersion
                    + " minor: " + minorVersion
                    + " code: " + code
                    + " reason: " + reasonPhrase);
        }
        HashMap status = new HashMap();
        status.put("major", majorVersion);
        status.put("minor", minorVersion);
        status.put("code", code);
        status.put("reason", reasonPhrase);
        // New status means new data. Clear the old.
        mDataBuilder.clear();
        sendMessageInternal(obtainMessage(MSG_STATUS, status));
    }

    // Handle the status callback on the WebCore thread.
    private void handleStatus(int major, int minor, int code, String reason) {
        if (mCancelled) return;

        mStatusCode = code;
        mStatusText = reason;
        mPermanent = false;
    }

    /**
     * Implementation of certificate handler for EventHandler.
     * Called every time a resource is loaded via a secure
     * connection. In this context, can be called multiple
     * times if we have redirects
     * @param certificate The SSL certifcate
     * IMPORTANT: as this is called from network thread, can't call native
     * directly
     */
    public void certificate(SslCertificate certificate) {
        sendMessageInternal(obtainMessage(MSG_SSL_CERTIFICATE, certificate));
    }

    // Handle the certificate on the WebCore thread.
    private void handleCertificate(SslCertificate certificate) {
        // if this is the top-most main-frame page loader
        if (mIsMainPageLoader) {
            // update the browser frame (ie, the main frame)
            mBrowserFrame.certificate(certificate);
        }
    }

    /**
     * Implementation of error handler for EventHandler.
     * Subclasses should call this method to have error fields set.
     * @param id The error id described by EventHandler.
     * @param description A string description of the error.
     * IMPORTANT: as this is called from network thread, can't call native
     * directly
     */
    public void error(int id, String description) {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener.error url:" +
                    url() + " id:" + id + " description:" + description);
        }
        sendMessageInternal(obtainMessage(MSG_CONTENT_ERROR, id, 0, description));
    }

    // Handle the error on the WebCore thread.
    private void handleError(int id, String description) {
        mErrorID = id;
        mErrorDescription = description;
        detachRequestHandle();
        notifyError();
        tearDown();
    }

    /**
     * Add data to the internal collection of data. This function is used by
     * the data: scheme, about: scheme and http/https schemes.
     * @param data A byte array containing the content.
     * @param length The length of data.
     * IMPORTANT: as this is called from network thread, can't call native
     * directly
     * XXX: Unlike the other network thread methods, this method can do the
     * work of decoding the data and appending it to the data builder because
     * mDataBuilder is a thread-safe structure.
     */
    public void data(byte[] data, int length) {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener.data(): url: " + url());
        }

        // Decode base64 data
        // Note: It's fine that we only decode base64 here and not in the other
        // data call because the only caller of the stream version is not
        // base64 encoded.
        if ("base64".equalsIgnoreCase(mTransferEncoding)) {
            if (length < data.length) {
                byte[] trimmedData = new byte[length];
                System.arraycopy(data, 0, trimmedData, 0, length);
                data = trimmedData;
            }
            data = Base64.decodeBase64(data);
            length = data.length;
        }
        // Synchronize on mData because commitLoad may write mData to WebCore
        // and we don't want to replace mData or mDataLength at the same time
        // as a write.
        boolean sendMessage = false;
        synchronized (mDataBuilder) {
            sendMessage = mDataBuilder.isEmpty();
            mDataBuilder.append(data, 0, length);
        }
        if (sendMessage) {
            // Send a message whenever data comes in after a write to WebCore
            sendMessageInternal(obtainMessage(MSG_CONTENT_DATA));
        }
    }

    /**
     * Event handler's endData call. Send a message to the handler notifying
     * them that the data has finished.
     * IMPORTANT: as this is called from network thread, can't call native
     * directly
     */
    public void endData() {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener.endData(): url: " + url());
        }
        sendMessageInternal(obtainMessage(MSG_CONTENT_FINISHED));
    }

    // Handle the end of data.
    private void handleEndData() {
        if (mCancelled) return;

        switch (mStatusCode) {
            case HTTP_MOVED_PERMANENTLY:
                // 301 - permanent redirect
                mPermanent = true;
            case HTTP_FOUND:
            case HTTP_SEE_OTHER:
            case HTTP_TEMPORARY_REDIRECT:
                // 301, 302, 303, and 307 - redirect
                if (mStatusCode == HTTP_TEMPORARY_REDIRECT) {
                    if (mRequestHandle != null && 
                                mRequestHandle.getMethod().equals("POST")) {
                        sendMessageInternal(obtainMessage(
                                MSG_LOCATION_CHANGED_REQUEST));  
                    } else if (mMethod != null && mMethod.equals("POST")) {
                        sendMessageInternal(obtainMessage(
                                MSG_LOCATION_CHANGED_REQUEST));
                    } else {
                        sendMessageInternal(obtainMessage(MSG_LOCATION_CHANGED));
                    }
                } else {
                    sendMessageInternal(obtainMessage(MSG_LOCATION_CHANGED));
                }
                return;

            case HTTP_AUTH:
            case HTTP_PROXY_AUTH:
                // According to rfc2616, the response for HTTP_AUTH must include
                // WWW-Authenticate header field and the response for 
                // HTTP_PROXY_AUTH must include Proxy-Authenticate header field.
                if (mAuthHeader != null &&
                        (Network.getInstance(mContext).isValidProxySet() ||
                         !mAuthHeader.isProxy())) {
                    Network.getInstance(mContext).handleAuthRequest(this);
                    return;
                }
                break;  // use default

            case HTTP_NOT_MODIFIED:
                // Server could send back NOT_MODIFIED even if we didn't
                // ask for it, so make sure we have a valid CacheLoader
                // before calling it.
                if (mCacheLoader != null) {
                    mCacheLoader.load();
                    if (Config.LOGV) {
                        Log.v(LOGTAG, "LoadListener cache load url=" + url());
                    }
                    return;
                }
                break;  // use default

            case HTTP_NOT_FOUND:
                // Not an error, the server can send back content.
            default:
                break;
        }
        detachRequestHandle();
        tearDown();
    }

    /* This method is called from CacheLoader when the initial request is
     * serviced by the Cache. */
    /* package */ void setCacheLoader(CacheLoader c) {
        mCacheLoader = c;
    }

    /**
     * Check the cache for the current URL, and load it if it is valid.
     *
     * @param headers for the request
     * @return true if cached response is used.
     */
    boolean checkCache(Map<String, String> headers) {
        // Get the cache file name for the current URL
        CacheResult result = CacheManager.getCacheFile(url(),
                headers);

        // Go ahead and set the cache loader to null in case the result is
        // null.
        mCacheLoader = null;

        if (result != null) {
            // The contents of the cache may need to be revalidated so just
            // remember the cache loader in the case that the server responds
            // positively to the cached content. This is also used to detect if
            // a redirect came from the cache.
            mCacheLoader = new CacheLoader(this, result);

            // If I got a cachedUrl and the revalidation header was not
            // added, then the cached content valid, we should use it.
            if (!headers.containsKey(
                    CacheManager.HEADER_KEY_IFNONEMATCH) &&
                    !headers.containsKey(
                            CacheManager.HEADER_KEY_IFMODIFIEDSINCE)) {
                if (Config.LOGV) {
                    Log.v(LOGTAG, "FrameLoader: HTTP URL in cache " +
                            "and usable: " + url());
                }
                // Load the cached file
                mCacheLoader.load();
                return true;
            }
        }
        return false;
    }

    /**
     * SSL certificate error callback. Handles SSL error(s) on the way up
     * to the user.
     * IMPORTANT: as this is called from network thread, can't call native
     * directly
     */
    public void handleSslErrorRequest(SslError error) {
        if (Config.LOGV) {
            Log.v(LOGTAG,
                    "LoadListener.handleSslErrorRequest(): url:" + url() +
                    " primary error: " + error.getPrimaryError() +
                    " certificate: " + error.getCertificate());
        }
        sendMessageInternal(obtainMessage(MSG_SSL_ERROR, error));
    }

    // Handle the ssl error on the WebCore thread.
    private void handleSslError(SslError error) {
        if (!mCancelled) {
            mSslError = error;
            Network.getInstance(mContext).handleSslErrorRequest(this);
        }
    }

    /**
     * @return HTTP authentication realm or null if none.
     */
    String realm() {
        if (mAuthHeader == null) {
            return null;
        } else {
            return mAuthHeader.getRealm();
        }
    }

    /**
     * Returns true iff an HTTP authentication problem has
     * occured (credentials invalid).
     */
    boolean authCredentialsInvalid() {
        // if it is digest and the nonce is stale, we just
        // resubmit with a new nonce
        return (mAuthFailed &&
                !(mAuthHeader.isDigest() && mAuthHeader.getStale()));
    }

    /**
     * @return The last SSL error or null if there is none
     */
    SslError sslError() {
        return mSslError;
    }

    /**
     * Handles SSL error(s) on the way down from the user
     * (the user has already provided their feedback).
     */
    void handleSslErrorResponse(boolean proceed) {
        if (mRequestHandle != null) {
            mRequestHandle.handleSslErrorResponse(proceed);
        }
        if (!proceed) {
            // Commit whatever data we have and tear down the loader.
            commitLoad();
            tearDown();
        }
    }

    /**
     * Uses user-supplied credentials to restart a request. If the credentials
     * are null, cancel the request.
     */
    void handleAuthResponse(String username, String password) {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener.handleAuthResponse: url: " + mUrl
                    + " username: " + username
                    + " password: " + password);
        }

        // create and queue an authentication-response
        if (username != null && password != null) {
            if (mAuthHeader != null && mRequestHandle != null) {
                mAuthHeader.setUsername(username);
                mAuthHeader.setPassword(password);

                int scheme = mAuthHeader.getScheme();
                if (scheme == HttpAuthHeader.BASIC) {
                    // create a basic response
                    boolean isProxy = mAuthHeader.isProxy();

                    mRequestHandle.setupBasicAuthResponse(isProxy,
                            username, password);
                } else {
                    if (scheme == HttpAuthHeader.DIGEST) {
                        // create a digest response
                        boolean isProxy = mAuthHeader.isProxy();

                        String realm     = mAuthHeader.getRealm();
                        String nonce     = mAuthHeader.getNonce();
                        String qop       = mAuthHeader.getQop();
                        String algorithm = mAuthHeader.getAlgorithm();
                        String opaque    = mAuthHeader.getOpaque();

                        mRequestHandle.setupDigestAuthResponse
                                (isProxy, username, password, realm,
                                 nonce, qop, algorithm, opaque);
                    }
                }
            }
        } else {
            // Commit whatever data we have and tear down the loader.
            commitLoad();
            tearDown();
        }
    }

    /**
     * This is called when a request can be satisfied by the cache, however,
     * the cache result could be a redirect. In this case we need to issue
     * the network request.
     * @param method
     * @param headers
     * @param postData
     * @param isHighPriority
     */
    void setRequestData(String method, Map<String, String> headers, 
            byte[] postData, boolean isHighPriority) {
        mMethod = method;
        mRequestHeaders = headers;
        mPostData = postData;
        mIsHighPriority = isHighPriority;
    }

    /**
     * @return The current URL associated with this load.
     */
    String url() {
        return mUrl;
    }

    /**
     * @return The current WebAddress associated with this load.
     */
    WebAddress getWebAddress() {
        return mUri;
    }

    /**
     * @return URL hostname (current URL).
     */
    String host() {
        if (mUri != null) {
            return mUri.mHost;
        }

        return null;
    }

    /**
     * @return The original URL associated with this load.
     */
    String originalUrl() {
        if (mOriginalUrl != null) {
            return mOriginalUrl;
        } else {
            return mUrl;
        }
    }

    void attachRequestHandle(RequestHandle requestHandle) {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener.attachRequestHandle(): " +
                    "requestHandle: " +  requestHandle);
        }
        mRequestHandle = requestHandle;
    }

    void detachRequestHandle() {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener.detachRequestHandle(): " +
                    "requestHandle: " + mRequestHandle);
        }
        mRequestHandle = null;
    }

    /*
     * This function is called from native WebCore code to
     * notify this LoadListener that the content it is currently
     * downloading should be saved to a file and not sent to
     * WebCore.
     */
    void downloadFile() {
        // Setting the Cache Result to null ensures that this
        // content is not added to the cache
        mCacheResult = null;
        
        // Inform the client that they should download a file
        mBrowserFrame.getCallbackProxy().onDownloadStart(url(), 
                mBrowserFrame.getUserAgentString(),
                mHeaders.getContentDisposition(), 
                mMimeType, mContentLength);

        // Cancel the download. We need to stop the http load.
        // The native loader object will get cleared by the call to
        // cancel() but will also be cleared on the WebCore side
        // when this function returns.
        cancel();
    }
    
    /*
     * This function is called from native WebCore code to
     * find out if the given URL is in the cache, and if it can
     * be used. This is just for forward/back navigation to a POST
     * URL.
     */
    static boolean willLoadFromCache(String url) {
        boolean inCache = CacheManager.getCacheFile(url, null) != null;
        if (Config.LOGV) {
            Log.v(LOGTAG, "willLoadFromCache: " + url + " in cache: " + 
                    inCache);
        }
        return inCache;
    }

    /*
     * Reset the cancel flag. This is used when we are resuming a stopped
     * download. To suspend a download, we cancel it. It can also be cancelled
     * when it has run out of disk space. In this situation, the download
     * can be resumed.
     */
    void resetCancel() {
        mCancelled = false;
    }

    String mimeType() {
        return mMimeType;
    }

    /*
     * Return the size of the content being downloaded. This represents the
     * full content size, even under the situation where the download has been
     * resumed after interruption.
     *
     * @ return full content size
     */
    long contentLength() {
        return mContentLength;
    }

    // Commit the headers if the status code is not a redirect.
    private void commitHeadersCheckRedirect() {
        if (mCancelled) return;

        // do not call webcore if it is redirect. According to the code in
        // InspectorController::willSendRequest(), the response is only updated
        // when it is not redirect.
        if ((mStatusCode >= 301 && mStatusCode <= 303) || mStatusCode == 307) {
            return;
        }

        commitHeaders();
    }

    // This commits the headers without checking the response status code.
    private void commitHeaders() {
        // Commit the headers to WebCore
        int nativeResponse = createNativeResponse();
        // The native code deletes the native response object.
        nativeReceivedResponse(nativeResponse);
    }

    /**
     * Create a WebCore response object so that it can be used by
     * nativeReceivedResponse or nativeRedirectedToUrl
     * @return native response pointer
     */
    private int createNativeResponse() {
        // If WebCore sends if-modified-since, mCacheLoader is null. If 
        // CacheManager sends it, mCacheLoader is not null. In this case, if the
        // server responds with a 304, then we treat it like it was a 200 code 
        // and proceed with loading the file from the cache.
        int statusCode = (mStatusCode == HTTP_NOT_MODIFIED &&
                mCacheLoader != null) ? HTTP_OK : mStatusCode;
        // pass content-type content-length and content-encoding
        final int nativeResponse = nativeCreateResponse(
                mUrl, statusCode, mStatusText,
                mMimeType, mContentLength, mEncoding,
                mCacheResult == null ? 0 : mCacheResult.expires / 1000);
        if (mHeaders != null) {
            mHeaders.getHeaders(new Headers.HeaderCallback() {
                    public void header(String name, String value) {
                        nativeSetResponseHeader(nativeResponse, name, value);
                    }
                });
        }
        return nativeResponse;
    }

    /**
     * Commit the load.  It should be ok to call repeatedly but only before
     * tearDown is called.
     */
    private void commitLoad() {
        if (mCancelled) return;

        // Give the data to WebKit now
        PerfChecker checker = new PerfChecker();
        ByteArrayBuilder.Chunk c;
        while (true) {
            c = mDataBuilder.getFirstChunk();
            if (c == null) break;

            if (c.mLength != 0) {
                if (mCacheResult != null) {
                    try {
                        mCacheResult.outStream.write(c.mArray, 0, c.mLength);
                    } catch (IOException e) {
                        mCacheResult = null;
                    }
                }
                nativeAddData(c.mArray, c.mLength);
            }
            mDataBuilder.releaseChunk(c);
            checker.responseAlert("res nativeAddData");
        }
    }

    /**
     * Tear down the load. Subclasses should clean up any mess because of
     * cancellation or errors during the load.
     */
    void tearDown() {
        if (mCacheResult != null) {
            if (getErrorID() == OK) {
                CacheManager.saveCacheFile(mUrl, mCacheResult);
            }

            // we need to reset mCacheResult to be null
            // resource loader's tearDown will call into WebCore's
            // nativeFinish, which in turn calls loader.cancel().
            // If we don't reset mCacheFile, the file will be deleted.
            mCacheResult = null;
        }
        if (mNativeLoader != 0) {
            PerfChecker checker = new PerfChecker();
            nativeFinished();
            checker.responseAlert("res nativeFinished");
            clearNativeLoader();
        }
    }

    /**
     * Helper for getting the error ID.
     * @return errorID.
     */
    private int getErrorID() {
        return mErrorID;
    }

    /**
     * Return the error description.
     * @return errorDescription.
     */
    private String getErrorDescription() {
        return mErrorDescription;
    }

    /**
     * Notify the loader we encountered an error.
     */
    void notifyError() {
        if (mNativeLoader != 0) {
            String description = getErrorDescription();
            if (description == null) description = "";
            nativeError(getErrorID(), description, url());
            clearNativeLoader();
        }
    }

    /**
     * Cancel a request.
     * FIXME: This will only work if the request has yet to be handled. This
     * is in no way guarenteed if requests are served in a separate thread.
     * It also causes major problems if cancel is called during an
     * EventHandler's method call.
     */
    public void cancel() {
        if (Config.LOGV) {
            if (mRequestHandle == null) {
                Log.v(LOGTAG, "LoadListener.cancel(): no requestHandle");
            } else {
                Log.v(LOGTAG, "LoadListener.cancel()");
            }
        }
        if (mRequestHandle != null) {
            mRequestHandle.cancel();
            mRequestHandle = null;
        }

        mCacheResult = null;
        mCancelled = true;

        clearNativeLoader();
    }

    // This count is transferred from RequestHandle to LoadListener when
    // loading from the cache so that we can detect redirect loops that switch
    // between the network and the cache.
    private int mCacheRedirectCount;

    /*
     * Perform the actual redirection. This involves setting up the new URL,
     * informing WebCore and then telling the Network to start loading again.
     */
    private void doRedirect() {
        // as cancel() can cancel the load before doRedirect() is
        // called through handleMessage, needs to check to see if we
        // are canceled before proceed
        if (mCancelled) {
            return;
        }

        // Do the same check for a redirect loop that
        // RequestHandle.setupRedirect does.
        if (mCacheRedirectCount >= RequestHandle.MAX_REDIRECT_COUNT) {
            handleError(EventHandler.ERROR_REDIRECT_LOOP, mContext.getString(
                    R.string.httpErrorRedirectLoop));
            return;
        }

        String redirectTo = mHeaders.getLocation();
        if (redirectTo != null) {
            int nativeResponse = createNativeResponse();
            redirectTo =
                    nativeRedirectedToUrl(mUrl, redirectTo, nativeResponse);
            // nativeRedirectedToUrl() may call cancel(), e.g. when redirect
            // from a https site to a http site, check mCancelled again
            if (mCancelled) {
                return;
            }
            if (redirectTo == null) {
                Log.d(LOGTAG, "Redirection failed for "
                        + mHeaders.getLocation());
                cancel();
                return;
            } else if (!URLUtil.isNetworkUrl(redirectTo)) {
                final String text = mContext
                        .getString(R.string.open_permission_deny)
                        + "\n" + redirectTo;
                nativeAddData(text.getBytes(), text.length());
                nativeFinished();
                clearNativeLoader();
                return;
            }

            if (mOriginalUrl == null) {
                mOriginalUrl = mUrl;
            }

            // Cache the redirect response
            if (mCacheResult != null) {
                if (getErrorID() == OK) {
                    CacheManager.saveCacheFile(mUrl, mCacheResult);
                }
                mCacheResult = null;
            }

            // This will strip the anchor
            setUrl(redirectTo);

            // Redirect may be in the cache
            if (mRequestHeaders == null) {
                mRequestHeaders = new HashMap<String, String>();
            }
            boolean fromCache = false;
            if (mCacheLoader != null) {
                // This is a redirect from the cache loader. Increment the
                // redirect count to avoid redirect loops.
                mCacheRedirectCount++;
                fromCache = true;
            }
            if (!checkCache(mRequestHeaders)) {
                // mRequestHandle can be null when the request was satisfied
                // by the cache, and the cache returned a redirect
                if (mRequestHandle != null) {
                    mRequestHandle.setupRedirect(mUrl, mStatusCode,
                            mRequestHeaders);
                } else {
                    // If the original request came from the cache, there is no
                    // RequestHandle, we have to create a new one through
                    // Network.requestURL.
                    Network network = Network.getInstance(getContext());
                    if (!network.requestURL(mMethod, mRequestHeaders,
                            mPostData, this, mIsHighPriority)) {
                        // Signal a bad url error if we could not load the
                        // redirection.
                        handleError(EventHandler.ERROR_BAD_URL,
                                mContext.getString(R.string.httpErrorBadUrl));
                        return;
                    }
                }
                if (fromCache) {
                    // If we are coming from a cache load, we need to transfer
                    // the redirect count to the new (or old) RequestHandle to
                    // keep the redirect count in sync.
                    mRequestHandle.setRedirectCount(mCacheRedirectCount);
                }
            } else if (!fromCache) {
                // Switching from network to cache means we need to grab the
                // redirect count from the RequestHandle to keep the count in
                // sync. Add 1 to account for the current redirect.
                mCacheRedirectCount = mRequestHandle.getRedirectCount() + 1;
            }
        } else {
            commitHeaders();
            commitLoad();
            tearDown();
        }

        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener.onRedirect(): redirect to: " +
                    redirectTo);
        }
    }

    /**
     * Parses the content-type header.
     * The first part only allows '-' if it follows x or X.
     */
    private static final Pattern CONTENT_TYPE_PATTERN =
            Pattern.compile("^((?:[xX]-)?[a-zA-Z\\*]+/[\\w\\+\\*-]+[\\.[\\w\\+-]+]*)$");

    private void parseContentTypeHeader(String contentType) {
        if (Config.LOGV) {
            Log.v(LOGTAG, "LoadListener.parseContentTypeHeader: " +
                    "contentType: " + contentType);
        }

        if (contentType != null) {
            int i = contentType.indexOf(';');
            if (i >= 0) {
                mMimeType = contentType.substring(0, i);

                int j = contentType.indexOf('=', i);
                if (j > 0) {
                    i = contentType.indexOf(';', j);
                    if (i < j) {
                        i = contentType.length();
                    }
                    mEncoding = contentType.substring(j + 1, i);
                } else {
                    mEncoding = contentType.substring(i + 1);
                }
                // Trim excess whitespace.
                mEncoding = mEncoding.trim();

                if (i < contentType.length() - 1) {
                    // for data: uri the mimeType and encoding have
                    // the form image/jpeg;base64 or text/plain;charset=utf-8
                    // or text/html;charset=utf-8;base64
                    mTransferEncoding = contentType.substring(i + 1).trim();
                }
            } else {
                mMimeType = contentType;
            }

            // Trim leading and trailing whitespace
            mMimeType = mMimeType.trim();

            try {
                Matcher m = CONTENT_TYPE_PATTERN.matcher(mMimeType);
                if (m.find()) {
                    mMimeType = m.group(1);
                } else {
                    guessMimeType();
                }
            } catch (IllegalStateException ex) {
                guessMimeType();
            }
        }
    }

    /**
     * @return The HTTP-authentication object or null if there
     * is no supported scheme in the header.
     * If there are several valid schemes present, we pick the
     * strongest one. If there are several schemes of the same
     * strength, we pick the one that comes first.
     */
    private HttpAuthHeader parseAuthHeader(String header) {
        if (header != null) {
            int posMax = 256;
            int posLen = 0;
            int[] pos = new int [posMax];

            int headerLen = header.length();
            if (headerLen > 0) {
                // first, we find all unquoted instances of 'Basic' and 'Digest'
                boolean quoted = false;
                for (int i = 0; i < headerLen && posLen < posMax; ++i) {
                    if (header.charAt(i) == '\"') {
                        quoted = !quoted;
                    } else {
                        if (!quoted) {
                            if (header.regionMatches(true, i,
                                    HttpAuthHeader.BASIC_TOKEN, 0,
                                    HttpAuthHeader.BASIC_TOKEN.length())) {
                                pos[posLen++] = i;
                                continue;
                            }

                            if (header.regionMatches(true, i,
                                    HttpAuthHeader.DIGEST_TOKEN, 0,
                                    HttpAuthHeader.DIGEST_TOKEN.length())) {
                                pos[posLen++] = i;
                                continue;
                            }
                        }
                    }
                }
            }

            if (posLen > 0) {
                // consider all digest schemes first (if any)
                for (int i = 0; i < posLen; i++) {
                    if (header.regionMatches(true, pos[i],
                                HttpAuthHeader.DIGEST_TOKEN, 0,
                                HttpAuthHeader.DIGEST_TOKEN.length())) {
                        String sub = header.substring(pos[i],
                                (i + 1 < posLen ? pos[i + 1] : headerLen));

                        HttpAuthHeader rval = new HttpAuthHeader(sub);
                        if (rval.isSupportedScheme()) {
                            // take the first match
                            return rval;
                        }
                    }
                }

                // ...then consider all basic schemes (if any)
                for (int i = 0; i < posLen; i++) {
                    if (header.regionMatches(true, pos[i],
                                HttpAuthHeader.BASIC_TOKEN, 0,
                                HttpAuthHeader.BASIC_TOKEN.length())) {
                        String sub = header.substring(pos[i],
                                (i + 1 < posLen ? pos[i + 1] : headerLen));

                        HttpAuthHeader rval = new HttpAuthHeader(sub);
                        if (rval.isSupportedScheme()) {
                            // take the first match
                            return rval;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * If the content is a redirect or not modified we should not send
     * any data into WebCore as that will cause it create a document with
     * the data, then when we try to provide the real content, it will assert.
     *
     * @return True iff the callback should be ignored.
     */
    private boolean ignoreCallbacks() {
        return (mCancelled || mAuthHeader != null ||
                (mStatusCode > 300 && mStatusCode < 400));
    }

    /**
     * Sets the current URL associated with this load.
     */
    void setUrl(String url) {
        if (url != null) {
            mUri = null;
            if (URLUtil.isNetworkUrl(url)) {
                mUrl = URLUtil.stripAnchor(url);
                try {
                    mUri = new WebAddress(mUrl);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            } else {
                mUrl = url;
            }
        }
    }

    /**
     * Guesses MIME type if one was not specified. Defaults to 'text/html'. In
     * addition, tries to guess the MIME type based on the extension.
     *
     */
    private void guessMimeType() {
        // Data urls must have a valid mime type or a blank string for the mime
        // type (implying text/plain).
        if (URLUtil.isDataUrl(mUrl) && mMimeType.length() != 0) {
            cancel();
            final String text = mContext.getString(R.string.httpErrorBadUrl);
            handleError(EventHandler.ERROR_BAD_URL, text);
        } else {
            // Note: This is ok because this is used only for the main content
            // of frames. If no content-type was specified, it is fine to
            // default to text/html.
            mMimeType = "text/html";
            String newMimeType = guessMimeTypeFromExtension();
            if (newMimeType != null) {
                mMimeType =  newMimeType;
            }
        }
    }

    /**
     * guess MIME type based on the file extension.
     */
    private String guessMimeTypeFromExtension() {
        // PENDING: need to normalize url
        if (Config.LOGV) {
            Log.v(LOGTAG, "guessMimeTypeFromExtension: mURL = " + mUrl);
        }

        String mimeType =
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        MimeTypeMap.getFileExtensionFromUrl(mUrl));

        if (mimeType != null) {
            // XXX: Until the servers send us either correct xhtml or
            // text/html, treat application/xhtml+xml as text/html.
            if (mimeType.equals("application/xhtml+xml")) {
                mimeType = "text/html";
            }
        }

        return mimeType;
    }

    /**
     * Either send a message to ourselves or queue the message if this is a
     * synchronous load.
     */
    private void sendMessageInternal(Message msg) {
        if (mSynchronous) {
            mMessageQueue.add(msg);
        } else {
            sendMessage(msg);
        }
    }

    /**
     * Cycle through our messages for synchronous loads.
     */
    /* package */ void loadSynchronousMessages() {
        if (Config.DEBUG && !mSynchronous) {
            throw new AssertionError();
        }
        // Note: this can be called twice if it is a synchronous network load,
        // and there is a cache, but it needs to go to network to validate. If 
        // validation succeed, the CacheLoader is used so this is first called 
        // from http thread. Then it is called again from WebViewCore thread 
        // after the load is completed. So make sure the queue is cleared but
        // don't set it to null.
        for (int size = mMessageQueue.size(); size > 0; size--) {
            handleMessage(mMessageQueue.remove(0));
        }
    }

    //=========================================================================
    // native functions
    //=========================================================================

    /**
     * Create a new native response object.
     * @param url The url of the resource.
     * @param statusCode The HTTP status code.
     * @param statusText The HTTP status text.
     * @param mimeType HTTP content-type.
     * @param expectedLength An estimate of the content length or the length
     *                       given by the server.
     * @param encoding HTTP encoding.
     * @param expireTime HTTP expires converted to seconds since the epoch.
     * @return The native response pointer.
     */
    private native int nativeCreateResponse(String url, int statusCode,
            String statusText, String mimeType, long expectedLength,
            String encoding, long expireTime);

    /**
     * Add a response header to the native object.
     * @param nativeResponse The native pointer.
     * @param key String key.
     * @param val String value.
     */
    private native void nativeSetResponseHeader(int nativeResponse, String key,
            String val);

    /**
     * Dispatch the response.
     * @param nativeResponse The native pointer.
     */
    private native void nativeReceivedResponse(int nativeResponse);

    /**
     * Add data to the loader.
     * @param data Byte array of data.
     * @param length Number of objects in data.
     */
    private native void nativeAddData(byte[] data, int length);

    /**
     * Tell the loader it has finished.
     */
    private native void nativeFinished();

    /**
     * tell the loader to redirect
     * @param baseUrl The base url.
     * @param redirectTo The url to redirect to.
     * @param nativeResponse The native pointer.
     * @return The new url that the resource redirected to.
     */
    private native String nativeRedirectedToUrl(String baseUrl,
            String redirectTo, int nativeResponse);

    /**
     * Tell the loader there is error
     * @param id
     * @param desc
     * @param failingUrl The url that failed.
     */
    private native void nativeError(int id, String desc, String failingUrl);

}
