/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.GeolocationPermissions.Callback;
import android.webkit.WebStorage.QuotaUpdater;

import java.io.File;
import java.util.List;

/**
 * This activity executes the test. It contains WebView and logic of LayoutTestController
 * functions. It runs in a separate process and sends the results of running the test
 * to ManagerService. The reason why is to handle crashing (test that crashes brings down
 * whole process with it).
 */
public class LayoutTestsExecutor extends Activity {

    private enum CurrentState {
        IDLE,
        RENDERING_PAGE,
        WAITING_FOR_ASYNCHRONOUS_TEST,
        OBTAINING_RESULT;

        public boolean isRunningState() {
            return this == CurrentState.RENDERING_PAGE ||
                    this == CurrentState.WAITING_FOR_ASYNCHRONOUS_TEST;
        }
    }

    /** TODO: make it a setting */
    static final String TESTS_ROOT_DIR_PATH =
            Environment.getExternalStorageDirectory() +
            File.separator + "android" +
            File.separator + "LayoutTests";

    private static final String LOG_TAG = "LayoutTestExecutor";

    public static final String EXTRA_TESTS_LIST = "TestsList";
    public static final String EXTRA_TEST_INDEX = "TestIndex";

    private static final int MSG_ACTUAL_RESULT_OBTAINED = 0;
    private static final int MSG_TEST_TIMED_OUT = 1;

    private static final int DEFAULT_TIME_OUT_MS = 15 * 1000;

    /** A list of tests that remain to run since last crash */
    private List<String> mTestsList;

    /**
     * This is a number of currently running test. It is 0-based and doesn't reset after
     * the crash. Initial index is passed to LayoutTestsExecuter in the intent that starts
     * it.
     */
    private int mCurrentTestIndex;

    /** The total number of tests to run, doesn't reset after crash */
    private int mTotalTestCount;

    private WebView mCurrentWebView;
    private String mCurrentTestRelativePath;
    private String mCurrentTestUri;
    private CurrentState mCurrentState = CurrentState.IDLE;

    private boolean mCurrentTestTimedOut;
    private AbstractResult mCurrentResult;
    private AdditionalTextOutput mCurrentAdditionalTextOutput;

    private LayoutTestController mLayoutTestController = new LayoutTestController(this);
    private boolean mCanOpenWindows;
    private boolean mDumpDatabaseCallbacks;
    private boolean mSetGeolocationPermissionCalled;
    private boolean mGeolocationPermission;

    private EventSender mEventSender = new EventSender();

    private WakeLock mScreenDimLock;

    /** COMMUNICATION WITH ManagerService */

