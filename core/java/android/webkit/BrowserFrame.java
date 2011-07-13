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

import android.app.ActivityManager;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.graphics.Bitmap;
import android.net.ParseException;
import android.net.Uri;
import android.net.WebAddress;
import android.net.http.ErrorStrings;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.Surface;
import android.view.ViewRootImpl;
import android.view.WindowManager;

import junit.framework.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URLEncoder;
import java.nio.charset.Charsets;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.harmony.security.provider.cert.X509CertImpl;

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
    // Flag for blocking messages. This is used during destroy() so
    // that if the UI thread posts any messages after the message
    // queue has been cleared,they are ignored.
    private boolean mBlockMessages = false;
    private int mOrientation = -1;

    // Is this frame the main frame?
    private boolean mIsMainFrame;

    // Attached Javascript interfaces
    private Map<String, Object> mJavaScriptObjects;
    private Set<Object> mRemovedJavaScriptObjects;

    // Key store handler when Chromium HTTP stack is used.
    private KeyStoreHandler mKeyStoreHandler = null;

    // Implementation of the searchbox API.
    private final SearchBoxImpl mSearchBox;

    // message ids
    // a message posted when a frame loading is completed
    static final int FRAME_COMPLETED = 1001;
    // orientation change message
    static final int ORIENTATION_CHANGED = 1002;
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

    private static class ConfigCallback implements ComponentCallbacks {
        private final ArrayList<WeakReference<Handler>> mHandlers =
                new ArrayList<WeakReference<Handler>>();
        private final WindowManager mWindowManager;

        ConfigCallback(WindowManager wm) {
            mWindowManager = wm;
        }

        public synchronized void addHandler(Handler h) {
            // No need to ever remove a Handler. If the BrowserFrame is
            // destroyed, it will be collected and the WeakReference set to
            // null. If it happens to still be around during a configuration
            // change, the message will be ignored.
            mHandlers.add(new WeakReference<Handler>(h));
        }

        public void onConfigurationChanged(Configuration newConfig) {
            if (mHandlers.size() == 0) {
                return;
            }
            int orientation =
                    mWindowManager.getDefaultDisplay().getOrientation();
            switch (orientation) {
                case Surface.ROTATION_90:
                    orientation = 90;
                    break;
                case Surface.ROTATION_180:
                    orientation = 180;
                    break;
                case Surface.ROTATION_270:
                    orientation = -90;
                    break;
                case Surface.ROTATION_0:
                    orientation = 0;
                    break;
                default:
                    break;
            }
            synchronized (this) {
                // Create a list of handlers to remove. Go ahead and make it
                // the same size to avoid resizing.
                ArrayList<WeakReference> handlersToRemove =
                        new ArrayList<WeakReference>(mHandlers.size());
                for (WeakReference<Handler> wh : mHandlers) {
                    Handler h = wh.get();
                    if (h != null) {
                        h.sendMessage(h.obtainMessage(ORIENTATION_CHANGED,
                                    orientation, 0));
                    } else {
                        handlersToRemove.add(wh);
                    }
                }
                // Now remove all the null references.
                for (WeakReference weak : handlersToRemove) {
                    mHandlers.remove(weak);
                }
            }
        }

        public void onLowMemory() {}
    }
    static ConfigCallback sConfigCallback;

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
            WebSettings settings, Map<String, Object> javascriptInterfaces) {

        Context appContext = context.getApplicationContext();

        // Create a global JWebCoreJavaBridge to handle timers and
        // cookies in the WebCore thread.
        if (sJavaBridge == null) {
            sJavaBridge = new JWebCoreJavaBridge();
            // set WebCore native cache size
            ActivityManager am = (ActivityManager) context
                    .getSystemService(Context.ACTIVITY_SERVICE);
            if (am.getMemoryClass() > 16) {
                sJavaBridge.setCacheSize(8 * 1024 * 1024);
            } else {
                sJavaBridge.setCacheSize(4 * 1024 * 1024);
            }
            // initialize CacheManager
            CacheManager.init(appContext);
            // create CookieSyncManager with current Context
            CookieSyncManager.createInstance(appContext);
            // create PluginManager with current Context
            PluginManager.getInstance(appContext);
        }

        if (sConfigCallback == null) {
            sConfigCallback = new ConfigCallback(
                    (WindowManager) appContext.getSystemService(
                            Context.WINDOW_SERVICE));
            ViewRootImpl.addConfigCallback(sConfigCallback);
        }
        sConfigCallback.addHandler(this);

        mJavaScriptObjects = javascriptInterfaces;
        if (mJavaScriptObjects == null) {
            mJavaScriptObjects = new HashMap<String, Object>();
        }
        mRemovedJavaScriptObjects = new HashSet<Object>();

        mSettings = settings;
        mContext = context;
        mCallbackProxy = proxy;
        mDatabase = WebViewDatabase.getInstance(appContext);
        mWebViewCore = w;

        mSearchBox = new SearchBoxImpl(mWebViewCore, mCallbackProxy);
        mJavaScriptObjects.put(SearchBoxImpl.JS_INTERFACE_NAME, mSearchBox);

        AssetManager am = context.getAssets();
        nativeCreateFrame(w, am, proxy.getBackForwardList());

        if (DebugFlags.BROWSER_FRAME) {
            Log.v(LOGTAG, "BrowserFrame constructor: this=" + this);
        }
    }

    /**
     * Load a url from the network or the filesystem into the main frame.
     * Following the same behaviour as Safari, javascript: URLs are not passed
     * to the main frame, instead they are evaluated immediately.
     * @param url The url to load.
     * @param extraHeaders The extra headers sent with this url. This should not
     *            include the common headers like "user-agent". If it does, it
     *            will be replaced by the intrinsic value of the WebView.
     */
    public void loadUrl(String url, Map<String, String> extraHeaders) {
        mLoadInitFromJava = true;
        if (URLUtil.isJavaScriptUrl(url)) {
            // strip off the scheme and evaluate the string
            stringByEvaluatingJavaScriptFromString(
                    url.substring("javascript:".length()));
        } else {
            nativeLoadUrl(url, extraHeaders);
        }
        mLoadInitFromJava = false;
    }

    /**
     * Load a url with "POST" method from the network into the main frame.
     * @param url The url to load.
     * @param data The data for POST request.
     */
    public void postUrl(String url, byte[] data) {
        mLoadInitFromJava = true;
        nativePostUrl(url, data);
        mLoadInitFromJava = false;
    }

    /**
     * Load the content as if it was loaded by the provided base URL. The
     * historyUrl is used as the history entry for the load data.
     * 
     * @param baseUrl Base URL used to resolve relative paths in the content
     * @param data Content to render in the browser
     * @param mimeType Mimetype of the data being passed in
     * @param encoding Character set encoding of the provided data.
     * @param historyUrl URL to use as the history entry.
     */
    public void loadData(String baseUrl, String data, String mimeType,
            String encoding, String historyUrl) {
        mLoadInitFromJava = true;
        if (historyUrl == null || historyUrl.length() == 0) {
            historyUrl = "about:blank";
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
        nativeLoadData(baseUrl, data, mimeType, encoding, historyUrl);
        mLoadInitFromJava = false;
    }

    /**
     * Saves the contents of the frame as a web archive.
     *
     * @param basename The filename where the archive should be placed.
     * @param autoname If false, takes filename to be a file. If true, filename
     *                 is assumed to be a directory in which a filename will be
     *                 chosen according to the url of the current page.
     */
    /* package */ String saveWebArchive(String basename, boolean autoname) {
        return nativeSaveWebArchive(basename, autoname);
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
     * @param description Optional human-readable description. If no description
     *     is given, we'll use a standard localized error message.
     * @param failingUrl The URL that was being loaded when the error occurred.
     * TODO: Report all errors including resource errors but include some kind
     * of domain identifier. Change errorCode to an enum for a cleaner
     * interface.
     */
    private void reportError(int errorCode, String description, String failingUrl) {
        // As this is called for the main resource and loading will be stopped
        // after, reset the state variables.
        resetLoadingStates();
        if (description == null || description.isEmpty()) {
            description = ErrorStrings.getString(errorCode, mContext);
        }
        mCallbackProxy.onReceivedError(errorCode, description, failingUrl);
    }

    private void resetLoadingStates() {
        mCommitted = true;
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
        }
    }

    @SuppressWarnings("unused")
    private void saveFormData(HashMap<String, String> data) {
        if (mSettings.getSaveFormData()) {
            final WebHistoryItem h = mCallbackProxy.getBackForwardList()
                    .getCurrentItem();
            if (h != null) {
                String currentUrl = h.getUrl();
                if (currentUrl != null) {
                    mDatabase.setFormData(currentUrl, data);
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private boolean shouldSaveFormData() {
        if (mSettings.getSaveFormData()) {
            final WebHistoryItem h = mCallbackProxy.getBackForwardList()
                    .getCurrentItem();
            return h != null && h.getUrl() != null;
        }
        return false;
    }

    /**
     * native callback
     * Indicates the WebKit has committed to the new load
     */
    private void transitionToCommitted(int loadType, boolean isMainFrame) {
        // loadType is not used yet
        if (isMainFrame) {
            mCommitted = true;
            mWebViewCore.getWebView().mViewManager.postResetStateAll();
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
        mBlockMessages = true;
        removeCallbacksAndMessages(null);
    }

    /**
     * Handle messages posted to us.
     * @param msg The message to handle.
     */
    @Override
    public void handleMessage(Message msg) {
        if (mBlockMessages) {
            return;
        }
        switch (msg.what) {
            case FRAME_COMPLETED: {
                if (mSettings.getSavePassword() && hasPasswordField()) {
                    WebHistoryItem item = mCallbackProxy.getBackForwardList()
                            .getCurrentItem();
                    if (item != null) {
                        WebAddress uri = new WebAddress(item.getUrl());
                        String schemePlusHost = uri.getScheme() + uri.getHost();
                        String[] up =
                                mDatabase.getUsernamePassword(schemePlusHost);
                        if (up != null && up[0] != null) {
                            setUsernamePassword(up[0], up[1]);
                        }
                    }
                }
                if (!JniUtil.useChromiumHttpStack()) {
                    WebViewWorker.getHandler().sendEmptyMessage(
                            WebViewWorker.MSG_TRIM_CACHE);
                }
                break;
            }

            case POLICY_FUNCTION: {
                nativeCallPolicyFunction(msg.arg1, msg.arg2);
                break;
            }

            case ORIENTATION_CHANGED: {
                if (mOrientation != msg.arg1) {
                    mOrientation = msg.arg1;
                    nativeOrientationChanged(msg.arg1);
                }
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
     * Retrieves the visual text of the frames, puts it as the object for
     * the message and sends the message.
     * @param callback the message to use to send the visual text
     */
    public void documentAsText(Message callback) {
        StringBuilder text = new StringBuilder();
        if (callback.arg1 != 0) {
            // Dump top frame as text.
            text.append(documentAsText());
        }
        if (callback.arg2 != 0) {
            // Dump child frames as text.
            text.append(childFramesAsText());
        }
        callback.obj = text.toString();
        callback.sendToTarget();
    }

    /**
     * Return the text drawn on the screen as a string
     */
    private native String documentAsText();

    /**
     * Return the text drawn on the child frames as a string
     */
    private native String childFramesAsText();

    /*
     * This method is called by WebCore to inform the frame that
     * the Javascript window object has been cleared.
     * We should re-attach any attached js interfaces.
     */
    private void windowObjectCleared(int nativeFramePointer) {
        Iterator<String> iter = mJavaScriptObjects.keySet().iterator();
        while (iter.hasNext())  {
            String interfaceName = iter.next();
            Object object = mJavaScriptObjects.get(interfaceName);
            if (object != null) {
                nativeAddJavascriptInterface(nativeFramePointer,
                        mJavaScriptObjects.get(interfaceName), interfaceName);
            }
        }
        mRemovedJavaScriptObjects.clear();

        stringByEvaluatingJavaScriptFromString(SearchBoxImpl.JS_BRIDGE);
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
        assert obj != null;
        removeJavascriptInterface(interfaceName);

        mJavaScriptObjects.put(interfaceName, obj);
    }

    public void removeJavascriptInterface(String interfaceName) {
        // We keep a reference to the removed object because the native side holds only a weak
        // reference and we need to allow the object to continue to be used until the page has been
        // navigated.
        if (mJavaScriptObjects.containsKey(interfaceName)) {
            mRemovedJavaScriptObjects.add(mJavaScriptObjects.remove(interfaceName));
        }
    }

    /**
     * Called by JNI.  Given a URI, find the associated file and return its size
     * @param uri A String representing the URI of the desired file.
     * @return int The size of the given file.
     */
    private int getFileSize(String uri) {
        int size = 0;
        try {
            InputStream stream = mContext.getContentResolver()
                            .openInputStream(Uri.parse(uri));
            size = stream.available();
            stream.close();
        } catch (Exception e) {}
        return size;
    }

    /**
     * Called by JNI.  Given a URI, a buffer, and an offset into the buffer,
     * copy the resource into buffer.
     * @param uri A String representing the URI of the desired file.
     * @param buffer The byte array to copy the data into.
     * @param offset The offet into buffer to place the data.
     * @param expectedSize The size that the buffer has allocated for this file.
     * @return int The size of the given file, or zero if it fails.
     */
    private int getFile(String uri, byte[] buffer, int offset,
            int expectedSize) {
        int size = 0;
        try {
            InputStream stream = mContext.getContentResolver()
                            .openInputStream(Uri.parse(uri));
            size = stream.available();
            if (size <= expectedSize && buffer != null
                    && buffer.length - offset >= size) {
                stream.read(buffer, offset, size);
            } else {
                size = 0;
            }
            stream.close();
        } catch (java.io.FileNotFoundException e) {
            Log.e(LOGTAG, "FileNotFoundException:" + e);
            size = 0;
        } catch (java.io.IOException e2) {
            Log.e(LOGTAG, "IOException: " + e2);
            size = 0;
        }
        return size;
    }

    /**
     * Get the InputStream for an Android resource
     * There are three different kinds of android resources:
     * - file:///android_res
     * - file:///android_asset
     * - content://
     * @param url The url to load.
     * @return An InputStream to the android resource
     */
    private InputStream inputStreamForAndroidResource(String url) {
        // This list needs to be kept in sync with the list in
        // external/webkit/WebKit/android/WebCoreSupport/WebUrlLoaderClient.cpp
        final String ANDROID_ASSET = "file:///android_asset/";
        final String ANDROID_RESOURCE = "file:///android_res/";
        final String ANDROID_CONTENT = "content:";

        // file:///android_res
        if (url.startsWith(ANDROID_RESOURCE)) {
            url = url.replaceFirst(ANDROID_RESOURCE, "");
            if (url == null || url.length() == 0) {
                Log.e(LOGTAG, "url has length 0 " + url);
                return null;
            }
            int slash = url.indexOf('/');
            int dot = url.indexOf('.', slash);
            if (slash == -1 || dot == -1) {
                Log.e(LOGTAG, "Incorrect res path: " + url);
                return null;
            }
            String subClassName = url.substring(0, slash);
            String fieldName = url.substring(slash + 1, dot);
            String errorMsg = null;
            try {
                final Class<?> d = mContext.getApplicationContext()
                        .getClassLoader().loadClass(
                                mContext.getPackageName() + ".R$"
                                        + subClassName);
                final java.lang.reflect.Field field = d.getField(fieldName);
                final int id = field.getInt(null);
                TypedValue value = new TypedValue();
                mContext.getResources().getValue(id, value, true);
                if (value.type == TypedValue.TYPE_STRING) {
                    return mContext.getAssets().openNonAsset(
                            value.assetCookie, value.string.toString(),
                            AssetManager.ACCESS_STREAMING);
                } else {
                    // Old stack only supports TYPE_STRING for res files
                    Log.e(LOGTAG, "not of type string: " + url);
                    return null;
                }
            } catch (Exception e) {
                Log.e(LOGTAG, "Exception: " + url);
                return null;
            }

        // file:///android_asset
        } else if (url.startsWith(ANDROID_ASSET)) {
            url = url.replaceFirst(ANDROID_ASSET, "");
            try {
                AssetManager assets = mContext.getAssets();
                return assets.open(url, AssetManager.ACCESS_STREAMING);
            } catch (IOException e) {
                return null;
            }

        // content://
        } else if (mSettings.getAllowContentAccess() &&
                   url.startsWith(ANDROID_CONTENT)) {
            try {
                // Strip off mimetype, for compatibility with ContentLoader.java
                // If we don't do this, we can fail to load Gmail attachments,
                // because the URL being loaded doesn't exactly match the URL we
                // have permission to read.
                int mimeIndex = url.lastIndexOf('?');
                if (mimeIndex != -1) {
                    url = url.substring(0, mimeIndex);
                }
                Uri uri = Uri.parse(url);
                return mContext.getContentResolver().openInputStream(uri);
            } catch (Exception e) {
                Log.e(LOGTAG, "Exception: " + url);
                return null;
            }
        } else {
            return null;
        }
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
     * @param postDataIdentifier If the post data contained form this is the form identifier, otherwise it is 0.
     * @param cacheMode The cache mode to use when loading this resource. See WebSettings.setCacheMode
     * @param mainResource True if the this resource is the main request, not a supporting resource
     * @param userGesture
     * @param synchronous True if the load is synchronous.
     * @return A newly created LoadListener object.
     */
    private LoadListener startLoadingResource(int loaderHandle,
                                              String url,
                                              String method,
                                              HashMap headers,
                                              byte[] postData,
                                              long postDataIdentifier,
                                              int cacheMode,
                                              boolean mainResource,
                                              boolean userGesture,
                                              boolean synchronous,
                                              String username,
                                              String password) {
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
            String[] ret = getUsernamePassword();
            if (ret != null) {
                String domUsername = ret[0];
                String domPassword = ret[1];
                maybeSavePassword(postData, domUsername, domPassword);
            }
        }

        // is this resource the main-frame top-level page?
        boolean isMainFramePage = mIsMainFrame;

        if (DebugFlags.BROWSER_FRAME) {
            Log.v(LOGTAG, "startLoadingResource: url=" + url + ", method="
                    + method + ", postData=" + postData + ", isMainFramePage="
                    + isMainFramePage + ", mainResource=" + mainResource
                    + ", userGesture=" + userGesture);
        }

        // Create a LoadListener
        LoadListener loadListener = LoadListener.getLoadListener(mContext,
                this, url, loaderHandle, synchronous, isMainFramePage,
                mainResource, userGesture, postDataIdentifier, username, password);

        if (LoadListener.getNativeLoaderCount() > MAX_OUTSTANDING_REQUESTS) {
            // send an error message, so that loadListener can be deleted
            // after this is returned. This is important as LoadListener's 
            // nativeError will remove the request from its DocLoader's request
            // list. But the set up is not done until this method is returned.
            loadListener.error(
                    android.net.http.EventHandler.ERROR, mContext.getString(
                            com.android.internal.R.string.httpErrorTooManyRequests));
            return loadListener;
        }

        // Note that we are intentionally skipping
        // inputStreamForAndroidResource.  This is so that FrameLoader will use
        // the various StreamLoader classes to handle assets.
        FrameLoader loader = new FrameLoader(loadListener, mSettings, method,
                mCallbackProxy.shouldInterceptRequest(url));
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

        return !synchronous ? loadListener : null;
    }

    /**
     * If this looks like a POST request (form submission) containing a username
     * and password, give the user the option of saving them. Will either do
     * nothing, or block until the UI interaction is complete.
     *
     * Called by startLoadingResource when using the Apache HTTP stack.
     * Called directly by WebKit when using the Chrome HTTP stack.
     *
     * @param postData The data about to be sent as the body of a POST request.
     * @param username The username entered by the user (sniffed from the DOM).
     * @param password The password entered by the user (sniffed from the DOM).
     */
    private void maybeSavePassword(
            byte[] postData, String username, String password) {
        if (postData == null
                || username == null || username.isEmpty()
                || password == null || password.isEmpty()) {
            return; // No password to save.
        }

        if (!mSettings.getSavePassword()) {
            return; // User doesn't want to save passwords.
        }

        try {
            if (DebugFlags.BROWSER_FRAME) {
                Assert.assertNotNull(mCallbackProxy.getBackForwardList()
                        .getCurrentItem());
            }
            WebAddress uri = new WebAddress(mCallbackProxy
                    .getBackForwardList().getCurrentItem().getUrl());
            String schemePlusHost = uri.getScheme() + uri.getHost();
            // Check to see if the username & password appear in
            // the post data (there could be another form on the
            // page and that was posted instead.
            String postString = new String(postData);
            if (postString.contains(URLEncoder.encode(username)) &&
                    postString.contains(URLEncoder.encode(password))) {
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
                                schemePlusHost, username, password);
                    }
                } else {
                    // CallbackProxy will handle creating the resume
                    // message
                    mCallbackProxy.onSavePassword(schemePlusHost, username,
                            password, null);
                }
            }
        } catch (ParseException ex) {
            // if it is bad uri, don't save its password
        }
    }

    // Called by jni from the chrome network stack.
    private WebResourceResponse shouldInterceptRequest(String url) {
        InputStream androidResource = inputStreamForAndroidResource(url);
        if (androidResource != null) {
            return new WebResourceResponse(null, null, androidResource);
        }
        WebResourceResponse response = mCallbackProxy.shouldInterceptRequest(url);
        if (response == null && "browser:incognito".equals(url)) {
            try {
                Resources res = mContext.getResources();
                InputStream ins = res.openRawResource(
                        com.android.internal.R.raw.incognito_mode_start_page);
                response = new WebResourceResponse("text/html", "utf8", ins);
            } catch (NotFoundException ex) {
                // This shouldn't happen, but try and gracefully handle it jic
                Log.w(LOGTAG, "Failed opening raw.incognito_mode_start_page", ex);
            }
        }
        return response;
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

    // Called by JNI when an apple-touch-icon attribute was found.
    private void didReceiveTouchIconUrl(String url, boolean precomposed) {
        mCallbackProxy.onReceivedTouchIconUrl(url, precomposed);
    }

    /**
     * Request a new window from the client.
     * @return The BrowserFrame object stored in the new WebView.
     */
    private BrowserFrame createWindow(boolean dialog, boolean userGesture) {
        return mCallbackProxy.createWindow(dialog, userGesture);
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

    // These ids need to be in sync with enum rawResId in PlatformBridge.h
    private static final int NODOMAIN = 1;
    private static final int LOADERROR = 2;
    /* package */ static final int DRAWABLEDIR = 3;
    private static final int FILE_UPLOAD_LABEL = 4;
    private static final int RESET_LABEL = 5;
    private static final int SUBMIT_LABEL = 6;
    private static final int FILE_UPLOAD_NO_FILE_CHOSEN = 7;

    private String getRawResFilename(int id) {
        return getRawResFilename(id, mContext);
    }
    /* package */ static String getRawResFilename(int id, Context context) {
        int resid;
        switch (id) {
            case NODOMAIN:
                resid = com.android.internal.R.raw.nodomain;
                break;

            case LOADERROR:
                resid = com.android.internal.R.raw.loaderror;
                break;

            case DRAWABLEDIR:
                // use one known resource to find the drawable directory
                resid = com.android.internal.R.drawable.btn_check_off;
                break;

            case FILE_UPLOAD_LABEL:
                return context.getResources().getString(
                        com.android.internal.R.string.upload_file);

            case RESET_LABEL:
                return context.getResources().getString(
                        com.android.internal.R.string.reset);

            case SUBMIT_LABEL:
                return context.getResources().getString(
                        com.android.internal.R.string.submit);

            case FILE_UPLOAD_NO_FILE_CHOSEN:
                return context.getResources().getString(
                        com.android.internal.R.string.no_file_chosen);

            default:
                Log.e(LOGTAG, "getRawResFilename got incompatible resource ID");
                return "";
        }
        TypedValue value = new TypedValue();
        context.getResources().getValue(resid, value, true);
        if (id == DRAWABLEDIR) {
            String path = value.string.toString();
            int index = path.lastIndexOf('/');
            if (index < 0) {
                Log.e(LOGTAG, "Can't find drawable directory.");
                return "";
            }
            return path.substring(0, index + 1);
        }
        return value.string.toString();
    }

    private float density() {
        return mContext.getResources().getDisplayMetrics().density;
    }

    /**
     * Called by JNI when the native HTTP stack gets an authentication request.
     *
     * We delegate the request to CallbackProxy, and route its response to
     * {@link #nativeAuthenticationProceed(int, String, String)} or
     * {@link #nativeAuthenticationCancel(int)}.
     *
     * We don't care what thread the callback is invoked on. All threading is
     * handled on the C++ side, because the WebKit thread may be blocked on a
     * synchronous call and unable to pump our MessageQueue.
     */
    private void didReceiveAuthenticationChallenge(
            final int handle, String host, String realm, final boolean useCachedCredentials) {

        HttpAuthHandler handler = new HttpAuthHandler() {

            @Override
            public boolean useHttpAuthUsernamePassword() {
                return useCachedCredentials;
            }

            @Override
            public void proceed(String username, String password) {
                nativeAuthenticationProceed(handle, username, password);
            }

            @Override
            public void cancel() {
                nativeAuthenticationCancel(handle);
            }
        };
        mCallbackProxy.onReceivedHttpAuthRequest(handler, host, realm);
    }

    /**
     * Called by JNI when the native HTTPS stack gets an invalid cert chain.
     *
     * We delegate the request to CallbackProxy, and route its response to
     * {@link #nativeSslCertErrorProceed(int)} or
     * {@link #nativeSslCertErrorCancel(int, int)}.
     */
    private void reportSslCertError(
            final int handle, final int cert_error, byte cert_der[], String url) {
        final SslError ssl_error;
        try {
            X509Certificate cert = new X509CertImpl(cert_der);
            SslCertificate sslCert = new SslCertificate(cert);
            if (JniUtil.useChromiumHttpStack()) {
                ssl_error = SslError.SslErrorFromChromiumErrorCode(cert_error, sslCert, url);
            } else {
                ssl_error = new SslError(cert_error, cert, url);
            }
        } catch (IOException e) {
            // Can't get the certificate, not much to do.
            Log.e(LOGTAG, "Can't get the certificate from WebKit, canceling");
            nativeSslCertErrorCancel(handle, cert_error);
            return;
        }

        SslErrorHandler handler = new SslErrorHandler() {

            @Override
            public void proceed() {
                SslCertLookupTable.getInstance().Allow(ssl_error);
                nativeSslCertErrorProceed(handle);
            }

            @Override
            public void cancel() {
                SslCertLookupTable.getInstance().Deny(ssl_error);
                nativeSslCertErrorCancel(handle, cert_error);
            }
        };

        if (SslCertLookupTable.getInstance().IsAllowed(ssl_error)) {
            nativeSslCertErrorProceed(handle);
        } else {
            mCallbackProxy.onReceivedSslError(handler, ssl_error);
        }
    }

    /**
     * Called by JNI when the native HTTPS stack gets a client
     * certificate request.
     *
     * We delegate the request to CallbackProxy, and route its response to
     * {@link #nativeSslClientCert(int, X509Certificate)}.
     */
    private void requestClientCert(int handle, byte[] host_and_port_bytes) {
        String host_and_port = new String(host_and_port_bytes, Charsets.UTF_8);
        SslClientCertLookupTable table = SslClientCertLookupTable.getInstance();
        if (table.IsAllowed(host_and_port)) {
            // previously allowed
            nativeSslClientCert(handle,
                                table.PrivateKey(host_and_port),
                                table.CertificateChain(host_and_port));
        } else if (table.IsDenied(host_and_port)) {
            // previously denied
            nativeSslClientCert(handle, null, null);
        } else {
            // previously ignored or new
            mCallbackProxy.onReceivedClientCertRequest(
                    new ClientCertRequestHandler(this, handle, host_and_port, table),
                    host_and_port);
        }
    }

    /**
     * Called by JNI when the native HTTP stack needs to download a file.
     *
     * We delegate the request to CallbackProxy, which owns the current app's
     * DownloadListener.
     */
    private void downloadStart(String url, String userAgent,
            String contentDisposition, String mimeType, long contentLength) {
        // This will only work if the url ends with the filename
        if (mimeType.isEmpty()) {
            try {
                String extension = url.substring(url.lastIndexOf('.') + 1);
                mimeType = libcore.net.MimeUtils.guessMimeTypeFromExtension(extension);
                // MimeUtils might return null, not sure if downloadmanager is happy with that
                if (mimeType == null)
                    mimeType = "";
            } catch(IndexOutOfBoundsException exception) {
                // mimeType string end with a '.', not much to do
            }
        }
        mimeType = MimeTypeMap.getSingleton().remapGenericMimeType(
                mimeType, url, contentDisposition);

        if (CertTool.getCertType(mimeType) != null) {
            mKeyStoreHandler = new KeyStoreHandler(mimeType);
        } else {
            mCallbackProxy.onDownloadStart(url, userAgent,
                contentDisposition, mimeType, contentLength);
        }
    }

    /**
     * Called by JNI for Chrome HTTP stack when the Java side needs to access the data.
     */
    private void didReceiveData(byte data[], int size) {
        if (mKeyStoreHandler != null) mKeyStoreHandler.didReceiveData(data, size);
    }

    private void didFinishLoading() {
      if (mKeyStoreHandler != null) {
          mKeyStoreHandler.installCert(mContext);
          mKeyStoreHandler = null;
      }
    }

    /**
     * Called by JNI when we load a page over SSL.
     */
    private void setCertificate(byte cert_der[]) {
        try {
            X509Certificate cert = new X509CertImpl(cert_der);
            mCallbackProxy.onReceivedCertificate(new SslCertificate(cert));
        } catch (IOException e) {
            // Can't get the certificate, not much to do.
            Log.e(LOGTAG, "Can't get the certificate from WebKit, canceling");
            return;
        }
    }

    /*package*/ SearchBox getSearchBox() {
        return mSearchBox;
    }

    /**
     * Called by JNI when processing the X-Auto-Login header.
     */
    private void autoLogin(String realm, String account, String args) {
        mCallbackProxy.onReceivedLoginRequest(realm, account, args);
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
    private native void nativeLoadUrl(String url, Map<String, String> headers);

    private native void nativePostUrl(String url, byte[] postData);

    private native void nativeLoadData(String baseUrl, String data,
            String mimeType, String encoding, String historyUrl);

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

    private native String nativeSaveWebArchive(String basename, boolean autoname);

    private native void nativeOrientationChanged(int orientation);

    private native void nativeAuthenticationProceed(int handle, String username, String password);
    private native void nativeAuthenticationCancel(int handle);

    private native void nativeSslCertErrorProceed(int handle);
    private native void nativeSslCertErrorCancel(int handle, int cert_error);

    native void nativeSslClientCert(int handle,
                                    byte[] pkcs8EncodedPrivateKey,
                                    byte[][] asn1DerEncodedCertificateChain);
}
