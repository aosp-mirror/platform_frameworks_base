/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.location.LocationManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.BootCompleteCache;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.appops.AppOpItem;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.LocationController.LocationChangeCallback;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.settings.FakeSettings;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class LocationControllerImplTest extends SysuiTestCase {

    private LocationControllerImpl mLocationController;
    private TestableLooper mTestableLooper;
    private DeviceConfigProxy mDeviceConfigProxy;
    private UiEventLoggerFake mUiEventLogger;
    private FakeSettings mSecureSettings;

    @Mock private PackageManager mPackageManager;
    @Mock private AppOpsController mAppOpsController;
    @Mock private UserTracker mUserTracker;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mUserTracker.getUserId()).thenReturn(UserHandle.USER_SYSTEM);
        when(mUserTracker.getUserHandle()).thenReturn(UserHandle.SYSTEM);
        when(mUserTracker.getUserProfiles())
                .thenReturn(ImmutableList.of(new UserInfo(0, "name", 0)));
        mDeviceConfigProxy = new DeviceConfigProxyFake();
        mUiEventLogger = new UiEventLoggerFake();
        mSecureSettings = new FakeSettings();
        mTestableLooper = TestableLooper.get(this);

        mLocationController = new LocationControllerImpl(mContext,
                mAppOpsController,
                mDeviceConfigProxy,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                mock(BroadcastDispatcher.class),
                mock(BootCompleteCache.class),
                mUserTracker,
                mPackageManager,
                mUiEventLogger,
                mSecureSettings);

        mTestableLooper.processAllMessages();
    }

    @Test
    public void testRemoveSelfActive_DoesNotCrash() {
        LocationController.LocationChangeCallback callback = new LocationChangeCallback() {
            @Override
            public void onLocationActiveChanged(boolean active) {
                mLocationController.removeCallback(this);
            }
        };
        mLocationController.addCallback(callback);
        mLocationController.addCallback(mock(LocationChangeCallback.class));

        when(mAppOpsController.getActiveAppOps()).thenReturn(ImmutableList.of());
        mLocationController.onActiveStateChanged(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, 0,
                "", false);
        when(mAppOpsController.getActiveAppOps())
                .thenReturn(ImmutableList.of(
                        new AppOpItem(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, 0, "",
                                System.currentTimeMillis())));
        mLocationController.onActiveStateChanged(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, 0,
                "", true);

        mTestableLooper.processAllMessages();
    }

    @Test
    public void testRemoveSelfSettings_DoesNotCrash() {
        LocationController.LocationChangeCallback callback = new LocationChangeCallback() {
            @Override
            public void onLocationSettingsChanged(boolean isEnabled) {
                mLocationController.removeCallback(this);
            }
        };
        mLocationController.addCallback(callback);
        mLocationController.addCallback(mock(LocationChangeCallback.class));

        mTestableLooper.processAllMessages();
    }

    @Test
    public void testAddCallback_notifiedImmediately() {
        LocationChangeCallback callback = mock(LocationChangeCallback.class);

        mLocationController.addCallback(callback);

        mTestableLooper.processAllMessages();

        verify(callback).onLocationSettingsChanged(anyBoolean());
    }

    @Test
    public void testCallbackNotified() {
        LocationChangeCallback callback = mock(LocationChangeCallback.class);

        mLocationController.addCallback(callback);

        mTestableLooper.processAllMessages();

        mLocationController.onReceive(mContext, new Intent(LocationManager.MODE_CHANGED_ACTION));

        mTestableLooper.processAllMessages();

        verify(callback, times(2)).onLocationSettingsChanged(anyBoolean());

        when(mAppOpsController.getActiveAppOps())
                .thenReturn(ImmutableList.of(
                        new AppOpItem(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, 0, "",
                                System.currentTimeMillis())));
        mLocationController.onActiveStateChanged(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, 0,
                "", true);

        mTestableLooper.processAllMessages();

        verify(callback, times(1)).onLocationActiveChanged(anyBoolean());
        assertThat(mUiEventLogger.numLogs()).isEqualTo(1);
        assertThat(mUiEventLogger.eventId(0)).isEqualTo(
                LocationControllerImpl.LocationIndicatorEvent.LOCATION_INDICATOR_MONITOR_HIGH_POWER
                        .getId());
    }

    @Test
    public void testCallbackNotified_additionalOps() {
        // Return -1 for non system apps
        when(mPackageManager.getPermissionFlags(any(), any(), any())).thenReturn(-1);
        LocationChangeCallback callback = mock(LocationChangeCallback.class);
        mLocationController.addCallback(callback);
        mDeviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_SMALL_ENABLED,
                "true",
                true);
        mTestableLooper.processAllMessages();

        when(mAppOpsController.getActiveAppOps())
                .thenReturn(ImmutableList.of(
                        new AppOpItem(AppOpsManager.OP_FINE_LOCATION, 0,
                                "com.third.party.app",
                                System.currentTimeMillis())));
        mLocationController.onActiveStateChanged(AppOpsManager.OP_FINE_LOCATION, 0,
                "ccom.third.party.app", true);

        mTestableLooper.processAllMessages();

        verify(callback, times(1)).onLocationActiveChanged(true);

        when(mAppOpsController.getActiveAppOps()).thenReturn(ImmutableList.of());
        mLocationController.onActiveStateChanged(AppOpsManager.OP_FINE_LOCATION, 0,
                "com.third.party.app", false);
        mTestableLooper.processAllMessages();

        verify(callback, times(1)).onLocationActiveChanged(false);

        when(mAppOpsController.getActiveAppOps()).thenReturn(ImmutableList.of());
        mLocationController.onActiveStateChanged(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, 0,
                "com.third.party.app", true);
        mTestableLooper.processAllMessages();
        verify(callback, times(1)).onLocationActiveChanged(true);
    }

    @Test
    public void testCallbackNotified_additionalOps_shouldNotShowSystem() {
        LocationChangeCallback callback = mock(LocationChangeCallback.class);
        mLocationController.addCallback(callback);
        mDeviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_SMALL_ENABLED,
                "true",
                true);
        mTestableLooper.processAllMessages();

        when(mAppOpsController.getActiveAppOps())
                .thenReturn(ImmutableList.of(
                        new AppOpItem(AppOpsManager.OP_FINE_LOCATION, 0,
                                "com.system.app",
                                System.currentTimeMillis())));
        mLocationController.onActiveStateChanged(AppOpsManager.OP_FINE_LOCATION, 0,
                "com.system.app", true);

        mTestableLooper.processAllMessages();

        verify(callback, times(0)).onLocationActiveChanged(true);
    }


    @Test
    public void testCallbackNotified_additionalOps_shouldShowSystem() {
        mSecureSettings.putInt(Settings.Secure.LOCATION_SHOW_SYSTEM_OPS, 1);
        LocationChangeCallback callback = mock(LocationChangeCallback.class);
        mLocationController.addCallback(callback);
        mDeviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_SMALL_ENABLED,
                "true",
                true);
        mDeviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_SHOW_SYSTEM,
                "true",
                true);
        mTestableLooper.processAllMessages();

        when(mAppOpsController.getActiveAppOps())
                .thenReturn(ImmutableList.of(
                        new AppOpItem(AppOpsManager.OP_FINE_LOCATION, 0, "com.system.app",
                                System.currentTimeMillis())));
        mLocationController.onActiveStateChanged(AppOpsManager.OP_FINE_LOCATION, 0,
                "com.system.app", true);

        mTestableLooper.processAllMessages();

        verify(callback, times(1)).onLocationActiveChanged(true);

        when(mAppOpsController.getActiveAppOps()).thenReturn(ImmutableList.of());
        mLocationController.onActiveStateChanged(AppOpsManager.OP_FINE_LOCATION, 0,
                "com.system.app", false);
        mTestableLooper.processAllMessages();

        verify(callback, times(1)).onLocationActiveChanged(false);

        mSecureSettings.putInt(Settings.Secure.LOCATION_SHOW_SYSTEM_OPS, 0);
        mLocationController.onActiveStateChanged(AppOpsManager.OP_FINE_LOCATION, 0,
                "com.system.app", true);

        mTestableLooper.processAllMessages();

        // onLocationActive(true) was not called again because the setting is disabled.
        verify(callback, times(1)).onLocationActiveChanged(true);
    }

    @Test
    public void testCallbackNotified_verifyMetrics() {
        // Return -1 for non system apps and 0 for system apps.
        when(mPackageManager.getPermissionFlags(any(),
                eq("com.system.app"), any())).thenReturn(0);
        when(mPackageManager.getPermissionFlags(any(),
                eq("com.third.party.app"), any())).thenReturn(-1);
        LocationChangeCallback callback = mock(LocationChangeCallback.class);
        mLocationController.addCallback(callback);
        mDeviceConfigProxy.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_SMALL_ENABLED,
                "true",
                true);
        mTestableLooper.processAllMessages();

        when(mAppOpsController.getActiveAppOps())
                .thenReturn(ImmutableList.of(
                        new AppOpItem(AppOpsManager.OP_FINE_LOCATION, 0, "com.system.app",
                                System.currentTimeMillis()),
                        new AppOpItem(AppOpsManager.OP_COARSE_LOCATION, 0,
                                "com.third.party.app",
                                System.currentTimeMillis()),
                        new AppOpItem(AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION, 0,
                                "com.another.developer.app",
                                System.currentTimeMillis())));
        mLocationController.onActiveStateChanged(AppOpsManager.OP_FINE_LOCATION, 0,
                "com.system.app", true);

        mTestableLooper.processAllMessages();

        verify(callback, times(1)).onLocationActiveChanged(true);
        assertThat(mUiEventLogger.numLogs()).isEqualTo(3);
        assertThat(mUiEventLogger.eventId(0)).isEqualTo(
                LocationControllerImpl.LocationIndicatorEvent.LOCATION_INDICATOR_MONITOR_HIGH_POWER
                        .getId());
        // Even though the system access wasn't shown due to the device settings, ensure it was
        // still logged.
        assertThat(mUiEventLogger.eventId(1)).isEqualTo(
                LocationControllerImpl.LocationIndicatorEvent.LOCATION_INDICATOR_SYSTEM_APP
                        .getId());
        assertThat(mUiEventLogger.eventId(2)).isEqualTo(
                LocationControllerImpl.LocationIndicatorEvent.LOCATION_INDICATOR_NON_SYSTEM_APP
                        .getId());
        mUiEventLogger.getLogs().clear();

        when(mAppOpsController.getActiveAppOps()).thenReturn(ImmutableList.of());
        mLocationController.onActiveStateChanged(AppOpsManager.OP_FINE_LOCATION, 0,
                "com.system.app", false);
        mTestableLooper.processAllMessages();

        verify(callback, times(1)).onLocationActiveChanged(false);
        assertThat(mUiEventLogger.numLogs()).isEqualTo(0);
    }

    @Test
    public void testCallbackRemoved() {
        LocationChangeCallback callback = mock(LocationChangeCallback.class);

        mLocationController.addCallback(callback);
        mTestableLooper.processAllMessages();

        verify(callback).onLocationSettingsChanged(anyBoolean());
        mLocationController.removeCallback(callback);

        mTestableLooper.processAllMessages();

        mLocationController.onReceive(mContext, new Intent(LocationManager.MODE_CHANGED_ACTION));

        mTestableLooper.processAllMessages();

        // No new callbacks
        verify(callback).onLocationSettingsChanged(anyBoolean());
    }
}