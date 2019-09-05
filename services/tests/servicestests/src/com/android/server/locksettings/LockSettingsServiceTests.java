/*
 * Copyright (C) 2016 The Android Open Source Project
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
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.service.gatekeeper.GateKeeperResponse;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.FakeGateKeeperService.VerifyHandle;
import com.android.server.locksettings.LockSettingsStorage.CredentialHash;

/**
 * runtest frameworks-services -c com.android.server.locksettings.LockSettingsServiceTests
 */
@SmallTest
@Presubmit
public class LockSettingsServiceTests extends BaseLockSettingsServiceTests {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testCreatePasswordPrimaryUser() throws RemoteException {
        testCreateCredential(PRIMARY_USER_ID, "password", CREDENTIAL_TYPE_PASSWORD,
                PASSWORD_QUALITY_ALPHABETIC);
    }

    public void testCreatePasswordFailsWithoutLockScreen() throws RemoteException {
        testCreateCredentialFailsWithoutLockScreen(PRIMARY_USER_ID, "password",
                CREDENTIAL_TYPE_PASSWORD, PASSWORD_QUALITY_ALPHABETIC);
    }

    public void testCreatePatternPrimaryUser() throws RemoteException {
        testCreateCredential(PRIMARY_USER_ID, "123456789", CREDENTIAL_TYPE_PATTERN,
                PASSWORD_QUALITY_SOMETHING);
    }

    public void testCreatePatternFailsWithoutLockScreen() throws RemoteException {
        testCreateCredentialFailsWithoutLockScreen(PRIMARY_USER_ID, "123456789",
                CREDENTIAL_TYPE_PATTERN, PASSWORD_QUALITY_SOMETHING);
    }

    public void testChangePasswordPrimaryUser() throws RemoteException {
        testChangeCredentials(PRIMARY_USER_ID, "78963214", CREDENTIAL_TYPE_PATTERN,
                "asdfghjk", CREDENTIAL_TYPE_PASSWORD, PASSWORD_QUALITY_ALPHABETIC);
    }

    public void testChangePatternPrimaryUser() throws RemoteException {
        testChangeCredentials(PRIMARY_USER_ID, "!£$%^&*(())", CREDENTIAL_TYPE_PASSWORD,
                "1596321", CREDENTIAL_TYPE_PATTERN, PASSWORD_QUALITY_SOMETHING);
    }

    public void testChangePasswordFailPrimaryUser() throws RemoteException {
        final long sid = 1234;
        initializeStorageWithCredential(PRIMARY_USER_ID, "password", CREDENTIAL_TYPE_PASSWORD, sid);

        assertFalse(mService.setLockCredential("newpwd".getBytes(), CREDENTIAL_TYPE_PASSWORD,
                    "badpwd".getBytes(), PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID, false));
        assertVerifyCredentials(PRIMARY_USER_ID, "password", CREDENTIAL_TYPE_PASSWORD, sid);
    }

