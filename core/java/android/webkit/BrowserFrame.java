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
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.net.ParseException;
import android.net.WebAddress;
import android.net.http.SslCertificate;
import android.os.Handler;
import android.os.Message;
import android.util.Config;
import android.util.Log;
import android.util.TypedValue;

import junit.framework.Assert;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;

class BrowserFrame extends Handler {

    private static final String LOGTAG = "webkit";

    /**
     * Cap the number of LoadListeners that will be instantiated, so
     * we don't blow the GREF count.  Attempting to queue more than
     * this many requests will prompt an error() callback on the
     * request's LoadListener
     */
    private final static int MAX_OUTSTANDING_REQUESTS = 300;

    private final CallbackProxy mCallbackProxy;
    private final WebSettings mSettings;
    private final Context mContext;
    private final WebViewDatabase mDatabase;
    private final WebViewCore mWebViewCore;
    /* package */ boolean mLoadInitFromJava;
    private int mLoadType;
    private boolean mFirstLayoutDone = true;
    private boolean mCommitted = true;

    // Is this frame the main frame?
    private boolean mIsMainFrame;

    // Attached Javascript interfaces
    private HashMap mJSInterfaceMap;

    // message ids
    // a message posted when a frame loading is completed
    static final int FRAME_COMPLETED = 1001;
    // a message posted when the user decides the policy
    static final int POLICY_FUNCTION = 1003;

    // Note: need to keep these in sync with FrameLoaderTypes.h in native
    static final int FRAME_LOADTYPE_STANDARD = 0;
    static final int FRAME_LOADTYPE_BACK = 1;
    static final int FRAME_LOADTYPE_FORWARD = 2;
    static final int FRAME_LOADTYPE_INDEXEDBACKFORWARD = 3;
    static final int FRAME_LOADTYPE_RELOAD = 4;
    static final int FRAME_LOADTYPE_RELOADALLOWINGSTALEDATA = 5;
    static final int FRAME_LOADTYPE_SAME = 6;
    static final int FRAME_LOADTYPE_REDIRECT = 7;
    static final int FRAME_LOADTYPE_REPLACE = 8;

    // A progress threshold to switch from history Picture to live Picture
    private static final int TRANSITION_SWITCH_THRESHOLD = 75;

    // This is a field accessed by native code as well as package classes.
    /*package*/ int mNativeFrame;

    // Static instance of a JWebCoreJavaBridge to handle timer and cookie
    // requests from WebCore.
    static JWebCoreJavaBridge sJavaBridge;

    /**
     * Create a new BrowserFrame to be used in an application.
     * @param context An application context to use when retrieving assets.
     * @param w A WebViewCore used as the view for this frame.
     * @param proxy A CallbackProxy for posting messages to the UI thread and
     *              querying a client for information.
     * @param settings A WebSettings object that holds all settings.
     * XXX: Called by WebCore thread.
     */
    public BrowserFrame(Context context, WebViewCore w, CallbackProxy proxy,
            WebSettings settings) {
        // Create a global JWebCoreJavaBridge to handle timers and
        // cookies in the WebCore thread.
        if (sJavaBridge == null) {
            sJavaBridge = new JWebCoreJavaBridge();
            // set WebCore native cache size
            sJavaBridge.setCacheSize(4 * 1024 * 1024);
            // initialize CacheManager
            CacheManager.init(context);
            // create CookieSyncManager with current Context
            CookieSyncManager.createInstance(context);
        }
        AssetManager am = context.getAssets();
        nativeCreateFrame(w, am, proxy.getBackForwardList());

        mSettings = settings;
        mContext = context;
        mCallbackProxy = proxy;
        mDatabase = WebViewDatabase.getInstance(context);
        mWebViewCore = w;

        if (Config.LOGV) {
            Log.v(LOGTAG, "BrowserFrame constructor: this=" + this);
        }
    }

