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


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.graphics.Rect;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.view.Display;
import android.view.IScrollCaptureResponseListener;
import android.view.IWindowManager;
import android.view.ScrollCaptureResponse;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.screenshot.scroll.ScrollCaptureClient.CaptureResult;
import com.android.systemui.screenshot.scroll.ScrollCaptureClient.Session;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ScrollCaptureClientTest extends SysuiTestCase {
    private static final float MAX_PAGES = 3.0f;

    private IWindowManager mWm;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mWm = mock(IWindowManager.class);
    }

    @Test
    public void testDetectAndConnect()
            throws RemoteException, InterruptedException, ExecutionException, TimeoutException {
        doAnswer((Answer<Void>) invocation -> {
            IScrollCaptureResponseListener listener = invocation.getArgument(3);
            listener.onScrollCaptureResponse(new ScrollCaptureResponse.Builder()
                    .setBoundsInWindow(new Rect(0, 0, 100, 100))
                    .setWindowBounds(new Rect(0, 0, 100, 100))
                    .setConnection(new FakeScrollCaptureConnection())
                    .build());
            return null;
        }).when(mWm).requestScrollCapture(/* displayId */ anyInt(), /* token */  isNull(),
                /* taskId */ anyInt(), any(IScrollCaptureResponseListener.class));

        // Create client
        ScrollCaptureClient client = new ScrollCaptureClient(mWm, Runnable::run, mContext);

        // Request scroll capture
        ListenableFuture<ScrollCaptureResponse> requestFuture =
                client.request(Display.DEFAULT_DISPLAY);
        assertNotNull(requestFuture.get(100, TimeUnit.MILLISECONDS));

        ScrollCaptureResponse response = requestFuture.get();
        assertTrue(response.isConnected());

        // Start a session
        ListenableFuture<Session> startFuture = client.start(response, MAX_PAGES);
        assertNotNull(startFuture.get(100, TimeUnit.MILLISECONDS));

        Session session = startFuture.get();
        Rect request = new Rect(0, 0, session.getPageWidth(), session.getTileHeight());

        // Request a tile
        ListenableFuture<CaptureResult> tileFuture = session.requestTile(0);
        assertNotNull(tileFuture.get(100, TimeUnit.MILLISECONDS));

        CaptureResult result = tileFuture.get();
        assertEquals(request, result.requested);
        assertEquals(result.requested, result.captured);
        assertNotNull(result.image);

        // End the session
        ListenableFuture<Void> endFuture = session.end();
        CountDownLatch latch = new CountDownLatch(1);
        endFuture.addListener(latch::countDown, Runnable::run);
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
    }
}
