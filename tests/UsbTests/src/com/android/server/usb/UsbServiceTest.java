/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.hardware.usb.UsbOperationInternal.USB_OPERATION_ERROR_INTERNAL;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.flags.Flags;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.usb.UsbService}
 */
@RunWith(AndroidJUnit4.class)
public class UsbServiceTest {

    @Mock
    private Context mContext;
    @Mock
    private UsbPortManager mUsbPortManager;
    @Mock
    private UsbAlsaManager mUsbAlsaManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private UsbSettingsManager mUsbSettingsManager;
    @Mock
    private IUsbOperationInternal mIUsbOperationInternal;

    private static final String TEST_PORT_ID = "123";

    private static final int TEST_TRANSACTION_ID = 1;

    private static final int TEST_FIRST_CALLER_ID = 1000;

    private static final int TEST_SECOND_CALLER_ID = 2000;

    private UsbService mUsbService;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mUsbService = new UsbService(mContext, mUsbPortManager, mUsbAlsaManager, mUserManager,
                mUsbSettingsManager);
    }

    /**
     * Verify enableUsbData successfully disables USB port without error
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USB_DATA_SIGNAL_STAKING)
    public void usbPort_SuccessfullyDisabled() {
        boolean enableState = false;
        when(mUsbPortManager.enableUsbData(TEST_PORT_ID, enableState, TEST_TRANSACTION_ID,
                mIUsbOperationInternal, null)).thenReturn(true);

        assertTrue(mUsbService.enableUsbDataInternal(TEST_PORT_ID, enableState,
                TEST_TRANSACTION_ID, mIUsbOperationInternal, TEST_FIRST_CALLER_ID));

        verify(mUsbPortManager, times(1)).enableUsbData(TEST_PORT_ID,
                enableState, TEST_TRANSACTION_ID, mIUsbOperationInternal, null);
        verifyZeroInteractions(mIUsbOperationInternal);
    }

    /**
     * Verify enableUsbData successfully enables USB port without error given no other stakers
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USB_DATA_SIGNAL_STAKING)
    public void usbPortWhenNoOtherStakers_SuccessfullyEnabledUsb() {
        boolean enableState = true;
        when(mUsbPortManager.enableUsbData(TEST_PORT_ID, enableState, TEST_TRANSACTION_ID,
                mIUsbOperationInternal, null))
                .thenReturn(true);

        assertTrue(mUsbService.enableUsbDataInternal(TEST_PORT_ID, enableState,
                TEST_TRANSACTION_ID, mIUsbOperationInternal, TEST_FIRST_CALLER_ID));
        verifyZeroInteractions(mIUsbOperationInternal);
    }

    /**
     * Verify enableUsbData does not enable USB port if other stakers are present
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USB_DATA_SIGNAL_STAKING)
    public void usbPortWithOtherStakers_DoesNotToEnableUsb() throws RemoteException {
        mUsbService.enableUsbDataInternal(TEST_PORT_ID, false, TEST_TRANSACTION_ID,
                mIUsbOperationInternal, TEST_FIRST_CALLER_ID);
        clearInvocations(mUsbPortManager);

        assertFalse(mUsbService.enableUsbDataInternal(TEST_PORT_ID, true,
                TEST_TRANSACTION_ID, mIUsbOperationInternal, TEST_SECOND_CALLER_ID));

        verifyZeroInteractions(mUsbPortManager);
        verify(mIUsbOperationInternal).onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
    }

    /**
     * Verify enableUsbDataWhileDockedInternal does not enable USB port if other stakers are present
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USB_DATA_SIGNAL_STAKING)
    public void enableUsbWhileDockedWhenThereAreOtherStakers_DoesNotEnableUsb()
            throws RemoteException {
        mUsbService.enableUsbDataInternal(TEST_PORT_ID, false, TEST_TRANSACTION_ID,
                mIUsbOperationInternal, TEST_FIRST_CALLER_ID);

        mUsbService.enableUsbDataWhileDockedInternal(TEST_PORT_ID, 0,
                mIUsbOperationInternal, TEST_SECOND_CALLER_ID);

        verify(mUsbPortManager, never()).enableUsbDataWhileDocked(any(),
                anyLong(), any(), any());
        verify(mIUsbOperationInternal).onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
    }

    /**
     * Verify enableUsbDataWhileDockedInternal does  enable USB port if other stakers are
     * not present
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_USB_DATA_SIGNAL_STAKING)
    public void enableUsbWhileDockedWhenThereAreNoStakers_SuccessfullyEnableUsb()
            throws RemoteException {
        mUsbService.enableUsbDataWhileDockedInternal(TEST_PORT_ID, TEST_TRANSACTION_ID,
                mIUsbOperationInternal, TEST_SECOND_CALLER_ID);

        verify(mUsbPortManager, times(1))
                .enableUsbDataWhileDocked(TEST_PORT_ID, TEST_TRANSACTION_ID,
                        mIUsbOperationInternal, null);
        verifyZeroInteractions(mIUsbOperationInternal);
    }
}
