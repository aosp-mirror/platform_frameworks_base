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

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD_OR_PIN;
import static com.android.internal.widget.LockPatternUtils.SYNTHETIC_PASSWORD_ENABLED_KEY;
import static com.android.internal.widget.LockPatternUtils.SYNTHETIC_PASSWORD_HANDLE_KEY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.PasswordMetrics;
import android.app.PropertyInvalidatedCache;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationResult;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationToken;
import com.android.server.locksettings.SyntheticPasswordManager.PasswordData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.ArrayList;


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
    public void testPasswordBasedSyntheticPassword() throws RemoteException {
        final int USER_ID = 10;
        final LockscreenCredential password = newPassword("user-password");
        final LockscreenCredential badPassword = newPassword("bad-password");
        MockSyntheticPasswordManager manager = new MockSyntheticPasswordManager(mContext, mStorage,
                mGateKeeperService, mUserManager, mPasswordSlotManager);
        AuthenticationToken authToken = manager.newSyntheticPasswordAndSid(mGateKeeperService, null,
                null, USER_ID);
        long handle = manager.createPasswordBasedSyntheticPassword(mGateKeeperService,
                password, authToken, USER_ID);

        AuthenticationResult result = manager.unwrapPasswordBasedSyntheticPassword(
                mGateKeeperService, handle, password, USER_ID, null);
        assertArrayEquals(result.authToken.deriveKeyStorePassword(),
                authToken.deriveKeyStorePassword());

        result = manager.unwrapPasswordBasedSyntheticPassword(mGateKeeperService, handle,
                badPassword, USER_ID, null);
        assertNull(result.authToken);
    }

    private void disableSyntheticPassword() throws RemoteException {
        mService.setLong(SYNTHETIC_PASSWORD_ENABLED_KEY, 0, UserHandle.USER_SYSTEM);
    }

    private void enableSyntheticPassword() throws RemoteException {
        mService.setLong(SYNTHETIC_PASSWORD_ENABLED_KEY, 1, UserHandle.USER_SYSTEM);
    }

    private boolean hasSyntheticPassword(int userId) throws RemoteException {
        return mService.getLong(SYNTHETIC_PASSWORD_HANDLE_KEY, 0, userId) != 0;
    }

    protected void initializeCredentialUnderSP(LockscreenCredential password, int userId)
            throws RemoteException {
        enableSyntheticPassword();
        assertTrue(mService.setLockCredential(password, nonePassword(), userId));
        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(userId));
        assertTrue(mService.isSyntheticPasswordBasedCredential(userId));
    }

    @Test
    public void testSyntheticPasswordChangeCredential() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential newPassword = newPassword("newpassword");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        mService.setLockCredential(newPassword, password, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                newPassword, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        assertEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    @Test
    public void testSyntheticPasswordVerifyCredential() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential badPassword = newPassword("badpassword");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());

        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR, mService.verifyCredential(
                badPassword, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
    }

    @Test
    public void testSyntheticPasswordClearCredential() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential badPassword = newPassword("newpassword");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
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
    public void testSyntheticPasswordChangeCredentialKeepsAuthSecret() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential badPassword = newPassword("new");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        mService.setLockCredential(badPassword, password, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                badPassword, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());

        // Check the same secret was passed each time
        ArgumentCaptor<ArrayList<Byte>> secret = ArgumentCaptor.forClass(ArrayList.class);
        verify(mAuthSecretService, atLeastOnce()).primaryUserCredential(secret.capture());
        assertEquals(1, secret.getAllValues().stream().distinct().count());
    }

    @Test
    public void testSyntheticPasswordVerifyPassesPrimaryUserAuthSecret() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential newPassword = newPassword("new");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        reset(mAuthSecretService);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        verify(mAuthSecretService).primaryUserCredential(any(ArrayList.class));
    }

    @Test
    public void testSecondaryUserDoesNotPassAuthSecret() throws RemoteException {
        LockscreenCredential password = newPassword("password");

        initializeCredentialUnderSP(password, SECONDARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, SECONDARY_USER_ID, 0 /* flags */).getResponseCode());
        verify(mAuthSecretService, never()).primaryUserCredential(any(ArrayList.class));
    }

    @Test
    public void testNoSyntheticPasswordOrCredentialDoesNotPassAuthSecret() throws RemoteException {
        mService.onUnlockUser(PRIMARY_USER_ID);
        flushHandlerTasks();
        verify(mAuthSecretService, never()).primaryUserCredential(any(ArrayList.class));
    }

    @Test
    public void testSyntheticPasswordAndCredentialDoesNotPassAuthSecret() throws RemoteException {
        LockscreenCredential password = newPassword("passwordForASyntheticPassword");
        initializeCredentialUnderSP(password, PRIMARY_USER_ID);

        reset(mAuthSecretService);
        mService.onUnlockUser(PRIMARY_USER_ID);
        flushHandlerTasks();
        verify(mAuthSecretService, never()).primaryUserCredential(any(ArrayList.class));
    }

    @Test
    public void testSyntheticPasswordButNoCredentialPassesAuthSecret() throws RemoteException {
        LockscreenCredential password = newPassword("getASyntheticPassword");
        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        mService.setLockCredential(nonePassword(), password, PRIMARY_USER_ID);

        reset(mAuthSecretService);
        mService.onUnlockUser(PRIMARY_USER_ID);
        flushHandlerTasks();
        verify(mAuthSecretService).primaryUserCredential(any(ArrayList.class));
    }

    @Test
    public void testTokenBasedResetPassword() throws RemoteException {
        LockscreenCredential password = newPassword("password");
        LockscreenCredential pattern = newPattern("123654");
        byte[] token = "some-high-entropy-secure-token".getBytes();
        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
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
        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
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
        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
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
    public void testEscrowTokenActivatedImmediatelyIfNoUserPasswordNeedsMigration()
            throws RemoteException {
        final byte[] token = "some-high-entropy-secure-token".getBytes();
        enableSyntheticPassword();
        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertTrue(hasSyntheticPassword(PRIMARY_USER_ID));
    }

    @Test
    public void testEscrowTokenActivatedImmediatelyIfNoUserPasswordNoMigration()
            throws RemoteException {
        final byte[] token = "some-high-entropy-secure-token".getBytes();
        // By first setting a password and then clearing it, we enter the state where SP is
        // initialized but the user currently has no password
        initializeCredentialUnderSP(newPassword("password"), PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(nonePassword(), newPassword("password"),
                PRIMARY_USER_ID));
        assertTrue(mService.isSyntheticPasswordBasedCredential(PRIMARY_USER_ID));

        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertTrue(hasSyntheticPassword(PRIMARY_USER_ID));
    }

    @Test
    public void testEscrowTokenActivatedLaterWithUserPasswordNeedsMigration()
            throws RemoteException {
        byte[] token = "some-high-entropy-secure-token".getBytes();
        LockscreenCredential password = newPassword("password");
        // Set up pre-SP user password
        disableSyntheticPassword();
        mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID);
        enableSyntheticPassword();

        long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
        // Token not activated immediately since user password exists
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        // Activate token (password gets migrated to SP at the same time)
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        // Verify token is activated
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
    }

    @Test
    public void testEscrowTokenCannotBeActivatedOnUnmanagedUser() {
        byte[] token = "some-high-entropy-secure-token".getBytes();
        when(mUserManagerInternal.isDeviceManaged()).thenReturn(false);
        when(mUserManagerInternal.isUserManaged(PRIMARY_USER_ID)).thenReturn(false);
        when(mDeviceStateCache.isDeviceProvisioned()).thenReturn(true);

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
        initializeCredentialUnderSP(password, PRIMARY_USER_ID);

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
        enableSyntheticPassword();
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
        mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID);
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
        LockscreenCredential pattern = newPattern("1236");
        mService.setLockCredential(pattern, nonePassword(), PRIMARY_USER_ID);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        assertNotNull(mService.getHashFactor(null, MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testGetHashFactorManagedProfileSeparateChallenge() throws RemoteException {
        LockscreenCredential primaryPassword = newPassword("primary");
        LockscreenCredential profilePassword = newPassword("profile");
        mService.setLockCredential(primaryPassword, nonePassword(), PRIMARY_USER_ID);
        mService.setLockCredential(profilePassword, nonePassword(), MANAGED_PROFILE_USER_ID);
        assertNotNull(
                mService.getHashFactor(profilePassword, MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testPasswordData_serializeDeserialize() {
        PasswordData data = new PasswordData();
        data.scryptLogN = 11;
        data.scryptLogR = 22;
        data.scryptLogP = 33;
        data.credentialType = CREDENTIAL_TYPE_PASSWORD;
        data.salt = PAYLOAD;
        data.passwordHandle = PAYLOAD2;

        PasswordData deserialized = PasswordData.fromBytes(data.toBytes());

        assertEquals(11, deserialized.scryptLogN);
        assertEquals(22, deserialized.scryptLogR);
        assertEquals(33, deserialized.scryptLogP);
        assertEquals(CREDENTIAL_TYPE_PASSWORD, deserialized.credentialType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
    }

    @Test
    public void testPasswordData_deserialize() {
        // Test that we can deserialize existing PasswordData and don't inadvertently change the
        // wire format.
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
    }

    @Test
    public void testGsiDisablesAuthSecret() throws RemoteException {
        mGsiService.setIsGsiRunning(true);

        LockscreenCredential password = newPassword("testGsiDisablesAuthSecret-password");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, PRIMARY_USER_ID, 0 /* flags */).getResponseCode());
        verify(mAuthSecretService, never()).primaryUserCredential(any(ArrayList.class));
    }

    @Test
    public void testUnlockUserWithToken() throws Exception {
        LockscreenCredential password = newPassword("password");
        byte[] token = "some-high-entropy-secure-token".getBytes();
        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
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
        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(password, password, PRIMARY_USER_ID));
        assertNoOrphanedFilesLeft(PRIMARY_USER_ID);
    }

    @Test
    public void testAddingEscrowToken_NoOrphanedFilesLeft() throws Exception {
        final byte[] token = "some-high-entropy-secure-token".getBytes();
        for (int i = 0; i < 16; i++) {
            long handle = mLocalService.addEscrowToken(token, PRIMARY_USER_ID, null);
            assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
            mLocalService.removeEscrowToken(handle, PRIMARY_USER_ID);
        }
        assertNoOrphanedFilesLeft(PRIMARY_USER_ID);
    }

    private void assertNoOrphanedFilesLeft(int userId) {
        String handleString = String.format("%016x",
                mService.getSyntheticPasswordHandleLocked(userId));
        File directory = mStorage.getSyntheticPasswordDirectoryForUser(userId);
        for (File file : directory.listFiles()) {
            String[] parts = file.getName().split("\\.");
            if (!parts[0].equals(handleString) && !parts[0].equals("0000000000000000")) {
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
