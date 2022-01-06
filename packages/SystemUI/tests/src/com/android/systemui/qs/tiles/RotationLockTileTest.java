/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.provider.Settings.Secure.CAMERA_AUTOROTATE;

import static junit.framework.TestCase.assertEquals;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.SensorPrivacyManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.TextUtils;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class RotationLockTileTest extends SysuiTestCase {

    private static final String PACKAGE_NAME = "package_name";

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSTileHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private SensorPrivacyManager mPrivacyManager;
    @Mock
    private BatteryController mBatteryController;

    private SecureSettings mSecureSettings;
    private RotationLockController mController;
    private TestableLooper mTestableLooper;
    private RotationLockTile mLockTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);
        when(mHost.getUserContext()).thenReturn(mContext);

        mSecureSettings = new FakeSettings();
        mController = new RotationLockControllerImpl(mContext, mSecureSettings);

        mLockTile = new RotationLockTile(
                mHost,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mController,
                mPrivacyManager,
                mBatteryController,
                mSecureSettings
        );

        mLockTile.initialize();

        // We are not setting the mocks to listening, so we trigger a first refresh state to
        // set the initial state
        mLockTile.refreshState();

        mTestableLooper.processAllMessages();

        mContext.setMockPackageManager(mPackageManager);
        doReturn(PACKAGE_NAME).when(mPackageManager).getRotationResolverPackageName();
        doReturn(PackageManager.PERMISSION_GRANTED).when(mPackageManager).checkPermission(
                Manifest.permission.CAMERA, PACKAGE_NAME);

        when(mBatteryController.isPowerSave()).thenReturn(false);
        when(mPrivacyManager.isSensorPrivacyEnabled(CAMERA)).thenReturn(false);
        enableAutoRotation();
        enableCameraBasedRotation();

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testSecondaryString_cameraRotateOn_returnsFaceBased() {
        assertEquals("On - Face-based", mLockTile.getState().secondaryLabel);
    }

    @Test
    public void testSecondaryString_rotateOff_isEmpty() {
        disableAutoRotation();

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertTrue(TextUtils.isEmpty(mLockTile.getState().secondaryLabel));
    }

    @Test
    public void testSecondaryString_cameraRotateOff_isEmpty() {
        disableCameraBasedRotation();

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertTrue(TextUtils.isEmpty(mLockTile.getState().secondaryLabel));
    }

    @Test
    public void testSecondaryString_powerSaveEnabled_isEmpty() {
        when(mBatteryController.isPowerSave()).thenReturn(true);

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertTrue(TextUtils.isEmpty(mLockTile.getState().secondaryLabel));
    }

    @Test
    public void testSecondaryString_cameraDisabled_isEmpty() {
        when(mPrivacyManager.isSensorPrivacyEnabled(CAMERA)).thenReturn(true);

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertTrue(TextUtils.isEmpty(mLockTile.getState().secondaryLabel));
    }

    @Test
    public void testSecondaryString_noCameraPermission_isEmpty() {
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                Manifest.permission.CAMERA, PACKAGE_NAME);

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertTrue(TextUtils.isEmpty(mLockTile.getState().secondaryLabel));
    }

    private void enableAutoRotation() {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 1, UserHandle.USER_CURRENT);
    }

    private void disableAutoRotation() {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT);
    }

    private void enableCameraBasedRotation() {
        mSecureSettings.putIntForUser(
                CAMERA_AUTOROTATE, 1, UserHandle.USER_CURRENT);
    }

    private void disableCameraBasedRotation() {
        mSecureSettings.putIntForUser(
                CAMERA_AUTOROTATE, 0, UserHandle.USER_CURRENT);
    }
}