    /**
     * Load a url from the network or the filesystem into the main frame.
     * Following the same behaviour as Safari, javascript: URLs are not
     * passed to the main frame, instead they are evaluated immediately.
     * @param url The url to load.
     */
    public void loadUrl(String url) {
        mLoadInitFromJava = true;
        if (URLUtil.isJavaScriptUrl(url)) {
            // strip off the scheme and evaluate the string
            stringByEvaluatingJavaScriptFromString(
                    url.substring("javascript:".length()));
        } else {
            nativeLoadUrl(url);
        }
        mLoadInitFromJava = false;
    }

    /**
     * Load the content as if it was loaded by the provided base URL. The
     * failUrl is used as the history entry for the load data. If null or
     * an empty string is passed for the failUrl, then no history entry is
     * created.
     * 
     * @param baseUrl Base URL used to resolve relative paths in the content
     * @param data Content to render in the browser
     * @param mimeType Mimetype of the data being passed in
     * @param encoding Character set encoding of the provided data.
     * @param failUrl URL to use if the content fails to load or null.
     */
    public void loadData(String baseUrl, String data, String mimeType,
            String encoding, String failUrl) {
        mLoadInitFromJava = true;
        if (failUrl == null) {
            failUrl = "";
        }
        if (data == null) {
            data = "";
        }
        
        // Setup defaults for missing values. These defaults where taken from
        // WebKit's WebFrame.mm
        if (baseUrl == null || baseUrl.length() == 0) {
            baseUrl = "about:blank";
        }
        if (mimeType == null || mimeType.length() == 0) {
            mimeType = "text/html";
        }
        nativeLoadData(baseUrl, data, mimeType, encoding, failUrl);
        mLoadInitFromJava = false;
    }

    /**
     * Go back or forward the number of steps given.
     * @param steps A negative or positive number indicating the direction
     *              and number of steps to move.
     */
    public void goBackOrForward(int steps) {
        mLoadInitFromJava = true;
        nativeGoBackOrForward(steps);
        mLoadInitFromJava = false;
    }

    /**
     * native callback
     * Report an error to an activity.
     * @param errorCode The HTTP error code.
     * @param description A String description.
     * TODO: Report all errors including resource errors but include some kind
     * of domain identifier. Change errorCode to an enum for a cleaner
     * interface.
     */
    private void reportError(final int errorCode, final String description,
            final String failingUrl) {
        // As this is called for the main resource and loading will be stopped
        // after, reset the state variables.
        resetLoadingStates();
        mCallbackProxy.onReceivedError(errorCode, description, failingUrl);
    }

    private void resetLoadingStates() {
        mCommitted = true;
        mWebViewCore.mEndScaleZoom = mFirstLayoutDone == false;
        mFirstLayoutDone = true;
    }

    /* package */boolean committed() {
        return mCommitted;
    }

    /* package */boolean firstLayoutDone() {
        return mFirstLayoutDone;
    }

    /* package */int loadType() {
        return mLoadType;
    }

    /* package */void didFirstLayout() {
        if (!mFirstLayoutDone) {
            mFirstLayoutDone = true;
            // ensure {@link WebViewCore#webkitDraw} is called as we were
            // blocking the update in {@link #loadStarted}
            mWebViewCore.contentDraw();
        }
        mWebViewCore.mEndScaleZoom = true;
    }

    /**
     * native callback
     * Indicates the beginning of a new load.
     * This method will be called once for the main frame.
     */
    private void loadStarted(String url, Bitmap favicon, int loadType,
            boolean isMainFrame) {
        mIsMainFrame = isMainFrame;

        if (isMainFrame || loadType == FRAME_LOADTYPE_STANDARD) {
            mLoadType = loadType;

            if (isMainFrame) {
                // Call onPageStarted for main frames.
                mCallbackProxy.onPageStarted(url, favicon);
                // as didFirstLayout() is only called for the main frame, reset 
                // mFirstLayoutDone only for the main frames
                mFirstLayoutDone = false;
                mCommitted = false;
                // remove pending draw to block update until mFirstLayoutDone is
                // set to true in didFirstLayout()
                mWebViewCore.removeMessages(WebViewCore.EventHub.WEBKIT_DRAW);
            }

            // Note: only saves committed form data in standard load
            if (loadType == FRAME_LOADTYPE_STANDARD
                    && mSettings.getSaveFormData()) {
                final WebHistoryItem h = mCallbackProxy.getBackForwardList()
                        .getCurrentItem();
                if (h != null) {
                    String currentUrl = h.getUrl();
                    if (currentUrl != null) {
                        mDatabase.setFormData(currentUrl, getFormTextData());
                    }
                }
            }
        }
    }

