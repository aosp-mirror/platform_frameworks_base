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

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.content.Context;
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
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link android.view.accessibility.IWindowMagnificationConnection} retrieved from
 * {@link WindowMagnification}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class IWindowMagnificationConnectionTest extends SysuiTestCase {

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private IWindowMagnificationConnectionCallback mConnectionCallback;
    @Mock
    private WindowMagnificationController mWindowMagnificationController;
    private IWindowMagnificationConnection mIWindowMagnificationConnection;
    private WindowMagnification mWindowMagnification;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().addMockSystemService(Context.ACCESSIBILITY_SERVICE, mAccessibilityManager);
        doAnswer(invocation -> {
            mIWindowMagnificationConnection = invocation.getArgument(0);
            return null;
        }).when(mAccessibilityManager).setWindowMagnificationConnection(
                any(IWindowMagnificationConnection.class));
        mWindowMagnification = new WindowMagnification(getContext(),
                getContext().getMainThreadHandler(), mCommandQueue);
        mWindowMagnification.mWindowMagnificationController = mWindowMagnificationController;
        mWindowMagnification.requestWindowMagnificationConnection(true);
        assertNotNull(mIWindowMagnificationConnection);
        mIWindowMagnificationConnection.setConnectionCallback(mConnectionCallback);
    }

    @Test
    public void enableWindowMagnification() throws RemoteException {
        mIWindowMagnificationConnection.enableWindowMagnification(TEST_DISPLAY, 3.0f, Float.NaN,
                Float.NaN);
        waitForIdleSync();

        verify(mWindowMagnificationController).enableWindowMagnification(3.0f, Float.NaN,
                Float.NaN);
    }

    @Test
    public void disableWindowMagnification_deleteWindowMagnification() throws RemoteException {
        mIWindowMagnificationConnection.enableWindowMagnification(TEST_DISPLAY, 3.0f, Float.NaN,
                Float.NaN);
        waitForIdleSync();

        mIWindowMagnificationConnection.disableWindowMagnification(TEST_DISPLAY);
        waitForIdleSync();

        verify(mWindowMagnificationController).deleteWindowMagnification();
    }

    @Test
    public void setScale() throws RemoteException {
        mIWindowMagnificationConnection.setScale(TEST_DISPLAY, 3.0f);
        waitForIdleSync();

        verify(mWindowMagnificationController).setScale(3.0f);
    }

    @Test
    public void moveWindowMagnifier() throws RemoteException {
        mIWindowMagnificationConnection.moveWindowMagnifier(TEST_DISPLAY, 100f, 200f);
        waitForIdleSync();

        verify(mWindowMagnificationController).moveWindowMagnifier(100f, 200f);
    }
}

