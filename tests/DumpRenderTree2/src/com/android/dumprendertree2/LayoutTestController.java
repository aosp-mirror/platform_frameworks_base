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

    public void waitUntilDone() {
        mLayoutTestsExecutor.waitUntilDone();
    }

    public void notifyDone() {
        mLayoutTestsExecutor.notifyDone();
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

    public void clearAllDatabases() {
        Log.w(LOG_TAG + "::clearAllDatabases", "called");
        WebStorage.getInstance().deleteAllData();
    }

    public void setCanOpenWindows() {
        mLayoutTestsExecutor.setCanOpenWindows();
    }

    public void dumpDatabaseCallbacks() {
        mLayoutTestsExecutor.dumpDatabaseCallbacks();
    }

    public void setDatabaseQuota(long quota) {
        /** TODO: Reset this before every test! */
        Log.w(LOG_TAG + "::setDatabaseQuota", "called with: " + quota);
        WebStorage.getInstance().setQuotaForOrigin(Uri.fromFile(new File("")).toString(),
                quota);
    }
}