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

package com.android.systemui.accessibility;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.testing.TestableLooper;
import android.util.Size;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class WindowMagnificationFrameSizePrefsTest extends SysuiTestCase {

    WindowMagnificationFrameSizePrefs mWindowMagnificationFrameSizePrefs;
    FakeSharedPreferences mSharedPreferences;

    @Before
    public void setUp() {
        mContext = spy(mContext);
        mSharedPreferences = new FakeSharedPreferences();
        when(mContext.getSharedPreferences(
                eq("window_magnification_preferences"), anyInt()))
                .thenReturn(mSharedPreferences);
        mWindowMagnificationFrameSizePrefs = new WindowMagnificationFrameSizePrefs(mContext);
    }

    @Test
    public void saveSizeForCurrentDensity_getExpectedSize() {
        Size testSize = new Size(500, 500);
        mWindowMagnificationFrameSizePrefs.saveSizeForCurrentDensity(testSize);

        assertThat(mWindowMagnificationFrameSizePrefs.getSizeForCurrentDensity())
                .isEqualTo(testSize);
    }

    @Test
    public void saveSizeForCurrentDensity_containsPreferenceForCurrentDensity() {
        Size testSize = new Size(500, 500);
        mWindowMagnificationFrameSizePrefs.saveSizeForCurrentDensity(testSize);

        assertThat(mWindowMagnificationFrameSizePrefs.isPreferenceSavedForCurrentDensity())
                .isTrue();
    }
}
