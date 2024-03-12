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

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Size;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class WindowMagnificationSizePrefsTest extends SysuiTestCase {

    WindowMagnificationSizePrefs mWindowMagnificationSizePrefs =
            new WindowMagnificationSizePrefs(mContext);

    @Test
    public void saveSizeForCurrentDensity_getExpectedSize() {
        Size testSize = new Size(500, 500);
        mWindowMagnificationSizePrefs.saveSizeForCurrentDensity(testSize);

        assertThat(mWindowMagnificationSizePrefs.getSizeForCurrentDensity())
                .isEqualTo(testSize);
    }

    @Test
    public void saveSizeForCurrentDensity_containsPreferenceForCurrentDensity() {
        Size testSize = new Size(500, 500);
        mWindowMagnificationSizePrefs.saveSizeForCurrentDensity(testSize);

        assertThat(mWindowMagnificationSizePrefs.isPreferenceSavedForCurrentDensity())
                .isTrue();
    }
}
