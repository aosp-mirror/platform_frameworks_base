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

package com.android.systemui.battery;

import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;

import static com.android.systemui.util.mockito.KotlinMockitoHelpersKt.eq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.os.Handler;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BatteryMeterViewControllerTest extends SysuiTestCase {
    @Mock
    private BatteryMeterView mBatteryMeterView;

    @Mock
    private UserTracker mUserTracker;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private TunerService mTunerService;
    @Mock
    private Handler mHandler;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private BatteryController mBatteryController;
    private FakeFeatureFlags mFakeFeatureFlags = new FakeFeatureFlags();

    private BatteryMeterViewController mController;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mBatteryMeterView.getContext()).thenReturn(mContext);
        when(mBatteryMeterView.getResources()).thenReturn(mContext.getResources());
    }

    @Test
    public void onViewAttached_callbacksRegistered() {
        initController();
        mController.onViewAttached();

        verify(mConfigurationController).addCallback(any());
        verify(mTunerService).addTunable(any(), any());
        verify(mContentResolver).registerContentObserver(
                eq(Settings.System.getUriFor(SHOW_BATTERY_PERCENT)), anyBoolean(), any(), anyInt()
        );
        verify(mContentResolver).registerContentObserver(
                eq(Settings.Global.getUriFor(Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME)),
                anyBoolean(),
                any()
        );
        verify(mBatteryController).addCallback(any());
    }

    @Test
    public void onViewDetached_callbacksUnregistered() {
        initController();
        // Set everything up first.
        mController.onViewAttached();

        mController.onViewDetached();

        verify(mConfigurationController).removeCallback(any());
        verify(mTunerService).removeTunable(any());
        verify(mContentResolver).unregisterContentObserver(any());
        verify(mBatteryController).removeCallback(any());
    }

    @Test
    public void ignoreTunerUpdates_afterOnViewAttached_callbackUnregistered() {
        initController();
        // Start out receiving tuner updates
        mController.onViewAttached();

        mController.ignoreTunerUpdates();

        verify(mTunerService).removeTunable(any());
    }

    @Test
    public void ignoreTunerUpdates_beforeOnViewAttached_callbackNeverRegistered() {
        initController();

        mController.ignoreTunerUpdates();

        mController.onViewAttached();

        verify(mTunerService, never()).addTunable(any(), any());
    }

    private void initController() {
        mController = new BatteryMeterViewController(
                mBatteryMeterView,
                StatusBarLocation.HOME,
                mUserTracker,
                mConfigurationController,
                mTunerService,
                mHandler,
                mContentResolver,
                mFakeFeatureFlags,
                mBatteryController
        );
    }
}
