/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.server.testutils.TestUtils.assertExpectException;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;

import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

/**
 * Run the synthetic password tests with caching enabled.
 *
 * By default, those tests run without caching. Untrusted credential reset depends on caching so
 * this class included those tests.
 */
@SmallTest
@Presubmit
public class CachedSyntheticPasswordTests extends SyntheticPasswordTests {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        enableSpCaching(true);
    }

    private void enableSpCaching(boolean enable) {
        when(mDevicePolicyManagerInternal
                .canUserHaveUntrustedCredentialReset(anyInt())).thenReturn(enable);
    }

    public void testSyntheticPasswordClearCredentialUntrusted() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential newPassword = newPassword("newpassword");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        // clear password
        mService.setLockCredential(nonePassword(), password, PRIMARY_USER_ID, true);
        assertEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));

        // set a new password
        mService.setLockCredential(newPassword, nonePassword(), PRIMARY_USER_ID,
                false);
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                newPassword, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        assertNotEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
    }

    public void testSyntheticPasswordChangeCredentialUntrusted() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential newPassword = newPassword("newpassword");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        // Untrusted change password
        mService.setLockCredential(newPassword, nonePassword(), PRIMARY_USER_ID,
                true);
        assertNotEquals(0, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));
        assertNotEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));

        // Verify the password
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                newPassword, 0, PRIMARY_USER_ID).getResponseCode());
    }

    public void testUntrustedCredentialChangeMaintainsAuthSecret() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential newPassword = newPassword("newpassword");

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        // Untrusted change password
        mService.setLockCredential(newPassword, nonePassword(), PRIMARY_USER_ID,
                true);

        // Verify the password
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                newPassword, 0, PRIMARY_USER_ID)
                        .getResponseCode());

        // Ensure the same secret was passed each time
        ArgumentCaptor<ArrayList<Byte>> secret = ArgumentCaptor.forClass(ArrayList.class);
        verify(mAuthSecretService, atLeastOnce()).primaryUserCredential(secret.capture());
        assertEquals(1, secret.getAllValues().stream().distinct().count());
    }

    public void testUntrustedCredentialChangeBlockedIfSpNotCached() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential newPassword = newPassword("newpassword");

        // Disable caching for this test
        enableSpCaching(false);

        initializeCredentialUnderSP(password, PRIMARY_USER_ID);
        flushHandlerTasks();

        long sid = mGateKeeperService.getSecureUserId(PRIMARY_USER_ID);
        // Untrusted change password
        assertExpectException(
                IllegalStateException.class,
                /* messageRegex= */ "Untrusted credential reset not possible without cached SP",
                () -> mService.setLockCredential(newPassword, nonePassword(),
                        PRIMARY_USER_ID, true));
        assertEquals(sid, mGateKeeperService.getSecureUserId(PRIMARY_USER_ID));

        // Verify the new password doesn't work but the old one still does
        assertEquals(VerifyCredentialResponse.RESPONSE_ERROR, mService.verifyCredential(
                newPassword, 0, PRIMARY_USER_ID)
                        .getResponseCode());
        assertEquals(VerifyCredentialResponse.RESPONSE_OK, mService.verifyCredential(
                password, 0, PRIMARY_USER_ID)
                        .getResponseCode());
    }

}
