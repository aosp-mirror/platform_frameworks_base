/*
 * Copyright (C) 2022 The Android Open Source Project
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


package android.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


@RunWith(JUnit4.class)
public class KeyguardManagerTest {

    private static final String TITLE = "Title";
    private static final String DESCRIPTION = "Description";
    private static final int USER_ID = 0;
    private static final String PASSWORD = "PASSWORD";
    private static final boolean DISALLOW_BIOMETRICS_IF_POLICY_EXISTS = false;
    private static final int PASSWORD_LOCK_TYPE = KeyguardManager.PASSWORD;
    private static final int MEDIUM_PASSWORD_COMPLEXITY =
            DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM;


    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private final KeyguardManager mKeyguardManager = spy(
            mContext.getSystemService(KeyguardManager.class));


    @BeforeClass
    public static void setup() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();
    }

    @AfterClass
    public static void cleanup() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void createConfirmDeviceCredentialIntent_deviceSecure() {
        when(mKeyguardManager.isDeviceSecure(USER_ID)).thenReturn(true);

        Intent intent = mKeyguardManager.createConfirmDeviceCredentialIntent(TITLE, DESCRIPTION,
                USER_ID);

        assertEquals(intent.getAction(),
                KeyguardManager.ACTION_CONFIRM_DEVICE_CREDENTIAL_WITH_USER);
        assertEquals(intent.getStringExtra(KeyguardManager.EXTRA_TITLE), TITLE);
        assertEquals(intent.getStringExtra(KeyguardManager.EXTRA_DESCRIPTION), DESCRIPTION);
        assertEquals(intent.getIntExtra(Intent.EXTRA_USER_ID, /* defaultValue= */-1), USER_ID);
        assertEquals(intent.getPackage(), "com.android.settings");
    }

    @Test
    public void createConfirmDeviceCredentialIntent_deviceNotSecure() {
        when(mKeyguardManager.isDeviceSecure(USER_ID)).thenReturn(false);

        Intent intent = mKeyguardManager.createConfirmDeviceCredentialIntent(TITLE, DESCRIPTION,
                USER_ID);

        assertNull(intent);
    }

    @Test
    public void createConfirmDeviceCredentialIntent() {
        when(mKeyguardManager.isDeviceSecure(USER_ID)).thenReturn(true);

        Intent intent = mKeyguardManager.createConfirmDeviceCredentialIntent(TITLE, DESCRIPTION,
                USER_ID, DISALLOW_BIOMETRICS_IF_POLICY_EXISTS);

        assertEquals(DISALLOW_BIOMETRICS_IF_POLICY_EXISTS,
                intent.getBooleanExtra(KeyguardManager.EXTRA_DISALLOW_BIOMETRICS_IF_POLICY_EXISTS,
                        !DISALLOW_BIOMETRICS_IF_POLICY_EXISTS));
    }

    @Test
    public void setPrivateNotificationsAllowed_allowed() {
        mKeyguardManager.setPrivateNotificationsAllowed(true);

        assertTrue(mKeyguardManager.getPrivateNotificationsAllowed());
    }

    @Test
    public void setPrivateNotificationsAllowed_notAllowed() {
        mKeyguardManager.setPrivateNotificationsAllowed(false);

        assertFalse(mKeyguardManager.getPrivateNotificationsAllowed());
    }

    @Test
    public void setLock_setInitialLockPermissionGranted_validPassword() {
        // Set to `true` to behave as if SET_INITIAL_LOCK permission had been granted.
        doReturn(true).when(mKeyguardManager).checkInitialLockMethodUsage();
        doReturn(true).when(mKeyguardManager).isValidLockPasswordComplexity(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);

        boolean successfullySetLock = mKeyguardManager.setLock(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);

        assertTrue(successfullySetLock);

        verifyDeviceLockedAndRemoveLock();
    }

    @Test
    public void setLock_setInitialLockPermissionGranted_invalidPassword() {
        // Set to `true` to behave as if SET_INITIAL_LOCK permission had been granted.
        doReturn(true).when(mKeyguardManager).checkInitialLockMethodUsage();
        doReturn(false).when(mKeyguardManager).isValidLockPasswordComplexity(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);

        boolean successfullySetLock = mKeyguardManager.setLock(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);

        assertFalse(successfullySetLock);
        assertFalse(mKeyguardManager.isDeviceSecure());
    }

    @Test
    public void setLock_setInitialLockPermissionDenied() {
        // Set to `false` to behave as if SET_INITIAL_LOCK permission had not been granted.
        doReturn(false).when(mKeyguardManager).checkInitialLockMethodUsage();
        assertFalse(mKeyguardManager.checkInitialLockMethodUsage());

        boolean successfullySetLock = mKeyguardManager.setLock(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);

        assertFalse(successfullySetLock);
        assertFalse(mKeyguardManager.isDeviceSecure());
    }

    @Test
    public void checkLock_correctCredentials() {
        // Set to `true` to behave as if SET_INITIAL_LOCK permission had been granted.
        doReturn(true).when(mKeyguardManager).checkInitialLockMethodUsage();
        doReturn(true).when(mKeyguardManager).isValidLockPasswordComplexity(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);
        mKeyguardManager.setLock(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);

        boolean correctLockCredentials = mKeyguardManager.checkLock(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes());

        assertTrue(correctLockCredentials);

        verifyDeviceLockedAndRemoveLock();
    }

    @Test
    public void checkLock_incorrectCredentials() {
        // Set to `true` to behave as if SET_INITIAL_LOCK permission had been granted.
        doReturn(true).when(mKeyguardManager).checkInitialLockMethodUsage();
        doReturn(true).when(mKeyguardManager).isValidLockPasswordComplexity(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);
        mKeyguardManager.setLock(PASSWORD_LOCK_TYPE,
                PASSWORD.getBytes(),
                MEDIUM_PASSWORD_COMPLEXITY);

        boolean correctLockCredentials = mKeyguardManager.checkLock(PASSWORD_LOCK_TYPE,
                "INCORRECT PASSWORD".getBytes());

        assertFalse(correctLockCredentials);

        verifyDeviceLockedAndRemoveLock();
    }

    private void verifyDeviceLockedAndRemoveLock() {
        assertTrue(mKeyguardManager.isDeviceSecure());
        assertTrue("Failed to remove new password that was set in the test case.",
                mKeyguardManager.setLock(-1, null, PASSWORD_LOCK_TYPE, PASSWORD.getBytes()));
    }
}
