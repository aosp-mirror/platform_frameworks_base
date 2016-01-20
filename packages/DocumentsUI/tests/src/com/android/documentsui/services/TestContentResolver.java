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

package com.android.documentsui.services;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.test.mock.MockContentResolver;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A test resolver that enables this test suite to listen for notifications that mark when copy
 * operations are done.
 */
class TestContentResolver extends MockContentResolver {

    private static final String TAG = "TestContextResolver";

    private CountDownLatch mReadySignal;
    private CountDownLatch mNotificationSignal;
    private Context mContext;

    public TestContentResolver(Context context) {
        mContext = context;
        mReadySignal = new CountDownLatch(1);
    }

    /**
     * Wait for the given number of files to be copied to destination. Times out after 1 sec.
     */
    public void waitForChanges(int count) throws Exception {
        // Wait for no more than 1 second by default.
        waitForChanges(count, 1000);
    }

    /**
     * Wait for files to be copied to destination.
     *
     * @param count Number of files to wait for.
     * @param timeOut Timeout in ms. TimeoutException will be thrown if this function times out.
     */
    public void waitForChanges(int count, int timeOut) throws Exception {
        mNotificationSignal = new CountDownLatch(count);
        // Signal that the test is now waiting for files.
        mReadySignal.countDown();
        if (!mNotificationSignal.await(timeOut, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timed out waiting for file operations to complete.");
        }
    }

    @Override
    public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
        // Wait until the test is ready to receive file notifications.
        try {
            mReadySignal.await();
        } catch (InterruptedException e) {
            Log.d(TAG, "Interrupted while waiting for file copy readiness");
            Thread.currentThread().interrupt();
        }
        if (DocumentsContract.isDocumentUri(mContext, uri)) {
            Log.d(TAG, "Notification: " + uri);
            // Watch for document URI change notifications - this signifies the end of a copy.
            mNotificationSignal.countDown();
        }
    }
}