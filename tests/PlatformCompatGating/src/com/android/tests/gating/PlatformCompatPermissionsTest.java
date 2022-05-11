/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tests.gating;

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.LOG_COMPAT_CHANGE;
import static android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG;
import static android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.compat.Compatibility.ChangeConfig;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.ServiceManager;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.IPlatformCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.HashSet;
import java.util.Set;

@RunWith(JUnit4.class)
public final class PlatformCompatPermissionsTest {

    // private Context mContext;
    private IPlatformCompat mPlatformCompat;

    @Rule
    public final ExpectedException thrown = ExpectedException.none();
    private Context mContext;
    private UiAutomation mUiAutomation;
    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        // mContext;
        mPlatformCompat = IPlatformCompat.Stub
            .asInterface(ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mUiAutomation = instrumentation.getUiAutomation();
        mContext = instrumentation.getTargetContext();

        mPackageManager = mContext.getPackageManager();
    }

    @After
    public void tearDown() {

        mUiAutomation.dropShellPermissionIdentity();
    }

    @Test
    public void reportChange_noLogCompatChangePermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.reportChange(1, mPackageManager.getApplicationInfo(packageName, 0));
    }

    @Test
    public void reportChange_logCompatChangePermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(LOG_COMPAT_CHANGE);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.reportChange(1, mPackageManager.getApplicationInfo(packageName, 0));
    }

    @Test
    public void reportChangeByPackageName_noLogCompatChangePermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.reportChangeByPackageName(1, packageName, 0);
    }

    @Test
    public void reportChangeByPackageName_logCompatChangePermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(LOG_COMPAT_CHANGE);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.reportChangeByPackageName(1, packageName, 0);
    }

    @Test
    public void reportChangeByUid_noLogCompatChangePermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);

        mPlatformCompat.reportChangeByUid(1, Process.myUid());
    }

    @Test
    public void reportChangeByUid_logCompatChangePermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(LOG_COMPAT_CHANGE);

        mPlatformCompat.reportChangeByUid(1, Process.myUid());
    }

    @Test
    public void isChangeEnabled_noReadCompatConfigPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.isChangeEnabled(1, mPackageManager.getApplicationInfo(packageName, 0));
    }

    @Test
    public void isChangeEnabled_noLogCompatChangeConfigPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        mUiAutomation.adoptShellPermissionIdentity(READ_COMPAT_CHANGE_CONFIG);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.isChangeEnabled(1, mPackageManager.getApplicationInfo(packageName, 0));
    }

    @Test
    public void isChangeEnabled_readAndLogCompatChangeConfigPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(READ_COMPAT_CHANGE_CONFIG, LOG_COMPAT_CHANGE);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.isChangeEnabled(1, mPackageManager.getApplicationInfo(packageName, 0));
    }

    @Test
    public void isChangeEnabledByPackageName_noReadCompatConfigPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.isChangeEnabledByPackageName(1, packageName, 0);
    }

    @Test
    public void isChangeEnabledByPackageName_noLogompatConfigPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        mUiAutomation.adoptShellPermissionIdentity(READ_COMPAT_CHANGE_CONFIG);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.isChangeEnabledByPackageName(1, packageName, 0);
    }

    @Test
    public void isChangeEnabledByPackageName_readAndLogCompatChangeConfigPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(READ_COMPAT_CHANGE_CONFIG, LOG_COMPAT_CHANGE);
        final String packageName = mContext.getPackageName();

        mPlatformCompat.isChangeEnabledByPackageName(1, packageName, 0);
    }

    @Test
    public void isChangeEnabledByUid_noReadCompatConfigPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);

        mPlatformCompat.isChangeEnabledByUid(1, Process.myUid());
    }

    @Test
    public void isChangeEnabledByUid_noLogCompatChangePermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        mUiAutomation.adoptShellPermissionIdentity(READ_COMPAT_CHANGE_CONFIG);

        mPlatformCompat.isChangeEnabledByUid(1, Process.myUid());
    }

    @Test
    public void isChangeEnabledByUid_readAndLogCompatChangeConfigPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(READ_COMPAT_CHANGE_CONFIG, LOG_COMPAT_CHANGE);

        mPlatformCompat.isChangeEnabledByUid(1, Process.myUid());
    }

    @Test
    public void setOverrides_noOverridesPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        Set<Long> enabled = new HashSet<>();
        Set<Long> disabled = new HashSet<>();
        ChangeConfig changeConfig = new ChangeConfig(enabled, disabled);
        CompatibilityChangeConfig compatibilityChangeConfig =
                new CompatibilityChangeConfig(changeConfig);

        mPlatformCompat.setOverrides(compatibilityChangeConfig, "foo.bar");
    }
    @Test
    public void setOverrides_overridesPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(OVERRIDE_COMPAT_CHANGE_CONFIG);
        Set<Long> enabled = new HashSet<>();
        Set<Long> disabled = new HashSet<>();
        ChangeConfig changeConfig = new ChangeConfig(enabled, disabled);
        CompatibilityChangeConfig compatibilityChangeConfig =
                new CompatibilityChangeConfig(changeConfig);

        mPlatformCompat.setOverrides(compatibilityChangeConfig, "foo.bar");
    }

    @Test
    public void setOverridesForTest_noOverridesPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        Set<Long> enabled = new HashSet<>();
        Set<Long> disabled = new HashSet<>();
        ChangeConfig changeConfig = new ChangeConfig(enabled, disabled);
        CompatibilityChangeConfig compatibilityChangeConfig =
                new CompatibilityChangeConfig(changeConfig);

        mPlatformCompat.setOverridesForTest(compatibilityChangeConfig, "foo.bar");
    }
    @Test
    public void setOverridesForTest_overridesPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(OVERRIDE_COMPAT_CHANGE_CONFIG);
        Set<Long> enabled = new HashSet<>();
        Set<Long> disabled = new HashSet<>();
        ChangeConfig changeConfig = new ChangeConfig(enabled, disabled);
        CompatibilityChangeConfig compatibilityChangeConfig =
                new CompatibilityChangeConfig(changeConfig);

        mPlatformCompat.setOverridesForTest(compatibilityChangeConfig, "foo.bar");
    }

    @Test
    public void clearOverrides_noOverridesPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL);

        mPlatformCompat.clearOverrides("foo.bar");
    }
    @Test
    public void clearOverrides_overridesPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(OVERRIDE_COMPAT_CHANGE_CONFIG,
                INTERACT_ACROSS_USERS_FULL);

        mPlatformCompat.clearOverrides("foo.bar");
    }

    @Test
    public void clearOverridesForTest_noOverridesPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL);

        mPlatformCompat.clearOverridesForTest("foo.bar");
    }
    @Test
    public void clearOverridesForTest_overridesPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(OVERRIDE_COMPAT_CHANGE_CONFIG,
                INTERACT_ACROSS_USERS_FULL);

        mPlatformCompat.clearOverridesForTest("foo.bar");
    }

    @Test
    public void clearOverride_noOverridesPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);
        mUiAutomation.adoptShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL);

        mPlatformCompat.clearOverride(1, "foo.bar");
    }
    @Test
    public void clearOverride_overridesPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(OVERRIDE_COMPAT_CHANGE_CONFIG,
                INTERACT_ACROSS_USERS_FULL);

        mPlatformCompat.clearOverride(1, "foo.bar");
    }

    @Test
    public void listAllChanges_noReadCompatConfigPermission_throwsSecurityException()
            throws Throwable {
        thrown.expect(SecurityException.class);

        mPlatformCompat.listAllChanges();
    }
    @Test
    public void listAllChanges_readCompatConfigPermission_noThrow()
            throws Throwable {
        mUiAutomation.adoptShellPermissionIdentity(READ_COMPAT_CHANGE_CONFIG);

        mPlatformCompat.listAllChanges();
    }
}
