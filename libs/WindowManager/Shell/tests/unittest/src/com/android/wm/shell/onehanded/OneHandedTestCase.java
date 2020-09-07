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

package com.android.wm.shell.onehanded;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.wm.shell.onehanded.OneHandedController.SUPPORT_ONE_HANDED_MODE;
import static com.android.wm.shell.onehanded.OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemProperties;
import android.provider.Settings;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;

/**
 * Base class that does One Handed specific setup.
 */
public abstract class OneHandedTestCase {
    static boolean sOrigEnabled;
    static boolean sOrigTapsAppToExitEnabled;
    static int sOrigTimeout;
    static boolean sOrigSwipeToNotification;

    protected Context mContext;

    @Before
    public void setupSettings() {
        final Context testContext =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final DisplayManager dm = testContext.getSystemService(DisplayManager.class);
        mContext = testContext.createDisplayContext(dm.getDisplay(DEFAULT_DISPLAY));

        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();

        sOrigEnabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                getContext().getContentResolver());
        sOrigTimeout = OneHandedSettingsUtil.getSettingsOneHandedModeTimeout(
                getContext().getContentResolver());
        sOrigTapsAppToExitEnabled = OneHandedSettingsUtil.getSettingsTapsAppToExit(
                getContext().getContentResolver());
        sOrigSwipeToNotification = OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                getContext().getContentResolver());
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 1);
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT, ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.TAPS_APP_TO_EXIT, 1);
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED, 1);
    }

    @Before
    public void assumeOneHandedModeSupported() {
        assumeTrue(SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false));
    }

    @After
    public void restoreSettings() {
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, sOrigEnabled ? 1 : 0);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT, sOrigTimeout);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.TAPS_APP_TO_EXIT, sOrigTapsAppToExitEnabled ? 1 : 0);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED,
                sOrigSwipeToNotification ? 1 : 0);

        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    protected Context getContext() {
        return mContext;
    }
}

