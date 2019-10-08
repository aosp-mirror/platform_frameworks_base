/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.hdmi;

import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_PLAYBACK;
import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_TV;

import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_2;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_3;
import static com.android.server.hdmi.Constants.ADDR_SPECIFIC_USE;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.Constants.ADDR_UNREGISTERED;

import static junit.framework.Assert.assertEquals;

import android.content.Context;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Looper;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiCecController.AllocateAddressCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link com.android.server.hdmi.HdmiCecController} class. */
@SmallTest
@RunWith(JUnit4.class)
public class HdmiCecControllerTest {

    private FakeNativeWrapper mNativeWrapper;

    private class MyHdmiControlService extends HdmiControlService {

        MyHdmiControlService(Context context) {
            super(context);
        }

        @Override
        Looper getIoLooper() {
            return mMyLooper;
        }

        @Override
        Looper getServiceLooper() {
            return mMyLooper;
        }
    }

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private int mLogicalAddress = 16;
    private AllocateAddressCallback mCallback =
            new AllocateAddressCallback() {
                @Override
                public void onAllocated(int deviceType, int logicalAddress) {
                    mLogicalAddress = logicalAddress;
                }
            };
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();

    @Before
    public void SetUp() {
        mMyLooper = mTestLooper.getLooper();
        mMyLooper = mTestLooper.getLooper();
        mHdmiControlService = new MyHdmiControlService(InstrumentationRegistry.getTargetContext());
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController =
                HdmiCecController.createWithNativeWrapper(mHdmiControlService, mNativeWrapper);
    }

    /** Tests for {@link HdmiCecController#allocateLogicalAddress} */
    @Test
    public void testAllocatLogicalAddress_TvDevicePreferredNotOcupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_TV, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_TV, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_TvDeviceNonPreferredNotOcupied() {

        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_TV, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_TvDeviceNonPreferredFirstOcupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_SPECIFIC_USE, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_TvDeviceNonPreferredAllOcupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_SPECIFIC_USE, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_AudioSystemNonPreferredNotOcupied() {
        mHdmiCecController.allocateLogicalAddress(
                DEVICE_AUDIO_SYSTEM, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_AUDIO_SYSTEM, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_AudioSystemNonPreferredAllOcupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_AUDIO_SYSTEM, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(
                DEVICE_AUDIO_SYSTEM, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackPreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_1, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackPreferredOcuppied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_1, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_2, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackNoPreferredNotOcuppied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackNoPreferredFirstOcuppied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_2, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackNonPreferredFirstTwoOcuppied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_3, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackNonPreferredAllOcupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }
}
