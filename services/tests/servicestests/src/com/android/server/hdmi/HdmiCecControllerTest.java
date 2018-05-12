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

import android.content.Context;
import android.hardware.hdmi.HdmiPortInfo;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import android.util.Log;
import com.android.server.hdmi.HdmiCecController.AllocateAddressCallback;
import com.android.server.hdmi.HdmiCecController.NativeWrapper;

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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link com.android.server.hdmi.HdmiCecController} class.
 */
@SmallTest
@RunWith(JUnit4.class)
public class HdmiCecControllerTest {

    private static final class NativeWrapperImpl implements NativeWrapper {

        @Override
        public long nativeInit(HdmiCecController handler, MessageQueue messageQueue) {
            return 1L;
        }

        @Override
        public int nativeSendCecCommand(long controllerPtr, int srcAddress, int dstAddress,
            byte[] body) {
            return mOccupied[srcAddress] ? 0 : 1;
        }

        @Override
        public int nativeAddLogicalAddress(long controllerPtr, int logicalAddress) {
            return 0;
        }

        @Override
        public void nativeClearLogicalAddress(long controllerPtr) {

        }

        @Override
        public int nativeGetPhysicalAddress(long controllerPtr) {
            return 0;
        }

        @Override
        public int nativeGetVersion(long controllerPtr) {
            return 0;
        }

        @Override
        public int nativeGetVendorId(long controllerPtr) {
            return 0;
        }

        @Override
        public HdmiPortInfo[] nativeGetPortInfos(long controllerPtr) {
            return new HdmiPortInfo[0];
        }

        @Override
        public void nativeSetOption(long controllerPtr, int flag, boolean enabled) {

        }

        @Override
        public void nativeSetLanguage(long controllerPtr, String language) {

        }

        @Override
        public void nativeEnableAudioReturnChannel(long controllerPtr, int port, boolean flag) {

        }

        @Override
        public boolean nativeIsConnected(long controllerPtr, int port) {
            return false;
        }
    }

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

    private static final String TAG = HdmiCecControllerTest.class.getSimpleName();
    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private static boolean[] mOccupied = new boolean[15];
    private int mLogicalAddress = 16;
    private AllocateAddressCallback mCallback = new AllocateAddressCallback() {
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
        mHdmiControlService = new MyHdmiControlService(null);
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
            mHdmiControlService, new NativeWrapperImpl());
    }

    /**
     * Tests for {@link HdmiCecController#allocateLogicalAddress}
     */
    @Test
    public void testAllocatLogicalAddress_TvDevicePreferredNotOcupied() {
        mOccupied[ADDR_TV] = false;
        mOccupied[ADDR_SPECIFIC_USE] = false;
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_TV, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_TV, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_TvDeviceNonPreferredNotOcupied() {
        mOccupied[ADDR_TV] = false;
        mOccupied[ADDR_SPECIFIC_USE] = false;
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_TV, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_TvDeviceNonPreferredFirstOcupied() {
        mOccupied[ADDR_TV] = true;
        mOccupied[ADDR_SPECIFIC_USE] = false;
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_SPECIFIC_USE, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_TvDeviceNonPreferredAllOcupied() {
        mOccupied[ADDR_TV] = true;
        mOccupied[ADDR_SPECIFIC_USE] = true;
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_AudioSystemNonPreferredNotOcupied() {
        mOccupied[ADDR_AUDIO_SYSTEM] = false;
        mHdmiCecController.allocateLogicalAddress(
            DEVICE_AUDIO_SYSTEM, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_AUDIO_SYSTEM, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_AudioSystemNonPreferredAllOcupied() {
        mOccupied[ADDR_AUDIO_SYSTEM] = true;
        mHdmiCecController.allocateLogicalAddress(
            DEVICE_AUDIO_SYSTEM, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackPreferredNotOccupied() {
        mOccupied[ADDR_PLAYBACK_1] = false;
        mOccupied[ADDR_PLAYBACK_2] = false;
        mOccupied[ADDR_PLAYBACK_3] = false;
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_1, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackPreferredOcuppied() {
        mOccupied[ADDR_PLAYBACK_1] = true;
        mOccupied[ADDR_PLAYBACK_2] = false;
        mOccupied[ADDR_PLAYBACK_3] = false;
        mHdmiCecController.allocateLogicalAddress(
            DEVICE_PLAYBACK, ADDR_PLAYBACK_1, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_2, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackNoPreferredNotOcuppied() {
        mOccupied[ADDR_PLAYBACK_1] = false;
        mOccupied[ADDR_PLAYBACK_2] = false;
        mOccupied[ADDR_PLAYBACK_3] = false;
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackNoPreferredFirstOcuppied() {
        mOccupied[ADDR_PLAYBACK_1] = true;
        mOccupied[ADDR_PLAYBACK_2] = false;
        mOccupied[ADDR_PLAYBACK_3] = false;
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_2, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackNonPreferredFirstTwoOcuppied() {
        mOccupied[ADDR_PLAYBACK_1] = true;
        mOccupied[ADDR_PLAYBACK_2] = true;
        mOccupied[ADDR_PLAYBACK_3] = false;
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_3, mLogicalAddress);
    }

    @Test
    public void testAllocatLogicalAddress_PlaybackNonPreferredAllOcupied() {
        mOccupied[ADDR_PLAYBACK_1] = true;
        mOccupied[ADDR_PLAYBACK_2] = true;
        mOccupied[ADDR_PLAYBACK_3] = true;
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }
}
