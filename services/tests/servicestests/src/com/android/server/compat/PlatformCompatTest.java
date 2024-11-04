/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.compat;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;
import static org.testng.Assert.assertThrows;

import android.compat.Compatibility.ChangeConfig;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Build;
import android.os.Process;

import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.ChangeReporter;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class PlatformCompatTest {
    private static final String PACKAGE_NAME = "my.package";

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    CompatChange.ChangeListener mListener1, mListener2;
    PlatformCompat mPlatformCompat;
    CompatConfig mCompatConfig;
    @Mock
    private AndroidBuildClassifier mBuildClassifier;
    @Mock
    private ChangeReporter mChangeReporter;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        android.app.compat.ChangeIdStateCache.disable();
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackageUid(eq(PACKAGE_NAME), eq(0))).thenThrow(
                new PackageManager.NameNotFoundException());
        when(mPackageManagerInternal.getPackageUid(eq(PACKAGE_NAME), eq(0), anyInt()))
            .thenReturn(-1);
        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
            .thenThrow(new PackageManager.NameNotFoundException());
        mCompatConfig = new CompatConfig(mBuildClassifier, mContext);
        mPlatformCompat =
                new PlatformCompat(mContext, mCompatConfig, mBuildClassifier, mChangeReporter);
        // Assume userdebug/eng non-final build
        mCompatConfig.forceNonDebuggableFinalForTest(false);
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(true);
        when(mBuildClassifier.isFinalBuild()).thenReturn(false);
        when(mBuildClassifier.platformTargetSdk()).thenReturn(30);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);
    }

    @Test
    public void testListAllChanges() {
        mCompatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1L)
                .addDisabledChangeWithIdAndName(2L, "change2")
                .addEnableAfterSdkChangeWithIdAndDescription(Build.VERSION_CODES.O, 3L, "desc")
                .addEnableAfterSdkChangeWithId(Build.VERSION_CODES.P, 4L)
                .addEnableAfterSdkChangeWithId(Build.VERSION_CODES.Q, 5L)
                .addEnableAfterSdkChangeWithId(Build.VERSION_CODES.R, 6L)
                .addLoggingOnlyChangeWithId(7L)
                .addDisabledOverridableChangeWithId(8L)
                .build();
        mPlatformCompat =
                new PlatformCompat(mContext, mCompatConfig, mBuildClassifier, mChangeReporter);
        assertThat(mPlatformCompat.listAllChanges()).asList().containsExactly(
                new CompatibilityChangeInfo(1L, "", -1, -1, false, false, "", false),
                new CompatibilityChangeInfo(2L, "change2", -1, -1, true, false, "", false),
                new CompatibilityChangeInfo(3L, "", Build.VERSION_CODES.O, -1, false, false,
                        "desc", false),
                new CompatibilityChangeInfo(
                        4L, "", Build.VERSION_CODES.P, -1, false, false, "", false),
                new CompatibilityChangeInfo(
                        5L, "", Build.VERSION_CODES.Q, -1, false, false, "", false),
                new CompatibilityChangeInfo(
                        6L, "", Build.VERSION_CODES.R, -1, false, false, "", false),
                new CompatibilityChangeInfo(7L, "", -1, -1, false, true, "", false),
                new CompatibilityChangeInfo(8L, "", -1, -1, true, false, "", true));
    }

    @Test
    public void testListUIChanges() {
        mCompatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1L)
                .addDisabledChangeWithIdAndName(2L, "change2")
                .addEnableSinceSdkChangeWithIdAndDescription(Build.VERSION_CODES.O, 3L, "desc")
                .addEnableSinceSdkChangeWithId(Build.VERSION_CODES.P, 4L)
                .addEnableSinceSdkChangeWithId(Build.VERSION_CODES.Q, 5L)
                .addEnableSinceSdkChangeWithId(Build.VERSION_CODES.R, 6L)
                .addLoggingOnlyChangeWithId(7L)
                .addEnableSinceSdkChangeWithId(31, 8L)
                .build();
        mPlatformCompat =
                new PlatformCompat(mContext, mCompatConfig, mBuildClassifier, mChangeReporter);
        assertThat(mPlatformCompat.listUIChanges()).asList().containsExactly(
                new CompatibilityChangeInfo(1L, "", -1, -1, false, false, "", false),
                new CompatibilityChangeInfo(2L, "change2", -1, -1, true, false, "", false),
                new CompatibilityChangeInfo(
                        5L, "", Build.VERSION_CODES.P, -1, false, false, "", false),
                new CompatibilityChangeInfo(
                        6L, "", Build.VERSION_CODES.Q, -1, false, false, "", false));
    }

    @Test
    public void testOverrideAtInstallTime() throws Exception {
        mCompatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1L)
                .addDisabledChangeWithId(2L)
                .addEnableAfterSdkChangeWithId(Build.VERSION_CODES.O, 3L)
                .build();
        mCompatConfig.forceNonDebuggableFinalForTest(true);
        mPlatformCompat =
                new PlatformCompat(mContext, mCompatConfig, mBuildClassifier, mChangeReporter);

        // Before adding overrides.
        assertThat(mPlatformCompat.isChangeEnabledByPackageName(1, PACKAGE_NAME, 0)).isTrue();
        assertThat(mPlatformCompat.isChangeEnabledByPackageName(2, PACKAGE_NAME, 0)).isFalse();
        assertThat(mPlatformCompat.isChangeEnabledByPackageName(3, PACKAGE_NAME, 0)).isTrue();

        // Add overrides.
        Set<Long> enabled = new HashSet<>();
        enabled.add(2L);
        Set<Long> disabled = new HashSet<>();
        disabled.add(1L);
        disabled.add(3L);
        ChangeConfig changeConfig = new ChangeConfig(enabled, disabled);
        CompatibilityChangeConfig compatibilityChangeConfig =
                new CompatibilityChangeConfig(changeConfig);
        mPlatformCompat.setOverridesForTest(compatibilityChangeConfig, PACKAGE_NAME);

        // After adding overrides.
        assertThat(mPlatformCompat.isChangeEnabledByPackageName(1, PACKAGE_NAME, 0)).isFalse();
        assertThat(mPlatformCompat.isChangeEnabledByPackageName(2, PACKAGE_NAME, 0)).isTrue();
        assertThat(mPlatformCompat.isChangeEnabledByPackageName(3, PACKAGE_NAME, 0)).isFalse();
    }

    @Test
    public void testRegisterListenerToSameIdThrows() throws Exception {
        // Registering a listener to change 1 is successful.
        mPlatformCompat.registerListener(1, mListener1);
        // Registering a listener to change 2 is successful.
        mPlatformCompat.registerListener(2, mListener1);
        // Trying to register another listener to change id 1 fails.
        assertThrows(IllegalStateException.class,
                () -> mPlatformCompat.registerListener(1, mListener1));
    }

    @Test
    public void testRegisterListenerReturn() throws Exception {
        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).build(),
                PACKAGE_NAME);

        // Change id 1 is known (added in setOverrides).
        assertThat(mPlatformCompat.registerListener(1, mListener1)).isTrue();
        // Change 2 is unknown.
        assertThat(mPlatformCompat.registerListener(2, mListener1)).isFalse();
    }

    @Test
    public void testListenerCalledOnSetOverrides() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener1);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(2)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerNotCalledOnWrongPackage() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener1);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, never()).onCompatChange("other.package");
    }

    @Test
    public void testListenerCalledOnSetOverridesTwoListeners() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.registerListener(2, mListener2);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnSetOverridesForTest() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener1);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(2)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnSetOverridesForTestTwoListeners() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.registerListener(2, mListener2);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrides() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener2);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).build(),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.clearOverrides(PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverridesMultipleOverrides() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener2);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.clearOverrides(PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrideExists() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener2);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).build(),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.clearOverride(1, PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrideDoesntExist() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);

        when(mPackageManager.getApplicationInfo(eq(PACKAGE_NAME), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create().withPackageName(PACKAGE_NAME).build());

        mPlatformCompat.clearOverride(1, PACKAGE_NAME);
        // Listener not called when a non existing override is removed.
        verify(mListener1, never()).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testReportChange() throws Exception {
        ApplicationInfo appInfo = ApplicationInfoBuilder.create().withUid(123).build();
        mPlatformCompat.reportChange(1L, appInfo);
        verify(mChangeReporter).reportChange(123, 1L, ChangeReporter.STATE_LOGGED, false, true);

        ApplicationInfo systemAppInfo =
                ApplicationInfoBuilder.create().withUid(123).systemApp().build();
        mPlatformCompat.reportChange(1L, systemAppInfo);
        verify(mChangeReporter).reportChange(123, 1L, ChangeReporter.STATE_LOGGED, true, true);
    }

    @Test
    public void testReportChangeByPackageName() throws Exception {
        when(mPackageManagerInternal.getApplicationInfo(
                        eq(PACKAGE_NAME), eq(0L), anyInt(), anyInt()))
                .thenReturn(
                        ApplicationInfoBuilder.create()
                                .withPackageName(PACKAGE_NAME)
                                .withUid(123)
                                .build());

        mPlatformCompat.reportChangeByPackageName(1L, PACKAGE_NAME, 123);
        verify(mChangeReporter).reportChange(123, 1L, ChangeReporter.STATE_LOGGED, false, true);

        String SYSTEM_PACKAGE_NAME = "my.system.package";

        when(mPackageManagerInternal.getApplicationInfo(
                        eq(SYSTEM_PACKAGE_NAME), eq(0L), anyInt(), anyInt()))
                .thenReturn(
                        ApplicationInfoBuilder.create()
                                .withPackageName(SYSTEM_PACKAGE_NAME)
                                .withUid(123)
                                .systemApp()
                                .build());

        mPlatformCompat.reportChangeByPackageName(1L, SYSTEM_PACKAGE_NAME, 123);
        verify(mChangeReporter).reportChange(123, 1L, ChangeReporter.STATE_LOGGED, true, true);
    }

    @Test
    public void testIsChangeEnabled() throws Exception {
        mCompatConfig =
                CompatConfigBuilder.create(mBuildClassifier, mContext)
                        .addEnabledChangeWithId(1L)
                        .addDisabledChangeWithId(2L)
                        .addEnabledChangeWithId(3L)
                        .build();
        mCompatConfig.forceNonDebuggableFinalForTest(true);
        mPlatformCompat =
                new PlatformCompat(mContext, mCompatConfig, mBuildClassifier, mChangeReporter);

        ApplicationInfo appInfo = ApplicationInfoBuilder.create().withUid(123).build();
        assertThat(mPlatformCompat.isChangeEnabled(1L, appInfo)).isTrue();
        verify(mChangeReporter).reportChange(123, 1L, ChangeReporter.STATE_ENABLED, false, false);
        assertThat(mPlatformCompat.isChangeEnabled(2L, appInfo)).isFalse();
        verify(mChangeReporter).reportChange(123, 2L, ChangeReporter.STATE_DISABLED, false, false);

        ApplicationInfo systemAppInfo =
                ApplicationInfoBuilder.create().withUid(123).systemApp().build();
        assertThat(mPlatformCompat.isChangeEnabled(3L, systemAppInfo)).isTrue();
        verify(mChangeReporter).reportChange(123, 3L, ChangeReporter.STATE_ENABLED, true, false);
    }

    @DisableFlags(Flags.FLAG_SYSTEM_UID_TARGET_SYSTEM_SDK)
    @Test
    public void testSharedSystemUidFlagOff() throws Exception {
        testSharedSystemUid(false);
    }

    @EnableFlags(Flags.FLAG_SYSTEM_UID_TARGET_SYSTEM_SDK)
    @Test
    public void testSharedSystemUidFlagOn() throws Exception {
        testSharedSystemUid(true);
    }

    private void testSharedSystemUid(Boolean expectSystemUidTargetSystemSdk) throws Exception {
        final String systemUidPackageNameTargetsR = "systemuid.package1";
        final String systemUidPackageNameTargetsQ = "systemuid.package2";
        final String nonSystemUidPackageNameTargetsR = "nonsystemuid.package1";
        final String nonSystemUidPackageNameTargetsQ = "nonsystemuid.package2";
        final int nonSystemUid = 123;

        mCompatConfig =
                CompatConfigBuilder.create(mBuildClassifier, mContext)
                        .addEnableSinceSdkChangeWithId(Build.VERSION_CODES.R, 1L)
                        .build();
        mCompatConfig.forceNonDebuggableFinalForTest(true);
        mPlatformCompat =
                new PlatformCompat(mContext, mCompatConfig, mBuildClassifier, mChangeReporter);

        ApplicationInfo systemUidAppInfo1 = ApplicationInfoBuilder.create()
            .withPackageName(systemUidPackageNameTargetsR)
            .withUid(Process.SYSTEM_UID)
            .withTargetSdk(Build.VERSION_CODES.R)
            .build();
        when(mPackageManagerInternal.getApplicationInfo(
                 eq(systemUidPackageNameTargetsR), anyLong(), anyInt(), anyInt()))
            .thenReturn(systemUidAppInfo1);

        ApplicationInfo systemUidAppInfo2 = ApplicationInfoBuilder.create()
            .withPackageName(systemUidPackageNameTargetsQ)
            .withUid(Process.SYSTEM_UID)
            .withTargetSdk(Build.VERSION_CODES.Q)
            .build();
        when(mPackageManagerInternal.getApplicationInfo(
                 eq(systemUidPackageNameTargetsQ), anyLong(), anyInt(), anyInt()))
            .thenReturn(systemUidAppInfo2);

        ApplicationInfo nonSystemUidAppInfo1 = ApplicationInfoBuilder.create()
            .withPackageName(nonSystemUidPackageNameTargetsR)
            .withUid(nonSystemUid)
            .withTargetSdk(Build.VERSION_CODES.R)
            .build();
        when(mPackageManagerInternal.getApplicationInfo(
                 eq(nonSystemUidPackageNameTargetsR), anyLong(), anyInt(), anyInt()))
            .thenReturn(nonSystemUidAppInfo1);

        ApplicationInfo nonSystemUidAppInfo2 = ApplicationInfoBuilder.create()
            .withPackageName(nonSystemUidPackageNameTargetsQ)
            .withUid(nonSystemUid)
            .withTargetSdk(Build.VERSION_CODES.Q)
            .build();
        when(mPackageManagerInternal.getApplicationInfo(
                 eq(nonSystemUidPackageNameTargetsQ), anyLong(), anyInt(), anyInt()))
            .thenReturn(nonSystemUidAppInfo2);

        when(mPackageManager.getPackagesForUid(eq(Process.SYSTEM_UID)))
            .thenReturn(new String[] {systemUidPackageNameTargetsR, systemUidPackageNameTargetsQ});
        when(mPackageManager.getPackagesForUid(eq(nonSystemUid)))
            .thenReturn(new String[] {
                            nonSystemUidPackageNameTargetsR, nonSystemUidPackageNameTargetsQ
                        });

        assertThat(mPlatformCompat.isChangeEnabledByUid(1L, Process.SYSTEM_UID))
            .isEqualTo(expectSystemUidTargetSystemSdk);
        assertThat(mPlatformCompat.isChangeEnabledByUid(1L, nonSystemUid)).isFalse();
    }
}
