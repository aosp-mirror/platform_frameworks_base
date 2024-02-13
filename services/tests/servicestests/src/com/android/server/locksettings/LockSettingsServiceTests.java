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

import static android.Manifest.permission.CONFIGURE_FACTORY_RESET_PROTECTION;
import static android.security.Flags.FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PropertyInvalidatedCache;
import android.content.Intent;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.gatekeeper.GateKeeperResponse;
import android.text.TextUtils;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsStateListener;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * atest FrameworksServicesTests:LockSettingsServiceTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockSettingsServiceTests extends BaseLockSettingsServiceTests {
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        PropertyInvalidatedCache.disableForTestMode();
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testSetPasswordPrimaryUser() throws RemoteException {
        setAndVerifyCredential(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test
    public void testSetPasswordFailsWithoutLockScreen() throws RemoteException {
        testSetCredentialFailsWithoutLockScreen(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetTooShortPatternFails() throws RemoteException {
        mService.setLockCredential(newPattern("123"), nonePassword(), PRIMARY_USER_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetTooShortPinFails() throws RemoteException {
        mService.setLockCredential(newPin("123"), nonePassword(), PRIMARY_USER_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetTooShortPassword() throws RemoteException {
        mService.setLockCredential(newPassword("123"), nonePassword(), PRIMARY_USER_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetPasswordWithInvalidChars() throws RemoteException {
        mService.setLockCredential(newPassword("§µ¿¶¥£"), nonePassword(), PRIMARY_USER_ID);
    }

    @Test
    public void testSetPatternPrimaryUser() throws RemoteException {
        setAndVerifyCredential(PRIMARY_USER_ID, newPattern("123456789"));
    }

    @Test
    public void testSetPatternFailsWithoutLockScreen() throws RemoteException {
        testSetCredentialFailsWithoutLockScreen(PRIMARY_USER_ID, newPattern("123456789"));
    }

    @Test
    public void testChangePasswordPrimaryUser() throws RemoteException {
        testChangeCredential(PRIMARY_USER_ID, newPattern("78963214"), newPassword("asdfghjk"));
    }

    @Test
    public void testChangePatternPrimaryUser() throws RemoteException {
        testChangeCredential(PRIMARY_USER_ID, newPassword("password"), newPattern("1596321"));
    }

    @Test
    public void testChangePasswordFailPrimaryUser() throws RemoteException {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        assertFalse(mService.setLockCredential(newPassword("newpwd"), newPassword("badpwd"),
                    PRIMARY_USER_ID));
        assertVerifyCredential(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test
    public void testClearPasswordPrimaryUser() throws RemoteException {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        clearCredential(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test
    public void testManagedProfileUnifiedChallenge() throws RemoteException {
        mService.initializeSyntheticPassword(TURNED_OFF_PROFILE_USER_ID);

        final LockscreenCredential firstUnifiedPassword = newPassword("pwd-1");
        final LockscreenCredential secondUnifiedPassword = newPassword("pwd-2");
        setCredential(PRIMARY_USER_ID, firstUnifiedPassword);
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
                firstUnifiedPassword, PRIMARY_USER_ID, 0 /* flags */)
                .getResponseCode());

        // Verify that we have a new auth token for the profile
        assertNotNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));

        // Verify that profile which aren't running (e.g. turn off work) don't get unlocked
        assertNull(mGateKeeperService.getAuthToken(TURNED_OFF_PROFILE_USER_ID));

        // Change primary password and verify that profile SID remains
        setCredential(PRIMARY_USER_ID, secondUnifiedPassword, firstUnifiedPassword);
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertNull(mGateKeeperService.getAuthToken(TURNED_OFF_PROFILE_USER_ID));

        // Clear unified challenge
        clearCredential(PRIMARY_USER_ID, secondUnifiedPassword);
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(0, mGateKeeperService.getSecureUserId(TURNED_OFF_PROFILE_USER_ID));
    }

    @Test
    public void testManagedProfileSeparateChallenge() throws RemoteException {
        final LockscreenCredential primaryPassword = newPassword("primary");
        final LockscreenCredential profilePassword = newPassword("profile");
        setCredential(PRIMARY_USER_ID, primaryPassword);
        setCredential(MANAGED_PROFILE_USER_ID, profilePassword);

        final long primarySid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        final long profileSid = mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID);
        assertTrue(primarySid != 0);
        assertTrue(profileSid != 0);
        assertTrue(profileSid != primarySid);

        // clear auth token and make sure verify challenge from primary user does not regenerate it.
        mGateKeeperService.clearAuthToken(MANAGED_PROFILE_USER_ID);
        // verify primary credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                primaryPassword, PRIMARY_USER_ID, 0 /* flags */)
                .getResponseCode());
        assertNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));

        // verify profile credential
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, MANAGED_PROFILE_USER_ID, 0 /* flags */)
                .getResponseCode());
        assertNotNull(mGateKeeperService.getAuthToken(MANAGED_PROFILE_USER_ID));
        assertEquals(profileSid, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));

        setCredential(PRIMARY_USER_ID, newPassword("password"), primaryPassword);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                profilePassword, MANAGED_PROFILE_USER_ID, 0 /* flags */)
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
        setCredential(MANAGED_PROFILE_USER_ID, newPassword("12345678"));
        assertNotEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(MANAGED_PROFILE_USER_ID));

        // Now unify again, profile should become passwordless again
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false,
                newPassword("12345678"));
        assertEquals(0, mGateKeeperService.getSecureUserId(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testSetLockCredential_forPrimaryUser_sendsFrpNotification() throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        checkRecordedFrpNotificationIntent();
    }

    @Test
    public void testSetLockCredential_forPrimaryUser_sendsCredentials() throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, "password".getBytes(),
                        PRIMARY_USER_ID);
    }

    @Test
    public void testSetLockCredential_forProfileWithSeparateChallenge_sendsCredentials()
            throws Exception {
        setCredential(MANAGED_PROFILE_USER_ID, newPattern("12345"));
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PATTERN, "12345".getBytes(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testSetLockCredential_forProfileWithSeparateChallenge_updatesCredentials()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, true, null);
        setCredential(MANAGED_PROFILE_USER_ID, newPattern("12345"));
        setCredential(MANAGED_PROFILE_USER_ID, newPassword("newPassword"), newPattern("12345"));
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_PASSWORD, "newPassword".getBytes(),
                        MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testSetLockCredential_forProfileWithUnifiedChallenge_doesNotSendRandomCredential()
            throws Exception {
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        setCredential(PRIMARY_USER_ID, newPattern("12345"));
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
        setCredential(PRIMARY_USER_ID, oldCredential);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        setCredential(PRIMARY_USER_ID, newCredential, oldCredential);

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
        setCredential(PRIMARY_USER_ID, newPassword("oldPassword"));
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        clearCredential(PRIMARY_USER_ID, newPassword("oldPassword"));

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_NONE, null, PRIMARY_USER_ID);
        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretChanged(CREDENTIAL_TYPE_NONE, null, MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void testClearLockCredential_removesBiometrics() throws RemoteException {
        setCredential(PRIMARY_USER_ID, newPattern("123654"));
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        clearCredential(PRIMARY_USER_ID, newPattern("123654"));

        // Verify fingerprint is removed
        verify(mFingerprintManager).removeAll(eq(PRIMARY_USER_ID), any());
        verify(mFaceManager).removeAll(eq(PRIMARY_USER_ID), any());

        verify(mFingerprintManager).removeAll(eq(MANAGED_PROFILE_USER_ID), any());
        verify(mFaceManager).removeAll(eq(MANAGED_PROFILE_USER_ID), any());
    }

    @Test
    public void testClearLockCredential_sendsFrpNotification() throws Exception {
        setCredential(PRIMARY_USER_ID, newPassword("password"));
        checkRecordedFrpNotificationIntent();
        mService.clearRecordedFrpNotificationData();
        clearCredential(PRIMARY_USER_ID, newPassword("password"));
        checkRecordedFrpNotificationIntent();
    }

    @Test
    public void testSetLockCredential_forUnifiedToSeparateChallengeProfile_sendsNewCredentials()
            throws Exception {
        final LockscreenCredential parentPassword = newPassword("parentPassword");
        final LockscreenCredential profilePassword = newPassword("profilePassword");
        setCredential(PRIMARY_USER_ID, parentPassword);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        setCredential(MANAGED_PROFILE_USER_ID, profilePassword);
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
        mService.setSeparateProfileChallengeEnabled(
                MANAGED_PROFILE_USER_ID, true, profilePassword);
        setCredential(PRIMARY_USER_ID, parentPassword);
        setAndVerifyCredential(MANAGED_PROFILE_USER_ID, profilePassword);

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
        setCredential(PRIMARY_USER_ID, password);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PASSWORD, password.getCredential(), PRIMARY_USER_ID);
    }

    @Test
    public void testVerifyCredential_forProfileWithSeparateChallenge_sendsCredentials()
            throws Exception {
        final LockscreenCredential pattern = newPattern("12345");
        setCredential(MANAGED_PROFILE_USER_ID, pattern);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(pattern, MANAGED_PROFILE_USER_ID, 0 /* flags */);

        verify(mRecoverableKeyStoreManager)
                .lockScreenSecretAvailable(
                        CREDENTIAL_TYPE_PATTERN, pattern.getCredential(), MANAGED_PROFILE_USER_ID);
    }

    @Test
    public void verifyCredential_forPrimaryUserWithUnifiedChallengeProfile_sendsCredentialsForBoth()
                    throws Exception {
        final LockscreenCredential pattern = newPattern("12345");
        setCredential(PRIMARY_USER_ID, pattern);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        reset(mRecoverableKeyStoreManager);

        mService.verifyCredential(pattern, PRIMARY_USER_ID, 0 /* flags */);

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
    public void testVerifyCredential_notifyLockSettingsStateListeners_whenGoodPassword()
            throws Exception {
        mSetFlagsRule.enableFlags(FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS);
        final LockscreenCredential password = newPassword("password");
        setCredential(PRIMARY_USER_ID, password);
        final LockSettingsStateListener listener = mock(LockSettingsStateListener.class);
        mLocalService.registerLockSettingsStateListener(listener);

        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                        .getResponseCode());

        verify(listener).onAuthenticationSucceeded(PRIMARY_USER_ID);
    }

    @Test
    public void testVerifyCredential_notifyLockSettingsStateListeners_whenBadPassword()
            throws Exception {
        mSetFlagsRule.enableFlags(FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS);
        final LockscreenCredential password = newPassword("password");
        setCredential(PRIMARY_USER_ID, password);
        final LockscreenCredential badPassword = newPassword("badPassword");
        final LockSettingsStateListener listener = mock(LockSettingsStateListener.class);
        mLocalService.registerLockSettingsStateListener(listener);

        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR,
                mService.verifyCredential(badPassword, PRIMARY_USER_ID, 0 /* flags */)
                        .getResponseCode());

        verify(listener).onAuthenticationFailed(PRIMARY_USER_ID);
    }

    @Test
    public void testLockSettingsStateListener_registeredThenUnregistered() throws Exception {
        mSetFlagsRule.enableFlags(FLAG_REPORT_PRIMARY_AUTH_ATTEMPTS);
        final LockscreenCredential password = newPassword("password");
        setCredential(PRIMARY_USER_ID, password);
        final LockscreenCredential badPassword = newPassword("badPassword");
        final LockSettingsStateListener listener = mock(LockSettingsStateListener.class);

        mLocalService.registerLockSettingsStateListener(listener);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK,
                mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                        .getResponseCode());
        verify(listener).onAuthenticationSucceeded(PRIMARY_USER_ID);

        mLocalService.unregisterLockSettingsStateListener(listener);
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR,
                mService.verifyCredential(badPassword, PRIMARY_USER_ID, 0 /* flags */)
                        .getResponseCode());
        verify(listener, never()).onAuthenticationFailed(PRIMARY_USER_ID);
    }

    @Test
    public void testSetCredentialNotPossibleInSecureFrpModeDuringSuw() {
        setUserSetupComplete(false);
        setSecureFrpMode(true);
        try {
            mService.setLockCredential(newPassword("1234"), nonePassword(), PRIMARY_USER_ID);
            fail("Password shouldn't be changeable before FRP unlock");
        } catch (SecurityException e) { }
    }

    @Test
    public void testSetCredentialPossibleInSecureFrpModeAfterSuw() throws RemoteException {
        setUserSetupComplete(true);
        setSecureFrpMode(true);
        setCredential(PRIMARY_USER_ID, newPassword("1234"));
    }

    @Test
    public void testPasswordHistoryDisabledByDefault() throws Exception {
        final int userId = PRIMARY_USER_ID;
        checkPasswordHistoryLength(userId, 0);
        setCredential(userId, newPassword("1234"));
        checkPasswordHistoryLength(userId, 0);
    }

    @Test
    public void testPasswordHistoryLengthHonored() throws Exception {
        final int userId = PRIMARY_USER_ID;
        when(mDevicePolicyManager.getPasswordHistoryLength(any(), eq(userId))).thenReturn(3);
        checkPasswordHistoryLength(userId, 0);

        setCredential(userId, newPassword("pass1"));
        checkPasswordHistoryLength(userId, 1);

        setCredential(userId, newPassword("pass2"), newPassword("pass1"));
        checkPasswordHistoryLength(userId, 2);

        setCredential(userId, newPassword("pass3"), newPassword("pass2"));
        checkPasswordHistoryLength(userId, 3);

        // maximum length should have been reached
        setCredential(userId, newPassword("pass4"), newPassword("pass3"));
        checkPasswordHistoryLength(userId, 3);
    }

    @Test(expected=NullPointerException.class)
    public void testSetBooleanRejectsNullKey() {
        mService.setBoolean(null, false, 0);
    }

    @Test(expected=NullPointerException.class)
    public void testSetLongRejectsNullKey() {
        mService.setLong(null, 0, 0);
    }

    @Test(expected=NullPointerException.class)
    public void testSetStringRejectsNullKey() {
        mService.setString(null, "value", 0);
    }

    private void checkRecordedFrpNotificationIntent() {
        if (android.security.Flags.frpEnforcement()) {
            Intent savedNotificationIntent = mService.getSavedFrpNotificationIntent();
            assertNotNull(savedNotificationIntent);
            UserHandle userHandle = mService.getSavedFrpNotificationUserHandle();
            assertEquals(userHandle,
                    UserHandle.of(mInjector.getUserManagerInternal().getMainUserId()));

            String permission = mService.getSavedFrpNotificationPermission();
            assertEquals(CONFIGURE_FACTORY_RESET_PROTECTION, permission);
        } else {
            assertNull(mService.getSavedFrpNotificationIntent());
            assertNull(mService.getSavedFrpNotificationUserHandle());
            assertNull(mService.getSavedFrpNotificationPermission());
        }
    }

    private void checkPasswordHistoryLength(int userId, int expectedLen) {
        String history = mService.getString(LockPatternUtils.PASSWORD_HISTORY_KEY, "", userId);
        String[] hashes = TextUtils.split(history, LockPatternUtils.PASSWORD_HISTORY_DELIMITER);
        assertEquals(expectedLen, hashes.length);
    }

    private void testSetCredentialFailsWithoutLockScreen(
            int userId, LockscreenCredential credential) throws RemoteException {
        mService.mHasSecureLockScreen = false;
        try {
            mService.setLockCredential(credential, nonePassword(), userId);
            fail("An exception should have been thrown.");
        } catch (UnsupportedOperationException e) {
            // Success - the exception was expected.
        }

        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(userId));
    }

    private void testChangeCredential(int userId, LockscreenCredential newCredential,
            LockscreenCredential oldCredential) throws RemoteException {
        setCredential(userId, oldCredential);
        setCredential(userId, newCredential, oldCredential);
        assertVerifyCredential(userId, newCredential);
    }

    private void assertVerifyCredential(int userId, LockscreenCredential credential)
            throws RemoteException{
        VerifyCredentialResponse response = mService.verifyCredential(credential, userId,
                0 /* flags */);

        assertEquals(GateKeeperResponse.RESPONSE_OK, response.getResponseCode());
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
                badCredential, userId, 0 /* flags */).getResponseCode());
    }

    private void setAndVerifyCredential(int userId, LockscreenCredential newCredential)
            throws RemoteException {
        setCredential(userId, newCredential);
        assertVerifyCredential(userId, newCredential);
    }

    private void setCredential(int userId, LockscreenCredential newCredential)
            throws RemoteException {
        setCredential(userId, newCredential, nonePassword());
    }

    private void clearCredential(int userId, LockscreenCredential oldCredential)
            throws RemoteException {
        setCredential(userId, nonePassword(), oldCredential);
    }

    private void setCredential(int userId, LockscreenCredential newCredential,
            LockscreenCredential oldCredential) throws RemoteException {
        assertTrue(mService.setLockCredential(newCredential, oldCredential, userId));
        assertEquals(newCredential.getType(), mService.getCredentialType(userId));
        if (newCredential.isNone()) {
            assertEquals(0, mGateKeeperService.getSecureUserId(userId));
        } else {
            assertNotEquals(0, mGateKeeperService.getSecureUserId(userId));
        }
    }
}
