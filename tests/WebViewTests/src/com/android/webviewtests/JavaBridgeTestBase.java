/*
 * Copyright (C) 2011 The Android Open Source Project
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

/**
 * Common functionality for testing the WebView's Java Bridge.
 */

package com.android.webviewtests;

import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import junit.framework.Assert;

public class JavaBridgeTestBase extends ActivityInstrumentationTestCase2<WebViewStubActivity> {
    protected class TestWebViewClient extends WebViewClient {
        private boolean mIsPageFinished;
        @Override
        public synchronized void onPageFinished(WebView webView, String url) {
            mIsPageFinished = true;
            notify();
        }
        public synchronized void waitForOnPageFinished() throws RuntimeException {
            while (!mIsPageFinished) {
                try {
                    wait(5000);
                } catch (Exception e) {
                    continue;
                }
                if (!mIsPageFinished) {
                    throw new RuntimeException("Timed out waiting for onPageFinished()");
                }
            }
            mIsPageFinished = false;
        }
    }

    protected class Controller {
        private boolean mIsResultReady;

        protected synchronized void notifyResultIsReady() {
            mIsResultReady = true;
            notify();
        }
        protected synchronized void waitForResult() {
            while (!mIsResultReady) {
                try {
                    wait(5000);
                } catch (Exception e) {
                    continue;
                }
                if (!mIsResultReady) {
                    Assert.fail("Wait timed out");
                }
            }
            mIsResultReady = false;
        }
    }

    protected TestWebViewClient mWebViewClient;

    public JavaBridgeTestBase() {
        super(WebViewStubActivity.class);
    }

    // Sets up the WebView and injects the supplied object. Intended to be called from setUp().
    protected void setUpWebView(final Object object, final String name) throws Exception {
        mWebViewClient = new TestWebViewClient();
        // This starts the activity, so must be called on the test thread.
        final WebViewStubActivity activity = getActivity();
        // On the UI thread, load an empty page and wait for it to finish
        // loading so that the Java object is injected.
        try {
            runTestOnUiThread(new Runnable() {
                @Override
                public void run() {
                    WebView webView = activity.getWebView();
                    webView.addJavascriptInterface(object, name);
                    webView.getSettings().setJavaScriptEnabled(true);
                    webView.setWebViewClient(mWebViewClient);
                    webView.loadData("<!DOCTYPE html><title></title>", "text/html", null);
                }
            });
            mWebViewClient.waitForOnPageFinished();
        } catch (Throwable e) {
            throw new RuntimeException("Failed to set up WebView: " + Log.getStackTraceString(e));
        }
    }

    protected void executeJavaScript(final String script) throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getWebView().loadUrl("javascript:" + script);
            }
        });
    }

    protected WebView getWebView() {
        return getActivity().getWebView();
    }
}
