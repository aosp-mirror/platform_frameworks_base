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

package android.hardware.input;

import static com.android.hardware.input.Flags.keyboardA11yStickyKeysFlag;
import static com.android.hardware.input.Flags.keyboardLayoutPreviewFlag;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link com.android.hardware.input.Flags}
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:InputFlagsTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class InputFlagsTest {

    /**
     * Test that the flags work
     */
    @Test
    public void testFlags() {
        // No crash when accessing the flag.
        keyboardLayoutPreviewFlag();
        keyboardA11yStickyKeysFlag();
    }
}

