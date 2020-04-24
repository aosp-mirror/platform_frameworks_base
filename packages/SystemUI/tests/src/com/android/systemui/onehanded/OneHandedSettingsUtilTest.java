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

package com.android.systemui.onehanded;

import static com.android.systemui.onehanded.OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_LONG_IN_SECONDS;
import static com.android.systemui.onehanded.OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS;
import static com.android.systemui.onehanded.OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_NEVER;
import static com.android.systemui.onehanded.OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_SHORT_IN_SECONDS;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OneHandedSettingsUtilTest extends OneHandedTestCase {
    OneHandedSettingsUtil mOneHandedSettingsUtil;
    ContentResolver mContentResolver;
    ContentObserver mContentObserver;
    boolean mOnChanged;

    @Before
    public void setUp() {
        mOneHandedSettingsUtil = new OneHandedSettingsUtil();
        mContentResolver = mContext.getContentResolver();
        mContentObserver = new ContentObserver(mContext.getMainThreadHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                mOnChanged = true;
            }
        };
    }

    @Test
    public void testRegisterSecureKeyObserver() {
        final Uri result = mOneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.ONE_HANDED_MODE_ENABLED, mContentResolver, mContentObserver);

        assertThat(result).isNotNull();

        mOneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.ONE_HANDED_MODE_ENABLED, mContentResolver, mContentObserver);
    }

    @Test
    public void testUnregisterSecureKeyObserver() {
        mOneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.ONE_HANDED_MODE_ENABLED, mContentResolver, mContentObserver);
        mOneHandedSettingsUtil.unregisterSettingsKeyObserver(mContentResolver, mContentObserver);

        assertThat(mOnChanged).isFalse();

        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 0);

        assertThat(mOnChanged).isFalse();
    }

    @Test
    public void testGetSettingsIsOneHandedModeEnabled() {
        assertThat(mOneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                mContentResolver)).isAnyOf(true, false);
    }

    @Test
    public void testGetSettingsTapsAppToExit() {
        assertThat(mOneHandedSettingsUtil.getSettingsTapsAppToExit(
                mContentResolver)).isAnyOf(true, false);
    }

    @Test
    public void testGetSettingsOneHandedModeTimeout() {
        assertThat(mOneHandedSettingsUtil.getSettingsOneHandedModeTimeout(
                mContentResolver)).isAnyOf(
                ONE_HANDED_TIMEOUT_NEVER,
                ONE_HANDED_TIMEOUT_SHORT_IN_SECONDS,
                ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS,
                ONE_HANDED_TIMEOUT_LONG_IN_SECONDS);
    }
}
