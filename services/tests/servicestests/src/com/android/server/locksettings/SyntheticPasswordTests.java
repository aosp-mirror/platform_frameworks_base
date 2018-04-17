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

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.SYNTHETIC_PASSWORD_ENABLED_KEY;
import static com.android.internal.widget.LockPatternUtils.SYNTHETIC_PASSWORD_HANDLE_KEY;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.app.admin.PasswordMetrics;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationResult;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationToken;
import com.android.server.locksettings.SyntheticPasswordManager.PasswordData;

import java.util.ArrayList;

import org.mockito.ArgumentCaptor;


/**
 * runtest frameworks-services -c com.android.server.locksettings.SyntheticPasswordTests
 */
public class SyntheticPasswordTests extends BaseLockSettingsServiceTests {

    public static final byte[] PAYLOAD = new byte[] {1, 2, -1, -2, 55};
    public static final byte[] PAYLOAD2 = new byte[] {2, 3, -2, -3, 44, 1};

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testPasswordBasedSyntheticPassword() throws RemoteException {
        final int USER_ID = 10;
        final String PASSWORD = "user-password";
        final String BADPASSWORD = "bad-password";
        MockSyntheticPasswordManager manager = new MockSyntheticPasswordManager(mContext, mStorage,
                mGateKeeperService, mUserManager);
        AuthenticationToken authToken = manager.newSyntheticPasswordAndSid(mGateKeeperService, null,
                null, USER_ID);
        long handle = manager.createPasswordBasedSyntheticPassword(mGateKeeperService, PASSWORD,
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, authToken, PASSWORD_QUALITY_ALPHABETIC,
                USER_ID);

        AuthenticationResult result = manager.unwrapPasswordBasedSyntheticPassword(
                mGateKeeperService, handle, PASSWORD, USER_ID, null);
        assertEquals(result.authToken.deriveKeyStorePassword(), authToken.deriveKeyStorePassword());

        result = manager.unwrapPasswordBasedSyntheticPassword(mGateKeeperService, handle,
                BADPASSWORD, USER_ID, null);
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

    public void testPasswordMigration() throws RemoteException {
        final String PASSWORD = "testPasswordMigration-password";

        disableSyntheticPassword();
        mService.setLockCredential(PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        final byte[] primaryStorageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);
        enableSyntheticPassword();
        // Performs migration
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                    .getResponseCode());
        assertEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertTrue(hasSyntheticPassword(PRIMARY_USER_ID));

        // SP-based verification
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        assertArrayNotEquals(primaryStorageKey,
                mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
    }

    protected void initializeCredentialUnderSP(String password, int userId) throws RemoteException {
        enableSyntheticPassword();
        int quality = password != null ? PASSWORD_QUALITY_ALPHABETIC
                : PASSWORD_QUALITY_UNSPECIFIED;
        int type = password != null ? LockPatternUtils.CREDENTIAL_TYPE_PASSWORD
                : LockPatternUtils.CREDENTIAL_TYPE_NONE;
        mService.setLockCredential(password, type, null, quality, userId);
    }

    public void testSyntheticPasswordChangeCredential() throws RemoteException {
        final String PASSWORD = "testSyntheticPasswordChangeCredential-password";
        final String NEWPASSWORD = "testSyntheticPasswordChangeCredential-newpassword";

        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        mService.setLockCredential(NEWPASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, PASSWORD,
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                NEWPASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        assertEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    public void testSyntheticPasswordVerifyCredential() throws RemoteException {
        final String PASSWORD = "testSyntheticPasswordVerifyCredential-password";
        final String BADPASSWORD = "testSyntheticPasswordVerifyCredential-badpassword";

        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());

        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR, mService.verifyCredential(
                BADPASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                    .getResponseCode());
    }

    public void testSyntheticPasswordClearCredential() throws RemoteException {
        final String PASSWORD = "testSyntheticPasswordClearCredential-password";
        final String NEWPASSWORD = "testSyntheticPasswordClearCredential-newpassword";

        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        // clear password
        mService.setLockCredential(null, LockPatternUtils.CREDENTIAL_TYPE_NONE, PASSWORD,
                PASSWORD_QUALITY_UNSPECIFIED, PRIMARY_USER_ID);
        assertEquals(0 ,mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));

        // set a new password
        mService.setLockCredential(NEWPASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                NEWPASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                    .getResponseCode());
        assertNotEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    public void testSyntheticPasswordChangeCredentialKeepsAuthSecret() throws RemoteException {
        final String PASSWORD = "testSyntheticPasswordChangeCredentialKeepsAuthSecret-password";
        final String NEWPASSWORD = "testSyntheticPasswordChangeCredentialKeepsAuthSecret-new";

        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        mService.setLockCredential(NEWPASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, PASSWORD,
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                NEWPASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());

        // Check the same secret was passed each time
        ArgumentCaptor<ArrayList<Byte>> secret = ArgumentCaptor.forClass(ArrayList.class);
        verify(mAuthSecretService, atLeastOnce()).primaryUserCredential(secret.capture());
        assertEquals(1, secret.getAllValues().stream().distinct().count());
    }

    public void testSyntheticPasswordVerifyPassesPrimaryUserAuthSecret() throws RemoteException {
        final String PASSWORD = "testSyntheticPasswordVerifyPassesPrimaryUserAuthSecret-password";
        final String NEWPASSWORD = "testSyntheticPasswordVerifyPassesPrimaryUserAuthSecret-new";

        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        reset(mAuthSecretService);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        verify(mAuthSecretService).primaryUserCredential(any(ArrayList.class));
    }

    public void testSecondaryUserDoesNotPassAuthSecret() throws RemoteException {
        final String PASSWORD = "testSecondaryUserDoesNotPassAuthSecret-password";
        final String NEWPASSWORD = "testSecondaryUserDoesNotPassAuthSecret-new";

        initializeCredentialUnderSP(PASSWORD, SECONDARY_USER_ID);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, SECONDARY_USER_ID)
                        .getResponseCode());
        verify(mAuthSecretService, never()).primaryUserCredential(any(ArrayList.class));
    }

