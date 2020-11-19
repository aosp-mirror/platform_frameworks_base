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

package com.android.wm.shell.letterbox;

import static org.junit.Assert.assertEquals;

import android.view.Gravity;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link LetterboxConfigController}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class LetterboxConfigControllerTest extends ShellTestCase {

    private LetterboxConfigController mLetterboxConfigController;

    @Before
    public void setUp() {
        mLetterboxConfigController = new LetterboxConfigController(getContext());
    }

    @Test
    public void testGetPortraitGravity_noOverrides_returnConfigValue() {
        assertEquals(
                mLetterboxConfigController.getPortraitGravity(),
                getContext().getResources().getInteger(R.integer.config_letterboxPortraitGravity));
    }

    @Test
    public void testGetLandscapeGravity_noOverrides_returnConfigValue() {
        assertEquals(
                mLetterboxConfigController.getLandscapeGravity(),
                getContext().getResources().getInteger(R.integer.config_letterboxLandscapeGravity));
    }

    @Test
    public void testSetPortraitGravity_validValue_savesValue() {
        mLetterboxConfigController.setPortraitGravity(Gravity.BOTTOM);
        assertEquals(mLetterboxConfigController.getPortraitGravity(), Gravity.BOTTOM);

        mLetterboxConfigController.setPortraitGravity(Gravity.CENTER);
        assertEquals(mLetterboxConfigController.getPortraitGravity(), Gravity.CENTER);

        mLetterboxConfigController.setPortraitGravity(Gravity.TOP);
        assertEquals(mLetterboxConfigController.getPortraitGravity(), Gravity.TOP);
    }

    @Test
    public void testSetLandscapeGravity_validValue_savesValue() {
        mLetterboxConfigController.setLandscapeGravity(Gravity.LEFT);
        assertEquals(mLetterboxConfigController.getLandscapeGravity(), Gravity.LEFT);

        mLetterboxConfigController.setLandscapeGravity(Gravity.CENTER);
        assertEquals(mLetterboxConfigController.getLandscapeGravity(), Gravity.CENTER);

        mLetterboxConfigController.setLandscapeGravity(Gravity.RIGHT);
        assertEquals(mLetterboxConfigController.getLandscapeGravity(), Gravity.RIGHT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetPortraitGravity_invalidValue_throwsException() {
        mLetterboxConfigController.setPortraitGravity(Gravity.RIGHT);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetLandscapeGravity_invalidValue_throwsException() {
        mLetterboxConfigController.setLandscapeGravity(Gravity.TOP);
    }

    @Test
    public void testResetPortraitGravity() {
        int defaultGravity =
                getContext().getResources().getInteger(R.integer.config_letterboxPortraitGravity);

        mLetterboxConfigController.setPortraitGravity(Gravity.BOTTOM);
        mLetterboxConfigController.resetPortraitGravity();
        assertEquals(mLetterboxConfigController.getPortraitGravity(), defaultGravity);

        mLetterboxConfigController.setPortraitGravity(Gravity.CENTER);
        mLetterboxConfigController.resetPortraitGravity();
        assertEquals(mLetterboxConfigController.getPortraitGravity(), defaultGravity);

        mLetterboxConfigController.setPortraitGravity(Gravity.TOP);
        mLetterboxConfigController.resetPortraitGravity();
        assertEquals(mLetterboxConfigController.getPortraitGravity(), defaultGravity);
    }

    @Test
    public void testResetLandscapeGravity() {
        int defaultGravity =
                getContext().getResources().getInteger(R.integer.config_letterboxLandscapeGravity);

        mLetterboxConfigController.setLandscapeGravity(Gravity.RIGHT);
        mLetterboxConfigController.resetLandscapeGravity();
        assertEquals(mLetterboxConfigController.getLandscapeGravity(), defaultGravity);

        mLetterboxConfigController.setLandscapeGravity(Gravity.CENTER);
        mLetterboxConfigController.resetLandscapeGravity();
        assertEquals(mLetterboxConfigController.getLandscapeGravity(), defaultGravity);

        mLetterboxConfigController.setLandscapeGravity(Gravity.LEFT);
        mLetterboxConfigController.resetLandscapeGravity();
        assertEquals(mLetterboxConfigController.getLandscapeGravity(), defaultGravity);
    }

}
