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
import android.net.Uri;
import android.os.Message;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebView;

/* package */ class WebchromeClientWrapper extends WebChromeClient {

    private final WebChromeClient mDelegate;
    private final WebDriver mDriver;

    public WebchromeClientWrapper(WebChromeClient delegate, WebDriver driver) {
        if (delegate == null) {
            this.mDelegate = new WebChromeClient();
        } else {
            this.mDelegate = delegate;
        }
        this.mDriver = driver;
    }

    @Override
    public void onProgressChanged(WebView view, int newProgress) {
        if (newProgress == 100) {
            mDriver.notifyCommandDone();
        }
        mDelegate.onProgressChanged(view, newProgress);
    }

    @Override
    public void onReceivedTitle(WebView view, String title) {
        mDelegate.onReceivedTitle(view, title);
    }

    @Override
    public void onReceivedIcon(WebView view, Bitmap icon) {
        mDelegate.onReceivedIcon(view, icon);
    }

    @Override
    public void onReceivedTouchIconUrl(WebView view, String url,
            boolean precomposed) {
        mDelegate.onReceivedTouchIconUrl(view, url, precomposed);
    }

    @Override
    public void onShowCustomView(View view,
            CustomViewCallback callback) {
        mDelegate.onShowCustomView(view, callback);
    }

    @Override
    public void onHideCustomView() {
        mDelegate.onHideCustomView();
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean dialog,
            boolean userGesture, Message resultMsg) {
        return mDelegate.onCreateWindow(view, dialog, userGesture, resultMsg);
    }

    @Override
    public void onRequestFocus(WebView view) {
        mDelegate.onRequestFocus(view);
    }

    @Override
    public void onCloseWindow(WebView window) {
        mDelegate.onCloseWindow(window);
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message,
            JsResult result) {
        return mDelegate.onJsAlert(view, url, message, result);
    }

    @Override
    public boolean onJsConfirm(WebView view, String url, String message,
            JsResult result) {
        return mDelegate.onJsConfirm(view, url, message, result);
    }

    @Override
    public boolean onJsPrompt(WebView view, String url, String message,
            String defaultValue, JsPromptResult result) {
        return mDelegate.onJsPrompt(view, url, message, defaultValue, result);
    }

    @Override
    public boolean onJsBeforeUnload(WebView view, String url, String message,
            JsResult result) {
        return mDelegate.onJsBeforeUnload(view, url, message, result);
    }

    @Override
    public void onExceededDatabaseQuota(String url, String databaseIdentifier,
            long currentQuota, long estimatedSize, long totalUsedQuota,
            WebStorage.QuotaUpdater quotaUpdater) {
        mDelegate.onExceededDatabaseQuota(url, databaseIdentifier, currentQuota,
                estimatedSize, totalUsedQuota, quotaUpdater);
    }

    @Override
    public void onReachedMaxAppCacheSize(long spaceNeeded, long totalUsedQuota,
            WebStorage.QuotaUpdater quotaUpdater) {
        mDelegate.onReachedMaxAppCacheSize(spaceNeeded, totalUsedQuota,
                quotaUpdater);
    }

    @Override
    public void onGeolocationPermissionsShowPrompt(String origin,
            GeolocationPermissions.Callback callback) {
        mDelegate.onGeolocationPermissionsShowPrompt(origin, callback);
    }

    @Override
    public void onGeolocationPermissionsHidePrompt() {
        mDelegate.onGeolocationPermissionsHidePrompt();
    }

    @Override
    public boolean onJsTimeout() {
        return mDelegate.onJsTimeout();
    }

    @Override
    public void onConsoleMessage(String message, int lineNumber,
            String sourceID) {
        mDelegate.onConsoleMessage(message, lineNumber, sourceID);
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        return mDelegate.onConsoleMessage(consoleMessage);
    }

    @Override
    public Bitmap getDefaultVideoPoster() {
        return mDelegate.getDefaultVideoPoster();
    }

    @Override
    public View getVideoLoadingProgressView() {
        return mDelegate.getVideoLoadingProgressView();
    }

    @Override
    public void getVisitedHistory(ValueCallback<String[]> callback) {
        mDelegate.getVisitedHistory(callback);
    }

    @Override
    public void openFileChooser(ValueCallback<Uri> uploadFile,
            String acceptType) {
        mDelegate.openFileChooser(uploadFile, acceptType);
    }

    @Override
    public void setInstallableWebApp() {
        mDelegate.setInstallableWebApp();
    }

    @Override
    public void setupAutoFill(Message msg) {
        mDelegate.setupAutoFill(msg);
    }
}
