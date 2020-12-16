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
import static com.android.server.hdmi.Constants.ADDR_BACKUP_1;
import static com.android.server.hdmi.Constants.ADDR_BACKUP_2;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_2;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_3;
import static com.android.server.hdmi.Constants.ADDR_SPECIFIC_USE;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.Constants.ADDR_UNREGISTERED;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiCecController.AllocateAddressCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link com.android.server.hdmi.HdmiCecController} class. */
@SmallTest
@Presubmit
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

        @Override
        int getCecVersion() {
            return mCecVersion;
        }
    }

    private HdmiCecController mHdmiCecController;
    private int mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_1_4_b;
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
        HdmiControlService hdmiControlService = new MyHdmiControlService(
                InstrumentationRegistry.getTargetContext());
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                hdmiControlService, mNativeWrapper, hdmiControlService.getAtomWriter());
    }

    /** Tests for {@link HdmiCecController#allocateLogicalAddress} */
    @Test
    public void testAllocateLogicalAddress_TvDevicePreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_TV, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_TV, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_TvDeviceNonPreferredNotOccupied() {

        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_TV, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_TvDeviceNonPreferredFirstOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_SPECIFIC_USE, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_TvDeviceNonPreferredAllOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_SPECIFIC_USE, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_AudioSystemNonPreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(
                DEVICE_AUDIO_SYSTEM, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_AUDIO_SYSTEM, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_AudioSystemNonPreferredAllOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_AUDIO_SYSTEM, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(
                DEVICE_AUDIO_SYSTEM, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackPreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_1, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackPreferredOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_1, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_2, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNoPreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNoPreferredFirstOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_2, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferredFirstTwoOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_3, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferredAllOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferred_2_0_BackupOne() {
        mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;

        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_BACKUP_1, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferred_2_0_BackupTwo() {
        mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;

        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_BACKUP_1, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_BACKUP_2, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackPreferredOccupiedDedicatedBelowAvailable() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_2, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackPreferredOccupiedDedicatedAboveAvailable() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_2, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_3, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferred_2_0_AllOccupied() {
        mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;

        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_BACKUP_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_BACKUP_2, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testIsLanguage() {
        assertTrue(HdmiCecController.isLanguage("en"));
        assertTrue(HdmiCecController.isLanguage("eng"));
        assertTrue(HdmiCecController.isLanguage("ger"));
        assertTrue(HdmiCecController.isLanguage("zh"));
        assertTrue(HdmiCecController.isLanguage("zhi"));
        assertTrue(HdmiCecController.isLanguage("zho"));

        assertFalse(HdmiCecController.isLanguage(null));
        assertFalse(HdmiCecController.isLanguage(""));
        assertFalse(HdmiCecController.isLanguage("e"));
        assertFalse(HdmiCecController.isLanguage("一")); // language code must be ASCII
    }
}
