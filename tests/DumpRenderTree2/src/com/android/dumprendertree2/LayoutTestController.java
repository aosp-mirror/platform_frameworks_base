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

import android.net.Uri;
import android.util.Log;
import android.webkit.MockGeolocation;
import android.webkit.WebStorage;

import java.io.File;

/**
 * A class that is registered as JS interface for webview in LayoutTestExecutor
 */
public class LayoutTestController {
    private static final String LOG_TAG = "LayoutTestController";

    LayoutTestsExecutor mLayoutTestsExecutor;

    public LayoutTestController(LayoutTestsExecutor layoutTestsExecutor) {
        mLayoutTestsExecutor = layoutTestsExecutor;
    }

    public void clearAllDatabases() {
        Log.i(LOG_TAG, "clearAllDatabases() called");
        WebStorage.getInstance().deleteAllData();
    }

    public void dumpAsText() {
        dumpAsText(false);
    }

    public void dumpAsText(boolean enablePixelTest) {
        mLayoutTestsExecutor.dumpAsText(enablePixelTest);
    }

    public void dumpChildFramesAsText() {
        mLayoutTestsExecutor.dumpChildFramesAsText();
    }

    public void dumpDatabaseCallbacks() {
        mLayoutTestsExecutor.dumpDatabaseCallbacks();
    }

    public void notifyDone() {
        mLayoutTestsExecutor.notifyDone();
    }

    public void overridePreference(String key, boolean value) {
        mLayoutTestsExecutor.overridePreference(key, value);
    }

    public void setAppCacheMaximumSize(long size) {
        Log.i(LOG_TAG, "setAppCacheMaximumSize() called with: " + size);
        android.webkit.WebStorageClassic.getInstance().setAppCacheMaximumSize(size);
    }

    public void setCanOpenWindows() {
        mLayoutTestsExecutor.setCanOpenWindows();
    }

    public void setDatabaseQuota(long quota) {
        /** TODO: Reset this before every test! */
        Log.i(LOG_TAG, "setDatabaseQuota() called with: " + quota);
        WebStorage.getInstance().setQuotaForOrigin(Uri.fromFile(new File("")).toString(),
                quota);
    }

    public void setMockGeolocationPosition(double latitude, double longitude, double accuracy) {
        Log.i(LOG_TAG, "setMockGeolocationPosition(): " + "latitude=" + latitude +
                " longitude=" + longitude + " accuracy=" + accuracy);
        mLayoutTestsExecutor.setMockGeolocationPosition(latitude, longitude, accuracy);
    }

    public void setMockGeolocationError(int code, String message) {
        Log.i(LOG_TAG, "setMockGeolocationError(): " + "code=" + code + " message=" + message);
        mLayoutTestsExecutor.setMockGeolocationError(code, message);
    }

    public void setGeolocationPermission(boolean allow) {
        mLayoutTestsExecutor.setGeolocationPermission(allow);
    }

    public void setMockDeviceOrientation(boolean canProvideAlpha, double alpha,
            boolean canProvideBeta, double beta, boolean canProvideGamma, double gamma) {
        // Configuration is in WebKit, so stay on WebCore thread, but go via LayoutTestsExecutor
        // as we need access to the Webview.
        Log.i(LOG_TAG, "setMockDeviceOrientation(" + canProvideAlpha +
                ", " + alpha + ", " + canProvideBeta + ", " + beta + ", " + canProvideGamma +
                ", " + gamma + ")");
        mLayoutTestsExecutor.setMockDeviceOrientation(
                canProvideAlpha, alpha, canProvideBeta, beta, canProvideGamma, gamma);
    }

    public void setXSSAuditorEnabled(boolean flag) {
        mLayoutTestsExecutor.setXSSAuditorEnabled(flag);
    }

    public void waitUntilDone() {
        mLayoutTestsExecutor.waitUntilDone();
    }
}
