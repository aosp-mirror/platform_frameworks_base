/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.usbtest;

import static android.provider.Settings.Secure.USER_SETUP_COMPLETE;

import static com.android.server.usb.UsbProfileGroupSettingsManager.PROPERTY_RESTRICT_USB_OVERLAY_ACTIVITIES;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.Property;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.usb.UsbHandlerManager;
import com.android.server.usb.UsbProfileGroupSettingsManager;
import com.android.server.usb.UsbSettingsManager;
import com.android.server.usb.UsbUserSettingsManager;
import com.android.server.usb.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.usb.UsbProfileGroupSettingsManager}.
 * Note: MUST claim MANAGE_USB permission in Manifest
 */
@RunWith(AndroidJUnit4.class)
public class UsbProfileGroupSettingsManagerTest {

    private static final String TEST_PACKAGE_NAME = "testPkg";

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private UserHandle mUserHandle;
    @Mock
    private UsbSettingsManager mUsbSettingsManager;
    @Mock
    private UsbHandlerManager mUsbHandlerManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private UsbUserSettingsManager mUsbUserSettingsManager;
    @Mock
    private Property mRestrictUsbOverlayActivitiesProperty;
    @Mock
    private UsbDevice mUsbDevice;

    private MockContentResolver mContentResolver;
    private MockitoSession mStaticMockSession;

    private UsbProfileGroupSettingsManager mUsbProfileGroupSettingsManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(Flags.class)
                .strictness(Strictness.WARN)
                .startMocking();

        when(mUsbSettingsManager.getSettingsForUser(anyInt())).thenReturn(mUsbUserSettingsManager);
        when(mUserManager.getEnabledProfiles(anyInt()))
                .thenReturn(List.of(Mockito.mock(UserInfo.class)));

        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());

        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getResources()).thenReturn(Mockito.mock(Resources.class));
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mContext.getSystemService(ActivityManager.class)).thenReturn(mActivityManager);
        when(mContext.createPackageContextAsUser(anyString(), anyInt(), any(UserHandle.class)))
                .thenReturn(mContext);

        mUsbProfileGroupSettingsManager = new UsbProfileGroupSettingsManager(
                mContext, mUserHandle, mUsbSettingsManager, mUsbHandlerManager);

        setupDefaultConfiguration();
    }

    /**
     * Setups the following configuration
     *
     * <ul>
     * <li>Flag is enabled
     * <li>Device setup has completed
     * <li>There is a foreground activity with MANAGE_USB permission
     * <li>The foreground activity has PROPERTY_RESTRICT_USB_OVERLAY_ACTIVITIES enabled
     * </ul>
     */
    private void setupDefaultConfiguration() throws NameNotFoundException {
        when(Flags.allowRestrictionOfOverlayActivities()).thenReturn(true);

        Settings.Secure.putInt(mContentResolver, USER_SETUP_COMPLETE, 1);

        ActivityManager.RunningAppProcessInfo mRunningAppProcessInfo =
                new ActivityManager.RunningAppProcessInfo();
        mRunningAppProcessInfo.pkgList = new String[] { TEST_PACKAGE_NAME };
        when(mActivityManager.getRunningAppProcesses()).thenReturn(List.of(mRunningAppProcessInfo));

        PackageInfo mPackageInfo = new PackageInfo();
        mPackageInfo.packageName = TEST_PACKAGE_NAME;
        mPackageInfo.applicationInfo = Mockito.mock(ApplicationInfo.class);
        when(mPackageManager.getPackageInfo(TEST_PACKAGE_NAME, 0)).thenReturn(mPackageInfo);
        when(mPackageManager.getPackagesHoldingPermissions(
                new String[] { android.Manifest.permission.MANAGE_USB },
                PackageManager.MATCH_SYSTEM_ONLY))
                .thenReturn(List.of(mPackageInfo));

        when(mRestrictUsbOverlayActivitiesProperty.getBoolean()).thenReturn(true);
        when(mPackageManager.getProperty(
                eq(PROPERTY_RESTRICT_USB_OVERLAY_ACTIVITIES), eq(TEST_PACKAGE_NAME)))
                .thenReturn(mRestrictUsbOverlayActivitiesProperty);
    }

    @After
    public void tearDown() throws Exception {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testDeviceAttached_foregroundActivityWithManifestField_resolveActivityNotCalled() {
        mUsbProfileGroupSettingsManager.deviceAttached(mUsbDevice);

        verify(mUsbUserSettingsManager, times(0)).queryIntentActivities(any(Intent.class));
    }

    @Test
    public void testDeviceAttached_noForegroundActivity_resolveActivityCalled() {
        when(mActivityManager.getRunningAppProcesses()).thenReturn(new ArrayList<>());

        mUsbProfileGroupSettingsManager.deviceAttached(mUsbDevice);

        verify(mUsbUserSettingsManager).queryIntentActivities(any(Intent.class));
    }

    @Test
    public void testDeviceAttached_noForegroundActivityWithUsbPermission_resolveActivityCalled() {
        when(mPackageManager.getPackagesHoldingPermissions(
                new String[] { android.Manifest.permission.MANAGE_USB },
                PackageManager.MATCH_SYSTEM_ONLY))
                .thenReturn(new ArrayList<>());

        mUsbProfileGroupSettingsManager.deviceAttached(mUsbDevice);

        verify(mUsbUserSettingsManager).queryIntentActivities(any(Intent.class));
    }

    @Test
    public void testDeviceAttached_restricUsbOverlayPropertyDisabled_resolveActivityCalled() {
        when(mRestrictUsbOverlayActivitiesProperty.getBoolean()).thenReturn(false);

        mUsbProfileGroupSettingsManager.deviceAttached(mUsbDevice);

        verify(mUsbUserSettingsManager).queryIntentActivities(any(Intent.class));
    }

    @Test
    public void testDeviceAttached_flagFalse_resolveActivityCalled() {
        when(Flags.allowRestrictionOfOverlayActivities()).thenReturn(false);

        mUsbProfileGroupSettingsManager.deviceAttached(mUsbDevice);

        verify(mUsbUserSettingsManager).queryIntentActivities(any(Intent.class));
    }

    @Test
    public void
            testDeviceAttached_setupNotCompleteAndNoBlockingActivities_resolveActivityNotCalled() {
        when(mRestrictUsbOverlayActivitiesProperty.getBoolean()).thenReturn(false);
        Settings.Secure.putInt(mContentResolver, USER_SETUP_COMPLETE, 0);

        mUsbProfileGroupSettingsManager.deviceAttached(mUsbDevice);

        verify(mUsbUserSettingsManager, times(0)).queryIntentActivities(any(Intent.class));
    }
}
