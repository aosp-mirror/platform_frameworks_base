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

package com.android.systemui.accessibility;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.view.Display;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class WindowMagnificationTest extends SysuiTestCase {

    @Mock
    private AccessibilityManager mAccessibilityManager;
    private CommandQueue mCommandQueue;
    private WindowMagnification mWindowMagnification;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);

        mCommandQueue = new CommandQueue(getContext());
        mWindowMagnification = new WindowMagnification(getContext(),
                getContext().getMainThreadHandler(), mCommandQueue);
        mWindowMagnification.start();
    }

    @Test
    public void requestWindowMagnificationConnection_setWindowMagnificationConnection() {
        mCommandQueue.requestWindowMagnificationConnection(true);
        waitForIdleSync();

        verify(mAccessibilityManager).setWindowMagnificationConnection(any(
                IWindowMagnificationConnection.class));

        mCommandQueue.requestWindowMagnificationConnection(false);
        waitForIdleSync();

        verify(mAccessibilityManager).setWindowMagnificationConnection(null);
    }

    @Test
    public void onWindowMagnifierBoundsChanged() throws RemoteException {
        final IWindowMagnificationConnectionCallback connectionCallback = Mockito.mock(
                IWindowMagnificationConnectionCallback.class);
        final Rect testBounds = new Rect(0, 0, 500, 600);
        doAnswer(invocation -> {
            IWindowMagnificationConnection connection = invocation.getArgument(0);
            connection.setConnectionCallback(connectionCallback);
            return null;
        }).when(mAccessibilityManager).setWindowMagnificationConnection(
                any(IWindowMagnificationConnection.class));
        mCommandQueue.requestWindowMagnificationConnection(true);
        waitForIdleSync();

        mWindowMagnification.onWindowMagnifierBoundsChanged(Display.DEFAULT_DISPLAY, testBounds);

        verify(connectionCallback).onWindowMagnifierBoundsChanged(Display.DEFAULT_DISPLAY,
                testBounds);
    }
}
