/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.usbtest;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.hardware.usb.flags.Flags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.usblib.UsbManagerTestLib;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link android.hardware.usb.UsbManager}.
 * Note: MUST claimed MANAGE_USB permission in Manifest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class UsbManagerApiTest {
    private Context mContext;

    private final UsbManagerTestLib mUsbManagerTestLib =
            new UsbManagerTestLib(mContext = InstrumentationRegistry.getContext());

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();
    /**
     * Verify NO SecurityException
     * Go through System Server
     */
    @Test
    public void testUsbApi_GetCurrentFunctionsSys_shouldNoSecurityException() throws Exception {
        mUsbManagerTestLib.testGetCurrentFunctionsSysEx();
    }

    /**
     * Verify NO SecurityException
     * Go through System Server
     */
    @Test
    public void testUsbApi_SetCurrentFunctionsSys_shouldNoSecurityException() throws Exception {
        mUsbManagerTestLib.testSetCurrentFunctionsSysEx(UsbManager.FUNCTION_NONE);
    }

    /**
     * Verify NO SecurityException
     * Go through Direct API, will not be denied by @RequiresPermission annotation
     */
    @Test
    public void testUsbApi_GetCurrentFunctions_shouldNoSecurityException() throws Exception {
        mUsbManagerTestLib.testGetCurrentFunctionsEx();
    }

    /**
     * Verify NO SecurityException
     * Go through Direct API, will not be denied by @RequiresPermission annotation
     */
    @Test
    public void testUsbApi_SetCurrentFunctions_shouldNoSecurityException() throws Exception {
        mUsbManagerTestLib.testSetCurrentFunctionsEx(UsbManager.FUNCTION_NONE);
    }

    /**
     * Verify API path from UsbManager to UsbService
     */
    @Test
    public void testUsbApi_GetCurrentFunctions_shouldMatched() {
        mUsbManagerTestLib.testGetCurrentFunctions_shouldMatched();
    }

    /**
     * Verify API path from UsbManager to UsbService
     */
    @Test
    public void testUsbApi_SetCurrentFunctions_shouldMatched() {
        mUsbManagerTestLib.testSetCurrentFunctions_shouldMatched();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_ACCESSORY_STREAM_API)
    public void testUsbApi_closesParcelFileDescriptorAfterAllStreamsClosed() {
        mUsbManagerTestLib.testParcelFileDescriptorClosedWhenAllOpenStreamsAreClosed();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_ACCESSORY_STREAM_API)
    public void testUsbApi_callingOpenAccessoryInputStreamTwiceThrowsException() {
        mUsbManagerTestLib.testOnlyOneOpenInputStreamAllowed();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_ACCESSORY_STREAM_API)
    public void testUsbApi_callingOpenAccessoryOutputStreamTwiceThrowsException() {
        mUsbManagerTestLib.testOnlyOneOpenOutputStreamAllowed();
    }

}
