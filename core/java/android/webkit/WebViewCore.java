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
import android.media.MediaFile;
import android.net.ProxyProperties;
import android.net.Uri;
import android.net.http.CertificateChainValidator;
import android.os.Bundle;
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
import android.webkit.WebViewClassic.FocusNodeHref;
import android.webkit.WebViewInputDispatcher.WebKitCallbacks;

import com.android.internal.os.SomeArgs;

import junit.framework.Assert;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

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

    // The WebViewClassic that corresponds to this WebViewCore.
    private WebViewClassic mWebViewClassic;
    // Proxy for handling callbacks from native code
    private final CallbackProxy mCallbackProxy;
    // Settings object for maintaining all settings
    private final WebSettingsClassic mSettings;
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

    private MockGeolocation mMockGeolocation = new MockGeolocation(this);

    private DeviceMotionAndOrientationManager mDeviceMotionAndOrientationManager =
            new DeviceMotionAndOrientationManager(this);
    private DeviceMotionService mDeviceMotionService;
    private DeviceOrientationService mDeviceOrientationService;

    private int mLowMemoryUsageThresholdMb;
    private int mHighMemoryUsageThresholdMb;
    private int mHighUsageDeltaMb;

    private int mChromeCanFocusDirection;
    private int mTextSelectionChangeReason = TextSelectionData.REASON_UNKNOWN;

    // Used to determine if we should monitor the WebCore thread for responsiveness.
    // If it "hangs", for example a web page enters a while(true) loop, we will
    // prompt the user with a dialog allowing them to terminate the process.
    private static boolean sShouldMonitorWebCoreThread;

    // The thread name used to identify the WebCore thread and for use in
    // debugging other classes that require operation within the WebCore thread.
    /* package */ static final String THREAD_NAME = "WebViewCoreThread";

    public WebViewCore(Context context, WebViewClassic w, CallbackProxy proxy,
            Map<String, Object> javascriptInterfaces) {
        // No need to assign this in the WebCore thread.
        mCallbackProxy = proxy;
        mWebViewClassic = w;
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

                if (sShouldMonitorWebCoreThread) {
                    // Start the singleton watchdog which will monitor the WebCore thread
                    // to verify it's still processing messages. Note that this is the only
                    // time we need to check the value as all the other public methods on
                    // the WebCoreThreadWatchdog are no-ops if start() is not called.
                    WebCoreThreadWatchdog.start(sWebCoreHandler);
                }
            }
            // Make sure the Watchdog is aware of this new WebView.
            WebCoreThreadWatchdog.registerWebView(w);
        }
        // Create an EventHub to handle messages before and after the thread is
        // ready.
        mEventHub = new EventHub();
        // Create a WebSettings object for maintaining all settings
        mSettings = new WebSettingsClassic(mContext, mWebViewClassic);
        // The WebIconDatabase needs to be initialized within the UI thread so
        // just request the instance here.
        WebIconDatabase.getInstance();
        // Create the WebStorageClassic singleton and the UI handler
        WebStorageClassic.getInstance().createUIHandler();
        // Create the UI handler for GeolocationPermissions
        GeolocationPermissionsClassic.getInstance().createUIHandler();

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
        WebIconDatabaseClassic.getInstance().createHandler();
        // Create the handler for WebStorageClassic
        WebStorageClassic.getInstance().createHandler();
        // Create the handler for GeolocationPermissions.
        GeolocationPermissionsClassic.getInstance().createHandler();
        // The transferMessages call will transfer all pending messages to the
        // WebCore thread handler.
        mEventHub.transferMessages();

        // Send a message back to WebView to tell it that we have set up the
        // WebCore thread.
        if (mWebViewClassic != null) {
            Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.WEBCORE_INITIALIZED_MSG_ID,
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

    public WebKitCallbacks getInputDispatcherCallbacks() {
        return mEventHub;
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

    public WebSettingsClassic getSettings() {
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
     * Called by JNI when the focus node changed.
     */
    private void focusNodeChanged(int nodePointer, WebKitHitTest hitTest) {
        if (mWebViewClassic == null) return;
        mWebViewClassic.mPrivateHandler.obtainMessage(WebViewClassic.FOCUS_NODE_CHANGED,
                nodePointer, 0, hitTest).sendToTarget();
    }

    /**
     * Called by JNI to advance focus to the next view.
     */
    private void chromeTakeFocus(int webkitDirection) {
        if (mWebViewClassic == null) return;
        Message m = mWebViewClassic.mPrivateHandler.obtainMessage(
                WebViewClassic.TAKE_FOCUS);
        m.arg1 = mapDirection(webkitDirection);
        m.sendToTarget();
    }

    /**
     * Called by JNI to see if we can take focus in the given direction.
     */
    private boolean chromeCanTakeFocus(int webkitDirection) {
        int direction = mapDirection(webkitDirection);
        return direction == mChromeCanFocusDirection && direction != 0;
    }

    /**
     * Maps a Webkit focus direction to a framework one
     */
    private int mapDirection(int webkitDirection) {
        /*
         * This is WebKit's FocusDirection enum (from FocusDirection.h)
        enum FocusDirection {
            FocusDirectionNone = 0,
            FocusDirectionForward,
            FocusDirectionBackward,
            FocusDirectionUp,
            FocusDirectionDown,
            FocusDirectionLeft,
            FocusDirectionRight
        };
         */
        switch (webkitDirection) {
        case 1:
            return View.FOCUS_FORWARD;
        case 2:
            return View.FOCUS_BACKWARD;
        case 3:
            return View.FOCUS_UP;
        case 4:
            return View.FOCUS_DOWN;
        case 5:
            return View.FOCUS_LEFT;
        case 6:
            return View.FOCUS_RIGHT;
        }
        return 0;
    }

    /**
     * Called by JNI.  Open a file chooser to upload a file.
     * @param acceptType The value of the 'accept' attribute of the
     *         input tag associated with this file picker.
     * @param capture The value of the 'capture' attribute of the
     *         input tag associated with this file picker.
     * @return String version of the URI.
     */
    private String openFileChooser(String acceptType, String capture) {
        Uri uri = mCallbackProxy.openFileChooser(acceptType, capture);
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
     * Notify the embedding application that the origin has exceeded it's database quota.
     * @param url The URL that caused the overflow.
     * @param databaseIdentifier The identifier of the database.
     * @param quota The current quota for the origin.
     * @param estimatedDatabaseSize The estimated size of the database.
     */
    protected void exceededDatabaseQuota(String url,
                                         String databaseIdentifier,
                                         long quota,
                                         long estimatedDatabaseSize) {
        // Inform the callback proxy of the quota overflow. Send an object
        // that encapsulates a call to the nativeSetDatabaseQuota method to
        // awaken the sleeping webcore thread when a decision from the
        // client to allow or deny quota is available.
        mCallbackProxy.onExceededDatabaseQuota(url, databaseIdentifier,
                quota, estimatedDatabaseSize, getUsedQuota(),
                new WebStorage.QuotaUpdater() {
                        @Override
                        public void updateQuota(long newQuota) {
                            nativeSetNewStorageLimit(mNativeClass, newQuota);
                        }
                });
    }

    /**
     * Notify the embedding application that the appcache has reached or exceeded its maximum
     * allowed storage size.
     *
     * @param requiredStorage is the amount of storage, in bytes, that would be
     * needed in order for the last appcache operation to succeed.
     * @param maxSize maximum allowed Application Cache database size, in bytes.
     */
    protected void reachedMaxAppCacheSize(long requiredStorage, long maxSize) {
        mCallbackProxy.onReachedMaxAppCacheSize(requiredStorage, maxSize,
                new WebStorage.QuotaUpdater() {
                    @Override
                    public void updateQuota(long newQuota) {
                        nativeSetNewStorageLimit(mNativeClass, newQuota);
                    }
                });
    }

    protected void populateVisitedLinks() {
        ValueCallback callback = new ValueCallback<String[]>() {
            @Override
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
            @Override
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
     * Notify the webview that we want to exit the video fullscreen.
     * This is called through JNI by webcore.
     */
    protected void exitFullscreenVideo() {
        if (mWebViewClassic == null) return;
        Message message = Message.obtain(mWebViewClassic.mPrivateHandler,
                       WebViewClassic.EXIT_FULLSCREEN_VIDEO);
        message.sendToTarget();
    }

    /**
     * Clear the picture set. To be called only on the WebCore thread.
     */
    /* package */ void clearContent() {
        nativeClearContent(mNativeClass);
    }

    //-------------------------------------------------------------------------
    // JNI methods
    //-------------------------------------------------------------------------

    static native String nativeFindAddress(String addr, boolean caseInsensitive);

    /**
     * Empty the picture set.
     */
    private native void nativeClearContent(int nativeClass);

    private native void nativeContentInvalidateAll(int nativeClass);

    /**
     * Redraw a portion of the picture set. The Point wh returns the
     * width and height of the overall picture.
     */
    private native int nativeRecordContent(int nativeClass, Point wh);

    /**
     * Notify webkit that animations have begun (on the hardware accelerated content)
     */
    private native void nativeNotifyAnimationStarted(int nativeClass);

    private native boolean nativeKey(int nativeClass, int keyCode,
            int unichar, int repeatCount, boolean isShift, boolean isAlt,
            boolean isSym, boolean isDown);

    private native void nativeSendListBoxChoices(int nativeClass,
            boolean[] choices, int size);

    private native void nativeSendListBoxChoice(int nativeClass, int choice);

    private native void nativeCloseIdleConnections(int nativeClass);

    /*  Tell webkit what its width and height are, for the purposes
        of layout/line-breaking. These coordinates are in document space,
        which is the same as View coords unless we have zoomed the document
        (see nativeSetZoom).
        textWrapWidth is used by layout to wrap column around. If viewport uses
        fixed size, textWrapWidth can be different from width with zooming.
        should this be called nativeSetViewPortSize?
    */
    private native void nativeSetSize(int nativeClass, int width, int height,
            int textWrapWidth, float scale, int screenWidth, int screenHeight,
            int anchorX, int anchorY, boolean ignoreHeight);

    private native int nativeGetContentMinPrefWidth(int nativeClass);

    // Start: functions that deal with text editing
    private native void nativeReplaceTextfieldText(
            int nativeClass, int oldStart, int oldEnd, String replace,
            int newStart, int newEnd, int textGeneration);

    private native void passToJs(int nativeClass,
            int gen, String currentText, int keyCode, int keyValue,
            boolean down, boolean cap, boolean fn, boolean sym);

    private native void nativeSetFocusControllerActive(int nativeClass,
            boolean active);

    private native void nativeSaveDocumentState(int nativeClass);

    private native void nativeMoveMouse(int nativeClass, int x, int y);

    private native String nativeRetrieveHref(int nativeClass, int x, int y);
    private native String nativeRetrieveAnchorText(int nativeClass,
            int x, int y);
    private native String nativeRetrieveImageSource(int nativeClass,
            int x, int y);
    private native boolean nativeMouseClick(int nativeClass);

    private native int nativeHandleTouchEvent(int nativeClass, int action,
            int[] idArray, int[] xArray, int[] yArray, int count,
            int actionIndex, int metaState);

    private native void nativeSetBackgroundColor(int nativeClass, int color);

    private native void nativeDumpDomTree(int nativeClass, boolean useFile);

    private native void nativeDumpRenderTree(int nativeClass, boolean useFile);

    private native void nativeSetJsFlags(int nativeClass, String flags);

    /**
     *  Delete text from start to end in the focused textfield. If there is no
     *  focus, or if start == end, silently fail.  If start and end are out of
     *  order, swap them.
     * @param  nativeClass Pointer to the C++ WebViewCore object mNativeClass
     * @param  start   Beginning of selection to delete.
     * @param  end     End of selection to delete.
     * @param  textGeneration Text generation number when delete was pressed.
     */
    private native void nativeDeleteSelection(int nativeClass, int start,
            int end, int textGeneration);

    /**
     *  Set the selection to (start, end) in the focused textfield. If start and
     *  end are out of order, swap them.
     * @param  nativeClass Pointer to the C++ WebViewCore object mNativeClass
     * @param  start   Beginning of selection.
     * @param  end     End of selection.
     */
    private native void nativeSetSelection(int nativeClass, int start, int end);

    // Register a scheme to be treated as local scheme so that it can access
    // local asset files for resources
    private native void nativeRegisterURLSchemeAsLocal(int nativeClass,
            String scheme);

    /*
     * Inform webcore that the user has decided whether to allow or deny new
     * quota for the current origin or more space for the app cache, and that
     * the main thread should wake up now.
     * @param limit Is the new quota for an origin or new app cache max size.
     */
    private native void nativeSetNewStorageLimit(int nativeClass, long limit);

    /**
     * Provide WebCore with a Geolocation permission state for the specified
     * origin.
     * @param nativeClass Pointer to the C++ WebViewCore object mNativeClass
     * @param origin The origin for which Geolocation permissions are provided.
     * @param allow Whether Geolocation permissions are allowed.
     * @param remember Whether this decision should be remembered beyond the
     *     life of the current page.
     */
    private native void nativeGeolocationPermissionsProvide(int nativeClass,
            String origin, boolean allow, boolean remember);

    /**
     * Provide WebCore with the previously visted links from the history database
     * @param nativeClass TODO
     */
    private native void nativeProvideVisitedHistory(int nativeClass,
            String[] history);

    /**
     * Modifies the current selection.
     *
     * Note: Accessibility support.
     * @param nativeClass Pointer to the C++ WebViewCore object mNativeClass
     * @param direction The direction in which to alter the selection.
     * @param granularity The granularity of the selection modification.
     *
     * @return The selection string.
     */
    private native String nativeModifySelection(int nativeClass, int direction,
            int granularity);

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

        @Override
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

                            case EventHub.HEARTBEAT:
                                // Ping back the watchdog to let it know we're still processing
                                // messages.
                                Message m = (Message)msg.obj;
                                m.sendToTarget();
                                break;
                            case EventHub.TRUST_STORAGE_UPDATED:
                                // post a task to network thread for updating trust manager
                                nativeCertTrustChanged();
                                CertificateChainValidator.handleTrustStorageUpdate();
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

    static class JSInterfaceData {
        Object mObject;
        String mInterfaceName;
        boolean mRequireAnnotation;
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
        static final int REASON_UNKNOWN = 0;
        static final int REASON_ACCESSIBILITY_INJECTOR = 1;
        static final int REASON_SELECT_WORD = 2;
        public TextSelectionData(int start, int end, int selectTextPtr) {
            mStart = start;
            mEnd = end;
            mSelectTextPtr = selectTextPtr;
        }
        int mStart;
        int mEnd;
        int mSelectTextPtr;
        int mSelectionReason = TextSelectionData.REASON_UNKNOWN;
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

    static class WebKitHitTest {
        String mLinkUrl;
        String mIntentUrl;
        String mAnchorText;
        String mImageUrl;
        String mAltDisplayString;
        String mTitle;
        Rect[] mTouchRects;
        boolean mEditable;
        int mTapHighlightColor = WebViewClassic.HIGHLIGHT_COLOR;
        Rect[] mEnclosingParentRects;
        boolean mHasFocus;

        // These are the input values that produced this hit test
        int mHitTestX;
        int mHitTestY;
        int mHitTestSlop;
        boolean mHitTestMovedMouse;
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

    static class TextFieldInitData {
        public int mFieldPointer;
        public String mText;
        public int mType;
        public boolean mIsSpellCheckEnabled;
        public boolean mIsTextFieldNext;
        public boolean mIsTextFieldPrev;
        public boolean mIsAutoCompleteEnabled;
        public String mName;
        public String mLabel;
        public int mMaxLength;
        public Rect mContentBounds;
        public int mNodeLayerId;
        public Rect mClientRect;
    }

    // mAction of TouchEventData can be MotionEvent.getAction() which uses the
    // last two bytes or one of the following values
    static final int ACTION_LONGPRESS = 0x100;
    static final int ACTION_DOUBLETAP = 0x200;

    private static final int TOUCH_FLAG_HIT_HANDLER = 0x1;
    private static final int TOUCH_FLAG_PREVENT_DEFAULT = 0x2;

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
            "", // 97
            "", // = 98
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
            "", // = 117;
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
            "", // = 133;
            "CLEAR_CONTENT", // = 134;
            "", // = 135;
            "", // = 136;
            "REQUEST_CURSOR_HREF", // = 137;
            "ADD_JS_INTERFACE", // = 138;
            "LOAD_DATA", // = 139;
            "", // = 140;
            "", // = 141;
            "SET_ACTIVE", // = 142;
            "ON_PAUSE",     // = 143
            "ON_RESUME",    // = 144
            "FREE_MEMORY",  // = 145
            "VALID_NODE_BOUNDS", // = 146
            "SAVE_WEBARCHIVE", // = 147
            "WEBKIT_DRAW_LAYERS", // = 148;
            "REMOVE_JS_INTERFACE", // = 149;
        };

    static class FindAllRequest {
        public FindAllRequest(String text) {
            mSearchText = text;
            mMatchCount = -1;
            mMatchIndex = -1;
        }
        public final String mSearchText;
        public int mMatchCount;
        public int mMatchIndex;
    }

    static class SaveViewStateRequest {
        SaveViewStateRequest(OutputStream s, ValueCallback<Boolean> cb) {
            mStream = s;
            mCallback = cb;
        }
        public OutputStream mStream;
        public ValueCallback<Boolean> mCallback;
    }

    /**
     * @hide
     */
    public class EventHub implements WebViewInputDispatcher.WebKitCallbacks {
        // Message Ids
        static final int REVEAL_SELECTION = 96;
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
        static final int SET_NETWORK_STATE = 119;
        static final int DOC_HAS_IMAGES = 120;
        static final int DELETE_SELECTION = 122;
        static final int LISTBOX_CHOICES = 123;
        static final int SINGLE_LISTBOX_CHOICE = 124;
        public static final int MESSAGE_RELAY = 125;
        static final int SET_BACKGROUND_COLOR = 126;
        static final int SAVE_DOCUMENT_STATE = 128;
        static final int DELETE_SURROUNDING_TEXT = 129;


        static final int WEBKIT_DRAW = 130;
        static final int POST_URL = 132;
        static final int CLEAR_CONTENT = 134;

        // UI nav messages
        static final int SET_MOVE_MOUSE = 135;
        static final int REQUEST_CURSOR_HREF = 137;
        static final int ADD_JS_INTERFACE = 138;
        static final int LOAD_DATA = 139;

        // Used to tell the focus controller not to draw the blinking cursor,
        // based on whether the WebView has focus and whether the WebView's
        // cursor matches the webpage's focus.
        static final int SET_ACTIVE = 142;

        // lifecycle activities for just this DOM (unlike pauseTimers, which
        // is global)
        static final int ON_PAUSE = 143;
        static final int ON_RESUME = 144;
        static final int FREE_MEMORY = 145;

        // Load and save web archives
        static final int SAVE_WEBARCHIVE = 147;

        static final int REMOVE_JS_INTERFACE = 149;

        // Network-based messaging
        static final int CLEAR_SSL_PREF_TABLE = 150;

        // Test harness messages
        static final int REQUEST_EXT_REPRESENTATION = 160;
        static final int REQUEST_DOC_AS_TEXT = 161;

        // debugging
        static final int DUMP_DOMTREE = 170;
        static final int DUMP_RENDERTREE = 171;

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

        // accessibility support
        static final int MODIFY_SELECTION = 190;

        static final int SET_USE_MOCK_DEVICE_ORIENTATION = 191;

        static final int AUTOFILL_FORM = 192;

        static final int PROXY_CHANGED = 193;

        static final int EXECUTE_JS = 194;

        static final int PLUGIN_SURFACE_READY = 195;

        static final int NOTIFY_ANIMATION_STARTED = 196;

        static final int HEARTBEAT = 197;

        static final int SCROLL_LAYER = 198;

        // private message ids
        private static final int DESTROY =     200;

        // for cut & paste
        static final int COPY_TEXT = 210;
        static final int DELETE_TEXT = 211;
        static final int INSERT_TEXT = 212;
        static final int SELECT_TEXT = 213;
        static final int SELECT_WORD_AT = 214;
        static final int SELECT_ALL = 215;

        // for updating state on trust storage change
        static final int TRUST_STORAGE_UPDATED = 220;

        // find-on-page controls
        static final int FIND_ALL = 221;
        static final int FIND_NEXT = 222;

        // key was pressed (down and up)
        static final int KEY_PRESS = 223;
        static final int SET_INITIAL_FOCUS = 224;

        static final int SAVE_VIEW_STATE = 225;
        static final int SET_USE_MOCK_GEOLOCATION = 226;

        // Private handler for WebCore messages.
        private Handler mHandler;
        // Message queue for containing messages before the WebCore thread is
        // ready.
        private LinkedList<Message> mMessages = new LinkedList<Message>();
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
        private static final int LAST_PACKAGE_MSG_ID = REMOVE_JS_INTERFACE;

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
                    switch (msg.what) {
                    case PAUSE_TIMERS:
                        mSavedPriority = Process.getThreadPriority(mTid);
                        Process.setThreadPriority(mTid,
                            Process.THREAD_PRIORITY_BACKGROUND);
                        pauseTimers();
                        if (mNativeClass != 0) {
                            nativeCloseIdleConnections(mNativeClass);
                        }
                        return;

                    case RESUME_TIMERS:
                        Process.setThreadPriority(mTid, mSavedPriority);
                        resumeTimers();
                        return;
                    }

                    if (mWebViewClassic == null || mNativeClass == 0) {
                        if (DebugFlags.WEB_VIEW_CORE) {
                            Log.w(LOGTAG, "Rejecting message " + msg.what
                                    + " because we are destroyed");
                        }
                        return;
                    }
                    if (mDestroying == true
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

                        case DESTROY:
                            // Time to take down the world. Cancel all pending
                            // loads and destroy the native view and frame.
                            synchronized (WebViewCore.this) {
                                mCallbackProxy.shutdown();
                                // Wake up the WebCore thread just in case it is waiting for a
                                // JavaScript dialog.
                                synchronized (mCallbackProxy) {
                                    mCallbackProxy.notify();
                                }
                                mBrowserFrame.destroy();
                                mBrowserFrame = null;
                                mSettings.onDestroyed();
                                mNativeClass = 0;
                                WebCoreThreadWatchdog.unregisterWebView(mWebViewClassic);
                                mWebViewClassic = null;
                            }
                            break;

                        case REVEAL_SELECTION:
                            nativeRevealSelection(mNativeClass);
                            break;

                        case SCROLL_TEXT_INPUT:
                            float xPercent;
                            if (msg.obj == null) {
                                xPercent = 0f;
                            } else {
                                xPercent = ((Float) msg.obj).floatValue();
                            }
                            nativeScrollFocusedTextInput(mNativeClass, xPercent,
                                    msg.arg2);
                            break;

                        case LOAD_URL: {
                            CookieManagerClassic.getInstance().waitForCookieOperationsToComplete();
                            GetUrlData param = (GetUrlData) msg.obj;
                            loadUrl(param.mUrl, param.mExtraHeaders);
                            break;
                        }

                        case POST_URL: {
                            CookieManagerClassic.getInstance().waitForCookieOperationsToComplete();
                            PostUrlData param = (PostUrlData) msg.obj;
                            mBrowserFrame.postUrl(param.mUrl, param.mPostData);
                            break;
                        }
                        case LOAD_DATA:
                            CookieManagerClassic.getInstance().waitForCookieOperationsToComplete();
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
                                        nativeRegisterURLSchemeAsLocal(mNativeClass,
                                                scheme);
                                    }
                                }
                            }
                            mBrowserFrame.loadData(baseUrl,
                                    loadParams.mData,
                                    loadParams.mMimeType,
                                    loadParams.mEncoding,
                                    loadParams.mHistoryUrl);
                            nativeContentInvalidateAll(mNativeClass);
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
                            key((KeyEvent) msg.obj, msg.arg1, true);
                            break;

                        case KEY_UP:
                            key((KeyEvent) msg.obj, msg.arg1, false);
                            break;

                        case KEY_PRESS:
                            keyPress(msg.arg1);
                            break;

                        case VIEW_SIZE_CHANGED: {
                            viewSizeChanged((WebViewClassic.ViewSizeData) msg.obj);
                            break;
                        }
                        case SET_SCROLL_OFFSET:
                            // note: these are in document coordinates
                            // (inv-zoom)
                            Point pt = (Point) msg.obj;
                            nativeSetScrollOffset(mNativeClass,
                                    msg.arg1 == 1, pt.x, pt.y);
                            break;

                        case SET_GLOBAL_BOUNDS:
                            Rect r = (Rect) msg.obj;
                            nativeSetGlobalBounds(mNativeClass, r.left, r.top,
                                r.width(), r.height());
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


                        case ON_PAUSE:
                            nativePause(mNativeClass);
                            break;

                        case ON_RESUME:
                            nativeResume(mNativeClass);
                            break;

                        case FREE_MEMORY:
                            clearCache(false);
                            nativeFreeMemory(mNativeClass);
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
                            nativeReplaceTextfieldText(mNativeClass, msg.arg1,
                                    msg.arg2, rep.mReplace, rep.mNewStart,
                                    rep.mNewEnd, rep.mTextGeneration);
                            break;

                        case PASS_TO_JS: {
                            JSKeyData jsData = (JSKeyData) msg.obj;
                            KeyEvent evt = jsData.mEvent;
                            int keyCode = evt.getKeyCode();
                            int keyValue = evt.getUnicodeChar();
                            int generation = msg.arg1;
                            passToJs(mNativeClass,
                                    generation,
                                    jsData.mCurrentText,
                                    keyCode,
                                    keyValue,
                                    evt.isDown(), evt.isShiftPressed(),
                                    evt.isAltPressed(), evt.isSymPressed());
                            break;
                        }

                        case SAVE_DOCUMENT_STATE: {
                            nativeSaveDocumentState(mNativeClass);
                            break;
                        }

                        case CLEAR_SSL_PREF_TABLE:
                            // FIXME: This will not work for connections currently in use, as
                            // they cache the certificate responses. See http://b/5324235.
                            SslCertLookupTable.getInstance().clear();
                            nativeCloseIdleConnections(mNativeClass);
                            break;

                        case SET_ACTIVE:
                            nativeSetFocusControllerActive(mNativeClass, msg.arg1 == 1);
                            break;

                        case ADD_JS_INTERFACE:
                            JSInterfaceData jsData = (JSInterfaceData) msg.obj;
                            mBrowserFrame.addJavascriptInterface(jsData.mObject,
                                    jsData.mInterfaceName, jsData.mRequireAnnotation);
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

                        case SET_MOVE_MOUSE:
                            nativeMoveMouse(mNativeClass, msg.arg1, msg.arg2);
                            break;

                        case REQUEST_CURSOR_HREF: {
                            WebKitHitTest hit = performHitTest(msg.arg1, msg.arg2, 1, false);
                            Message hrefMsg = (Message) msg.obj;
                            Bundle data = hrefMsg.getData();
                            data.putString(FocusNodeHref.URL,hit.mLinkUrl);
                            data.putString(FocusNodeHref.TITLE, hit.mAnchorText);
                            data.putString(FocusNodeHref.SRC, hit.mImageUrl);
                            hrefMsg.sendToTarget();
                            break;
                        }

                        case DOC_HAS_IMAGES:
                            Message imageResult = (Message) msg.obj;
                            imageResult.arg1 =
                                    mBrowserFrame.documentHasImages() ? 1 : 0;
                            imageResult.sendToTarget();
                            break;

                        case DELETE_SELECTION:
                            TextSelectionData deleteSelectionData
                                    = (TextSelectionData) msg.obj;
                            nativeDeleteSelection(mNativeClass,
                                    deleteSelectionData.mStart, deleteSelectionData.mEnd, msg.arg1);
                            break;

                        case SET_SELECTION:
                            nativeSetSelection(mNativeClass, msg.arg1, msg.arg2);
                            break;

                        case MODIFY_SELECTION:
                            mTextSelectionChangeReason
                                    = TextSelectionData.REASON_ACCESSIBILITY_INJECTOR;
                            final SomeArgs args = (SomeArgs) msg.obj;
                            final String modifiedSelectionString = nativeModifySelection(
                                    mNativeClass, args.argi1, args.argi2);
                            // If accessibility is on, the main thread may be
                            // waiting for a response. Send on webcore thread.
                            mWebViewClassic.handleSelectionChangedWebCoreThread(
                                    modifiedSelectionString, args.argi3);
                            args.recycle();
                            mTextSelectionChangeReason
                                    = TextSelectionData.REASON_UNKNOWN;
                            break;

                        case LISTBOX_CHOICES:
                            SparseBooleanArray choices = (SparseBooleanArray)
                                    msg.obj;
                            int choicesSize = msg.arg1;
                            boolean[] choicesArray = new boolean[choicesSize];
                            for (int c = 0; c < choicesSize; c++) {
                                choicesArray[c] = choices.get(c);
                            }
                            nativeSendListBoxChoices(mNativeClass,
                                    choicesArray, choicesSize);
                            break;

                        case SINGLE_LISTBOX_CHOICE:
                            nativeSendListBoxChoice(mNativeClass, msg.arg1);
                            break;

                        case SET_BACKGROUND_COLOR:
                            nativeSetBackgroundColor(mNativeClass, msg.arg1);
                            break;

                        case DUMP_DOMTREE:
                            nativeDumpDomTree(mNativeClass, msg.arg1 == 1);
                            break;

                        case DUMP_RENDERTREE:
                            nativeDumpRenderTree(mNativeClass, msg.arg1 == 1);
                            break;

                        case SET_JS_FLAGS:
                            nativeSetJsFlags(mNativeClass, (String)msg.obj);
                            break;

                        case CONTENT_INVALIDATE_ALL:
                            nativeContentInvalidateAll(mNativeClass);
                            break;

                        case SAVE_WEBARCHIVE:
                            WebViewClassic.SaveWebArchiveMessage saveMessage =
                                (WebViewClassic.SaveWebArchiveMessage)msg.obj;
                            saveMessage.mResultFile =
                                saveWebArchive(saveMessage.mBasename, saveMessage.mAutoname);
                            mWebViewClassic.mPrivateHandler.obtainMessage(
                                WebViewClassic.SAVE_WEBARCHIVE_FINISHED, saveMessage).sendToTarget();
                            break;

                        case GEOLOCATION_PERMISSIONS_PROVIDE:
                            GeolocationPermissionsData data =
                                    (GeolocationPermissionsData) msg.obj;
                            nativeGeolocationPermissionsProvide(mNativeClass,
                                    data.mOrigin, data.mAllow, data.mRemember);
                            break;

                        case CLEAR_CONTENT:
                            // Clear the view so that onDraw() will draw nothing
                            // but white background
                            // (See public method WebView.clearView)
                            clearContent();
                            break;

                        case MESSAGE_RELAY:
                            ((Message) msg.obj).sendToTarget();
                            break;

                        case POPULATE_VISITED_LINKS:
                            nativeProvideVisitedHistory(mNativeClass, (String[])msg.obj);
                            break;

                        case HIDE_FULLSCREEN:
                            nativeFullScreenPluginHidden(mNativeClass, msg.arg1);
                            break;

                        case PLUGIN_SURFACE_READY:
                            nativePluginSurfaceReady(mNativeClass);
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

                        case SET_USE_MOCK_GEOLOCATION:
                            setUseMockGeolocation();
                            break;

                        case SET_USE_MOCK_DEVICE_ORIENTATION:
                            setUseMockDeviceOrientation();
                            break;

                        case AUTOFILL_FORM:
                            nativeAutoFillForm(mNativeClass, msg.arg1);
                            mWebViewClassic.mPrivateHandler.obtainMessage(
                                    WebViewClassic.AUTOFILL_COMPLETE, null).sendToTarget();
                            break;

                        case EXECUTE_JS:
                            if (msg.obj instanceof String) {
                                if (DebugFlags.WEB_VIEW_CORE) {
                                    Log.d(LOGTAG, "Executing JS : " + msg.obj);
                                }
                                mBrowserFrame.stringByEvaluatingJavaScriptFromString(
                                        (String) msg.obj);
                            }
                            break;
                        case SCROLL_LAYER:
                            int nativeLayer = msg.arg1;
                            Rect rect = (Rect) msg.obj;
                            nativeScrollLayer(mNativeClass, nativeLayer,
                                    rect);
                            break;

                        case DELETE_TEXT: {
                            int[] handles = (int[]) msg.obj;
                            nativeDeleteText(mNativeClass, handles[0],
                                    handles[1], handles[2], handles[3]);
                            break;
                        }
                        case COPY_TEXT: {
                            int[] handles = (int[]) msg.obj;
                            String copiedText = nativeGetText(mNativeClass,
                                    handles[0], handles[1], handles[2],
                                    handles[3]);
                            if (copiedText != null) {
                                mWebViewClassic.mPrivateHandler.obtainMessage(
                                        WebViewClassic.COPY_TO_CLIPBOARD, copiedText)
                                        .sendToTarget();
                            }
                            break;
                        }
                        case INSERT_TEXT:
                            nativeInsertText(mNativeClass, (String) msg.obj);
                            break;
                        case SELECT_TEXT: {
                            int handleId = (Integer) msg.obj;
                            nativeSelectText(mNativeClass, handleId,
                                    msg.arg1, msg.arg2);
                            break;
                        }
                        case SELECT_WORD_AT: {
                            mTextSelectionChangeReason
                                    = TextSelectionData.REASON_SELECT_WORD;
                            int x = msg.arg1;
                            int y = msg.arg2;
                            if (!nativeSelectWordAt(mNativeClass, x, y)) {
                                mWebViewClassic.mPrivateHandler.obtainMessage(WebViewClassic.SHOW_CARET_HANDLE)
                                    .sendToTarget();
                            }
                            mTextSelectionChangeReason
                                    = TextSelectionData.REASON_UNKNOWN;
                            break;
                        }
                        case SELECT_ALL:
                            nativeSelectAll(mNativeClass);
                            break;
                        case FIND_ALL: {
                            FindAllRequest request = (FindAllRequest)msg.obj;
                            if (request != null) {
                                int matchCount = nativeFindAll(mNativeClass, request.mSearchText);
                                int matchIndex = nativeFindNext(mNativeClass, true);
                                synchronized (request) {
                                    request.mMatchCount = matchCount;
                                    request.mMatchIndex = matchIndex;
                                    request.notify();
                                }
                            } else {
                                nativeFindAll(mNativeClass, null);
                            }
                            Message.obtain(mWebViewClassic.mPrivateHandler,
                                    WebViewClassic.UPDATE_MATCH_COUNT, request).sendToTarget();
                            break;
                        }
                        case FIND_NEXT: {
                            FindAllRequest request = (FindAllRequest)msg.obj;
                            int matchIndex = nativeFindNext(mNativeClass, msg.arg1 != 0);
                            synchronized (request) {
                                request.mMatchIndex = matchIndex;
                            }
                            Message.obtain(mWebViewClassic.mPrivateHandler,
                                    WebViewClassic.UPDATE_MATCH_COUNT, request).sendToTarget();
                            break;
                        }
                        case SET_INITIAL_FOCUS:
                            nativeSetInitialFocus(mNativeClass, msg.arg1);
                            break;
                        case SAVE_VIEW_STATE:
                            SaveViewStateRequest request = (SaveViewStateRequest) msg.obj;
                            saveViewState(request.mStream, request.mCallback);
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

        @Override
        public Looper getWebKitLooper() {
            return mHandler.getLooper();
        }

        @Override
        public boolean dispatchWebKitEvent(WebViewInputDispatcher dispatcher,
                MotionEvent event, int eventType, int flags) {
            if (mNativeClass == 0) {
                return false;
            }
            switch (eventType) {
                case WebViewInputDispatcher.EVENT_TYPE_HIT_TEST:
                    int x = Math.round(event.getX());
                    int y = Math.round(event.getY());
                    WebKitHitTest hit = performHitTest(x, y,
                            mWebViewClassic.getScaledNavSlop(), true);
                    mWebViewClassic.mPrivateHandler.obtainMessage(
                            WebViewClassic.HIT_TEST_RESULT, hit).sendToTarget();
                    return false;

                case WebViewInputDispatcher.EVENT_TYPE_CLICK:
                    return nativeMouseClick(mNativeClass);

                case WebViewInputDispatcher.EVENT_TYPE_TOUCH: {
                    int count = event.getPointerCount();
                    int[] idArray = new int[count];
                    int[] xArray = new int[count];
                    int[] yArray = new int[count];
                    for (int i = 0; i < count; i++) {
                        idArray[i] = event.getPointerId(i);
                        xArray[i] = (int) event.getX(i);
                        yArray[i] = (int) event.getY(i);
                    }
                    int touchFlags = nativeHandleTouchEvent(mNativeClass,
                            event.getActionMasked(),
                            idArray, xArray, yArray, count,
                            event.getActionIndex(), event.getMetaState());
                    if (touchFlags == 0
                            && event.getActionMasked() != MotionEvent.ACTION_CANCEL
                            && (flags & WebViewInputDispatcher.FLAG_PRIVATE) == 0) {
                        dispatcher.skipWebkitForRemainingTouchStream();
                    }
                    return (touchFlags & TOUCH_FLAG_PREVENT_DEFAULT) > 0;
                }

                default:
                    return false;
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
                Iterator<Message> iter = mMessages.iterator();
                while (iter.hasNext()) {
                    Message m = iter.next();
                    if (m.what == what) {
                        iter.remove();
                    }
                }
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

    void sendMessages(ArrayList<Message> messages) {
        synchronized (mEventHub) {
            for (int i = 0; i < messages.size(); i++) {
                mEventHub.sendMessage(messages.get(i));
            }
        }
    }

    void sendMessage(int what) {
        mEventHub.sendMessage(Message.obtain(null, what));
    }

    void sendMessageAtFrontOfQueue(int what, int arg1, int arg2, Object obj) {
        mEventHub.sendMessageAtFrontOfQueue(Message.obtain(
                null, what, arg1, arg2, obj));
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
            // send DESTROY to front of queue
            // PAUSE/RESUME timers will still be processed even if they get handled later
            mEventHub.mDestroying = true;
            mEventHub.sendMessageAtFrontOfQueue(
                    Message.obtain(null, EventHub.DESTROY));
            mEventHub.blockMessages();
        }
    }

    //-------------------------------------------------------------------------
    // WebViewCore private methods
    //-------------------------------------------------------------------------

    private WebKitHitTest performHitTest(int x, int y, int slop, boolean moveMouse) {
        WebKitHitTest hit = nativeHitTest(mNativeClass, x, y, slop, moveMouse);
        hit.mHitTestX = x;
        hit.mHitTestY = y;
        hit.mHitTestSlop = slop;
        hit.mHitTestMovedMouse = moveMouse;
        return hit;
    }

    private void clearCache(boolean includeDiskFiles) {
        mBrowserFrame.clearCache();
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

    private void key(KeyEvent evt, int canTakeFocusDirection, boolean isDown) {
        if (DebugFlags.WEB_VIEW_CORE) {
            Log.v(LOGTAG, "CORE key at " + System.currentTimeMillis() + ", "
                    + evt);
        }
        mChromeCanFocusDirection = canTakeFocusDirection;
        int keyCode = evt.getKeyCode();
        int unicodeChar = evt.getUnicodeChar();

        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && evt.getCharacters() != null
                && evt.getCharacters().length() > 0) {
            // we should only receive individual complex characters
            unicodeChar = evt.getCharacters().codePointAt(0);
        }

        boolean handled = nativeKey(mNativeClass, keyCode, unicodeChar, evt.getRepeatCount(),
                evt.isShiftPressed(), evt.isAltPressed(),
                evt.isSymPressed(), isDown);
        mChromeCanFocusDirection = 0;
        if (!handled && keyCode != KeyEvent.KEYCODE_ENTER) {
            if (keyCode >= KeyEvent.KEYCODE_DPAD_UP
                    && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (canTakeFocusDirection != 0 && isDown) {
                    Message m = mWebViewClassic.mPrivateHandler.obtainMessage(
                            WebViewClassic.TAKE_FOCUS);
                    m.arg1 = canTakeFocusDirection;
                    m.sendToTarget();
                }
                return;
            }
            // bubble up the event handling
            // but do not bubble up the ENTER key, which would open the search
            // bar without any text.
            mCallbackProxy.onUnhandledKeyEvent(evt);
        }
    }

    private void keyPress(int unicodeChar) {
        nativeKey(mNativeClass, 0, unicodeChar, 0, false, false, false, true);
        nativeKey(mNativeClass, 0, unicodeChar, 0, false, false, false, false);
    }

    // These values are used to avoid requesting a layout based on old values
    private int mCurrentViewWidth = 0;
    private int mCurrentViewHeight = 0;
    private float mCurrentViewScale = 1.0f;

    // notify webkit that our virtual view size changed size (after inv-zoom)
    private void viewSizeChanged(WebViewClassic.ViewSizeData data) {
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
        int screenHeight = data.mActualViewHeight > 0 ? data.mActualViewHeight : h;
        nativeSetSize(mNativeClass, width, height, textwrapWidth, scale,
                w, screenHeight, data.mAnchorX, data.mAnchorY, data.mIgnoreHeight);
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
    }

    // Calculate width to be used in webkit window.
    private int calculateWindowWidth(int viewWidth) {
        int width = viewWidth;
        if (mSettings.getUseWideViewPort()) {
            if (mViewportWidth == -1) {
                // Fixed viewport width.
                width = WebViewClassic.DEFAULT_VIEWPORT_WIDTH;
            } else if (mViewportWidth > 0) {
                // Use website specified or desired fixed viewport width.
                width = mViewportWidth;
            } else {
                // For mobile web site.
                width = Math.round(mWebViewClassic.getViewWidth() /
                        mWebViewClassic.getDefaultZoomScale());
            }
        }
        return width;
    }

    // Utility method for exceededDatabaseQuota callback. Computes the sum
    // of WebSQL database quota for all origins.
    private long getUsedQuota() {
        WebStorageClassic webStorage = WebStorageClassic.getInstance();
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
            mContentSize = new Point();
        }
        int mBaseLayer;
        // view size that was used by webkit during the most recent layout
        Point mViewSize;
        Point mContentSize;
        int mMinPrefWidth;
        // only non-null if it is for the first picture set after the first layout
        ViewState mViewState;
        boolean mFirstLayoutForNonStandardLoad;
    }

    DrawData mLastDrawData = null;

    private Object m_skipDrawFlagLock = new Object();
    private boolean m_skipDrawFlag = false;
    private boolean m_drawWasSkipped = false;

    void pauseWebKitDraw() {
        synchronized (m_skipDrawFlagLock) {
            if (!m_skipDrawFlag) {
                m_skipDrawFlag = true;
            }
        }
    }

    void resumeWebKitDraw() {
        synchronized (m_skipDrawFlagLock) {
            if (m_skipDrawFlag && m_drawWasSkipped) {
                // a draw was dropped, send a retry
                m_drawWasSkipped = false;
                mEventHub.sendMessage(Message.obtain(null, EventHub.WEBKIT_DRAW));
            }
            m_skipDrawFlag = false;
        }
    }

    private void webkitDraw() {
        synchronized (m_skipDrawFlagLock) {
            if (m_skipDrawFlag) {
                m_drawWasSkipped = true;
                return;
            }
        }

        mDrawIsScheduled = false;
        DrawData draw = new DrawData();
        if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw start");
        draw.mBaseLayer = nativeRecordContent(mNativeClass, draw.mContentSize);
        if (draw.mBaseLayer == 0) {
            if (mWebViewClassic != null && !mWebViewClassic.isPaused()) {
                if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw abort, resending draw message");
                mEventHub.sendMessageDelayed(Message.obtain(null, EventHub.WEBKIT_DRAW), 10);
            } else {
                if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "webkitDraw abort, webview paused");
            }
            return;
        }
        mLastDrawData = draw;
        webkitDraw(draw);
    }

    private void webkitDraw(DrawData draw) {
        if (mWebViewClassic != null) {
            draw.mViewSize = new Point(mCurrentViewWidth, mCurrentViewHeight);
            if (mSettings.getUseWideViewPort()) {
                draw.mMinPrefWidth = Math.max(
                        mViewportWidth == -1 ? WebViewClassic.DEFAULT_VIEWPORT_WIDTH
                                : (mViewportWidth == 0 ? mCurrentViewWidth
                                        : mViewportWidth),
                        nativeGetContentMinPrefWidth(mNativeClass));
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
            pauseWebKitDraw();
            Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.NEW_PICTURE_MSG_ID, draw).sendToTarget();
        }
    }

    private void saveViewState(OutputStream stream,
            ValueCallback<Boolean> callback) {
        // TODO: Create a native method to do this better without overloading
        // the draw path (and fix saving <canvas>)
        DrawData draw = new DrawData();
        if (DebugFlags.WEB_VIEW_CORE) Log.v(LOGTAG, "saveViewState start");
        draw.mBaseLayer = nativeRecordContent(mNativeClass, draw.mContentSize);
        boolean result = false;
        try {
            result = ViewStateSerializer.serializeViewState(stream, draw);
        } catch (Throwable t) {
            Log.w(LOGTAG, "Failed to save view state", t);
        }
        callback.onReceiveValue(result);
        if (draw.mBaseLayer != 0) {
            if (mDrawIsScheduled) {
                mDrawIsScheduled = false;
                mEventHub.removeMessages(EventHub.WEBKIT_DRAW);
            }
            mLastDrawData = draw;
            webkitDraw(draw);
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
        WebBackForwardListClassic list = mCallbackProxy.getBackForwardList();
        int size = list.getSize();
        for (int i = 0; i < size; i++) {
            list.getItemAtIndex(i).inflate(mBrowserFrame.mNativeFrame);
        }
        mBrowserFrame.mLoadInitFromJava = true;
        WebBackForwardListClassic.restoreIndex(mBrowserFrame.mNativeFrame, index);
        mBrowserFrame.mLoadInitFromJava = false;
    }

    //-------------------------------------------------------------------------
    // Implement abstract methods in WebViewCore, native WebKit callback part
    //-------------------------------------------------------------------------

    // called from JNI or WebView thread
    /* package */ void contentDraw() {
        synchronized (this) {
            if (mWebViewClassic == null || mBrowserFrame == null) {
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
        if (mWebViewClassic != null) {
            Message msg = Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.SCROLL_TO_MSG_ID, animate ? 1 : 0,
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
        contentDraw();
    }

    /*  Called by JNI. The coordinates are in doc coordinates, so they need to
        be scaled before they can be used by the view system, which happens
        in WebView since it (and its thread) know the current scale factor.
     */
    private void sendViewInvalidate(int left, int top, int right, int bottom) {
        if (mWebViewClassic != null) {
            Message.obtain(mWebViewClassic.mPrivateHandler,
                           WebViewClassic.INVAL_RECT_MSG_ID,
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

    // Gets the WebViewClassic corresponding to this WebViewCore. Note that the
    // WebViewClassic object must only be used on the UI thread.
    /* package */ WebViewClassic getWebViewClassic() {
        return mWebViewClassic;
    }

    // Called by JNI
    private WebView getWebView() {
        return mWebViewClassic.getWebView();
    }

    // Called by JNI
    private void sendPluginDrawMsg() {
        sendMessage(EventHub.PLUGIN_SURFACE_READY);
    }

    private native void setViewportSettingsFromNative(int nativeClass);

    // called by JNI
    private void didFirstLayout(boolean standardLoad) {
        if (DebugFlags.WEB_VIEW_CORE) {
            Log.v(LOGTAG, "didFirstLayout standardLoad =" + standardLoad);
        }

        mBrowserFrame.didFirstLayout();

        if (mWebViewClassic == null) return;

        boolean updateViewState = standardLoad || mIsRestored;
        setupViewport(updateViewState);
        // if updateRestoreState is true, ViewManager.postReadyToDrawAll() will
        // be called after the WebView updates its state. If updateRestoreState
        // is false, start to draw now as it is ready.
        if (!updateViewState) {
            mWebViewClassic.mViewManager.postReadyToDrawAll();
        }

        // remove the touch highlight when moving to a new page
        mWebViewClassic.mPrivateHandler.sendEmptyMessage(
                WebViewClassic.HIT_TEST_RESULT);

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

    static float getFixedDisplayDensity(Context context) {
        // We make bad assumptions about multiplying and dividing density by 100,
        // force them to be true with this hack
        float density = context.getResources().getDisplayMetrics().density;
        return ((int) (density * 100)) / 100.0f;
    }

    private void setupViewport(boolean updateViewState) {
        if (mWebViewClassic == null || mSettings == null) {
            // We've been destroyed or are being destroyed, return early
            return;
        }
        // set the viewport settings from WebKit
        setViewportSettingsFromNative(mNativeClass);

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
            adjust = getFixedDisplayDensity(mContext);
        } else if (mViewportDensityDpi > 0) {
            adjust = (float) mContext.getResources().getDisplayMetrics().densityDpi
                    / mViewportDensityDpi;
            adjust = ((int) (adjust * 100)) / 100.0f;
        }

        // Remove any update density messages in flight.
        // If the density is indeed different from WebView's default scale,
        // a new message will be queued.
        mWebViewClassic.mPrivateHandler.removeMessages(
                WebViewClassic.UPDATE_ZOOM_DENSITY);
        if (adjust != mWebViewClassic.getDefaultZoomScale()) {
            Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.UPDATE_ZOOM_DENSITY, adjust).sendToTarget();
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
            Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.UPDATE_ZOOM_RANGE, viewState).sendToTarget();
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
            webViewWidth = mWebViewClassic.getViewWidth();
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

        if (mWebViewClassic.mHeightCanMeasure) {
            // Trick to ensure that the Picture has the exact height for the
            // content by forcing to layout with 0 height after the page is
            // ready, which is indicated by didFirstLayout. This is essential to
            // get rid of the white space in the GMail which uses WebView for
            // message view.
            mWebViewClassic.mLastHeightSent = 0;
            // Send a negative scale to indicate that WebCore should reuse
            // the current scale
            WebViewClassic.ViewSizeData data = new WebViewClassic.ViewSizeData();
            data.mWidth = mWebViewClassic.mLastWidthSent;
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
                mWebViewClassic.mLastWidthSent = 0;
            } else {
                WebViewClassic.ViewSizeData data = new WebViewClassic.ViewSizeData();
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
                                mWebViewClassic.computeReadingLevelScale(data.mScale);
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
                        Math.round(mWebViewClassic.getViewHeight() / data.mScale)
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
        if (mWebViewClassic != null) {
            Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.WEBCORE_NEED_TOUCH_EVENTS, need ? 1 : 0, 0)
                    .sendToTarget();
        }
    }

    // called by JNI
    private void updateTextfield(int ptr, String text, int textGeneration) {
        if (mWebViewClassic != null) {
            Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.UPDATE_TEXTFIELD_TEXT_MSG_ID, ptr,
                    textGeneration, text).sendToTarget();
        }
    }

    private TextSelectionData createTextSelection(int start, int end, int selPtr) {
        TextSelectionData data = new TextSelectionData(start, end, selPtr);
        data.mSelectionReason = mTextSelectionChangeReason;
        return data;
    }

    // called by JNI
    private void updateTextSelection(int pointer, int start, int end,
            int textGeneration, int selectionPtr) {
        if (mWebViewClassic != null) {
            Message.obtain(mWebViewClassic.mPrivateHandler,
                WebViewClassic.UPDATE_TEXT_SELECTION_MSG_ID, pointer, textGeneration,
                createTextSelection(start, end, selectionPtr)).sendToTarget();
        }
    }

    // called by JNI
    private void updateTextSizeAndScroll(int pointer, int width, int height,
            int scrollX, int scrollY) {
        if (mWebViewClassic != null) {
            Rect rect = new Rect(-scrollX, -scrollY, width - scrollX,
                    height - scrollY);
            Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.EDIT_TEXT_SIZE_CHANGED, pointer, 0, rect)
                    .sendToTarget();
        }
    }

    // called by JNI
    private void clearTextEntry() {
        if (mWebViewClassic == null) return;
        Message.obtain(mWebViewClassic.mPrivateHandler,
                WebViewClassic.CLEAR_TEXT_ENTRY).sendToTarget();
    }

    // called by JNI
    private void initEditField(int start, int end, int selectionPtr,
            TextFieldInitData initData) {
        if (mWebViewClassic == null) {
            return;
        }
        Message.obtain(mWebViewClassic.mPrivateHandler,
                WebViewClassic.INIT_EDIT_FIELD, initData).sendToTarget();
        Message.obtain(mWebViewClassic.mPrivateHandler,
                WebViewClassic.UPDATE_TEXT_SELECTION_MSG_ID,
                initData.mFieldPointer, 0,
                createTextSelection(start, end, selectionPtr))
                .sendToTarget();
    }

    private native void nativeRevealSelection(int nativeClass);
    private native String nativeRequestLabel(int nativeClass, int framePtr,
            int nodePtr);
    /**
     * Scroll the focused textfield to (xPercent, y) in document space
     */
    private native void nativeScrollFocusedTextInput(int nativeClass,
            float xPercent, int y);

    // these must be in document space (i.e. not scaled/zoomed).
    private native void nativeSetScrollOffset(int nativeClass,
            boolean sendScrollEvent, int dx, int dy);

    private native void nativeSetGlobalBounds(int nativeClass, int x, int y,
            int w, int h);

    // called by JNI
    private void requestListBox(String[] array, int[] enabledArray,
            int[] selectedArray) {
        if (mWebViewClassic != null) {
            mWebViewClassic.requestListBox(array, enabledArray, selectedArray);
        }
    }

    // called by JNI
    private void requestListBox(String[] array, int[] enabledArray,
            int selection) {
        if (mWebViewClassic != null) {
            mWebViewClassic.requestListBox(array, enabledArray, selection);
        }

    }

    // called by JNI
    private void requestKeyboard(boolean showKeyboard) {
        if (mWebViewClassic != null) {
            Message.obtain(mWebViewClassic.mPrivateHandler,
                    WebViewClassic.REQUEST_KEYBOARD, showKeyboard ? 1 : 0, 0)
                    .sendToTarget();
        }
    }

    private void setWebTextViewAutoFillable(int queryId, String preview) {
        if (mWebViewClassic != null) {
            Message.obtain(mWebViewClassic.mPrivateHandler, WebViewClassic.SET_AUTOFILLABLE,
                    new AutoFillData(queryId, preview))
                    .sendToTarget();
        }
    }

    Context getContext() {
        return mContext;
    }

    // called by JNI
    private void keepScreenOn(boolean screenOn) {
        if (mWebViewClassic != null) {
            Message message = mWebViewClassic.mPrivateHandler.obtainMessage(
                    WebViewClassic.SCREEN_ON);
            message.arg1 = screenOn ? 1 : 0;
            message.sendToTarget();
        }
    }

    // called by JNI
    private Class<?> getPluginClass(String libName, String clsName) {

        if (mWebViewClassic == null) {
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
        if (mWebViewClassic == null) {
            return;
        }

        Message message = mWebViewClassic.mPrivateHandler.obtainMessage(
                WebViewClassic.SHOW_FULLSCREEN);
        message.obj = childView.mView;
        message.arg1 = orientation;
        message.arg2 = npp;
        message.sendToTarget();
    }

    // called by JNI
    private void hideFullScreenPlugin() {
        if (mWebViewClassic == null) {
            return;
        }
        mWebViewClassic.mPrivateHandler.obtainMessage(WebViewClassic.HIDE_FULLSCREEN)
                .sendToTarget();
    }

    private ViewManager.ChildView createSurface(View pluginView) {
        if (mWebViewClassic == null) {
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

        ViewManager.ChildView view = mWebViewClassic.mViewManager.createView();
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
        if (mWebViewClassic != null) {
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
            Message.obtain(mWebViewClassic.mPrivateHandler, WebViewClassic.SHOW_RECT_MSG_ID,
                    data).sendToTarget();
        }
    }

    // called by JNI
    private void centerFitRect(int x, int y, int width, int height) {
        if (mWebViewClassic == null) {
            return;
        }
        mWebViewClassic.mPrivateHandler.obtainMessage(WebViewClassic.CENTER_FIT_RECT,
                new Rect(x, y, x + width, y + height)).sendToTarget();
    }

    // called by JNI
    private void setScrollbarModes(int hMode, int vMode) {
        if (mWebViewClassic == null) {
            return;
        }
        mWebViewClassic.mPrivateHandler.obtainMessage(WebViewClassic.SET_SCROLLBAR_MODES,
                hMode, vMode).sendToTarget();
    }

    // called by JNI
    private void selectAt(int x, int y) {
        // TODO: Figure out what to do with this (b/6111818)
    }

    private void setUseMockDeviceOrientation() {
        mDeviceMotionAndOrientationManager.setUseMock();
    }

    private void setUseMockGeolocation() {
        mMockGeolocation.setUseMock();
    }

    public void setMockGeolocationPosition(double latitude, double longitude, double accuracy) {
        mMockGeolocation.setPosition(latitude, longitude, accuracy);
    }

    public void setMockGeolocationError(int code, String message) {
        mMockGeolocation.setError(code, message);
    }

    public void setMockGeolocationPermission(boolean allow) {
        mMockGeolocation.setPermission(allow);
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

    static void setShouldMonitorWebCoreThread() {
        sShouldMonitorWebCoreThread = true;
    }

    private native void nativePause(int nativeClass);
    private native void nativeResume(int nativeClass);
    private native void nativeFreeMemory(int nativeClass);
    private native void nativeFullScreenPluginHidden(int nativeClass, int npp);
    private native void nativePluginSurfaceReady(int nativeClass);

    private native WebKitHitTest nativeHitTest(int nativeClass, int x, int y,
            int slop, boolean moveMouse);

    private native void nativeAutoFillForm(int nativeClass, int queryId);
    private native void nativeScrollLayer(int nativeClass, int layer, Rect rect);
    private native int nativeFindAll(int nativeClass, String text);
    private native int nativeFindNext(int nativeClass, boolean forward);

    /**
     * Deletes editable text between two points. Note that the selection may
     * differ from the WebView's selection because the algorithms for selecting
     * text differs for non-LTR text. Any text that isn't editable will be
     * left unchanged.
     * @param nativeClass The pointer to the native class (mNativeClass)
     * @param startX The X position of the top-left selection point.
     * @param startY The Y position of the top-left selection point.
     * @param endX The X position of the bottom-right selection point.
     * @param endY The Y position of the bottom-right selection point.
     */
    private native void nativeDeleteText(int nativeClass,
            int startX, int startY, int endX, int endY);
    /**
     * Inserts text at the current cursor position. If the currently-focused
     * node does not have a cursor position then this function does nothing.
     */
    private native void nativeInsertText(int nativeClass, String text);
    /**
     * Gets the text between two selection points. Note that the selection
     * may differ from the WebView's selection because the algorithms for
     * selecting text differs for non-LTR text.
     * @param nativeClass The pointer to the native class (mNativeClass)
     * @param startX The X position of the top-left selection point.
     * @param startY The Y position of the top-left selection point.
     * @param endX The X position of the bottom-right selection point.
     * @param endY The Y position of the bottom-right selection point.
     */
    private native String nativeGetText(int nativeClass,
            int startX, int startY, int endX, int endY);
    private native void nativeSelectText(int nativeClass,
            int handleId, int x, int y);
    private native void nativeClearTextSelection(int nativeClass);
    private native boolean nativeSelectWordAt(int nativeClass, int x, int y);
    private native void nativeSelectAll(int nativeClass);
    private native void nativeSetInitialFocus(int nativeClass, int keyDirection);

    private static native void nativeCertTrustChanged();
}
