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

package android.webkit;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.DrawFilter;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Picture;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.provider.Browser;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import junit.framework.Assert;

final class WebViewCore {

    private static final String LOGTAG = "webcore";

    static {
        // Load libwebcore during static initialization. This happens in the
        // zygote process so it will be shared read-only across all app
        // processes.
        System.loadLibrary("webcore");
    }

    /*
     * WebViewCore always executes in the same thread as the native webkit.
     */

    // The WebView that corresponds to this WebViewCore.
    private WebView mWebView;
    // Proxy for handling callbacks from native code
    private final CallbackProxy mCallbackProxy;
    // Settings object for maintaining all settings
    private final WebSettings mSettings;
    // Context for initializing the BrowserFrame with the proper assets.
    private final Context mContext;
    // The pointer to a native view object.
    private int mNativeClass;
    // The BrowserFrame is an interface to the native Frame component.
    private BrowserFrame mBrowserFrame;
    // Custom JS interfaces to add during the initialization.
    private Map<String, Object> mJavascriptInterfaces;
    /*
     * range is from 200 to 10,000. 0 is a special value means device-width. -1
     * means undefined.
     */
    private int mViewportWidth = -1;

    /*
     * range is from 200 to 10,000. 0 is a special value means device-height. -1
     * means undefined.
     */
    private int mViewportHeight = -1;

    /*
     * scale in percent, range is from 1 to 1000. 0 means undefined.
     */
    private int mViewportInitialScale = 0;

    /*
     * scale in percent, range is from 1 to 1000. 0 means undefined.
     */
    private int mViewportMinimumScale = 0;

    /*
     * scale in percent, range is from 1 to 1000. 0 means undefined.
     */
    private int mViewportMaximumScale = 0;

    private boolean mViewportUserScalable = true;

    /*
     * range is from 70 to 400.
     * 0 is a special value means device-dpi. The default scale factor will be
     * always 100.
     * -1 means undefined. The default scale factor will be
     * WebView.DEFAULT_SCALE_PERCENT.
     */
    private int mViewportDensityDpi = -1;

    private int mRestoredScale = 0;
    private int mRestoredScreenWidthScale = 0;
    private int mRestoredX = 0;
    private int mRestoredY = 0;

    private int mWebkitScrollX = 0;
    private int mWebkitScrollY = 0;

    // If the site doesn't use viewport meta tag to specify the viewport, use
    // DEFAULT_VIEWPORT_WIDTH as default viewport width
    static final int DEFAULT_VIEWPORT_WIDTH = 800;

    // The thread name used to identify the WebCore thread and for use in
    // debugging other classes that require operation within the WebCore thread.
    /* package */ static final String THREAD_NAME = "WebViewCoreThread";

    public WebViewCore(Context context, WebView w, CallbackProxy proxy,
            Map<String, Object> javascriptInterfaces) {
        // No need to assign this in the WebCore thread.
        mCallbackProxy = proxy;
        mWebView = w;
        mJavascriptInterfaces = javascriptInterfaces;
        // This context object is used to initialize the WebViewCore during
        // subwindow creation.
        mContext = context;

        // We need to wait for the initial thread creation before sending
        // a message to the WebCore thread.
        // XXX: This is the only time the UI thread will wait for the WebCore
        // thread!
        synchronized (WebViewCore.class) {
            if (sWebCoreHandler == null) {
                // Create a global thread and start it.
                Thread t = new Thread(new WebCoreThread());
                t.setName(THREAD_NAME);
                t.start();
                try {
                    WebViewCore.class.wait();
                } catch (InterruptedException e) {
                    Log.e(LOGTAG, "Caught exception while waiting for thread " +
                           "creation.");
                    Log.e(LOGTAG, Log.getStackTraceString(e));
                }
            }
        }
        // Create an EventHub to handle messages before and after the thread is
        // ready.
        mEventHub = new EventHub();
        // Create a WebSettings object for maintaining all settings
        mSettings = new WebSettings(mContext, mWebView);
        // The WebIconDatabase needs to be initialized within the UI thread so
        // just request the instance here.
        WebIconDatabase.getInstance();
        // Create the WebStorage singleton and the UI handler
        WebStorage.getInstance().createUIHandler();
        // Create the UI handler for GeolocationPermissions
        GeolocationPermissions.getInstance().createUIHandler();
        // Send a message to initialize the WebViewCore.
        Message init = sWebCoreHandler.obtainMessage(
                WebCoreThread.INITIALIZE, this);
        sWebCoreHandler.sendMessage(init);
    }

