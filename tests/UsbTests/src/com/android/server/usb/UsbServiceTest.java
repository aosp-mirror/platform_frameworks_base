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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.flags.Flags;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.flag.junit.SetFlagsRule;

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
    private IUsbOperationInternal mCallback;

    private static final String TEST_PORT_ID = "123";

    private static final int TEST_TRANSACTION_ID = 1;

    private static final int TEST_FIRST_CALLER_ID = 1000;

    private static final int TEST_SECOND_CALLER_ID = 2000;

    private UsbService mUsbService;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_USB_DATA_SIGNAL_STAKING);
        MockitoAnnotations.initMocks(this);

        when(mUsbPortManager.enableUsbData(eq(TEST_PORT_ID), anyBoolean(), eq(TEST_TRANSACTION_ID),
                eq(mCallback), any())).thenReturn(true);

        mUsbService = new UsbService(mContext, mUsbPortManager, mUsbAlsaManager,
                mUserManager, mUsbSettingsManager);
    }

    private void assertToggleUsbSuccessfully(int uid, boolean enable) {
        assertTrue(mUsbService.enableUsbDataInternal(TEST_PORT_ID, enable,
                TEST_TRANSACTION_ID, mCallback, uid));

        verify(mUsbPortManager).enableUsbData(TEST_PORT_ID,
                enable, TEST_TRANSACTION_ID, mCallback, null);
        verifyZeroInteractions(mCallback);

        clearInvocations(mUsbPortManager);
        clearInvocations(mCallback);
    }

    private void assertToggleUsbFailed(int uid, boolean enable) throws Exception {
        assertFalse(mUsbService.enableUsbDataInternal(TEST_PORT_ID, enable,
                TEST_TRANSACTION_ID, mCallback, uid));

        verifyZeroInteractions(mUsbPortManager);
        verify(mCallback).onOperationComplete(USB_OPERATION_ERROR_INTERNAL);

        clearInvocations(mUsbPortManager);
        clearInvocations(mCallback);
    }

    /**
     * Verify enableUsbData successfully disables USB port without error
     */
    @Test
    public void disableUsb_successfullyDisable() {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false);
    }

    /**
     * Verify enableUsbData successfully enables USB port without error given no other stakers
     */
    @Test
    public void enableUsbWhenNoOtherStakers_successfullyEnable() {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, true);
    }

    /**
     * Verify enableUsbData does not enable USB port if other stakers are present
     */
    @Test
    public void enableUsbPortWithOtherStakers_failsToEnable() throws Exception {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false);

        assertToggleUsbFailed(TEST_SECOND_CALLER_ID, true);
    }

    /**
     * Verify enableUsbData successfully enables USB port when the last staker is removed
     */
    @Test
    public void enableUsbByTheOnlyStaker_successfullyEnable() {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false);

        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, true);
    }

    /**
     * Verify enableUsbDataWhileDockedInternal does not enable USB port if other stakers are present
     */
    @Test
    public void enableUsbWhileDockedWhenThereAreOtherStakers_failsToEnable()
            throws RemoteException {
        assertToggleUsbSuccessfully(TEST_FIRST_CALLER_ID, false);

        mUsbService.enableUsbDataWhileDockedInternal(TEST_PORT_ID, TEST_TRANSACTION_ID,
                mCallback, TEST_SECOND_CALLER_ID);

        verifyZeroInteractions(mUsbPortManager);
        verify(mCallback).onOperationComplete(USB_OPERATION_ERROR_INTERNAL);
    }

    /**
     * Verify enableUsbDataWhileDockedInternal does enable USB port if other stakers are
     * not present
     */
    @Test
    public void enableUsbWhileDockedWhenThereAreNoStakers_SuccessfullyEnable() {
        mUsbService.enableUsbDataWhileDockedInternal(TEST_PORT_ID, TEST_TRANSACTION_ID,
                mCallback, TEST_SECOND_CALLER_ID);

        verify(mUsbPortManager).enableUsbDataWhileDocked(TEST_PORT_ID, TEST_TRANSACTION_ID,
                        mCallback, null);
        verifyZeroInteractions(mCallback);
    }
}