    /**
     * native callback
     * Indicates the WebKit has committed to the new load
     */
    private void transitionToCommitted(int loadType, boolean isMainFrame) {
        // loadType is not used yet
        if (isMainFrame) {
            mCommitted = true;
        }
    }

    /**
     * native callback
     * <p>
     * Indicates the end of a new load.
     * This method will be called once for the main frame.
     */
    private void loadFinished(String url, int loadType, boolean isMainFrame) {
        // mIsMainFrame and isMainFrame are better be equal!!!

        if (isMainFrame || loadType == FRAME_LOADTYPE_STANDARD) {
            if (isMainFrame) {
                resetLoadingStates();
                mCallbackProxy.switchOutDrawHistory();
                mCallbackProxy.onPageFinished(url);
            }
        }
    }

    /**
     * We have received an SSL certificate for the main top-level page.
     *
     * !!!Called from the network thread!!!
     */
    void certificate(SslCertificate certificate) {
        if (mIsMainFrame) {
            // we want to make this call even if the certificate is null
            // (ie, the site is not secure)
            mCallbackProxy.onReceivedCertificate(certificate);
        }
    }

    /**
     * Destroy all native components of the BrowserFrame.
     */
    public void destroy() {
        nativeDestroyFrame();
        removeCallbacksAndMessages(null);
    }

    /**
     * Handle messages posted to us.
     * @param msg The message to handle.
     */
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case FRAME_COMPLETED: {
                if (mSettings.getSavePassword() && hasPasswordField()) {
                    if (Config.DEBUG) {
                        Assert.assertNotNull(mCallbackProxy.getBackForwardList()
                                .getCurrentItem());
                    }
                    WebAddress uri = new WebAddress(
                            mCallbackProxy.getBackForwardList().getCurrentItem()
                            .getUrl());
                    String schemePlusHost = uri.mScheme + uri.mHost;
                    String[] up = mDatabase.getUsernamePassword(schemePlusHost);
                    if (up != null && up[0] != null) {
                        setUsernamePassword(up[0], up[1]);
                    }
                }
                CacheManager.trimCacheIfNeeded();
                break;
            }

            case POLICY_FUNCTION: {
                nativeCallPolicyFunction(msg.arg1, msg.arg2);
                break;
            }

