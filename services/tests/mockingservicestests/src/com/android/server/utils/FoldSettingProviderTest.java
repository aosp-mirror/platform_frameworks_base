/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.utils;

import static com.android.server.utils.FoldSettingProvider.SETTING_VALUE_SLEEP_ON_FOLD;
import static com.android.server.utils.FoldSettingProvider.SETTING_VALUE_STAY_AWAKE_ON_FOLD;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.foldables.FoldLockSettingAvailabilityProvider;
import com.android.internal.util.SettingsWrapper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class FoldSettingProviderTest {

    private static final String SETTING_VALUE_INVALID = "invalid_fold_lock_behavior";

    @Mock
    private Context mContext;
    @Mock
    private SettingsWrapper mSettingsWrapper;
    @Mock
    private FoldLockSettingAvailabilityProvider mFoldLockSettingAvailabilityProvider;
    private ContentResolver mContentResolver;
    private FoldSettingProvider mFoldSettingProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContentResolver =
                InstrumentationRegistry.getInstrumentation().getContext().getContentResolver();
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        setFoldLockBehaviorAvailability(true);

        mFoldSettingProvider = new FoldSettingProvider(mContext, mSettingsWrapper,
                mFoldLockSettingAvailabilityProvider);
    }

    @Test
    public void foldSettingNotAvailable_returnDefaultSetting() {
        setFoldLockBehaviorAvailability(false);
        setFoldLockBehaviorSettingValue(SETTING_VALUE_STAY_AWAKE_ON_FOLD);
        mFoldSettingProvider = new FoldSettingProvider(mContext, mSettingsWrapper,
                mFoldLockSettingAvailabilityProvider);

        boolean shouldSelectiveStayAwakeOnFold =
                mFoldSettingProvider.shouldSelectiveStayAwakeOnFold();

        assertThat(shouldSelectiveStayAwakeOnFold).isTrue();
    }

    @Test
    public void foldSettingNotAvailable_notReturnStayAwakeOnFoldTrue() {
        setFoldLockBehaviorAvailability(false);
        setFoldLockBehaviorSettingValue(SETTING_VALUE_STAY_AWAKE_ON_FOLD);
        mFoldSettingProvider = new FoldSettingProvider(mContext, mSettingsWrapper,
                mFoldLockSettingAvailabilityProvider);

        boolean shouldStayAwakeOnFold = mFoldSettingProvider.shouldStayAwakeOnFold();

        assertThat(shouldStayAwakeOnFold).isFalse();
    }

    @Test
    public void foldSettingNotAvailable_notReturnSleepOnFoldTrue() {
        setFoldLockBehaviorAvailability(false);
        setFoldLockBehaviorSettingValue(SETTING_VALUE_SLEEP_ON_FOLD);
        mFoldSettingProvider = new FoldSettingProvider(mContext, mSettingsWrapper,
                mFoldLockSettingAvailabilityProvider);

        boolean shouldSleepOnFold = mFoldSettingProvider.shouldSleepOnFold();

        assertThat(shouldSleepOnFold).isFalse();
    }

    @Test
    public void foldSettingAvailable_returnCorrectFoldSetting() {
        setFoldLockBehaviorSettingValue(SETTING_VALUE_STAY_AWAKE_ON_FOLD);

        boolean shouldStayAwakeOnFold = mFoldSettingProvider.shouldStayAwakeOnFold();

        assertThat(shouldStayAwakeOnFold).isTrue();
    }

    @Test
    public void foldSettingInvalid_returnDefaultSetting() {
        setFoldLockBehaviorSettingValue(SETTING_VALUE_INVALID);

        boolean shouldSelectiveStayAwakeOnFold =
                mFoldSettingProvider.shouldSelectiveStayAwakeOnFold();

        assertThat(shouldSelectiveStayAwakeOnFold).isTrue();
    }

    @Test
    public void foldSettingNotDefined_returnDefaultSetting() {
        setFoldLockBehaviorSettingValue(null);

        boolean shouldSelectiveStayAwakeOnFold =
                mFoldSettingProvider.shouldSelectiveStayAwakeOnFold();

        assertThat(shouldSelectiveStayAwakeOnFold).isTrue();
    }

    private void setFoldLockBehaviorAvailability(boolean isFoldLockBehaviorEnabled) {
        when(mFoldLockSettingAvailabilityProvider.isFoldLockBehaviorAvailable()).thenReturn(
                isFoldLockBehaviorEnabled);
    }

    private void setFoldLockBehaviorSettingValue(String foldLockBehaviorSettingValue) {
        when(mSettingsWrapper.getStringForUser(any(),
                eq(Settings.System.FOLD_LOCK_BEHAVIOR),
                eq(UserHandle.USER_CURRENT))).thenReturn(foldLockBehaviorSettingValue);
    }
}
