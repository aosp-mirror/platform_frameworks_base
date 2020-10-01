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

package com.android.pacprocessor;

import android.util.Log;
import android.webkit.PacProcessor;

/**
 * @hide
 */
public class PacWebView implements LibpacInterface {
    private static final String TAG = "PacWebView";

    private static final PacWebView sInstance = new PacWebView();
    private PacProcessor mProcessor = PacProcessor.getInstance();

    public static PacWebView getInstance() {
        return sInstance;
    }

    @Override
    public synchronized boolean setCurrentProxyScript(String script) {
        if (!mProcessor.setProxyScript(script)) {
            Log.e(TAG, "Unable to parse proxy script.");
            return false;
        }
        return true;
    }

    @Override
    public synchronized String makeProxyRequest(String url, String host) {
        return mProcessor.findProxyForUrl(url);
    }
}