    public void testNoSyntheticPasswordOrCredentialDoesNotPassAuthSecret() throws RemoteException {
        // Setting null doesn't create a synthetic password
        initializeCredentialUnderSP(null, PRIMARY_USER_ID);

        reset(mAuthSecretService);
        mService.onUnlockUser(PRIMARY_USER_ID);
        mService.mHandler.runWithScissors(() -> {}, 0 /*now*/); // Flush runnables on handler
        verify(mAuthSecretService, never()).primaryUserCredential(any(ArrayList.class));
    }

    public void testSyntheticPasswordAndCredentialDoesNotPassAuthSecret() throws RemoteException {
        final String PASSWORD = "passwordForASyntheticPassword";
        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);

        reset(mAuthSecretService);
        mService.onUnlockUser(PRIMARY_USER_ID);
        mService.mHandler.runWithScissors(() -> {}, 0 /*now*/); // Flush runnables on handler
        verify(mAuthSecretService, never()).primaryUserCredential(any(ArrayList.class));
    }

    public void testSyntheticPasswordButNoCredentialPassesAuthSecret() throws RemoteException {
        final String PASSWORD = "getASyntheticPassword";
        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        mService.setLockCredential(null, LockPatternUtils.CREDENTIAL_TYPE_NONE, PASSWORD,
                PASSWORD_QUALITY_UNSPECIFIED, PRIMARY_USER_ID);

        reset(mAuthSecretService);
        mService.onUnlockUser(PRIMARY_USER_ID);
        mService.mHandler.runWithScissors(() -> {}, 0 /*now*/); // Flush runnables on handler
        verify(mAuthSecretService).primaryUserCredential(any(ArrayList.class));
    }

    public void testManagedProfileUnifiedChallengeMigration() throws RemoteException {
        final String UnifiedPassword = "testManagedProfileUnifiedChallengeMigration-pwd";
        disableSyntheticPassword();
        mService.setLockCredential(UnifiedPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        final long primarySid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        final long profileSid = mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID);
        final byte[] primaryStorageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);
        final byte[] profileStorageKey = mStorageManager.getUserUnlockToken(MANAGED_PROFILE_USER_ID);
        assertTrue(primarySid != 0);
        assertTrue(profileSid != 0);
        assertTrue(profileSid != primarySid);

        // do migration
        enableSyntheticPassword();
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                UnifiedPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());

        // verify
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                UnifiedPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        assertEquals(primarySid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertArrayNotEquals(primaryStorageKey,
                mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
        assertArrayNotEquals(profileStorageKey,
                mStorageManager.getUserUnlockToken(MANAGED_PROFILE_USER_ID));
        assertTrue(hasSyntheticPassword(PRIMARY_USER_ID));
        assertTrue(hasSyntheticPassword(MANAGED_PROFILE_USER_ID));
    }

    public void testManagedProfileSeparateChallengeMigration() throws RemoteException {
        final String primaryPassword = "testManagedProfileSeparateChallengeMigration-primary";
        final String profilePassword = "testManagedProfileSeparateChallengeMigration-profile";
        disableSyntheticPassword();
        mService.setLockCredential(primaryPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        mService.setLockCredential(profilePassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_ALPHABETIC, MANAGED_PROFILE_USER_ID);
        final long primarySid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        final long profileSid = mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID);
        final byte[] primaryStorageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);
        final byte[] profileStorageKey = mStorageManager.getUserUnlockToken(MANAGED_PROFILE_USER_ID);
        assertTrue(primarySid != 0);
        assertTrue(profileSid != 0);
        assertTrue(profileSid != primarySid);

        // do migration
        enableSyntheticPassword();
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                primaryPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                0, MANAGED_PROFILE_USER_ID).getResponseCode());

        // verify
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                primaryPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                0, MANAGED_PROFILE_USER_ID).getResponseCode());
        assertEquals(primarySid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertArrayNotEquals(primaryStorageKey,
                mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
        assertArrayNotEquals(profileStorageKey,
                mStorageManager.getUserUnlockToken(MANAGED_PROFILE_USER_ID));
        assertTrue(hasSyntheticPassword(PRIMARY_USER_ID));
        assertTrue(hasSyntheticPassword(MANAGED_PROFILE_USER_ID));
    }

    public void testTokenBasedResetPassword() throws RemoteException {
        final String PASSWORD = "password";
        final String PATTERN = "123654";
        final String TOKEN = "some-high-entropy-secure-token";
        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        final byte[] storageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);

        long handle = mLocalService.addEscrowToken(TOKEN.getBytes(), PRIMARY_USER_ID);
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mService.verifyCredential(PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0,
                PRIMARY_USER_ID).getResponseCode();
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mLocalService.setLockCredentialWithToken(PATTERN, LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                handle, TOKEN.getBytes(), PASSWORD_QUALITY_SOMETHING, PRIMARY_USER_ID);

        // Verify DPM gets notified about new device lock
        mService.mHandler.runWithScissors(() -> {}, 0 /*now*/); // Flush runnables on handler
        PasswordMetrics metric = PasswordMetrics.computeForPassword(PATTERN);
        metric.quality = PASSWORD_QUALITY_SOMETHING;
        verify(mDevicePolicyManager).setActivePasswordState(metric, PRIMARY_USER_ID);

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                PATTERN, LockPatternUtils.CREDENTIAL_TYPE_PATTERN, 0, PRIMARY_USER_ID)
                    .getResponseCode());
        assertArrayEquals(storageKey, mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
    }

    public void testTokenBasedClearPassword() throws RemoteException {
        final String PASSWORD = "password";
        final String PATTERN = "123654";
        final String TOKEN = "some-high-entropy-secure-token";
        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        final byte[] storageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);

        long handle = mLocalService.addEscrowToken(TOKEN.getBytes(), PRIMARY_USER_ID);
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mService.verifyCredential(PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                0, PRIMARY_USER_ID).getResponseCode();
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mLocalService.setLockCredentialWithToken(null, LockPatternUtils.CREDENTIAL_TYPE_NONE,
                handle, TOKEN.getBytes(), PASSWORD_QUALITY_UNSPECIFIED, PRIMARY_USER_ID);
        mLocalService.setLockCredentialWithToken(PATTERN, LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                handle, TOKEN.getBytes(), PASSWORD_QUALITY_SOMETHING, PRIMARY_USER_ID);

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                PATTERN, LockPatternUtils.CREDENTIAL_TYPE_PATTERN, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        assertArrayEquals(storageKey, mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
    }

    public void testTokenBasedResetPasswordAfterCredentialChanges() throws RemoteException {
        final String PASSWORD = "password";
        final String PATTERN = "123654";
        final String NEWPASSWORD = "password";
        final String TOKEN = "some-high-entropy-secure-token";
        initializeCredentialUnderSP(PASSWORD, PRIMARY_USER_ID);
        final byte[] storageKey = mStorageManager.getUserUnlockToken(PRIMARY_USER_ID);

        long handle = mLocalService.addEscrowToken(TOKEN.getBytes(), PRIMARY_USER_ID);
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mService.verifyCredential(PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                0, PRIMARY_USER_ID).getResponseCode();
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        mService.setLockCredential(PATTERN, LockPatternUtils.CREDENTIAL_TYPE_PATTERN, PASSWORD,
                PASSWORD_QUALITY_SOMETHING, PRIMARY_USER_ID);

        mLocalService.setLockCredentialWithToken(NEWPASSWORD,
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, handle, TOKEN.getBytes(),
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);

        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                NEWPASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                    .getResponseCode());
        assertArrayEquals(storageKey, mStorageManager.getUserUnlockToken(PRIMARY_USER_ID));
    }

    public void testEscrowTokenActivatedImmediatelyIfNoUserPasswordNeedsMigration()
            throws RemoteException {
        final String TOKEN = "some-high-entropy-secure-token";
        enableSyntheticPassword();
        long handle = mLocalService.addEscrowToken(TOKEN.getBytes(), PRIMARY_USER_ID);
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertTrue(hasSyntheticPassword(PRIMARY_USER_ID));
    }

    public void testEscrowTokenActivatedImmediatelyIfNoUserPasswordNoMigration()
            throws RemoteException {
        final String TOKEN = "some-high-entropy-secure-token";
        initializeCredentialUnderSP(null, PRIMARY_USER_ID);
        long handle = mLocalService.addEscrowToken(TOKEN.getBytes(), PRIMARY_USER_ID);
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertTrue(hasSyntheticPassword(PRIMARY_USER_ID));
    }

    public void testEscrowTokenActivatedLaterWithUserPasswordNeedsMigration()
            throws RemoteException {
        final String TOKEN = "some-high-entropy-secure-token";
        final String PASSWORD = "password";
        // Set up pre-SP user password
        disableSyntheticPassword();
        mService.setLockCredential(PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        enableSyntheticPassword();

        long handle = mLocalService.addEscrowToken(TOKEN.getBytes(), PRIMARY_USER_ID);
        // Token not activated immediately since user password exists
        assertFalse(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
        // Activate token (password gets migrated to SP at the same time)
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                PASSWORD, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                    .getResponseCode());
        // Verify token is activated
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));
    }

    public void testPasswordData_serializeDeserialize() {
        PasswordData data = new PasswordData();
        data.scryptN = 11;
        data.scryptR = 22;
        data.scryptP = 33;
        data.passwordType = CREDENTIAL_TYPE_PASSWORD;
        data.salt = PAYLOAD;
        data.passwordHandle = PAYLOAD2;

        PasswordData deserialized = PasswordData.fromBytes(data.toBytes());

        assertEquals(11, deserialized.scryptN);
        assertEquals(22, deserialized.scryptR);
        assertEquals(33, deserialized.scryptP);
        assertEquals(CREDENTIAL_TYPE_PASSWORD, deserialized.passwordType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
    }

    public void testPasswordData_deserialize() {
        // Test that we can deserialize existing PasswordData and don't inadvertently change the
        // wire format.
        byte[] serialized = new byte[] {
                0, 0, 0, 2, /* CREDENTIAL_TYPE_PASSWORD */
                11, /* scryptN */
                22, /* scryptR */
                33, /* scryptP */
                0, 0, 0, 5, /* salt.length */
                1, 2, -1, -2, 55, /* salt */
                0, 0, 0, 6, /* passwordHandle.length */
                2, 3, -2, -3, 44, 1, /* passwordHandle */
        };
        PasswordData deserialized = PasswordData.fromBytes(serialized);

        assertEquals(11, deserialized.scryptN);
        assertEquals(22, deserialized.scryptR);
        assertEquals(33, deserialized.scryptP);
        assertEquals(CREDENTIAL_TYPE_PASSWORD, deserialized.passwordType);
        assertArrayEquals(PAYLOAD, deserialized.salt);
        assertArrayEquals(PAYLOAD2, deserialized.passwordHandle);
    }

    // b/62213311
    //TODO: add non-migration work profile case, and unify/un-unify transition.
    //TODO: test token after user resets password
    //TODO: test token based reset after unified work challenge
    //TODO: test clear password after unified work challenge
}
