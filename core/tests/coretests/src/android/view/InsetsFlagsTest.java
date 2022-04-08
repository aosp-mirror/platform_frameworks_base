/*
 * Copyright (C) 2019 The Android Open Source Project
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


import static android.view.InsetsFlags.getAppearance;
import static android.view.View.NAVIGATION_BAR_TRANSLUCENT;
import static android.view.View.NAVIGATION_BAR_TRANSPARENT;
import static android.view.View.STATUS_BAR_TRANSLUCENT;
import static android.view.View.STATUS_BAR_TRANSPARENT;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;

import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.view.WindowInsetsController.Appearance;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link InsetsFlags}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:InsetsFlagsTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InsetsFlagsTest {

    @Test
    public void testGetAppearance() {
        assertContainsAppearance(APPEARANCE_LOW_PROFILE_BARS, SYSTEM_UI_FLAG_LOW_PROFILE);
        assertContainsAppearance(APPEARANCE_LIGHT_STATUS_BARS, SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        assertContainsAppearance(APPEARANCE_LIGHT_NAVIGATION_BARS,
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        assertContainsAppearance(APPEARANCE_OPAQUE_STATUS_BARS,
                0xffffffff & ~(STATUS_BAR_TRANSLUCENT | STATUS_BAR_TRANSPARENT));
        assertContainsAppearance(APPEARANCE_OPAQUE_NAVIGATION_BARS,
                0xffffffff & ~(NAVIGATION_BAR_TRANSLUCENT | NAVIGATION_BAR_TRANSPARENT));
    }

    void assertContainsAppearance(@Appearance int appearance, int systemUiVisibility) {
        assertTrue((getAppearance(systemUiVisibility) & appearance) == appearance);
    }
}
