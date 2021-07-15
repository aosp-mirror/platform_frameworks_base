/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link SoundEffectConstants}
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:SoundEffectConstantsTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SoundEffectConstantsTest {

    @Test
    public void testIsNavigationRepeat() {
        assertTrue(SoundEffectConstants.isNavigationRepeat(
                SoundEffectConstants.NAVIGATION_REPEAT_RIGHT));
        assertTrue(SoundEffectConstants.isNavigationRepeat(
                SoundEffectConstants.NAVIGATION_REPEAT_LEFT));
        assertTrue(
                SoundEffectConstants.isNavigationRepeat(SoundEffectConstants.NAVIGATION_REPEAT_UP));
        assertTrue(SoundEffectConstants.isNavigationRepeat(
                SoundEffectConstants.NAVIGATION_REPEAT_DOWN));
        assertFalse(SoundEffectConstants.isNavigationRepeat(SoundEffectConstants.NAVIGATION_RIGHT));
        assertFalse(SoundEffectConstants.isNavigationRepeat(SoundEffectConstants.NAVIGATION_LEFT));
        assertFalse(SoundEffectConstants.isNavigationRepeat(SoundEffectConstants.NAVIGATION_UP));
        assertFalse(SoundEffectConstants.isNavigationRepeat(SoundEffectConstants.NAVIGATION_DOWN));
        assertFalse(SoundEffectConstants.isNavigationRepeat(-1));
    }
}
