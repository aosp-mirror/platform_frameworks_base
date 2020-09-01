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

package com.android.server.usb;

import android.content.Context;
import android.hardware.usb.UsbManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.android.server.usblib.UsbManagerTestLib;

/**
 * Unit tests for {@link android.hardware.usb.UsbManager}.
 * Note: NOT claimed MANAGE_USB permission in Manifest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class UsbManagerNoPermTest {
    private Context mContext;

    private final UsbManagerTestLib mUsbManagerTestLib =
            new UsbManagerTestLib(mContext = InstrumentationRegistry.getContext());

    /**
     * Verify SecurityException resulting from required permissions missing
     * Go through System Server
     */
    @Test(expected = SecurityException.class)
    public void testUsbApi_GetCurrentFunctionsSys_OnSecurityException() throws Exception {
        mUsbManagerTestLib.testGetCurrentFunctionsSysEx();
    }

    /**
     * Verify SecurityException resulting from required permissions missing
     * Go through System Server
     */
    @Test(expected = SecurityException.class)
    public void testUsbApi_SetCurrentFunctionsSys_OnSecurityException() throws Exception {
        mUsbManagerTestLib.testSetCurrentFunctionsSysEx(UsbManager.FUNCTION_NONE);
    }

    /**
     * Verify SecurityException resulting from required permissions missing
     * Go through Direct API, will not be denied by @RequiresPermission annotation
     */
    @Test(expected = SecurityException.class)
    @Ignore
    public void testUsbApi_GetCurrentFunctions_OnSecurityException() throws Exception {
        mUsbManagerTestLib.testGetCurrentFunctionsEx();
    }

    /**
     * Verify SecurityException resulting from required permissions missing
     * Go through Direct API, will not be denied by @RequiresPermission annotation
     */
    @Test(expected = SecurityException.class)
    @Ignore
    public void testUsbApi_SetCurrentFunctions_OnSecurityException() throws Exception {
        mUsbManagerTestLib.testSetCurrentFunctionsEx(UsbManager.FUNCTION_NONE);
    }
}
