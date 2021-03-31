/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.uwb;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.test.suitebuilder.annotation.SmallTest;
import android.uwb.IUwbAdapter;
import android.uwb.IUwbAdapterStateCallbacks;
import android.uwb.IUwbRangingCallbacks;
import android.uwb.SessionHandle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link UwbServiceImpl}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class UwbServiceImplTest {
    @Mock private IUwbAdapter mVendorService;
    @Mock private Context mContext;
    @Mock private UwbInjector mUwbInjector;

    private UwbServiceImpl mUwbServiceImpl;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mUwbInjector.getVendorService()).thenReturn(mVendorService);
        mUwbServiceImpl = new UwbServiceImpl(mContext, mUwbInjector);
    }

    @Test
    public void testApiCallThrowsIllegalStateExceptionIfVendorServiceNotFound() throws Exception {
        when(mUwbInjector.getVendorService()).thenReturn(null);

        final IUwbAdapterStateCallbacks cb = mock(IUwbAdapterStateCallbacks.class);
        try {
            mUwbServiceImpl.registerAdapterStateCallbacks(cb);
            fail();
        } catch (IllegalStateException e) { /* pass */ }
    }

    @Test
    public void testRegisterAdapterStateCallbacks() throws Exception {
        final IUwbAdapterStateCallbacks cb = mock(IUwbAdapterStateCallbacks.class);
        mUwbServiceImpl.registerAdapterStateCallbacks(cb);

        verify(mVendorService).registerAdapterStateCallbacks(cb);
    }

    @Test
    public void testUnregisterAdapterStateCallbacks() throws Exception {
        final IUwbAdapterStateCallbacks cb = mock(IUwbAdapterStateCallbacks.class);
        mUwbServiceImpl.unregisterAdapterStateCallbacks(cb);

        verify(mVendorService).unregisterAdapterStateCallbacks(cb);
    }

    @Test
    public void testGetTimestampResolutionNanos() throws Exception {
        final long timestamp = 34L;
        when(mVendorService.getTimestampResolutionNanos()).thenReturn(timestamp);
        assertThat(mUwbServiceImpl.getTimestampResolutionNanos()).isEqualTo(timestamp);

        verify(mVendorService).getTimestampResolutionNanos();
    }

    @Test
    public void testGetSpecificationInfo() throws Exception {
        final PersistableBundle specification = new PersistableBundle();
        when(mVendorService.getSpecificationInfo()).thenReturn(specification);
        assertThat(mUwbServiceImpl.getSpecificationInfo()).isEqualTo(specification);

        verify(mVendorService).getSpecificationInfo();
    }

    @Test
    public void testOpenRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final IUwbRangingCallbacks cb = mock(IUwbRangingCallbacks.class);
        final PersistableBundle parameters = new PersistableBundle();

        mUwbServiceImpl.openRanging(sessionHandle, cb, parameters);

        verify(mVendorService).openRanging(sessionHandle, cb, parameters);
    }

    @Test
    public void testStartRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final PersistableBundle parameters = new PersistableBundle();

        mUwbServiceImpl.startRanging(sessionHandle, parameters);

        verify(mVendorService).startRanging(sessionHandle, parameters);
    }

    @Test
    public void testReconfigureRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);
        final PersistableBundle parameters = new PersistableBundle();

        mUwbServiceImpl.reconfigureRanging(sessionHandle, parameters);

        verify(mVendorService).reconfigureRanging(sessionHandle, parameters);
    }

    @Test
    public void testStopRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);

        mUwbServiceImpl.stopRanging(sessionHandle);

        verify(mVendorService).stopRanging(sessionHandle);
    }

    @Test
    public void testCloseRanging() throws Exception {
        final SessionHandle sessionHandle = new SessionHandle(5);

        mUwbServiceImpl.closeRanging(sessionHandle);

        verify(mVendorService).closeRanging(sessionHandle);
    }
}
