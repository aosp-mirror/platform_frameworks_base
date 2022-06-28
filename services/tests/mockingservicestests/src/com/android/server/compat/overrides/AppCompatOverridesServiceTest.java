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

import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_CHANGED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.ACTION_USER_SWITCHED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.compat.overrides.AppCompatOverridesParser.FLAG_OWNED_CHANGE_IDS;
import static com.android.server.compat.overrides.AppCompatOverridesParser.FLAG_REMOVE_OVERRIDES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.compat.PackageOverride;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.CompatibilityOverridesByPackageConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveByPackageConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.modules.utils.testing.TestableDeviceConfig.TestableDeviceConfigRule;

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
    private static final String NAMESPACE_2 = "namespace_2";
    private static final String NAMESPACE_3 = "namespace_3";
    private static final List<String> SUPPORTED_NAMESPACES = Arrays.asList(NAMESPACE_1,
            NAMESPACE_2, NAMESPACE_3);

    private static final String PACKAGE_1 = "com.android.test1";
    private static final String PACKAGE_2 = "com.android.test2";
    private static final String PACKAGE_3 = "com.android.test3";
    private static final String PACKAGE_4 = "com.android.test4";

    private MockContext mMockContext;
    private BroadcastReceiver mPackageReceiver;
    private AppCompatOverridesService mService;

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private IPlatformCompat mPlatformCompat;

    @Captor
    private ArgumentCaptor<CompatibilityOverrideConfig> mOverridesToAddConfigCaptor;
    @Captor
    private ArgumentCaptor<CompatibilityOverridesByPackageConfig>
            mOverridesToAddByPackageConfigCaptor;
    @Captor
    private ArgumentCaptor<CompatibilityOverridesToRemoveConfig> mOverridesToRemoveConfigCaptor;
    @Captor
    private ArgumentCaptor<CompatibilityOverridesToRemoveByPackageConfig>
            mOverridesToRemoveByPackageConfigCaptor;

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

        @Override
        @Nullable
        public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
                @NonNull IntentFilter filter, @Nullable String broadcastPermission,
                @Nullable Handler scheduler) {
            mPackageReceiver = receiver;
            return null;
        }
    }

    @Before
    public void setUp() throws Exception {
        mMockContext = new MockContext(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        mService = new AppCompatOverridesService(mMockContext, mPlatformCompat,
                SUPPORTED_NAMESPACES);
        mService.registerPackageReceiver();
        assertThat(mPackageReceiver).isNotNull();
    }

    @Test
    public void onPropertiesChanged_removeOverridesFlagNotSet_appliesPackageOverrides()
            throws Exception {
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 3);
        mockGetApplicationInfoNotInstalled(PACKAGE_2);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 10);
        mockGetApplicationInfo(PACKAGE_4, /* versionCode= */ 1);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456,789")
                .setString(PACKAGE_1, "123:::true,456::1:false,456:2::true,789:::false")
                .setString(PACKAGE_2, "123:::true")
                .setString(PACKAGE_3, "123:1:9:true,123:10:11:false,123:11::true")
                .setString(PACKAGE_4, "").build());

        verify(mPlatformCompat).putAllOverridesOnReleaseBuilds(
                mOverridesToAddByPackageConfigCaptor.capture());
        verify(mPlatformCompat).removeAllOverridesOnReleaseBuilds(
                mOverridesToRemoveByPackageConfigCaptor.capture());
        Map<String, CompatibilityOverrideConfig> packageNameToAddedOverrides =
                mOverridesToAddByPackageConfigCaptor.getValue().packageNameToOverrides;
        Map<String, CompatibilityOverridesToRemoveConfig> packageNameToRemovedOverrides =
                mOverridesToRemoveByPackageConfigCaptor.getValue().packageNameToOverridesToRemove;
        Map<Long, PackageOverride> addedOverrides;
        assertThat(packageNameToAddedOverrides.keySet()).containsExactly(PACKAGE_1, PACKAGE_3);
        assertThat(packageNameToRemovedOverrides.keySet()).containsExactly(PACKAGE_2, PACKAGE_3,
                PACKAGE_4);
        // Package 1
        addedOverrides = packageNameToAddedOverrides.get(PACKAGE_1).overrides;
        assertThat(addedOverrides).hasSize(3);
        assertThat(addedOverrides.get(123L)).isEqualTo(
                new PackageOverride.Builder().setEnabled(true).build());
        assertThat(addedOverrides.get(456L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(2).setEnabled(true).build());
        assertThat(addedOverrides.get(789L)).isEqualTo(
                new PackageOverride.Builder().setEnabled(false).build());
        // Package 2
        assertThat(packageNameToRemovedOverrides.get(PACKAGE_2).changeIds).containsExactly(123L,
                456L, 789L);
        // Package 3
        addedOverrides = packageNameToAddedOverrides.get(PACKAGE_3).overrides;
        assertThat(addedOverrides).hasSize(1);
        assertThat(addedOverrides.get(123L)).isEqualTo(
                new PackageOverride.Builder().setMinVersionCode(10).setMaxVersionCode(
                        11).setEnabled(false).build());
        assertThat(packageNameToRemovedOverrides.get(PACKAGE_3).changeIds).containsExactly(456L,
                789L);
        // Package 4
        assertThat(packageNameToRemovedOverrides.get(PACKAGE_4).changeIds).containsExactly(123L,
                456L, 789L);
    }

    @Test
    public void onPropertiesChanged_ownedChangeIdsFlagNotSet_onlyAddsOverrides()
            throws Exception {
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "123:::true").build());

        verify(mPlatformCompat).putAllOverridesOnReleaseBuilds(
                mOverridesToAddByPackageConfigCaptor.capture());
        verify(mPlatformCompat, never()).removeAllOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveByPackageConfig.class));
        Map<String, CompatibilityOverrideConfig> packageNameToAddedOverrides =
                mOverridesToAddByPackageConfigCaptor.getValue().packageNameToOverrides;
        assertThat(packageNameToAddedOverrides.keySet()).containsExactly(PACKAGE_1);
        assertThat(packageNameToAddedOverrides.get(PACKAGE_1).overrides.keySet()).containsExactly(
                123L);
    }

    @Test
    public void onPropertiesChanged_removeOverridesFlagSetBefore_skipsOverridesToRemove()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456,789")
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_1 + "=123:456," + PACKAGE_2 + "=123")
                .setString(PACKAGE_1, "123:::true")
                .setString(PACKAGE_4, "123:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 0);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "123:::true,789:::false")
                .setString(PACKAGE_2, "123:::true")
                .setString(PACKAGE_3, "456:::true").build());

        verify(mPlatformCompat).putAllOverridesOnReleaseBuilds(
                mOverridesToAddByPackageConfigCaptor.capture());
        verify(mPlatformCompat).removeAllOverridesOnReleaseBuilds(
                mOverridesToRemoveByPackageConfigCaptor.capture());
        Map<String, CompatibilityOverrideConfig> packageNameToAddedOverrides =
                mOverridesToAddByPackageConfigCaptor.getValue().packageNameToOverrides;
        Map<String, CompatibilityOverridesToRemoveConfig> packageNameToRemovedOverrides =
                mOverridesToRemoveByPackageConfigCaptor.getValue().packageNameToOverridesToRemove;
        assertThat(packageNameToAddedOverrides.keySet()).containsExactly(PACKAGE_1, PACKAGE_3);
        assertThat(packageNameToRemovedOverrides.keySet()).containsExactly(PACKAGE_2, PACKAGE_3);
        // Package 1
        assertThat(packageNameToAddedOverrides.get(PACKAGE_1).overrides.keySet()).containsExactly(
                789L);
        // Package 2
        assertThat(packageNameToRemovedOverrides.get(PACKAGE_2).changeIds).containsExactly(456L,
                789L);
        // Package 3
        assertThat(packageNameToAddedOverrides.get(PACKAGE_3).overrides.keySet()).containsExactly(
                456L);
        assertThat(packageNameToRemovedOverrides.get(PACKAGE_3).changeIds).containsExactly(123L,
                789L);
        // Package 4 (not applied because it hasn't changed after the listener was added)
    }

    @Test
    public void onPropertiesChanged_removeOverridesFlagChangedNoPackageOverridesFlags_removesOnly()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456,789")
                .setString(PACKAGE_1, "")
                .setString(PACKAGE_2, "").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_REMOVE_OVERRIDES,
                        PACKAGE_1 + "=123:456," + PACKAGE_2 + "=*").build());

        verify(mPlatformCompat, never()).putAllOverridesOnReleaseBuilds(
                any(CompatibilityOverridesByPackageConfig.class));
        verify(mPlatformCompat, times(2)).removeAllOverridesOnReleaseBuilds(
                mOverridesToRemoveByPackageConfigCaptor.capture());
        List<CompatibilityOverridesToRemoveByPackageConfig> configs =
                mOverridesToRemoveByPackageConfigCaptor.getAllValues();
        assertThat(configs.size()).isAtLeast(2);
        Map<String, CompatibilityOverridesToRemoveConfig> firstPackageNameToRemovedOverrides =
                configs.get(configs.size() - 2).packageNameToOverridesToRemove;
        Map<String, CompatibilityOverridesToRemoveConfig> secondPackageNameToRemovedOverrides =
                configs.get(configs.size() - 1).packageNameToOverridesToRemove;
        assertThat(firstPackageNameToRemovedOverrides.keySet()).containsExactly(PACKAGE_1,
                PACKAGE_2);
        assertThat(secondPackageNameToRemovedOverrides.keySet()).containsExactly(PACKAGE_1);
        // Package 1
        assertThat(firstPackageNameToRemovedOverrides.get(PACKAGE_1).changeIds).containsExactly(
                123L, 456L);
        assertThat(secondPackageNameToRemovedOverrides.get(PACKAGE_1).changeIds).containsExactly(
                789L);
        // Package 2
        assertThat(firstPackageNameToRemovedOverrides.get(PACKAGE_2).changeIds).containsExactly(
                123L, 456L, 789L);
    }

    @Test
    public void onPropertiesChanged_removeOverridesFlagAndSomePackageOverrideFlagsChanged_ok()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456,789")
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_1 + "=123:456")
                .setString(PACKAGE_1, "123:::true,789:::false")
                .setString(PACKAGE_3, "456:::false,789:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 0);

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_2 + "=123," + PACKAGE_3 + "=789")
                .setString(PACKAGE_2, "123:::true").build());

        verify(mPlatformCompat).putAllOverridesOnReleaseBuilds(
                mOverridesToAddByPackageConfigCaptor.capture());
        verify(mPlatformCompat, times(2)).removeAllOverridesOnReleaseBuilds(
                mOverridesToRemoveByPackageConfigCaptor.capture());
        Map<String, CompatibilityOverrideConfig> packageNameToAddedOverrides =
                mOverridesToAddByPackageConfigCaptor.getValue().packageNameToOverrides;
        List<CompatibilityOverridesToRemoveByPackageConfig> removeConfigs =
                mOverridesToRemoveByPackageConfigCaptor.getAllValues();
        assertThat(removeConfigs.size()).isAtLeast(2);
        Map<String, CompatibilityOverridesToRemoveConfig> firstPackageNameToRemovedOverrides =
                removeConfigs.get(removeConfigs.size() - 2).packageNameToOverridesToRemove;
        Map<String, CompatibilityOverridesToRemoveConfig> secondPackageNameToRemovedOverrides =
                removeConfigs.get(removeConfigs.size() - 1).packageNameToOverridesToRemove;
        assertThat(packageNameToAddedOverrides.keySet()).containsExactly(PACKAGE_1, PACKAGE_3);
        assertThat(firstPackageNameToRemovedOverrides.keySet()).containsExactly(PACKAGE_2,
                PACKAGE_3);
        assertThat(secondPackageNameToRemovedOverrides.keySet()).containsExactly(PACKAGE_1,
                PACKAGE_2,
                PACKAGE_3);
        // Package 1
        assertThat(packageNameToAddedOverrides.get(PACKAGE_1).overrides.keySet()).containsExactly(
                123L, 789L);
        assertThat(secondPackageNameToRemovedOverrides.get(PACKAGE_1).changeIds).containsExactly(
                456L);
        // Package 2
        assertThat(firstPackageNameToRemovedOverrides.get(PACKAGE_2).changeIds).containsExactly(
                123L);
        assertThat(secondPackageNameToRemovedOverrides.get(PACKAGE_2).changeIds).containsExactly(
                456L, 789L);
        // Package 3
        assertThat(packageNameToAddedOverrides.get(PACKAGE_3).overrides.keySet()).containsExactly(
                456L);
        assertThat(firstPackageNameToRemovedOverrides.get(PACKAGE_3).changeIds).containsExactly(
                789L);
        assertThat(secondPackageNameToRemovedOverrides.get(PACKAGE_3).changeIds).containsExactly(
                123L);
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

        verify(mPlatformCompat).putAllOverridesOnReleaseBuilds(
                mOverridesToAddByPackageConfigCaptor.capture());
        verify(mPlatformCompat, times(2)).removeAllOverridesOnReleaseBuilds(
                mOverridesToRemoveByPackageConfigCaptor.capture());
        Map<String, CompatibilityOverrideConfig> packageNameToAddedOverrides =
                mOverridesToAddByPackageConfigCaptor.getValue().packageNameToOverrides;
        List<CompatibilityOverridesToRemoveByPackageConfig> removeConfigs =
                mOverridesToRemoveByPackageConfigCaptor.getAllValues();
        assertThat(removeConfigs.size()).isAtLeast(2);
        Map<String, CompatibilityOverridesToRemoveConfig> firstPackageNameToRemovedOverrides =
                removeConfigs.get(removeConfigs.size() - 2).packageNameToOverridesToRemove;
        Map<String, CompatibilityOverridesToRemoveConfig> secondPackageNameToRemovedOverrides =
                removeConfigs.get(removeConfigs.size() - 1).packageNameToOverridesToRemove;
        assertThat(packageNameToAddedOverrides.keySet()).containsExactly(PACKAGE_2);
        assertThat(firstPackageNameToRemovedOverrides.keySet()).containsExactly(PACKAGE_1);
        assertThat(secondPackageNameToRemovedOverrides.keySet()).containsExactly(PACKAGE_2);
        // Package 1
        assertThat(firstPackageNameToRemovedOverrides.get(PACKAGE_1).changeIds).containsExactly(
                123L, 456L, 789L);
        // Package 2
        assertThat(packageNameToAddedOverrides.get(PACKAGE_2).overrides.keySet()).containsExactly(
                123L);
        assertThat(secondPackageNameToRemovedOverrides.get(PACKAGE_2).changeIds).containsExactly(
                456L, 789L);
    }

    @Test
    public void onPropertiesChanged_platformCompatThrowsExceptionForPutCall_skipsFailedCall()
            throws Exception {
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_4, /* versionCode= */ 0);
        doThrow(new RemoteException()).when(mPlatformCompat).putAllOverridesOnReleaseBuilds(
                any(CompatibilityOverridesByPackageConfig.class));

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456")
                .setString(PACKAGE_1, "123:::true")
                .setString(PACKAGE_2, "123:::true")
                .setString(PACKAGE_3, "123:::true")
                .setString(PACKAGE_4, "123:::true").build());

        verify(mPlatformCompat).putAllOverridesOnReleaseBuilds(
                any(CompatibilityOverridesByPackageConfig.class));
        verify(mPlatformCompat).removeAllOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveByPackageConfig.class));
    }

    @Test
    public void onPropertiesChanged_platformCompatThrowsExceptionForRemoveCall_skipsFailedCall()
            throws Exception {
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_2, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_3, /* versionCode= */ 0);
        mockGetApplicationInfo(PACKAGE_4, /* versionCode= */ 0);
        doThrow(new RemoteException()).when(mPlatformCompat).removeAllOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveByPackageConfig.class));

        mService.registerDeviceConfigListeners();
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "123,456")
                .setString(PACKAGE_1, "123:::true")
                .setString(PACKAGE_2, "123:::true")
                .setString(PACKAGE_3, "123:::true")
                .setString(PACKAGE_4, "123:::true").build());

        verify(mPlatformCompat).putAllOverridesOnReleaseBuilds(
                any(CompatibilityOverridesByPackageConfig.class));
        verify(mPlatformCompat).removeAllOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveByPackageConfig.class));
    }

    @Test
    public void packageReceiver_packageAddedIntentDataIsNull_doesNothing() throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "101:::true").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(PACKAGE_1, "201:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mPackageReceiver.onReceive(mMockContext, new Intent(ACTION_PACKAGE_ADDED));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
    }

    @Test
    public void packageReceiver_actionIsNull_doesNothing() throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "101:::true").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(PACKAGE_1, "201:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, /* action= */ null));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
    }

    @Test
    public void packageReceiver_unsupportedAction_doesNothing() throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "101:::true").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(PACKAGE_1, "201:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_USER_SWITCHED));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
    }

    @Test
    public void packageReceiver_packageAddedIntentPackageNotInstalled_doesNothing()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "101:::true").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(PACKAGE_1, "201:::true").build());
        mockGetApplicationInfoNotInstalled(PACKAGE_1);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_ADDED));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
    }

    @Test
    public void packageReceiver_packageAddedIntentNoOverridesForPackage_doesNothing()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_2, "101:::true").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(PACKAGE_3, "201:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_ADDED));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
    }

    @Test
    public void packageReceiver_packageAddedIntent_appliesOverridesFromAllNamespaces()
            throws Exception {
        // We're adding the owned_change_ids flag to make sure it's ignored.
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "101,102,103")
                .setString(PACKAGE_1, "101:::true")
                .setString(PACKAGE_2, "102:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(FLAG_OWNED_CHANGE_IDS, "201,202,203")
                .setString(PACKAGE_3, "201:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_3)
                .setString(FLAG_OWNED_CHANGE_IDS, "301,302")
                .setString(PACKAGE_1, "301:::true,302:::false")
                .setString(PACKAGE_2, "302:::false").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_ADDED));

        verify(mPlatformCompat, times(2)).putOverridesOnReleaseBuilds(
                mOverridesToAddConfigCaptor.capture(), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
        List<CompatibilityOverrideConfig> configs = mOverridesToAddConfigCaptor.getAllValues();
        assertThat(configs.get(0).overrides.keySet()).containsExactly(101L);
        assertThat(configs.get(1).overrides.keySet()).containsExactly(301L, 302L);
    }

    @Test
    public void packageReceiver_packageChangedIntent_appliesOverrides()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "101:::true,103:::false").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_CHANGED));

        verify(mPlatformCompat).putOverridesOnReleaseBuilds(
                mOverridesToAddConfigCaptor.capture(), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
        assertThat(mOverridesToAddConfigCaptor.getValue().overrides.keySet()).containsExactly(101L,
                103L);
    }

    @Test
    public void packageReceiver_packageAddedIntentRemoveOverridesSetForSomeNamespaces_skipsIds()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_1 + "=103," + PACKAGE_2 + "=101")
                .setString(PACKAGE_1, "101:::true,103:::false")
                .setString(PACKAGE_2, "102:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(PACKAGE_1, "201:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_3)
                .setString(FLAG_REMOVE_OVERRIDES, PACKAGE_1 + "=301," + PACKAGE_3 + "=302")
                .setString(PACKAGE_1, "301:::true,302:::false,303:::true")
                .setString(PACKAGE_3, "302:::false").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_ADDED));

        verify(mPlatformCompat, times(3)).putOverridesOnReleaseBuilds(
                mOverridesToAddConfigCaptor.capture(), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
        List<CompatibilityOverrideConfig> configs = mOverridesToAddConfigCaptor.getAllValues();
        assertThat(configs.get(0).overrides.keySet()).containsExactly(101L);
        assertThat(configs.get(1).overrides.keySet()).containsExactly(201L);
        assertThat(configs.get(2).overrides.keySet()).containsExactly(302L, 303L);
    }

    @Test
    public void packageReceiver_packageRemovedIntentNoOverridesForPackage_doesNothing()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "101,102")
                .setString(PACKAGE_2, "101:::true").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(FLAG_OWNED_CHANGE_IDS, "201,202")
                .setString(PACKAGE_3, "201:::true").build());
        mockGetApplicationInfoNotInstalled(PACKAGE_1);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_REMOVED));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
    }

    @Test
    public void packageReceiver_packageRemovedIntentPackageInstalledForAnotherUser_doesNothing()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "101,102,103")
                .setString(PACKAGE_1, "101:::true,103:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(FLAG_OWNED_CHANGE_IDS, "201,202")
                .setString(PACKAGE_1, "202:::false").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_REMOVED));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
    }

    @Test
    public void packageReceiver_packageRemovedIntent_removesOwnedOverridesForNamespacesWithPackage()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "101,102,103")
                .setString(PACKAGE_1, "101:::true,103:::false")
                .setString(PACKAGE_2, "102:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(FLAG_OWNED_CHANGE_IDS, "201")
                .setString(PACKAGE_3, "201:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_3)
                .setString(FLAG_OWNED_CHANGE_IDS, "301,302")
                .setString(PACKAGE_1, "302:::false")
                .setString(PACKAGE_2, "301:::true").build());
        mockGetApplicationInfoNotInstalled(PACKAGE_1);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_REMOVED));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, times(2)).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_1));
        List<CompatibilityOverridesToRemoveConfig> configs =
                mOverridesToRemoveConfigCaptor.getAllValues();
        assertThat(configs.get(0).changeIds).containsExactly(101L, 102L, 103L);
        assertThat(configs.get(1).changeIds).containsExactly(301L, 302L);
    }

    @Test
    public void packageReceiver_packageRemovedIntentNoOwnedIdsForSomeNamespace_skipsNamespace()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(FLAG_OWNED_CHANGE_IDS, "101,102")
                .setString(PACKAGE_1, "101:::true").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(PACKAGE_1, "201:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_3)
                .setString(FLAG_OWNED_CHANGE_IDS, "301")
                .setString(PACKAGE_1, "301:::true").build());
        mockGetApplicationInfoNotInstalled(PACKAGE_1);

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_REMOVED));

        verify(mPlatformCompat, never()).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, times(2)).removeOverridesOnReleaseBuilds(
                mOverridesToRemoveConfigCaptor.capture(), eq(PACKAGE_1));
        List<CompatibilityOverridesToRemoveConfig> configs =
                mOverridesToRemoveConfigCaptor.getAllValues();
        assertThat(configs.get(0).changeIds).containsExactly(101L, 102L);
        assertThat(configs.get(1).changeIds).containsExactly(301L);
    }

    @Test
    public void packageReceiver_platformCompatThrowsExceptionForSomeNamespace_skipsFailedCall()
            throws Exception {
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_1)
                .setString(PACKAGE_1, "101:::true").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_2)
                .setString(PACKAGE_1, "201:::false").build());
        DeviceConfig.setProperties(new Properties.Builder(NAMESPACE_3)
                .setString(PACKAGE_1, "301:::true").build());
        mockGetApplicationInfo(PACKAGE_1, /* versionCode= */ 0);
        doThrow(new RemoteException()).when(mPlatformCompat).putOverridesOnReleaseBuilds(
                argThat(config -> config.overrides.containsKey(201L)), eq(PACKAGE_1));

        mPackageReceiver.onReceive(mMockContext,
                createPackageIntent(PACKAGE_1, ACTION_PACKAGE_ADDED));

        verify(mPlatformCompat, times(3)).putOverridesOnReleaseBuilds(
                any(CompatibilityOverrideConfig.class), eq(PACKAGE_1));
        verify(mPlatformCompat, never()).removeOverridesOnReleaseBuilds(
                any(CompatibilityOverridesToRemoveConfig.class), eq(PACKAGE_1));
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

    private Intent createPackageIntent(String packageName, @Nullable String action) {
        return new Intent(action, Uri.parse("package:" + packageName));
    }
}
