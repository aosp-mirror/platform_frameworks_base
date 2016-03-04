/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.StressProvider.DEFAULT_AUTHORITY;
import static com.android.documentsui.StressProvider.STRESS_ROOT_0_ID;
import static com.android.documentsui.StressProvider.STRESS_ROOT_1_ID;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.KeyEvent;

import com.android.documentsui.model.RootInfo;
import com.android.documentsui.EventListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

@LargeTest
public class FilesActivityPerfTest extends ActivityTest<FilesActivity> {

    // Constants starting with KEY_ are used to report metrics to APCT.
    private static final String KEY_FILES_LISTED_PERFORMANCE_FIRST =
            "files-listed-performance-first";

    private static final String KEY_FILES_LISTED_PERFORMANCE_MEDIAN =
            "files-listed-performance-median";

    private static final String TESTED_URI =
            "content://com.android.documentsui.stressprovider/document/STRESS_ROOT_1_DOC";

    private static final int NUM_MEASUREMENTS = 10;

    public FilesActivityPerfTest() {
        super(FilesActivity.class);
    }

    @Override
    protected RootInfo getInitialRoot() {
        return rootDir0;
    }

    @Override
    protected String getTestingProviderAuthority() {
        return DEFAULT_AUTHORITY;
    }

    @Override
    protected void setupTestingRoots() throws RemoteException {
        rootDir0 = mDocsHelper.getRoot(STRESS_ROOT_0_ID);
        rootDir1 = mDocsHelper.getRoot(STRESS_ROOT_1_ID);
    }

    @Override
    public void initTestFiles() throws RemoteException {
        // Nothing to create, already done by StressProvider.
    }

    public void testFilesListedPerformance() throws Exception {
        final BaseActivity activity = getActivity();

        final List<Long> measurements = new ArrayList<Long>();
        EventListener listener;
        for (int i = 0; i < 10; i++) {
            final CountDownLatch signal = new CountDownLatch(1);
            listener = new EventListener() {
                @Override
                public void onDirectoryNavigated(Uri uri) {
                    if (uri != null && TESTED_URI.equals(uri.toString())) {
                        mStartTime = System.currentTimeMillis();
                    } else {
                        mStartTime = -1;
                    }
                }

                @Override
                public void onDirectoryLoaded(Uri uri) {
                    if (uri == null || !TESTED_URI.equals(uri.toString())) {
                        return;
                    }
                    assertTrue(mStartTime != -1);
                    getInstrumentation().waitForIdle(new Runnable() {
                        @Override
                        public void run() {
                            assertTrue(mStartTime != -1);
                            measurements.add(System.currentTimeMillis() - mStartTime);
                            signal.countDown();
                        }
                    });
                }

                private long mStartTime = -1;
            };

            try {
                activity.addEventListener(listener);
                bots.roots.openRoot(STRESS_ROOT_1_ID);
                signal.await();
            } finally {
                activity.removeEventListener(listener);
            }

            assertEquals(i + 1, measurements.size());

            // Go back to the empty root.
            bots.roots.openRoot(STRESS_ROOT_0_ID);
        }

        assertEquals(NUM_MEASUREMENTS, measurements.size());

        final Bundle status = new Bundle();
        status.putDouble(KEY_FILES_LISTED_PERFORMANCE_FIRST, measurements.get(0));

        final Long[] rawMeasurements = measurements.toArray(new Long[NUM_MEASUREMENTS]);
        Arrays.sort(rawMeasurements);

        final long median = rawMeasurements[NUM_MEASUREMENTS / 2 - 1];
        status.putDouble(KEY_FILES_LISTED_PERFORMANCE_MEDIAN, median);

        getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }
}
