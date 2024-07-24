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

import static android.view.InsetsSource.ID_IME_CAPTION_BAR;
import static android.view.WindowInsets.Type.FIRST;
import static android.view.WindowInsets.Type.LAST;
import static android.view.WindowInsets.Type.SIZE;
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

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

    private final InsetsSource mSource = new InsetsSource(0 /* id */, navigationBars());
    private final InsetsSource mImeSource = new InsetsSource(1 /* id */, ime());
    private final InsetsSource mImeCaptionSource = new InsetsSource(
            ID_IME_CAPTION_BAR, captionBar());
    private final InsetsSource mCaptionSource = new InsetsSource(2 /* id */, captionBar());

    @Before
    public void setUp() {
        mSource.setVisible(true);
        mImeSource.setVisible(true);
        mImeCaptionSource.setVisible(true);
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
    public void testCalculateInsets_imeCaptionBar() {
        mImeCaptionSource.setFrame(new Rect(0, 400, 500, 500));
        Insets insets = mImeCaptionSource.calculateInsets(new Rect(0, 0, 500, 500), false);
        assertEquals(Insets.of(0, 0, 0, 100), insets);

        // Place caption bar at top; IME caption bar must always return bottom insets
        mImeCaptionSource.setFrame(new Rect(0, 0, 500, 100));
        insets = mImeCaptionSource.calculateInsets(new Rect(0, 0, 500, 500), false);
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

    @Test
    public void testCreateId() {
        final int numSourcePerType = 2048;
        final int numTotalSources = SIZE * numSourcePerType;
        final SparseArray<InsetsSource> sources = new SparseArray<>(numTotalSources);
        final Object owner = new Object();
        for (int index = 0; index < numSourcePerType; index++) {
            for (int type = FIRST; type <= LAST; type = type << 1) {
                final int id = InsetsSource.createId(owner, index, type);
                assertNull("Must not create the same ID.", sources.get(id));
                sources.append(id, new InsetsSource(id, type));
            }
        }
        assertEquals(numTotalSources, sources.size());
    }

    @Test
    public void testGetIndex() {
        // Here doesn't iterate all the owners, or the test cannot be done before timeout.
        for (int owner = 0; owner < 100; owner++) {
            for (int index = 0; index < 2048; index++) {
                for (int type = FIRST; type <= LAST; type = type << 1) {
                    final int id = InsetsSource.createId(owner, index, type);
                    final int indexFromId = InsetsSource.getIndex(id);
                    assertEquals("index and indexFromId must be the same. id=" + id
                            + ", owner=" + owner
                            + ", index=" + index
                            + ", type=" + type
                            + ", indexFromId=" + indexFromId + ".", index, indexFromId);
                }
            }
        }
    }

    @Test
    public void testGetType() {
        // Here doesn't iterate all the owners, or the test cannot be done before timeout.
        for (int owner = 0; owner < 100; owner++) {
            for (int index = 0; index < 2048; index++) {
                for (int type = FIRST; type <= LAST; type = type << 1) {
                    final int id = InsetsSource.createId(owner, index, type);
                    final int typeFromId = InsetsSource.getType(id);
                    assertEquals("type and typeFromId must be the same. id=" + id
                            + ", owner=" + owner
                            + ", index=" + index
                            + ", type=" + type
                            + ", typeFromId=" + typeFromId + ".", type, typeFromId);
                }
            }
        }
    }

    @Test
    public void testCalculateBoundingRects_noBoundingRects_createsSingleRect() {
        mSource.setFrame(new Rect(0, 0, 1000, 100));
        mSource.setBoundingRects(null);

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(0, 0, 1000, 1000), false);

        assertEquals(1, rects.length);
        assertEquals(new Rect(0, 0, 1000, 100), rects[0]);
    }

    @Test
    public void testCalculateBoundingRects_noBoundingRectsAndLargerFrame_singleRectFitsRelFrame() {
        mSource.setFrame(new Rect(0, 0, 1000, 100));
        mSource.setBoundingRects(null);

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(0, 0, 500, 1000), false);

        assertEquals(1, rects.length);
        assertEquals(new Rect(0, 0, 500, 100), rects[0]);
    }

    @Test
    public void testCalculateBoundingRects_frameAtOrigin_resultRelativeToRelFrame() {
        mSource.setFrame(new Rect(0, 0, 1000, 100));
        mSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 300, 100),
                new Rect(800, 0, 1000, 100),
        });

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(0, 0, 1000, 1000), false);

        assertEquals(2, rects.length);
        assertEquals(new Rect(0, 0, 300, 100), rects[0]);
        assertEquals(new Rect(800, 0, 1000, 100), rects[1]);
    }

    @Test
    public void testCalculateBoundingRects_notAtOrigin_resultRelativeToRelFrame() {
        mSource.setFrame(new Rect(100, 100, 1100, 200));
        mSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 300, 100),    // 300x100, aligned left
                new Rect(800, 0, 1000, 100), // 200x100, aligned right
        });

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(100, 100, 1100, 1100), false);

        assertEquals(2, rects.length);
        assertEquals(new Rect(0, 0, 300, 100), rects[0]);
        assertEquals(new Rect(800, 0, 1000, 100), rects[1]);
    }

    @Test
    public void testCalculateBoundingRects_boundingRectFullyInsideFrameInWindow() {
        mSource.setFrame(new Rect(0, 0, 1000, 100));
        mSource.setBoundingRects(new Rect[]{
                new Rect(100, 0, 400, 100), // Inside |frame| and |relativeFrame|.
        });

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(0, 0, 500, 100), false);

        assertEquals(1, rects.length);
        assertEquals(new Rect(100, 0, 400, 100), rects[0]);
    }

    @Test
    public void testCalculateBoundingRects_boundingRectOutsideFrameInWindow_dropped() {
        mSource.setFrame(new Rect(0, 0, 1000, 100));
        mSource.setBoundingRects(new Rect[]{
                new Rect(700, 0, 1000, 100), // Inside |frame|, but outside |relativeFrame|.
        });

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(0, 0, 500, 100), false);

        assertEquals(0, rects.length);
    }

    @Test
    public void testCalculateBoundingRects_boundingRectPartlyOutsideFrameInWindow_cropped() {
        mSource.setFrame(new Rect(0, 0, 1000, 100));
        mSource.setBoundingRects(new Rect[]{
                new Rect(400, 0, 600, 100), // Inside |frame|, and only half inside |relativeFrame|.
        });

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(0, 0, 500, 100), false);

        assertEquals(1, rects.length);
        assertEquals(new Rect(400, 0, 500, 100), rects[0]);
    }

    @Test
    public void testCalculateBoundingRects_framesNotAtOrigin_resultRelativeToWindowFrame() {
        mSource.setFrame(new Rect(100, 100, 1100, 200));
        mSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 300, 100), // 300x100 aligned to left.
                new Rect(800, 0, 1000, 100) // 200x100 align to right.
        });

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(100, 100, 1100, 1100), false);

        assertEquals(2, rects.length);
        assertEquals(new Rect(0, 0, 300, 100), rects[0]);
        assertEquals(new Rect(800, 0, 1000, 100), rects[1]);
    }

    @Test
    public void testCalculateBoundingRects_captionBar() {
        mCaptionSource.setFrame(new Rect(0, 0, 1000, 100));
        mCaptionSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 200, 100), // 200x100, aligned left.
                new Rect(800, 0, 1000, 100) // 200x100, aligned right.
        });

        final Rect[] rects = mCaptionSource.calculateBoundingRects(
                new Rect(0, 0, 1000, 1000), false);

        assertEquals(2, rects.length);
        assertEquals(new Rect(0, 0, 200, 100), rects[0]);
        assertEquals(new Rect(800, 0, 1000, 100), rects[1]);
    }

    @Test
    public void testCalculateBoundingRects_captionBarFrameMisaligned_rectsFixedToTop() {
        mCaptionSource.setFrame(new Rect(500, 500, 1500, 600));
        mCaptionSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 100, 100), // 100x100, aligned to left/top of frame
        });

        final Rect[] rects = mCaptionSource.calculateBoundingRects(
                new Rect(495, 495, 1500, 1500), false);

        assertEquals(1, rects.length);
        // rect should be aligned to the top of relative frame, as if the caption frame had been
        // corrected to be aligned at the top.
        assertEquals(new Rect(0, 0, 100, 100), rects[0]);
    }

    @Test
    public void testCalculateBoundingRects_imeCaptionBarFrameMisaligned_rectsFixedToBottom() {
        mImeCaptionSource.setFrame(new Rect(500, 1400, 1500, 1500));
        mImeCaptionSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 100, 100), // 100x100, aligned to left/top of frame
        });

        final Rect[] rects = mImeCaptionSource.calculateBoundingRects(
                new Rect(495, 495, 1500, 1500), false);

        assertEquals(1, rects.length);
        // rect should be aligned to the bottom of relative frame, as if the ime caption frame had
        // been corrected to be aligned at the top.
        assertEquals(new Rect(0, 905, 100, 1005), rects[0]);
    }

    @Test
    public void testCalculateBoundingRects_imeCaptionBar() {
        mImeCaptionSource.setFrame(new Rect(0, 900, 1000, 1000)); // Frame at the bottom.
        mImeCaptionSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 200, 100), // 200x100, aligned left.
        });

        final Rect[] rects = mImeCaptionSource.calculateBoundingRects(
                new Rect(0, 0, 1000, 1000), false);

        assertEquals(1, rects.length);
        assertEquals(new Rect(0, 900, 200, 1000), rects[0]);
    }

    @Test
    public void testCalculateBoundingRects_invisible() {
        mSource.setFrame(new Rect(0, 0, 1000, 100));
        mSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 300, 100),
                new Rect(800, 0, 1000, 100),
        });
        mSource.setVisible(false);

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(0, 0, 1000, 1000),
                false /* ignoreVisibility */);

        assertEquals(0, rects.length);
    }

    @Test
    public void testCalculateBoundingRects_ignoreVisibility() {
        mSource.setFrame(new Rect(0, 0, 1000, 100));
        mSource.setBoundingRects(new Rect[]{
                new Rect(0, 0, 300, 100),
                new Rect(800, 0, 1000, 100),
        });
        mSource.setVisible(false);

        final Rect[] rects = mSource.calculateBoundingRects(new Rect(0, 0, 1000, 1000),
                true /* ignoreVisibility */);

        assertEquals(2, rects.length);
        assertEquals(new Rect(0, 0, 300, 100), rects[0]);
        assertEquals(new Rect(800, 0, 1000, 100), rects[1]);
    }

    // Parcel and equals already tested via InsetsStateTest
}
