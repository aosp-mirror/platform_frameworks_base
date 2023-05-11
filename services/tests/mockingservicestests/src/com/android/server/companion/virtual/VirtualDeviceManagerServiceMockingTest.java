/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.virtual;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualDeviceManagerServiceMockingTest {
    private static final int UID_1 = 0;
    private static final int DEVICE_ID_1 = 42;
    private static final int DEVICE_ID_2 = 43;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getContext());

    private VirtualDeviceManagerService mVdms;
    private VirtualDeviceManagerInternal mLocalService;

    @Before
    public void setUp() {
        mVdms = new VirtualDeviceManagerService(mContext);
        mLocalService = mVdms.getLocalServiceInstance();
    }

    @Test
    public void onAuthenticationPrompt_noDevices_noCrash() {
        // This should not crash
        mLocalService.onAuthenticationPrompt(UID_1);
    }

    @Test
    public void onAuthenticationPrompt_oneDevice_showToastWhereUidIsRunningIsCalled() {
        VirtualDeviceImpl device = mock(VirtualDeviceImpl.class);
        mVdms.addVirtualDevice(device);

        mLocalService.onAuthenticationPrompt(UID_1);

        verify(device).showToastWhereUidIsRunning(eq(UID_1),
                eq(R.string.app_streaming_blocked_message_for_fingerprint_dialog), anyInt(),
                any(Looper.class));
    }

    @Test
    public void onAuthenticationPrompt_twoDevices_showToastWhereUidIsRunningIsCalledOnBoth() {
        VirtualDeviceImpl device1 = mock(VirtualDeviceImpl.class);
        VirtualDeviceImpl device2 = mock(VirtualDeviceImpl.class);
        when(device1.getDeviceId()).thenReturn(DEVICE_ID_1);
        when(device2.getDeviceId()).thenReturn(DEVICE_ID_2);
        mVdms.addVirtualDevice(device1);
        mVdms.addVirtualDevice(device2);

        mLocalService.onAuthenticationPrompt(UID_1);

        verify(device1).showToastWhereUidIsRunning(eq(UID_1),
                eq(R.string.app_streaming_blocked_message_for_fingerprint_dialog), anyInt(),
                any(Looper.class));
        verify(device2).showToastWhereUidIsRunning(eq(UID_1),
                eq(R.string.app_streaming_blocked_message_for_fingerprint_dialog), anyInt(),
                any(Looper.class));
    }
}
