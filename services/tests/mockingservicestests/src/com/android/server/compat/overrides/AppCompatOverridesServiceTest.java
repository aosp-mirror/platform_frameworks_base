/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.compat.overrides;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.compat.overrides.AppCompatOverridesParser.FLAG_OWNED_CHANGE_IDS;
import static com.android.server.compat.overrides.AppCompatOverridesParser.FLAG_REMOVE_OVERRIDES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.app.compat.PackageOverride;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.server.testables.TestableDeviceConfig.TestableDeviceConfigRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Test class for {@link AppCompatOverridesService}.
 *
 * Build/Install/Run:
 * atest FrameworksMockingServicesTests:AppCompatOverridesServiceTest
 */
@RunWith(MockitoJUnitRunner.class)
@SmallTest
@Presubmit
public class AppCompatOverridesServiceTest {
    private static final String NAMESPACE_1 = "namespace_1";
    private static final List<String> SUPPORTED_NAMESPACES = Arrays.asList(NAMESPACE_1);

    private static final String PACKAGE_1 = "com.android.test1";
    private static final String PACKAGE_2 = "com.android.test2";
    private static final String PACKAGE_3 = "com.android.test3";
    private static final String PACKAGE_4 = "com.android.test4";
    private static final String PACKAGE_5 = "com.android.test5";

    private MockContext mMockContext;
    private AppCompatOverridesService mService;

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private IPlatformCompat mPlatformCompat;

    @Captor
    private ArgumentCaptor<CompatibilityOverrideConfig> mOverridesToAddConfigCaptor;
    @Captor
    private ArgumentCaptor<CompatibilityOverridesToRemoveConfig> mOverridesToRemoveConfigCaptor;

    @Rule
    public TestableDeviceConfigRule mDeviceConfigRule = new TestableDeviceConfigRule();

    class MockContext extends ContextWrapper {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public Executor getMainExecutor() {
            // Run on current thread
            return Runnable::run;
        }
    }

