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

import static com.google.common.truth.Truth.assertThat;

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
    private Context mContext;
    private ContentResolver mContentResolver;
    private SettingsHelper mSettingsHelper;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        mContentResolver = mContext.getContentResolver();
        mSettingsHelper = new SettingsHelper(mContext);
    }

    /** Tests for {@link Settings.Secure#ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED}. */
    @Test
    public void
            restoreAccessibilityDisplayMagnificationNavbarEnabled_alreadyConfigured_doesNotRestore()
                    throws Exception {
        // Simulate already configuring setting via SUW.
        Settings.Secure.putInt(
                mContentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                1);

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                String.valueOf(0),
                Build.VERSION.SDK_INT);

        assertThat(
                        Settings.Secure.getInt(
                                mContentResolver,
                                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED))
                .isEqualTo(1);
    }

    @Test
    public void
            restoreAccessibilityDisplayMagnificationNavbarEnabled_notAlreadyConfigured_restores()
                    throws Exception {
        // Simulate system default at boot.
        Settings.Secure.putInt(
                mContentResolver,
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                0);

        mSettingsHelper.restoreValue(
                mContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
                String.valueOf(1),
                Build.VERSION.SDK_INT);

        assertThat(
                        Settings.Secure.getInt(
                                mContentResolver,
                                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED))
                .isEqualTo(1);
    }
}
