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

import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_LOW;
import static android.app.admin.DevicePolicyManager.PASSWORD_COMPLEXITY_MEDIUM;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import static java.io.FileDescriptor.err;
import static java.io.FileDescriptor.in;
import static java.io.FileDescriptor.out;

import android.app.ActivityManager;
import android.app.admin.PasswordMetrics;
import android.app.admin.PasswordPolicy;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockscreenCredential;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Test class for {@link LockSettingsShellCommand}.
 *
 * runtest frameworks-services -c com.android.server.locksettings.LockSettingsShellCommandTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockSettingsShellCommandTest {

    private LockSettingsShellCommand mCommand;

    private @Mock LockPatternUtils mLockPatternUtils;
    private int mUserId;
    private final Binder mBinder = new Binder();
    private final ShellCallback mShellCallback = new ShellCallback();
    private final ResultReceiver mResultReceiver = new ResultReceiver(
            new Handler(Looper.getMainLooper()));

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        final Context context = InstrumentationRegistry.getTargetContext();
        mUserId = ActivityManager.getCurrentUser();
        mCommand = new LockSettingsShellCommand(mLockPatternUtils, context, 0,
                Process.SHELL_UID);
        when(mLockPatternUtils.hasSecureLockScreen()).thenReturn(true);
    }

    @Test
    public void testWrongPassword() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPassword("1234"), mUserId, null)).thenReturn(false);
        assertEquals(-1, mCommand.exec(mBinder, in, out, err,
                new String[] { "set-pin", "--old", "1234" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils, never()).setLockCredential(any(), any(), anyInt());
    }

    @Test
    public void testChangePin() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.getKeyguardStoredPasswordQuality(mUserId)).thenReturn(
                PASSWORD_QUALITY_NUMERIC);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPin("1234"), mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_NUMERIC));
        assertEquals(0, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pin", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils).setLockCredential(
                LockscreenCredential.createPin("4321"),
                LockscreenCredential.createPin("1234"),
                mUserId);
    }

    @Test
    public void testChangePin_nonCompliant() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.getKeyguardStoredPasswordQuality(mUserId)).thenReturn(
                PASSWORD_QUALITY_NUMERIC);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPin("1234"), mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_ALPHABETIC));
        assertEquals(-1, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pin", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils, never()).setLockCredential(any(), any(), anyInt());
    }

    @Test
    public void testChangePin_noLockScreen() throws Exception {
        when(mLockPatternUtils.hasSecureLockScreen()).thenReturn(false);
        assertEquals(-1, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pin", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils).hasSecureLockScreen();
        verifyNoMoreInteractions(mLockPatternUtils);
    }

    @Test
    public void testChangePassword() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.getKeyguardStoredPasswordQuality(mUserId)).thenReturn(
                PASSWORD_QUALITY_ALPHABETIC);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPassword("1234"), mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_ALPHABETIC));
        assertEquals(0,  mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-password", "--old", "1234", "abcd" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils).setLockCredential(
                LockscreenCredential.createPassword("abcd"),
                LockscreenCredential.createPassword("1234"),
                mUserId);
    }

    @Test
    public void testChangePassword_nonCompliant() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.getKeyguardStoredPasswordQuality(mUserId)).thenReturn(
                PASSWORD_QUALITY_ALPHABETIC);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPassword("1234"), mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_COMPLEX));
        assertEquals(-1,  mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-password", "--old", "1234", "weakpassword" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils, never()).setLockCredential(any(), any(), anyInt());
    }

    @Test
    public void testChangePassword_noLockScreen() throws Exception {
        when(mLockPatternUtils.hasSecureLockScreen()).thenReturn(false);
        assertEquals(-1,  mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-password", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils).hasSecureLockScreen();
        verifyNoMoreInteractions(mLockPatternUtils);
    }

    @Test
    public void testChangePattern() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_SOMETHING));
        assertEquals(0, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pattern", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils).setLockCredential(
                LockscreenCredential.createPattern(stringToPattern("4321")),
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId);
    }

    @Test
    public void testChangePattern_nonCompliant() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_NUMERIC));
        assertEquals(-1, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pattern", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils, never()).setLockCredential(any(), any(), anyInt());
    }

    @Test
    public void testChangePattern_noLockScreen() throws Exception {
        when(mLockPatternUtils.hasSecureLockScreen()).thenReturn(false);
        assertEquals(-1, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pattern", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils).hasSecureLockScreen();
        verifyNoMoreInteractions(mLockPatternUtils);
    }

    @Test
    public void testClear() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_UNSPECIFIED));
        assertEquals(0, mCommand.exec(new Binder(), in, out, err,
                new String[] { "clear", "--old", "1234" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils).setLockCredential(
                LockscreenCredential.createNone(),
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId);
    }

    @Test
    public void testClear_nonCompliant() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_SOMETHING));
        assertEquals(-1, mCommand.exec(new Binder(), in, out, err,
                new String[] { "clear", "--old", "1234" },
                mShellCallback, mResultReceiver));
        verify(mLockPatternUtils, never()).setLockCredential(any(), any(), anyInt());
    }

    @Test
    public void testSetPin_nonCompliantWithComplexity() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.getKeyguardStoredPasswordQuality(mUserId)).thenReturn(
                PASSWORD_QUALITY_NUMERIC);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPin("1234"), mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_UNSPECIFIED));
        when(mLockPatternUtils.getRequestedPasswordComplexity(mUserId))
                .thenReturn(PASSWORD_COMPLEXITY_MEDIUM);

        assertEquals(-1, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pin", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));

        verify(mLockPatternUtils, never()).setLockCredential(any(), any(), anyInt());
    }

    @Test
    public void testSetPin_compliantWithComplexity() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.getKeyguardStoredPasswordQuality(mUserId)).thenReturn(
                PASSWORD_QUALITY_NUMERIC);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPin("1234"), mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_UNSPECIFIED));
        when(mLockPatternUtils.getRequestedPasswordComplexity(mUserId))
                .thenReturn(PASSWORD_COMPLEXITY_MEDIUM);

        assertEquals(0, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pin", "--old", "1234", "4231" },
                mShellCallback, mResultReceiver));

        verify(mLockPatternUtils).setLockCredential(
                LockscreenCredential.createPin("4231"),
                LockscreenCredential.createPin("1234"),
                mUserId);
    }

    @Test
    public void testSetPattern_nonCompliantWithComplexity() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_UNSPECIFIED));
        when(mLockPatternUtils.getRequestedPasswordComplexity(mUserId))
                .thenReturn(PASSWORD_COMPLEXITY_HIGH);

        assertEquals(-1, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pattern", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));

        verify(mLockPatternUtils, never()).setLockCredential(any(), any(), anyInt());
    }

    @Test
    public void testSetPattern_compliantWithComplexity() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPatternEnabled(mUserId)).thenReturn(true);
        when(mLockPatternUtils.isLockPasswordEnabled(mUserId)).thenReturn(false);
        when(mLockPatternUtils.checkCredential(
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId, null)).thenReturn(true);
        when(mLockPatternUtils.getRequestedPasswordMetrics(mUserId))
                .thenReturn(metricsForAdminQuality(PASSWORD_QUALITY_UNSPECIFIED));
        when(mLockPatternUtils.getRequestedPasswordComplexity(mUserId))
                .thenReturn(PASSWORD_COMPLEXITY_LOW);

        assertEquals(0, mCommand.exec(new Binder(), in, out, err,
                new String[] { "set-pattern", "--old", "1234", "4321" },
                mShellCallback, mResultReceiver));

        verify(mLockPatternUtils).setLockCredential(
                LockscreenCredential.createPattern(stringToPattern("4321")),
                LockscreenCredential.createPattern(stringToPattern("1234")),
                mUserId);
    }

    @Test
    public void testRequireStrongAuth_STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN() throws Exception {
        when(mLockPatternUtils.isSecure(mUserId)).thenReturn(true);

        assertEquals(0, mCommand.exec(new Binder(), in, out, err,
                new String[] { "require-strong-auth", "STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN"},
                mShellCallback, mResultReceiver));

        verify(mLockPatternUtils).requireStrongAuth(
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                UserHandle.USER_ALL);
    }

    private List<LockPatternView.Cell> stringToPattern(String str) {
        return LockPatternUtils.byteArrayToPattern(str.getBytes());
    }

    private PasswordMetrics metricsForAdminQuality(int quality) {
        PasswordPolicy policy = new PasswordPolicy();
        policy.quality = quality;
        return policy.getMinMetrics();
    }
}
