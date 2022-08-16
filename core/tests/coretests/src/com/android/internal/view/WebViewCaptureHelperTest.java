/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.view;

import android.annotation.UiThread;
import android.app.Instrumentation;
import android.content.Context;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebView.VisualStateCallback;
import android.webkit.WebViewClient;

import androidx.test.platform.app.InstrumentationRegistry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebViewCaptureHelperTest
        extends AbsCaptureHelperTest<WebView, WebViewCaptureHelper> {

    private static final String TAG = "WebViewCaptureHelperTest";

    private WebView mWebView;

    @Override
    protected WebViewCaptureHelper createHelper() {
        return new WebViewCaptureHelper();
    }

    @UiThread
    protected void setInitialScrollPosition(WebView target, ScrollPosition position) {
        int contentHeight = (int) (target.getContentHeight() * target.getScale());
        int scrollBy = 0;
        switch (position) {
            case MIDDLE:
                scrollBy =  WINDOW_HEIGHT;
                break;
            case BOTTOM:
                scrollBy = WINDOW_HEIGHT * 2;
                break;
        }
        Log.d(TAG, "scrollToPosition: position=" + position + " contentHeight=" + contentHeight
                + " scrollBy=" + scrollBy);
        target.scrollBy(0, scrollBy);
    }

    @Override
    protected WebView createScrollableContent(ViewGroup parent) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch scaleAdjusted = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            Context mContext = parent.getContext();
            mWebView = new WebView(mContext);
            mWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    Log.d(TAG, "onPageFinished: " + url);
                }

                @Override
                public void onScaleChanged(WebView view, float oldScale, float newScale) {
                    Log.d(TAG, "onScaleChanged: oldScale=" + oldScale + " newScale=" + newScale);
                    // WebView reports 1.00125 when 1.0 is requested!?
                    if (newScale > 0.99f && newScale < 1.01f) {
                        scaleAdjusted.countDown();
                    }
                    Log.d(TAG, "scaleAdjusted: " + scaleAdjusted.getCount());
                }

                @Override
                public void onLoadResource(WebView view, String url) {
                    Log.d(TAG, "onLoadResource: " + url);
                }

                @Override
                public void onPageCommitVisible(WebView view, String url) {
                    Log.d(TAG, "onPageCommitVisible: " + url);
                }
            });

            WebSettings webSettings = mWebView.getSettings();
            webSettings.setAllowFileAccessFromFileURLs(true);
            webSettings.setAllowUniversalAccessFromFileURLs(true);

            mWebView.loadUrl("file:///android_asset/scroll_capture_test.html");
            mWebView.postVisualStateCallback(1L, new VisualStateCallback() {
                @Override
                public void onComplete(long requestId) {
                    Log.d(TAG, "VisualStateCallback::complete");
                    loaded.countDown();
                }
            });
        });

        waitFor(loaded, 5, TimeUnit.SECONDS);

        // Request a 1.0 zoom factor.
        instrumentation.runOnMainSync(() -> mWebView.zoomBy(1.0f / mWebView.getScale()));
        try {
            // Wait for the scale factor to adjust.
            //
            // WebViewClient#onScaleChanged occasionally fails to fire causing a false
            // negative test failure. WebView#getScale is not consistent across threads.
            // So no attempt can be made here to wait for or verify the scale value directly,
            // we just must wait and trust it changes to 1.0. :-(
            //
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return mWebView;
    }

    private static boolean waitFor(CountDownLatch latch, long time, TimeUnit unit) {
        try {
            return latch.await(time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