    public void testClearPasswordPrimaryUser() throws RemoteException {
        final String PASSWORD = "password";
        initializeStorageWithCredential(PRIMARY_USER_ID, PASSWORD, CREDENTIAL_TYPE_PASSWORD, 1234);
        assertTrue(mService.setLockCredential(null, CREDENTIAL_TYPE_NONE, PASSWORD.getBytes(),
                PASSWORD_QUALITY_UNSPECIFIED, PRIMARY_USER_ID, false));
        assertFalse(mService.havePassword(PRIMARY_USER_ID));
        assertFalse(mService.havePattern(PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    public void testManagedProfileUnifiedChallenge() throws RemoteException {
        final String firstUnifiedPassword = "testManagedProfileUnifiedChallenge-pwd-1";
        final String secondUnifiedPassword = "testManagedProfileUnifiedChallenge-pwd-2";
        assertTrue(mService.setLockCredential(firstUnifiedPassword.getBytes(),
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                null, PASSWORD_QUALITY_COMPLEX, PRIMARY_USER_ID, false));
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        final long primarySid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        final long profileSid = mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID);
        final long turnedOffProfileSid =
                mGateKeeperService.getSecureUserId(TURNED_OFF_PROFILE_USER_ID);
        assertTrue(primarySid != 0);
        assertTrue(profileSid != 0);
        assertTrue(profileSid != primarySid);
        assertTrue(turnedOffProfileSid != 0);
        assertTrue(turnedOffProfileSid != primarySid);
        assertTrue(turnedOffProfileSid != profileSid);

        // clear auth token and wait for verify challenge from primary user to re-generate it.
        mGateKeeperService.clearAuthToken(MANAGED_PROFILE_USER_ID);
        mGateKeeperService.clearAuthToken(TURNED_OFF_PROFILE_USER_ID);
        // verify credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                firstUnifiedPassword.getBytes(), LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0,
                PRIMARY_USER_ID).getResponseCode());

        // Verify that we have a new auth token for the profile
        assertNotNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));

        // Verify that profile which aren't running (e.g. turn off work) don't get unlocked
        assertNull(mGateKeeperService.getAuthToken(TURNED_OFF_PROFILE_USER_ID));

        /* Currently in LockSettingsService.setLockCredential, unlockUser() is called with the new
         * credential as part of verifyCredential() before the new credential is committed in
         * StorageManager. So we relax the check in our mock StorageManager to allow that.
         */
        mStorageManager.setIgnoreBadUnlock(true);
        // Change primary password and verify that profile SID remains
        assertTrue(mService.setLockCredential(secondUnifiedPassword.getBytes(),
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, firstUnifiedPassword.getBytes(),
                PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID, false));
        mStorageManager.setIgnoreBadUnlock(false);
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertNull(mGateKeeperService.getAuthToken(TURNED_OFF_PROFILE_USER_ID));

        // Clear unified challenge
        assertTrue(mService.setLockCredential(null, LockPatternUtils.CREDENTIAL_TYPE_NONE,
                secondUnifiedPassword.getBytes(), PASSWORD_QUALITY_UNSPECIFIED, PRIMARY_USER_ID,
                false));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(TURNED_OFF_PROFILE_USER_ID));
    }

    public void testManagedProfileSeparateChallenge() throws RemoteException {
        final String primaryPassword = "testManagedProfileSeparateChallenge-primary";
        final String profilePassword = "testManagedProfileSeparateChallenge-profile";
        assertTrue(mService.setLockCredential(primaryPassword.getBytes(),
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_COMPLEX, PRIMARY_USER_ID, false));
        /* Currently in LockSettingsService.setLockCredential, unlockUser() is called with the new
         * credential as part of verifyCredential() before the new credential is committed in
         * StorageManager. So we relax the check in our mock StorageManager to allow that.
         */
        mStorageManager.setIgnoreBadUnlock(true);
        assertTrue(mService.setLockCredential(profilePassword.getBytes(),
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_COMPLEX, MANAGED_PROFILE_USER_ID, false));
        mStorageManager.setIgnoreBadUnlock(false);

        final long primarySid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        final long profileSid = mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID);
        assertTrue(primarySid != 0);
        assertTrue(profileSid != 0);
        assertTrue(profileSid != primarySid);

        // clear auth token and make sure verify challenge from primary user does not regenerate it.
        mGateKeeperService.clearAuthToken(MANAGED_PROFILE_USER_ID);
        // verify primary credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                primaryPassword.getBytes(), LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0,
                PRIMARY_USER_ID).getResponseCode());
        assertNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));

        // verify profile credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword.getBytes(), LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0,
                MANAGED_PROFILE_USER_ID).getResponseCode());
        assertNotNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));

        // Change primary credential and make sure we don't affect profile
        mStorageManager.setIgnoreBadUnlock(true);
        assertTrue(mService.setLockCredential("pwd".getBytes(),
                LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                primaryPassword.getBytes(), PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID, false));
        mStorageManager.setIgnoreBadUnlock(false);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword.getBytes(), LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0,
                MANAGED_PROFILE_USER_ID).getResponseCode());
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
    }

    public void testSetLockCredential_forPrimaryUser_sendsCredentials() throws Exception {
        final byte[] password = "password".getBytes();

        assertTrue(mService.setLockCredential(
                password,
                CREDENTIAL_TYPE_PASSWORD,
                null,
                PASSWORD_QUALITY_ALPHABETIC,
                PRIMARY_USER_ID,
                false));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, password, PRIMARY_USER_ID);
    }

    public void testSetLockCredential_forProfileWithSeparateChallenge_sendsCredentials()
            throws Exception {
        final byte[] pattern = "12345".getBytes();

        assertTrue(mService.setLockCredential(
                pattern,
                CREDENTIAL_TYPE_PATTERN,
                null,
                PASSWORD_QUALITY_SOMETHING,
                MANAGED_PROFILE_USER_ID,
                false));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PATTERN, pattern, MANAGED_PROFILE_USER_ID);
    }

    public void testSetLockCredential_forProfileWithSeparateChallenge_updatesCredentials()
            throws Exception {
        final String oldCredential = "12345";
        final byte[] newCredential = "newPassword".getBytes();
        initializeStorageWithCredential(
                MANAGED_PROFILE_USER_ID,
                oldCredential,
                CREDENTIAL_TYPE_PATTERN,
                PASSWORD_QUALITY_SOMETHING);

        assertTrue(mService.setLockCredential(
                newCredential,
                CREDENTIAL_TYPE_PASSWORD,
                oldCredential.getBytes(),
                PASSWORD_QUALITY_ALPHABETIC,
                MANAGED_PROFILE_USER_ID,
                false));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(
                        CREDENTIAL_TYPE_PASSWORD, newCredential, MANAGED_PROFILE_USER_ID);
    }

    public void testSetLockCredential_forProfileWithUnifiedChallenge_doesNotSendRandomCredential()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertTrue(mService.setLockCredential(
                "12345".getBytes(),
                CREDENTIAL_TYPE_PATTERN,
                null,
                PASSWORD_QUALITY_SOMETHING,
                PRIMARY_USER_ID,
                false));

        verify(mRecoverableKeyStoreManager, never())
                .lockScreenSecretChanged(
                        eq(CREDENTIAL_TYPE_PASSWORD), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    public void
            testSetLockCredential_forPrimaryUserWithUnifiedChallengeProfile_updatesBothCredentials()
                    throws Exception {
        final String oldCredential = "oldPassword";
        final byte[] newCredential = "newPassword".getBytes();
        initializeStorageWithCredential(
                PRIMARY_USER_ID, oldCredential, CREDENTIAL_TYPE_PASSWORD, 1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertTrue(mService.setLockCredential(
                newCredential,
                CREDENTIAL_TYPE_PASSWORD,
                oldCredential.getBytes(),
                PASSWORD_QUALITY_ALPHABETIC,
                PRIMARY_USER_ID,
                false));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, newCredential, PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(
                        CREDENTIAL_TYPE_PASSWORD, newCredential, MANAGED_PROFILE_USER_ID);
    }

    public void
            testSetLockCredential_forPrimaryUserWithUnifiedChallengeProfile_removesBothCredentials()
                    throws Exception {
        final String oldCredential = "oldPassword";
        initializeStorageWithCredential(
                PRIMARY_USER_ID, oldCredential, CREDENTIAL_TYPE_PASSWORD, 1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertTrue(mService.setLockCredential(
                null,
                CREDENTIAL_TYPE_NONE,
                oldCredential.getBytes(),
                PASSWORD_QUALITY_UNSPECIFIED,
                PRIMARY_USER_ID,
                false));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_NONE, null, PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_NONE, null, MANAGED_PROFILE_USER_ID);
    }

    public void testSetLockCredential_forUnifiedToSeparateChallengeProfile_sendsNewCredentials()
            throws Exception {
        final String parentPassword = "parentPassword";
        final byte[] profilePassword = "profilePassword".getBytes();
        initializeStorageWithCredential(
                PRIMARY_USER_ID, parentPassword, CREDENTIAL_TYPE_PASSWORD, 1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertTrue(mService.setLockCredential(
                profilePassword,
                CREDENTIAL_TYPE_PASSWORD,
                null,
                PASSWORD_QUALITY_ALPHABETIC,
                MANAGED_PROFILE_USER_ID,
                false));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(
                        CREDENTIAL_TYPE_PASSWORD, profilePassword, MANAGED_PROFILE_USER_ID);
    }

    public void
            testSetLockCredential_forSeparateToUnifiedChallengeProfile_doesNotSendRandomCredential()
                    throws Exception {
        final String parentPassword = "parentPassword";
        final String profilePassword = "12345";
        initializeStorageWithCredential(
                PRIMARY_USER_ID, parentPassword, CREDENTIAL_TYPE_PASSWORD, 1234);
        // Create and verify separate profile credentials.
        testCreateCredential(
                MANAGED_PROFILE_USER_ID,
                profilePassword,
                CREDENTIAL_TYPE_PATTERN,
                PASSWORD_QUALITY_SOMETHING);

        mService.setSeparateProfileChallengeEnabled(
                MANAGED_PROFILE_USER_ID, false, profilePassword.getBytes());

        // Called once for setting the initial separate profile credentials and not again during
        // unification.
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(anyInt(), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    public void testVerifyCredential_forPrimaryUser_sendsCredentials() throws Exception {
        final String password = "password";
        initializeStorageWithCredential(PRIMARY_USER_ID, password, CREDENTIAL_TYPE_PASSWORD, 1234);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(
                password.getBytes(), CREDENTIAL_TYPE_PASSWORD, 1, PRIMARY_USER_ID);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PASSWORD, password.getBytes(), PRIMARY_USER_ID);
    }

    public void testVerifyCredential_forProfileWithSeparateChallenge_sendsCredentials()
            throws Exception {
        final byte[] pattern = "12345".getBytes();
        assertTrue(mService.setLockCredential(
                pattern,
                CREDENTIAL_TYPE_PATTERN,
                null,
                PASSWORD_QUALITY_SOMETHING,
                MANAGED_PROFILE_USER_ID,
                false));
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(pattern, CREDENTIAL_TYPE_PATTERN, 1, MANAGED_PROFILE_USER_ID);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern, MANAGED_PROFILE_USER_ID);
    }

    public void
            testVerifyCredential_forPrimaryUserWithUnifiedChallengeProfile_sendsCredentialsForBoth()
                    throws Exception {
        final String pattern = "12345";
        initializeStorageWithCredential(PRIMARY_USER_ID, pattern, CREDENTIAL_TYPE_PATTERN, 1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(pattern.getBytes(), CREDENTIAL_TYPE_PATTERN, 1, PRIMARY_USER_ID);

        // Parent sends its credentials for both the parent and profile.
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getBytes(), PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getBytes(), MANAGED_PROFILE_USER_ID);
        // Profile doesn't send its own random credentials.
        verify(mRecoverableKeyStoreManager, never())
                .lockScreenSecretAvailable(
                        eq(CREDENTIAL_TYPE_PASSWORD), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    private void testCreateCredential(int userId, String credential, int type, int quality)
            throws RemoteException {
        assertTrue(mService.setLockCredential(credential.getBytes(), type, null, quality,
                userId, false));
        assertVerifyCredentials(userId, credential, type, -1);
    }

    private void testCreateCredentialFailsWithoutLockScreen(
            int userId, String credential, int type, int quality) throws RemoteException {
        mHasSecureLockScreen = false;

        try {
            mService.setLockCredential(credential.getBytes(), type, null, quality,
                    userId, false);
            fail("An exception should have been thrown.");
        } catch (UnsupportedOperationException e) {
            // Success - the exception was expected.
        }

        assertFalse(mService.havePassword(userId));
        assertFalse(mService.havePattern(userId));
    }

    private void testChangeCredentials(int userId, String newCredential, int newType,
            String oldCredential, int oldType, int quality) throws RemoteException {
        final long sid = 1234;
        initializeStorageWithCredential(userId, oldCredential, oldType, sid);
        assertTrue(mService.setLockCredential(newCredential.getBytes(), newType,
                oldCredential.getBytes(), quality, userId, false));
        assertVerifyCredentials(userId, newCredential, newType, sid);
    }

    private void assertVerifyCredentials(int userId, String credential, int type, long sid)
            throws RemoteException{
        final long challenge = 54321;
        VerifyCredentialResponse response = mService.verifyCredential(credential.getBytes(),
                type, challenge, userId);

        assertEquals(GateKeeperResponse.RESPONSE_OK, response.getResponseCode());
        if (sid != -1) assertEquals(sid, mGateKeeperService.getSecureUserId(userId));
        final int incorrectType;
        if (type == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD) {
            assertTrue(mService.havePassword(userId));
            assertFalse(mService.havePattern(userId));
            incorrectType = LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
        } else if (type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN){
            assertFalse(mService.havePassword(userId));
            assertTrue(mService.havePattern(userId));
            incorrectType = LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
        } else {
            assertFalse(mService.havePassword(userId));
            assertFalse(mService.havePassword(userId));
            incorrectType = LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
        }
        // check for bad type
        assertEquals(GateKeeperResponse.RESPONSE_ERROR, mService.verifyCredential(
                credential.getBytes(), incorrectType, challenge, userId).getResponseCode());
        // check for bad credential
        assertEquals(GateKeeperResponse.RESPONSE_ERROR, mService.verifyCredential(
                ("0" + credential).getBytes(), type, challenge, userId).getResponseCode());
    }

    private void initializeStorageWithCredential(int userId, String credential, int type, long sid)
            throws RemoteException {
        byte[] credentialBytes = credential == null ? null : credential.getBytes();
        byte[] oldHash = new VerifyHandle(credential.getBytes(), sid).toBytes();
        if (mService.shouldMigrateToSyntheticPasswordLocked(userId)) {
            mService.initializeSyntheticPasswordLocked(oldHash, credentialBytes, type,
                    type == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD ? PASSWORD_QUALITY_ALPHABETIC
                            : PASSWORD_QUALITY_SOMETHING, userId);
        } else {
            if (type == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD) {
                mStorage.writeCredentialHash(CredentialHash.create(oldHash,
                        LockPatternUtils.CREDENTIAL_TYPE_PASSWORD), userId);
            } else {
                mStorage.writeCredentialHash(CredentialHash.create(oldHash,
                        LockPatternUtils.CREDENTIAL_TYPE_PATTERN), userId);
            }
        }
    }
}
