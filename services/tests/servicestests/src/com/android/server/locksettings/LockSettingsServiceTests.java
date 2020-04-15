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

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.FakeGateKeeperService.VerifyHandle;
import com.android.server.locksettings.LockSettingsStorage.CredentialHash;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * atest FrameworksServicesTests:LockSettingsServiceTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockSettingsServiceTests extends BaseLockSettingsServiceTests {
    @Test
    public void testCreatePasswordPrimaryUser() throws RemoteException {
        testCreateCredential(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test
    public void testCreatePasswordFailsWithoutLockScreen() throws RemoteException {
        testCreateCredentialFailsWithoutLockScreen(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test
    public void testCreatePatternPrimaryUser() throws RemoteException {
        testCreateCredential(PRIMARY_USER_ID, newPattern("123456789"));
    }

    @Test
    public void testCreatePatternFailsWithoutLockScreen() throws RemoteException {
        testCreateCredentialFailsWithoutLockScreen(PRIMARY_USER_ID, newPattern("123456789"));
    }

    @Test
    public void testChangePasswordPrimaryUser() throws RemoteException {
        testChangeCredentials(PRIMARY_USER_ID, newPattern("78963214"), newPassword("asdfghjk"));
    }

    @Test
    public void testChangePatternPrimaryUser() throws RemoteException {
        testChangeCredentials(PRIMARY_USER_ID, newPassword("!£$%^&*(())"), newPattern("1596321"));
    }

    @Test
    public void testChangePasswordFailPrimaryUser() throws RemoteException {
        final long sid = 1234;
        initializeStorageWithCredential(PRIMARY_USER_ID, newPassword("password"), sid);

        assertFalse(mService.setLockCredential(newPassword("newpwd"), newPassword("badpwd"),
                    PRIMARY_USER_ID));
        assertVerifyCredentials(PRIMARY_USER_ID, newPassword("password"), sid);
    }

    @Test
    public void testClearPasswordPrimaryUser() throws RemoteException {
        initializeStorageWithCredential(PRIMARY_USER_ID, newPassword("password"), 1234);
        assertTrue(mService.setLockCredential(nonePassword(), newPassword("password"),
                PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    @Test
    public void testManagedProfileUnifiedChallenge() throws RemoteException {
        final LockscreenCredential firstUnifiedPassword = newPassword("pwd-1");
        final LockscreenCredential secondUnifiedPassword = newPassword("pwd-2");
        assertTrue(mService.setLockCredential(firstUnifiedPassword,
                nonePassword(), PRIMARY_USER_ID));
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
                firstUnifiedPassword, 0, PRIMARY_USER_ID)
                .getResponseCode());

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
        assertTrue(mService.setLockCredential(
                secondUnifiedPassword, firstUnifiedPassword, PRIMARY_USER_ID));
        mStorageManager.setIgnoreBadUnlock(false);
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertNull(mGateKeeperService.getAuthToken(TURNED_OFF_PROFILE_USER_ID));

        // Clear unified challenge
        assertTrue(mService.setLockCredential(nonePassword(),
                secondUnifiedPassword, PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(TURNED_OFF_PROFILE_USER_ID));
    }

    @Test
    public void testManagedProfileSeparateChallenge() throws RemoteException {
        final LockscreenCredential primaryPassword = newPassword("primary");
        final LockscreenCredential profilePassword = newPassword("profile");
        assertTrue(mService.setLockCredential(primaryPassword,
                nonePassword(),
                PRIMARY_USER_ID));
        /* Currently in LockSettingsService.setLockCredential, unlockUser() is called with the new
         * credential as part of verifyCredential() before the new credential is committed in
         * StorageManager. So we relax the check in our mock StorageManager to allow that.
         */
        mStorageManager.setIgnoreBadUnlock(true);
        assertTrue(mService.setLockCredential(profilePassword,
                nonePassword(),
                MANAGED_PROFILE_USER_ID));
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
                primaryPassword, 0, PRIMARY_USER_ID)
                .getResponseCode());
        assertNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));

        // verify profile credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, 0, MANAGED_PROFILE_USER_ID)
                .getResponseCode());
        assertNotNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));

        // Change primary credential and make sure we don't affect profile
        mStorageManager.setIgnoreBadUnlock(true);
        assertTrue(mService.setLockCredential(
                newPassword("pwd"), primaryPassword, PRIMARY_USER_ID));
        mStorageManager.setIgnoreBadUnlock(false);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, 0, MANAGED_PROFILE_USER_ID)
                .getResponseCode());
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testManagedProfileChallengeUnification_parentUserNoPassword() throws Exception {
        // Start with a profile with unified challenge, parent user has not password
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(MANAGED_PROFILE_USER_ID));

        // Set a separate challenge on the profile
        assertTrue(mService.setLockCredential(
                newPassword("12345678"), nonePassword(), MANAGED_PROFILE_USER_ID));
        assertNotEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(MANAGED_PROFILE_USER_ID));

        // Now unify again, profile should become passwordless again
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false,
                newPassword("12345678"));
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testSetLockCredential_forPrimaryUser_sendsCredentials() throws Exception {
        assertTrue(mService.setLockCredential(
                newPassword("password"),
                nonePassword(),
                PRIMARY_USER_ID));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, "password".getBytes(),
                        PRIMARY_USER_ID);
    }

    @Test
    public void testSetLockCredential_forProfileWithSeparateChallenge_sendsCredentials()
            throws Exception {
        assertTrue(mService.setLockCredential(
                newPattern("12345"),
                nonePassword(),
                MANAGED_PROFILE_USER_ID));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PATTERN, "12345".getBytes(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testSetLockCredential_forProfileWithSeparateChallenge_updatesCredentials()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, true, null);
        initializeStorageWithCredential(
                MANAGED_PROFILE_USER_ID,
                newPattern("12345"),
                1234);

        assertTrue(mService.setLockCredential(
                newPassword("newPassword"),
                newPattern("12345"),
                MANAGED_PROFILE_USER_ID));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, "newPassword".getBytes(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testSetLockCredential_forProfileWithUnifiedChallenge_doesNotSendRandomCredential()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertTrue(mService.setLockCredential(
                newPattern("12345"),
                nonePassword(),
                PRIMARY_USER_ID));

        verify(mRecoverableKeyStoreManager, never())
                .lockScreenSecretChanged(
                        eq(CREDENTIAL_TYPE_PASSWORD), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void
            testSetLockCredential_forPrimaryUserWithUnifiedChallengeProfile_updatesBothCredentials()
                    throws Exception {
        final LockscreenCredential oldCredential = newPassword("oldPassword");
        final LockscreenCredential newCredential = newPassword("newPassword");
        initializeStorageWithCredential(
                PRIMARY_USER_ID, oldCredential, 1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertTrue(mService.setLockCredential(
                newCredential,
                oldCredential,
                PRIMARY_USER_ID));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, newCredential.getCredential(),
                        PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, newCredential.getCredential(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void
            testSetLockCredential_forPrimaryUserWithUnifiedChallengeProfile_removesBothCredentials()
                    throws Exception {
        initializeStorageWithCredential(PRIMARY_USER_ID, newPassword("oldPassword"), 1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertTrue(mService.setLockCredential(
                nonePassword(),
                newPassword("oldPassword"),
                PRIMARY_USER_ID));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_NONE, null, PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_NONE, null, MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testSetLockCredential_nullCredential_removeBiometrics() throws RemoteException {
        initializeStorageWithCredential(
                PRIMARY_USER_ID,
                newPattern("123654"),
                1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        mService.setLockCredential(nonePassword(), newPattern("123654"), PRIMARY_USER_ID);

        // Verify fingerprint is removed
        verify(mFingerprintManager).remove(any(), eq(PRIMARY_USER_ID), any());
        verify(mFaceManager).remove(any(), eq(PRIMARY_USER_ID), any());

        verify(mFingerprintManager).remove(any(), eq(MANAGED_PROFILE_USER_ID), any());
        verify(mFaceManager).remove(any(), eq(MANAGED_PROFILE_USER_ID), any());
    }

    @Test
    public void testSetLockCredential_forUnifiedToSeparateChallengeProfile_sendsNewCredentials()
            throws Exception {
        final LockscreenCredential parentPassword = newPassword("parentPassword");
        final LockscreenCredential profilePassword = newPassword("profilePassword");
        initializeStorageWithCredential(PRIMARY_USER_ID, parentPassword, 1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertTrue(mService.setLockCredential(
                profilePassword,
                nonePassword(),
                MANAGED_PROFILE_USER_ID));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, profilePassword.getCredential(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void
            testSetLockCredential_forSeparateToUnifiedChallengeProfile_doesNotSendRandomCredential()
                    throws Exception {
        final LockscreenCredential parentPassword = newPassword("parentPassword");
        final LockscreenCredential profilePassword = newPattern("12345");
        initializeStorageWithCredential(PRIMARY_USER_ID, parentPassword, 1234);
        // Create and verify separate profile credentials.
        testCreateCredential(MANAGED_PROFILE_USER_ID, profilePassword);

        mService.setSeparateProfileChallengeEnabled(
                MANAGED_PROFILE_USER_ID, false, profilePassword);

        // Called once for setting the initial separate profile credentials and not again during
        // unification.
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(anyInt(), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testVerifyCredential_forPrimaryUser_sendsCredentials() throws Exception {
        final LockscreenCredential password = newPassword("password");
        initializeStorageWithCredential(PRIMARY_USER_ID, password, 1234);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(password, 1, PRIMARY_USER_ID);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PASSWORD, password.getCredential(), PRIMARY_USER_ID);
    }

    @Test
    public void testVerifyCredential_forProfileWithSeparateChallenge_sendsCredentials()
            throws Exception {
        final LockscreenCredential pattern = newPattern("12345");
        assertTrue(mService.setLockCredential(
                pattern,
                nonePassword(),
                MANAGED_PROFILE_USER_ID));
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(pattern, 1, MANAGED_PROFILE_USER_ID);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getCredential(), MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void verifyCredential_forPrimaryUserWithUnifiedChallengeProfile_sendsCredentialsForBoth()
                    throws Exception {
        final LockscreenCredential pattern = newPattern("12345");
        initializeStorageWithCredential(PRIMARY_USER_ID, pattern, 1234);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(pattern, 1, PRIMARY_USER_ID);

        // Parent sends its credentials for both the parent and profile.
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getCredential(), PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getCredential(), MANAGED_PROFILE_USER_ID);
        // Profile doesn't send its own random credentials.
        verify(mRecoverableKeyStoreManager, never())
                .lockScreenSecretAvailable(
                        eq(CREDENTIAL_TYPE_PASSWORD), any(), eq(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testCredentialChangeNotPossibleInSecureFrpModeDuringSuw() {
        mSettings.setUserSetupComplete(false);
        mSettings.setSecureFrpMode(true);
        try {
            mService.setLockCredential(newPassword("1234"), nonePassword(), PRIMARY_USER_ID);
            fail("Password shouldn't be changeable before FRP unlock");
        } catch (SecurityException e) { }
    }

    @Test
    public void testCredentialChangePossibleInSecureFrpModeAfterSuw() {
        mSettings.setUserSetupComplete(true);
        mSettings.setSecureFrpMode(true);
        assertTrue(mService.setLockCredential(newPassword("1234"), nonePassword(),
                PRIMARY_USER_ID));
    }

    private void testCreateCredential(int userId, LockscreenCredential credential)
            throws RemoteException {
        assertTrue(mService.setLockCredential(credential, nonePassword(), userId));
        assertVerifyCredentials(userId, credential, -1);
    }

    private void testCreateCredentialFailsWithoutLockScreen(
            int userId, LockscreenCredential credential) throws RemoteException {
        mService.mHasSecureLockScreen = false;

        try {
            mService.setLockCredential(credential, null, userId);
            fail("An exception should have been thrown.");
        } catch (UnsupportedOperationException e) {
            // Success - the exception was expected.
        }

        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId));
    }

    private void testChangeCredentials(int userId, LockscreenCredential newCredential,
            LockscreenCredential oldCredential) throws RemoteException {
        final long sid = 1234;
        initializeStorageWithCredential(userId, oldCredential, sid);
        assertTrue(mService.setLockCredential(newCredential, oldCredential, userId));
        assertVerifyCredentials(userId, newCredential, sid);
    }

    private void assertVerifyCredentials(int userId, LockscreenCredential credential, long sid)
            throws RemoteException{
        final long challenge = 54321;
        VerifyCredentialResponse response = mService.verifyCredential(credential,
                challenge, userId);

        assertEquals(GateKeeperResponse.RESPONSE_OK, response.getResponseCode());
        if (sid != -1) assertEquals(sid, mGateKeeperService.getSecureUserId(userId));
        if (credential.isPassword()) {
            assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(userId));
        } else if (credential.isPin()) {
            assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(userId));
        } else if (credential.isPattern()) {
            assertEquals(CREDENTIAL_TYPE_PATTERN, mService.getCredentialType(userId));
        } else {
            assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId));
        }
        // check for bad credential
        final LockscreenCredential badCredential;
        if (!credential.isNone()) {
            badCredential = credential.duplicate();
            badCredential.getCredential()[0] ^= 1;
        } else {
            badCredential = LockscreenCredential.createPin("0");
        }
        assertEquals(GateKeeperResponse.RESPONSE_ERROR, mService.verifyCredential(
                badCredential, challenge, userId).getResponseCode());
    }

    private void initializeStorageWithCredential(int userId, LockscreenCredential credential,
            long sid) throws RemoteException {
        byte[] oldHash = new VerifyHandle(credential.getCredential(), sid).toBytes();
        if (mService.shouldMigrateToSyntheticPasswordLocked(userId)) {
            mService.initializeSyntheticPasswordLocked(oldHash, credential, userId);
        } else {
            if (credential.isPassword() || credential.isPin()) {
                mStorage.writeCredentialHash(CredentialHash.create(oldHash,
                        LockPatternUtils.CREDENTIAL_TYPE_PASSWORD), userId);
            } else {
                mStorage.writeCredentialHash(CredentialHash.create(oldHash,
                        LockPatternUtils.CREDENTIAL_TYPE_PATTERN), userId);
            }
        }
    }
}
