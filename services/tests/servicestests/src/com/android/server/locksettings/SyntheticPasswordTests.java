/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.locksettings;

import static android.content.pm.UserInfo.FLAG_FULL;
import static android.content.pm.UserInfo.FLAG_MAIN;
import static android.content.pm.UserInfo.FLAG_PRIMARY;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD_OR_PIN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.PIN_LENGTH_UNAVAILABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PropertyInvalidatedCache;
import android.app.admin.PasswordMetrics;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationResult;
import com.android.server.locksettings.SyntheticPasswordManager.PasswordData;
import com.android.server.locksettings.SyntheticPasswordManager.SyntheticPassword;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.File;

/**
 * atest FrameworksServicesTests:SyntheticPasswordTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SyntheticPasswordTests extends BaseLockSettingsServiceTests {

    public static final byte[] PAYLOAD = new byte[] {1, 2, -1, -2, 55};
    public static final byte[] PAYLOAD2 = new byte[] {2, 3, -2, -3, 44, 1};

    @Before
    public void disableProcessCaches() {
        PropertyInvalidatedCache.disableForTestMode();
    }

    @Test
    public void testNoneLskfBasedProtector() throws RemoteException {
        final int USER_ID = 10;
        MockSyntheticPasswordManager manager = new MockSyntheticPasswordManager(mContext, mStorage,
                mGateKeeperService, mUserManager, mPasswordSlotManager);
        SyntheticPassword sp = manager.newSyntheticPassword(USER_ID);
        assertFalse(lskfGatekeeperHandleExists(USER_ID));
        long protectorId = manager.createLskfBasedProtector(mGateKeeperService,
                LockscreenCredential.createNone(), sp, USER_ID);
        assertFalse(lskfGatekeeperHandleExists(USER_ID));
        assertFalse(manager.hasPasswordData(protectorId, USER_ID));
        assertFalse(manager.hasPasswordMetrics(protectorId, USER_ID));

        AuthenticationResult result = manager.unlockLskfBasedProtector(mGateKeeperService,
                protectorId, LockscreenCredential.createNone(), USER_ID, null);
        assertArrayEquals(result.syntheticPassword.deriveKeyStorePassword(),
                sp.deriveKeyStorePassword());
    }

    @Test
    public void testNonNoneLskfBasedProtector() throws RemoteException {
        final int USER_ID = 10;
        final LockscreenCredential password = newPassword("user-password");
        final LockscreenCredential badPassword = newPassword("bad-password");
        MockSyntheticPasswordManager manager = new MockSyntheticPasswordManager(mContext, mStorage,
                mGateKeeperService, mUserManager, mPasswordSlotManager);
        SyntheticPassword sp = manager.newSyntheticPassword(USER_ID);
        assertFalse(lskfGatekeeperHandleExists(USER_ID));
        long protectorId = manager.createLskfBasedProtector(mGateKeeperService, password, sp,
                USER_ID);
        assertTrue(lskfGatekeeperHandleExists(USER_ID));
        assertTrue(manager.hasPasswordData(protectorId, USER_ID));
        assertTrue(manager.hasPasswordMetrics(protectorId, USER_ID));

        AuthenticationResult result = manager.unlockLskfBasedProtector(mGateKeeperService,
                protectorId, password, USER_ID, null);
        assertArrayEquals(result.syntheticPassword.deriveKeyStorePassword(),
                sp.deriveKeyStorePassword());

        result = manager.unlockLskfBasedProtector(mGateKeeperService, protectorId, badPassword,
                USER_ID, null);
        assertNull(result.syntheticPassword);
    }

    private boolean lskfGatekeeperHandleExists(int userId) throws RemoteException {
        return mGateKeeperService.getSecureUserId(SyntheticPasswordManager.fakeUserId(userId)) != 0;
    }

    private void initSpAndSetCredential(int userId, LockscreenCredential credential)
            throws RemoteException {
        mService.initializeSyntheticPassword(userId);
        assertTrue(mService.setLockCredential(credential, nonePassword(), userId));
        assertEquals(credential.getType(), mService.getCredentialType(userId));
    }

    // Tests that the FRP credential is updated when an LSKF-based protector is created for the user
    // that owns the FRP credential, if the device is already provisioned.
    @Test
    public void testFrpCredentialSyncedIfDeviceProvisioned() throws RemoteException {
        setDeviceProvisioned(true);
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        verify(mStorage.mPersistentDataBlockManager).setFrpCredentialHandle(any());
    }

    // Tests that the FRP credential is not updated when an LSKF-based protector is created for the
    // user that owns the FRP credential, if the new credential is empty and the device is not yet
    // provisioned.
    @Test
    public void testEmptyFrpCredentialNotSyncedIfDeviceNotProvisioned() throws RemoteException {
        setDeviceProvisioned(false);
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        verify(mStorage.mPersistentDataBlockManager, never()).setFrpCredentialHandle(any());
    }

    // Tests that the FRP credential is updated when an LSKF-based protector is created for the user
    // that owns the FRP credential, if the new credential is nonempty and the device is not yet
    // provisioned.
    @Test
    public void testNonEmptyFrpCredentialSyncedIfDeviceNotProvisioned() throws RemoteException {
        setDeviceProvisioned(false);
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        verify(mStorage.mPersistentDataBlockManager, never()).setFrpCredentialHandle(any());
        mService.setLockCredential(newPassword("password"), nonePassword(), PRIMARY_USER_ID);
        verify(mStorage.mPersistentDataBlockManager).setFrpCredentialHandle(any());
    }

    @Test
    public void testChangeCredential() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential newPassword = newPassword("newpassword");

        initSpAndSetCredential(PRIMARY_USER_ID, password);
        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        mService.setLockCredential(newPassword, password, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                newPassword, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    @Test
    public void testVerifyCredential() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential badPassword = newPassword("badpassword");

        initSpAndSetCredential(PRIMARY_USER_ID, password);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        verify(mActivityManager).unlockUser2(eq(PRIMARY_USER_ID), any());

        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR, mService.verifyCredential(
                badPassword, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
    }

    @Test
    public void testClearCredential() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential badPassword = newPassword("newpassword");

        initSpAndSetCredential(PRIMARY_USER_ID, password);
        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        // clear password
        mService.setLockCredential(nonePassword(), password, PRIMARY_USER_ID);
        assertEquals(0 ,mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));

        // set a new password
        mService.setLockCredential(badPassword, nonePassword(), PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                badPassword, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertNotEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    @Test
    public void testChangeCredentialKeepsAuthSecret() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential newPassword = newPassword("newPassword");

        initSpAndSetCredential(PRIMARY_USER_ID, password);
        mService.setLockCredential(newPassword, password, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                newPassword, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());

        // Check the same secret was passed each time
        ArgumentCaptor<byte[]> secret = ArgumentCaptor.forClass(byte[].class);
        verify(mAuthSecretService, atLeastOnce()).setPrimaryUserCredential(secret.capture());
        for (byte[] val : secret.getAllValues()) {
          assertArrayEquals(val, secret.getAllValues().get(0));
        }
    }

    @Test
    public void testVerifyPassesPrimaryUserAuthSecret() throws RemoteException {
        LockscreenCredential password = newPassword("password");

        initSpAndSetCredential(PRIMARY_USER_ID, password);
        reset(mAuthSecretService);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        verify(mAuthSecretService).setPrimaryUserCredential(any(byte[].class));
    }

    @Test
    public void testSecondaryUserDoesNotPassAuthSecret() throws RemoteException {
        LockscreenCredential password = newPassword("password");

        initSpAndSetCredential(SECONDARY_USER_ID, password);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, SECONDARY_USER_ID, 0 /* flags */).getResponseCode());
        verify(mAuthSecretService, never()).setPrimaryUserCredential(any(byte[].class));
    }

    @Test
    public void testUnlockUserKeyIfUnsecuredPassesPrimaryUserAuthSecret() throws RemoteException {
        initSpAndSetCredential(PRIMARY_USER_ID, newPassword(null));
        reset(mAuthSecretService);
        mLocalService.unlockUserKeyIfUnsecured(PRIMARY_USER_ID);
        verify(mAuthSecretService).setPrimaryUserCredential(any(byte[].class));
    }

    @Test
    public void testUnlockUserKeyIfUnsecuredPassesPrimaryUserAuthSecretIfPasswordIsCleared()
            throws RemoteException {
        LockscreenCredential password = newPassword("password");
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        mService.setLockCredential(nonePassword(), password, PRIMARY_USER_ID);

        reset(mAuthSecretService);
        mLocalService.unlockUserKeyIfUnsecured(PRIMARY_USER_ID);
        verify(mAuthSecretService).setPrimaryUserCredential(any(byte[].class));
    }

    private void setupHeadlessTest() {
        mInjector.mIsHeadlessSystemUserMode = true;
        mInjector.mIsMainUserPermanentAdmin = true;
        mPrimaryUserInfo.flags &= ~(FLAG_FULL | FLAG_PRIMARY);
        mSecondaryUserInfo.flags |= FLAG_MAIN;
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(SECONDARY_USER_ID);
        mService.initializeSyntheticPassword(TERTIARY_USER_ID);
        reset(mAuthSecretService);
    }

    @Test
    public void testHeadlessSystemUserDoesNotPassAuthSecret() throws RemoteException {
        setupHeadlessTest();
        mLocalService.unlockUserKeyIfUnsecured(PRIMARY_USER_ID);
        verify(mAuthSecretService, never()).setPrimaryUserCredential(any(byte[].class));
    }

    @Test
    public void testHeadlessSecondaryUserPassesAuthSecret() throws RemoteException {
        setupHeadlessTest();
        mLocalService.unlockUserKeyIfUnsecured(SECONDARY_USER_ID);
        verify(mAuthSecretService).setPrimaryUserCredential(any(byte[].class));
    }

    @Test
    public void testHeadlessTertiaryUserPassesSameAuthSecret() throws RemoteException {
        setupHeadlessTest();
        mLocalService.unlockUserKeyIfUnsecured(SECONDARY_USER_ID);
        var captor = ArgumentCaptor.forClass(byte[].class);
        verify(mAuthSecretService).setPrimaryUserCredential(captor.capture());
        var value = captor.getValue();
        reset(mAuthSecretService);
        mLocalService.unlockUserKeyIfUnsecured(TERTIARY_USER_ID);
        verify(mAuthSecretService).setPrimaryUserCredential(eq(value));
    }

    @Test
    public void testHeadlessTertiaryUserPassesSameAuthSecretAfterReset() throws RemoteException {
        setupHeadlessTest();
        mLocalService.unlockUserKeyIfUnsecured(SECONDARY_USER_ID);
        var captor = ArgumentCaptor.forClass(byte[].class);
        verify(mAuthSecretService).setPrimaryUserCredential(captor.capture());
        var value = captor.getValue();
        mService.clearAuthSecret();
        reset(mAuthSecretService);
        mLocalService.unlockUserKeyIfUnsecured(TERTIARY_USER_ID);
        verify(mAuthSecretService).setPrimaryUserCredential(eq(value));
    }

    @Test
    public void testTokenBasedResetPassword() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential pattern = newPattern("123654");
        byte[] token = "some-high-entropy-secure-token".getBytes();
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        // Disregard any reportPasswordChanged() invocations as part of credential setup.
        flushHandlerTasks();
        reset(mDevicePolicyManager);

        byte[] storageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);

        assertFalse(mService.hasPendingEscrowToken(PRIMARY_USER_ID));
        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        assertTrue(mService.hasPendingEscrowToken(PRIMARY_USER_ID));

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        assertFalse(mService.hasPendingEscrowToken(PRIMARY_USER_ID));

        mLocalService.setLockCredentialWithToken(pattern, handle, token, PRIMARY_USER_ID);

        // Verify DPM gets notified about new device lock
        flushHandlerTasks();
        final PasswordMetrics metric = PasswordMetrics.computeForCredential(pattern);
        assertEquals(metric, mService.getUserPasswordMetrics(PRIMARY_USER_ID));
        verify(mDevicePolicyManager).reportPasswordChanged(metric, PRIMARY_USER_ID);

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                pattern, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertArrayEquals(storageKey, mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
    }

    @Test
    public void testTokenBasedClearPassword() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential pattern = newPattern("123654");
        byte[] token = "some-high-entropy-secure-token".getBytes();
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        byte[] storageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);

        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mLocalService.setLockCredentialWithToken(nonePassword(), handle, token, PRIMARY_USER_ID);
        flushHandlerTasks(); // flush the unlockUser() call before changing password again
        mLocalService.setLockCredentialWithToken(pattern, handle, token,
                PRIMARY_USER_ID);

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                pattern, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertArrayEquals(storageKey, mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
    }

    @Test
    public void testTokenBasedResetPasswordAfterCredentialChanges() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential pattern = newPattern("123654");
        LockscreenCredential newPassword = newPassword("password");
        byte[] token = "some-high-entropy-secure-token".getBytes();
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        byte[] storageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);

        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mService.setLockCredential(pattern, password, PRIMARY_USER_ID);

        mLocalService.setLockCredentialWithToken(newPassword, handle, token, PRIMARY_USER_ID);

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                newPassword, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertArrayEquals(storageKey, mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
    }

    @Test
    public void testEscrowTokenActivatedImmediatelyIfNoUserPassword() throws RemoteException {
        final byte[] token = "some-high-entropy-secure-token".getBytes();
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    @Test
    public void testEscrowTokenActivatedLaterWithUserPassword() throws RemoteException {
        byte[] token = "some-high-entropy-secure-token".getBytes();
        LockscreenCredential password = newPassword("password");

        initSpAndSetCredential(PRIMARY_USER_ID, password);
        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        // Token not activated immediately since user password exists
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        // Activate token
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        // Verify token is activated
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
    }

    @Test
    public void testEscrowTokenCannotBeActivatedOnUnmanagedUser() {
        byte[] token = "some-high-entropy-secure-token".getBytes();
        when(mDeviceStateCache.isUserOrganizationManaged(anyInt())).thenReturn(false);
        // TODO(b/258213147): Remove
        when(mUserManagerInternal.isDeviceManaged()).thenReturn(false);
        when(mUserManagerInternal.isUserManaged(PRIMARY_USER_ID)).thenReturn(false);
        when(mDeviceStateCache.isDeviceProvisioned()).thenReturn(true);

        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        try {
            mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
            fail("Escrow token should not be possible on unmanaged device");
        } catch (SecurityException expected) { }
    }

    @Test
    public void testActivateMultipleEscrowTokens() throws Exception {
        byte[] token0 = "some-high-entropy-secure-token-0".getBytes();
        byte[] token1 = "some-high-entropy-secure-token-1".getBytes();
        byte[] token2 = "some-high-entropy-secure-token-2".getBytes();

        LockscreenCredential password = newPassword("password");
        LockscreenCredential pattern = newPattern("123654");
        initSpAndSetCredential(PRIMARY_USER_ID, password);

        long handle0 = mLocalService.addEscrowToken(token0, PRIMARY_USER_ID, null);
        long handle1 = mLocalService.addEscrowToken(token1, PRIMARY_USER_ID, null);
        long handle2 = mLocalService.addEscrowToken(token2, PRIMARY_USER_ID, null);

        // Activate token
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());

        // Verify tokens work
        assertTrue(mLocalService.isEscrowTokenActive(handle0, PRIMARY_USER_ID));
        assertTrue(mLocalService.setLockCredentialWithToken(
                pattern, handle0, token0, PRIMARY_USER_ID));
        assertTrue(mLocalService.isEscrowTokenActive(handle1, PRIMARY_USER_ID));
        assertTrue(mLocalService.setLockCredentialWithToken(
                pattern, handle1, token1, PRIMARY_USER_ID));
        assertTrue(mLocalService.isEscrowTokenActive(handle2, PRIMARY_USER_ID));
        assertTrue(mLocalService.setLockCredentialWithToken(
                pattern, handle2, token2, PRIMARY_USER_ID));
    }

    @Test
    public void testSetLockCredentialWithTokenFailsWithoutLockScreen() throws Exception {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential pattern = newPattern("123654");
        byte[] token = "some-high-entropy-secure-token".getBytes();

        mService.mHasSecureLockScreen = false;
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        try {
            mLocalService.setLockCredentialWithToken(password, handle, token, PRIMARY_USER_ID);
            fail("An exception should have been thrown.");
        } catch (UnsupportedOperationException e) {
            // Success - the exception was expected.
        }
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(PRIMARY_USER_ID));

        try {
            mLocalService.setLockCredentialWithToken(pattern, handle, token, PRIMARY_USER_ID);
            fail("An exception should have been thrown.");
        } catch (UnsupportedOperationException e) {
            // Success - the exception was expected.
        }
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(PRIMARY_USER_ID));
    }

    @Test
    public void testGetHashFactorPrimaryUser() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        byte[] hashFactor = mService.getHashFactor(password, PRIMARY_USER_ID);
        assertNotNull(hashFactor);

        mService.setLockCredential(nonePassword(), password, PRIMARY_USER_ID);
        byte[] newHashFactor = mService.getHashFactor(nonePassword(), PRIMARY_USER_ID);
        assertNotNull(newHashFactor);
        // Hash factor should never change after password change/removal
        assertArrayEquals(hashFactor, newHashFactor);
    }

    @Test
    public void testGetHashFactorManagedProfileUnifiedChallenge() throws RemoteException {
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);

        LockscreenCredential pattern = newPattern("1236");
        mService.setLockCredential(pattern, nonePassword(), PRIMARY_USER_ID);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        assertNotNull(mService.getHashFactor(null, MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testGetHashFactorManagedProfileSeparateChallenge() throws RemoteException {
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);

        LockscreenCredential primaryPassword = newPassword("primary");
        LockscreenCredential profilePassword = newPassword("profile");
        mService.setLockCredential(primaryPassword, nonePassword(), PRIMARY_USER_ID);
        mService.setLockCredential(profilePassword, nonePassword(), MANAGED_PROFILE_USER_ID);
        assertNotNull(
                mService.getHashFactor(profilePassword, MANAGED_PROFILE_USER_ID));
    }

    // Tests stretching of a nonempty LSKF.
    @Test
    public void testStretchLskf_enabled() {
        byte[] actual = mSpManager.stretchLskf(newPin("12345"), createTestPasswordData());
        String expected = "467986710DE8F0D4F4A3668DFF58C9B7E5DB96A79B7CCF415BBD4D7767F8CFFA";
        assertEquals(expected, HexEncoding.encodeToString(actual));
    }

    // Tests the case where stretching is disabled for an empty LSKF.
    @Test
    public void testStretchLskf_disabled() {
        byte[] actual = mSpManager.stretchLskf(nonePassword(), null);
        // "default-password", zero padded
        String expected = "64656661756C742D70617373776F726400000000000000000000000000000000";
        assertEquals(expected, HexEncoding.encodeToString(actual));
    }

    // Tests the legacy case where stretching is enabled for an empty LSKF.
    @Test
    public void testStretchLskf_emptyButEnabled() {
        byte[] actual = mSpManager.stretchLskf(nonePassword(), createTestPasswordData());
        String expected = "9E6DDCC1EC388BB1E1CD54097AF924CA80BCB90993196FA8F6122FF58EB333DE";
        assertEquals(expected, HexEncoding.encodeToString(actual));
    }

    // Tests the forbidden case where stretching is disabled for a nonempty LSKF.
    @Test
    public void testStretchLskf_nonEmptyButDisabled() {
        assertThrows(IllegalArgumentException.class,
                () -> mSpManager.stretchLskf(newPin("12345"), null));
    }

    private PasswordData createTestPasswordData() {
        PasswordData data = new PasswordData();
        // For the unit test, the scrypt parameters have to be constant; the salt can't be random.
        data.scryptLogN = 11;
        data.scryptLogR = 3;
        data.scryptLogP = 1;
        data.pinLength = 5;
        data.salt = "abcdefghijklmnop".getBytes();
        return data;
    }

    @Test
    public void testPasswordDataLatestVersion_serializeDeserialize() {
        PasswordData data = new PasswordData();
        data.scryptLogN = 11;
        data.scryptLogR = 22;
        data.scryptLogP = 33;
        data.credentialType = CREDENTIAL_TYPE_PASSWORD;
        data.salt = PAYLOAD;
        data.passwordHandle = PAYLOAD2;
        data.pinLength = 5;

        PasswordData deserialized = PasswordData.fromBytes(data.toBytes());
        assertEquals(11, deserialized.scryptLogN);
        assertEquals(22, deserialized.scryptLogR);
        assertEquals(33, deserialized.scryptLogP);
        assertEquals(5, deserialized.pinLength);
        assertEquals(CREDENTIAL_TYPE_PASSWORD, deserialized.credentialType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
    }

    @Test
    public void testStorePinLengthOnDisk() {
        int userId = 1;
        LockscreenCredential lockscreenCredentialPin = LockscreenCredential.createPin("123456");
        MockSyntheticPasswordManager manager = new MockSyntheticPasswordManager(mContext, mStorage,
                mGateKeeperService, mUserManager, mPasswordSlotManager);
        SyntheticPassword sp = manager.newSyntheticPassword(userId);
        long protectorId = manager.createLskfBasedProtector(mGateKeeperService,
                lockscreenCredentialPin, sp,
                userId);
        PasswordMetrics passwordMetrics =
                PasswordMetrics.computeForCredential(lockscreenCredentialPin);
        boolean result = manager.refreshPinLengthOnDisk(passwordMetrics, protectorId, userId);

        assertEquals(manager.getPinLength(protectorId, userId), lockscreenCredentialPin.size());
        assertTrue(result);
    }

    @Test
    public void testDeserializePasswordData_forPinWithLengthAvailable() {
        byte[] serialized = new byte[] {
                0, 0, 0, 3, /* CREDENTIAL_TYPE_PIN */
                11, /* scryptLogN */
                22, /* scryptLogR */
                33, /* scryptLogP */
                0, 0, 0, 5, /* salt.length */
                1, 2, -1, -2, 55, /* salt */
                0, 0, 0, 6, /* passwordHandle.length */
                2, 3, -2, -3, 44, 1, /* passwordHandle */
                0, 0, 0, 6, /* pinLength */
        };
        assertFalse(PasswordData.isBadFormatFromAndroid14Beta(serialized));
        PasswordData deserialized = PasswordData.fromBytes(serialized);

        assertEquals(11, deserialized.scryptLogN);
        assertEquals(22, deserialized.scryptLogR);
        assertEquals(33, deserialized.scryptLogP);
        assertEquals(CREDENTIAL_TYPE_PIN, deserialized.credentialType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
        assertEquals(6, deserialized.pinLength);
    }

    @Test
    public void testDeserializePasswordData_forPinWithLengthExplicitlyUnavailable() {
        byte[] serialized = new byte[] {
                0, 0, 0, 3, /* CREDENTIAL_TYPE_PIN */
                11, /* scryptLogN */
                22, /* scryptLogR */
                33, /* scryptLogP */
                0, 0, 0, 5, /* salt.length */
                1, 2, -1, -2, 55, /* salt */
                0, 0, 0, 6, /* passwordHandle.length */
                2, 3, -2, -3, 44, 1, /* passwordHandle */
                -1, -1, -1, -1, /* pinLength */
        };
        PasswordData deserialized = PasswordData.fromBytes(serialized);

        assertEquals(11, deserialized.scryptLogN);
        assertEquals(22, deserialized.scryptLogR);
        assertEquals(33, deserialized.scryptLogP);
        assertEquals(CREDENTIAL_TYPE_PIN, deserialized.credentialType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
        assertEquals(PIN_LENGTH_UNAVAILABLE, deserialized.pinLength);
    }

    @Test
    public void testDeserializePasswordData_forPinWithVersionNumber() {
        // Test deserializing a PasswordData that has a version number in the first two bytes.
        // Files like this were created by some Android 14 beta versions.  This version number was a
        // mistake and should be ignored by the deserializer.
        byte[] serialized = new byte[] {
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
        assertTrue(PasswordData.isBadFormatFromAndroid14Beta(serialized));
        PasswordData deserialized = PasswordData.fromBytes(serialized);

        assertEquals(11, deserialized.scryptLogN);
        assertEquals(22, deserialized.scryptLogR);
        assertEquals(33, deserialized.scryptLogP);
        assertEquals(CREDENTIAL_TYPE_PIN, deserialized.credentialType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
        assertEquals(6, deserialized.pinLength);
    }

    @Test
    public void testDeserializePasswordData_forNoneCred() {
        // Test that a PasswordData that uses CREDENTIAL_TYPE_NONE and lacks the PIN length field
        // can be deserialized.  Files like this were created by Android 13 and earlier.  Android 14
        // and later no longer create PasswordData for CREDENTIAL_TYPE_NONE.
        byte[] serialized = new byte[] {
                -1, -1, -1, -1, /* CREDENTIAL_TYPE_NONE */
                11, /* scryptLogN */
                22, /* scryptLogR */
                33, /* scryptLogP */
                0, 0, 0, 5, /* salt.length */
                1, 2, -1, -2, 55, /* salt */
                0, 0, 0, 6, /* passwordHandle.length */
                2, 3, -2, -3, 44, 1, /* passwordHandle */
        };
        PasswordData deserialized = PasswordData.fromBytes(serialized);

        assertEquals(11, deserialized.scryptLogN);
        assertEquals(22, deserialized.scryptLogR);
        assertEquals(33, deserialized.scryptLogP);
        assertEquals(CREDENTIAL_TYPE_NONE, deserialized.credentialType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
        assertEquals(PIN_LENGTH_UNAVAILABLE, deserialized.pinLength);
    }

    @Test
    public void testDeserializePasswordData_forPasswordOrPin() {
        // Test that a PasswordData that uses CREDENTIAL_TYPE_PASSWORD_OR_PIN and lacks the PIN
        // length field can be deserialized.  Files like this were created by Android 10 and
        // earlier.  Android 11 eliminated CREDENTIAL_TYPE_PASSWORD_OR_PIN.
        byte[] serialized = new byte[] {
                0, 0, 0, 2, /* CREDENTIAL_TYPE_PASSWORD_OR_PIN */
                11, /* scryptLogN */
                22, /* scryptLogR */
                33, /* scryptLogP */
                0, 0, 0, 5, /* salt.length */
                1, 2, -1, -2, 55, /* salt */
                0, 0, 0, 6, /* passwordHandle.length */
                2, 3, -2, -3, 44, 1, /* passwordHandle */
        };
        PasswordData deserialized = PasswordData.fromBytes(serialized);

        assertEquals(11, deserialized.scryptLogN);
        assertEquals(22, deserialized.scryptLogR);
        assertEquals(33, deserialized.scryptLogP);
        assertEquals(CREDENTIAL_TYPE_PASSWORD_OR_PIN, deserialized.credentialType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
        assertEquals(PIN_LENGTH_UNAVAILABLE, deserialized.pinLength);
    }

    @Test
    public void testGsiDisablesAuthSecret() throws RemoteException {
        mGsiService.setIsGsiRunning(true);

        LockscreenCredential password = newPassword("testGsiDisablesAuthSecret-password");

        initSpAndSetCredential(PRIMARY_USER_ID, password);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        verify(mAuthSecretService, never()).setPrimaryUserCredential(any(byte[].class));
    }

    @Test
    public void testUnlockUserWithToken() throws Exception {
        LockscreenCredential password = newPassword("password");
        byte[] token = "some-high-entropy-secure-token".getBytes();
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        // Disregard any reportPasswordChanged() invocations as part of credential setup.
        flushHandlerTasks();
        reset(mDevicePolicyManager);

        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mService.onCleanupUser(PRIMARY_USER_ID);
        assertNull(mLocalService.getUserPasswordMetrics(PRIMARY_USER_ID));

        assertTrue(mLocalService.unlockUserWithToken(handle, token, PRIMARY_USER_ID));
        assertEquals(PasswordMetrics.computeForCredential(password),
                mLocalService.getUserPasswordMetrics(PRIMARY_USER_ID));
    }

    @Test
    public void testPasswordChange_NoOrphanedFilesLeft() throws Exception {
        LockscreenCredential password = newPassword("password");
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        assertTrue(mService.setLockCredential(password, password, PRIMARY_USER_ID));
        assertNoOrphanedFilesLeft(PRIMARY_USER_ID);
    }

    @Test
    public void testAddingEscrowToken_NoOrphanedFilesLeft() throws Exception {
        final byte[] token = "some-high-entropy-secure-token".getBytes();
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        for (int i = 0; i < 16; i++) {
            long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
            assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
            mLocalService.removeEscrowToken(handle, PRIMARY_USER_ID);
        }
        assertNoOrphanedFilesLeft(PRIMARY_USER_ID);
    }

    private void assertNoOrphanedFilesLeft(int userId) {
        String lskfProtectorPrefix = String.format("%016x",
                mService.getCurrentLskfBasedProtectorId(userId));
        File directory = mStorage.getSyntheticPasswordDirectoryForUser(userId);
        for (File file : directory.listFiles()) {
            String[] parts = file.getName().split("\\.");
            if (!parts[0].equals(lskfProtectorPrefix) && !parts[0].equals("0000000000000000")) {
                fail("Orphaned state left: " + file.getName());
            }
        }
    }

    @Test
    public void testHexEncodingIsUppercase() {
        final byte[] raw = new byte[] { (byte)0xAB, (byte)0xCD, (byte)0xEF };
        final byte[] expected = new byte[] { 'A', 'B', 'C', 'D', 'E', 'F' };
        assertArrayEquals(expected, SyntheticPasswordManager.bytesToHex(raw));
    }

    // b/62213311
    //TODO: add non-migration work profile case, and unify/un-unify transition.
    //TODO: test token after user resets password
    //TODO: test token based reset after unified work challenge
    //TODO: test clear password after unified work challenge
}
