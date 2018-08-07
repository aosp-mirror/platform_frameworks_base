/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.OP_ASSIST_SCREENSHOT;
import static android.app.AppOpsManager.OP_ASSIST_STRUCTURE;
import static android.graphics.Bitmap.Config.ARGB_8888;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import android.view.IWindowManager;

import com.android.server.am.AssistDataRequester.AssistDataRequesterCallbacks;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Note: Currently, we only support fetching the screenshot for the current application, so the
 * screenshot checks are hardcoded accordingly.
 *
 * runtest --path frameworks/base/services/tests/servicestests/src/com/android/server/am/AssistDataRequesterTest.java
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class AssistDataRequesterTest extends ActivityTestsBase {

    private static final String TAG = AssistDataRequesterTest.class.getSimpleName();

    private static final boolean CURRENT_ACTIVITY_ASSIST_ALLOWED = true;
    private static final boolean CALLER_ASSIST_STRUCTURE_ALLOWED = true;
    private static final boolean CALLER_ASSIST_SCREENSHOT_ALLOWED = true;
    private static final boolean FETCH_DATA = true;
    private static final boolean FETCH_SCREENSHOTS = true;
    private static final boolean ALLOW_FETCH_DATA = true;
    private static final boolean ALLOW_FETCH_SCREENSHOTS = true;

    private static final int TEST_UID = 0;
    private static final String TEST_PACKAGE = "";

    private Context mContext;
    private AssistDataRequester mDataRequester;
    private Callbacks mCallbacks;
    private Object mCallbacksLock;
    private Handler mHandler;
    private IActivityManager mAm;
    private IWindowManager mWm;
    private AppOpsManager mAppOpsManager;

    /**
     * The requests to fetch assist data are done incrementally from the text thread, and we
     * immediately post onto the main thread handler below, which would immediately make the
     * callback and decrement the pending counts. In order to assert the pending counts, we defer
     * the callbacks on the test-side until after we flip the gate, after which we can drain the
     * main thread handler and make assertions on the actual callbacks
     */
    private CountDownLatch mGate;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mAm = mock(IActivityManager.class);
        mWm = mock(IWindowManager.class);
        mAppOpsManager = mock(AppOpsManager.class);
        mContext =  InstrumentationRegistry.getContext();
        mHandler = new Handler(Looper.getMainLooper());
        mCallbacksLock = new Object();
        mCallbacks = new Callbacks();
        mDataRequester = new AssistDataRequester(mContext, mAm, mWm, mAppOpsManager, mCallbacks,
                mCallbacksLock, OP_ASSIST_STRUCTURE, OP_ASSIST_SCREENSHOT);

        // Gate the continuation of the assist data callbacks until we are ready within the tests
        mGate = new CountDownLatch(1);
        doAnswer(invocation -> {
            mHandler.post(() -> {
                try {
                    mGate.await(10, TimeUnit.SECONDS);
                    mDataRequester.onHandleAssistData(new Bundle());
                } catch (InterruptedException e) {
                    Log.e(TAG, "Failed to wait", e);
                }
            });
            return true;
        }).when(mAm).requestAssistContextExtras(anyInt(), any(), any(), any(), anyBoolean(),
                anyBoolean());
        doAnswer(invocation -> {
            mHandler.post(() -> {
                try {
                    mGate.await(10, TimeUnit.SECONDS);
                    mDataRequester.onHandleAssistScreenshot(Bitmap.createBitmap(1, 1, ARGB_8888));
                } catch (InterruptedException e) {
                    Log.e(TAG, "Failed to wait", e);
                }
            });
            return true;
        }).when(mWm).requestAssistScreenshot(any());
    }

    private void setupMocks(boolean currentActivityAssistAllowed, boolean assistStructureAllowed,
            boolean assistScreenshotAllowed) throws Exception {
        doReturn(currentActivityAssistAllowed).when(mAm).isAssistDataAllowedOnCurrentActivity();
        doReturn(assistStructureAllowed ? MODE_ALLOWED : MODE_ERRORED).when(mAppOpsManager)
                .checkOpNoThrow(eq(OP_ASSIST_STRUCTURE), anyInt(), anyString());
        doReturn(assistScreenshotAllowed ? MODE_ALLOWED : MODE_ERRORED).when(mAppOpsManager)
                .checkOpNoThrow(eq(OP_ASSIST_SCREENSHOT), anyInt(), anyString());
    }

    @Test
    public void testRequestData() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        assertReceivedDataCount(5, 5, 1, 1);
    }

    @Test
    public void testEmptyActivities_expectNoCallbacks() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mDataRequester.requestAssistData(createActivityList(0), FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        assertReceivedDataCount(0, 0, 0, 0);
    }

    @Test
    public void testCurrentAppDisallow_expectNullCallbacks() throws Exception {
        setupMocks(!CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        assertReceivedDataCount(0, 1, 0, 1);
    }

    @Test
    public void testProcessPendingData() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mCallbacks.canHandleReceivedData = false;
        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        assertTrue(mDataRequester.getPendingDataCount() == 5);
        assertTrue(mDataRequester.getPendingScreenshotCount() == 1);
        mGate.countDown();
        waitForIdle(mHandler);

        // Callbacks still not ready to receive, but all pending data is received
        assertTrue(mDataRequester.getPendingDataCount() == 0);
        assertTrue(mDataRequester.getPendingScreenshotCount() == 0);
        assertTrue(mCallbacks.receivedData.isEmpty());
        assertTrue(mCallbacks.receivedScreenshots.isEmpty());
        assertFalse(mCallbacks.requestCompleted);

        mCallbacks.canHandleReceivedData = true;
        mDataRequester.processPendingAssistData();
        // Since we are posting the callback for the request-complete, flush the handler as well
        mGate.countDown();
        waitForIdle(mHandler);
        assertTrue(mCallbacks.receivedData.size() == 5);
        assertTrue(mCallbacks.receivedScreenshots.size() == 1);
        assertTrue(mCallbacks.requestCompleted);

        // Clear the state and ensure that we only process pending data once
        mCallbacks.reset();
        mDataRequester.processPendingAssistData();
        assertTrue(mCallbacks.receivedData.isEmpty());
        assertTrue(mCallbacks.receivedScreenshots.isEmpty());
    }

    @Test
    public void testNoFetchData_expectNoDataCallbacks() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mDataRequester.requestAssistData(createActivityList(5), !FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        assertReceivedDataCount(0, 0, 0, 1);
    }

    @Test
    public void testDisallowAssistStructure_expectNullDataCallbacks() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, !CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        // Expect a single null data when the appops is denied
        assertReceivedDataCount(0, 1, 0, 1);
    }

    @Test
    public void testDisallowAssistContextExtras_expectNullDataCallbacks() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);
        doReturn(false).when(mAm).requestAssistContextExtras(anyInt(), any(), any(), any(),
                anyBoolean(), anyBoolean());

        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        // Expect a single null data when requestAssistContextExtras() fails
        assertReceivedDataCount(0, 1, 0, 1);
    }

    @Test
    public void testNoFetchScreenshots_expectNoScreenshotCallbacks() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, !FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        assertReceivedDataCount(5, 5, 0, 0);
    }

    @Test
    public void testDisallowAssistScreenshot_expectNullScreenshotCallback() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                !CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        // Expect a single null screenshot when the appops is denied
        assertReceivedDataCount(5, 5, 0, 1);
    }

    @Test
    public void testCanNotHandleReceivedData_expectNoCallbacks() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, !CALLER_ASSIST_STRUCTURE_ALLOWED,
                !CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mCallbacks.canHandleReceivedData = false;
        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, FETCH_SCREENSHOTS,
                ALLOW_FETCH_DATA, ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        mGate.countDown();
        waitForIdle(mHandler);
        assertTrue(mCallbacks.receivedData.isEmpty());
        assertTrue(mCallbacks.receivedScreenshots.isEmpty());
    }

    @Test
    public void testRequestDataNoneAllowed_expectNullCallbacks() throws Exception {
        setupMocks(CURRENT_ACTIVITY_ASSIST_ALLOWED, CALLER_ASSIST_STRUCTURE_ALLOWED,
                CALLER_ASSIST_SCREENSHOT_ALLOWED);

        mDataRequester.requestAssistData(createActivityList(5), FETCH_DATA, FETCH_SCREENSHOTS,
                !ALLOW_FETCH_DATA, !ALLOW_FETCH_SCREENSHOTS, TEST_UID, TEST_PACKAGE);
        assertReceivedDataCount(0, 1, 0, 1);
    }

    private void assertReceivedDataCount(int numPendingData, int numReceivedData,
            int numPendingScreenshots, int numReceivedScreenshots) throws Exception {
        assertTrue("Expected " + numPendingData + " pending data, got "
                        + mDataRequester.getPendingDataCount(),
                mDataRequester.getPendingDataCount() == numPendingData);
        assertTrue("Expected " + numPendingScreenshots + " pending screenshots, got "
                        + mDataRequester.getPendingScreenshotCount(),
                mDataRequester.getPendingScreenshotCount() == numPendingScreenshots);
        assertFalse("Expected request NOT completed", mCallbacks.requestCompleted);
        mGate.countDown();
        waitForIdle(mHandler);
        assertTrue("Expected " + numReceivedData + " data, received "
                        + mCallbacks.receivedData.size(),
                mCallbacks.receivedData.size() == numReceivedData);
        assertTrue("Expected " + numReceivedScreenshots + " screenshots, received "
                        + mCallbacks.receivedScreenshots.size(),
                mCallbacks.receivedScreenshots.size() == numReceivedScreenshots);
        assertTrue("Expected request completed", mCallbacks.requestCompleted);
    }

    private List<IBinder> createActivityList(int size) {
        ArrayList<IBinder> activities = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            activities.add(mock(IBinder.class));
        }
        return activities;
    }

    public void waitForIdle(Handler h) throws Exception {
        if (Looper.myLooper() == h.getLooper()) {
            throw new RuntimeException("This method can not be called from the waiting looper");
        }
        CountDownLatch latch = new CountDownLatch(1);
        h.post(() -> latch.countDown());
        latch.await(2, TimeUnit.SECONDS);
    }

    private class Callbacks implements AssistDataRequesterCallbacks {

        boolean canHandleReceivedData = true;
        boolean requestCompleted = false;
        ArrayList<Bundle> receivedData = new ArrayList<>();
        ArrayList<Bitmap> receivedScreenshots = new ArrayList<>();

        void reset() {
            canHandleReceivedData = true;
            receivedData.clear();
            receivedScreenshots.clear();
        }

        @Override
        public boolean canHandleReceivedAssistDataLocked() {
            return canHandleReceivedData;
        }

        @Override
        public void onAssistDataReceivedLocked(Bundle data, int activityIndex, int activityCount) {
            receivedData.add(data);
        }

        @Override
        public void onAssistScreenshotReceivedLocked(Bitmap screenshot) {
            receivedScreenshots.add(screenshot);
        }

        @Override
        public void onAssistRequestCompleted() {
            mHandler.post(() -> {
                try {
                    mGate.await(10, TimeUnit.SECONDS);
                    requestCompleted = true;
                } catch (InterruptedException e) {
                    Log.e(TAG, "Failed to wait", e);
                }
            });
        }
    }
}