/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.providers.settings;

import static junit.framework.Assert.assertEquals;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link SettingsHelper#restoreValue(Context, ContentResolver, ContentValues, Uri,
 * String, String, int)}. Specifically verifies that we restore critical accessibility settings only
 * if the user has not already configured these in SUW.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsHelperRestoreTest {
    private static final float FLOAT_TOLERANCE = 0.01f;

    private Context mContext;
    private ContentResolver mContentResolver;
    private SettingsHelper mSettingsHelper;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        mContentResolver = mContext.getContentResolver();
        mSettingsHelper = new SettingsHelper(mContext);
    }

    /** Tests for {@link Settings.Secure#ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE}. */
    @Test
    public void
            testRestoreAccessibilityDisplayMagnificationScale_alreadyConfigured_doesNotRestoreValue()
                    throws Exception {
        float defaultSettingValue = setDefaultAccessibilityDisplayMagnificationScale();
        String settingName = Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE;
        float restoreSettingValue = defaultSettingValue + 0.5f;

        // Simulate already configuring setting via SUW.
        float configuredSettingValue = defaultSettingValue + 1.0f;
        Settings.Secure.putFloat(mContentResolver, settingName, configuredSettingValue);

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                String.valueOf(restoreSettingValue),
                Build.VERSION.SDK_INT);

        assertEquals(
                configuredSettingValue,
                Settings.Secure.getFloat(mContentResolver, settingName),
                FLOAT_TOLERANCE);
    }

    @Test
    public void
            testRestoreAccessibilityDisplayMagnificationScale_notAlreadyConfigured_restoresValue()
                    throws Exception {
        float defaultSettingValue = setDefaultAccessibilityDisplayMagnificationScale();
        String settingName = Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE;
        float restoreSettingValue = defaultSettingValue + 0.5f;

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                String.valueOf(restoreSettingValue),
                Build.VERSION.SDK_INT);

        assertEquals(
                restoreSettingValue,
                Settings.Secure.getFloat(mContentResolver, settingName),
                FLOAT_TOLERANCE);
    }

    /**
     * Simulate {@link Settings.Secure#ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE} value at boot by
     * loading the default.
     *
     * @return the default value.
     */
    private float setDefaultAccessibilityDisplayMagnificationScale() {
        float defaultSettingValue =
                mContext.getResources()
                        .getFraction(
                                R.fraction.def_accessibility_display_magnification_scale, 1, 1);
        Settings.Secure.putFloat(
                mContentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                defaultSettingValue);
        return defaultSettingValue;
    }

    /** Tests for {@link Settings.Secure#ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED}. */
    @Test
    public void
            testRestoreAccessibilityDisplayMagnificationNavbarEnabled_alreadyConfigured_doesNotRestoreValue()
                    throws Exception {
        String settingName = Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED;
        // Simulate already configuring setting via SUW.
        int configuredSettingValue = 1;
        Settings.Secure.putInt(mContentResolver, settingName, configuredSettingValue);

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                String.valueOf(0),
                Build.VERSION.SDK_INT);

        assertEquals(configuredSettingValue, Settings.Secure.getInt(mContentResolver, settingName));
    }

    @Test
    public void
            testRestoreAccessibilityDisplayMagnificationNavbarEnabled_notAlreadyConfigured_restoresValue()
                    throws Exception {
        String settingName = Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED;
        int defaultSettingValue = 0;
        // Simulate system default at boot.
        Settings.Secure.putInt(mContentResolver, settingName, defaultSettingValue);

        int restoreSettingValue = 1;
        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                String.valueOf(restoreSettingValue),
                Build.VERSION.SDK_INT);

        assertEquals(restoreSettingValue, Settings.Secure.getInt(mContentResolver, settingName));
    }
}
