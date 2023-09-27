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

package com.android.systemui.qs.tiles;

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;

import static junit.framework.TestCase.assertEquals;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.SensorPrivacyManager;
import android.os.Handler;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.res.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.DeviceStateRotationLockSettingController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockControllerImpl;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.wrapper.RotationPolicyWrapper;

import org.junit.After;
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
    private static final String[] DEFAULT_SETTINGS = new String[]{
            "0:0",
            "1:2"
    };

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSHost mHost;
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
    @Mock
    DeviceStateRotationLockSettingController mDeviceStateRotationLockSettingController;
    @Mock
    RotationPolicyWrapper mRotationPolicyWrapper;
    @Mock
    QsEventLogger mUiEventLogger;

    private RotationLockController mController;
    private TestableLooper mTestableLooper;
    private RotationLockTile mLockTile;
    private TestableResources mTestableResources;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        mTestableResources = mContext.getOrCreateTestableResources();

        when(mHost.getContext()).thenReturn(mContext);
        when(mHost.getUserContext()).thenReturn(mContext);
        mTestableResources.addOverride(com.android.internal.R.bool.config_allowRotationResolver,
                true);

        mController = new RotationLockControllerImpl(mRotationPolicyWrapper,
                mDeviceStateRotationLockSettingController, DEFAULT_SETTINGS);

        mLockTile = new RotationLockTile(
                mHost,
                mUiEventLogger,
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
                new FakeSettings()
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

    @After
    public void tearDown() {
        mLockTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testSecondaryString_cameraRotateOn_returnsFaceBased() {
        assertEquals(mContext.getString(R.string.rotation_lock_camera_rotation_on),
                mLockTile.getState().secondaryLabel.toString());
    }

    @Test
    public void testSecondaryString_rotateOff_isEmpty() {
        disableAutoRotation();

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals("", mLockTile.getState().secondaryLabel.toString());
    }

    @Test
    public void testSecondaryString_cameraRotateOff_isEmpty() {
        disableCameraBasedRotation();

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals("", mLockTile.getState().secondaryLabel.toString());
    }

    @Test
    public void testSecondaryString_powerSaveEnabled_isEmpty() {
        when(mBatteryController.isPowerSave()).thenReturn(true);

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals("", mLockTile.getState().secondaryLabel.toString());
    }

    @Test
    public void testSecondaryString_cameraDisabled_isEmpty() {
        when(mPrivacyManager.isSensorPrivacyEnabled(CAMERA)).thenReturn(true);

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals("", mLockTile.getState().secondaryLabel.toString());
    }

    @Test
    public void testSecondaryString_noCameraPermission_isEmpty() {
        doReturn(PackageManager.PERMISSION_DENIED).when(mPackageManager).checkPermission(
                Manifest.permission.CAMERA, PACKAGE_NAME);

        mLockTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals("", mLockTile.getState().secondaryLabel.toString());
    }

    @Test
    public void testSecondaryString_rotationResolverDisabled_isEmpty() {
        mTestableResources.addOverride(com.android.internal.R.bool.config_allowRotationResolver,
                false);
        RotationLockTile otherTile = new RotationLockTile(
                mHost,
                mUiEventLogger,
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
                new FakeSettings()
        );

        otherTile.refreshState();
        mTestableLooper.processAllMessages();

        assertEquals("", otherTile.getState().secondaryLabel.toString());

        destroyTile(otherTile);
    }

    @Test
    public void testIcon_whenDisabled_isOffState() {
        QSTile.BooleanState state = new QSTile.BooleanState();
        disableAutoRotation();

        mLockTile.handleUpdateState(state, /* arg= */ null);

        assertEquals(state.icon, QSTileImpl.ResourceIcon.get(R.drawable.qs_auto_rotate_icon_off));
    }

    @Test
    public void testIcon_whenEnabled_isOnState() {
        QSTile.BooleanState state = new QSTile.BooleanState();
        enableAutoRotation();

        mLockTile.handleUpdateState(state, /* arg= */ null);

        assertEquals(state.icon, QSTileImpl.ResourceIcon.get(R.drawable.qs_auto_rotate_icon_on));
    }


    private void destroyTile(QSTileImpl<?> tile) {
        tile.destroy();
        mTestableLooper.processAllMessages();
    }

    private void enableAutoRotation() {
        when(mRotationPolicyWrapper.isRotationLocked()).thenReturn(false);
    }

    private void disableAutoRotation() {
        when(mRotationPolicyWrapper.isRotationLocked()).thenReturn(true);
    }

    private void enableCameraBasedRotation() {
        when(mRotationPolicyWrapper.isCameraRotationEnabled()).thenReturn(true);
    }

    private void disableCameraBasedRotation() {
        when(mRotationPolicyWrapper.isCameraRotationEnabled()).thenReturn(false);
    }
}
