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

package com.android.systemui.biometrics;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.fingerprint.IUdfpsRefreshRateRequestCallback;
import android.os.RemoteException;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecution;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
public class UdfpsDisplayModeTest extends SysuiTestCase {
    private static final int DISPLAY_ID = 0;

    @Mock
    private AuthController mAuthController;
    @Mock
    private IUdfpsRefreshRateRequestCallback mDisplayCallback;
    @Mock
    private UdfpsLogger mUdfpsLogger;
    @Mock
    private Runnable mOnEnabled;
    @Mock
    private Runnable mOnDisabled;

    private final FakeExecution mExecution = new FakeExecution();
    private UdfpsDisplayMode mHbmController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Force mContext to always return DISPLAY_ID
        Context contextSpy = spy(mContext);
        when(contextSpy.getDisplayId()).thenReturn(DISPLAY_ID);

        // Set up mocks.
        when(mAuthController.getUdfpsRefreshRateCallback()).thenReturn(mDisplayCallback);

        // Create a real controller with mock dependencies.
        mHbmController = new UdfpsDisplayMode(contextSpy, mExecution, mAuthController,
                mUdfpsLogger);
    }

    @Test
    public void roundTrip() throws RemoteException {
        // Enable the UDFPS mode.
        mHbmController.enable(mOnEnabled);

        // Should set the appropriate refresh rate for UDFPS and notify the caller.
        verify(mDisplayCallback).onRequestEnabled(eq(DISPLAY_ID));
        verify(mOnEnabled).run();

        // Disable the UDFPS mode.
        mHbmController.disable(mOnDisabled);

        // Should unset the refresh rate and notify the caller.
        verify(mOnDisabled).run();
        verify(mDisplayCallback).onRequestDisabled(eq(DISPLAY_ID));
    }

    @Test
    public void mustNotEnableMoreThanOnce() throws RemoteException {
        // First request to enable the UDFPS mode.
        mHbmController.enable(mOnEnabled);

        // Should set the appropriate refresh rate for UDFPS and notify the caller.
        verify(mDisplayCallback).onRequestEnabled(eq(DISPLAY_ID));
        verify(mOnEnabled).run();

        // Second request to enable the UDFPS mode, while it's still enabled.
        mHbmController.enable(mOnEnabled);

        // Should ignore the second request.
        verifyNoMoreInteractions(mDisplayCallback);
        verifyNoMoreInteractions(mOnEnabled);
    }

    @Test
    public void mustNotDisableMoreThanOnce() throws RemoteException {
        // Disable the UDFPS mode.
        mHbmController.enable(mOnEnabled);

        // Should set the appropriate refresh rate for UDFPS and notify the caller.
        verify(mDisplayCallback).onRequestEnabled(eq(DISPLAY_ID));
        verify(mOnEnabled).run();

        // First request to disable the UDFPS mode.
        mHbmController.disable(mOnDisabled);

        // Should unset the refresh rate and notify the caller.
        verify(mOnDisabled).run();
        verify(mDisplayCallback).onRequestDisabled(eq(DISPLAY_ID));

        // Second request to disable the UDFPS mode, when it's already disabled.
        mHbmController.disable(mOnDisabled);

        // Should ignore the second request.
        verifyNoMoreInteractions(mOnDisabled);
        verifyNoMoreInteractions(mDisplayCallback);
    }
}
