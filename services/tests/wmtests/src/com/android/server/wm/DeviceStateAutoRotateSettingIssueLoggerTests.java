/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.DeviceStateAutoRotateSettingIssueLogger.DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_THRESHOLD_MILLIS;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;

import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.wm.utils.CurrentTimeMillisSupplierFake;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link DeviceStateAutoRotateSettingIssueLogger}.
 *
 * <p>Build/Install/Run: atest WmTests:DeviceStateAutoRotateSettingIssueLoggerTests
 */
@SmallTest
@Presubmit
public class DeviceStateAutoRotateSettingIssueLoggerTests {
    private static final int DELAY = 500;

    private DeviceStateAutoRotateSettingIssueLogger mDeviceStateAutoRotateSettingIssueLogger;
    private StaticMockitoSession mStaticMockitoSession;
    @NonNull
    private CurrentTimeMillisSupplierFake mTestTimeSupplier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mStaticMockitoSession = mockitoSession().mockStatic(
                FrameworkStatsLog.class).startMocking();
        mTestTimeSupplier = new CurrentTimeMillisSupplierFake();
        mDeviceStateAutoRotateSettingIssueLogger =
                new DeviceStateAutoRotateSettingIssueLogger(mTestTimeSupplier);
    }

    @After
    public void teardown() {
        mStaticMockitoSession.finishMocking();
    }

    @Test
    public void onStateChange_deviceStateChangedFirst_isDeviceStateFirstTrue() {
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateChange();
        mTestTimeSupplier.delay(DELAY);
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateAutoRotateSettingChange();

        verify(() ->
                FrameworkStatsLog.write(
                        eq(FrameworkStatsLog.DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_REPORTED),
                        anyInt(),
                        eq(true)));
    }

    @Test
    public void onStateChange_autoRotateSettingChangedFirst_isDeviceStateFirstFalse() {
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateAutoRotateSettingChange();
        mTestTimeSupplier.delay(DELAY);
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateChange();

        verify(() ->
                FrameworkStatsLog.write(
                        eq(FrameworkStatsLog.DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_REPORTED),
                        anyInt(),
                        eq(false)));
    }

    @Test
    public void onStateChange_deviceStateDidNotChange_doNotReport() {
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateAutoRotateSettingChange();

        verify(() ->
                FrameworkStatsLog.write(
                        eq(FrameworkStatsLog.DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_REPORTED),
                        anyInt(),
                        anyBoolean()), never());
    }

    @Test
    public void onStateChange_autoRotateSettingDidNotChange_doNotReport() {
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateChange();

        verify(() ->
                FrameworkStatsLog.write(
                        eq(FrameworkStatsLog.DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_REPORTED),
                        anyInt(),
                        anyBoolean()), never());
    }

    @Test
    public void onStateChange_issueOccurred_correctDurationReported() {
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateChange();
        mTestTimeSupplier.delay(DELAY);
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateAutoRotateSettingChange();

        verify(() ->
                FrameworkStatsLog.write(
                        eq(FrameworkStatsLog.DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_REPORTED),
                        eq(DELAY),
                        anyBoolean()));
    }

    @Test
    public void onStateChange_durationLongerThanThreshold_doNotReport() {
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateChange();
        mTestTimeSupplier.delay(
                DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_THRESHOLD_MILLIS + DELAY);
        mDeviceStateAutoRotateSettingIssueLogger.onDeviceStateAutoRotateSettingChange();

        verify(() ->
                FrameworkStatsLog.write(
                        eq(FrameworkStatsLog.DEVICE_STATE_AUTO_ROTATE_SETTING_ISSUE_REPORTED),
                        anyInt(),
                        anyBoolean()), never());
    }
}
