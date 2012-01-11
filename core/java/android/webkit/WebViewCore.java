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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.media.MediaFile;
import android.net.ProxyProperties;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.provider.MediaStore;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.DeviceMotionService;
import android.webkit.DeviceMotionAndOrientationManager;
import android.webkit.DeviceOrientationService;
import android.webkit.JniUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import junit.framework.Assert;

/**
 * @hide
 */
public final class WebViewCore {

    private static final String LOGTAG = "webcore";

    static {
        // Load libwebcore and libchromium_net during static initialization.
        // This happens in the zygote process so they will be shared read-only
        // across all app processes.
        try {
            System.loadLibrary("webcore");
            System.loadLibrary("chromium_net");
        } catch (UnsatisfiedLinkError e) {
            Log.e(LOGTAG, "Unable to load native support libraries.");
        }
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

    private boolean mIsRestored = false;
    private float mRestoredScale = 0;
    private float mRestoredTextWrapScale = 0;
    private int mRestoredX = 0;
    private int mRestoredY = 0;

    private DeviceMotionAndOrientationManager mDeviceMotionAndOrientationManager =
            new DeviceMotionAndOrientationManager(this);
    private DeviceMotionService mDeviceMotionService;
    private DeviceOrientationService mDeviceOrientationService;

    private int mLowMemoryUsageThresholdMb;
    private int mHighMemoryUsageThresholdMb;
    private int mHighUsageDeltaMb;

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

        // Get the memory class of the current device. V8 will use these values
        // to GC more effectively.
        ActivityManager manager = (ActivityManager) mContext.getSystemService(
                Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memInfo);

        // Allow us to use up to our memory class value before V8's GC kicks in.
        // These values have been determined by experimentation.
        mLowMemoryUsageThresholdMb = manager.getLargeMemoryClass();
        mHighMemoryUsageThresholdMb = (int) (mLowMemoryUsageThresholdMb * 1.5);
        // Avoid constant V8 GC when memory usage equals to working set estimate.
        mHighUsageDeltaMb = mLowMemoryUsageThresholdMb / 32;

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
    /* package */ synchronized BrowserFrame getBrowserFrame() {
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

    /*
     * Given mimeType, check whether it's supported in Android media framework.
     * mimeType could be such as "audio/ogg" and "video/mp4".
     */
    /* package */ static boolean isSupportedMediaMimeType(String mimeType) {
        int fileType = MediaFile.getFileTypeForMimeType(mimeType);
        return MediaFile.isAudioFileType(fileType)
            || MediaFile.isVideoFileType(fileType)
            || MediaFile.isPlayListFileType(fileType)
            // The following is not in Media framework, but it's supported.
            || (mimeType != null && mimeType.startsWith("video/m4v"));
    }

    /**
     * Add an error message to the client's console.
     * @param message The message to add
     * @param lineNumber the line on which the error occurred
     * @param sourceID the filename of the source that caused the error.
     * @param msgLevel the log level of this message. This is a value casted to int
     *     from WebCore::MessageLevel in WebCore/page/Console.h.
     */
    protected void addMessageToConsole(String message, int lineNumber, String sourceID,
            int msgLevel) {
        mCallbackProxy.addMessageToConsole(message, lineNumber, sourceID, msgLevel);
    }

    /**
     * Invoke a javascript alert.
     * @param message The message displayed in the alert.
     */
    protected void jsAlert(String url, String message) {
        mCallbackProxy.onJsAlert(url, message);
    }

    /**
     * Called by JNI.  Send a message to the UI thread to hide the soft keyboard
     * if the node pointed to by nodePointer is still in focus.
     * @param nodePointer The node which just blurred.
     */
    private void formDidBlur(int nodePointer) {
        if (mWebView == null) return;
        Message.obtain(mWebView.mPrivateHandler, WebView.FORM_DID_BLUR,
                nodePointer, 0).sendToTarget();
    }

    /**
     * Called by JNI.  Open a file chooser to upload a file.
     * @param acceptType The value of the 'accept' attribute of the
     *         input tag associated with this file picker.
     * @return String version of the URI.
     */
    private String openFileChooser(String acceptType) {
        Uri uri = mCallbackProxy.openFileChooser(acceptType);
        if (uri != null) {
            String filePath = "";
            // Note - querying for MediaStore.Images.Media.DATA
            // seems to work for all content URIs, not just images
            Cursor cursor = mContext.getContentResolver().query(
                    uri,
                    new String[] { MediaStore.Images.Media.DATA },
                    null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToNext()) {
                        filePath = cursor.getString(0);
                    }
                } finally {
                    cursor.close();
                }
            } else {
                filePath = uri.getLastPathSegment();
            }
            String uriString = uri.toString();
            BrowserFrame.sJavaBridge.storeFilePathForContentUri(filePath, uriString);
            return uriString;
        }
        return "";
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

    /**
     * Notify the webview that this is an installable web app.
     */
    protected void setInstallableWebApp() {
        mCallbackProxy.setInstallableWebApp();
    }

    /**
     * Notify the webview that we want to display the video layer fullscreen.
     */
    protected void enterFullscreenForVideoLayer(int layerId, String url) {
        if (mWebView == null) return;
        Message message = Message.obtain(mWebView.mPrivateHandler,
                       WebView.ENTER_FULLSCREEN_VIDEO, layerId, 0);
        message.obj = url;
        message.sendToTarget();
    }

    //-------------------------------------------------------------------------
    // JNI methods
    //-------------------------------------------------------------------------

    static native String nativeFindAddress(String addr, boolean caseInsensitive);

    /**
     * Empty the picture set.
     */
    private native void nativeClearContent();

    private native void nativeContentInvalidateAll();

    /**
     * Redraw a portion of the picture set. The Point wh returns the
     * width and height of the overall picture.
     */
    private native int nativeRecordContent(Region invalRegion, Point wh);

    /**
     * Update the layers' content
     */
    private native boolean nativeUpdateLayers(int nativeClass, int baseLayer);

    /**
     * Notify webkit that animations have begun (on the hardware accelerated content)
     */
    private native void nativeNotifyAnimationStarted(int nativeClass);

    private native boolean nativeFocusBoundsChanged();

    /**
     * Splits slow parts of the picture set. Called from the webkit thread after
     * WebView.nativeDraw() returns content to be split.
     */
    private native void nativeSplitContent(int content);

    private native boolean nativeKey(int keyCode, int unichar,
            int repeatCount, boolean isShift, boolean isAlt, boolean isSym,
            boolean isDown);

    private native void nativeClick(int framePtr, int nodePtr, boolean fake);

    private native void nativeSendListBoxChoices(boolean[] choices, int size);

    private native void nativeSendListBoxChoice(int choice);

    private native void nativeCloseIdleConnections();

    /*  Tell webkit what its width and height are, for the purposes
        of layout/line-breaking. These coordinates are in document space,
        which is the same as View coords unless we have zoomed the document
        (see nativeSetZoom).
        textWrapWidth is used by layout to wrap column around. If viewport uses
        fixed size, textWrapWidth can be different from width with zooming.
        should this be called nativeSetViewPortSize?
    */
    private native void nativeSetSize(int width, int height, int textWrapWidth,
            float scale, int screenWidth, int screenHeight, int anchorX,
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

    private native void nativeMoveFocus(int framePtr, int nodePointer);
    private native void nativeMoveMouse(int framePtr, int x, int y);

    private native void nativeMoveMouseIfLatest(int moveGeneration,
            int framePtr, int x, int y);

    private native String nativeRetrieveHref(int x, int y);
    private native String nativeRetrieveAnchorText(int x, int y);
    private native String nativeRetrieveImageSource(int x, int y);
    private native void nativeStopPaintingCaret();
    private native void nativeTouchUp(int touchGeneration,
            int framePtr, int nodePtr, int x, int y);

    private native boolean nativeHandleTouchEvent(int action, int[] idArray,
            int[] xArray, int[] yArray, int count, int actionIndex, int metaState);

    private native void nativeUpdateFrameCache();

    private native void nativeSetBackgroundColor(int color);

    private native void nativeDumpDomTree(boolean useFile);

    private native void nativeDumpRenderTree(boolean useFile);

    private native void nativeDumpNavTree();

    private native void nativeDumpV8Counters();

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
    private native void nativeProvideVisitedHistory(String[] history);

    /**
     * Modifies the current selection.
     *
     * Note: Accessibility support.
     *
     * @param direction The direction in which to alter the selection.
     * @param granularity The granularity of the selection modification.
     *
     * @return The selection string.
     */
    private native String nativeModifySelection(int direction, int granularity);

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

                            case EventHub.ADD_PACKAGE_NAME:
                                if (BrowserFrame.sJavaBridge == null) {
                                    throw new IllegalStateException(
                                            "No WebView has been created in this process!");
                                }
                                BrowserFrame.sJavaBridge.addPackageName((String) msg.obj);
                                break;

                            case EventHub.REMOVE_PACKAGE_NAME:
                                if (BrowserFrame.sJavaBridge == null) {
                                    throw new IllegalStateException(
                                            "No WebView has been created in this process!");
                                }
                                BrowserFrame.sJavaBridge.removePackageName((String) msg.obj);
                                break;

                            case EventHub.PROXY_CHANGED:
                                if (BrowserFrame.sJavaBridge == null) {
                                    throw new IllegalStateException(
                                            "No WebView has been created in this process!");
                                }
                                BrowserFrame.sJavaBridge.updateProxy((ProxyProperties)msg.obj);
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
        String mHistoryUrl;
    }

    static class CursorData {
        CursorData() {}
        CursorData(int frame, int node, int x, int y) {
            mFrame = frame;
            mNode = node;
            mX = x;
            mY = y;
        }
        int mMoveGeneration;
        int mFrame;
        int mNode;
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

    static class MotionUpData {
        int mFrame;
        int mNode;
        Rect mBounds;
        int mX;
        int mY;
    }

    static class GetUrlData {
        String mUrl;
        Map<String, String> mExtraHeaders;
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
        int mNativeLayer;
        Rect mNativeLayerRect = new Rect();
    }

    static class TouchHighlightData {
        int mX;
        int mY;
        int mSlop;
        int mNativeLayer;
        Rect mNativeLayerRect;
    }

    static class AutoFillData {
        public AutoFillData() {
            mQueryId = WebTextView.FORM_NOT_AUTOFILLABLE;
            mPreview = "";
        }

        public AutoFillData(int queryId, String preview) {
            mQueryId = queryId;
            mPreview = preview;
        }

        public int getQueryId() {
            return mQueryId;
        }

        public String getPreviewString() {
            return mPreview;
        }

        private int mQueryId;
        private String mPreview;
    }

    // mAction of TouchEventData can be MotionEvent.getAction() which uses the
    // last two bytes or one of the following values
    static final int ACTION_LONGPRESS = 0x100;
    static final int ACTION_DOUBLETAP = 0x200;

    static class TouchEventData {
        int mAction;
        int[] mIds;  // Ids of the touch points
        Point[] mPoints;
        Point[] mPointsInView;  // the point coordinates in view axis.
        int mActionIndex;  // Associated pointer index for ACTION_POINTER_DOWN/UP
        int mMetaState;
        boolean mReprocess;
        MotionEvent mMotionEvent;
        int mNativeLayer;
        Rect mNativeLayerRect = new Rect();
        long mSequence;
        boolean mNativeResult;
    }

    static class GeolocationPermissionsData {
        String mOrigin;
        boolean mAllow;
        boolean mRemember;
    }

        static final String[] HandlerDebugString = {
            "REVEAL_SELECTION", // 96
            "REQUEST_LABEL", // 97
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
            "FAKE_CLICK", // = 121;
            "DELETE_SELECTION", // = 122;
            "LISTBOX_CHOICES", // = 123;
            "SINGLE_LISTBOX_CHOICE", // = 124;
            "MESSAGE_RELAY", // = 125;
            "SET_BACKGROUND_COLOR", // = 126;
            "SET_MOVE_FOCUS", // = 127
            "SAVE_DOCUMENT_STATE", // = 128;
            "129", // = 129;
            "WEBKIT_DRAW", // = 130;
            "131", // = 131;
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
            "VALID_NODE_BOUNDS", // = 146
            "SAVE_WEBARCHIVE", // = 147
            "WEBKIT_DRAW_LAYERS", // = 148;
            "REMOVE_JS_INTERFACE", // = 149;
        };

    /**
     * @hide
     */
    public class EventHub {
        // Message Ids
        static final int REVEAL_SELECTION = 96;
        static final int REQUEST_LABEL = 97;
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
        static final int FAKE_CLICK = 121;
        static final int DELETE_SELECTION = 122;
        static final int LISTBOX_CHOICES = 123;
        static final int SINGLE_LISTBOX_CHOICE = 124;
        public static final int MESSAGE_RELAY = 125;
        static final int SET_BACKGROUND_COLOR = 126;
        static final int SET_MOVE_FOCUS = 127;
        static final int SAVE_DOCUMENT_STATE = 128;

        static final int WEBKIT_DRAW = 130;
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
        static final int VALID_NODE_BOUNDS = 146;

        // Load and save web archives
        static final int SAVE_WEBARCHIVE = 147;

        // Update layers
        static final int WEBKIT_DRAW_LAYERS = 148;

        static final int REMOVE_JS_INTERFACE = 149;

        // Network-based messaging
        static final int CLEAR_SSL_PREF_TABLE = 150;

        // Test harness messages
        static final int REQUEST_EXT_REPRESENTATION = 160;
        static final int REQUEST_DOC_AS_TEXT = 161;

        // debugging
        static final int DUMP_DOMTREE = 170;
        static final int DUMP_RENDERTREE = 171;
        static final int DUMP_NAVTREE = 172;
        static final int DUMP_V8COUNTERS = 173;

        static final int SET_JS_FLAGS = 174;
        static final int CONTENT_INVALIDATE_ALL = 175;
        // Geolocation
        static final int GEOLOCATION_PERMISSIONS_PROVIDE = 180;

        static final int POPULATE_VISITED_LINKS = 181;

        static final int HIDE_FULLSCREEN = 182;

        static final int SET_NETWORK_TYPE = 183;

        // navigator.isApplicationInstalled()
        static final int ADD_PACKAGE_NAMES = 184;
        static final int ADD_PACKAGE_NAME = 185;
        static final int REMOVE_PACKAGE_NAME = 186;

        static final int GET_TOUCH_HIGHLIGHT_RECTS = 187;

        // accessibility support
        static final int MODIFY_SELECTION = 190;

        static final int USE_MOCK_DEVICE_ORIENTATION = 191;

        static final int AUTOFILL_FORM = 192;

        static final int PROXY_CHANGED = 193;

        static final int EXECUTE_JS = 194;

        static final int PLUGIN_SURFACE_READY = 195;

        static final int NOTIFY_ANIMATION_STARTED = 196;

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
        private boolean mDestroying;

        private int mTid;
        private int mSavedPriority;

        /**
         * Prevent other classes from creating an EventHub.
         */
        private EventHub() {}

        private static final int FIRST_PACKAGE_MSG_ID = REVEAL_SELECTION;
        private static final int LAST_PACKAGE_MSG_ID = VALID_NODE_BOUNDS;

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
                        Log.v(LOGTAG, (msg.what < FIRST_PACKAGE_MSG_ID
                                || msg.what > LAST_PACKAGE_MSG_ID
                                ? Integer.toString(msg.what)
                                : HandlerDebugString[msg.what
                                        - FIRST_PACKAGE_MSG_ID])
                                + " arg1=" + msg.arg1 + " arg2=" + msg.arg2
                                + " obj=" + msg.obj);
                    }
                    if (mWebView == null || mNativeClass == 0) {
                        if (DebugFlags.WEB_VIEW_CORE) {
                            Log.w(LOGTAG, "Rejecting message " + msg.what
                                    + " because we are destroyed");
                        }
                        return;
                    }
                    if (mDestroying == true
                            && msg.what != EventHub.RESUME_TIMERS
                            && msg.what != EventHub.PAUSE_TIMERS
                            && msg.what != EventHub.DESTROY) {
                        if (DebugFlags.WEB_VIEW_CORE) {
                            Log.v(LOGTAG, "Rejecting message " + msg.what
                                    + " because we are being destroyed");
                        }
                        return;
                    }
                    switch (msg.what) {
                        case WEBKIT_DRAW:
                            webkitDraw();
                            break;

                        case WEBKIT_DRAW_LAYERS:
                            webkitDrawLayers();
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

                        case REVEAL_SELECTION:
                            nativeRevealSelection();
                            break;

                        case REQUEST_LABEL:
                            if (mWebView != null) {
                                int nodePointer = msg.arg2;
                                String label = nativeRequestLabel(msg.arg1,
                                        nodePointer);
                                if (label != null && label.length() > 0) {
                                    Message.obtain(mWebView.mPrivateHandler,
                                            WebView.RETURN_LABEL, nodePointer,
                                            0, label).sendToTarget();
                                }
                            }
                            break;

                        case UPDATE_FRAME_CACHE_IF_LOADING:
                            nativeUpdateFrameCacheIfLoading();
                            break;

                        case SCROLL_TEXT_INPUT:
                            float xPercent;
                            if (msg.obj == null) {
                                xPercent = 0f;
                            } else {
                                xPercent = ((Float) msg.obj).floatValue();
                            }
                            nativeScrollFocusedTextInput(xPercent, msg.arg2);
                            break;

                        case LOAD_URL: {
                            CookieManager.getInstance().waitForCookieOperationsToComplete();
                            GetUrlData param = (GetUrlData) msg.obj;
                            loadUrl(param.mUrl, param.mExtraHeaders);
                            break;
                        }

                        case POST_URL: {
                            CookieManager.getInstance().waitForCookieOperationsToComplete();
                            PostUrlData param = (PostUrlData) msg.obj;
                            mBrowserFrame.postUrl(param.mUrl, param.mPostData);
                            break;
                        }
                        case LOAD_DATA:
                            CookieManager.getInstance().waitForCookieOperationsToComplete();
                            BaseUrlData loadParams = (BaseUrlData) msg.obj;
                            String baseUrl = loadParams.mBaseUrl;
                            if (baseUrl != null) {
                                int i = baseUrl.indexOf(':');
                                if (i > 0) {
                                    // In 1.0, WebView.loadDataWithBaseURL() could access local
                                    // asset files using 'file' scheme URLs as long as the data is
                                    // valid. Later versions of WebKit have tightened the
                                    // restriction around when pages can access such local URLs.
                                    // To maintain compatibility with 1.0, we register the scheme of
                                    // the baseUrl to be considered local, as long as it is not
                                    // http(s)/ftp(s)/about/javascript.
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
                                    loadParams.mHistoryUrl);
                            nativeContentInvalidateAll();
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

                        case FAKE_CLICK:
                            nativeClick(msg.arg1, msg.arg2, true);
                            break;

                        case CLICK:
                            nativeClick(msg.arg1, msg.arg2, false);
                            break;

                        case VIEW_SIZE_CHANGED: {
                            viewSizeChanged((WebView.ViewSizeData) msg.obj);
                            break;
                        }
                        case SET_SCROLL_OFFSET:
                            // note: these are in document coordinates
                            // (inv-zoom)
                            Point pt = (Point) msg.obj;
                            nativeSetScrollOffset(msg.arg1, msg.arg2 == 1,
                                    pt.x, pt.y);
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
                            if (!JniUtil.useChromiumHttpStack()) {
                                WebViewWorker.getHandler().sendEmptyMessage(
                                        WebViewWorker.MSG_PAUSE_CACHE_TRANSACTION);
                            } else {
                                nativeCloseIdleConnections();
                            }
                            break;

                        case RESUME_TIMERS:
                            Process.setThreadPriority(mTid, mSavedPriority);
                            resumeTimers();
                            if (!JniUtil.useChromiumHttpStack()) {
                                WebViewWorker.getHandler().sendEmptyMessage(
                                        WebViewWorker.MSG_RESUME_CACHE_TRANSACTION);
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

                        case SET_NETWORK_STATE:
                            if (BrowserFrame.sJavaBridge == null) {
                                throw new IllegalStateException("No WebView " +
                                        "has been created in this process!");
                            }
                            BrowserFrame.sJavaBridge
                                    .setNetworkOnLine(msg.arg1 == 1);
                            break;

                        case SET_NETWORK_TYPE:
                            if (BrowserFrame.sJavaBridge == null) {
                                throw new IllegalStateException("No WebView " +
                                        "has been created in this process!");
                            }
                            Map<String, String> map = (Map<String, String>) msg.obj;
                            BrowserFrame.sJavaBridge
                                    .setNetworkType(map.get("type"), map.get("subtype"));
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
                            if (JniUtil.useChromiumHttpStack()) {
                                // FIXME: This will not work for connections currently in use, as
                                // they cache the certificate responses. See http://b/5324235.
                                SslCertLookupTable.getInstance().clear();
                                nativeCloseIdleConnections();
                            } else {
                                Network.getInstance(mContext).clearUserSslPrefTable();
                            }
                            break;

                        case TOUCH_UP:
                            TouchUpData touchUpData = (TouchUpData) msg.obj;
                            if (touchUpData.mNativeLayer != 0) {
                                nativeScrollLayer(touchUpData.mNativeLayer,
                                        touchUpData.mNativeLayerRect);
                            }
                            nativeTouchUp(touchUpData.mMoveGeneration,
                                    touchUpData.mFrame, touchUpData.mNode,
                                    touchUpData.mX, touchUpData.mY);
                            break;

                        case TOUCH_EVENT: {
                            TouchEventData ted = (TouchEventData) msg.obj;
                            final int count = ted.mPoints.length;
                            int[] xArray = new int[count];
                            int[] yArray = new int[count];
                            for (int c = 0; c < count; c++) {
                                xArray[c] = ted.mPoints[c].x;
                                yArray[c] = ted.mPoints[c].y;
                            }
                            if (ted.mNativeLayer != 0) {
                                nativeScrollLayer(ted.mNativeLayer,
                                        ted.mNativeLayerRect);
                            }
                            ted.mNativeResult = nativeHandleTouchEvent(ted.mAction, ted.mIds,
                                    xArray, yArray, count, ted.mActionIndex, ted.mMetaState);
                            Message.obtain(
                                    mWebView.mPrivateHandler,
                                    WebView.PREVENT_TOUCH_ID,
                                    ted.mAction,
                                    ted.mNativeResult ? 1 : 0,
                                    ted).sendToTarget();
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

                        case REMOVE_JS_INTERFACE:
                            jsData = (JSInterfaceData) msg.obj;
                            mBrowserFrame.removeJavascriptInterface(
                                    jsData.mInterfaceName);
                            break;

                        case REQUEST_EXT_REPRESENTATION:
                            mBrowserFrame.externalRepresentation(
                                    (Message) msg.obj);
                            break;

                        case REQUEST_DOC_AS_TEXT:
                            mBrowserFrame.documentAsText((Message) msg.obj);
                            break;

                        case SET_MOVE_FOCUS:
                            CursorData focusData = (CursorData) msg.obj;
                            nativeMoveFocus(focusData.mFrame, focusData.mNode);
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
                            if (msg.arg1 == 1) {
                                nativeStopPaintingCaret();
                            }
                            break;

                        case REQUEST_CURSOR_HREF: {
                            Message hrefMsg = (Message) msg.obj;
                            hrefMsg.getData().putString("url",
                                    nativeRetrieveHref(msg.arg1, msg.arg2));
                            hrefMsg.getData().putString("title",
                                    nativeRetrieveAnchorText(msg.arg1, msg.arg2));
                            hrefMsg.getData().putString("src",
                                    nativeRetrieveImageSource(msg.arg1, msg.arg2));
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

                        case MODIFY_SELECTION:
                            String modifiedSelectionString = nativeModifySelection(msg.arg1,
                                    msg.arg2);
                            mWebView.mPrivateHandler.obtainMessage(WebView.SELECTION_STRING_CHANGED,
                                    modifiedSelectionString).sendToTarget();
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

                        case DUMP_DOMTREE:
                            nativeDumpDomTree(msg.arg1 == 1);
                            break;

                        case DUMP_RENDERTREE:
                            nativeDumpRenderTree(msg.arg1 == 1);
                            break;

                        case DUMP_NAVTREE:
                            nativeDumpNavTree();
                            break;

                        case DUMP_V8COUNTERS:
                            nativeDumpV8Counters();
                            break;

                        case SET_JS_FLAGS:
                            nativeSetJsFlags((String)msg.obj);
                            break;

                        case CONTENT_INVALIDATE_ALL:
                            nativeContentInvalidateAll();
                            break;

                        case SAVE_WEBARCHIVE:
                            WebView.SaveWebArchiveMessage saveMessage =
                                (WebView.SaveWebArchiveMessage)msg.obj;
                            saveMessage.mResultFile =
                                saveWebArchive(saveMessage.mBasename, saveMessage.mAutoname);
                            mWebView.mPrivateHandler.obtainMessage(
                                WebView.SAVE_WEBARCHIVE_FINISHED, saveMessage).sendToTarget();
                            break;

                        case GEOLOCATION_PERMISSIONS_PROVIDE:
                            GeolocationPermissionsData data =
                                    (GeolocationPermissionsData) msg.obj;
                            nativeGeolocationPermissionsProvide(data.mOrigin,
                                    data.mAllow, data.mRemember);
                            break;

                        case SPLIT_PICTURE_SET:
                            nativeSplitContent(msg.arg1);
                            mWebView.mPrivateHandler.obtainMessage(
                                    WebView.REPLACE_BASE_CONTENT, msg.arg1, 0);
                            mSplitPictureIsScheduled = false;
                            break;

                        case CLEAR_CONTENT:
                            // Clear the view so that onDraw() will draw nothing
                            // but white background
                            // (See public method WebView.clearView)
                            nativeClearContent();
                            break;

                        case MESSAGE_RELAY:
                            ((Message) msg.obj).sendToTarget();
                            break;

                        case POPULATE_VISITED_LINKS:
                            nativeProvideVisitedHistory((String[])msg.obj);
                            break;

                        case VALID_NODE_BOUNDS: {
                            MotionUpData motionUpData = (MotionUpData) msg.obj;
                            if (!nativeValidNodeAndBounds(
                                    motionUpData.mFrame, motionUpData.mNode,
                                    motionUpData.mBounds)) {
                                nativeUpdateFrameCache();
                            }
                            Message message = mWebView.mPrivateHandler
                                    .obtainMessage(WebView.DO_MOTION_UP,
                                    motionUpData.mX, motionUpData.mY);
                            mWebView.mPrivateHandler.sendMessageAtFrontOfQueue(
                                    message);
                            break;
                        }

                        case HIDE_FULLSCREEN:
                            nativeFullScreenPluginHidden(msg.arg1);
                            break;

                        case PLUGIN_SURFACE_READY:
                            nativePluginSurfaceReady();
                            break;

                        case NOTIFY_ANIMATION_STARTED:
                            nativeNotifyAnimationStarted(mNativeClass);
                            break;

                        case ADD_PACKAGE_NAMES:
                            if (BrowserFrame.sJavaBridge == null) {
                                throw new IllegalStateException("No WebView " +
                                        "has been created in this process!");
                            }
                            BrowserFrame.sJavaBridge.addPackageNames(
                                    (Set<String>) msg.obj);
                            break;

                        case GET_TOUCH_HIGHLIGHT_RECTS:
                            TouchHighlightData d = (TouchHighlightData) msg.obj;
                            if (d.mNativeLayer != 0) {
                                nativeScrollLayer(d.mNativeLayer,
                                        d.mNativeLayerRect);
                            }
                            ArrayList<Rect> rects = nativeGetTouchHighlightRects
                                    (d.mX, d.mY, d.mSlop);
                            mWebView.mPrivateHandler.obtainMessage(
                                    WebView.SET_TOUCH_HIGHLIGHT_RECTS, rects)
                                    .sendToTarget();
                            break;

                        case USE_MOCK_DEVICE_ORIENTATION:
                            useMockDeviceOrientation();
                            break;

                        case AUTOFILL_FORM:
                            nativeAutoFillForm(msg.arg1);
                            mWebView.mPrivateHandler.obtainMessage(WebView.AUTOFILL_COMPLETE, null)
                                    .sendToTarget();
                            break;

                        case EXECUTE_JS:
                            if (msg.obj instanceof String) {
                                if (DebugFlags.WEB_VIEW_CORE) {
                                    Log.d(LOGTAG, "Executing JS : " + msg.obj);
                                }
                                mBrowserFrame.stringByEvaluatingJavaScriptFromString((String) msg.obj);
                            }
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
                Throwable throwable = new Throwable(
                        "EventHub.removeMessages(int what = " + what + ") is not supported " +
                        "before the WebViewCore is set up.");
                Log.w(LOGTAG, Log.getStackTraceString(throwable));
            } else {
                mHandler.removeMessages(what);
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

    /**
     * @hide
     */
    public void sendMessage(Message msg) {
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

    void sendMessageAtFrontOfQueue(int what, Object obj) {
        mEventHub.sendMessageAtFrontOfQueue(Message.obtain(
                null, what, obj));
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
     * Sends a DESTROY message to WebCore.
     * Called from UI thread.
     */
    void destroy() {
        synchronized (mEventHub) {
            // Do not call removeMessages as then we risk removing PAUSE_TIMERS
            // or RESUME_TIMERS messages, which we must still handle as they
            // are per process. DESTROY will instead trigger a white list in
            // mEventHub, skipping any remaining messages in the queue
            mEventHub.mDestroying = true;
            mEventHub.sendMessage(
                    Message.obtain(null, EventHub.DESTROY));
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

    private void loadUrl(String url, Map<String, String> extraHeaders) {
        if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, " CORE loadUrl " + url);
        mBrowserFrame.loadUrl(url, extraHeaders);
    }

    private String saveWebArchive(String filename, boolean autoname) {
        if (DebugFlags.WEB_VIEW_CORE) {
            Log.v(LOGTAG, " CORE saveWebArchive " + filename + " " + autoname);
        }
        return mBrowserFrame.saveWebArchive(filename, autoname);
    }

    private void key(KeyEvent evt, boolean isDown) {
        if (DebugFlags.WEB_VIEW_CORE) {
            Log.v(LOGTAG, "CORE key at " + System.currentTimeMillis() + ", "
                    + evt);
        }
        int keyCode = evt.getKeyCode();
        int unicodeChar = evt.getUnicodeChar();

        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && evt.getCharacters() != null
                && evt.getCharacters().length() > 0) {
            // we should only receive individual complex characters
            unicodeChar = evt.getCharacters().codePointAt(0);
        }

        if (!nativeKey(keyCode, unicodeChar, evt.getRepeatCount(), evt.isShiftPressed(),
                evt.isAltPressed(), evt.isSymPressed(),
                isDown) && keyCode != KeyEvent.KEYCODE_ENTER) {
            if (keyCode >= KeyEvent.KEYCODE_DPAD_UP
                    && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (DebugFlags.WEB_VIEW_CORE) {
                    Log.v(LOGTAG, "key: arrow unused by page: " + keyCode);
                }
                if (mWebView != null && evt.isDown()) {
                    Message.obtain(mWebView.mPrivateHandler,
                            WebView.UNHANDLED_NAV_KEY, keyCode,
                            0).sendToTarget();
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
    private void viewSizeChanged(WebView.ViewSizeData data) {
        int w = data.mWidth;
        int h = data.mHeight;
        int textwrapWidth = data.mTextWrapWidth;
        float scale = data.mScale;
        if (DebugFlags.WEB_VIEW_CORE) {
            Log.v(LOGTAG, "viewSizeChanged w=" + w + "; h=" + h
                    + "; textwrapWidth=" + textwrapWidth + "; scale=" + scale);
        }
        if (w == 0) {
            Log.w(LOGTAG, "skip viewSizeChanged as w is 0");
            return;
        }
        int width = calculateWindowWidth(w);
        int height = h;
        if (width != w) {
            float heightWidthRatio = data.mHeightWidthRatio;
            float ratio = (heightWidthRatio > 0) ? heightWidthRatio : (float) h / w;
            height = Math.round(ratio * width);
        }
        nativeSetSize(width, height, textwrapWidth, scale, w,
                data.mActualViewHeight > 0 ? data.mActualViewHeight : h,
                data.mAnchorX, data.mAnchorY, data.mIgnoreHeight);
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

    // Calculate width to be used in webkit window.
    private int calculateWindowWidth(int viewWidth) {
        int width = viewWidth;
        if (mSettings.getUseWideViewPort()) {
            if (mViewportWidth == -1) {
                // Fixed viewport width.
                width = WebView.DEFAULT_VIEWPORT_WIDTH;
            } else if (mViewportWidth > 0) {
                // Use website specified or desired fixed viewport width.
                width = mViewportWidth;
            } else {
                // For mobile web site.
                width = Math.round(mWebView.getViewWidth() / mWebView.getDefaultZoomScale());
            }
        }
        return width;
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

    // called from UI thread
    void splitContent(int content) {
        if (!mSplitPictureIsScheduled) {
            mSplitPictureIsScheduled = true;
            sendMessage(EventHub.SPLIT_PICTURE_SET, content, 0);
        }
    }

    // Used to avoid posting more than one draw message.
    private boolean mDrawIsScheduled;
    private boolean mDrawLayersIsScheduled;

    // Used to avoid posting more than one split picture message.
    private boolean mSplitPictureIsScheduled;

    // Used to suspend drawing.
    private boolean mDrawIsPaused;

    // mInitialViewState is set by didFirstLayout() and then reset in the
    // next webkitDraw after passing the state to the UI thread.
    private ViewState mInitialViewState = null;
    private boolean mFirstLayoutForNonStandardLoad;

    static class ViewState {
        float mMinScale;
        float mMaxScale;
        float mViewScale;
        float mTextWrapScale;
        float mDefaultScale;
        int mScrollX;
        int mScrollY;
        boolean mMobileSite;
        boolean mIsRestored;
        boolean mShouldStartScrolledRight;
    }

    static class DrawData {
        DrawData() {
            mBaseLayer = 0;
            mInvalRegion = new Region();
            mContentSize = new Point();
        }
        int mBaseLayer;
        Region mInvalRegion;
        // view size that was used by webkit during the most recent layout
        Point mViewSize;
        Point mContentSize;
        int mMinPrefWidth;
        // only non-null if it is for the first picture set after the first layout
        ViewState mViewState;
        boolean mFirstLayoutForNonStandardLoad;
        boolean mFocusSizeChanged;
    }

    DrawData mLastDrawData = null;

    // Only update the layers' content, not the base surface
    // PictureSet.
    private void webkitDrawLayers() {
        mDrawLayersIsScheduled = false;
        if (mDrawIsScheduled || mLastDrawData == null) {
            removeMessages(EventHub.WEBKIT_DRAW);
            webkitDraw();
            return;
        }
        // Directly update the layers we last passed to the UI side
        if (nativeUpdateLayers(mNativeClass, mLastDrawData.mBaseLayer)) {
            // If anything more complex than position has been touched, let's do a full draw
            webkitDraw();
        }
        mWebView.mPrivateHandler.removeMessages(WebView.INVAL_RECT_MSG_ID);
        mWebView.mPrivateHandler.sendMessageAtFrontOfQueue(mWebView.mPrivateHandler
                .obtainMessage(WebView.INVAL_RECT_MSG_ID));
    }

    private void webkitDraw() {
        mDrawIsScheduled = false;
        DrawData draw = new DrawData();
        if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw start");
        draw.mBaseLayer = nativeRecordContent(draw.mInvalRegion, draw.mContentSize);
        if (draw.mBaseLayer == 0) {
            if (mWebView != null && !mWebView.isPaused()) {
                if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw abort, resending draw message");
                mEventHub.sendMessage(Message.obtain(null, EventHub.WEBKIT_DRAW));
            } else {
                if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw abort, webview paused");
            }
            return;
        }
        mLastDrawData = draw;
        webkitDraw(draw);
    }

    private void webkitDraw(DrawData draw) {
        if (mWebView != null) {
            draw.mFocusSizeChanged = nativeFocusBoundsChanged();
            draw.mViewSize = new Point(mCurrentViewWidth, mCurrentViewHeight);
            if (mSettings.getUseWideViewPort()) {
                draw.mMinPrefWidth = Math.max(
                        mViewportWidth == -1 ? WebView.DEFAULT_VIEWPORT_WIDTH
                                : (mViewportWidth == 0 ? mCurrentViewWidth
                                        : mViewportWidth),
                        nativeGetContentMinPrefWidth());
            }
            if (mInitialViewState != null) {
                draw.mViewState = mInitialViewState;
                mInitialViewState = null;
            }
            if (mFirstLayoutForNonStandardLoad) {
                draw.mFirstLayoutForNonStandardLoad = true;
                mFirstLayoutForNonStandardLoad = false;
            }
            if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw NEW_PICTURE_MSG_ID");
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.NEW_PICTURE_MSG_ID, draw).sendToTarget();
        }
    }

    static void reducePriority() {
        // remove the pending REDUCE_PRIORITY and RESUME_PRIORITY messages
        sWebCoreHandler.removeMessages(WebCoreThread.REDUCE_PRIORITY);
        sWebCoreHandler.removeMessages(WebCoreThread.RESUME_PRIORITY);
        sWebCoreHandler.sendMessageAtFrontOfQueue(sWebCoreHandler
                .obtainMessage(WebCoreThread.REDUCE_PRIORITY));
    }

    static void resumePriority() {
        // remove the pending REDUCE_PRIORITY and RESUME_PRIORITY messages
        sWebCoreHandler.removeMessages(WebCoreThread.REDUCE_PRIORITY);
        sWebCoreHandler.removeMessages(WebCoreThread.RESUME_PRIORITY);
        sWebCoreHandler.sendMessageAtFrontOfQueue(sWebCoreHandler
                .obtainMessage(WebCoreThread.RESUME_PRIORITY));
    }

    static void sendStaticMessage(int messageType, Object argument) {
        if (sWebCoreHandler == null)
            return;

        sWebCoreHandler.sendMessage(sWebCoreHandler.obtainMessage(messageType, argument));
    }

    static void pauseUpdatePicture(WebViewCore core) {
        // Note: there is one possible failure mode. If pauseUpdatePicture() is
        // called from UI thread while WEBKIT_DRAW is just pulled out of the
        // queue in WebCore thread to be executed. Then update won't be blocked.
        if (core != null) {
            if (!core.getSettings().enableSmoothTransition()) return;

            synchronized (core) {
                if (core.mNativeClass == 0) {
                    Log.w(LOGTAG, "Cannot pauseUpdatePicture, core destroyed or not initialized!");
                    return;
                }
                core.nativeSetIsPaused(true);
                core.mDrawIsPaused = true;
            }
        }

    }

    static void resumeUpdatePicture(WebViewCore core) {
        if (core != null) {
            // if mDrawIsPaused is true, ignore the setting, continue to resume
            if (!core.mDrawIsPaused)
                return;

            synchronized (core) {
                if (core.mNativeClass == 0) {
                    Log.w(LOGTAG, "Cannot resumeUpdatePicture, core destroyed!");
                    return;
                }
                core.nativeSetIsPaused(false);
                core.mDrawIsPaused = false;
                // always redraw on resume to reenable gif animations
                core.mDrawIsScheduled = false;
            }
        }
    }

    static boolean isUpdatePicturePaused(WebViewCore core) {
        return core != null ? core.mDrawIsPaused : false;
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
        synchronized (this) {
            if (mWebView == null || mBrowserFrame == null) {
                // We were destroyed
                return;
            }
            // don't update the Picture until we have an initial width and finish
            // the first layout
            if (mCurrentViewWidth == 0 || !mBrowserFrame.firstLayoutDone()) {
                return;
            }
            // only fire an event if this is our first request
            if (mDrawIsScheduled) return;
            mDrawIsScheduled = true;
            mEventHub.sendMessage(Message.obtain(null, EventHub.WEBKIT_DRAW));
        }
    }

    // called from JNI
    void layersDraw() {
        synchronized (this) {
            if (mDrawLayersIsScheduled) return;
            mDrawLayersIsScheduled = true;
            mEventHub.sendMessage(Message.obtain(null, EventHub.WEBKIT_DRAW_LAYERS));
        }
    }

    // called by JNI
    private void contentScrollTo(int x, int y, boolean animate,
            boolean onlyIfImeIsShowing) {
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
                    WebView.SCROLL_TO_MSG_ID, animate ? 1 : 0,
                    onlyIfImeIsShowing ? 1 : 0, new Point(x, y));
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
        if (!JniUtil.useChromiumHttpStack()) {
            // as CacheManager can behave based on database transaction, we need to
            // call tick() to trigger endTransaction
            WebViewWorker.getHandler().removeMessages(
                    WebViewWorker.MSG_CACHE_TRANSACTION_TICKER);
            WebViewWorker.getHandler().sendEmptyMessage(
                    WebViewWorker.MSG_CACHE_TRANSACTION_TICKER);
        }
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

    private static boolean mRepaintScheduled = false;

    /*
     * Called by the WebView thread
     */
    /* package */ void signalRepaintDone() {
        mRepaintScheduled = false;
    }

    // Gets the WebView corresponding to this WebViewCore. Note that the
    // WebView object must only be used on the UI thread.
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

        boolean updateViewState = standardLoad || mIsRestored;
        setupViewport(updateViewState);
        // if updateRestoreState is true, ViewManager.postReadyToDrawAll() will
        // be called after the WebView updates its state. If updateRestoreState
        // is false, start to draw now as it is ready.
        if (!updateViewState) {
            mWebView.mViewManager.postReadyToDrawAll();
        }

        // remove the touch highlight when moving to a new page
        if (WebView.USE_WEBKIT_RINGS || getSettings().supportTouchOnly()) {
            mWebView.mPrivateHandler.sendEmptyMessage(
                    WebView.SET_TOUCH_HIGHLIGHT_RECTS);
        }

        // reset the scroll position, the restored offset and scales
        mRestoredX = mRestoredY = 0;
        mIsRestored = false;
        mRestoredScale = mRestoredTextWrapScale = 0;
    }

    // called by JNI
    private void updateViewport() {
        // Update viewport asap to make sure we get correct one.
        setupViewport(true);
    }

    private void setupViewport(boolean updateViewState) {
        if (mWebView == null || mSettings == null) {
            // We've been destroyed or are being destroyed, return early
            return;
        }
        // set the viewport settings from WebKit
        setViewportSettingsFromNative();

        // clamp initial scale
        if (mViewportInitialScale > 0) {
            if (mViewportMinimumScale > 0) {
                mViewportInitialScale = Math.max(mViewportInitialScale,
                        mViewportMinimumScale);
            }
            if (mViewportMaximumScale > 0) {
                mViewportInitialScale = Math.min(mViewportInitialScale,
                        mViewportMaximumScale);
            }
        }

        if (mSettings.forceUserScalable()) {
            mViewportUserScalable = true;
            if (mViewportInitialScale > 0) {
                if (mViewportMinimumScale > 0) {
                    mViewportMinimumScale = Math.min(mViewportMinimumScale,
                            mViewportInitialScale / 2);
                }
                if (mViewportMaximumScale > 0) {
                    mViewportMaximumScale = Math.max(mViewportMaximumScale,
                            mViewportInitialScale * 2);
                }
            } else {
                if (mViewportMinimumScale > 0) {
                    mViewportMinimumScale = Math.min(mViewportMinimumScale, 50);
                }
                if (mViewportMaximumScale > 0) {
                    mViewportMaximumScale = Math.max(mViewportMaximumScale, 200);
                }
            }
        }

        // adjust the default scale to match the densityDpi
        float adjust = 1.0f;
        if (mViewportDensityDpi == -1) {
            // convert default zoom scale to a integer (percentage) to avoid any
            // issues with floating point comparisons
            if (mWebView != null && (int)(mWebView.getDefaultZoomScale() * 100) != 100) {
                adjust = mWebView.getDefaultZoomScale();
            }
        } else if (mViewportDensityDpi > 0) {
            adjust = (float) mContext.getResources().getDisplayMetrics().densityDpi
                    / mViewportDensityDpi;
        }
        if (adjust != mWebView.getDefaultZoomScale()) {
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.UPDATE_ZOOM_DENSITY, adjust).sendToTarget();
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
        if (mViewportWidth != 0 && !updateViewState) {
            // For non standard load, since updateViewState will be false.
            mFirstLayoutForNonStandardLoad = true;
            ViewState viewState = new ViewState();
            viewState.mMinScale = mViewportMinimumScale / 100.0f;
            viewState.mMaxScale = mViewportMaximumScale / 100.0f;
            viewState.mDefaultScale = adjust;
            // as mViewportWidth is not 0, it is not mobile site.
            viewState.mMobileSite = false;
            // for non-mobile site, we don't need minPrefWidth, set it as 0
            viewState.mScrollX = 0;
            viewState.mShouldStartScrolledRight = false;
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.UPDATE_ZOOM_RANGE, viewState).sendToTarget();
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
                if (DebugFlags.WEB_VIEW_CORE) {
                    Log.v(LOGTAG, "Can't get the viewWidth yet");
                }
            }
        } else {
            webViewWidth = Math.round(viewportWidth * mCurrentViewScale);
        }
        mInitialViewState = new ViewState();
        mInitialViewState.mMinScale = mViewportMinimumScale / 100.0f;
        mInitialViewState.mMaxScale = mViewportMaximumScale / 100.0f;
        mInitialViewState.mDefaultScale = adjust;
        mInitialViewState.mScrollX = mRestoredX;
        mInitialViewState.mScrollY = mRestoredY;
        mInitialViewState.mShouldStartScrolledRight = (mRestoredX == 0)
                && (mRestoredY == 0)
                && (mBrowserFrame != null)
                && mBrowserFrame.getShouldStartScrolledRight();

        mInitialViewState.mMobileSite = (0 == mViewportWidth);
        if (mIsRestored) {
            mInitialViewState.mIsRestored = true;
            mInitialViewState.mViewScale = mRestoredScale;
            if (mRestoredTextWrapScale > 0) {
                mInitialViewState.mTextWrapScale = mRestoredTextWrapScale;
            } else {
                mInitialViewState.mTextWrapScale = mInitialViewState.mViewScale;
            }
        } else {
            if (mViewportInitialScale > 0) {
                mInitialViewState.mViewScale = mInitialViewState.mTextWrapScale =
                        mViewportInitialScale / 100.0f;
            } else if (mViewportWidth > 0 && mViewportWidth < webViewWidth &&
                !getSettings().getUseFixedViewport()) {
                mInitialViewState.mViewScale = mInitialViewState.mTextWrapScale =
                        (float) webViewWidth / mViewportWidth;
            } else {
                mInitialViewState.mTextWrapScale = adjust;
                if (mSettings.getUseWideViewPort()) {
                    // 0 will trigger WebView to turn on zoom overview mode
                    mInitialViewState.mViewScale = 0;
                } else {
                    mInitialViewState.mViewScale = adjust;
                }
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
        } else {
            if (viewportWidth == 0) {
                // Trick to ensure VIEW_SIZE_CHANGED will be sent from WebView
                // to WebViewCore
                mWebView.mLastWidthSent = 0;
            } else {
                WebView.ViewSizeData data = new WebView.ViewSizeData();
                // mViewScale as 0 means it is in zoom overview mode. So we don't
                // know the exact scale. If mRestoredScale is non-zero, use it;
                // otherwise just use mTextWrapScale as the initial scale.
                float tentativeScale = mInitialViewState.mViewScale;
                if (tentativeScale == 0) {
                    // The following tries to figure out more correct view scale
                    // and text wrap scale to be sent to webkit, by using some
                    // knowledge from web settings and zoom manager.

                    // Calculated window width will be used to guess the scale
                    // in zoom overview mode.
                    tentativeScale = mInitialViewState.mTextWrapScale;
                    int tentativeViewWidth = Math.round(webViewWidth / tentativeScale);
                    int windowWidth = calculateWindowWidth(tentativeViewWidth);
                    // In viewport setup time, since no content width is known, we assume
                    // the windowWidth will be the content width, to get a more likely
                    // zoom overview scale.
                    data.mScale = (float) webViewWidth / windowWidth;
                    if (!mSettings.getLoadWithOverviewMode()) {
                        // If user choose non-overview mode.
                        data.mScale = Math.max(data.mScale, tentativeScale);
                    }
                    if (mSettings.isNarrowColumnLayout()) {
                        // In case of automatic text reflow in fixed view port mode.
                        mInitialViewState.mTextWrapScale =
                                mWebView.getReadingLevelScale();
                    }
                } else {
                    // Scale is given such as when page is restored, use it.
                    data.mScale = tentativeScale;
                }
                if (DebugFlags.WEB_VIEW_CORE) {
                    Log.v(LOGTAG, "setupViewport"
                             + " mRestoredScale=" + mRestoredScale
                             + " mViewScale=" + mInitialViewState.mViewScale
                             + " mTextWrapScale=" + mInitialViewState.mTextWrapScale
                             + " data.mScale= " + data.mScale
                             );
                }
                data.mWidth = Math.round(webViewWidth / data.mScale);
                // We may get a call here when mCurrentViewHeight == 0 if webcore completes the
                // first layout before we sync our webview dimensions to it. In that case, we
                // request the real height of the webview. This is not a perfect solution as we
                // are calling a WebView method from the WebCore thread. But this is preferable
                // to syncing an incorrect height.
                data.mHeight = mCurrentViewHeight == 0 ?
                        Math.round(mWebView.getViewHeight() / data.mScale)
                        : Math.round((float) mCurrentViewHeight * data.mWidth / viewportWidth);
                data.mTextWrapWidth = Math.round(webViewWidth
                        / mInitialViewState.mTextWrapScale);
                data.mIgnoreHeight = false;
                data.mAnchorX = data.mAnchorY = 0;
                // send VIEW_SIZE_CHANGED to the front of the queue so that we
                // can avoid pushing the wrong picture to the WebView side.
                mEventHub.removeMessages(EventHub.VIEW_SIZE_CHANGED);
                // Let webkit know the scale and inner width/height immediately
                // in viewport setup time to avoid wrong information.
                viewSizeChanged(data);
            }
        }
    }

    // called by JNI
    private void restoreScale(float scale, float textWrapScale) {
        if (mBrowserFrame.firstLayoutDone() == false) {
            mIsRestored = true;
            mRestoredScale = scale;
            if (mSettings.getUseWideViewPort()) {
                mRestoredTextWrapScale = textWrapScale;
            }
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

    // called by JNI
    private void sendFindAgain() {
        if (mWebView == null) return;
        Message.obtain(mWebView.mPrivateHandler,
                WebView.FIND_AGAIN).sendToTarget();
    }

    private native void nativeUpdateFrameCacheIfLoading();
    private native void nativeRevealSelection();
    private native String nativeRequestLabel(int framePtr, int nodePtr);
    /**
     * Scroll the focused textfield to (xPercent, y) in document space
     */
    private native void nativeScrollFocusedTextInput(float xPercent, int y);

    // these must be in document space (i.e. not scaled/zoomed).
    private native void nativeSetScrollOffset(int gen, boolean sendScrollEvent, int dx, int dy);

    private native void nativeSetGlobalBounds(int x, int y, int w, int h);

    // called by JNI
    private void requestListBox(String[] array, int[] enabledArray,
            int[] selectedArray) {
        if (mWebView != null) {
            mWebView.requestListBox(array, enabledArray, selectedArray);
        }
    }

    // called by JNI
    private void requestListBox(String[] array, int[] enabledArray,
            int selection) {
        if (mWebView != null) {
            mWebView.requestListBox(array, enabledArray, selection);
        }

    }

    // called by JNI
    private void requestKeyboardWithSelection(int pointer, int selStart,
            int selEnd, int textGeneration) {
        if (mWebView != null) {
            Message.obtain(mWebView.mPrivateHandler,
                    WebView.REQUEST_KEYBOARD_WITH_SELECTION_MSG_ID, pointer,
                    textGeneration, new TextSelectionData(selStart, selEnd))
                    .sendToTarget();
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

    private void setWebTextViewAutoFillable(int queryId, String preview) {
        if (mWebView != null) {
            Message.obtain(mWebView.mPrivateHandler, WebView.SET_AUTOFILLABLE,
                    new AutoFillData(queryId, preview))
                    .sendToTarget();
        }
    }

    Context getContext() {
        return mContext;
    }

    // called by JNI
    private void keepScreenOn(boolean screenOn) {
        if (mWebView != null) {
            Message message = mWebView.mPrivateHandler.obtainMessage(WebView.SCREEN_ON);
            message.arg1 = screenOn ? 1 : 0;
            message.sendToTarget();
        }
    }

    // called by JNI
    private Class<?> getPluginClass(String libName, String clsName) {

        if (mWebView == null) {
            return null;
        }

        PluginManager pluginManager = PluginManager.getInstance(null);

        String pkgName = pluginManager.getPluginsAPKName(libName);
        if (pkgName == null) {
            Log.w(LOGTAG, "Unable to resolve " + libName + " to a plugin APK");
            return null;
        }

        try {
            return pluginManager.getPluginClass(pkgName, clsName);
        } catch (NameNotFoundException e) {
            Log.e(LOGTAG, "Unable to find plugin classloader for the apk (" + pkgName + ")");
        } catch (ClassNotFoundException e) {
            Log.e(LOGTAG, "Unable to find plugin class (" + clsName +
                    ") in the apk (" + pkgName + ")");
        }

        return null;
    }

    // called by JNI. PluginWidget function to launch a full-screen view using a
    // View object provided by the plugin class.
    private void showFullScreenPlugin(ViewManager.ChildView childView, int orientation, int npp) {
        if (mWebView == null) {
            return;
        }

        Message message = mWebView.mPrivateHandler.obtainMessage(WebView.SHOW_FULLSCREEN);
        message.obj = childView.mView;
        message.arg1 = orientation;
        message.arg2 = npp;
        message.sendToTarget();
    }

    // called by JNI
    private void hideFullScreenPlugin() {
        if (mWebView == null) {
            return;
        }
        mWebView.mPrivateHandler.obtainMessage(WebView.HIDE_FULLSCREEN)
                .sendToTarget();
    }

    private ViewManager.ChildView createSurface(View pluginView) {
        if (mWebView == null) {
            return null;
        }

        if (pluginView == null) {
            Log.e(LOGTAG, "Attempted to add an empty plugin view to the view hierarchy");
            return null;
        }

        // ensures the view system knows the view can redraw itself
        pluginView.setWillNotDraw(false);

        if(pluginView instanceof SurfaceView)
            ((SurfaceView)pluginView).setZOrderOnTop(true);

        ViewManager.ChildView view = mWebView.mViewManager.createView();
        view.mView = pluginView;
        return view;
    }

    // called by JNI.  PluginWidget functions for creating an embedded View for
    // the surface drawing model.
    private ViewManager.ChildView addSurface(View pluginView, int x, int y,
                                             int width, int height) {
        ViewManager.ChildView view = createSurface(pluginView);
        view.attachView(x, y, width, height);
        return view;
    }

    private void updateSurface(ViewManager.ChildView childView, int x, int y,
            int width, int height) {
        childView.attachView(x, y, width, height);
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

    // called by JNI
    private void centerFitRect(int x, int y, int width, int height) {
        if (mWebView == null) {
            return;
        }
        mWebView.mPrivateHandler.obtainMessage(WebView.CENTER_FIT_RECT,
                new Rect(x, y, x + width, y + height)).sendToTarget();
    }

    // called by JNI
    private void setScrollbarModes(int hMode, int vMode) {
        if (mWebView == null) {
            return;
        }
        mWebView.mPrivateHandler.obtainMessage(WebView.SET_SCROLLBAR_MODES,
                hMode, vMode).sendToTarget();
    }

    // called by JNI
    @SuppressWarnings("unused")
    private void selectAt(int x, int y) {
        if (mWebView != null) {
            mWebView.mPrivateHandler.obtainMessage(WebView.SELECT_AT, x, y).sendToTarget();
        }
    }

    private void useMockDeviceOrientation() {
        mDeviceMotionAndOrientationManager.useMock();
    }

    public void setMockDeviceOrientation(boolean canProvideAlpha, double alpha,
            boolean canProvideBeta, double beta, boolean canProvideGamma, double gamma) {
        mDeviceMotionAndOrientationManager.setMockOrientation(canProvideAlpha, alpha,
                canProvideBeta, beta, canProvideGamma, gamma);
    }

    protected DeviceMotionService getDeviceMotionService() {
        if (mDeviceMotionService == null) {
            mDeviceMotionService =
                    new DeviceMotionService(mDeviceMotionAndOrientationManager, mContext);
        }
        return mDeviceMotionService;
    }

    protected DeviceOrientationService getDeviceOrientationService() {
        if (mDeviceOrientationService == null) {
            mDeviceOrientationService =
                    new DeviceOrientationService(mDeviceMotionAndOrientationManager, mContext);
        }
        return mDeviceOrientationService;
    }

    private native void nativeSetIsPaused(boolean isPaused);
    private native void nativePause();
    private native void nativeResume();
    private native void nativeFreeMemory();
    private native void nativeFullScreenPluginHidden(int npp);
    private native void nativePluginSurfaceReady();
    private native boolean nativeValidNodeAndBounds(int frame, int node,
            Rect bounds);

    private native ArrayList<Rect> nativeGetTouchHighlightRects(int x, int y,
            int slop);

    private native void nativeAutoFillForm(int queryId);
    private native void nativeScrollLayer(int layer, Rect rect);
}
