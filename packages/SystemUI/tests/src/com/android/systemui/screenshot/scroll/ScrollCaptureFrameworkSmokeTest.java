/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot.scroll;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Intent;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.util.Log;
import android.view.Display;
import android.view.IScrollCaptureResponseListener;
import android.view.IWindowManager;
import android.view.ScrollCaptureResponse;
import android.view.WindowManagerGlobal;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.systemui.SysuiTestCase;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests the of internal framework Scroll Capture API from SystemUI.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@Ignore
public class ScrollCaptureFrameworkSmokeTest extends SysuiTestCase {
    private static final String TAG = "ScrollCaptureFrameworkSmokeTest";
    private volatile ScrollCaptureResponse mResponse;

    /**
     * Verifies that a request traverses from SystemUI, to WindowManager and to the app process and
     * is returned without error. Device must be unlocked.
     */
    @Test
    public void testBasicOperation() throws InterruptedException {
        IWindowManager wms = WindowManagerGlobal.getWindowManagerService();

        // Start an activity to be on top that will be targeted
        InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(mContext, ScrollViewActivity.class).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK));

        final CountDownLatch latch = new CountDownLatch(1);
        try {
            wms.requestScrollCapture(Display.DEFAULT_DISPLAY, null, -1,
                    new IScrollCaptureResponseListener.Stub() {
                        @Override
                        public void onScrollCaptureResponse(
                                ScrollCaptureResponse response)
                                throws RemoteException {
                            mResponse = response;
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "request failed", e);
            fail("caught remote exception " + e);
        }
        latch.await(1000, TimeUnit.MILLISECONDS);

        assertNotNull(mResponse);
        if (!mResponse.isConnected()) {
            Log.e(TAG, "Received response with no connection: " + mResponse);
            fail("expected response.isConnected() == true");
        }
        assertTrue("expected a connection to ScrollViewActivity",
                mResponse.getWindowTitle().contains(ScrollViewActivity.class.getSimpleName()));
    }
}
