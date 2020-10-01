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

package com.android.server.usblib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.RemoteException;
import android.util.Log;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests lib for {@link android.hardware.usb.UsbManager}.
 */
public class UsbManagerTestLib {
    private static final String TAG = UsbManagerTestLib.class.getSimpleName();

    private Context mContext;

    private UsbManager mUsbManagerSys;
    private UsbManager mUsbManagerMock;
    @Mock private android.hardware.usb.IUsbManager mMockUsbService;

    public UsbManagerTestLib(Context context) {
        MockitoAnnotations.initMocks(this);
        mContext = context;

        assertNotNull(mUsbManagerSys = mContext.getSystemService(UsbManager.class));
        assertNotNull(mUsbManagerMock = new UsbManager(mContext, mMockUsbService));
    }

    private long getCurrentFunctions() {
        return mUsbManagerMock.getCurrentFunctions();
    }

    private void setCurrentFunctions(long functions) {
        mUsbManagerMock.setCurrentFunctions(functions);
    }

    private long getCurrentFunctionsSys() {
        return mUsbManagerSys.getCurrentFunctions();
    }

    private void setCurrentFunctionsSys(long functions) {
        mUsbManagerSys.setCurrentFunctions(functions);
    }

    private void testSetGetCurrentFunctions_Matched(long functions) {
        setCurrentFunctions(functions);
        assertEquals("CurrentFunctions mismatched: ", functions, getCurrentFunctions());
    }

    private void testGetCurrentFunctionsMock_Matched(long functions) {
        try {
            when(mMockUsbService.getCurrentFunctions()).thenReturn(functions);

            assertEquals("CurrentFunctions mismatched: ", functions, getCurrentFunctions());
        } catch (RemoteException remEx) {
            Log.w(TAG, "RemoteException");
        }
    }

    private void testSetCurrentFunctionsMock_Matched(long functions) {
        try {
            setCurrentFunctions(functions);

            verify(mMockUsbService).setCurrentFunctions(eq(functions));
        } catch (RemoteException remEx) {
            Log.w(TAG, "RemoteException");
        }
    }

    public void testGetCurrentFunctionsSysEx() throws Exception {
        getCurrentFunctionsSys();
    }

    public void testSetCurrentFunctionsSysEx(long functions) throws Exception {
        setCurrentFunctionsSys(functions);
    }

    public void testGetCurrentFunctionsEx() throws Exception {
        getCurrentFunctions();

        verify(mMockUsbService).getCurrentFunctions();
    }

    public void testSetCurrentFunctionsEx(long functions) throws Exception {
        setCurrentFunctions(functions);

        verify(mMockUsbService).setCurrentFunctions(eq(functions));
    }

    public void testGetCurrentFunctions_shouldMatched() {
        testGetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_NONE);
        testGetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_MTP);
        testGetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_PTP);
        testGetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_MIDI);
        testGetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_RNDIS);
    }

    public void testSetCurrentFunctions_shouldMatched() {
        testSetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_NONE);
        testSetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_MTP);
        testSetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_PTP);
        testSetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_MIDI);
        testSetCurrentFunctionsMock_Matched(UsbManager.FUNCTION_RNDIS);
    }
}
