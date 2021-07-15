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
package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD_OR_PIN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.USER_FRP;

import static org.junit.Assert.assertEquals;

import android.app.admin.DevicePolicyManager;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


/** Test setting a lockscreen credential and then verify it under USER_FRP */
@RunWith(AndroidJUnit4.class)
public class LockscreenFrpTest extends BaseLockSettingsServiceTests {

    @Before
    public void setDeviceNotProvisioned() throws Exception {
        // FRP credential can only be verified prior to provisioning
        mSettings.setDeviceProvisioned(false);
    }

    @Test
    public void testFrpCredential_setPin() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);

        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(USER_FRP));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPin("1234"), USER_FRP, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void testFrpCredential_setPattern() {
        mService.setLockCredential(newPattern("4321"), nonePassword(), PRIMARY_USER_ID);

        assertEquals(CREDENTIAL_TYPE_PATTERN, mService.getCredentialType(USER_FRP));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPattern("4321"), USER_FRP, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void testFrpCredential_setPassword() {
        mService.setLockCredential(newPassword("4321"), nonePassword(), PRIMARY_USER_ID);

        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(USER_FRP));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPassword("4321"), USER_FRP, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void testFrpCredential_changeCredential() {
        mService.setLockCredential(newPassword("1234"), nonePassword(), PRIMARY_USER_ID);
        mService.setLockCredential(newPattern("5678"), newPassword("1234"), PRIMARY_USER_ID);

        assertEquals(CREDENTIAL_TYPE_PATTERN, mService.getCredentialType(USER_FRP));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPattern("5678"), USER_FRP, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void testFrpCredential_removeCredential() {
        mService.setLockCredential(newPassword("1234"), nonePassword(), PRIMARY_USER_ID);
        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(USER_FRP));

        mService.setLockCredential(nonePassword(), newPassword("1234"), PRIMARY_USER_ID);
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(USER_FRP));
    }

    @Test
    public void testFrpCredential_cannotVerifyAfterProvsioning() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);

        mSettings.setDeviceProvisioned(true);
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR,
                mService.verifyCredential(newPin("1234"), USER_FRP, 0 /* flags */)
                        .getResponseCode());
    }

    @Test
    public void testFrpCredential_legacyPinTypePersistentData() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        PersistentData data = mStorage.readPersistentDataBlock();
        // Tweak the existing persistent data to make it look like one with legacy credential type
        assertEquals(CREDENTIAL_TYPE_PIN, data.payload[3]);
        data.payload[3] = CREDENTIAL_TYPE_PASSWORD_OR_PIN;
        mStorage.writePersistentDataBlock(data.type, data.userId,
                DevicePolicyManager.PASSWORD_QUALITY_NUMERIC, data.payload);

        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(USER_FRP));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPin("1234"), USER_FRP, 0 /* flags */)
                        .getResponseCode());

    }

    @Test
    public void testFrpCredential_legacyPasswordTypePersistentData() {
        mService.setLockCredential(newPassword("1234"), nonePassword(), PRIMARY_USER_ID);
        PersistentData data = mStorage.readPersistentDataBlock();
        // Tweak the existing persistent data to make it look like one with legacy credential type
        assertEquals(CREDENTIAL_TYPE_PASSWORD, data.payload[3]);
        data.payload[3] = CREDENTIAL_TYPE_PASSWORD_OR_PIN;
        mStorage.writePersistentDataBlock(data.type, data.userId,
                DevicePolicyManager.PASSWORD_QUALITY_COMPLEX, data.payload);

        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(USER_FRP));
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(newPin("1234"), USER_FRP, 0 /* flags */)
                        .getResponseCode());
    }
}
