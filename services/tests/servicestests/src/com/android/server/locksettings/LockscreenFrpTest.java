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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.PropertyInvalidatedCache;
import android.app.admin.DevicePolicyManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;

/** Tests that involve the Factory Reset Protection (FRP) credential. */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockscreenFrpTest extends BaseLockSettingsServiceTests {

    @Before
    public void setUp() throws Exception {
        PropertyInvalidatedCache.disableForTestMode();

        // FRP credential can only be verified prior to provisioning
        setDeviceProvisioned(false);

        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
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

        setDeviceProvisioned(true);
        mService.setLockCredential(nonePassword(), newPassword("1234"), PRIMARY_USER_ID);
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(USER_FRP));
    }

    @Test
    public void testFrpCredential_cannotVerifyAfterProvsioning() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);

        setDeviceProvisioned(true);
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

    // The FRP block that gets written by the current version of Android must still be accepted by
    // old versions of Android.  This test tries to detect non-forward-compatible changes in
    // PasswordData#toBytes(), which would break that.
    @Test
    public void testFrpBlock_isForwardsCompatible() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        PersistentData data = mStorage.readPersistentDataBlock();
        ByteBuffer buffer = ByteBuffer.wrap(data.payload);

        final int credentialType = buffer.getInt();
        assertEquals(CREDENTIAL_TYPE_PIN, credentialType);

        final byte scryptLogN = buffer.get();
        assertTrue(scryptLogN >= 0);

        final byte scryptLogR = buffer.get();
        assertTrue(scryptLogR >= 0);

        final byte scryptLogP = buffer.get();
        assertTrue(scryptLogP >= 0);

        final int saltLength = buffer.getInt();
        assertTrue(saltLength > 0);
        final byte[] salt = new byte[saltLength];
        buffer.get(salt);

        final int passwordHandleLength = buffer.getInt();
        assertTrue(passwordHandleLength > 0);
        final byte[] passwordHandle = new byte[passwordHandleLength];
        buffer.get(passwordHandle);
    }

    @Test
    public void testFrpBlock_inBadAndroid14FormatIsAutomaticallyFixed() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);

        // Write a "bad" FRP block with PasswordData beginning with the bytes [0, 2].
        byte[] badPasswordData = new byte[] {
                0, 2, /* version 2 */
                0, 3, /* CREDENTIAL_TYPE_PIN */
                11, /* scryptLogN */
                22, /* scryptLogR */
                33, /* scryptLogP */
                0, 0, 0, 5, /* salt.length */
                1, 2, -1, -2, 55, /* salt */
                0, 0, 0, 6, /* passwordHandle.length */
                2, 3, -2, -3, 44, 1, /* passwordHandle */
                0, 0, 0, 6, /* pinLength */
        };
        mStorage.writePersistentDataBlock(PersistentData.TYPE_SP_GATEKEEPER, PRIMARY_USER_ID, 0,
                badPasswordData);

        // Execute the code that should fix the FRP block.
        assertFalse(mStorage.getBoolean("migrated_frp2", false, 0));
        mService.migrateOldDataAfterSystemReady();
        assertTrue(mStorage.getBoolean("migrated_frp2", false, 0));

        // Verify that the FRP block has been fixed.
        PersistentData data = mStorage.readPersistentDataBlock();
        assertEquals(PersistentData.TYPE_SP_GATEKEEPER, data.type);
        ByteBuffer buffer = ByteBuffer.wrap(data.payload);
        assertEquals(CREDENTIAL_TYPE_PIN, buffer.getInt());
    }
}
