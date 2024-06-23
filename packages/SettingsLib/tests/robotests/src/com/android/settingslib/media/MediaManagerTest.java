/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.settingslib.media;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class MediaManagerTest {

    private static final String TEST_ID = "test_id";

    @Mock
    private MediaManager.MediaDeviceCallback mCallback;
    @Mock
    private MediaDevice mDevice;

    private MediaManager mMediaManager;
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = RuntimeEnvironment.application;

        when(mDevice.getId()).thenReturn(TEST_ID);

        mMediaManager = new MediaManager(mContext, null) {};
    }

    @Test
    public void dispatchDeviceListAdded_registerCallback_shouldDispatchCallback() {
        mMediaManager.registerCallback(mCallback);

        mMediaManager.dispatchDeviceListAdded();

        verify(mCallback).onDeviceListAdded(any());
    }

    @Test
    public void dispatchDeviceListRemoved_registerCallback_shouldDispatchCallback() {
        mMediaManager.registerCallback(mCallback);

        mMediaManager.dispatchDeviceListRemoved(mMediaManager.mMediaDevices);

        verify(mCallback).onDeviceListRemoved(mMediaManager.mMediaDevices);
    }

    @Test
    public void dispatchActiveDeviceChanged_registerCallback_shouldDispatchCallback() {
        mMediaManager.registerCallback(mCallback);

        mMediaManager.dispatchConnectedDeviceChanged(TEST_ID);

        verify(mCallback).onConnectedDeviceChanged(TEST_ID);
    }

    @Test
    public void findMediaDevice_idExist_shouldReturnMediaDevice() {
        mMediaManager.mMediaDevices.add(mDevice);

        final MediaDevice device = mMediaManager.findMediaDevice(TEST_ID);

        assertThat(device.getId()).isEqualTo(mDevice.getId());
    }

    @Test
    public void findMediaDevice_idNotExist_shouldReturnNull() {
        mMediaManager.mMediaDevices.add(mDevice);

        final MediaDevice device = mMediaManager.findMediaDevice("123");

        assertThat(device).isNull();
    }

    @Test
    public void dispatchOnRequestFailed_registerCallback_shouldDispatchCallback() {
        mMediaManager.registerCallback(mCallback);

        mMediaManager.dispatchOnRequestFailed(1);

        verify(mCallback).onRequestFailed(1);
    }

}
