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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ParseException;
import android.net.Uri;
import android.net.WebAddress;
import android.net.http.EventHandler;
import android.net.http.Headers;
import android.net.http.HttpAuthHeader;
import android.net.http.RequestHandle;
import android.net.http.SslCertificate;
import android.net.http.SslError;

import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.webkit.CacheManager.CacheResult;
import android.webkit.JniUtil;

import com.android.internal.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

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
    private static final int HTTP_PARTIAL_CONTENT = 206;
    private static final int HTTP_MOVED_PERMANENTLY = 301;
    private static final int HTTP_FOUND = 302;
    private static final int HTTP_SEE_OTHER = 303;
    private static final int HTTP_NOT_MODIFIED = 304;
    private static final int HTTP_TEMPORARY_REDIRECT = 307;
    private static final int HTTP_AUTH = 401;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_PROXY_AUTH = 407;

    private static int sNativeLoaderCount;

    private final ByteArrayBuilder mDataBuilder = new ByteArrayBuilder();

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
    private boolean  mFromCache = false;
    private HttpAuthHeader mAuthHeader;
    private int      mErrorID = OK;
    private String   mErrorDescription;
    private SslError mSslError;
    private RequestHandle mRequestHandle;
    private RequestHandle mSslErrorRequestHandle;
    private long     mPostIdentifier;
    private boolean  mSetNativeResponse;

    // Request data. It is only valid when we are doing a load from the
    // cache. It is needed if the cache returns a redirect
    private String mMethod;
    private Map<String, String> mRequestHeaders;
    private byte[] mPostData;
    // Flag to indicate that this load is synchronous.
    private boolean mSynchronous;
    private Vector<Message> mMessageQueue;

    // Does this loader correspond to the main-frame top-level page?
    private boolean mIsMainPageLoader;
    // Does this loader correspond to the main content (as opposed to a supporting resource)
    private final boolean mIsMainResourceLoader;
    private final boolean mUserGesture;

    private Headers mHeaders;

    private final String mUsername;
    private final String mPassword;

    // =========================================================================
    // Public functions
    // =========================================================================

    public static LoadListener getLoadListener(Context context,
            BrowserFrame frame, String url, int nativeLoader,
            boolean synchronous, boolean isMainPageLoader,
            boolean isMainResource, boolean userGesture, long postIdentifier,
            String username, String password) {

        sNativeLoaderCount += 1;
        return new LoadListener(context, frame, url, nativeLoader, synchronous,
                isMainPageLoader, isMainResource, userGesture, postIdentifier,
                username, password);
    }

    public static int getNativeLoaderCount() {
        return sNativeLoaderCount;
    }

    LoadListener(Context context, BrowserFrame frame, String url,
            int nativeLoader, boolean synchronous, boolean isMainPageLoader,
            boolean isMainResource, boolean userGesture, long postIdentifier,
            String username, String password) {
        assert !JniUtil.useChromiumHttpStack();

        if (DebugFlags.LOAD_LISTENER) {
            Log.v(LOGTAG, "LoadListener constructor url=" + url);
        }
        mContext = context;
        mBrowserFrame = frame;
        setUrl(url);
        mNativeLoader = nativeLoader;
        mSynchronous = synchronous;
        if (synchronous) {
            mMessageQueue = new Vector<Message>();
        }
        mIsMainPageLoader = isMainPageLoader;
        mIsMainResourceLoader = isMainResource;
        mUserGesture = userGesture;
        mPostIdentifier = postIdentifier;
        mUsername = username;
        mPassword = password;
    }

    /**
     * We keep a count of refs to the nativeLoader so we do not create
     * so many LoadListeners that the GREFs blow up
     */
    private void clearNativeLoader() {
        sNativeLoaderCount -= 1;
        mNativeLoader = 0;
        mSetNativeResponse = false;
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
        if (DebugFlags.LOAD_LISTENER) Log.v(LOGTAG, "LoadListener.headers");
        // call db (setCookie) in the non-WebCore thread
        if (mCancelled) return;
        ArrayList<String> cookies = headers.getSetCookie();
        for (int i = 0; i < cookies.size(); ++i) {
            CookieManager.getInstance().setCookie(mUri, cookies.get(i));
        }
        sendMessageInternal(obtainMessage(MSG_CONTENT_HEADERS, headers));
    }

    // This is the same regex that DOMImplementation uses to check for xml
    // content. Use this to check if another Activity wants to handle the
    // content before giving it to webkit.
    private static final String XML_MIME_TYPE =
            "^[\\w_\\-+~!$\\^{}|.%'`#&*]+/" +
            "[\\w_\\-+~!$\\^{}|.%'`#&*]+\\+xml$";

    // Does the header parsing work on the WebCore thread.
    private void handleHeaders(Headers headers) {
        if (mCancelled) return;

        // Note: the headers we care in LoadListeners, like
        // content-type/content-length, should not be updated for partial
        // content. Just skip here and go ahead with adding data.
        if (mStatusCode == HTTP_PARTIAL_CONTENT) {
            // we don't support cache for partial content yet
            WebViewWorker.getHandler().obtainMessage(
                    WebViewWorker.MSG_REMOVE_CACHE, this).sendToTarget();
            return;
        }

        mHeaders = headers;

        long contentLength = headers.getContentLength();
        if (contentLength != Headers.NO_CONTENT_LENGTH) {
            mContentLength = contentLength;
        } else {
            mContentLength = 0;
        }

        String contentType = headers.getContentType();
        if (contentType != null) {
            parseContentTypeHeader(contentType);
            mMimeType = MimeTypeMap.getSingleton().remapGenericMimeType(
                    mMimeType, mUrl, headers.getContentDisposition());
        } else {
            /* Often when servers respond with 304 Not Modified or a
               Redirect, then they don't specify a MIMEType. When this
               occurs, the function below is called.  In the case of
               304 Not Modified, the cached headers are used rather
               than the headers that are returned from the server. */
            guessMimeType();
        }
        // At this point, mMimeType has been set to non-null.
        if (mIsMainPageLoader && mIsMainResourceLoader && mUserGesture &&
                Pattern.matches(XML_MIME_TYPE, mMimeType) &&
                !mMimeType.equalsIgnoreCase("application/xhtml+xml")) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(url()), mMimeType);
            ResolveInfo info = mContext.getPackageManager().resolveActivity(i,
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && !mContext.getPackageName().equals(
                    info.activityInfo.packageName)) {
                // someone (other than the current app) knows how to
                // handle this mime type.
                try {
                    mContext.startActivity(i);
                    mBrowserFrame.stopLoading();
                    return;
                } catch (ActivityNotFoundException ex) {
                    // continue loading internally.
                }
            }
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
            // we failed, if we must authenticate again now and
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
            // for POST request, only cache the result if there is an identifier
            // associated with it. postUrl() or form submission should set the
            // identifier while XHR POST doesn't.
            if (!mFromCache && mRequestHandle != null
                    && (!mRequestHandle.getMethod().equals("POST")
                            || mPostIdentifier != 0)) {
                WebViewWorker.CacheCreateData data = new WebViewWorker.CacheCreateData();
                data.mListener = this;
                data.mUrl = mUrl;
                data.mMimeType = mMimeType;
                data.mStatusCode = mStatusCode;
                data.mPostId = mPostIdentifier;
                data.mHeaders = headers;
                WebViewWorker.getHandler().obtainMessage(
                        WebViewWorker.MSG_CREATE_CACHE, data).sendToTarget();
            }
            WebViewWorker.CacheEncoding ce = new WebViewWorker.CacheEncoding();
            ce.mEncoding = mEncoding;
            ce.mListener = this;
            WebViewWorker.getHandler().obtainMessage(
                    WebViewWorker.MSG_UPDATE_CACHE_ENCODING, ce).sendToTarget();
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
        if (DebugFlags.LOAD_LISTENER) {
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
        mMimeType = "";
        mEncoding = "";
        mTransferEncoding = "";
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
     * Implementation of certificate handler for EventHandler. Called
     * before a resource is requested. In this context, can be called
     * multiple times if we have redirects
     *
     * IMPORTANT: as this is called from network thread, can't call
     * native directly
     *
     * @param certificate The SSL certifcate or null if the request
     * was not secure
     */
    public void certificate(SslCertificate certificate) {
        if (DebugFlags.LOAD_LISTENER) {
            Log.v(LOGTAG, "LoadListener.certificate: " + certificate);
        }
        sendMessageInternal(obtainMessage(MSG_SSL_CERTIFICATE, certificate));
    }

    // Handle the certificate on the WebCore thread.
    private void handleCertificate(SslCertificate certificate) {
        // if this is main resource of the top frame
        if (mIsMainPageLoader && mIsMainResourceLoader) {
            // update the browser frame with certificate
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
        if (DebugFlags.LOAD_LISTENER) {
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
     * work of decoding the data and appending it to the data builder.
     */
    public void data(byte[] data, int length) {
        if (DebugFlags.LOAD_LISTENER) {
            Log.v(LOGTAG, "LoadListener.data(): url: " + url());
        }

        // The reason isEmpty() and append() need to synchronized together is
        // because it is possible for getFirstChunk() to be called multiple
        // times between isEmpty() and append(). This could cause commitLoad()
        // to finish before processing the newly appended data and no message
        // will be sent.
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
        if (DebugFlags.LOAD_LISTENER) {
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
                    // If this is the first attempt to authenticate, try again with the username and
                    // password supplied in the URL, if present.
                    if (!mAuthFailed && mUsername != null && mPassword != null) {
                        String host = mAuthHeader.isProxy() ?
                                Network.getInstance(mContext).getProxyHostname() :
                                mUri.getHost();
                        HttpAuthHandlerImpl.onReceivedCredentials(this, host,
                                mAuthHeader.getRealm(), mUsername, mPassword);
                        makeAuthResponse(mUsername, mPassword);
                    } else {
                        Network.getInstance(mContext).handleAuthRequest(this);
                    }
                    return;
                }
                break;  // use default

            case HTTP_NOT_MODIFIED:
                // Server could send back NOT_MODIFIED even if we didn't
                // ask for it, so make sure we have a valid CacheLoader
                // before calling it.
                if (mCacheLoader != null) {
                    if (isSynchronous()) {
                        mCacheLoader.load();
                    } else {
                        // Load the cached file in a separate thread
                        WebViewWorker.getHandler().obtainMessage(
                                WebViewWorker.MSG_ADD_STREAMLOADER, mCacheLoader)
                                .sendToTarget();
                    }
                    mFromCache = true;
                    if (DebugFlags.LOAD_LISTENER) {
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
        mFromCache = true;
    }

    /**
     * Check the cache for the current URL, and load it if it is valid.
     *
     * @param headers for the request
     * @return true if cached response is used.
     */
    boolean checkCache(Map<String, String> headers) {
        // Get the cache file name for the current URL
        CacheResult result = CacheManager.getCacheFile(url(), mPostIdentifier,
                headers);

        // Go ahead and set the cache loader to null in case the result is
        // null.
        mCacheLoader = null;
        // reset the flag
        mFromCache = false;

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
                if (DebugFlags.LOAD_LISTENER) {
                    Log.v(LOGTAG, "FrameLoader: HTTP URL in cache " +
                            "and usable: " + url());
                }
                if (isSynchronous()) {
                    mCacheLoader.load();
                } else {
                    // Load the cached file in a separate thread
                    WebViewWorker.getHandler().obtainMessage(
                            WebViewWorker.MSG_ADD_STREAMLOADER, mCacheLoader)
                            .sendToTarget();
                }
                mFromCache = true;
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
    public boolean handleSslErrorRequest(SslError error) {
        if (DebugFlags.LOAD_LISTENER) {
            Log.v(LOGTAG,
                    "LoadListener.handleSslErrorRequest(): url:" + url() +
                    " primary error: " + error.getPrimaryError() +
                    " certificate: " + error.getCertificate());
        }
        // Check the cached preference table before sending a message. This
        // will prevent waiting for an already available answer.
        if (Network.getInstance(mContext).checkSslPrefTable(this, error)) {
            return true;
        }
        // Do not post a message for a synchronous request. This will cause a
        // deadlock. Just bail on the request.
        if (isSynchronous()) {
            mRequestHandle.handleSslErrorResponse(false);
            return true;
        }
        sendMessageInternal(obtainMessage(MSG_SSL_ERROR, error));
        // if it has been canceled, return false so that the network thread
        // won't be blocked. If it is not canceled, save the mRequestHandle
        // so that if it is canceled when MSG_SSL_ERROR is handled, we can
        // still call handleSslErrorResponse which will call restartConnection
        // to unblock the network thread.
        if (!mCancelled) {
            mSslErrorRequestHandle = mRequestHandle;
        }
        return !mCancelled;
    }

    // Handle the ssl error on the WebCore thread.
    private void handleSslError(SslError error) {
        if (!mCancelled) {
            mSslError = error;
            Network.getInstance(mContext).handleSslErrorRequest(this);
        } else if (mSslErrorRequestHandle != null) {
            mSslErrorRequestHandle.handleSslErrorResponse(true);
        }
        mSslErrorRequestHandle = null;
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
            mBrowserFrame.stopLoading();
            tearDown();
        }
    }

    /**
     * Uses user-supplied credentials to restart a request. If the credentials
     * are null, cancel the request.
     */
    void handleAuthResponse(String username, String password) {
        if (DebugFlags.LOAD_LISTENER) {
            Log.v(LOGTAG, "LoadListener.handleAuthResponse: url: " + mUrl
                    + " username: " + username
                    + " password: " + password);
        }
        if (username != null && password != null) {
            makeAuthResponse(username, password);
        } else {
            // Commit whatever data we have and tear down the loader.
            commitLoad();
            tearDown();
        }
    }

    void makeAuthResponse(String username, String password) {
        if (mAuthHeader == null || mRequestHandle == null) {
            return;
        }

        mAuthHeader.setUsername(username);
        mAuthHeader.setPassword(password);

        int scheme = mAuthHeader.getScheme();
        if (scheme == HttpAuthHeader.BASIC) {
            // create a basic response
            boolean isProxy = mAuthHeader.isProxy();

            mRequestHandle.setupBasicAuthResponse(isProxy, username, password);
        } else if (scheme == HttpAuthHeader.DIGEST) {
            // create a digest response
            boolean isProxy = mAuthHeader.isProxy();

            String realm     = mAuthHeader.getRealm();
            String nonce     = mAuthHeader.getNonce();
            String qop       = mAuthHeader.getQop();
            String algorithm = mAuthHeader.getAlgorithm();
            String opaque    = mAuthHeader.getOpaque();

            mRequestHandle.setupDigestAuthResponse(isProxy, username, password,
                    realm, nonce, qop, algorithm, opaque);
        }
    }

    /**
     * This is called when a request can be satisfied by the cache, however,
     * the cache result could be a redirect. In this case we need to issue
     * the network request.
     * @param method
     * @param headers
     * @param postData
     */
    void setRequestData(String method, Map<String, String> headers, 
            byte[] postData) {
        mMethod = method;
        mRequestHeaders = headers;
        mPostData = postData;
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
            return mUri.getHost();
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

    long postIdentifier() {
        return mPostIdentifier;
    }

    void attachRequestHandle(RequestHandle requestHandle) {
        if (DebugFlags.LOAD_LISTENER) {
            Log.v(LOGTAG, "LoadListener.attachRequestHandle(): " +
                    "requestHandle: " +  requestHandle);
        }
        mRequestHandle = requestHandle;
    }

    void detachRequestHandle() {
        if (DebugFlags.LOAD_LISTENER) {
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
        // remove the cache
        WebViewWorker.getHandler().obtainMessage(
                WebViewWorker.MSG_REMOVE_CACHE, this).sendToTarget();

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
    static boolean willLoadFromCache(String url, long identifier) {
        assert !JniUtil.useChromiumHttpStack();
        boolean inCache =
                CacheManager.getCacheFile(url, identifier, null) != null;
        if (DebugFlags.LOAD_LISTENER) {
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

    String transferEncoding() {
        return mTransferEncoding;
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
        // when it is not redirect. If we received a not-modified response from
        // the server and mCacheLoader is not null, do not send the response to
        // webkit. This is just a validation response for loading from the
        // cache.
        if ((mStatusCode >= 301 && mStatusCode <= 303) || mStatusCode == 307 ||
                (mStatusCode == 304 && mCacheLoader != null)) {
            return;
        }

        commitHeaders();
    }

    // This commits the headers without checking the response status code.
    private void commitHeaders() {
        if (mIsMainPageLoader && CertTool.getCertType(mMimeType) != null) {
            // In the case of downloading certificate, we will save it to the
            // KeyStore in commitLoad. Do not call webcore.
            return;
        }

        // If the response is an authentication and we've resent the
        // request with some credentials then don't commit the headers
        // of this response; wait for the response to the request with the
        // credentials.
        if (mAuthHeader != null) {
            return;
        }

        setNativeResponse();
    }

    private void setNativeResponse() {
        int nativeResponse = createNativeResponse();
        // The native code deletes the native response object.
        nativeReceivedResponse(nativeResponse);
        mSetNativeResponse = true;
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
                originalUrl(), statusCode, mStatusText,
                mMimeType, mContentLength, mEncoding);
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
        if (!mSetNativeResponse) {
            setNativeResponse();
        }

        if (mIsMainPageLoader) {
            String type = CertTool.getCertType(mMimeType);
            if (type != null) {
                // This must be synchronized so that no more data can be added
                // after getByteSize returns.
                synchronized (mDataBuilder) {
                    // In the case of downloading certificate, we will save it
                    // to the KeyStore and stop the current loading so that it
                    // will not generate a new history page
                    byte[] cert = new byte[mDataBuilder.getByteSize()];
                    int offset = 0;
                    while (true) {
                        ByteArrayBuilder.Chunk c = mDataBuilder.getFirstChunk();
                        if (c == null) break;

                        if (c.mLength != 0) {
                            System.arraycopy(c.mArray, 0, cert, offset, c.mLength);
                            offset += c.mLength;
                        }
                        c.release();
                    }
                    CertTool.addCertificate(mContext, type, cert);
                    mBrowserFrame.stopLoading();
                    return;
                }
            }
        }

        // Give the data to WebKit now. We don't have to synchronize on
        // mDataBuilder here because pulling each chunk removes it from the
        // internal list so it cannot be modified.
        ByteArrayBuilder.Chunk c;
        while (true) {
            c = mDataBuilder.getFirstChunk();
            if (c == null) break;

            if (c.mLength != 0) {
                nativeAddData(c.mArray, c.mLength);
                WebViewWorker.CacheData data = new WebViewWorker.CacheData();
                data.mListener = this;
                data.mChunk = c;
                WebViewWorker.getHandler().obtainMessage(
                        WebViewWorker.MSG_APPEND_CACHE, data).sendToTarget();
            } else {
                c.release();
            }
        }
    }

    /**
     * Tear down the load. Subclasses should clean up any mess because of
     * cancellation or errors during the load.
     */
    void tearDown() {
        if (getErrorID() == OK) {
            WebViewWorker.CacheSaveData data = new WebViewWorker.CacheSaveData();
            data.mListener = this;
            data.mUrl = mUrl;
            data.mPostId = mPostIdentifier;
            WebViewWorker.getHandler().obtainMessage(
                    WebViewWorker.MSG_SAVE_CACHE, data).sendToTarget();
        } else {
            WebViewWorker.getHandler().obtainMessage(
                    WebViewWorker.MSG_REMOVE_CACHE, this).sendToTarget();
        }
        if (mNativeLoader != 0) {
            if (!mSetNativeResponse) {
                setNativeResponse();
            }

            nativeFinished();
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
     * Pause the load. For example, if a plugin is unable to accept more data,
     * we pause reading from the request. Called directly from the WebCore thread.
     */
    void pauseLoad(boolean pause) {
        if (mRequestHandle != null) {
            mRequestHandle.pauseRequest(pause);
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
        if (DebugFlags.LOAD_LISTENER) {
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

        WebViewWorker.getHandler().obtainMessage(
                WebViewWorker.MSG_REMOVE_CACHE, this).sendToTarget();
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
                if (!mSetNativeResponse) {
                    setNativeResponse();
                }
                nativeAddData(text.getBytes(), text.length());
                nativeFinished();
                clearNativeLoader();
                return;
            }


            // Cache the redirect response
            if (getErrorID() == OK) {
                WebViewWorker.CacheSaveData data = new WebViewWorker.CacheSaveData();
                data.mListener = this;
                data.mUrl = mUrl;
                data.mPostId = mPostIdentifier;
                WebViewWorker.getHandler().obtainMessage(
                        WebViewWorker.MSG_SAVE_CACHE, data).sendToTarget();
            } else {
                WebViewWorker.getHandler().obtainMessage(
                        WebViewWorker.MSG_REMOVE_CACHE, this).sendToTarget();
            }

            // Saving a copy of the unstripped url for the response
            mOriginalUrl = redirectTo;
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
                    try {
                        mRequestHandle.setupRedirect(mUrl, mStatusCode,
                                mRequestHeaders);
                    } catch(RuntimeException e) {
                        Log.e(LOGTAG, e.getMessage());
                        // Signal a bad url error if we could not load the
                        // redirection.
                        handleError(EventHandler.ERROR_BAD_URL,
                                mContext.getString(R.string.httpErrorBadUrl));
                        return;
                    }
                } else {
                    // If the original request came from the cache, there is no
                    // RequestHandle, we have to create a new one through
                    // Network.requestURL.
                    Network network = Network.getInstance(getContext());
                    if (!network.requestURL(mMethod, mRequestHeaders,
                            mPostData, this)) {
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

        if (DebugFlags.LOAD_LISTENER) {
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

    /* package */ void parseContentTypeHeader(String contentType) {
        if (DebugFlags.LOAD_LISTENER) {
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
                mEncoding = mEncoding.trim().toLowerCase();

                if (i < contentType.length() - 1) {
                    // for data: uri the mimeType and encoding have
                    // the form image/jpeg;base64 or text/plain;charset=utf-8
                    // or text/html;charset=utf-8;base64
                    mTransferEncoding =
                            contentType.substring(i + 1).trim().toLowerCase();
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
        // Ensure mMimeType is lower case.
        mMimeType = mMimeType.toLowerCase();
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
                // Allow 305 (Use Proxy) to call through.
                (mStatusCode > 300 && mStatusCode < 400 && mStatusCode != 305));
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
            String newMimeType = guessMimeTypeFromExtension(mUrl);
            if (newMimeType != null) {
                mMimeType = newMimeType;
            }
        }
    }

    /**
     * guess MIME type based on the file extension.
     */
    private String guessMimeTypeFromExtension(String url) {
        // PENDING: need to normalize url
        if (DebugFlags.LOAD_LISTENER) {
            Log.v(LOGTAG, "guessMimeTypeFromExtension: url = " + url);
        }

        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(url));
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
        if (DebugFlags.LOAD_LISTENER && !mSynchronous) {
            throw new AssertionError();
        }
        // Note: this can be called twice if it is a synchronous network load,
        // and there is a cache, but it needs to go to network to validate. If 
        // validation succeed, the CacheLoader is used so this is first called 
        // from http thread. Then it is called again from WebViewCore thread 
        // after the load is completed. So make sure the queue is cleared but
        // don't set it to null.
        while (!mMessageQueue.isEmpty()) {
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
     * @return The native response pointer.
     */
    private native int nativeCreateResponse(String url, int statusCode,
            String statusText, String mimeType, long expectedLength,
            String encoding);

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
