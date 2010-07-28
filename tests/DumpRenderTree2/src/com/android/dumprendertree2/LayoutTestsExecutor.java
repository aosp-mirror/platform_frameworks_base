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
import android.os.RemoteException;
import android.util.Log;
import android.view.Window;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

    private List<String> mTestsList;

    /**
     * This is a number of currently running test. It is 0-based and doesn't reset after
     * the crash. Initial index is passed to LayoutTestsExecuter in the intent that starts
     * it.
     */
    private int mCurrentTestIndex;

    private int mTotalTestCount;

    private WebView mCurrentWebView;
    private String mCurrentTestRelativePath;
    private String mCurrentTestUri;
    private CurrentState mCurrentState = CurrentState.IDLE;

    private boolean mCurrentTestTimedOut;
    private AbstractResult mCurrentResult;

    /** COMMUNICATION WITH ManagerService */

    private Messenger mManagerServiceMessenger;

    private ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mManagerServiceMessenger = new Messenger(service);
            runNextTest();
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

            /** TODO: Implement waitUntilDone */
            onTestFinished();
        }
    };

    private WebChromeClient mWebChromeClient = new WebChromeClient() {
        @Override
        public void onExceededDatabaseQuota(String url, String databaseIdentifier,
                long currentQuota, long estimatedSize, long totalUsedQuota,
                QuotaUpdater quotaUpdater) {
            /** TODO: This should be recorded as part of the text result */
            quotaUpdater.updateQuota(currentQuota + 5 * 1024 * 1024);
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            /** TODO: Alerts should be recorded as part of text result */
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            /** TODO: Alerts should be recorded as part of text result */
            result.confirm();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                JsPromptResult result) {
            /** TODO: Alerts should be recorded as part of text result */
            result.confirm();
            return true;
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

        bindService(new Intent(this, ManagerService.class), mServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    private void reset() {
        WebView previousWebView = mCurrentWebView;

        mCurrentTestTimedOut = false;
        mCurrentResult = null;

        mCurrentWebView = new WebView(this);
        mCurrentWebView.setWebViewClient(mWebViewClient);
        mCurrentWebView.setWebChromeClient(mWebChromeClient);

        WebSettings webViewSettings = mCurrentWebView.getSettings();
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

        setContentView(mCurrentWebView);
        if (previousWebView != null) {
            previousWebView.destroy();
        }
    }

    private void runNextTest() {
        assert mCurrentState == CurrentState.IDLE : "mCurrentState = " + mCurrentState.name();

        if (mTestsList.isEmpty()) {
            onAllTestsFinished();
            return;
        }

        mCurrentTestRelativePath = mTestsList.remove(0);
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
        try {
            Message serviceMsg =
                    Message.obtain(null, ManagerService.MSG_PROCESS_ACTUAL_RESULTS);

            Bundle bundle = mCurrentResult.getBundle();
            bundle.putInt("testIndex", mCurrentTestIndex);
            if (mCurrentTestTimedOut) {
                bundle.putString("resultCode", AbstractResult.ResultCode.FAIL_TIMED_OUT.name());
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
        try {
            Message serviceMsg =
                    Message.obtain(null, ManagerService.MSG_ALL_TESTS_FINISHED);
            mManagerServiceMessenger.send(serviceMsg);
        } catch (RemoteException e) {
            Log.e(LOG_TAG + "::onAllTestsFinished", e.getMessage());
        }
    }
}