    @Before
    public void setUp() throws Exception {
        mMockContext = new MockContext(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        mService = new AppCompatOverridesService(mMockContext, mPlatformCompat,
                SUPPORTED_NAMESPACES);
    }

    @Test
    public void onPropertiesChanged_removeOverridesFlagNotSet_appliesPackageOverrides()
            throws Exception {
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 3);
        mockGetApplicationInfoNotInstalled(PACKAGE_2);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 10);
        mockGetApplicationInfo(PACKAGE_4, /* versionCode= */ 1);
        mockGetApplicationInfo(PACKAGE_5, /* versionCode= */ 1);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "123:::true,456::1:false,456:2::true")
                .setString(PACKAGE_2, "123:::true")
                .setString(PACKAGE_3, "123:1:9:true,123:10:11:false,123:11::true,456:::")
                .setString(PACKAGE_4, "")
                .setString(PACKAGE_5, "123:::,789:::")
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456,789").build());

        Map<Long, PackageOverride> addedOverrides;
        // Package 1
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(mOverridesToAddConfigCaptor.capture(),
                eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
        addedOverrides = mOverridesToAddConfigCaptor.getValue().overrides;
        assertThat(addedOverrides).hasSize(2);
        assertThat(addedOverrides.get(123L)).isEqualTo(
                new PackageOverride.Builder().setEnabled(true).build());
        assertThat(addedOverrides.get(456L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(2).setEnabled(true).build());
        // Package 2
        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_2));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_2));
        // Package 3
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(mOverridesToAddConfigCaptor.capture(),
                eq(PACKAGE_3));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_3));
        addedOverrides = mOverridesToAddConfigCaptor.getValue().overrides;
        assertThat(addedOverrides).hasSize(1);
        assertThat(addedOverrides.get(123L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(10).setMaxVersionCode(
                        11).setEnabled(false).build());
        assertThat(mOverridesToRemoveConfigCaptor.getValue().changeIds).containsExactly(456L);
        // Package 4
        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_4));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_4));
        // Package 5
        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_5));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_5));
        assertThat(mOverridesToRemoveConfigCaptor.getValue().changeIds).containsExactly(123L, 789L);
    }

    @Test
    public void onPropertiesChanged_removeOverridesFlagSetBefore_skipsOverridesToRemove()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_1 + "=123:456," + PACKAGE_2 + "=123")
                .setString(PACKAGE_1, "123:::true")
                .setString(PACKAGE_4, "123:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 0);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "123:::true,456:::,789:::false")
                .setString(PACKAGE_2, "123:::true")
                .setString(PACKAGE_3, "456:::true").build());

        // Package 1
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(mOverridesToAddConfigCaptor.capture(),
                eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
        assertThat(mOverridesToAddConfigCaptor.getValue().overrides.keySet()).containsExactly(789L);
        // Package 2
        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_2));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_2));
        // Package 3
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(mOverridesToAddConfigCaptor.capture(),
                eq(PACKAGE_3));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_3));
        assertThat(mOverridesToAddConfigCaptor.getValue().overrides.keySet()).containsExactly(456L);
        // Package 4 (not applied because it hasn't changed after the listener was added)
        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_4));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_4));
    }

    @Test
    public void onPropertiesChanged_removeOverridesFlagChangedNoPackageOverridesFlags_removesOnly()
            throws Exception {
        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_REMOVE_OVERRIDES,
                        PACKAGE_1 + "=123:456," + PACKAGE_2 + "=789").build());

        // Package 1
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_1));
        assertThat(mOverridesToRemoveConfigCaptor.getValue().changeIds).containsExactly(123L, 456L);
        // Package 2
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_2));
        assertThat(mOverridesToRemoveConfigCaptor.getValue().changeIds).containsExactly(789L);
    }

    @Test
    public void onPropertiesChanged_removeOverridesFlagAndSomePackageOverrideFlagsChanged_ok()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_1 + "=123:456")
                .setString(PACKAGE_1, "123:::true,456:::,789:::false")
                .setString(PACKAGE_3, "456:::false,789:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 0);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_2 + "=123," + PACKAGE_3 + "=789")
                .setString(PACKAGE_2, "123:::true,456:::").build());

        // Package 1
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(mOverridesToAddConfigCaptor.capture(),
                eq(PACKAGE_1));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_1));
        assertThat(mOverridesToAddConfigCaptor.getValue().overrides.keySet()).containsExactly(123L,
                789L);
        assertThat(mOverridesToRemoveConfigCaptor.getValue().changeIds).containsExactly(456L);
        // Package 2
        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_2));
        verify(mPlatformCompat, times(2)).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_2));
        List<CompatibilityOverridesToRemoveConfig> configs =
                mOverridesToRemoveConfigCaptor.getAllValues();
        assertThat(configs.size()).isAtLeast(2);
        assertThat(configs.get(configs.size() - 2).changeIds).containsExactly(123L);
        assertThat(configs.get(configs.size() - 1).changeIds).containsExactly(456L);
        // Package 3
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(mOverridesToAddConfigCaptor.capture(),
                eq(PACKAGE_3));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_3));
        assertThat(mOverridesToAddConfigCaptor.getValue().overrides.keySet()).containsExactly(456L);
        assertThat(mOverridesToRemoveConfigCaptor.getValue().changeIds).containsExactly(789L);
    }

    @Test
    public void onPropertiesChanged_ownedChangeIdsFlagAndSomePackageOverrideFlagsChanged_ok()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_1 + "=*")
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456")
                .setString(PACKAGE_1, "123:::true")
                .setString(PACKAGE_3, "456:::false").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 0);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456,789")
                .setString(PACKAGE_2, "123:::true").build());

        // Package 1
        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_1));
        assertThat(mOverridesToRemoveConfigCaptor.getValue().changeIds).containsExactly(123L, 456L,
                789L);
        // Package 2
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(mOverridesToAddConfigCaptor.capture(),
                eq(PACKAGE_2));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_2));
        assertThat(mOverridesToAddConfigCaptor.getValue().overrides.keySet()).containsExactly(123L);
        // Package 3
        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_3));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_3));
    }

    @Test
    public void onPropertiesChanged_platformCompatThrowsExceptionForSomeCalls_skipsFailedCalls()
            throws Exception {
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_4, /* versionCode= */ 0);
        doThrow(new RemoteException()).when(mPlatformCompat).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_2));
        doThrow(new RemoteException()).when(mPlatformCompat).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_3));

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "123:::true,456:::")
                .setString(PACKAGE_2, "123:::true,456:::")
                .setString(PACKAGE_3, "123:::true,456:::")
                .setString(PACKAGE_4, "123:::true,456:::").build());

        // Package 1
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(any(CompatibilityOverrideConfig.class),
                eq(PACKAGE_1));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
        // Package 2
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(any(CompatibilityOverrideConfig.class),
                eq(PACKAGE_2));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_2));
        // Package 3
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(any(CompatibilityOverrideConfig.class),
                eq(PACKAGE_3));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_3));
        // Package 4
        verify(mPlatformCompat).putOverridesOnReleaseBuilds(any(CompatibilityOverrideConfig.class),
                eq(PACKAGE_1));
        verify(mPlatformCompat).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_4));
    }

    private void mockGetApplicationInfo(String packageName, long versionCode)
            throws Exception {
        when(mPackageManager.getApplicationInfo(eq(packageName), anyInt())).thenReturn(
                createAppInfo(versionCode));
    }

    private void mockGetApplicationInfoNotInstalled(String packageName) throws Exception {
        when(mPackageManager.getApplicationInfo(eq(packageName), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());
    }

    private static ApplicationInfo createAppInfo(long versionCode) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.longVersionCode = versionCode;
        return appInfo;
    }
}