    private Messenger mManagerServiceMessenger;

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mManagerServiceMessenger = new Messenger(service);
            startTests();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            /** TODO */
        }
    };

    private final Handler mResultHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ACTUAL_RESULT_OBTAINED:
                    onActualResultsObtained();
                    break;

                case MSG_TEST_TIMED_OUT:
                    onTestTimedOut();
                    break;

                default:
                    break;
            }
        }
    };

    /** WEBVIEW CONFIGURATION */

    private WebViewClient mWebViewClient = new WebViewClient() {
        @Override
        public void onPageFinished(WebView view, String url) {
            /** Some tests fire up many page loads, we don't want to detect them */
            if (!url.equals(mCurrentTestUri)) {
                return;
            }

            if (mCurrentState == CurrentState.RENDERING_PAGE) {
                onTestFinished();
            }
        }
    };

    private WebChromeClient mWebChromeClient = new WebChromeClient() {
        @Override
        public void onExceededDatabaseQuota(String url, String databaseIdentifier,
                long currentQuota, long estimatedSize, long totalUsedQuota,
                QuotaUpdater quotaUpdater) {
            /** TODO: This should be recorded as part of the text result */
            /** TODO: The quota should also probably be reset somehow for every test? */
            if (mDumpDatabaseCallbacks) {
                getCurrentAdditionalTextOutput().appendExceededDbQuotaMessage(url,
                        databaseIdentifier);
            }
            quotaUpdater.updateQuota(currentQuota + 5 * 1024 * 1024);
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            getCurrentAdditionalTextOutput().appendJsAlert(message);
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            getCurrentAdditionalTextOutput().appendJsConfirm(message);
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                JsPromptResult result) {
            getCurrentAdditionalTextOutput().appendJsPrompt(message, defaultValue);
            result.confirm();
            return true;
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            getCurrentAdditionalTextOutput().appendConsoleMessage(consoleMessage);
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture,
                Message resultMsg) {
            WebView.WebViewTransport transport = (WebView.WebViewTransport)resultMsg.obj;
            /** By default windows cannot be opened, so just send null back. */
            WebView newWindowWebView = null;

            if (mCanOpenWindows) {
                /**
                 * We never display the new window, just create the view and allow it's content to
                 * execute and be recorded by the executor.
                 */
                newWindowWebView = new WebView(LayoutTestsExecutor.this);
                setupWebView(newWindowWebView);
            }

            transport.setWebView(newWindowWebView);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, Callback callback) {
            if (mSetGeolocationPermissionCalled) {
                callback.invoke(origin, mGeolocationPermission, false);
            }
        }
    };

    /** IMPLEMENTATION */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_PROGRESS);

        Intent intent = getIntent();
        mTestsList = intent.getStringArrayListExtra(EXTRA_TESTS_LIST);
        mCurrentTestIndex = intent.getIntExtra(EXTRA_TEST_INDEX, -1);
        mTotalTestCount = mCurrentTestIndex + mTestsList.size();

        PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mScreenDimLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK
                | PowerManager.ON_AFTER_RELEASE, "WakeLock in LayoutTester");
        mScreenDimLock.acquire();

        bindService(new Intent(this, ManagerService.class), mServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    private void reset() {
        WebView previousWebView = mCurrentWebView;

        resetLayoutTestController();

        mCurrentTestTimedOut = false;
        mCurrentResult = null;
        mCurrentAdditionalTextOutput = null;

        mCurrentWebView = new WebView(this);
        setupWebView(mCurrentWebView);

        mEventSender.reset(mCurrentWebView);

        setContentView(mCurrentWebView);
        if (previousWebView != null) {
            Log.d(LOG_TAG + "::reset", "previousWebView != null");
            previousWebView.destroy();
        }
    }

    private void setupWebView(WebView webView) {
        webView.setWebViewClient(mWebViewClient);
        webView.setWebChromeClient(mWebChromeClient);
        webView.addJavascriptInterface(mLayoutTestController, "layoutTestController");
        webView.addJavascriptInterface(mEventSender, "eventSender");

        /**
         * Setting a touch interval of -1 effectively disables the optimisation in WebView
         * that stops repeated touch events flooding WebCore. The Event Sender only sends a
         * single event rather than a stream of events (like what would generally happen in
         * a real use of touch events in a WebView)  and so if the WebView drops the event,
         * the test will fail as the test expects one callback for every touch it synthesizes.
         */
        webView.setTouchInterval(-1);

        WebSettings webViewSettings = webView.getSettings();
        webViewSettings.setAppCacheEnabled(true);
        webViewSettings.setAppCachePath(getApplicationContext().getCacheDir().getPath());
        webViewSettings.setAppCacheMaxSize(Long.MAX_VALUE);
        webViewSettings.setJavaScriptEnabled(true);
        webViewSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webViewSettings.setSupportMultipleWindows(true);
        webViewSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        webViewSettings.setDatabaseEnabled(true);
        webViewSettings.setDatabasePath(getDir("databases", 0).getAbsolutePath());
        webViewSettings.setDomStorageEnabled(true);
        webViewSettings.setWorkersEnabled(false);
        webViewSettings.setXSSAuditorEnabled(false);

        // This is asynchronous, but it gets processed by WebCore before it starts loading pages.
        mCurrentWebView.useMockDeviceOrientation();
    }

    private void startTests() {
        try {
            Message serviceMsg =
                    Message.obtain(null, ManagerService.MSG_FIRST_TEST);

            Bundle bundle = new Bundle();
            if (!mTestsList.isEmpty()) {
                bundle.putString("firstTest", mTestsList.get(0));
                bundle.putInt("index", mCurrentTestIndex);
            }

            serviceMsg.setData(bundle);
            mManagerServiceMessenger.send(serviceMsg);
        } catch (RemoteException e) {
            Log.e(LOG_TAG + "::startTests", e.getMessage());
        }

        runNextTest();
    }

    private void runNextTest() {
        assert mCurrentState == CurrentState.IDLE : "mCurrentState = " + mCurrentState.name();

        if (mTestsList.isEmpty()) {
            onAllTestsFinished();
            return;
        }

        mCurrentTestRelativePath = mTestsList.remove(0);
        Log.d(LOG_TAG + "::runNextTest", "Start: " + mCurrentTestRelativePath +
                "(" + mCurrentTestIndex + ")");
        mCurrentTestUri =
                Uri.fromFile(new File(TESTS_ROOT_DIR_PATH, mCurrentTestRelativePath)).toString();

        reset();

        /** Start time-out countdown and the test */
        mCurrentState = CurrentState.RENDERING_PAGE;
        mResultHandler.sendEmptyMessageDelayed(MSG_TEST_TIMED_OUT, DEFAULT_TIME_OUT_MS);
        mCurrentWebView.loadUrl(mCurrentTestUri);
    }

    private void onTestTimedOut() {
        assert mCurrentState.isRunningState() : "mCurrentState = " + mCurrentState.name();

        mCurrentTestTimedOut = true;

        /**
         * While it is theoretically possible that the test times out because
         * of webview becoming unresponsive, it is very unlikely. Therefore it's
         * assumed that obtaining results (that calls various webview methods)
         * will not itself hang.
         */
        obtainActualResultsFromWebView();
    }

    private void onTestFinished() {
        assert mCurrentState.isRunningState() : "mCurrentState = " + mCurrentState.name();

        obtainActualResultsFromWebView();
    }

    private void obtainActualResultsFromWebView() {
        /**
         * If the result has not been set by the time the test finishes we create
         * a default type of result.
         */
        if (mCurrentResult == null) {
            /** TODO: Default type should be RenderTreeResult. We don't support it now. */
            mCurrentResult = new TextResult(mCurrentTestRelativePath);
        }

        mCurrentState = CurrentState.OBTAINING_RESULT;

        mCurrentResult.obtainActualResults(mCurrentWebView,
                mResultHandler.obtainMessage(MSG_ACTUAL_RESULT_OBTAINED));
    }

    private void onActualResultsObtained() {
        assert mCurrentState == CurrentState.OBTAINING_RESULT
                : "mCurrentState = " + mCurrentState.name();

        mCurrentState = CurrentState.IDLE;

        mResultHandler.removeMessages(MSG_TEST_TIMED_OUT);
        reportResultToService();
        mCurrentTestIndex++;
        updateProgressBar();
        runNextTest();
    }

    private void reportResultToService() {
        if (mCurrentAdditionalTextOutput != null) {
            mCurrentResult.setAdditionalTextOutputString(mCurrentAdditionalTextOutput.toString());
        }

        try {
            Message serviceMsg =
                    Message.obtain(null, ManagerService.MSG_PROCESS_ACTUAL_RESULTS);

            Bundle bundle = mCurrentResult.getBundle();
            bundle.putInt("testIndex", mCurrentTestIndex);
            if (mCurrentTestTimedOut) {
                bundle.putString("resultCode", AbstractResult.ResultCode.FAIL_TIMED_OUT.name());
            }
            if (!mTestsList.isEmpty()) {
                bundle.putString("nextTest", mTestsList.get(0));
            }

            serviceMsg.setData(bundle);
            mManagerServiceMessenger.send(serviceMsg);
        } catch (RemoteException e) {
            Log.e(LOG_TAG + "::reportResultToService", e.getMessage());
        }
    }

    private void updateProgressBar() {
        getWindow().setFeatureInt(Window.FEATURE_PROGRESS,
                mCurrentTestIndex * Window.PROGRESS_END / mTotalTestCount);
        setTitle(mCurrentTestIndex * 100 / mTotalTestCount + "% " +
                "(" + mCurrentTestIndex + "/" + mTotalTestCount + ")");
    }

    private void onAllTestsFinished() {
        mScreenDimLock.release();

        try {
            Message serviceMsg =
                    Message.obtain(null, ManagerService.MSG_ALL_TESTS_FINISHED);
            mManagerServiceMessenger.send(serviceMsg);
        } catch (RemoteException e) {
            Log.e(LOG_TAG + "::onAllTestsFinished", e.getMessage());
        }

        unbindService(mServiceConnection);
    }

    private AdditionalTextOutput getCurrentAdditionalTextOutput() {
        if (mCurrentAdditionalTextOutput == null) {
            mCurrentAdditionalTextOutput = new AdditionalTextOutput();
        }
        return mCurrentAdditionalTextOutput;
    }

    /** LAYOUT TEST CONTROLLER */

    private static final int MSG_WAIT_UNTIL_DONE = 0;
    private static final int MSG_NOTIFY_DONE = 1;
    private static final int MSG_DUMP_AS_TEXT = 2;
    private static final int MSG_DUMP_CHILD_FRAMES_AS_TEXT = 3;
    private static final int MSG_SET_CAN_OPEN_WINDOWS = 4;
    private static final int MSG_DUMP_DATABASE_CALLBACKS = 5;
    private static final int MSG_SET_GEOLOCATION_PREMISSION = 6;

    Handler mLayoutTestControllerHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            assert mCurrentState.isRunningState() : "mCurrentState = " + mCurrentState.name();

            switch (msg.what) {
                case MSG_WAIT_UNTIL_DONE:
                    mCurrentState = CurrentState.WAITING_FOR_ASYNCHRONOUS_TEST;
                    break;

                case MSG_NOTIFY_DONE:
                    if (mCurrentState == CurrentState.WAITING_FOR_ASYNCHRONOUS_TEST) {
                        onTestFinished();
                    }
                    break;

                case MSG_DUMP_AS_TEXT:
                    if (mCurrentResult == null) {
                        mCurrentResult = new TextResult(mCurrentTestRelativePath);
                    }

                    assert mCurrentResult instanceof TextResult
                            : "mCurrentResult instanceof" + mCurrentResult.getClass().getName();

                    break;

                case MSG_DUMP_CHILD_FRAMES_AS_TEXT:
                    /** If dumpAsText was not called we assume that the result should be text */
                    if (mCurrentResult == null) {
                        mCurrentResult = new TextResult(mCurrentTestRelativePath);
                    }

                    assert mCurrentResult instanceof TextResult
                            : "mCurrentResult instanceof" + mCurrentResult.getClass().getName();

                    ((TextResult)mCurrentResult).setDumpChildFramesAsText(true);
                    break;

                case MSG_SET_CAN_OPEN_WINDOWS:
                    mCanOpenWindows = true;
                    break;

                case MSG_DUMP_DATABASE_CALLBACKS:
                    mDumpDatabaseCallbacks = true;
                    break;

                case MSG_SET_GEOLOCATION_PREMISSION:
                    mSetGeolocationPermissionCalled = true;
                    mGeolocationPermission = msg.arg1 == 1;
                    break;

                default:
                    Log.w(LOG_TAG + "::handleMessage", "Message code does not exist: " + msg.what);
                    break;
            }
        }
    };

    private void resetLayoutTestController() {
        mCanOpenWindows = false;
        mDumpDatabaseCallbacks = false;
        mSetGeolocationPermissionCalled = false;
        mGeolocationPermission = false;
    }

    public void waitUntilDone() {
        Log.w(LOG_TAG + "::waitUntilDone", "called");
        mLayoutTestControllerHandler.sendEmptyMessage(MSG_WAIT_UNTIL_DONE);
    }

    public void notifyDone() {
        Log.w(LOG_TAG + "::notifyDone", "called");
        mLayoutTestControllerHandler.sendEmptyMessage(MSG_NOTIFY_DONE);
    }

    public void dumpAsText(boolean enablePixelTest) {
        Log.w(LOG_TAG + "::dumpAsText(" + enablePixelTest + ")", "called");
        /** TODO: Implement */
        if (enablePixelTest) {
            Log.w(LOG_TAG + "::dumpAsText", "enablePixelTest not implemented, switching to false");
        }
        mLayoutTestControllerHandler.sendEmptyMessage(MSG_DUMP_AS_TEXT);
    }

    public void dumpChildFramesAsText() {
        Log.w(LOG_TAG + "::dumpChildFramesAsText", "called");
        mLayoutTestControllerHandler.sendEmptyMessage(MSG_DUMP_CHILD_FRAMES_AS_TEXT);
    }

    public void setCanOpenWindows() {
        Log.w(LOG_TAG + "::setCanOpenWindows", "called");
        mLayoutTestControllerHandler.sendEmptyMessage(MSG_SET_CAN_OPEN_WINDOWS);
    }

    public void dumpDatabaseCallbacks() {
        Log.w(LOG_TAG + "::dumpDatabaseCallbacks:", "called");
        mLayoutTestControllerHandler.sendEmptyMessage(MSG_DUMP_DATABASE_CALLBACKS);
    }

    public void setGeolocationPermission(boolean allow) {
        Log.w(LOG_TAG + "::setGeolocationPermission", "called");
        Message msg = mLayoutTestControllerHandler.obtainMessage(MSG_SET_GEOLOCATION_PREMISSION);
        msg.arg1 = allow ? 1 : 0;
        msg.sendToTarget();
    }

    public void setMockDeviceOrientation(boolean canProvideAlpha, double alpha,
            boolean canProvideBeta, double beta, boolean canProvideGamma, double gamma) {
        mCurrentWebView.setMockDeviceOrientation(canProvideAlpha, alpha, canProvideBeta, beta,
                canProvideGamma, gamma);
    }
}
