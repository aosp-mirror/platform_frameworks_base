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

import android.os.RemoteException;
import android.os.UserHandle;
import android.service.gatekeeper.GateKeeperResponse;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.locksettings.LockSettingsStorage.CredentialHash;
import com.android.server.locksettings.FakeGateKeeperService.VerifyHandle;

/**
 * runtest frameworks-services -c com.android.server.locksettings.LockSettingsServiceTests
 */
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

    public void testCreatePatternPrimaryUser() throws RemoteException {
        testCreateCredential(PRIMARY_USER_ID, "123456789", CREDENTIAL_TYPE_PATTERN,
                PASSWORD_QUALITY_SOMETHING);
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
        final String FAILED_MESSAGE = "Failed to enroll password";
        initializeStorageWithCredential(PRIMARY_USER_ID, "password", CREDENTIAL_TYPE_PASSWORD, sid);

        try {
            mService.setLockCredential("newpwd", CREDENTIAL_TYPE_PASSWORD, "badpwd",
                    PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
            fail("Did not fail when enrolling using incorrect credential");
        } catch (RemoteException expected) {
            assertTrue(expected.getMessage().equals(FAILED_MESSAGE));
        }
        assertVerifyCredentials(PRIMARY_USER_ID, "password", CREDENTIAL_TYPE_PASSWORD, sid);
    }

    public void testClearPasswordPrimaryUser() throws RemoteException {
        final String PASSWORD = "password";
        initializeStorageWithCredential(PRIMARY_USER_ID, PASSWORD, CREDENTIAL_TYPE_PASSWORD, 1234);
        mService.setLockCredential(null, CREDENTIAL_TYPE_NONE, PASSWORD,
                PASSWORD_QUALITY_UNSPECIFIED, PRIMARY_USER_ID);
        assertFalse(mService.havePassword(PRIMARY_USER_ID));
        assertFalse(mService.havePattern(PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    public void testManagedProfileUnifiedChallenge() throws RemoteException {
        final String firstUnifiedPassword = "testManagedProfileUnifiedChallenge-pwd-1";
        final String secondUnifiedPassword = "testManagedProfileUnifiedChallenge-pwd-2";
        mService.setLockCredential(firstUnifiedPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                null, PASSWORD_QUALITY_COMPLEX, PRIMARY_USER_ID);
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
                firstUnifiedPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
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
        mService.setLockCredential(secondUnifiedPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                firstUnifiedPassword, PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        mStorageManager.setIgnoreBadUnlock(false);
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertNull(mGateKeeperService.getAuthToken(TURNED_OFF_PROFILE_USER_ID));

        // Clear unified challenge
        mService.setLockCredential(null, LockPatternUtils.CREDENTIAL_TYPE_NONE,
                secondUnifiedPassword, PASSWORD_QUALITY_UNSPECIFIED, PRIMARY_USER_ID);
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(TURNED_OFF_PROFILE_USER_ID));
    }

    public void testManagedProfileSeparateChallenge() throws RemoteException {
        final String primaryPassword = "testManagedProfileSeparateChallenge-primary";
        final String profilePassword = "testManagedProfileSeparateChallenge-profile";
        mService.setLockCredential(primaryPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_COMPLEX, PRIMARY_USER_ID);
        /* Currently in LockSettingsService.setLockCredential, unlockUser() is called with the new
         * credential as part of verifyCredential() before the new credential is committed in
         * StorageManager. So we relax the check in our mock StorageManager to allow that.
         */
        mStorageManager.setIgnoreBadUnlock(true);
        mService.setLockCredential(profilePassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, null,
                PASSWORD_QUALITY_COMPLEX, MANAGED_PROFILE_USER_ID);
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
                primaryPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0, PRIMARY_USER_ID)
                .getResponseCode());
        assertNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));

        // verify profile credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0,
                MANAGED_PROFILE_USER_ID).getResponseCode());
        assertNotNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));

        // Change primary credential and make sure we don't affect profile
        mStorageManager.setIgnoreBadUnlock(true);
        mService.setLockCredential("pwd", LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                primaryPassword, PASSWORD_QUALITY_ALPHABETIC, PRIMARY_USER_ID);
        mStorageManager.setIgnoreBadUnlock(false);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD, 0,
                MANAGED_PROFILE_USER_ID).getResponseCode());
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
    }

    private void testCreateCredential(int userId, String credential, int type, int quality)
            throws RemoteException {
        mService.setLockCredential(credential, type, null, quality, userId);
        assertVerifyCredentials(userId, credential, type, -1);
    }

    private void testChangeCredentials(int userId, String newCredential, int newType,
            String oldCredential, int oldType, int quality) throws RemoteException {
        final long sid = 1234;
        initializeStorageWithCredential(userId, oldCredential, oldType, sid);
        mService.setLockCredential(newCredential, newType, oldCredential, quality, userId);
        assertVerifyCredentials(userId, newCredential, newType, sid);
    }

    private void assertVerifyCredentials(int userId, String credential, int type, long sid)
            throws RemoteException{
        final long challenge = 54321;
        VerifyCredentialResponse response = mService.verifyCredential(credential, type, challenge,
                userId);

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
        assertEquals(GateKeeperResponse.RESPONSE_ERROR, mService.verifyCredential(credential,
                incorrectType, challenge, userId).getResponseCode());
        // check for bad credential
        assertEquals(GateKeeperResponse.RESPONSE_ERROR, mService.verifyCredential("0" + credential,
                type, challenge, userId).getResponseCode());
    }

    private void initializeStorageWithCredential(int userId, String credential, int type, long sid)
            throws RemoteException {
        byte[] oldHash = new VerifyHandle(credential.getBytes(), sid).toBytes();
        if (mService.shouldMigrateToSyntheticPasswordLocked(userId)) {
            mService.initializeSyntheticPasswordLocked(oldHash, credential, type,
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
