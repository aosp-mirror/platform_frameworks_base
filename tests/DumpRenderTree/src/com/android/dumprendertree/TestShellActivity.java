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

package com.android.dumprendertree;

import com.android.dumprendertree.forwarder.ForwardService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnClickListener;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Bitmap.Config;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Vector;

public class TestShellActivity extends Activity implements LayoutTestController {

    static enum DumpDataType {DUMP_AS_TEXT, EXT_REPR, NO_OP}

    // String constants for use with layoutTestController.overridePreferences
    private final String WEBKIT_OFFLINE_WEB_APPLICATION_CACHE_ENABLED =
            "WebKitOfflineWebApplicationCacheEnabled";
    private final String WEBKIT_USES_PAGE_CACHE_PREFERENCE_KEY = "WebKitUsesPageCachePreferenceKey";

    public class AsyncHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_TIMEOUT) {
                mTimedOut = true;
                mWebView.stopLoading();
                if (mCallback != null)
                    mCallback.timedOut(mWebView.getUrl());
                if (!mRequestedWebKitData) {
                    requestWebKitData();
                } else {
                    // if timed out and webkit data has been dumped before
                    // finish directly
                    finished();
                }
                return;
            } else if (msg.what == MSG_WEBKIT_DATA) {
                Log.v(LOGTAG, "Received WebView dump data");
                mHandler.removeMessages(MSG_DUMP_TIMEOUT);
                TestShellActivity.this.dump(mTimedOut, (String)msg.obj);
                return;
            } else if (msg.what == MSG_DUMP_TIMEOUT) {
                throw new RuntimeException("WebView dump timeout, is it pegged?");
            }
            super.handleMessage(msg);
        }
    }

    public void requestWebKitData() {
        setDumpTimeout(DUMP_TIMEOUT_MS);
        Message callback = mHandler.obtainMessage(MSG_WEBKIT_DATA);

        if (mRequestedWebKitData)
            throw new AssertionError("Requested webkit data twice: " + mWebView.getUrl());

        mRequestedWebKitData = true;
        Log.v(LOGTAG, "message sent to WebView to dump text.");
        switch (mDumpDataType) {
            case DUMP_AS_TEXT:
                callback.arg1 = mDumpTopFrameAsText ? 1 : 0;
                callback.arg2 = mDumpChildFramesAsText ? 1 : 0;
                mWebView.documentAsText(callback);
                break;
            case EXT_REPR:
                mWebView.externalRepresentation(callback);
                break;
            default:
                finished();
                break;
        }
    }

    private void setDumpTimeout(long timeout) {
        Log.v(LOGTAG, "setting dump timeout at " + timeout);
        Message msg = mHandler.obtainMessage(MSG_DUMP_TIMEOUT);
        mHandler.sendMessageDelayed(msg, timeout);
    }

    public void clearCache() {
      mWebView.freeMemory();
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_PROGRESS);

        LinearLayout contentView = new LinearLayout(this);
        contentView.setOrientation(LinearLayout.VERTICAL);
        setContentView(contentView);

        CookieManager.setAcceptFileSchemeCookies(true);
        mWebView = new WebView(this);
        mEventSender = new WebViewEventSender(mWebView);
        mCallbackProxy = new CallbackProxy(mEventSender, this);

        mWebView.addJavascriptInterface(mCallbackProxy, "layoutTestController");
        mWebView.addJavascriptInterface(mCallbackProxy, "eventSender");
        setupWebViewForLayoutTests(mWebView, mCallbackProxy);

        contentView.addView(mWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0f));

        mWebView.getSettings().setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);

        // Expose window.gc function to JavaScript. JSC build exposes
        // this function by default, but V8 requires the flag to turn it on.
        // WebView::setJsFlags is noop in JSC build.
        mWebView.setJsFlags("--expose_gc");

        mHandler = new AsyncHandler();

        Intent intent = getIntent();
        if (intent != null) {
            executeIntent(intent);
        }

        // This is asynchronous, but it gets processed by WebCore before it starts loading pages.
        mWebView.useMockDeviceOrientation();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        executeIntent(intent);
    }

    private void executeIntent(Intent intent) {
        resetTestStatus();
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }

        mTotalTestCount = intent.getIntExtra(TOTAL_TEST_COUNT, mTotalTestCount);
        mCurrentTestNumber = intent.getIntExtra(CURRENT_TEST_NUMBER, mCurrentTestNumber);

        mTestUrl = intent.getStringExtra(TEST_URL);
        if (mTestUrl == null) {
            mUiAutoTestPath = intent.getStringExtra(UI_AUTO_TEST);
            if(mUiAutoTestPath != null) {
                beginUiAutoTest();
            }
            return;
        }

        mResultFile = intent.getStringExtra(RESULT_FILE);
        mTimeoutInMillis = intent.getIntExtra(TIMEOUT_IN_MILLIS, 0);
        mGetDrawtime = intent.getBooleanExtra(GET_DRAW_TIME, false);
        mSaveImagePath = intent.getStringExtra(SAVE_IMAGE);
        mStopOnRefError = intent.getBooleanExtra(STOP_ON_REF_ERROR, false);
        setTitle("Test " + mCurrentTestNumber + " of " + mTotalTestCount);
        float ratio = (float)mCurrentTestNumber / mTotalTestCount;
        int progress = (int)(ratio * Window.PROGRESS_END);
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS, progress);

        Log.v(LOGTAG, "  Loading " + mTestUrl);

        if (mTestUrl.contains("/dumpAsText/")) {
            dumpAsText(false);
        }

        mWebView.loadUrl(mTestUrl);

        if (mTimeoutInMillis > 0) {
            // Create a timeout timer
            Message m = mHandler.obtainMessage(MSG_TIMEOUT);
            mHandler.sendMessageDelayed(m, mTimeoutInMillis);
        }
    }

    private void beginUiAutoTest() {
        try {
            mTestListReader = new BufferedReader(
                    new FileReader(mUiAutoTestPath));
        } catch (IOException ioe) {
            Log.e(LOGTAG, "Failed to open test list for read.", ioe);
            finishUiAutoTest();
            return;
        }
        moveToNextTest();
    }

    private void finishUiAutoTest() {
        try {
            if(mTestListReader != null)
                mTestListReader.close();
        } catch (IOException ioe) {
            Log.w(LOGTAG, "Failed to close test list file.", ioe);
        }
        ForwardService.getForwardService().stopForwardService();
        finished();
    }

    private void moveToNextTest() {
        String url = null;
        try {
            url = mTestListReader.readLine();
        } catch (IOException ioe) {
            Log.e(LOGTAG, "Failed to read next test.", ioe);
            finishUiAutoTest();
            return;
        }
        if (url == null) {
            mUiAutoTestPath = null;
            finishUiAutoTest();
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage("All tests finished. Exit?")
                   .setCancelable(false)
                   .setPositiveButton("Yes", new OnClickListener(){
                       public void onClick(DialogInterface dialog, int which) {
                           TestShellActivity.this.finish();
                       }
                   })
                   .setNegativeButton("No", new OnClickListener(){
                       public void onClick(DialogInterface dialog, int which) {
                           dialog.cancel();
                       }
                   });
            builder.create().show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TestShellActivity.TEST_URL, FsUtils.getTestUrl(url));
        intent.putExtra(TestShellActivity.CURRENT_TEST_NUMBER, ++mCurrentTestNumber);
        intent.putExtra(TIMEOUT_IN_MILLIS, 10000);
        executeIntent(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mWebView.stopLoading();
    }


    //TODO: remove. this is temporary for bug investigation
    @Override
    public void finish() {
      Exception e = new Exception("finish() call stack");
      Log.d(LOGTAG, "finish stack trace", e);
      super.finish();
    }

    @Override
    protected void onDestroy() {
        //TODO: remove exception log. this is temporary for bug investigation
        Exception e = new Exception("onDestroy stack trace");
        Log.d(LOGTAG, "onDestroy stack trace", e);
        mWebView.destroy();
        mWebView = null;
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.e(LOGTAG, "Low memory, clearing caches");
        mWebView.freeMemory();
    }

    // Dump the page
    public void dump(boolean timeout, String webkitData) {
        mDumpWebKitData = true;
        if (mResultFile == null || mResultFile.length() == 0) {
            finished();
            return;
        }

        try {
            File parentDir = new File(mResultFile).getParentFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            FileOutputStream os = new FileOutputStream(mResultFile);
            if (timeout) {
                Log.w("Layout test: Timeout", mResultFile);
                os.write(TIMEOUT_STR.getBytes());
                os.write('\n');
            }
            if (mDumpTitleChanges)
                os.write(mTitleChanges.toString().getBytes());
            if (mDialogStrings != null)
                os.write(mDialogStrings.toString().getBytes());
            mDialogStrings = null;
            if (mDatabaseCallbackStrings != null)
                os.write(mDatabaseCallbackStrings.toString().getBytes());
            mDatabaseCallbackStrings = null;
            if (mConsoleMessages != null)
                os.write(mConsoleMessages.toString().getBytes());
            mConsoleMessages = null;
            if (webkitData != null)
                os.write(webkitData.getBytes());
            os.flush();
            os.close();
        } catch (IOException ex) {
            Log.e(LOGTAG, "Cannot write to " + mResultFile + ", " + ex.getMessage());
        }

        finished();
    }

    public void setCallback(TestShellCallback callback) {
        mCallback = callback;
    }

    public boolean finished() {
        if (canMoveToNextTest()) {
            mHandler.removeMessages(MSG_TIMEOUT);
            if (mUiAutoTestPath != null) {
                //don't really finish here
                moveToNextTest();
            } else {
                if (mCallback != null) {
                    mCallback.finished();
                }
            }
            return true;
        }
        return false;
    }

    public void setDefaultDumpDataType(DumpDataType defaultDumpDataType) {
        mDefaultDumpDataType = defaultDumpDataType;
    }

    // .......................................
    // LayoutTestController Functions
    public void dumpAsText(boolean enablePixelTests) {
        // Added after webkit update to r63859. See trac.webkit.org/changeset/63730.
        if (enablePixelTests) {
            Log.v(LOGTAG, "dumpAsText(enablePixelTests == true) not implemented on Android!");
        }

        mDumpDataType = DumpDataType.DUMP_AS_TEXT;
        mDumpTopFrameAsText = true;
        if (mWebView != null) {
            String url = mWebView.getUrl();
            Log.v(LOGTAG, "dumpAsText called: "+url);
        }
    }

    public void dumpChildFramesAsText() {
        mDumpDataType = DumpDataType.DUMP_AS_TEXT;
        mDumpChildFramesAsText = true;
        if (mWebView != null) {
            String url = mWebView.getUrl();
            Log.v(LOGTAG, "dumpChildFramesAsText called: "+url);
        }
    }

    public void waitUntilDone() {
        mWaitUntilDone = true;
        String url = mWebView.getUrl();
        Log.v(LOGTAG, "waitUntilDone called: " + url);
    }

    public void notifyDone() {
        String url = mWebView.getUrl();
        Log.v(LOGTAG, "notifyDone called: " + url);
        if (mWaitUntilDone) {
            mWaitUntilDone = false;
            if (!mRequestedWebKitData && !mTimedOut && !finished()) {
                requestWebKitData();
            }
        }
    }

    public void display() {
        mWebView.invalidate();
    }

    public void clearBackForwardList() {
        mWebView.clearHistory();

    }

    public void dumpBackForwardList() {
        //printf("\n============== Back Forward List ==============\n");
        // mWebHistory
        //printf("===============================================\n");

    }

    public void dumpChildFrameScrollPositions() {
        // TODO Auto-generated method stub

    }

    public void dumpEditingCallbacks() {
        // TODO Auto-generated method stub

    }

    public void dumpSelectionRect() {
        // TODO Auto-generated method stub

    }

    public void dumpTitleChanges() {
        if (!mDumpTitleChanges) {
            mTitleChanges = new StringBuffer();
        }
        mDumpTitleChanges = true;
    }

    public void keepWebHistory() {
        if (!mKeepWebHistory) {
            mWebHistory = new Vector();
        }
        mKeepWebHistory = true;
    }

    public void queueBackNavigation(int howfar) {
        // TODO Auto-generated method stub

    }

    public void queueForwardNavigation(int howfar) {
        // TODO Auto-generated method stub

    }

    public void queueLoad(String Url, String frameTarget) {
        // TODO Auto-generated method stub

    }

    public void queueReload() {
        mWebView.reload();
    }

    public void queueScript(String scriptToRunInCurrentContext) {
        mWebView.loadUrl("javascript:"+scriptToRunInCurrentContext);
    }

    public void repaintSweepHorizontally() {
        // TODO Auto-generated method stub

    }

    public void setAcceptsEditing(boolean b) {
        // TODO Auto-generated method stub

    }

    public void setMainFrameIsFirstResponder(boolean b) {
        // TODO Auto-generated method stub

    }

    public void setWindowIsKey(boolean b) {
        // This is meant to show/hide the window. The best I can find
        // is setEnabled()
        mWebView.setEnabled(b);
    }

    public void testRepaint() {
        mWebView.invalidate();
    }

    public void dumpDatabaseCallbacks() {
        Log.v(LOGTAG, "dumpDatabaseCallbacks called.");
        mDumpDatabaseCallbacks = true;
    }

    public void setCanOpenWindows() {
        Log.v(LOGTAG, "setCanOpenWindows called.");
        mCanOpenWindows = true;
    }

    /**
     * Sets the Geolocation permission state to be used for all future requests.
     */
    public void setGeolocationPermission(boolean allow) {
        mIsGeolocationPermissionSet = true;
        mGeolocationPermission = allow;

        if (mPendingGeolocationPermissionCallbacks != null) {
            Iterator iter = mPendingGeolocationPermissionCallbacks.keySet().iterator();
            while (iter.hasNext()) {
                GeolocationPermissions.Callback callback =
                        (GeolocationPermissions.Callback) iter.next();
                String origin = (String) mPendingGeolocationPermissionCallbacks.get(callback);
                callback.invoke(origin, mGeolocationPermission, false);
            }
            mPendingGeolocationPermissionCallbacks = null;
        }
    }

    public void setMockDeviceOrientation(boolean canProvideAlpha, double alpha,
            boolean canProvideBeta, double beta, boolean canProvideGamma, double gamma) {
        mWebView.setMockDeviceOrientation(canProvideAlpha, alpha, canProvideBeta, beta,
                canProvideGamma, gamma);
    }

    public void overridePreference(String key, boolean value) {
        // TODO: We should look up the correct WebView for the frame which
        // called the layoutTestController method. Currently, we just use the
        // WebView for the main frame. EventSender suffers from the same
        // problem.
        if (WEBKIT_OFFLINE_WEB_APPLICATION_CACHE_ENABLED.equals(key)) {
            mWebView.getSettings().setAppCacheEnabled(value);
        } else if (WEBKIT_USES_PAGE_CACHE_PREFERENCE_KEY.equals(key)) {
            // Cache the maximum possible number of pages.
            mWebView.getSettings().setPageCacheCapacity(Integer.MAX_VALUE);
        } else {
            Log.w(LOGTAG, "LayoutTestController.overridePreference(): " +
                  "Unsupported preference '" + key + "'");
        }
    }

    public void setXSSAuditorEnabled (boolean flag) {
        mWebView.getSettings().setXSSAuditorEnabled(flag);
    }

    private final WebViewClient mViewClient = new WebViewClient(){
        @Override
        public void onPageFinished(WebView view, String url) {
            Log.v(LOGTAG, "onPageFinished, url=" + url);
            mPageFinished = true;
            // get page draw time
            if (FsUtils.isTestPageUrl(url)) {
                if (mGetDrawtime) {
                    long[] times = new long[DRAW_RUNS];
                    times = getDrawWebViewTime(mWebView, DRAW_RUNS);
                    FsUtils.writeDrawTime(DRAW_TIME_LOG, url, times);
                }
                if (mSaveImagePath != null) {
                    String name = FsUtils.getLastSegmentInPath(url);
                    drawPageToFile(mSaveImagePath + "/" + name + ".png", mWebView);
                }
            }

            // Calling finished() will check if we've met all the conditions for completing
            // this test and move to the next one if we are ready. Otherwise we ask WebCore to
            // dump the page.
            if (finished()) {
                return;
            }

            if (!mWaitUntilDone && !mRequestedWebKitData && !mTimedOut) {
                requestWebKitData();
            } else {
                if (mWaitUntilDone) {
                    Log.v(LOGTAG, "page finished loading but waiting for notifyDone to be called: " + url);
                }

                if (mRequestedWebKitData) {
                    Log.v(LOGTAG, "page finished loading but webkit data has already been requested: " + url);
                }

                if (mTimedOut) {
                    Log.v(LOGTAG, "page finished loading but already timed out: " + url);
                }
            }

            super.onPageFinished(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Log.v(LOGTAG, "onPageStarted, url=" + url);
            mPageFinished = false;
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description,
                String failingUrl) {
            Log.v(LOGTAG, "onReceivedError, errorCode=" + errorCode
                    + ", desc=" + description + ", url=" + failingUrl);
            super.onReceivedError(view, errorCode, description, failingUrl);
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                String host, String realm) {
            if (handler.useHttpAuthUsernamePassword() && view != null) {
                String[] credentials = view.getHttpAuthUsernamePassword(host, realm);
                if (credentials != null && credentials.length == 2) {
                    handler.proceed(credentials[0], credentials[1]);
                    return;
                }
            }
            handler.cancel();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                SslError error) {
            handler.proceed();
        }
    };


    private final WebChromeClient mChromeClient = new WebChromeClient() {
        @Override
        public void onReceivedTitle(WebView view, String title) {
            setTitle("Test " + mCurrentTestNumber + " of " + mTotalTestCount + ": "+ title);
            if (mDumpTitleChanges) {
                mTitleChanges.append("TITLE CHANGED: ");
                mTitleChanges.append(title);
                mTitleChanges.append("\n");
            }
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message,
                JsResult result) {
            if (mDialogStrings == null) {
                mDialogStrings = new StringBuffer();
            }
            mDialogStrings.append("ALERT: ");
            mDialogStrings.append(message);
            mDialogStrings.append('\n');
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message,
                JsResult result) {
            if (mDialogStrings == null) {
                mDialogStrings = new StringBuffer();
            }
            mDialogStrings.append("CONFIRM: ");
            mDialogStrings.append(message);
            mDialogStrings.append('\n');
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message,
                String defaultValue, JsPromptResult result) {
            if (mDialogStrings == null) {
                mDialogStrings = new StringBuffer();
            }
            mDialogStrings.append("PROMPT: ");
            mDialogStrings.append(message);
            mDialogStrings.append(", default text: ");
            mDialogStrings.append(defaultValue);
            mDialogStrings.append('\n');
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsTimeout() {
            Log.v(LOGTAG, "JavaScript timeout");
            return false;
        }

        @Override
        public void onExceededDatabaseQuota(String url_str,
                String databaseIdentifier, long currentQuota,
                long estimatedSize, long totalUsedQuota,
                WebStorage.QuotaUpdater callback) {
            if (mDumpDatabaseCallbacks) {
                if (mDatabaseCallbackStrings == null) {
                    mDatabaseCallbackStrings = new StringBuffer();
                }

                String protocol = "";
                String host = "";
                int port = 0;

                try {
                    URL url = new URL(url_str);
                    protocol = url.getProtocol();
                    host = url.getHost();
                    if (url.getPort() > -1) {
                        port = url.getPort();
                    }
                } catch (MalformedURLException e) {}

                String databaseCallbackString =
                        "UI DELEGATE DATABASE CALLBACK: " +
                        "exceededDatabaseQuotaForSecurityOrigin:{" + protocol +
                        ", " + host + ", " + port + "} database:" +
                        databaseIdentifier + "\n";
                Log.v(LOGTAG, "LOG: "+databaseCallbackString);
                mDatabaseCallbackStrings.append(databaseCallbackString);
            }
            // Give 5MB more quota.
            callback.updateQuota(currentQuota + 1024 * 1024 * 5);
        }

        /**
         * Instructs the client to show a prompt to ask the user to set the
         * Geolocation permission state for the specified origin.
         */
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                GeolocationPermissions.Callback callback) {
            if (mIsGeolocationPermissionSet) {
                callback.invoke(origin, mGeolocationPermission, false);
                return;
            }
            if (mPendingGeolocationPermissionCallbacks == null) {
                mPendingGeolocationPermissionCallbacks =
                        new HashMap<GeolocationPermissions.Callback, String>();
            }
            mPendingGeolocationPermissionCallbacks.put(callback, origin);
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            String msg = "CONSOLE MESSAGE: line " + consoleMessage.lineNumber() + ": "
                    + consoleMessage.message() + "\n";
            if (mConsoleMessages == null) {
                mConsoleMessages = new StringBuffer();
            }
            mConsoleMessages.append(msg);
            Log.v(LOGTAG, "LOG: " + msg);
            // the rationale here is that if there's an error of either type, and the test was
            // waiting for "notifyDone" signal to finish, then there's no point in waiting
            // anymore because the JS execution is already terminated at this point and a
            // "notifyDone" will never come out so it's just wasting time till timeout kicks in
            if ((msg.contains("Uncaught ReferenceError:") || msg.contains("Uncaught TypeError:"))
                    && mWaitUntilDone && mStopOnRefError) {
                Log.w(LOGTAG, "Terminating test case on uncaught ReferenceError or TypeError.");
                mHandler.postDelayed(new Runnable() {
                    public void run() {
                        notifyDone();
                    }
                }, 500);
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean dialog,
                boolean userGesture, Message resultMsg) {
            if (!mCanOpenWindows) {
                // We can't open windows, so just send null back.
                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(null);
                resultMsg.sendToTarget();
                return true;
            }

            // We never display the new window, just create the view and
            // allow it's content to execute and be recorded by the test
            // runner.

            HashMap<String, Object> jsIfaces = new HashMap<String, Object>();
            jsIfaces.put("layoutTestController", mCallbackProxy);
            jsIfaces.put("eventSender", mCallbackProxy);
            WebView newWindowView = new NewWindowWebView(TestShellActivity.this, jsIfaces);
            setupWebViewForLayoutTests(newWindowView, mCallbackProxy);
            WebView.WebViewTransport transport =
                    (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(newWindowView);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView view) {
            view.destroy();
        }
    };

    private static class NewWindowWebView extends WebView {
        public NewWindowWebView(Context context, Map<String, Object> jsIfaces) {
            super(context, null, 0, jsIfaces, false);
        }
    }

    private void resetTestStatus() {
        mWaitUntilDone = false;
        mDumpDataType = mDefaultDumpDataType;
        mDumpTopFrameAsText = false;
        mDumpChildFramesAsText = false;
        mTimedOut = false;
        mDumpTitleChanges = false;
        mRequestedWebKitData = false;
        mDumpDatabaseCallbacks = false;
        mCanOpenWindows = false;
        mEventSender.resetMouse();
        mEventSender.clearTouchPoints();
        mEventSender.clearTouchMetaState();
        mPageFinished = false;
        mDumpWebKitData = false;
        mGetDrawtime = false;
        mSaveImagePath = null;
        setDefaultWebSettings(mWebView);
        mIsGeolocationPermissionSet = false;
        mPendingGeolocationPermissionCallbacks = null;
        CookieManager.getInstance().removeAllCookie();
    }

    private long[] getDrawWebViewTime(WebView view, int count) {
        if (count == 0)
            return null;
        long[] ret = new long[count];
        long start;
        Canvas canvas = new Canvas();
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Config.ARGB_8888);
        canvas.setBitmap(bitmap);
        for (int i = 0; i < count; i++) {
            start = System.currentTimeMillis();
            view.draw(canvas);
            ret[i] = System.currentTimeMillis() - start;
        }
        return ret;
    }

    private void drawPageToFile(String fileName, WebView view) {
        Canvas canvas = new Canvas();
        Bitmap bitmap = Bitmap.createBitmap(view.getContentWidth(), view.getContentHeight(),
                Config.ARGB_8888);
        canvas.setBitmap(bitmap);
        view.drawPage(canvas);
        try {
            FileOutputStream fos = new FileOutputStream(fileName);
            if(!bitmap.compress(CompressFormat.PNG, 90, fos)) {
                Log.w(LOGTAG, "Failed to compress and save image.");
            }
        } catch (IOException ioe) {
            Log.e(LOGTAG, "", ioe);
        }
        bitmap.recycle();
    }

    private boolean canMoveToNextTest() {
        return (mDumpWebKitData && mPageFinished && !mWaitUntilDone) || mTimedOut;
    }

    private void setupWebViewForLayoutTests(WebView webview, CallbackProxy callbackProxy) {
        if (webview == null) {
            return;
        }

        setDefaultWebSettings(webview);

        webview.setWebChromeClient(mChromeClient);
        webview.setWebViewClient(mViewClient);
        // Setting a touch interval of -1 effectively disables the optimisation in WebView
        // that stops repeated touch events flooding WebCore. The Event Sender only sends a
        // single event rather than a stream of events (like what would generally happen in
        // a real use of touch events in a WebView)  and so if the WebView drops the event,
        // the test will fail as the test expects one callback for every touch it synthesizes.
        webview.setTouchInterval(-1);
    }

    public void setDefaultWebSettings(WebView webview) {
        WebSettings settings = webview.getSettings();
        settings.setAppCacheEnabled(true);
        settings.setAppCachePath(getApplicationContext().getCacheDir().getPath());
        settings.setAppCacheMaxSize(Long.MAX_VALUE);
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        settings.setDatabaseEnabled(true);
        settings.setDatabasePath(getDir("databases",0).getAbsolutePath());
        settings.setDomStorageEnabled(true);
        settings.setWorkersEnabled(false);
        settings.setXSSAuditorEnabled(false);
        settings.setPageCacheCapacity(0);
    }

    private WebView mWebView;
    private WebViewEventSender mEventSender;
    private AsyncHandler mHandler;
    private TestShellCallback mCallback;

    private CallbackProxy mCallbackProxy;

    private String mTestUrl;
    private String mResultFile;
    private int mTimeoutInMillis;
    private String mUiAutoTestPath;
    private String mSaveImagePath;
    private BufferedReader mTestListReader;
    private boolean mGetDrawtime;
    private int mTotalTestCount;
    private int mCurrentTestNumber;
    private boolean mStopOnRefError;

    // States
    private boolean mTimedOut;
    private boolean mRequestedWebKitData;
    private boolean mFinishedRunning;

    // Layout test controller variables.
    private DumpDataType mDumpDataType;
    private DumpDataType mDefaultDumpDataType = DumpDataType.EXT_REPR;
    private boolean mDumpTopFrameAsText;
    private boolean mDumpChildFramesAsText;
    private boolean mWaitUntilDone;
    private boolean mDumpTitleChanges;
    private StringBuffer mTitleChanges;
    private StringBuffer mDialogStrings;
    private boolean mKeepWebHistory;
    private Vector mWebHistory;
    private boolean mDumpDatabaseCallbacks;
    private StringBuffer mDatabaseCallbackStrings;
    private StringBuffer mConsoleMessages;
    private boolean mCanOpenWindows;

    private boolean mPageFinished = false;
    private boolean mDumpWebKitData = false;

    static final String TIMEOUT_STR = "**Test timeout";
    static final long DUMP_TIMEOUT_MS = 100000; // 100s timeout for dumping webview content

    static final int MSG_TIMEOUT = 0;
    static final int MSG_WEBKIT_DATA = 1;
    static final int MSG_DUMP_TIMEOUT = 2;

    static final String LOGTAG="TestShell";

    static final String TEST_URL = "TestUrl";
    static final String RESULT_FILE = "ResultFile";
    static final String TIMEOUT_IN_MILLIS = "TimeoutInMillis";
    static final String UI_AUTO_TEST = "UiAutoTest";
    static final String GET_DRAW_TIME = "GetDrawTime";
    static final String SAVE_IMAGE = "SaveImage";
    static final String TOTAL_TEST_COUNT = "TestCount";
    static final String CURRENT_TEST_NUMBER = "TestNumber";
    static final String STOP_ON_REF_ERROR = "StopOnReferenceError";

    static final int DRAW_RUNS = 5;
    static final String DRAW_TIME_LOG = Environment.getExternalStorageDirectory() +
        "/android/page_draw_time.txt";

    private boolean mIsGeolocationPermissionSet;
    private boolean mGeolocationPermission;
    private Map mPendingGeolocationPermissionCallbacks;
}