            default:
                break;
        }
    }

    /**
     * Punch-through for WebCore to set the document
     * title. Inform the Activity of the new title.
     * @param title The new title of the document.
     */
    private void setTitle(String title) {
        // FIXME: The activity must call getTitle (a native method) to get the
        // title. We should try and cache the title if we can also keep it in
        // sync with the document.
        mCallbackProxy.onReceivedTitle(title);
    }

    /**
     * Retrieves the render tree of this frame and puts it as the object for
     * the message and sends the message.
     * @param callback the message to use to send the render tree
     */
    public void externalRepresentation(Message callback) {
        callback.obj = externalRepresentation();;
        callback.sendToTarget();
    }

    /**
     * Return the render tree as a string
     */
    private native String externalRepresentation();

    /**
     * Retrieves the visual text of the current frame, puts it as the object for
     * the message and sends the message.
     * @param callback the message to use to send the visual text
     */
    public void documentAsText(Message callback) {
        callback.obj = documentAsText();;
        callback.sendToTarget();
    }

    /**
     * Return the text drawn on the screen as a string
     */
    private native String documentAsText();

    /*
     * This method is called by WebCore to inform the frame that
     * the Javascript window object has been cleared.
     * We should re-attach any attached js interfaces.
     */
    private void windowObjectCleared(int nativeFramePointer) {
        if (mJSInterfaceMap != null) {
            Iterator iter = mJSInterfaceMap.keySet().iterator();
            while (iter.hasNext())  {
                String interfaceName = (String) iter.next();
                nativeAddJavascriptInterface(nativeFramePointer,
                        mJSInterfaceMap.get(interfaceName), interfaceName);
            }
        }
    }

    /**
     * This method is called by WebCore to check whether application
     * wants to hijack url loading
     */
    public boolean handleUrl(String url) {
        if (mLoadInitFromJava == true) {
            return false;
        }
        if (mCallbackProxy.shouldOverrideUrlLoading(url)) {
            // if the url is hijacked, reset the state of the BrowserFrame
            didFirstLayout();
            return true;
        } else {
            return false;
        }
    }

    public void addJavascriptInterface(Object obj, String interfaceName) {
        if (mJSInterfaceMap == null) {
            mJSInterfaceMap = new HashMap<String, Object>();
        }
        if (mJSInterfaceMap.containsKey(interfaceName)) {
            mJSInterfaceMap.remove(interfaceName);
        }
        mJSInterfaceMap.put(interfaceName, obj);
    }

    /**
     * Start loading a resource.
     * @param loaderHandle The native ResourceLoader that is the target of the
     *                     data.
     * @param url The url to load.
     * @param method The http method.
     * @param headers The http headers.
     * @param postData If the method is "POST" postData is sent as the request
     *                 body. Is null when empty.
     * @param cacheMode The cache mode to use when loading this resource.
     * @param isHighPriority True if this resource needs to be put at the front
     *                       of the network queue.
     * @param synchronous True if the load is synchronous.
     * @return A newly created LoadListener object.
     */
    private LoadListener startLoadingResource(int loaderHandle,
                                              String url,
                                              String method,
                                              HashMap headers,
                                              byte[] postData,
                                              int cacheMode,
                                              boolean isHighPriority,
                                              boolean synchronous) {
        PerfChecker checker = new PerfChecker();

        if (mSettings.getCacheMode() != WebSettings.LOAD_DEFAULT) {
            cacheMode = mSettings.getCacheMode();
        }

        if (method.equals("POST")) {
            // Don't use the cache on POSTs when issuing a normal POST
            // request.
            if (cacheMode == WebSettings.LOAD_NORMAL) {
                cacheMode = WebSettings.LOAD_NO_CACHE;
            }
            if (mSettings.getSavePassword() && hasPasswordField()) {
                try {
                    if (Config.DEBUG) {
                        Assert.assertNotNull(mCallbackProxy.getBackForwardList()
                                .getCurrentItem());
                    }
                    WebAddress uri = new WebAddress(mCallbackProxy
                            .getBackForwardList().getCurrentItem().getUrl());
                    String schemePlusHost = uri.mScheme + uri.mHost;
                    String[] ret = getUsernamePassword();
                    // Has the user entered a username/password pair and is
                    // there some POST data
                    if (ret != null && postData != null && 
                            ret[0].length() > 0 && ret[1].length() > 0) {
                        // Check to see if the username & password appear in
                        // the post data (there could be another form on the
                        // page and that was posted instead.
                        String postString = new String(postData);
                        if (postString.contains(URLEncoder.encode(ret[0])) &&
                                postString.contains(URLEncoder.encode(ret[1]))) {
                            String[] saved = mDatabase.getUsernamePassword(
                                    schemePlusHost);
                            if (saved != null) {
                                // null username implies that user has chosen not to
                                // save password
                                if (saved[0] != null) {
                                    // non-null username implies that user has
                                    // chosen to save password, so update the 
                                    // recorded password
                                    mDatabase.setUsernamePassword(
                                            schemePlusHost, ret[0], ret[1]);
                                }
                            } else {
                                // CallbackProxy will handle creating the resume
                                // message
                                mCallbackProxy.onSavePassword(schemePlusHost, ret[0], 
                                        ret[1], null);
                            }
                        }
                    }
                } catch (ParseException ex) {
                    // if it is bad uri, don't save its password
                }
                
            }
        }

        // is this resource the main-frame top-level page?
        boolean isMainFramePage = mIsMainFrame;

        if (Config.LOGV) {
            Log.v(LOGTAG, "startLoadingResource: url=" + url + ", method="
                    + method + ", postData=" + postData + ", isHighPriority="
                    + isHighPriority + ", isMainFramePage=" + isMainFramePage);
        }

        // Create a LoadListener
        LoadListener loadListener = LoadListener.getLoadListener(mContext, this, url,
                loaderHandle, synchronous, isMainFramePage);

        mCallbackProxy.onLoadResource(url);

        if (LoadListener.getNativeLoaderCount() > MAX_OUTSTANDING_REQUESTS) {
            loadListener.error(
                    android.net.http.EventHandler.ERROR, mContext.getString(
                            com.android.internal.R.string.httpErrorTooManyRequests));
            loadListener.notifyError();
            loadListener.tearDown();
            return null;
        }

        // during synchronous load, the WebViewCore thread is blocked, so we
        // need to endCacheTransaction first so that http thread won't be 
        // blocked in setupFile() when createCacheFile.
        if (synchronous) {
            CacheManager.endCacheTransaction();
        }

        FrameLoader loader = new FrameLoader(loadListener, mSettings,
                method, isHighPriority);
        loader.setHeaders(headers);
        loader.setPostData(postData);
        // Set the load mode to the mode used for the current page.
        // If WebKit wants validation, go to network directly.
        loader.setCacheMode(headers.containsKey("If-Modified-Since")
                || headers.containsKey("If-None-Match") ? 
                        WebSettings.LOAD_NO_CACHE : cacheMode);
        // Set referrer to current URL?
        if (!loader.executeLoad()) {
            checker.responseAlert("startLoadingResource fail");
        }
        checker.responseAlert("startLoadingResource succeed");

        if (synchronous) {
            CacheManager.startCacheTransaction();
        }

        return !synchronous ? loadListener : null;
    }

    /**
     * Set the progress for the browser activity.  Called by native code.
     * Uses a delay so it does not happen too often.
     * @param newProgress An int between zero and one hundred representing
     *                    the current progress percentage of loading the page.
     */
    private void setProgress(int newProgress) {
        mCallbackProxy.onProgressChanged(newProgress);
        if (newProgress == 100) {
            sendMessageDelayed(obtainMessage(FRAME_COMPLETED), 100);
        }
        // FIXME: Need to figure out a better way to switch out of the history
        // drawing mode. Maybe we can somehow compare the history picture with 
        // the current picture, and switch when it contains more content.
        if (mFirstLayoutDone && newProgress > TRANSITION_SWITCH_THRESHOLD) {
            mCallbackProxy.switchOutDrawHistory();
        }
    }

    /**
     * Send the icon to the activity for display.
     * @param icon A Bitmap representing a page's favicon.
     */
    private void didReceiveIcon(Bitmap icon) {
        mCallbackProxy.onReceivedIcon(icon);
    }

    /**
     * Request a new window from the client.
     * @return The BrowserFrame object stored in the new WebView.
     */
    private BrowserFrame createWindow(boolean dialog, boolean userGesture) {
        WebView w = mCallbackProxy.createWindow(dialog, userGesture);
        if (w != null) {
            return w.getWebViewCore().getBrowserFrame();
        }
        return null;
    }

    /**
     * Try to focus this WebView.
     */
    private void requestFocus() {
        mCallbackProxy.onRequestFocus();
    }

    /**
     * Close this frame and window.
     */
    private void closeWindow(WebViewCore w) {
        mCallbackProxy.onCloseWindow(w.getWebView());
    }

    // XXX: Must match PolicyAction in FrameLoaderTypes.h in webcore
    static final int POLICY_USE = 0;
    static final int POLICY_IGNORE = 2;

    private void decidePolicyForFormResubmission(int policyFunction) {
        Message dontResend = obtainMessage(POLICY_FUNCTION, policyFunction,
                POLICY_IGNORE);
        Message resend = obtainMessage(POLICY_FUNCTION, policyFunction,
                POLICY_USE);
        mCallbackProxy.onFormResubmission(dontResend, resend);
    }

    /**
     * Tell the activity to update its global history.
     */
    private void updateVisitedHistory(String url, boolean isReload) {
        mCallbackProxy.doUpdateVisitedHistory(url, isReload);
    }

    /**
     * Get the CallbackProxy for sending messages to the UI thread.
     */
    /* package */ CallbackProxy getCallbackProxy() {
        return mCallbackProxy;
    }

    /**
     * Returns the User Agent used by this frame
     */
    String getUserAgentString() {
        return mSettings.getUserAgentString();
    }

    // these ids need to be in sync with enum RAW_RES_ID in WebFrame
    private static final int NODOMAIN = 1;
    private static final int LOADERROR = 2;

    String getRawResFilename(int id) {
        int resid;
        switch (id) {
            case NODOMAIN:
                resid = com.android.internal.R.raw.nodomain;
                break;

            case LOADERROR:
                resid = com.android.internal.R.raw.loaderror;
                break;

            default:
                Log.e(LOGTAG, "getRawResFilename got incompatible resource ID");
                return new String();
        }
        TypedValue value = new TypedValue();
        mContext.getResources().getValue(resid, value, true);
        return value.string.toString();
    }

    //==========================================================================
    // native functions
    //==========================================================================

    /**
     * Create a new native frame for a given WebView
     * @param w     A WebView that the frame draws into.
     * @param am    AssetManager to use to get assets.
     * @param list  The native side will add and remove items from this list as
     *              the native list changes.
     */
    private native void nativeCreateFrame(WebViewCore w, AssetManager am,
            WebBackForwardList list);

    /**
     * Destroy the native frame.
     */
    public native void nativeDestroyFrame();

    private native void nativeCallPolicyFunction(int policyFunction,
            int decision);

    /**
     * Reload the current main frame.
     */
    public native void reload(boolean allowStale);

    /**
     * Go back or forward the number of steps given.
     * @param steps A negative or positive number indicating the direction
     *              and number of steps to move.
     */
    private native void nativeGoBackOrForward(int steps);

    /**
     * stringByEvaluatingJavaScriptFromString will execute the
     * JS passed in in the context of this browser frame.
     * @param script A javascript string to execute
     * 
     * @return string result of execution or null
     */
    public native String stringByEvaluatingJavaScriptFromString(String script);

    /**
     * Add a javascript interface to the main frame.
     */
    private native void nativeAddJavascriptInterface(int nativeFramePointer,
            Object obj, String interfaceName);

    /**
     * Enable or disable the native cache.
     */
    /* FIXME: The native cache is always on for now until we have a better
     * solution for our 2 caches. */
    private native void setCacheDisabled(boolean disabled);

    public native boolean cacheDisabled();

    public native void clearCache();

    /**
     * Returns false if the url is bad.
     */
    private native void nativeLoadUrl(String url);

    private native void nativeLoadData(String baseUrl, String data,
            String mimeType, String encoding, String failUrl);

    /**
     * Stop loading the current page.
     */
    public void stopLoading() {
        if (mIsMainFrame) {
            resetLoadingStates();
        }
        nativeStopLoading();
    }

    private native void nativeStopLoading();

    /**
     * Return true if the document has images.
     */
    public native boolean documentHasImages();

    /**
     * @return TRUE if there is a password field in the current frame
     */
    private native boolean hasPasswordField();

    /**
     * Get username and password in the current frame. If found, String[0] is
     * username and String[1] is password. Otherwise return NULL.
     * @return String[]
     */
    private native String[] getUsernamePassword();

    /**
     * Set username and password to the proper fields in the current frame
     * @param username
     * @param password
     */
    private native void setUsernamePassword(String username, String password);

    /**
     * Get form's "text" type data associated with the current frame.
     * @return HashMap If succeed, returns a list of name/value pair. Otherwise
     *         returns null.
     */
    private native HashMap getFormTextData();
}
