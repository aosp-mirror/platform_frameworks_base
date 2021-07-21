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

import static android.view.InsetsState.ITYPE_CAPTION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;

import static org.junit.Assert.assertEquals;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link InsetsSource}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:InsetsSourceTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InsetsSourceTest {

    private InsetsSource mSource = new InsetsSource(ITYPE_NAVIGATION_BAR);
    private InsetsSource mImeSource = new InsetsSource(ITYPE_IME);
    private InsetsSource mCaptionSource = new InsetsSource(ITYPE_CAPTION_BAR);

    @Before
    public void setUp() {
        mSource.setVisible(true);
        mImeSource.setVisible(true);
        mCaptionSource.setVisible(true);
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
    public void testCalculateInsets_ime_leftCutout() {
        mImeSource.setFrame(new Rect(100, 400, 500, 500));
        Insets insets = mImeSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 0, 0, 100), insets);
    }

    @Test
    public void testCalculateInsets_caption_resizing() {
        mCaptionSource.setFrame(new Rect(0, 0, 100, 100));
        Insets insets = mCaptionSource.calculateInsets(new Rect(0, 0, 200, 200), false);
        assertEquals(Insets.of(0, 100, 0, 0), insets);
        insets = mCaptionSource.calculateInsets(new Rect(0, 0, 50, 200), false);
        assertEquals(Insets.of(0, 100, 0, 0), insets);
        insets = mCaptionSource.calculateInsets(new Rect(100, 100, 200, 500), false);
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

    @Test
    public void testCalculateVisibleInsets_default() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        Insets insets = mSource.calculateVisibleInsets(new Rect(100, 0, 500, 500));
        assertEquals(Insets.of(0, 100, 0, 0), insets);
    }

    @Test
    public void testCalculateInsets_noIntersection_vertical() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        Insets insets = mSource.calculateInsets(new Rect(0, 100, 500, 500), false);
        assertEquals(Insets.NONE, insets);
    }

    @Test
    public void testCalculateInsets_zeroWidthIntersection_vertical_start() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        Insets insets = mSource.calculateInsets(new Rect(0, 0, 0, 500), false);
        assertEquals(Insets.of(0, 100, 0, 0), insets);
    }

    @Test
    public void testCalculateInsets_zeroWidthIntersection_vertical_end() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        Insets insets = mSource.calculateInsets(new Rect(500, 0, 500, 500), false);
        assertEquals(Insets.of(0, 100, 0, 0), insets);
    }

    @Test
    public void testCalculateInsets_noIntersection_horizontal() {
        mSource.setFrame(new Rect(0, 0, 100, 500));
        Insets insets = mSource.calculateInsets(new Rect(100, 0, 500, 500), false);
        assertEquals(Insets.NONE, insets);
    }

    @Test
    public void testCalculateInsetsForIme_noIntersection_horizontal() {
        mImeSource.setFrame(new Rect(0, 0, 100, 500));
        Insets insets = mImeSource.calculateInsets(new Rect(100, 0, 500, 500), false);
        assertEquals(Insets.NONE, insets);
    }

    @Test
    public void testCalculateInsets_zeroWidthIntersection_horizontal_start() {
        mSource.setFrame(new Rect(0, 0, 100, 500));
        Insets insets = mSource.calculateInsets(new Rect(0, 0, 500, 0), false);
        assertEquals(Insets.of(100, 0, 0, 0), insets);
    }

    @Test
    public void testCalculateInsets_zeroWidthIntersection_horizontal_end() {
        mSource.setFrame(new Rect(0, 0, 100, 500));
        Insets insets = mSource.calculateInsets(new Rect(0, 500, 500, 500), false);
        assertEquals(Insets.of(100, 0, 0, 0), insets);
    }

    @Test
    public void testCalculateVisibleInsets_override() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        mSource.setVisibleFrame(new Rect(0, 0, 500, 200));
        Insets insets = mSource.calculateVisibleInsets(new Rect(100, 0, 500, 500));
        assertEquals(Insets.of(0, 200, 0, 0), insets);
    }

    @Test
    public void testCalculateVisibleInsets_invisible() {
        mSource.setFrame(new Rect(0, 0, 500, 100));
        mSource.setVisibleFrame(new Rect(0, 0, 500, 200));
        mSource.setVisible(false);
        Insets insets = mSource.calculateVisibleInsets(new Rect(100, 0, 500, 500));
        assertEquals(Insets.of(0, 0, 0, 0), insets);
    }


    // Parcel and equals already tested via InsetsStateTest
}
