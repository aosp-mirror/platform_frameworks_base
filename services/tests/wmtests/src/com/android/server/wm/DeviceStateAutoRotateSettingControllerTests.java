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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;
import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link DeviceStateAutoRotateSettingController}.
 *
 * <p>Build/Install/Run: atest WmTests:DeviceStateAutoRotateSettingControllerTests
 */
@SmallTest
@Presubmit
public class DeviceStateAutoRotateSettingControllerTests {
    private static final OffsettableClock sClock = new OffsettableClock.Stopped();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private DeviceStateAutoRotateSettingController mDeviceStateAutoRotateSettingController;
    @Mock
    private DeviceStateAutoRotateSettingIssueLogger mMockLogger;
    @Mock
    private Context mMockContext;
    @Mock
    private ContentResolver mMockContentResolver;
    @Captor
    private ArgumentCaptor<ContentObserver> mContentObserverCaptor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getContentResolver()).thenReturn(mMockContentResolver);
        mDeviceStateAutoRotateSettingController = new DeviceStateAutoRotateSettingController(
                mMockContext, mMockLogger, new TestHandler(null, sClock));
        verify(mMockContentResolver)
                .registerContentObserver(
                        eq(Settings.Secure.getUriFor(Settings.Secure.DEVICE_STATE_ROTATION_LOCK)),
                        anyBoolean(),
                        mContentObserverCaptor.capture());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DEVICE_STATE_AUTO_ROTATE_SETTING_LOGGING)
    public void loggingFlagEnabled_onDeviceStateChanged_loggerNotified() {
        mDeviceStateAutoRotateSettingController.onDeviceStateChange(
                DeviceStateController.DeviceState.FOLDED);

        verify(mMockLogger, times(1)).onDeviceStateChange();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_DEVICE_STATE_AUTO_ROTATE_SETTING_LOGGING)
    public void loggingFlagDisabled_onDeviceStateChanged_loggerNotNotified() {
        mDeviceStateAutoRotateSettingController.onDeviceStateChange(
                DeviceStateController.DeviceState.FOLDED);

        verify(mMockLogger, never()).onDeviceStateChange();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DEVICE_STATE_AUTO_ROTATE_SETTING_LOGGING)
    public void loggingFlagEnabled_settingChanged_loggerNotified() {
        mContentObserverCaptor.getValue().onChange(false);

        verify(mMockLogger, times(1)).onDeviceStateAutoRotateSettingChange();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_DEVICE_STATE_AUTO_ROTATE_SETTING_LOGGING)
    public void loggingFlagDisabled_settingChanged_loggerNotNotified() {
        mContentObserverCaptor.getValue().onChange(false);

        verify(mMockLogger, never()).onDeviceStateAutoRotateSettingChange();
    }
}
