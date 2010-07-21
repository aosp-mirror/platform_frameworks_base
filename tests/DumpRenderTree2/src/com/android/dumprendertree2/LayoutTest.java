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
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebStorage.QuotaUpdater;

import java.io.File;

/**
 * A class that represents a single layout test. It is responsible for running the test,
 * checking its result and creating an AbstractResult object.
 */
public class LayoutTest {

    private static final String LOG_TAG = "LayoutTest";

    public static final int MSG_ACTUAL_RESULT_OBTAINED = 0;

    private String mRelativePath;
    private String mTestsRootDirPath;
    private String mUrl;
    private boolean mOnTestFinishedCalled;
    private Message mTestFinishedMsg;
    private AbstractResult mResult;

    private WebView mWebView;
    private Activity mActivity;

    private final Handler mResultHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_ACTUAL_RESULT_OBTAINED) {
                mResult.setExpectedTextResult(LayoutTestsRunnerThread.getExpectedTextResult(mRelativePath));
                mResult.setExpectedImageResult(LayoutTestsRunnerThread.getExpectedImageResult(mRelativePath));
                mTestFinishedMsg.sendToTarget();
            }
        }
    };

    private WebViewClient mWebViewClient = new WebViewClient() {
        @Override
        public void onPageFinished(WebView view, String url) {
            /** Some tests fire up many page loads, we don't want to detect them */
            if (!url.equals(mUrl)) {
                return;
            }

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

    public LayoutTest(String relativePath, String testsRootDirPath, Message testFinishedMsg,
            LayoutTestsRunner activity) {
        mRelativePath = relativePath;
        mTestsRootDirPath = testsRootDirPath;
        mTestFinishedMsg = testFinishedMsg;
        mActivity = activity;
    }

    public void run() {
        mWebView = new WebView(mActivity);
        mActivity.setContentView(mWebView);

        setupWebView();

        /** TODO: Add timeout msg */
        mUrl = Uri.fromFile(new File(mTestsRootDirPath, mRelativePath)).toString();
        mWebView.loadUrl(mUrl);
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
        if (mResult == null) {
            /** TODO: Default type should be RenderTreeResult. We don't support it now. */
            mResult = new TextResult(mRelativePath);
        }

        /** TODO: Implement waitUntilDone */

        mResult.obtainActualResult(mWebView,
                mResultHandler.obtainMessage(MSG_ACTUAL_RESULT_OBTAINED));
    }

    private void setupWebView() {
        WebSettings webViewSettings = mWebView.getSettings();
        webViewSettings.setAppCacheEnabled(true);
        webViewSettings.setAppCachePath(mActivity.getApplicationContext().getCacheDir().getPath());
        webViewSettings.setAppCacheMaxSize(Long.MAX_VALUE);
        webViewSettings.setJavaScriptEnabled(true);
        webViewSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webViewSettings.setSupportMultipleWindows(true);
        webViewSettings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        webViewSettings.setDatabaseEnabled(true);
        webViewSettings.setDatabasePath(mActivity.getDir("databases", 0).getAbsolutePath());
        webViewSettings.setDomStorageEnabled(true);
        webViewSettings.setWorkersEnabled(false);
        webViewSettings.setXSSAuditorEnabled(false);

        mWebView.setWebViewClient(mWebViewClient);
        mWebView.setWebChromeClient(mWebChromeClient);
    }

    public AbstractResult getResult() {
        return mResult;
    }

    public String getRelativePath() {
        return mRelativePath;
    }
}