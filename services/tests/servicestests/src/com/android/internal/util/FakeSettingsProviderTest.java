/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.util;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for FakeSettingsProvider.
 */
public class FakeSettingsProviderTest extends AndroidTestCase {

    private MockContentResolver mCr;

    @Override
    public void setUp() throws Exception {
        mCr = new MockContentResolver();
        mCr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
    }

    @SmallTest
    public void testBasicOperation() throws Exception {
        String settingName = Settings.System.SCREEN_BRIGHTNESS;

        try {
            Settings.System.getInt(mCr, settingName);
            fail("FakeSettingsProvider should start off empty.");
        } catch (Settings.SettingNotFoundException expected) {}

        // Check that fake settings can be written and read back.
        Settings.System.putInt(mCr, settingName, 123);
        assertEquals(123, Settings.System.getInt(mCr, settingName));
    }
}
