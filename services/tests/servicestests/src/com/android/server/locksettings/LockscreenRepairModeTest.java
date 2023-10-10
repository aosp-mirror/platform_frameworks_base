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

package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.USER_REPAIR_MODE;
import static com.android.internal.widget.LockPatternUtils.VERIFY_FLAG_WRITE_REPAIR_MODE_PW;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.app.PropertyInvalidatedCache;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockscreenRepairModeTest extends BaseLockSettingsServiceTests {

    @Before
    public void setUp() throws Exception {
        PropertyInvalidatedCache.disableForTestMode();
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
    }

    @Test
    public void verifyPin_writeRepairModePW() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());

        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                        newPin("1234"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PIN,
                mService.getCredentialType(USER_REPAIR_MODE));
    }

    @Test
    public void verifyPattern_writeRepairModePW() {
        mService.setLockCredential(newPattern("4321"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());

        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                        newPattern("4321"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                mService.getCredentialType(USER_REPAIR_MODE));
    }

    @Test
    public void verifyPassword_writeRepairModePW() {
        mService.setLockCredential(newPassword("4321"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());

        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                        newPassword("4321"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                mService.getCredentialType(USER_REPAIR_MODE));
    }

    @Test
    public void verifyCredential_writeRepairModePW_repairModeActive() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());

        setRepairModeActive(true);
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR,
                mService.verifyCredential(
                                newPin("1234"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
    }

    @Test
    public void deleteRepairModePersistentData() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                                newPin("1234"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PIN,
                mService.getCredentialType(USER_REPAIR_MODE));

        mService.deleteRepairModePersistentDataIfNeeded();
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
    }

    @Test
    public void verifyPin_userRepairMode() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                        newPin("1234"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        setRepairModeActive(true);

        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PIN,
                mService.getCredentialType(USER_REPAIR_MODE));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPin("1234"), USER_REPAIR_MODE, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void verifyPattern_userRepairMode() {
        mService.setLockCredential(newPattern("4321"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                        newPattern("4321"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        setRepairModeActive(true);

        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                mService.getCredentialType(USER_REPAIR_MODE));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPattern("4321"), USER_REPAIR_MODE, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void verifyPassword_userRepairMode() {
        mService.setLockCredential(newPassword("4321"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                        newPassword("4321"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        setRepairModeActive(true);

        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                mService.getCredentialType(USER_REPAIR_MODE));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPassword("4321"), USER_REPAIR_MODE, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void verifyCredential_userRepairMode_repairModeIsNotActive() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                                newPin("1234"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());

        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PIN,
                mService.getCredentialType(USER_REPAIR_MODE));
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR,
                mService.verifyCredential(newPin("1234"), USER_REPAIR_MODE, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void verifyCredential_userRepairMode_wrongPin() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(
                                newPin("1234"), PRIMARY_USER_ID, VERIFY_FLAG_WRITE_REPAIR_MODE_PW)
                        .getResponseCode());
        setRepairModeActive(true);

        assertEquals(LockPatternUtils.CREDENTIAL_TYPE_PIN,
                mService.getCredentialType(USER_REPAIR_MODE));
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR,
                mService.verifyCredential(newPin("5678"), USER_REPAIR_MODE, 0 /* flags */)
                        .getResponseCode());
    }

    private void setRepairModeActive(boolean active) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REPAIR_MODE_ACTIVE, active ? 1 : 0);
    }
}
