/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.view;

import static com.android.window.flags.Flags.FLAG_ENABLE_WM_EXTENSIONS_FOR_ALL_FLAG;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link WindowManager}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:WindowManagerTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowManagerTests {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testHasWindowExtensionsEnabled_flagDisabled() {
        mSetFlagsRule.disableFlags(FLAG_ENABLE_WM_EXTENSIONS_FOR_ALL_FLAG);

        // Before FLAG_ENABLE_WM_EXTENSIONS_FOR_ALL_FLAG, Extensions are always bundled with AE.
        assertEquals(isActivityEmbeddingEnableForAll(),
                WindowManager.hasWindowExtensionsEnabled());
    }

    @Test
    public void testHasWindowExtensionsEnabled_flagEnabled() {
        mSetFlagsRule.enableFlags(FLAG_ENABLE_WM_EXTENSIONS_FOR_ALL_FLAG);

        // Extensions should be enabled on all devices.
        assertTrue(WindowManager.hasWindowExtensionsEnabled());
    }

    @Test
    public void testActivityEmbeddingAvailability() {
        assumeTrue(isActivityEmbeddingEnableForAll());

        // AE can only be enabled when extensions is enabled.
        assertTrue(WindowManager.hasWindowExtensionsEnabled());
    }

    private static boolean isActivityEmbeddingEnableForAll() {
        return !WindowManager.ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15;
    }
}
