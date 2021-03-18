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

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class OneHandedSettingsUtilTest extends OneHandedTestCase {
    ContentResolver mContentResolver;
    ContentObserver mContentObserver;
    boolean mOnChanged;

    @Before
    public void setUp() {
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
        final Uri result = OneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.TAPS_APP_TO_EXIT, mContentResolver, mContentObserver);

        assertThat(result).isNotNull();

        OneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.TAPS_APP_TO_EXIT, mContentResolver, mContentObserver);
    }

    @Test
    public void testUnregisterSecureKeyObserver() {
        OneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.TAPS_APP_TO_EXIT, mContentResolver, mContentObserver);
        OneHandedSettingsUtil.unregisterSettingsKeyObserver(mContentResolver, mContentObserver);

        assertThat(mOnChanged).isFalse();

        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.TAPS_APP_TO_EXIT, 0);

        assertThat(mOnChanged).isFalse();
    }
}
