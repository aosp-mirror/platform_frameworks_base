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

    private static final int DEMO_USER_ID = 5;

    private LockPatternUtils mLockPatternUtils;

    private void configureTest(boolean isSecure, boolean isDemoUser, int deviceDemoMode)
            throws Exception {
        final Context context = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        final MockContentResolver cr = new MockContentResolver(context);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(context.getContentResolver()).thenReturn(cr);
        Settings.Global.putInt(cr, Settings.Global.DEVICE_DEMO_MODE, deviceDemoMode);

        final ILockSettings ils = Mockito.mock(ILockSettings.class);
        when(ils.getCredentialType(DEMO_USER_ID)).thenReturn(
                isSecure ? LockPatternUtils.CREDENTIAL_TYPE_PASSWORD
                         : LockPatternUtils.CREDENTIAL_TYPE_NONE);
        when(ils.getLong("lockscreen.password_type", PASSWORD_QUALITY_UNSPECIFIED, DEMO_USER_ID))
                .thenReturn((long) PASSWORD_QUALITY_MANAGED);
        // TODO(b/63758238): stop spying the class under test
        mLockPatternUtils = spy(new LockPatternUtils(context));
        when(mLockPatternUtils.getLockSettings()).thenReturn(ils);
        doReturn(true).when(mLockPatternUtils).hasSecureLockScreen();

        final UserInfo userInfo = Mockito.mock(UserInfo.class);
        when(userInfo.isDemo()).thenReturn(isDemoUser);
        final UserManager um = Mockito.mock(UserManager.class);
        when(um.getUserInfo(DEMO_USER_ID)).thenReturn(userInfo);
        when(context.getSystemService(Context.USER_SERVICE)).thenReturn(um);
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
