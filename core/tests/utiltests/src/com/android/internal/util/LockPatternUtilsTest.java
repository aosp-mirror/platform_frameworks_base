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
 * limitations under the License.
 */

package com.android.internal.util;

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_MANAGED;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LockPatternUtilsTest {

    private ILockSettings mLockSettings;
    private static final int USER_ID = 1;
    private static final int DEMO_USER_ID = 5;

    private LockPatternUtils mLockPatternUtils;

    private void configureTest(boolean isSecure, boolean isDemoUser, int deviceDemoMode)
            throws Exception {
        mLockSettings = Mockito.mock(ILockSettings.class);
        final Context context = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        final MockContentResolver cr = new MockContentResolver(context);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(context.getContentResolver()).thenReturn(cr);
        Settings.Global.putInt(cr, Settings.Global.DEVICE_DEMO_MODE, deviceDemoMode);

        when(mLockSettings.getCredentialType(DEMO_USER_ID)).thenReturn(
                isSecure ? LockPatternUtils.CREDENTIAL_TYPE_PASSWORD
                         : LockPatternUtils.CREDENTIAL_TYPE_NONE);
        when(mLockSettings.getLong("lockscreen.password_type", PASSWORD_QUALITY_UNSPECIFIED,
                DEMO_USER_ID)).thenReturn((long) PASSWORD_QUALITY_MANAGED);
        // TODO(b/63758238): stop spying the class under test
        mLockPatternUtils = spy(new LockPatternUtils(context));
        when(mLockPatternUtils.getLockSettings()).thenReturn(mLockSettings);
        doReturn(true).when(mLockPatternUtils).hasSecureLockScreen();

        final UserInfo userInfo = Mockito.mock(UserInfo.class);
        when(userInfo.isDemo()).thenReturn(isDemoUser);
        final UserManager um = Mockito.mock(UserManager.class);
        when(um.getUserInfo(DEMO_USER_ID)).thenReturn(userInfo);
        when(context.getSystemService(Context.USER_SERVICE)).thenReturn(um);
    }

    @Test
    public void isUserInLockDown() throws Exception {
        configureTest(true, false, 2);

        // GIVEN strong auth not required
        when(mLockSettings.getStrongAuthForUser(USER_ID)).thenReturn(STRONG_AUTH_NOT_REQUIRED);

        // THEN user isn't in lockdown
        assertFalse(mLockPatternUtils.isUserInLockdown(USER_ID));

        // GIVEN lockdown
        when(mLockSettings.getStrongAuthForUser(USER_ID)).thenReturn(
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);

        // THEN user is in lockdown
        assertTrue(mLockPatternUtils.isUserInLockdown(USER_ID));

        // GIVEN lockdown and lockout
        when(mLockSettings.getStrongAuthForUser(USER_ID)).thenReturn(
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN | STRONG_AUTH_REQUIRED_AFTER_LOCKOUT);

        // THEN user is in lockdown
        assertTrue(mLockPatternUtils.isUserInLockdown(USER_ID));
    }

    @Test
    public void isLockScreenDisabled_isDemoUser_true() throws Exception {
        configureTest(false, true, 2);
        assertTrue(mLockPatternUtils.isLockScreenDisabled(DEMO_USER_ID));
    }

    @Test
    public void isLockScreenDisabled_isSecureAndDemoUser_false() throws Exception {
        configureTest(true, true, 2);
        assertFalse(mLockPatternUtils.isLockScreenDisabled(DEMO_USER_ID));
    }

    @Test
    public void isLockScreenDisabled_isNotDemoUser_false() throws Exception {
        configureTest(false, false, 2);
        assertFalse(mLockPatternUtils.isLockScreenDisabled(DEMO_USER_ID));
    }

    @Test
    public void isLockScreenDisabled_isNotInDemoMode_false() throws Exception {
        configureTest(false, true, 0);
        assertFalse(mLockPatternUtils.isLockScreenDisabled(DEMO_USER_ID));
    }
}
