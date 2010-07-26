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
public class LayoutTestsExecuter extends Activity {

    /** TODO: make it a setting */
    static final String TESTS_ROOT_DIR_PATH =
            Environment.getExternalStorageDirectory() +
            File.separator + "android" +
            File.separator + "LayoutTests";

    private static final String LOG_TAG = "LayoutTestExecuter";

    public static final String EXTRA_TESTS_LIST = "TestsList";

    private static final int MSG_ACTUAL_RESULT_OBTAINED = 0;

    private List<String> mTestsList;
    private int mCurrentTestCount = 0;

    private WebView mCurrentWebView;
    private String mCurrentTestRelativePath;
    private String mCurrentTestUri;

    private boolean mOnTestFinishedCalled;
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
            if (msg.what == MSG_ACTUAL_RESULT_OBTAINED) {
                reportResultToService();
                runNextTest();
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

        Intent intent = getIntent();
        mTestsList = intent.getStringArrayListExtra(EXTRA_TESTS_LIST);

        bindService(new Intent(this, ManagerService.class), mServiceConnection,
                Context.BIND_AUTO_CREATE);
    }

    private void reset() {
        mOnTestFinishedCalled = false;
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
    }

    private void runNextTest() {
        if (mTestsList.isEmpty()) {
            onAllTestsFinished();
            return;
        }

        mCurrentTestCount++;
        mCurrentTestRelativePath = mTestsList.remove(0);
        mCurrentTestUri =
                Uri.fromFile(new File(TESTS_ROOT_DIR_PATH, mCurrentTestRelativePath)).toString();

        reset();
        /** TODO: Implement timeout */
        mCurrentWebView.loadUrl(mCurrentTestUri);
    }

    private void onTestFinished() {
        if (mOnTestFinishedCalled) {
            return;
        }

        mOnTestFinishedCalled = true;

        /**
         * If the result has not been set by the time the test finishes we create
         * a default type of result.
         */
        if (mCurrentResult == null) {
            /** TODO: Default type should be RenderTreeResult. We don't support it now. */
            mCurrentResult = new TextResult(mCurrentTestRelativePath);
        }

        mCurrentResult.obtainActualResults(mCurrentWebView,
                mResultHandler.obtainMessage(MSG_ACTUAL_RESULT_OBTAINED));
    }

    private void reportResultToService() {
        try {
            Message serviceMsg =
                    Message.obtain(null, ManagerService.MSG_PROCESS_ACTUAL_RESULTS);
            Bundle bundle = mCurrentResult.getBundle();
            /** TODO: Add timeout info to bundle */
            serviceMsg.setData(bundle);
            mManagerServiceMessenger.send(serviceMsg);
        } catch (RemoteException e) {
            Log.e(LOG_TAG + "::reportResultToService", e.getMessage());
        }
    }

    private void onAllTestsFinished() {
        Log.d(LOG_TAG + "::onAllTestsFisnihed", "Begin.");
    }
}