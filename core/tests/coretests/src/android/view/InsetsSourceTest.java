/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.InsetsState.TYPE_NAVIGATION_BAR;

import static junit.framework.Assert.assertEquals;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class InsetsSourceTest {

    private InsetsSource mSource = new InsetsSource(TYPE_NAVIGATION_BAR);

    @Before
    public void setUp() {
        mSource.setVisible(true);
    }

    @Test
    public void testCalculateInsetsTop() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        Insets insets = mSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 100, 0, 0), insets);
    }

    @Test
    public void testCalculateInsetsBottom() {
        mSource.setFrame(new Rect(0, 400, 500, 500));
        Insets insets = mSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 0, 0, 100), insets);
    }

    @Test
    public void testCalculateInsetsLeft() {
        mSource.setFrame(new Rect(0, 0, 100, 500));
        Insets insets = mSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(100, 0, 0, 0), insets);
    }

    @Test
    public void testCalculateInsetsRight() {
        mSource.setFrame(new Rect(400, 0, 500, 500));
        Insets insets = mSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 0, 100, 0), insets);
    }

    @Test
    public void testCalculateInsets_overextend() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        Insets insets = mSource.calculateInsets(new Rect(100, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 100, 0, 0), insets);
    }

    @Test
    public void testCalculateInsets_invisible() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        mSource.setVisible(false);
        Insets insets = mSource.calculateInsets(new Rect(100, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 0, 0, 0), insets);
    }

    @Test
    public void testCalculateInsets_ignoreVisibility() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        mSource.setVisible(false);
        Insets insets = mSource.calculateInsets(new Rect(100, 0, 500, 500),
                true /* ignoreVisibility */);
        assertEquals(Insets.of(0, 100, 0, 0), insets);
    }

    // Parcel and equals already tested via InsetsStateTest
}
