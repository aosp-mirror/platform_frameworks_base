/*
 * Copyright (C) 2010, The Android Open Source Project
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

package com.android.frameworks.downloadmanagertests;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.test.InstrumentationTestSuite;
import android.util.Log;

import com.android.frameworks.downloadmanagertests.DownloadManagerTestApp;

import junit.framework.TestSuite;

/**
 * Instrumentation Test Runner for all download manager tests.
 *
 * To run the download manager tests:
 *
 * adb shell am instrument -e external_download_uri <uri> external_large_download_uri <uri> \
 *     -w com.android.frameworks.downloadmanagertests/.DownloadManagerTestRunner
 */

public class DownloadManagerTestRunner extends InstrumentationTestRunner {
    private static final String EXTERNAL_DOWNLOAD_URI_KEY = "external_download_uri";
    public String externalDownloadUriValue = null;

    @Override
    public TestSuite getAllTests() {
        TestSuite suite = new InstrumentationTestSuite(this);
        suite.addTestSuite(DownloadManagerTestApp.class);
        return suite;
    }

    @Override
    public ClassLoader getLoader() {
        return DownloadManagerTestRunner.class.getClassLoader();
    }

    @Override
    public void onCreate(Bundle icicle) {
        // Extract the extra params passed in from the bundle...
        String externalDownloadUri = (String) icicle.get(EXTERNAL_DOWNLOAD_URI_KEY);
        if (externalDownloadUri != null) {
            externalDownloadUriValue = externalDownloadUri;
        }
        super.onCreate(icicle);
    }

}
