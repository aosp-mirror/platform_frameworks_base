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

import static junit.framework.Assert.assertEquals;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.provider.SettingsStringUtil;
import android.view.accessibility.Flags;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.test.BroadcastInterceptingContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.ExecutionException;

/**
 * Tests for {@link SettingsHelper#restoreValue(Context, ContentResolver, ContentValues, Uri,
 * String, String, int)}. Specifically verifies that we restore critical accessibility settings only
 * if the user has not already configured these in SUW.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsHelperRestoreTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    public final BroadcastInterceptingContext mInterceptingContext =
            new BroadcastInterceptingContext(
                    InstrumentationRegistry.getInstrumentation().getContext());
    private static final float FLOAT_TOLERANCE = 0.01f;
    private ContentResolver mContentResolver;
    private SettingsHelper mSettingsHelper;

    @Before
    public void setUp() {
        mContentResolver = mInterceptingContext.getContentResolver();
        mSettingsHelper = new SettingsHelper(mInterceptingContext);
    }

    @After
    public void cleanUp() {
        setDefaultAccessibilityDisplayMagnificationScale();
        Settings.Secure.putInt(mContentResolver,
                Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED, 0);
        Settings.Secure.putString(mContentResolver, Settings.Secure.ACCESSIBILITY_QS_TARGETS, null);
        Settings.Secure.putString(mContentResolver,
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, null);
        Settings.Secure.putString(mContentResolver,
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, null);
    }

    @Test
    public void restoreHighTextContrastEnabled_currentlyEnabled_enableInRestoredFromVanilla_dontSendNotification_hctKeepsEnabled()
            throws ExecutionException, InterruptedException {
        BroadcastInterceptingContext.FutureIntent futureIntent =
                mInterceptingContext.nextBroadcastIntent(
                        SettingsHelper.HIGH_CONTRAST_TEXT_RESTORED_BROADCAST_ACTION);
        String settingName = Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED;
        Settings.Secure.putInt(mContentResolver, settingName, 1);

        mSettingsHelper.restoreValue(
                mInterceptingContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                String.valueOf(1),
                Build.VERSION_CODES.VANILLA_ICE_CREAM);

        futureIntent.assertNotReceived();
        assertThat(Settings.Secure.getInt(mContentResolver, settingName, 0)).isEqualTo(1);
    }

    @EnableFlags(com.android.graphics.hwui.flags.Flags.FLAG_HIGH_CONTRAST_TEXT_SMALL_TEXT_RECT)
    @Test
    public void restoreHighTextContrastEnabled_currentlyDisabled_enableInRestoredFromVanilla_sendNotification_hctKeepsDisabled()
            throws ExecutionException, InterruptedException {
        BroadcastInterceptingContext.FutureIntent futureIntent =
                mInterceptingContext.nextBroadcastIntent(
                        SettingsHelper.HIGH_CONTRAST_TEXT_RESTORED_BROADCAST_ACTION);
        String settingName = Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED;
        Settings.Secure.putInt(mContentResolver, settingName, 0);

        mSettingsHelper.restoreValue(
                mInterceptingContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                String.valueOf(1),
                Build.VERSION_CODES.VANILLA_ICE_CREAM);

        Intent intentReceived = futureIntent.get();
        assertThat(intentReceived).isNotNull();
        assertThat(intentReceived.getPackage()).isNotNull();
        assertThat(Settings.Secure.getInt(mContentResolver, settingName, 0)).isEqualTo(0);
    }

    @Test
    public void restoreHighTextContrastEnabled_currentlyDisabled_enableInRestoredFromAfterVanilla_dontSendNotification_hctShouldEnabled()
            throws ExecutionException, InterruptedException {
        BroadcastInterceptingContext.FutureIntent futureIntent =
                mInterceptingContext.nextBroadcastIntent(
                        SettingsHelper.HIGH_CONTRAST_TEXT_RESTORED_BROADCAST_ACTION);
        String settingName = Settings.Secure.ACCESSIBILITY_HIGH_TEXT_CONTRAST_ENABLED;
        Settings.Secure.putInt(mContentResolver, settingName, 0);

        mSettingsHelper.restoreValue(
                mInterceptingContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                String.valueOf(1),
                Build.VERSION_CODES.BAKLAVA);

        futureIntent.assertNotReceived();
        assertThat(Settings.Secure.getInt(mContentResolver, settingName, 0)).isEqualTo(1);
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
                mInterceptingContext,
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
                mInterceptingContext,
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
                mInterceptingContext.getResources()
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
                mInterceptingContext,
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
                mInterceptingContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                String.valueOf(restoreSettingValue),
                Build.VERSION.SDK_INT);

        assertEquals(restoreSettingValue, Settings.Secure.getInt(mContentResolver, settingName));
    }

    @Test
    public void restoreAccessibilityQsTargets_broadcastSent()
            throws ExecutionException, InterruptedException {
        final String settingName = Settings.Secure.ACCESSIBILITY_QS_TARGETS;
        final String restoreSettingValue = "com.android.server.accessibility/ColorInversion"
                + SettingsStringUtil.DELIMITER
                + "com.android.server.accessibility/ColorCorrectionTile";
        BroadcastInterceptingContext.FutureIntent futureIntent =
                mInterceptingContext.nextBroadcastIntent(Intent.ACTION_SETTING_RESTORED);

        mSettingsHelper.restoreValue(
                mInterceptingContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                restoreSettingValue,
                Build.VERSION.SDK_INT);

        Intent intentReceived = futureIntent.get();
        assertThat(intentReceived.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE))
                .isEqualTo(restoreSettingValue);
        assertThat(intentReceived.getIntExtra(
                Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT, /* defaultValue= */ 0))
                .isEqualTo(Build.VERSION.SDK_INT);
    }

    @Test
    public void restoreAccessibilityShortcutTargetService_broadcastSent()
            throws ExecutionException, InterruptedException {
        final String settingName = Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE;
        final String restoredValue = "com.android.a11y/Service";
        BroadcastInterceptingContext.FutureIntent futureIntent =
                mInterceptingContext.nextBroadcastIntent(Intent.ACTION_SETTING_RESTORED);

        mSettingsHelper.restoreValue(
                mInterceptingContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                restoredValue,
                Build.VERSION.SDK_INT);

        Intent intentReceived = futureIntent.get();
        assertThat(intentReceived.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE))
                .isEqualTo(restoredValue);
        assertThat(intentReceived.getIntExtra(
                Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT, /* defaultValue= */ 0))
                .isEqualTo(Build.VERSION.SDK_INT);
    }

    @EnableFlags(Flags.FLAG_RESTORE_A11Y_SECURE_SETTINGS_ON_HSUM_DEVICE)
    @Test
    public void restoreAccessibilityShortcutTargets_broadcastSent()
            throws ExecutionException, InterruptedException {
        final String settingName = Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS;
        final String restoreSettingValue = "com.android.server.accessibility/ColorInversion"
                + SettingsStringUtil.DELIMITER
                + "com.android.server.accessibility/ColorCorrectionTile";
        BroadcastInterceptingContext.FutureIntent futureIntent =
                mInterceptingContext.nextBroadcastIntent(Intent.ACTION_SETTING_RESTORED);

        mSettingsHelper.restoreValue(
                mInterceptingContext,
                mContentResolver,
                new ContentValues(2),
                Settings.Secure.getUriFor(settingName),
                settingName,
                restoreSettingValue,
                Build.VERSION.SDK_INT);

        Intent intentReceived = futureIntent.get();
        assertThat(intentReceived.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE))
                .isEqualTo(restoreSettingValue);
        assertThat(intentReceived.getIntExtra(
                Intent.EXTRA_SETTING_RESTORED_FROM_SDK_INT, /* defaultValue= */ 0))
                .isEqualTo(Build.VERSION.SDK_INT);
    }
}
