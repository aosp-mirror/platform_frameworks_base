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

package android.webkit.webdriver;

import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/* package */ class WebViewClientWrapper extends WebViewClient {
    private final WebViewClient mDelegate;
    private final WebDriver mDriver;

    public WebViewClientWrapper(WebViewClient delegate, WebDriver driver) {
        if (delegate == null) {
            mDelegate = new WebViewClient();
        } else {
            mDelegate = delegate;
        }
        this.mDriver = driver;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return mDelegate.shouldOverrideUrlLoading(view, url);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        mDriver.notifyPageStartedLoading();
        mDelegate.onPageStarted(view, url, favicon);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        mDelegate.onPageFinished(view, url);
    }

    @Override
    public void onLoadResource(WebView view, String url) {
        mDelegate.onLoadResource(view, url);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view,
            String url) {
        return mDelegate.shouldInterceptRequest(view, url);
    }

    @Override
    public void onTooManyRedirects(WebView view, Message cancelMsg,
            Message continueMsg) {
        mDelegate.onTooManyRedirects(view, cancelMsg, continueMsg);
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description,
            String failingUrl) {
        mDelegate.onReceivedError(view, errorCode, description, failingUrl);
    }

    @Override
    public void onFormResubmission(WebView view, Message dontResend,
            Message resend) {
        mDelegate.onFormResubmission(view, dontResend, resend);
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url,
            boolean isReload) {
        mDelegate.doUpdateVisitedHistory(view, url, isReload);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler,
            SslError error) {
        mDelegate.onReceivedSslError(view, handler, error);
    }

    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
            String host, String realm) {
        mDelegate.onReceivedHttpAuthRequest(view, handler, host, realm);
    }

    @Override
    public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
        return mDelegate.shouldOverrideKeyEvent(view, event);
    }

    @Override
    public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
        mDelegate.onUnhandledKeyEvent(view, event);
    }

    @Override
    public void onScaleChanged(WebView view, float oldScale, float newScale) {
        mDelegate.onScaleChanged(view, oldScale, newScale);
    }

    @Override
    public void onReceivedLoginRequest(WebView view, String realm,
            String account, String args) {
        mDelegate.onReceivedLoginRequest(view, realm, account, args);
    }
}