    /* Initialize private data within the WebCore thread.
     */
    private void initialize() {
        /* Initialize our private BrowserFrame class to handle all
         * frame-related functions. We need to create a new view which
         * in turn creates a C level FrameView and attaches it to the frame.
         */
        mBrowserFrame = new BrowserFrame(mContext, this, mCallbackProxy,
                mSettings, mJavascriptInterfaces);
        mJavascriptInterfaces = null;
        // Sync the native settings and also create the WebCore thread handler.
        mSettings.syncSettingsAndCreateHandler(mBrowserFrame);
        // Create the handler and transfer messages for the IconDatabase
        WebIconDatabase.getInstance().createHandler();
        // Create the handler for WebStorage
        WebStorage.getInstance().createHandler();
        // Create the handler for GeolocationPermissions.
        GeolocationPermissions.getInstance().createHandler();
        // The transferMessages call will transfer all pending messages to the
        // WebCore thread handler.
        mEventHub.transferMessages();

        // Send a message back to WebView to tell it that we have set up the
        // WebCore thread.
        if (mWebView != null) {
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.WEBCORE_INITIALIZED_MSG_ID,
                    mNativeClass, 0).sendToTarget();
        }

    }

    /* Handle the initialization of WebViewCore during subwindow creation. This
     * method is called from the WebCore thread but it is called before the
     * INITIALIZE message can be handled.
     */
    /* package */ void initializeSubwindow() {
        // Go ahead and initialize the core components.
        initialize();
        // Remove the INITIALIZE method so we don't try to initialize twice.
        sWebCoreHandler.removeMessages(WebCoreThread.INITIALIZE, this);
    }

    /* Get the BrowserFrame component. This is used for subwindow creation and
     * is called only from BrowserFrame in the WebCore thread. */
    /* package */ BrowserFrame getBrowserFrame() {
        return mBrowserFrame;
    }

    //-------------------------------------------------------------------------
    // Common methods
    //-------------------------------------------------------------------------

    /**
     * Causes all timers to pause. This applies to all WebViews in the current
     * app process.
     */
    public static void pauseTimers() {
        if (BrowserFrame.sJavaBridge == null) {
            throw new IllegalStateException(
                    "No WebView has been created in this process!");
        }
        BrowserFrame.sJavaBridge.pause();
    }

    /**
     * Resume all timers. This applies to all WebViews in the current process.
     */
    public static void resumeTimers() {
        if (BrowserFrame.sJavaBridge == null) {
            throw new IllegalStateException(
                    "No WebView has been created in this process!");
        }
        BrowserFrame.sJavaBridge.resume();
    }

    public WebSettings getSettings() {
        return mSettings;
    }

    /**
     * Add an error message to the client's console.
     * @param message The message to add
     * @param lineNumber the line on which the error occurred
     * @param sourceID the filename of the source that caused the error.
     */
    protected void addMessageToConsole(String message, int lineNumber, String sourceID) {
        mCallbackProxy.addMessageToConsole(message, lineNumber, sourceID);
    }

    /**
     * Invoke a javascript alert.
     * @param message The message displayed in the alert.
     */
    protected void jsAlert(String url, String message) {
        mCallbackProxy.onJsAlert(url, message);
    }

    /**
     * Notify the browser that the origin has exceeded it's database quota.
     * @param url The URL that caused the overflow.
     * @param databaseIdentifier The identifier of the database.
     * @param currentQuota The current quota for the origin.
     * @param estimatedSize The estimated size of the database.
     */
    protected void exceededDatabaseQuota(String url,
                                         String databaseIdentifier,
                                         long currentQuota,
                                         long estimatedSize) {
        // Inform the callback proxy of the quota overflow. Send an object
        // that encapsulates a call to the nativeSetDatabaseQuota method to
        // awaken the sleeping webcore thread when a decision from the
        // client to allow or deny quota is available.
        mCallbackProxy.onExceededDatabaseQuota(url, databaseIdentifier,
                currentQuota, estimatedSize, getUsedQuota(),
                new WebStorage.QuotaUpdater() {
                        public void updateQuota(long quota) {
                            nativeSetNewStorageLimit(quota);
                        }
                });
    }

    /**
     * Notify the browser that the appcache has exceeded its max size.
     * @param spaceNeeded is the amount of disk space that would be needed
     * in order for the last appcache operation to succeed.
     */
    protected void reachedMaxAppCacheSize(long spaceNeeded) {
        mCallbackProxy.onReachedMaxAppCacheSize(spaceNeeded, getUsedQuota(),
                new WebStorage.QuotaUpdater() {
                    public void updateQuota(long quota) {
                        nativeSetNewStorageLimit(quota);
                    }
                });
    }

    protected void populateVisitedLinks() {
        ValueCallback callback = new ValueCallback<String[]>() {
            public void onReceiveValue(String[] value) {
                sendMessage(EventHub.POPULATE_VISITED_LINKS, (Object)value);
            }
        };
        mCallbackProxy.getVisitedHistory(callback);
    }

    /**
     * Shows a prompt to ask the user to set the Geolocation permission state
     * for the given origin.
     * @param origin The origin for which Geolocation permissions are
     *     requested.
     */
    protected void geolocationPermissionsShowPrompt(String origin) {
        mCallbackProxy.onGeolocationPermissionsShowPrompt(origin,
                new GeolocationPermissions.Callback() {
          public void invoke(String origin, boolean allow, boolean remember) {
            GeolocationPermissionsData data = new GeolocationPermissionsData();
            data.mOrigin = origin;
            data.mAllow = allow;
            data.mRemember = remember;
            // Marshall to WebCore thread.
            sendMessage(EventHub.GEOLOCATION_PERMISSIONS_PROVIDE, data);
          }
        });
    }

    /**
     * Hides the Geolocation permissions prompt.
     */
    protected void geolocationPermissionsHidePrompt() {
        mCallbackProxy.onGeolocationPermissionsHidePrompt();
    }

    /**
     * Invoke a javascript confirm dialog.
     * @param message The message displayed in the dialog.
     * @return True if the user confirmed or false if the user cancelled.
     */
    protected boolean jsConfirm(String url, String message) {
        return mCallbackProxy.onJsConfirm(url, message);
    }

    /**
     * Invoke a javascript prompt dialog.
     * @param message The message to be displayed in the dialog.
     * @param defaultValue The default value in the prompt input.
     * @return The input from the user or null to indicate the user cancelled
     *         the dialog.
     */
    protected String jsPrompt(String url, String message, String defaultValue) {
        return mCallbackProxy.onJsPrompt(url, message, defaultValue);
    }

    /**
     * Invoke a javascript before unload dialog.
     * @param url The url that is requesting the dialog.
     * @param message The message displayed in the dialog.
     * @return True if the user confirmed or false if the user cancelled. False
     *         will cancel the navigation.
     */
    protected boolean jsUnload(String url, String message) {
        return mCallbackProxy.onJsBeforeUnload(url, message);
    }

    /**
     *
     * Callback to notify that a JavaScript execution timeout has occured.
     * @return True if the JavaScript execution should be interrupted. False
     *         will continue the execution.
     */
    protected boolean jsInterrupt() {
        return mCallbackProxy.onJsTimeout();
    }

    //-------------------------------------------------------------------------
    // JNI methods
    //-------------------------------------------------------------------------

    static native String nativeFindAddress(String addr, boolean caseInsensitive);

    /**
     * Empty the picture set.
     */
    private native void nativeClearContent();

    /**
     * Create a flat picture from the set of pictures.
     */
    private native void nativeCopyContentToPicture(Picture picture);

    /**
     * Draw the picture set with a background color. Returns true
     * if some individual picture took too long to draw and can be
     * split into parts. Called from the UI thread.
     */
    private native boolean nativeDrawContent(Canvas canvas, int color);

    /**
     * check to see if picture is blank and in progress
     */
    private native boolean nativePictureReady();

    /**
     * Redraw a portion of the picture set. The Point wh returns the
     * width and height of the overall picture.
     */
    private native boolean nativeRecordContent(Region invalRegion, Point wh);

    /**
     * Splits slow parts of the picture set. Called from the webkit
     * thread after nativeDrawContent returns true.
     */
    private native void nativeSplitContent();

    private native boolean nativeKey(int keyCode, int unichar,
            int repeatCount, boolean isShift, boolean isAlt, boolean isSym,
            boolean isDown);

    private native void nativeClick(int framePtr, int nodePtr);

    private native void nativeSendListBoxChoices(boolean[] choices, int size);

    private native void nativeSendListBoxChoice(int choice);

    /*  Tell webkit what its width and height are, for the purposes
        of layout/line-breaking. These coordinates are in document space,
        which is the same as View coords unless we have zoomed the document
        (see nativeSetZoom).
        screenWidth is used by layout to wrap column around. If viewport uses
        fixed size, screenWidth can be different from width with zooming.
        should this be called nativeSetViewPortSize?
    */
    private native void nativeSetSize(int width, int height, int screenWidth,
            float scale, int realScreenWidth, int screenHeight, int anchorX,
            int anchorY, boolean ignoreHeight);

    private native int nativeGetContentMinPrefWidth();

    // Start: functions that deal with text editing
    private native void nativeReplaceTextfieldText(
            int oldStart, int oldEnd, String replace, int newStart, int newEnd,
            int textGeneration);

    private native void passToJs(int gen,
            String currentText, int keyCode, int keyValue, boolean down,
            boolean cap, boolean fn, boolean sym);

    private native void nativeSetFocusControllerActive(boolean active);

    private native void nativeSaveDocumentState(int frame);

    private native void nativeMoveMouse(int framePtr, int x, int y);

    private native void nativeMoveMouseIfLatest(int moveGeneration,
            int framePtr, int x, int y);

    private native String nativeRetrieveHref(int framePtr, int nodePtr);

    private native void nativeTouchUp(int touchGeneration,
            int framePtr, int nodePtr, int x, int y);

    private native boolean nativeHandleTouchEvent(int action, int x, int y);

    private native void nativeUpdateFrameCache();

    private native void nativeSetBackgroundColor(int color);

    private native void nativeDumpDomTree(boolean useFile);

    private native void nativeDumpRenderTree(boolean useFile);

    private native void nativeDumpNavTree();

    private native void nativeSetJsFlags(String flags);

    /**
     *  Delete text from start to end in the focused textfield. If there is no
     *  focus, or if start == end, silently fail.  If start and end are out of
     *  order, swap them.
     *  @param  start   Beginning of selection to delete.
     *  @param  end     End of selection to delete.
     *  @param  textGeneration Text generation number when delete was pressed.
     */
    private native void nativeDeleteSelection(int start, int end,
            int textGeneration);

    /**
     *  Set the selection to (start, end) in the focused textfield. If start and
     *  end are out of order, swap them.
     *  @param  start   Beginning of selection.
     *  @param  end     End of selection.
     */
    private native void nativeSetSelection(int start, int end);

    private native String nativeGetSelection(Region sel);

    // Register a scheme to be treated as local scheme so that it can access
    // local asset files for resources
    private native void nativeRegisterURLSchemeAsLocal(String scheme);

    /*
     * Inform webcore that the user has decided whether to allow or deny new
     * quota for the current origin or more space for the app cache, and that
     * the main thread should wake up now.
     * @param limit Is the new quota for an origin or new app cache max size.
     */
    private native void nativeSetNewStorageLimit(long limit);

    private native void nativeUpdatePluginState(int framePtr, int nodePtr, int state);

    /**
     * Provide WebCore with a Geolocation permission state for the specified
     * origin.
     * @param origin The origin for which Geolocation permissions are provided.
     * @param allow Whether Geolocation permissions are allowed.
     * @param remember Whether this decision should be remembered beyond the
     *     life of the current page.
     */
    private native void nativeGeolocationPermissionsProvide(String origin, boolean allow, boolean remember);

    /**
     * Provide WebCore with the previously visted links from the history database
     */
    private native void  nativeProvideVisitedHistory(String[] history);

    // EventHub for processing messages
    private final EventHub mEventHub;
    // WebCore thread handler
    private static Handler sWebCoreHandler;
    // Class for providing Handler creation inside the WebCore thread.
    private static class WebCoreThread implements Runnable {
        // Message id for initializing a new WebViewCore.
        private static final int INITIALIZE = 0;
        private static final int REDUCE_PRIORITY = 1;
        private static final int RESUME_PRIORITY = 2;
        private static final int CACHE_TICKER = 3;
        private static final int BLOCK_CACHE_TICKER = 4;
        private static final int RESUME_CACHE_TICKER = 5;

        private static final int CACHE_TICKER_INTERVAL = 60 * 1000; // 1 minute

        private static boolean mCacheTickersBlocked = true;

        public void run() {
            Looper.prepare();
            Assert.assertNull(sWebCoreHandler);
            synchronized (WebViewCore.class) {
                sWebCoreHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                            case INITIALIZE:
                                WebViewCore core = (WebViewCore) msg.obj;
                                core.initialize();
                                break;

                            case REDUCE_PRIORITY:
                                // 3 is an adjustable number.
                                Process.setThreadPriority(
                                        Process.THREAD_PRIORITY_DEFAULT + 3 *
                                        Process.THREAD_PRIORITY_LESS_FAVORABLE);
                                break;

                            case RESUME_PRIORITY:
                                Process.setThreadPriority(
                                        Process.THREAD_PRIORITY_DEFAULT);
                                break;

                            case CACHE_TICKER:
                                if (!mCacheTickersBlocked) {
                                    CacheManager.endCacheTransaction();
                                    CacheManager.startCacheTransaction();
                                    sendMessageDelayed(
                                            obtainMessage(CACHE_TICKER),
                                            CACHE_TICKER_INTERVAL);
                                }
                                break;

                            case BLOCK_CACHE_TICKER:
                                if (CacheManager.endCacheTransaction()) {
                                    mCacheTickersBlocked = true;
                                }
                                break;

                            case RESUME_CACHE_TICKER:
                                if (CacheManager.startCacheTransaction()) {
                                    mCacheTickersBlocked = false;
                                }
                                break;
                        }
                    }
                };
                WebViewCore.class.notify();
            }
            Looper.loop();
        }
    }

    static class BaseUrlData {
        String mBaseUrl;
        String mData;
        String mMimeType;
        String mEncoding;
        String mFailUrl;
    }

    static class CursorData {
        CursorData() {}
        CursorData(int frame, int node, int x, int y) {
            mFrame = frame;
            mX = x;
            mY = y;
        }
        int mMoveGeneration;
        int mFrame;
        int mX;
        int mY;
    }

    static class JSInterfaceData {
        Object mObject;
        String mInterfaceName;
    }

    static class JSKeyData {
        String mCurrentText;
        KeyEvent mEvent;
    }

    static class PostUrlData {
        String mUrl;
        byte[] mPostData;
    }

    static class ReplaceTextData {
        String mReplace;
        int mNewStart;
        int mNewEnd;
        int mTextGeneration;
    }

    static class TextSelectionData {
        public TextSelectionData(int start, int end) {
            mStart = start;
            mEnd = end;
        }
        int mStart;
        int mEnd;
    }

    static class TouchUpData {
        int mMoveGeneration;
        int mFrame;
        int mNode;
        int mX;
        int mY;
    }

    static class TouchEventData {
        int mAction;    // MotionEvent.getAction()
        int mX;
        int mY;
    }

    static class PluginStateData {
        int mFrame;
        int mNode;
        int mState;
    }

    static class GeolocationPermissionsData {
        String mOrigin;
        boolean mAllow;
        boolean mRemember;
    }

        static final String[] HandlerDebugString = {
            "UPDATE_FRAME_CACHE_IF_LOADING", // = 98
            "SCROLL_TEXT_INPUT", // = 99
            "LOAD_URL", // = 100;
            "STOP_LOADING", // = 101;
            "RELOAD", // = 102;
            "KEY_DOWN", // = 103;
            "KEY_UP", // = 104;
            "VIEW_SIZE_CHANGED", // = 105;
            "GO_BACK_FORWARD", // = 106;
            "SET_SCROLL_OFFSET", // = 107;
            "RESTORE_STATE", // = 108;
            "PAUSE_TIMERS", // = 109;
            "RESUME_TIMERS", // = 110;
            "CLEAR_CACHE", // = 111;
            "CLEAR_HISTORY", // = 112;
            "SET_SELECTION", // = 113;
            "REPLACE_TEXT", // = 114;
            "PASS_TO_JS", // = 115;
            "SET_GLOBAL_BOUNDS", // = 116;
            "UPDATE_CACHE_AND_TEXT_ENTRY", // = 117;
            "CLICK", // = 118;
            "SET_NETWORK_STATE", // = 119;
            "DOC_HAS_IMAGES", // = 120;
            "121", // = 121;
            "DELETE_SELECTION", // = 122;
            "LISTBOX_CHOICES", // = 123;
            "SINGLE_LISTBOX_CHOICE", // = 124;
            "MESSAGE_RELAY", // = 125;
            "SET_BACKGROUND_COLOR", // = 126;
            "PLUGIN_STATE", // = 127;
            "SAVE_DOCUMENT_STATE", // = 128;
            "GET_SELECTION", // = 129;
            "WEBKIT_DRAW", // = 130;
            "SYNC_SCROLL", // = 131;
            "POST_URL", // = 132;
            "SPLIT_PICTURE_SET", // = 133;
            "CLEAR_CONTENT", // = 134;
            "SET_MOVE_MOUSE", // = 135;
            "SET_MOVE_MOUSE_IF_LATEST", // = 136;
            "REQUEST_CURSOR_HREF", // = 137;
            "ADD_JS_INTERFACE", // = 138;
            "LOAD_DATA", // = 139;
            "TOUCH_UP", // = 140;
            "TOUCH_EVENT", // = 141;
            "SET_ACTIVE", // = 142;
            "ON_PAUSE",     // = 143
            "ON_RESUME",    // = 144
            "FREE_MEMORY",  // = 145
        };

    class EventHub {
        // Message Ids
        static final int UPDATE_FRAME_CACHE_IF_LOADING = 98;
        static final int SCROLL_TEXT_INPUT = 99;
        static final int LOAD_URL = 100;
        static final int STOP_LOADING = 101;
        static final int RELOAD = 102;
        static final int KEY_DOWN = 103;
        static final int KEY_UP = 104;
        static final int VIEW_SIZE_CHANGED = 105;
        static final int GO_BACK_FORWARD = 106;
        static final int SET_SCROLL_OFFSET = 107;
        static final int RESTORE_STATE = 108;
        static final int PAUSE_TIMERS = 109;
        static final int RESUME_TIMERS = 110;
        static final int CLEAR_CACHE = 111;
        static final int CLEAR_HISTORY = 112;
        static final int SET_SELECTION = 113;
        static final int REPLACE_TEXT = 114;
        static final int PASS_TO_JS = 115;
        static final int SET_GLOBAL_BOUNDS = 116;
        static final int UPDATE_CACHE_AND_TEXT_ENTRY = 117;
        static final int CLICK = 118;
        static final int SET_NETWORK_STATE = 119;
        static final int DOC_HAS_IMAGES = 120;
        static final int DELETE_SELECTION = 122;
        static final int LISTBOX_CHOICES = 123;
        static final int SINGLE_LISTBOX_CHOICE = 124;
        static final int MESSAGE_RELAY = 125;
        static final int SET_BACKGROUND_COLOR = 126;
        static final int PLUGIN_STATE = 127; // plugin notifications
        static final int SAVE_DOCUMENT_STATE = 128;
        static final int GET_SELECTION = 129;
        static final int WEBKIT_DRAW = 130;
        static final int SYNC_SCROLL = 131;
        static final int POST_URL = 132;
        static final int SPLIT_PICTURE_SET = 133;
        static final int CLEAR_CONTENT = 134;

        // UI nav messages
        static final int SET_MOVE_MOUSE = 135;
        static final int SET_MOVE_MOUSE_IF_LATEST = 136;
        static final int REQUEST_CURSOR_HREF = 137;
        static final int ADD_JS_INTERFACE = 138;
        static final int LOAD_DATA = 139;

        // motion
        static final int TOUCH_UP = 140;
        // message used to pass UI touch events to WebCore
        static final int TOUCH_EVENT = 141;

        // Used to tell the focus controller not to draw the blinking cursor,
        // based on whether the WebView has focus and whether the WebView's
        // cursor matches the webpage's focus.
        static final int SET_ACTIVE = 142;

        // lifecycle activities for just this DOM (unlike pauseTimers, which
        // is global)
        static final int ON_PAUSE = 143;
        static final int ON_RESUME = 144;
        static final int FREE_MEMORY = 145;

        // Network-based messaging
        static final int CLEAR_SSL_PREF_TABLE = 150;

        // Test harness messages
        static final int REQUEST_EXT_REPRESENTATION = 160;
        static final int REQUEST_DOC_AS_TEXT = 161;

        // debugging
        static final int DUMP_DOMTREE = 170;
        static final int DUMP_RENDERTREE = 171;
        static final int DUMP_NAVTREE = 172;

        static final int SET_JS_FLAGS = 173;
        // Geolocation
        static final int GEOLOCATION_PERMISSIONS_PROVIDE = 180;

        static final int POPULATE_VISITED_LINKS = 181;

        // private message ids
        private static final int DESTROY =     200;

        // Private handler for WebCore messages.
        private Handler mHandler;
        // Message queue for containing messages before the WebCore thread is
        // ready.
        private ArrayList<Message> mMessages = new ArrayList<Message>();
        // Flag for blocking messages. This is used during DESTROY to avoid
        // posting more messages to the EventHub or to WebView's event handler.
        private boolean mBlockMessages;

        private int mTid;
        private int mSavedPriority;

        /**
         * Prevent other classes from creating an EventHub.
         */
        private EventHub() {}

        /**
         * Transfer all messages to the newly created webcore thread handler.
         */
        private void transferMessages() {
            mTid = Process.myTid();
            mSavedPriority = Process.getThreadPriority(mTid);

            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    if (DebugFlags.WEB_VIEW_CORE) {
                        Log.v(LOGTAG, (msg.what < UPDATE_FRAME_CACHE_IF_LOADING
                                || msg.what
                                > FREE_MEMORY ? Integer.toString(msg.what)
                                : HandlerDebugString[msg.what
                                        - UPDATE_FRAME_CACHE_IF_LOADING])
                                + " arg1=" + msg.arg1 + " arg2=" + msg.arg2
                                + " obj=" + msg.obj);
                    }
                    switch (msg.what) {
                        case WEBKIT_DRAW:
                            webkitDraw();
                            break;

                        case DESTROY:
                            // Time to take down the world. Cancel all pending
                            // loads and destroy the native view and frame.
                            synchronized (WebViewCore.this) {
                                mBrowserFrame.destroy();
                                mBrowserFrame = null;
                                mSettings.onDestroyed();
                                mNativeClass = 0;
                                mWebView = null;
                            }
                            break;

                        case UPDATE_FRAME_CACHE_IF_LOADING:
                            nativeUpdateFrameCacheIfLoading();
                            break;

                        case SCROLL_TEXT_INPUT:
                            nativeScrollFocusedTextInput(
                                    ((Float) msg.obj).floatValue(), msg.arg1);
                            break;

                        case LOAD_URL:
                            loadUrl((String) msg.obj);
                            break;

                        case POST_URL: {
                            PostUrlData param = (PostUrlData) msg.obj;
                            mBrowserFrame.postUrl(param.mUrl, param.mPostData);
                            break;
                        }
                        case LOAD_DATA:
                            BaseUrlData loadParams = (BaseUrlData) msg.obj;
                            String baseUrl = loadParams.mBaseUrl;
                            if (baseUrl != null) {
                                int i = baseUrl.indexOf(':');
                                if (i > 0) {
                                    /*
                                     * In 1.0, {@link
                                     * WebView#loadDataWithBaseURL} can access
                                     * local asset files as long as the data is
                                     * valid. In the new WebKit, the restriction
                                     * is tightened. To be compatible with 1.0,
                                     * we automatically add the scheme of the
                                     * baseUrl for local access as long as it is
                                     * not http(s)/ftp(s)/about/javascript
                                     */
                                    String scheme = baseUrl.substring(0, i);
                                    if (!scheme.startsWith("http") &&
                                            !scheme.startsWith("ftp") &&
                                            !scheme.startsWith("about") &&
                                            !scheme.startsWith("javascript")) {
                                        nativeRegisterURLSchemeAsLocal(scheme);
                                    }
                                }
                            }
                            mBrowserFrame.loadData(baseUrl,
                                    loadParams.mData,
                                    loadParams.mMimeType,
                                    loadParams.mEncoding,
                                    loadParams.mFailUrl);
                            break;

                        case STOP_LOADING:
                            // If the WebCore has committed the load, but not
                            // finished the first layout yet, we need to set
                            // first layout done to trigger the interpreted side sync
                            // up with native side
                            if (mBrowserFrame.committed()
                                    && !mBrowserFrame.firstLayoutDone()) {
                                mBrowserFrame.didFirstLayout();
                            }
                            // Do this after syncing up the layout state.
                            stopLoading();
                            break;

                        case RELOAD:
                            mBrowserFrame.reload(false);
                            break;

                        case KEY_DOWN:
                            key((KeyEvent) msg.obj, true);
                            break;

                        case KEY_UP:
                            key((KeyEvent) msg.obj, false);
                            break;

                        case CLICK:
                            nativeClick(msg.arg1, msg.arg2);
                            break;

                        case VIEW_SIZE_CHANGED: {
                            WebView.ViewSizeData data =
                                    (WebView.ViewSizeData) msg.obj;
                            viewSizeChanged(data.mWidth, data.mHeight,
                                    data.mTextWrapWidth, data.mScale,
                                    data.mAnchorX, data.mAnchorY,
                                    data.mIgnoreHeight);
                            break;
                        }
                        case SET_SCROLL_OFFSET:
                            // note: these are in document coordinates
                            // (inv-zoom)
                            Point pt = (Point) msg.obj;
                            nativeSetScrollOffset(msg.arg1, pt.x, pt.y);
                            break;

                        case SET_GLOBAL_BOUNDS:
                            Rect r = (Rect) msg.obj;
                            nativeSetGlobalBounds(r.left, r.top, r.width(),
                                r.height());
                            break;

                        case GO_BACK_FORWARD:
                            // If it is a standard load and the load is not
                            // committed yet, we interpret BACK as RELOAD
                            if (!mBrowserFrame.committed() && msg.arg1 == -1 &&
                                    (mBrowserFrame.loadType() ==
                                    BrowserFrame.FRAME_LOADTYPE_STANDARD)) {
                                mBrowserFrame.reload(true);
                            } else {
                                mBrowserFrame.goBackOrForward(msg.arg1);
                            }
                            break;

                        case RESTORE_STATE:
                            stopLoading();
                            restoreState(msg.arg1);
                            break;

                        case PAUSE_TIMERS:
                            mSavedPriority = Process.getThreadPriority(mTid);
                            Process.setThreadPriority(mTid,
                                    Process.THREAD_PRIORITY_BACKGROUND);
                            pauseTimers();
                            if (CacheManager.disableTransaction()) {
                                WebCoreThread.mCacheTickersBlocked = true;
                                sWebCoreHandler.removeMessages(
                                        WebCoreThread.CACHE_TICKER);
                            }
                            break;

                        case RESUME_TIMERS:
                            Process.setThreadPriority(mTid, mSavedPriority);
                            resumeTimers();
                            if (CacheManager.enableTransaction()) {
                                WebCoreThread.mCacheTickersBlocked = false;
                                sWebCoreHandler.sendMessageDelayed(
                                        sWebCoreHandler.obtainMessage(
                                        WebCoreThread.CACHE_TICKER),
                                        WebCoreThread.CACHE_TICKER_INTERVAL);
                            }
                            break;

                        case ON_PAUSE:
                            nativePause();
                            break;

                        case ON_RESUME:
                            nativeResume();
                            break;

                        case FREE_MEMORY:
                            clearCache(false);
                            nativeFreeMemory();
                            break;

                        case PLUGIN_STATE:
                            PluginStateData psd = (PluginStateData) msg.obj;
                            nativeUpdatePluginState(psd.mFrame, psd.mNode, psd.mState);
                            break;

                        case SET_NETWORK_STATE:
                            if (BrowserFrame.sJavaBridge == null) {
                                throw new IllegalStateException("No WebView " +
                                        "has been created in this process!");
                            }
                            BrowserFrame.sJavaBridge
                                    .setNetworkOnLine(msg.arg1 == 1);
                            break;

                        case CLEAR_CACHE:
                            clearCache(msg.arg1 == 1);
                            break;

                        case CLEAR_HISTORY:
                            mCallbackProxy.getBackForwardList().
                                    close(mBrowserFrame.mNativeFrame);
                            break;

                        case REPLACE_TEXT:
                            ReplaceTextData rep = (ReplaceTextData) msg.obj;
                            nativeReplaceTextfieldText(msg.arg1, msg.arg2,
                                    rep.mReplace, rep.mNewStart, rep.mNewEnd,
                                    rep.mTextGeneration);
                            break;

                        case PASS_TO_JS: {
                            JSKeyData jsData = (JSKeyData) msg.obj;
                            KeyEvent evt = jsData.mEvent;
                            int keyCode = evt.getKeyCode();
                            int keyValue = evt.getUnicodeChar();
                            int generation = msg.arg1;
                            passToJs(generation,
                                    jsData.mCurrentText,
                                    keyCode,
                                    keyValue,
                                    evt.isDown(),
                                    evt.isShiftPressed(), evt.isAltPressed(),
                                    evt.isSymPressed());
                            break;
                        }

                        case SAVE_DOCUMENT_STATE: {
                            CursorData cDat = (CursorData) msg.obj;
                            nativeSaveDocumentState(cDat.mFrame);
                            break;
                        }

                        case CLEAR_SSL_PREF_TABLE:
                            Network.getInstance(mContext)
                                    .clearUserSslPrefTable();
                            break;

                        case TOUCH_UP:
                            TouchUpData touchUpData = (TouchUpData) msg.obj;
                            nativeTouchUp(touchUpData.mMoveGeneration,
                                    touchUpData.mFrame, touchUpData.mNode,
                                    touchUpData.mX, touchUpData.mY);
                            break;

                        case TOUCH_EVENT: {
                            TouchEventData ted = (TouchEventData) msg.obj;
                            Message.obtain(
                                    mWebView.mPrivateHandler,
                                    WebView.PREVENT_TOUCH_ID, ted.mAction,
                                    nativeHandleTouchEvent(ted.mAction, ted.mX,
                                            ted.mY) ? 1 : 0).sendToTarget();
                            break;
                        }

                        case SET_ACTIVE:
                            nativeSetFocusControllerActive(msg.arg1 == 1);
                            break;

                        case ADD_JS_INTERFACE:
                            JSInterfaceData jsData = (JSInterfaceData) msg.obj;
                            mBrowserFrame.addJavascriptInterface(jsData.mObject,
                                    jsData.mInterfaceName);
                            break;

                        case REQUEST_EXT_REPRESENTATION:
                            mBrowserFrame.externalRepresentation(
                                    (Message) msg.obj);
                            break;

                        case REQUEST_DOC_AS_TEXT:
                            mBrowserFrame.documentAsText((Message) msg.obj);
                            break;

                        case SET_MOVE_MOUSE:
                            CursorData cursorData = (CursorData) msg.obj;
                            nativeMoveMouse(cursorData.mFrame,
                                     cursorData.mX, cursorData.mY);
                            break;

                        case SET_MOVE_MOUSE_IF_LATEST:
                            CursorData cData = (CursorData) msg.obj;
                            nativeMoveMouseIfLatest(cData.mMoveGeneration,
                                    cData.mFrame,
                                    cData.mX, cData.mY);
                            break;

                        case REQUEST_CURSOR_HREF: {
                            Message hrefMsg = (Message) msg.obj;
                            String res = nativeRetrieveHref(msg.arg1, msg.arg2);
                            hrefMsg.getData().putString("url", res);
                            hrefMsg.sendToTarget();
                            break;
                        }

                        case UPDATE_CACHE_AND_TEXT_ENTRY:
                            nativeUpdateFrameCache();
                            // FIXME: this should provide a minimal rectangle
                            if (mWebView != null) {
                                mWebView.postInvalidate();
                            }
                            sendUpdateTextEntry();
                            break;

                        case DOC_HAS_IMAGES:
                            Message imageResult = (Message) msg.obj;
                            imageResult.arg1 =
                                    mBrowserFrame.documentHasImages() ? 1 : 0;
                            imageResult.sendToTarget();
                            break;

                        case DELETE_SELECTION:
                            TextSelectionData deleteSelectionData
                                    = (TextSelectionData) msg.obj;
                            nativeDeleteSelection(deleteSelectionData.mStart,
                                    deleteSelectionData.mEnd, msg.arg1);
                            break;

                        case SET_SELECTION:
                            nativeSetSelection(msg.arg1, msg.arg2);
                            break;

                        case LISTBOX_CHOICES:
                            SparseBooleanArray choices = (SparseBooleanArray)
                                    msg.obj;
                            int choicesSize = msg.arg1;
                            boolean[] choicesArray = new boolean[choicesSize];
                            for (int c = 0; c < choicesSize; c++) {
                                choicesArray[c] = choices.get(c);
                            }
                            nativeSendListBoxChoices(choicesArray,
                                    choicesSize);
                            break;

                        case SINGLE_LISTBOX_CHOICE:
                            nativeSendListBoxChoice(msg.arg1);
                            break;

                        case SET_BACKGROUND_COLOR:
                            nativeSetBackgroundColor(msg.arg1);
                            break;

                        case GET_SELECTION:
                            String str = nativeGetSelection((Region) msg.obj);
                            Message.obtain(mWebView.mPrivateHandler
                                    , WebView.UPDATE_CLIPBOARD, str)
                                    .sendToTarget();
                            break;

                        case DUMP_DOMTREE:
                            nativeDumpDomTree(msg.arg1 == 1);
                            break;

                        case DUMP_RENDERTREE:
                            nativeDumpRenderTree(msg.arg1 == 1);
                            break;

                        case DUMP_NAVTREE:
                            nativeDumpNavTree();
                            break;

                        case SET_JS_FLAGS:
                            nativeSetJsFlags((String)msg.obj);
                            break;

                        case GEOLOCATION_PERMISSIONS_PROVIDE:
                            GeolocationPermissionsData data =
                                    (GeolocationPermissionsData) msg.obj;
                            nativeGeolocationPermissionsProvide(data.mOrigin,
                                    data.mAllow, data.mRemember);
                            break;

                        case SYNC_SCROLL:
                            mWebkitScrollX = msg.arg1;
                            mWebkitScrollY = msg.arg2;
                            break;

                        case SPLIT_PICTURE_SET:
                            nativeSplitContent();
                            mSplitPictureIsScheduled = false;
                            break;

                        case CLEAR_CONTENT:
                            // Clear the view so that onDraw() will draw nothing
                            // but white background
                            // (See public method WebView.clearView)
                            nativeClearContent();
                            break;

                        case MESSAGE_RELAY:
                            if (msg.obj instanceof Message) {
                                ((Message) msg.obj).sendToTarget();
                            }
                            break;

                        case POPULATE_VISITED_LINKS:
                            nativeProvideVisitedHistory((String[])msg.obj);
                            break;
                    }
                }
            };
            // Take all queued messages and resend them to the new handler.
            synchronized (this) {
                int size = mMessages.size();
                for (int i = 0; i < size; i++) {
                    mHandler.sendMessage(mMessages.get(i));
                }
                mMessages = null;
            }
        }

        /**
         * Send a message internally to the queue or to the handler
         */
        private synchronized void sendMessage(Message msg) {
            if (mBlockMessages) {
                return;
            }
            if (mMessages != null) {
                mMessages.add(msg);
            } else {
                mHandler.sendMessage(msg);
            }
        }

        private synchronized void removeMessages(int what) {
            if (mBlockMessages) {
                return;
            }
            if (what == EventHub.WEBKIT_DRAW) {
                mDrawIsScheduled = false;
            }
            if (mMessages != null) {
                Log.w(LOGTAG, "Not supported in this case.");
            } else {
                mHandler.removeMessages(what);
            }
        }

        private synchronized boolean hasMessages(int what) {
            if (mBlockMessages) {
                return false;
            }
            if (mMessages != null) {
                Log.w(LOGTAG, "hasMessages() is not supported in this case.");
                return false;
            } else {
                return mHandler.hasMessages(what);
            }
        }

        private synchronized void sendMessageDelayed(Message msg, long delay) {
            if (mBlockMessages) {
                return;
            }
            mHandler.sendMessageDelayed(msg, delay);
        }

        /**
         * Send a message internally to the front of the queue.
         */
        private synchronized void sendMessageAtFrontOfQueue(Message msg) {
            if (mBlockMessages) {
                return;
            }
            if (mMessages != null) {
                mMessages.add(0, msg);
            } else {
                mHandler.sendMessageAtFrontOfQueue(msg);
            }
        }

        /**
         * Remove all the messages.
         */
        private synchronized void removeMessages() {
            // reset mDrawIsScheduled flag as WEBKIT_DRAW may be removed
            mDrawIsScheduled = false;
            mSplitPictureIsScheduled = false;
            if (mMessages != null) {
                mMessages.clear();
            } else {
                mHandler.removeCallbacksAndMessages(null);
            }
        }

        /**
         * Block sending messages to the EventHub.
         */
        private synchronized void blockMessages() {
            mBlockMessages = true;
        }
    }

    //-------------------------------------------------------------------------
    // Methods called by host activity (in the same thread)
    //-------------------------------------------------------------------------

    void stopLoading() {
        if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "CORE stopLoading");
        if (mBrowserFrame != null) {
            mBrowserFrame.stopLoading();
        }
    }

    //-------------------------------------------------------------------------
    // Methods called by WebView
    // If it refers to local variable, it needs synchronized().
    // If it needs WebCore, it has to send message.
    //-------------------------------------------------------------------------

    void sendMessage(Message msg) {
        mEventHub.sendMessage(msg);
    }

    void sendMessage(int what) {
        mEventHub.sendMessage(Message.obtain(null, what));
    }

    void sendMessage(int what, Object obj) {
        mEventHub.sendMessage(Message.obtain(null, what, obj));
    }

    void sendMessage(int what, int arg1) {
        // just ignore the second argument (make it 0)
        mEventHub.sendMessage(Message.obtain(null, what, arg1, 0));
    }

    void sendMessage(int what, int arg1, int arg2) {
        mEventHub.sendMessage(Message.obtain(null, what, arg1, arg2));
    }

    void sendMessage(int what, int arg1, Object obj) {
        // just ignore the second argument (make it 0)
        mEventHub.sendMessage(Message.obtain(null, what, arg1, 0, obj));
    }

    void sendMessage(int what, int arg1, int arg2, Object obj) {
        mEventHub.sendMessage(Message.obtain(null, what, arg1, arg2, obj));
    }

    void sendMessageDelayed(int what, Object obj, long delay) {
        mEventHub.sendMessageDelayed(Message.obtain(null, what, obj), delay);
    }

    void removeMessages(int what) {
        mEventHub.removeMessages(what);
    }

    void removeMessages() {
        mEventHub.removeMessages();
    }

    /**
     * Removes pending messages and trigger a DESTROY message to send to
     * WebCore.
     * Called from UI thread.
     */
    void destroy() {
        // We don't want anyone to post a message between removing pending
        // messages and sending the destroy message.
        synchronized (mEventHub) {
            // RESUME_TIMERS and PAUSE_TIMERS are per process base. They need to
            // be preserved even the WebView is destroyed.
            // Note: we should not have more than one RESUME_TIMERS/PAUSE_TIMERS
            boolean hasResume = mEventHub.hasMessages(EventHub.RESUME_TIMERS);
            boolean hasPause = mEventHub.hasMessages(EventHub.PAUSE_TIMERS);
            mEventHub.removeMessages();
            mEventHub.sendMessageAtFrontOfQueue(
                    Message.obtain(null, EventHub.DESTROY));
            if (hasPause) {
                mEventHub.sendMessageAtFrontOfQueue(
                        Message.obtain(null, EventHub.PAUSE_TIMERS));
            }
            if (hasResume) {
                mEventHub.sendMessageAtFrontOfQueue(
                        Message.obtain(null, EventHub.RESUME_TIMERS));
            }
            mEventHub.blockMessages();
        }
    }

    //-------------------------------------------------------------------------
    // WebViewCore private methods
    //-------------------------------------------------------------------------

    private void clearCache(boolean includeDiskFiles) {
        mBrowserFrame.clearCache();
        if (includeDiskFiles) {
            CacheManager.removeAllCacheFiles();
        }
    }

    private void loadUrl(String url) {
        if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, " CORE loadUrl " + url);
        mBrowserFrame.loadUrl(url);
    }

    private void key(KeyEvent evt, boolean isDown) {
        if (DebugFlags.WEB_VIEW_CORE) {
            Log.v(LOGTAG, "CORE key at " + System.currentTimeMillis() + ", "
                    + evt);
        }
        int keyCode = evt.getKeyCode();
        if (!nativeKey(keyCode, evt.getUnicodeChar(),
                evt.getRepeatCount(), evt.isShiftPressed(), evt.isAltPressed(),
                evt.isSymPressed(),
                isDown) && keyCode != KeyEvent.KEYCODE_ENTER) {
            if (keyCode >= KeyEvent.KEYCODE_DPAD_UP
                    && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (DebugFlags.WEB_VIEW_CORE) {
                    Log.v(LOGTAG, "key: arrow unused by plugin: " + keyCode);
                }
                if (mWebView != null && evt.isDown()) {
                    Message.obtain(mWebView.mPrivateHandler,
                            WebView.MOVE_OUT_OF_PLUGIN, keyCode).sendToTarget();
                }
                return;
            }
            // bubble up the event handling
            // but do not bubble up the ENTER key, which would open the search
            // bar without any text.
            mCallbackProxy.onUnhandledKeyEvent(evt);
        }
    }

    // These values are used to avoid requesting a layout based on old values
    private int mCurrentViewWidth = 0;
    private int mCurrentViewHeight = 0;
    private float mCurrentViewScale = 1.0f;

    // notify webkit that our virtual view size changed size (after inv-zoom)
    private void viewSizeChanged(int w, int h, int textwrapWidth, float scale,
            int anchorX, int anchorY, boolean ignoreHeight) {
        if (DebugFlags.WEB_VIEW_CORE) {
            Log.v(LOGTAG, "viewSizeChanged w=" + w + "; h=" + h
                    + "; textwrapWidth=" + textwrapWidth + "; scale=" + scale);
        }
        if (w == 0) {
            Log.w(LOGTAG, "skip viewSizeChanged as w is 0");
            return;
        }
        int width = w;
        if (mSettings.getUseWideViewPort()) {
            if (mViewportWidth == -1) {
                if (mSettings.getLayoutAlgorithm() ==
                        WebSettings.LayoutAlgorithm.NORMAL) {
                    width = DEFAULT_VIEWPORT_WIDTH;
                } else {
                    /*
                     * if a page's minimum preferred width is wider than the
                     * given "w", use it instead to get better layout result. If
                     * we start a page with MAX_ZOOM_WIDTH, "w" will be always
                     * wider. If we start a page with screen width, due to the
                     * delay between {@link #didFirstLayout} and
                     * {@link #viewSizeChanged},
                     * {@link #nativeGetContentMinPrefWidth} will return a more
                     * accurate value than initial 0 to result a better layout.
                     * In the worse case, the native width will be adjusted when
                     * next zoom or screen orientation change happens.
                     */
                    width = Math.max(w, Math.max(DEFAULT_VIEWPORT_WIDTH,
                            nativeGetContentMinPrefWidth()));
                }
            } else if (mViewportWidth > 0) {
                width = Math.max(w, mViewportWidth);
            } else {
                width = textwrapWidth;
            }
        }
        nativeSetSize(width, width == w ? h : Math.round((float) width * h / w),
                textwrapWidth, scale, w, h, anchorX, anchorY, ignoreHeight);
        // Remember the current width and height
        boolean needInvalidate = (mCurrentViewWidth == 0);
        mCurrentViewWidth = w;
        mCurrentViewHeight = h;
        mCurrentViewScale = scale;
        if (needInvalidate) {
            // ensure {@link #webkitDraw} is called as we were blocking in
            // {@link #contentDraw} when mCurrentViewWidth is 0
            if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "viewSizeChanged");
            contentDraw();
        }
        mEventHub.sendMessage(Message.obtain(null,
                EventHub.UPDATE_CACHE_AND_TEXT_ENTRY));
    }

    private void sendUpdateTextEntry() {
        if (mWebView != null) {
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.UPDATE_TEXT_ENTRY_MSG_ID).sendToTarget();
        }
    }

    // Utility method for exceededDatabaseQuota and reachedMaxAppCacheSize
    // callbacks. Computes the sum of database quota for all origins.
    private long getUsedQuota() {
        WebStorage webStorage = WebStorage.getInstance();
        Collection<WebStorage.Origin> origins = webStorage.getOriginsSync();

        if (origins == null) {
            return 0;
        }
        long usedQuota = 0;
        for (WebStorage.Origin website : origins) {
            usedQuota += website.getQuota();
        }
        return usedQuota;
    }

    // Used to avoid posting more than one draw message.
    private boolean mDrawIsScheduled;

    // Used to avoid posting more than one split picture message.
    private boolean mSplitPictureIsScheduled;

    // Used to suspend drawing.
    private boolean mDrawIsPaused;

    // mRestoreState is set in didFirstLayout(), and reset in the next
    // webkitDraw after passing it to the UI thread.
    private RestoreState mRestoreState = null;

    static class RestoreState {
        float mMinScale;
        float mMaxScale;
        float mViewScale;
        float mTextWrapScale;
        float mDefaultScale;
        int mScrollX;
        int mScrollY;
        boolean mMobileSite;
    }

    static class DrawData {
        DrawData() {
            mInvalRegion = new Region();
            mWidthHeight = new Point();
        }
        Region mInvalRegion;
        Point mViewPoint;
        Point mWidthHeight;
        int mMinPrefWidth;
        RestoreState mRestoreState; // only non-null if it is for the first
                                    // picture set after the first layout
    }

    private void webkitDraw() {
        mDrawIsScheduled = false;
        DrawData draw = new DrawData();
        if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw start");
        if (nativeRecordContent(draw.mInvalRegion, draw.mWidthHeight)
                == false) {
            if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw abort");
            return;
        }
        if (mWebView != null) {
            // Send the native view size that was used during the most recent
            // layout.
            draw.mViewPoint = new Point(mCurrentViewWidth, mCurrentViewHeight);
            if (mSettings.getUseWideViewPort()) {
                draw.mMinPrefWidth = Math.max(
                        mViewportWidth == -1 ? DEFAULT_VIEWPORT_WIDTH
                                : (mViewportWidth == 0 ? mCurrentViewWidth
                                        : mViewportWidth),
                        nativeGetContentMinPrefWidth());
            }
            if (mRestoreState != null) {
                draw.mRestoreState = mRestoreState;
                mRestoreState = null;
            }
            if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw NEW_PICTURE_MSG_ID");
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.NEW_PICTURE_MSG_ID, draw).sendToTarget();
            if (mWebkitScrollX != 0 || mWebkitScrollY != 0) {
                // as we have the new picture, try to sync the scroll position
                Message.obtain(mWebView.mPrivateHandler,
                        WebView.SYNC_SCROLL_TO_MSG_ID, mWebkitScrollX,
                        mWebkitScrollY).sendToTarget();
                mWebkitScrollX = mWebkitScrollY = 0;
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // These are called from the UI thread, not our thread

    static final int ZOOM_BITS = Paint.FILTER_BITMAP_FLAG |
                                         Paint.DITHER_FLAG |
                                         Paint.SUBPIXEL_TEXT_FLAG;
    static final int SCROLL_BITS = Paint.FILTER_BITMAP_FLAG |
                                           Paint.DITHER_FLAG;

    final DrawFilter mZoomFilter =
                    new PaintFlagsDrawFilter(ZOOM_BITS, Paint.LINEAR_TEXT_FLAG);
    final DrawFilter mScrollFilter =
                new PaintFlagsDrawFilter(SCROLL_BITS, 0);

    /* package */ void drawContentPicture(Canvas canvas, int color,
                                          boolean animatingZoom,
                                          boolean animatingScroll) {
        DrawFilter df = null;
        if (animatingZoom) {
            df = mZoomFilter;
        } else if (animatingScroll) {
            df = mScrollFilter;
        }
        canvas.setDrawFilter(df);
        boolean tookTooLong = nativeDrawContent(canvas, color);
        canvas.setDrawFilter(null);
        if (tookTooLong && mSplitPictureIsScheduled == false) {
            mSplitPictureIsScheduled = true;
            sendMessage(EventHub.SPLIT_PICTURE_SET);
        }
    }

    /* package */ synchronized boolean pictureReady() {
        return 0 != mNativeClass ? nativePictureReady() : false;
    }

    /*package*/ synchronized Picture copyContentPicture() {
        Picture result = new Picture();
        if (0 != mNativeClass) {
            nativeCopyContentToPicture(result);
        }
        return result;
    }

    static void pauseUpdate(WebViewCore core) {
        // remove the pending REDUCE_PRIORITY and RESUME_PRIORITY messages
        sWebCoreHandler.removeMessages(WebCoreThread.REDUCE_PRIORITY);
        sWebCoreHandler.removeMessages(WebCoreThread.RESUME_PRIORITY);
        sWebCoreHandler.sendMessageAtFrontOfQueue(sWebCoreHandler
                .obtainMessage(WebCoreThread.REDUCE_PRIORITY));
        // Note: there is one possible failure mode. If pauseUpdate() is called
        // from UI thread while in webcore thread WEBKIT_DRAW is just pulled out
        // of the queue and about to be executed. mDrawIsScheduled may be set to
        // false in webkitDraw(). So update won't be blocked. But at least the
        // webcore thread priority is still lowered.
        if (core != null) {
            synchronized (core) {
                core.mDrawIsPaused = true;
                core.mEventHub.removeMessages(EventHub.WEBKIT_DRAW);
            }
        }
    }

    static void resumeUpdate(WebViewCore core) {
        // remove the pending REDUCE_PRIORITY and RESUME_PRIORITY messages
        sWebCoreHandler.removeMessages(WebCoreThread.REDUCE_PRIORITY);
        sWebCoreHandler.removeMessages(WebCoreThread.RESUME_PRIORITY);
        sWebCoreHandler.sendMessageAtFrontOfQueue(sWebCoreHandler
                .obtainMessage(WebCoreThread.RESUME_PRIORITY));
        if (core != null) {
            synchronized (core) {
                core.mDrawIsScheduled = false;
                core.mDrawIsPaused = false;
                if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "resumeUpdate");
                core.contentDraw();
            }
        }
    }

    static void startCacheTransaction() {
        sWebCoreHandler.sendMessage(sWebCoreHandler
                .obtainMessage(WebCoreThread.RESUME_CACHE_TICKER));
    }

    static void endCacheTransaction() {
        sWebCoreHandler.sendMessage(sWebCoreHandler
                .obtainMessage(WebCoreThread.BLOCK_CACHE_TICKER));
    }

    //////////////////////////////////////////////////////////////////////////

    private void restoreState(int index) {
        WebBackForwardList list = mCallbackProxy.getBackForwardList();
        int size = list.getSize();
        for (int i = 0; i < size; i++) {
            list.getItemAtIndex(i).inflate(mBrowserFrame.mNativeFrame);
        }
        mBrowserFrame.mLoadInitFromJava = true;
        list.restoreIndex(mBrowserFrame.mNativeFrame, index);
        mBrowserFrame.mLoadInitFromJava = false;
    }

    //-------------------------------------------------------------------------
    // Implement abstract methods in WebViewCore, native WebKit callback part
    //-------------------------------------------------------------------------

    // called from JNI or WebView thread
    /* package */ void contentDraw() {
        // don't update the Picture until we have an initial width and finish
        // the first layout
        if (mCurrentViewWidth == 0 || !mBrowserFrame.firstLayoutDone()) {
            return;
        }
        // only fire an event if this is our first request
        synchronized (this) {
            if (mDrawIsPaused || mDrawIsScheduled) {
                return;
            }
            mDrawIsScheduled = true;
            mEventHub.sendMessage(Message.obtain(null, EventHub.WEBKIT_DRAW));
        }
    }

    // called by JNI
    private void contentScrollBy(int dx, int dy, boolean animate) {
        if (!mBrowserFrame.firstLayoutDone()) {
            // Will this happen? If yes, we need to do something here.
            return;
        }
        if (mWebView != null) {
            Message msg = Message.obtain(mWebView.mPrivateHandler,
                    WebView.SCROLL_BY_MSG_ID, dx, dy, new Boolean(animate));
            if (mDrawIsScheduled) {
                mEventHub.sendMessage(Message.obtain(null,
                        EventHub.MESSAGE_RELAY, msg));
            } else {
                msg.sendToTarget();
            }
        }
    }

    // called by JNI
    private void contentScrollTo(int x, int y) {
        if (!mBrowserFrame.firstLayoutDone()) {
            /*
             * WebKit restore state will be called before didFirstLayout(),
             * remember the position as it has to be applied after restoring
             * zoom factor which is controlled by screenWidth.
             */
            mRestoredX = x;
            mRestoredY = y;
            return;
        }
        if (mWebView != null) {
            Message msg = Message.obtain(mWebView.mPrivateHandler,
                    WebView.SCROLL_TO_MSG_ID, x, y);
            if (mDrawIsScheduled) {
                mEventHub.sendMessage(Message.obtain(null,
                        EventHub.MESSAGE_RELAY, msg));
            } else {
                msg.sendToTarget();
            }
        }
    }

    // called by JNI
    private void contentSpawnScrollTo(int x, int y) {
        if (!mBrowserFrame.firstLayoutDone()) {
            /*
             * WebKit restore state will be called before didFirstLayout(),
             * remember the position as it has to be applied after restoring
             * zoom factor which is controlled by screenWidth.
             */
            mRestoredX = x;
            mRestoredY = y;
            return;
        }
        if (mWebView != null) {
            Message msg = Message.obtain(mWebView.mPrivateHandler,
                    WebView.SPAWN_SCROLL_TO_MSG_ID, x, y);
            if (mDrawIsScheduled) {
                mEventHub.sendMessage(Message.obtain(null,
                        EventHub.MESSAGE_RELAY, msg));
            } else {
                msg.sendToTarget();
            }
        }
    }

    // called by JNI
    private void sendNotifyProgressFinished() {
        sendUpdateTextEntry();
        // as CacheManager can behave based on database transaction, we need to
        // call tick() to trigger endTransaction
        sWebCoreHandler.removeMessages(WebCoreThread.CACHE_TICKER);
        sWebCoreHandler.sendMessage(sWebCoreHandler
                .obtainMessage(WebCoreThread.CACHE_TICKER));
        contentDraw();
    }

    /*  Called by JNI. The coordinates are in doc coordinates, so they need to
        be scaled before they can be used by the view system, which happens
        in WebView since it (and its thread) know the current scale factor.
     */
    private void sendViewInvalidate(int left, int top, int right, int bottom) {
        if (mWebView != null) {
            Message.obtain(mWebView.mPrivateHandler,
                           WebView.INVAL_RECT_MSG_ID,
                           new Rect(left, top, right, bottom)).sendToTarget();
        }
    }

    /* package */ WebView getWebView() {
        return mWebView;
    }

    private native void setViewportSettingsFromNative();

    // called by JNI
    private void didFirstLayout(boolean standardLoad) {
        if (DebugFlags.WEB_VIEW_CORE) {
            Log.v(LOGTAG, "didFirstLayout standardLoad =" + standardLoad);
        }

        mBrowserFrame.didFirstLayout();

        if (mWebView == null) return;

        setupViewport(standardLoad || mRestoredScale > 0);

        // reset the scroll position, the restored offset and scales
        mWebkitScrollX = mWebkitScrollY = mRestoredX = mRestoredY
                = mRestoredScale = mRestoredScreenWidthScale = 0;
    }

    // called by JNI
    private void updateViewport() {
        // if updateViewport is called before first layout, wait until first
        // layout to update the viewport. In the rare case, this is called after
        // first layout, force an update as we have just parsed the viewport
        // meta tag.
        if (mBrowserFrame.firstLayoutDone()) {
            setupViewport(true);
        }
    }

    private void setupViewport(boolean updateRestoreState) {
        // set the viewport settings from WebKit
        setViewportSettingsFromNative();

        // adjust the default scale to match the densityDpi
        float adjust = 1.0f;
        if (mViewportDensityDpi == -1) {
            if (WebView.DEFAULT_SCALE_PERCENT != 100) {
                adjust = WebView.DEFAULT_SCALE_PERCENT / 100.0f;
            }
        } else if (mViewportDensityDpi > 0) {
            adjust = (float) mContext.getResources().getDisplayMetrics().densityDpi
                    / mViewportDensityDpi;
        }
        int defaultScale = (int) (adjust * 100);

        if (mViewportInitialScale > 0) {
            mViewportInitialScale *= adjust;
        }
        if (mViewportMinimumScale > 0) {
            mViewportMinimumScale *= adjust;
        }
        if (mViewportMaximumScale > 0) {
            mViewportMaximumScale *= adjust;
        }

        // infer the values if they are not defined.
        if (mViewportWidth == 0) {
            if (mViewportInitialScale == 0) {
                mViewportInitialScale = defaultScale;
            }
        }
        if (mViewportUserScalable == false) {
            mViewportInitialScale = defaultScale;
            mViewportMinimumScale = defaultScale;
            mViewportMaximumScale = defaultScale;
        }
        if (mViewportMinimumScale > mViewportInitialScale
                && mViewportInitialScale != 0) {
            mViewportMinimumScale = mViewportInitialScale;
        }
        if (mViewportMaximumScale > 0
                && mViewportMaximumScale < mViewportInitialScale) {
            mViewportMaximumScale = mViewportInitialScale;
        }
        if (mViewportWidth < 0 && mViewportInitialScale == defaultScale) {
            mViewportWidth = 0;
        }

        // if mViewportWidth is 0, it means device-width, always update.
        if (mViewportWidth != 0 && !updateRestoreState) {
            RestoreState restoreState = new RestoreState();
            restoreState.mMinScale = mViewportMinimumScale / 100.0f;
            restoreState.mMaxScale = mViewportMaximumScale / 100.0f;
            restoreState.mDefaultScale = adjust;
            // as mViewportWidth is not 0, it is not mobile site.
            restoreState.mMobileSite = false;
            // for non-mobile site, we don't need minPrefWidth, set it as 0
            restoreState.mScrollX = 0;
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.UPDATE_ZOOM_RANGE, restoreState).sendToTarget();
            return;
        }

        // now notify webview
        // webViewWidth refers to the width in the view system
        int webViewWidth;
        // viewportWidth refers to the width in the document system
        int viewportWidth = mCurrentViewWidth;
        if (viewportWidth == 0) {
            // this may happen when WebView just starts. This is not perfect as
            // we call WebView method from WebCore thread. But not perfect
            // reference is better than no reference.
            webViewWidth = mWebView.getViewWidth();
            viewportWidth = (int) (webViewWidth / adjust);
            if (viewportWidth == 0) {
                Log.w(LOGTAG, "Can't get the viewWidth after the first layout");
            }
        } else {
            webViewWidth = Math.round(viewportWidth * mCurrentViewScale);
        }
        mRestoreState = new RestoreState();
        mRestoreState.mMinScale = mViewportMinimumScale / 100.0f;
        mRestoreState.mMaxScale = mViewportMaximumScale / 100.0f;
        mRestoreState.mDefaultScale = adjust;
        mRestoreState.mScrollX = mRestoredX;
        mRestoreState.mScrollY = mRestoredY;
        mRestoreState.mMobileSite = (0 == mViewportWidth);
        if (mRestoredScale > 0) {
            mRestoreState.mViewScale = mRestoredScale / 100.0f;
            if (mRestoredScreenWidthScale > 0) {
                mRestoreState.mTextWrapScale =
                        mRestoredScreenWidthScale / 100.0f;
            } else {
                mRestoreState.mTextWrapScale = mRestoreState.mViewScale;
            }
        } else {
            if (mViewportInitialScale > 0) {
                mRestoreState.mViewScale = mRestoreState.mTextWrapScale =
                        mViewportInitialScale / 100.0f;
            } else if (mViewportWidth > 0 && mViewportWidth < webViewWidth) {
                mRestoreState.mViewScale = mRestoreState.mTextWrapScale =
                        (float) webViewWidth / mViewportWidth;
            } else {
                mRestoreState.mTextWrapScale = adjust;
                // 0 will trigger WebView to turn on zoom overview mode
                mRestoreState.mViewScale = 0;
            }
        }

        if (mWebView.mHeightCanMeasure) {
            // Trick to ensure that the Picture has the exact height for the
            // content by forcing to layout with 0 height after the page is
            // ready, which is indicated by didFirstLayout. This is essential to
            // get rid of the white space in the GMail which uses WebView for
            // message view.
            mWebView.mLastHeightSent = 0;
            // Send a negative scale to indicate that WebCore should reuse
            // the current scale
            WebView.ViewSizeData data = new WebView.ViewSizeData();
            data.mWidth = mWebView.mLastWidthSent;
            data.mHeight = 0;
            // if mHeightCanMeasure is true, getUseWideViewPort() can't be
            // true. It is safe to use mWidth for mTextWrapWidth.
            data.mTextWrapWidth = data.mWidth;
            data.mScale = -1.0f;
            data.mIgnoreHeight = false;
            data.mAnchorX = data.mAnchorY = 0;
            // send VIEW_SIZE_CHANGED to the front of the queue so that we can
            // avoid pushing the wrong picture to the WebView side. If there is
            // a VIEW_SIZE_CHANGED in the queue, probably from WebView side,
            // ignore it as we have a new size. If we leave VIEW_SIZE_CHANGED
            // in the queue, as mLastHeightSent has been updated here, we may
            // miss the requestLayout in WebView side after the new picture.
            mEventHub.removeMessages(EventHub.VIEW_SIZE_CHANGED);
            mEventHub.sendMessageAtFrontOfQueue(Message.obtain(null,
                    EventHub.VIEW_SIZE_CHANGED, data));
        } else if (mSettings.getUseWideViewPort()) {
            if (viewportWidth == 0) {
                // Trick to ensure VIEW_SIZE_CHANGED will be sent from WebView
                // to WebViewCore
                mWebView.mLastWidthSent = 0;
            } else {
                WebView.ViewSizeData data = new WebView.ViewSizeData();
                // mViewScale as 0 means it is in zoom overview mode. So we don't
                // know the exact scale. If mRestoredScale is non-zero, use it;
                // otherwise just use mTextWrapScale as the initial scale.
                data.mScale = mRestoreState.mViewScale == 0
                        ? (mRestoredScale > 0 ? mRestoredScale
                                : mRestoreState.mTextWrapScale)
                        : mRestoreState.mViewScale;
                data.mWidth = Math.round(webViewWidth / data.mScale);
                data.mHeight = mCurrentViewHeight * data.mWidth / viewportWidth;
                data.mTextWrapWidth = Math.round(webViewWidth
                        / mRestoreState.mTextWrapScale);
                data.mIgnoreHeight = false;
                data.mAnchorX = data.mAnchorY = 0;
                // send VIEW_SIZE_CHANGED to the front of the queue so that we
                // can avoid pushing the wrong picture to the WebView side.
                mEventHub.removeMessages(EventHub.VIEW_SIZE_CHANGED);
                mEventHub.sendMessageAtFrontOfQueue(Message.obtain(null,
                        EventHub.VIEW_SIZE_CHANGED, data));
            }
        }
    }

    // called by JNI
    private void restoreScale(int scale) {
        if (mBrowserFrame.firstLayoutDone() == false) {
            mRestoredScale = scale;
        }
    }

    // called by JNI
    private void restoreScreenWidthScale(int scale) {
        if (!mSettings.getUseWideViewPort()) {
            return;
        }

        if (mBrowserFrame.firstLayoutDone() == false) {
            mRestoredScreenWidthScale = scale;
        }
    }

    // called by JNI
    private void needTouchEvents(boolean need) {
        if (mWebView != null) {
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.WEBCORE_NEED_TOUCH_EVENTS, need ? 1 : 0, 0)
                    .sendToTarget();
        }
    }

    // called by JNI
    private void updateTextfield(int ptr, boolean changeToPassword,
            String text, int textGeneration) {
        if (mWebView != null) {
            Message msg = Message.obtain(mWebView.mPrivateHandler,
                    WebView.UPDATE_TEXTFIELD_TEXT_MSG_ID, ptr,
                    textGeneration, text);
            msg.getData().putBoolean("password", changeToPassword);
            msg.sendToTarget();
        }
    }

    // called by JNI
    private void updateTextSelection(int pointer, int start, int end,
            int textGeneration) {
        if (mWebView != null) {
            Message.obtain(mWebView.mPrivateHandler,
                WebView.UPDATE_TEXT_SELECTION_MSG_ID, pointer, textGeneration,
                new TextSelectionData(start, end)).sendToTarget();
        }
    }

    // called by JNI
    private void clearTextEntry() {
        if (mWebView == null) return;
        Message.obtain(mWebView.mPrivateHandler,
                WebView.CLEAR_TEXT_ENTRY).sendToTarget();
    }

    private native void nativeUpdateFrameCacheIfLoading();

    /**
     * Scroll the focused textfield to (xPercent, y) in document space
     */
    private native void nativeScrollFocusedTextInput(float xPercent, int y);

    // these must be in document space (i.e. not scaled/zoomed).
    private native void nativeSetScrollOffset(int gen, int dx, int dy);

    private native void nativeSetGlobalBounds(int x, int y, int w, int h);

    // called by JNI
    private void requestListBox(String[] array, boolean[] enabledArray,
            int[] selectedArray) {
        if (mWebView != null) {
            mWebView.requestListBox(array, enabledArray, selectedArray);
        }
    }

    // called by JNI
    private void requestListBox(String[] array, boolean[] enabledArray,
            int selection) {
        if (mWebView != null) {
            mWebView.requestListBox(array, enabledArray, selection);
        }

    }

    // called by JNI
    private void requestKeyboard(boolean showKeyboard) {
        if (mWebView != null) {
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.REQUEST_KEYBOARD, showKeyboard ? 1 : 0, 0)
                    .sendToTarget();
        }
    }

    // called by JNI. PluginWidget function to launch an activity and overlays
    // the activity with the View provided by the plugin class.
    private void startFullScreenPluginActivity(String libName, String clsName, int npp) {
        if (mWebView == null) {
            return;
        }

        String pkgName = PluginManager.getInstance(null).getPluginsAPKName(libName);
        if (pkgName == null) {
            Log.w(LOGTAG, "Unable to resolve " + libName + " to a plugin APK");
            return;
        }

        Intent intent = new Intent("android.intent.webkit.PLUGIN");
        intent.putExtra(PluginActivity.INTENT_EXTRA_PACKAGE_NAME, pkgName);
        intent.putExtra(PluginActivity.INTENT_EXTRA_CLASS_NAME, clsName);
        intent.putExtra(PluginActivity.INTENT_EXTRA_NPP_INSTANCE, npp);
        mWebView.getContext().startActivity(intent);
    }

    // called by JNI.  PluginWidget functions for creating an embedded View for
    // the surface drawing model.
    private ViewManager.ChildView createSurface(String libName, String clsName,
            int npp, int x, int y, int width, int height) {
        if (mWebView == null) {
            return null;
        }

        String pkgName = PluginManager.getInstance(null).getPluginsAPKName(libName);
        if (pkgName == null) {
            Log.w(LOGTAG, "Unable to resolve " + libName + " to a plugin APK");
            return null;
        }

        PluginStub stub =PluginUtil.getPluginStub(mWebView.getContext(),pkgName, clsName);
        if (stub == null) {
            Log.e(LOGTAG, "Unable to find plugin class (" + clsName +
                    ") in the apk (" + pkgName + ")");
            return null;
        }

        View pluginView = stub.getEmbeddedView(npp, mWebView.getContext());

        ViewManager.ChildView view = mWebView.mViewManager.createView();
        view.mView = pluginView;
        view.attachView(x, y, width, height);
        return view;
    }
    
    private void destroySurface(ViewManager.ChildView childView) {
        childView.removeView();
    }

    // called by JNI
    static class ShowRectData {
        int mLeft;
        int mTop;
        int mWidth;
        int mHeight;
        int mContentWidth;
        int mContentHeight;
        float mXPercentInDoc;
        float mXPercentInView;
        float mYPercentInDoc;
        float mYPercentInView;
    }

    private void showRect(int left, int top, int width, int height,
            int contentWidth, int contentHeight, float xPercentInDoc,
            float xPercentInView, float yPercentInDoc, float yPercentInView) {
        if (mWebView != null) {
            ShowRectData data = new ShowRectData();
            data.mLeft = left;
            data.mTop = top;
            data.mWidth = width;
            data.mHeight = height;
            data.mContentWidth = contentWidth;
            data.mContentHeight = contentHeight;
            data.mXPercentInDoc = xPercentInDoc;
            data.mXPercentInView = xPercentInView;
            data.mYPercentInDoc = yPercentInDoc;
            data.mYPercentInView = yPercentInView;
            Message.obtain(mWebView.mPrivateHandler, WebView.SHOW_RECT_MSG_ID,
                    data).sendToTarget();
        }
    }

    private native void nativePause();
    private native void nativeResume();
    private native void nativeFreeMemory();
}
