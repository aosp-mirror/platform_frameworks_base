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

import static com.android.systemui.onehanded.OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS;

import android.provider.Settings;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;

/**
 * Base class that does One Handed specific setup.
 */
public abstract class OneHandedTestCase extends SysuiTestCase {
    static boolean sOrigEnabled;
    static int sOrigTimeout;

    @Before
    public void setupSettings() {
        sOrigEnabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                getContext().getContentResolver());
        sOrigTimeout = OneHandedSettingsUtil.getSettingsOneHandedModeTimeout(
                getContext().getContentResolver());
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, 1);
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT, ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS);
    }

    @After
    public void restoreSettings() {
        Settings.Secure.putInt(getContext().getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_ENABLED, sOrigEnabled ? 1 : 0);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.ONE_HANDED_MODE_TIMEOUT, sOrigTimeout);
    }
}

