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

package com.android.systemui.screenshot;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.view.Display;
import android.view.IScrollCaptureCallbacks;
import android.view.IWindowManager;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.screenshot.ScrollCaptureClient.CaptureResult;
import com.android.systemui.screenshot.ScrollCaptureClient.Connection;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.stubbing.Answer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ScrollCaptureClientTest extends SysuiTestCase {
    private static final float MAX_PAGES = 3f;

    private Context mContext;
    private IWindowManager mWm;

    @Spy private TestableConsumer<Session> mSessionConsumer;
    @Spy private TestableConsumer<Connection> mConnectionConsumer;
    @Spy private TestableConsumer<CaptureResult> mResultConsumer;
    @Mock private Runnable mRunnable;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        DisplayManager displayManager = requireNonNull(
                context.getSystemService(DisplayManager.class));
        mContext = context.createDisplayContext(
                displayManager.getDisplay(Display.DEFAULT_DISPLAY));
        mWm = mock(IWindowManager.class);
    }

    @Test
    public void testBasicClientFlow() throws RemoteException {
        doAnswer((Answer<Void>) invocation -> {
            IScrollCaptureCallbacks cb = invocation.getArgument(3);
            cb.onConnected(
                    new FakeScrollCaptureConnection(cb),
                    /* scrollBounds */ new Rect(0, 0, 100, 100),
                    /* positionInWindow */ new Point(0, 0));
            return null;
        }).when(mWm).requestScrollCapture(/* displayId */ anyInt(), /* token */  isNull(),
                /* taskId */ anyInt(), any(IScrollCaptureCallbacks.class));

        // Create client
        ScrollCaptureClient client = new ScrollCaptureClient(mContext, mWm);

        client.request(Display.DEFAULT_DISPLAY, mConnectionConsumer);
        verify(mConnectionConsumer, timeout(100)).accept(any(Connection.class));

        Connection conn = mConnectionConsumer.getValue();

        conn.start(mSessionConsumer, MAX_PAGES);
        verify(mSessionConsumer, timeout(100)).accept(any(Session.class));

        Session session = mSessionConsumer.getValue();
        Rect request = new Rect(0, 0, session.getPageWidth(), session.getTileHeight());

        session.requestTile(0, mResultConsumer);
        verify(mResultConsumer, timeout(100)).accept(any(CaptureResult.class));

        CaptureResult result = mResultConsumer.getValue();
        assertThat(result.requested).isEqualTo(request);
        assertThat(result.captured).isEqualTo(result.requested);
        assertThat(result.image).isNotNull();

        session.end(mRunnable);
        verify(mRunnable, timeout(100)).run();

        // TODO verify image
        // TODO test threading
        // TODO test failures
    }

}
