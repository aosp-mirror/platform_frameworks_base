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
import com.android.server.locksettings.SyntheticPasswordManager.PasswordData;

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
                getCredentialType(mStorage.readRepairModePersistentData()));
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
                getCredentialType(mStorage.readRepairModePersistentData()));
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
                getCredentialType(mStorage.readRepairModePersistentData()));
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
                getCredentialType(mStorage.readRepairModePersistentData()));

        mService.deleteRepairModePersistentDataIfNeeded();
        assertSame(PersistentData.NONE, mStorage.readRepairModePersistentData());
    }

    private void setRepairModeActive(boolean active) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REPAIR_MODE_ACTIVE, active ? 1 : 0);
    }

    private static int getCredentialType(PersistentData persistentData) {
        if (persistentData == null || persistentData.payload == null) {
            return LockPatternUtils.CREDENTIAL_TYPE_NONE;
        }
        return PasswordData.fromBytes(persistentData.payload).credentialType;
    }
}
