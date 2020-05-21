/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.IActivityManager;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.UserManager;
import android.telecom.TelecomManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.SensorPrivacyController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.RingerModeLiveData;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.time.DateFormatUtil;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class PhoneStatusBarPolicyTest extends SysuiTestCase {

    private static final int DISPLAY_ID = 0;
    @Mock
    private StatusBarIconController mIconController;
    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private Executor mBackgroundExecutor;
    @Mock
    private CastController mCastController;
    @Mock
    private HotspotController mHotSpotController;
    @Mock
    private BluetoothController mBluetoothController;
    @Mock
    private NextAlarmController mNextAlarmController;
    @Mock
    private UserInfoController mUserInfoController;
    @Mock
    private RotationLockController mRotationLockController;
    @Mock
    private DataSaverController mDataSaverController;
    @Mock
    private ZenModeController mZenModeController;
    @Mock
    private DeviceProvisionedController mDeviceProvisionerController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private LocationController mLocationController;
    @Mock
    private SensorPrivacyController mSensorPrivacyController;
    @Mock
    private IActivityManager mIActivityManager;
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private RecordingController mRecordingController;
    @Mock
    private MediaDataManager mMediaDataManager;
    @Mock
    private TelecomManager mTelecomManager;
    @Mock
    private SharedPreferences mSharedPreferences;
    @Mock
    private DateFormatUtil mDateFormatUtil;
    @Mock
    private RingerModeTracker mRingerModeTracker;
    @Mock
    private RingerModeLiveData mRingerModeLiveData;
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    private Resources mResources;
    private PhoneStatusBarPolicy mPhoneStatusBarPolicy;

    @Before
    public void setup() {
        mResources = spy(getContext().getResources());
        mPhoneStatusBarPolicy = new PhoneStatusBarPolicy(mIconController, mCommandQueue,
                mBroadcastDispatcher, mBackgroundExecutor, mResources, mCastController,
                mHotSpotController, mBluetoothController, mNextAlarmController, mUserInfoController,
                mRotationLockController, mDataSaverController, mZenModeController,
                mDeviceProvisionerController, mKeyguardStateController, mLocationController,
                mSensorPrivacyController, mIActivityManager, mAlarmManager, mUserManager,
                mRecordingController, mMediaDataManager, mTelecomManager, DISPLAY_ID,
                mSharedPreferences, mDateFormatUtil, mRingerModeTracker);
        when(mRingerModeTracker.getRingerMode()).thenReturn(mRingerModeLiveData);
        when(mRingerModeTracker.getRingerModeInternal()).thenReturn(mRingerModeLiveData);
        clearInvocations(mIconController);
    }

    @Test
    public void testInit_registerMediaCallback() {
        mPhoneStatusBarPolicy.init();
        verify(mMediaDataManager).addListener(eq(mPhoneStatusBarPolicy));
    }

    @Test
    public void testOnMediaDataLoaded_updatesIcon_hasMedia() {
        String mediaSlot = mResources.getString(com.android.internal.R.string.status_bar_media);
        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);
        mPhoneStatusBarPolicy.onMediaDataLoaded(null, null);
        verify(mMediaDataManager).hasActiveMedia();
        verify(mIconController).setIconVisibility(eq(mediaSlot), eq(true));
    }

    @Test
    public void testOnMediaDataRemoved_updatesIcon_noMedia() {
        String mediaSlot = mResources.getString(com.android.internal.R.string.status_bar_media);
        mPhoneStatusBarPolicy.onMediaDataRemoved(null);
        verify(mMediaDataManager).hasActiveMedia();
        verify(mIconController).setIconVisibility(eq(mediaSlot), eq(false));
    }
}
