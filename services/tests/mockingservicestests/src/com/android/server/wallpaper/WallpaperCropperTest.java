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

package com.android.server.wallpaper;

import static android.app.WallpaperManager.LANDSCAPE;
import static android.app.WallpaperManager.ORIENTATION_UNKNOWN;
import static android.app.WallpaperManager.PORTRAIT;
import static android.app.WallpaperManager.SQUARE_LANDSCAPE;
import static android.app.WallpaperManager.SQUARE_PORTRAIT;
import static android.app.WallpaperManager.getOrientation;
import static android.app.WallpaperManager.getRotatedOrientation;

import static com.android.window.flags.Flags.FLAG_MULTI_CROP;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.SparseArray;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Comparator;
import java.util.List;

/**
 * Unit tests for the most important helpers of {@link WallpaperCropper}, in particular
 * {@link WallpaperCropper#getCrop(Point, Point, SparseArray, boolean)}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(FLAG_MULTI_CROP)
public class WallpaperCropperTest {

    @Mock
    private WallpaperDisplayHelper mWallpaperDisplayHelper;
    private WallpaperCropper mWallpaperCropper;

    private static final Point PORTRAIT_ONE = new Point(500, 800);
    private static final Point PORTRAIT_TWO = new Point(400, 1000);
    private static final Point PORTRAIT_THREE = new Point(2000, 800);
    private static final Point PORTRAIT_FOUR = new Point(1600, 1000);

    private static final Point SQUARE_PORTRAIT_ONE = new Point(1000, 800);
    private static final Point SQUARE_LANDSCAPE_ONE = new Point(800, 1000);

    /**
     * Common device: a single screen of portrait/landscape orientation
     */
    private static final List<Point> STANDARD_DISPLAY = List.of(PORTRAIT_ONE);

    /** 1: folded: portrait, unfolded: square with w < h */
    private static final List<Point> FOLDABLE_ONE = List.of(PORTRAIT_ONE, SQUARE_PORTRAIT_ONE);

    /** 2: folded: portrait, unfolded: square with w > h */
    private static final List<Point> FOLDABLE_TWO = List.of(PORTRAIT_TWO, SQUARE_LANDSCAPE_ONE);

    /** 3: folded: square with w < h, unfolded: portrait */
    private static final List<Point> FOLDABLE_THREE = List.of(SQUARE_PORTRAIT_ONE, PORTRAIT_THREE);

    /** 4: folded: square with w > h, unfolded: portrait */
    private static final List<Point> FOLDABLE_FOUR = List.of(SQUARE_LANDSCAPE_ONE, PORTRAIT_FOUR);

    /**
     * List of different sets of displays for foldable devices. Foldable devices have two displays:
     * a folded (smaller) unfolded (larger).
     */
    private static final List<List<Point>> ALL_FOLDABLE_DISPLAYS = List.of(
            FOLDABLE_ONE, FOLDABLE_TWO, FOLDABLE_THREE, FOLDABLE_FOUR);

    private SparseArray<Point> mDisplaySizes = new SparseArray<>();
    private int mFolded = ORIENTATION_UNKNOWN;
    private int mFoldedRotated = ORIENTATION_UNKNOWN;
    private int mUnfolded = ORIENTATION_UNKNOWN;
    private int mUnfoldedRotated = ORIENTATION_UNKNOWN;

    private static final List<Integer> ALL_MODES = List.of(
            WallpaperCropper.ADD, WallpaperCropper.REMOVE, WallpaperCropper.BALANCE);

    @Before
    public void setUp() {
        initMocks(this);
        mWallpaperCropper = new WallpaperCropper(mWallpaperDisplayHelper);
    }

    private void setUpWithDisplays(List<Point> displaySizes) {
        mDisplaySizes = new SparseArray<>();
        displaySizes.forEach(size -> {
            mDisplaySizes.put(getOrientation(size), size);
            Point rotated = new Point(size.y, size.x);
            mDisplaySizes.put(getOrientation(rotated), rotated);
        });
        when(mWallpaperDisplayHelper.getDefaultDisplaySizes()).thenReturn(mDisplaySizes);
        if (displaySizes.size() == 2) {
            Point largestDisplay = displaySizes.stream().max(
                    Comparator.comparingInt(p -> p.x * p.y)).get();
            Point smallestDisplay = displaySizes.stream().min(
                    Comparator.comparingInt(p -> p.x * p.y)).get();
            mUnfolded = getOrientation(largestDisplay);
            mFolded = getOrientation(smallestDisplay);
            mUnfoldedRotated = getRotatedOrientation(mUnfolded);
            mFoldedRotated = getRotatedOrientation(mFolded);
        }
        doAnswer(invocation -> getFoldedOrientation(invocation.getArgument(0)))
                .when(mWallpaperDisplayHelper).getFoldedOrientation(anyInt());
        doAnswer(invocation -> getUnfoldedOrientation(invocation.getArgument(0)))
                .when(mWallpaperDisplayHelper).getUnfoldedOrientation(anyInt());
    }

    private int getFoldedOrientation(int orientation) {
        if (orientation == ORIENTATION_UNKNOWN) return ORIENTATION_UNKNOWN;
        if (orientation == mUnfolded) return mFolded;
        if (orientation == mUnfoldedRotated) return mFoldedRotated;
        return ORIENTATION_UNKNOWN;
    }

    private int getUnfoldedOrientation(int orientation) {
        if (orientation == ORIENTATION_UNKNOWN) return ORIENTATION_UNKNOWN;
        if (orientation == mFolded) return mUnfolded;
        if (orientation == mFoldedRotated) return mUnfoldedRotated;
        return ORIENTATION_UNKNOWN;
    }

    /**
     * Test that {@link WallpaperCropper#noParallax} successfully removes the parallax in a simple
     * case, removing the right or left part depending on the "rtl" argument.
     */
    @Test
    public void testNoParallax_noScale() {
        Point displaySize = new Point(1000, 1000);
        Point bitmapSize = new Point(1200, 1000);
        Point expectedCropSize = new Point(1000, 1000);
        Rect crop = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
        assertThat(WallpaperCropper.noParallax(crop, displaySize, bitmapSize, /* rtl */ false))
                .isEqualTo(leftOf(crop, expectedCropSize));
        assertThat(WallpaperCropper.noParallax(crop, displaySize, bitmapSize, /* rtl */ true))
                .isEqualTo(rightOf(crop, expectedCropSize));
    }

    /**
     * Test that {@link WallpaperCropper#noParallax} correctly takes zooming into account.
     */
    @Test
    public void testNoParallax_withScale() {
        Point displaySize = new Point(1000, 1000);
        Point bitmapSize = new Point(600, 500);
        Rect crop = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
        Point expectedCropSize = new Point(500, 500);
        assertThat(WallpaperCropper.noParallax(crop, displaySize, bitmapSize, /* rtl */ false))
                .isEqualTo(leftOf(crop, expectedCropSize));
        assertThat(WallpaperCropper.noParallax(crop, displaySize, bitmapSize, /* rtl */ true))
                .isEqualTo(rightOf(crop, expectedCropSize));
    }

    /**
     * Test that {@link WallpaperCropper#noParallax} correctly removes parallax when the image is
     * cropped, i.e. when the crop rectangle is not the full bitmap.
     */
    @Test
    public void testNoParallax_withScaleAndCrop() {
        Point displaySize = new Point(1000, 1000);
        Point bitmapSize = new Point(2000, 2000);
        Rect crop = new Rect(300, 1000, 900, 1500);
        Point expectedCropSize = new Point(500, 500);
        assertThat(WallpaperCropper.noParallax(crop, displaySize, bitmapSize, /* rtl */ false))
                .isEqualTo(leftOf(crop, expectedCropSize));
        assertThat(WallpaperCropper.noParallax(crop, displaySize, bitmapSize, /* rtl */ true))
                .isEqualTo(rightOf(crop, expectedCropSize));
    }

    /**
     * Test that {@link WallpaperCropper#getAdjustedCrop} does nothing when the crop has the same
     * width/height ratio than the screen.
     */
    @Test
    public void testGetAdjustedCrop_noOp() {
        Point displaySize = new Point(1000, 1000);

        for (Point bitmapSize: List.of(
                new Point(1000, 1000),
                new Point(2000, 2000),
                new Point(500, 500))) {
            for (Rect crop: List.of(
                    new Rect(0, 0, bitmapSize.x, bitmapSize.y),
                    new Rect(100, 200, bitmapSize.x - 100, bitmapSize.y))) {
                for (int mode: ALL_MODES) {
                    for (boolean parallax: List.of(true, false)) {
                        assertThat(WallpaperCropper.getAdjustedCrop(
                                crop, bitmapSize, displaySize, parallax, mode))
                                .isEqualTo(crop);
                    }
                }
            }
        }
    }

    /**
     * Test that {@link WallpaperCropper#getAdjustedCrop}, when called with parallax = true,
     * does not keep more width than needed for {@link WallpaperCropper#MAX_PARALLAX}.
     */
    @Test
    public void testGetAdjustedCrop_tooMuchParallax() {
        Point displaySize = new Point(1000, 1000);
        int tooLargeWidth = (int) (displaySize.x * (1 + 2 * WallpaperCropper.MAX_PARALLAX));
        Point bitmapSize = new Point(tooLargeWidth, 1000);
        Rect crop = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
        int expectedWidth = (int) (displaySize.x * (1 + WallpaperCropper.MAX_PARALLAX));
        Point expectedCropSize = new Point(expectedWidth, 1000);
        for (int mode: ALL_MODES) {
            assertThat(WallpaperCropper.getAdjustedCrop(
                    crop, bitmapSize, displaySize, true, mode))
                    .isEqualTo(centerOf(crop, expectedCropSize));
        }
    }

    /**
     * Test that {@link WallpaperCropper#getAdjustedCrop}, when called with parallax = true,
     * does not remove parallax if the parallax is below {@link WallpaperCropper#MAX_PARALLAX}.
     */
    @Test
    public void testGetAdjustedCrop_acceptableParallax() {
        Point displaySize = new Point(1000, 1000);
        List<Integer> acceptableWidths = List.of(displaySize.x,
                (int) (displaySize.x * (1 + 0.5 * WallpaperCropper.MAX_PARALLAX)),
                (int) (displaySize.x * (1 + 0.9 * WallpaperCropper.MAX_PARALLAX)),
                (int) (displaySize.x * (1 + 1.0 * WallpaperCropper.MAX_PARALLAX)));
        for (int acceptableWidth: acceptableWidths) {
            Point bitmapSize = new Point(acceptableWidth, 1000);
            Rect crop = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
            for (int mode : ALL_MODES) {
                assertThat(WallpaperCropper.getAdjustedCrop(
                        crop, bitmapSize, displaySize, true, mode))
                        .isEqualTo(crop);
            }
        }
    }

    /**
     * Test that {@link WallpaperCropper#getAdjustedCrop}, when called with
     * {@link WallpaperCropper#ADD}, correctly enlarges the crop to match the display dimensions,
     * and adds content to the crop by an equal amount on both sides when possible.
     */
    @Test
    public void testGetAdjustedCrop_add() {
        Point displaySize = new Point(1000, 1000);
        Point bitmapSize = new Point(1000, 1000);

        List<Rect> crops = List.of(
                new Rect(0, 0, 900, 1000),
                new Rect(0, 0, 1000, 900),
                new Rect(0, 0, 400, 500),
                new Rect(500, 600, 1000, 1000));

        List<Rect> expectedAdjustedCrops = List.of(
                new Rect(0, 0, 1000, 1000),
                new Rect(0, 0, 1000, 1000),
                new Rect(0, 0, 500, 500),
                new Rect(500, 500, 1000, 1000));

        for (int i = 0; i < crops.size(); i++) {
            Rect crop = crops.get(i);
            Rect expectedCrop = expectedAdjustedCrops.get(i);
            assertThat(WallpaperCropper.getAdjustedCrop(
                    crop, bitmapSize, displaySize, false, WallpaperCropper.ADD))
                    .isEqualTo(expectedCrop);
        }
    }

    /**
     * Test that {@link WallpaperCropper#getAdjustedCrop}, when called with
     * {@link WallpaperCropper#REMOVE}, correctly shrinks the crop to match the display dimensions,
     * and removes content by an equal amount on both sides.
     */
    @Test
    public void testGetAdjustedCrop_remove() {
        Point displaySize = new Point(1000, 1000);
        Point bitmapSize = new Point(1500, 1500);

        List<Rect> crops = List.of(
                new Rect(50, 0, 1150, 1000),
                new Rect(0, 50, 1000, 1150));

        Point expectedCropSize = new Point(1000, 1000);

        for (Rect crop: crops) {
            assertThat(WallpaperCropper.getAdjustedCrop(
                    crop, bitmapSize, displaySize, false, WallpaperCropper.REMOVE))
                    .isEqualTo(centerOf(crop, expectedCropSize));
        }
    }

    /**
     * Test that {@link WallpaperCropper#getAdjustedCrop}, when called with
     * {@link WallpaperCropper#BALANCE}, gives an adjusted crop with the same center and same number
     * of pixels when possible.
     */
    @Test
    public void testGetAdjustedCrop_balance() {
        Point displaySize = new Point(500, 1000);
        Point transposedDisplaySize = new Point(1000, 500);
        Point bitmapSize = new Point(1000, 1000);

        List<Rect> crops = List.of(
                new Rect(0, 250, 1000, 750),
                new Rect(100, 0, 300, 100));

        List<Rect> expectedAdjustedCrops = List.of(
                new Rect(250, 0, 750, 1000),
                new Rect(150, 0, 250, 200));

        for (int i = 0; i < crops.size(); i++) {
            Rect crop = crops.get(i);
            Rect expected = expectedAdjustedCrops.get(i);
            assertThat(WallpaperCropper.getAdjustedCrop(
                    crop, bitmapSize, displaySize, false, WallpaperCropper.BALANCE))
                    .isEqualTo(expected);

            Rect transposedCrop = new Rect(crop.top, crop.left, crop.bottom, crop.right);
            Rect expectedTransposed = new Rect(
                    expected.top, expected.left, expected.bottom, expected.right);
            assertThat(WallpaperCropper.getAdjustedCrop(transposedCrop, bitmapSize,
                    transposedDisplaySize, false, WallpaperCropper.BALANCE))
                    .isEqualTo(expectedTransposed);
        }
    }

    /**
     * Test that {@link WallpaperCropper#getCrop} uses the full image when no crops are provided.
     * If the image has more width/height ratio than the screen, keep that width for parallax up
     * to {@link WallpaperCropper#MAX_PARALLAX}. If the crop has less width/height ratio, remove the
     * surplus height, on both sides to keep the wallpaper centered.
     */
    @Test
    public void testGetCrop_noSuggestedCrops() {
        setUpWithDisplays(STANDARD_DISPLAY);
        Point bitmapSize = new Point(800, 1000);
        Rect bitmapRect = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
        SparseArray<Rect> suggestedCrops = new SparseArray<>();

        List<Point> displaySizes = List.of(
                new Point(500, 1000),
                new Point(200, 1000),
                new Point(1000, 500));
        List<Point> expectedCropSizes = List.of(
                new Point(Math.min(800, (int) (500 * (1 + WallpaperCropper.MAX_PARALLAX))), 1000),
                new Point(Math.min(800, (int) (200 * (1 + WallpaperCropper.MAX_PARALLAX))), 1000),
                new Point(800, 400));

        for (int i = 0; i < displaySizes.size(); i++) {
            Point displaySize = displaySizes.get(i);
            Point expectedCropSize = expectedCropSizes.get(i);
            for (boolean rtl : List.of(false, true)) {
                assertThat(mWallpaperCropper.getCrop(
                        displaySize, bitmapSize, suggestedCrops, rtl))
                        .isEqualTo(centerOf(bitmapRect, expectedCropSize));
            }
        }
    }

    /**
     * Test that {@link WallpaperCropper#getCrop} reuses a suggested crop of the same orientation
     * as the display if possible, and does not remove additional width for parallax,
     * but adds width if necessary.
     */
    @Test
    public void testGetCrop_hasSuggestedCrop() {
        setUpWithDisplays(STANDARD_DISPLAY);
        Point bitmapSize = new Point(800, 1000);
        SparseArray<Rect> suggestedCrops = new SparseArray<>();
        suggestedCrops.put(PORTRAIT, new Rect(0, 0, 400, 800));
        for (int otherOrientation: List.of(LANDSCAPE, SQUARE_LANDSCAPE, SQUARE_PORTRAIT)) {
            suggestedCrops.put(otherOrientation, new Rect(0, 0, 10, 10));
        }

        for (boolean rtl : List.of(false, true)) {
            assertThat(mWallpaperCropper.getCrop(
                    new Point(300, 800), bitmapSize, suggestedCrops, rtl))
                    .isEqualTo(suggestedCrops.get(PORTRAIT));
            assertThat(mWallpaperCropper.getCrop(
                    new Point(500, 800), bitmapSize, suggestedCrops, rtl))
                    .isEqualTo(new Rect(0, 0, 500, 800));
        }
    }

    /**
     * Test that {@link WallpaperCropper#getCrop}, if there is no suggested crop of the same
     * orientation as the display, reuses a suggested crop of the rotated orientation if possible,
     * and preserves the center and number of pixels of the crop if possible.
     * <p>
     * To simplify, in this test case all crops have the same size as the display (no zoom)
     * and are at the center of the image. Also the image is large enough to preserver the number
     * of pixels (no additional zoom required).
     */
    @Test
    public void testGetCrop_hasRotatedSuggestedCrop() {
        setUpWithDisplays(STANDARD_DISPLAY);
        Point bitmapSize = new Point(2000, 1800);
        Rect bitmapRect = new Rect(0, 0, bitmapSize.x, bitmapSize.y);
        SparseArray<Rect> suggestedCrops = new SparseArray<>();
        Point portrait = PORTRAIT_ONE;
        Point landscape = new Point(PORTRAIT_ONE.y, PORTRAIT_ONE.x);
        Point squarePortrait = SQUARE_PORTRAIT_ONE;
        Point squareLandscape = new Point(SQUARE_PORTRAIT_ONE.y, SQUARE_PORTRAIT_ONE.y);
        suggestedCrops.put(PORTRAIT, centerOf(bitmapRect, portrait));
        suggestedCrops.put(SQUARE_LANDSCAPE, centerOf(bitmapRect, squareLandscape));
        for (boolean rtl : List.of(false, true)) {
            assertThat(mWallpaperCropper.getCrop(
                    landscape, bitmapSize, suggestedCrops, rtl))
                    .isEqualTo(centerOf(bitmapRect, landscape));

            assertThat(mWallpaperCropper.getCrop(
                    squarePortrait, bitmapSize, suggestedCrops, rtl))
                    .isEqualTo(centerOf(bitmapRect, squarePortrait));
        }
    }

    /**
     * Test that {@link WallpaperCropper#getCrop}, when asked for a folded crop with a suggested
     * crop only for the relative unfolded orientation, creates the folded crop at the center of the
     * unfolded crop, by removing content on two sides to match the folded screen dimensions, and
     * then adds some width for parallax.
     * <p>
     * To simplify, in this test case all crops have the same size as the display (no zoom)
     * and are at the center of the image.
     */
    @Test
    public void testGetCrop_hasUnfoldedSuggestedCrop() {
        for (List<Point> displaySizes : ALL_FOLDABLE_DISPLAYS) {
            setUpWithDisplays(displaySizes);
            Point bitmapSize = new Point(2000, 2400);
            Rect bitmapRect = new Rect(0, 0, bitmapSize.x, bitmapSize.y);

            Point largestDisplay = displaySizes.stream().max(
                    Comparator.comparingInt(a -> a.x * a.y)).orElseThrow();
            int unfoldedOne = getOrientation(largestDisplay);
            int unfoldedTwo = getRotatedOrientation(unfoldedOne);
            Rect unfoldedCropOne = centerOf(bitmapRect, mDisplaySizes.get(unfoldedOne));
            Rect unfoldedCropTwo = centerOf(bitmapRect, mDisplaySizes.get(unfoldedTwo));
            List<Rect> unfoldedCrops = List.of(unfoldedCropOne, unfoldedCropTwo);
            SparseArray<Rect> suggestedCrops = new SparseArray<>();
            suggestedCrops.put(unfoldedOne, unfoldedCropOne);
            suggestedCrops.put(unfoldedTwo, unfoldedCropTwo);

            int foldedOne = getFoldedOrientation(unfoldedOne);
            int foldedTwo = getFoldedOrientation(unfoldedTwo);
            Point foldedDisplayOne = mDisplaySizes.get(foldedOne);
            Point foldedDisplayTwo = mDisplaySizes.get(foldedTwo);
            List<Point> foldedDisplays = List.of(foldedDisplayOne, foldedDisplayTwo);

            for (boolean rtl : List.of(false, true)) {
                for (int i = 0; i < 2; i++) {
                    Rect unfoldedCrop = unfoldedCrops.get(i);
                    Point foldedDisplay = foldedDisplays.get(i);
                    Rect expectedCrop = centerOf(unfoldedCrop, foldedDisplay);
                    int maxParallax = (int) (WallpaperCropper.MAX_PARALLAX * unfoldedCrop.width());

                    // the expected behaviour is that we add width for parallax until we reach
                    // either MAX_PARALLAX or the edge of the crop for the unfolded screen.
                    if (rtl) {
                        expectedCrop.left = Math.max(
                                unfoldedCrop.left, expectedCrop.left - maxParallax);
                    } else {
                        expectedCrop.right = Math.min(
                                unfoldedCrop.right, unfoldedCrop.right + maxParallax);
                    }
                    assertThat(mWallpaperCropper.getCrop(
                            foldedDisplay, bitmapSize, suggestedCrops, rtl))
                            .isEqualTo(expectedCrop);
                }
            }
        }
    }

    /**
     * Test that {@link WallpaperCropper#getCrop}, when asked for an unfolded crop with a suggested
     * crop only for the relative folded orientation, creates the unfolded crop with the same center
     * as the folded crop, by adding content on two sides to match the unfolded screen dimensions.
     * <p>
     * To simplify, in this test case all crops have the same size as the display (no zoom) and are
     * at the center of the image. Also the image is large enough to add content.
     */
    @Test
    public void testGetCrop_hasFoldedSuggestedCrop() {
        for (List<Point> displaySizes : ALL_FOLDABLE_DISPLAYS) {
            setUpWithDisplays(displaySizes);
            Point bitmapSize = new Point(2000, 2000);
            Rect bitmapRect = new Rect(0, 0, 2000, 2000);

            Point smallestDisplay = displaySizes.stream().min(
                    Comparator.comparingInt(a -> a.x * a.y)).orElseThrow();
            int foldedOne = getOrientation(smallestDisplay);
            int foldedTwo = getRotatedOrientation(foldedOne);
            Point foldedDisplayOne = mDisplaySizes.get(foldedOne);
            Point foldedDisplayTwo = mDisplaySizes.get(foldedTwo);
            Rect foldedCropOne = centerOf(bitmapRect, foldedDisplayOne);
            Rect foldedCropTwo = centerOf(bitmapRect, foldedDisplayTwo);
            SparseArray<Rect> suggestedCrops = new SparseArray<>();
            suggestedCrops.put(foldedOne, foldedCropOne);
            suggestedCrops.put(foldedTwo, foldedCropTwo);

            int unfoldedOne = getUnfoldedOrientation(foldedOne);
            int unfoldedTwo = getUnfoldedOrientation(foldedTwo);
            Point unfoldedDisplayOne = mDisplaySizes.get(unfoldedOne);
            Point unfoldedDisplayTwo = mDisplaySizes.get(unfoldedTwo);

            for (boolean rtl : List.of(false, true)) {
                assertThat(centerOf(mWallpaperCropper.getCrop(
                        unfoldedDisplayOne, bitmapSize, suggestedCrops, rtl), foldedDisplayOne))
                        .isEqualTo(foldedCropOne);

                assertThat(centerOf(mWallpaperCropper.getCrop(
                        unfoldedDisplayTwo, bitmapSize, suggestedCrops, rtl), foldedDisplayTwo))
                        .isEqualTo(foldedCropTwo);
            }
        }
    }

    /**
     * Test that {@link WallpaperCropper#getCrop}, when asked for an folded crop with a suggested
     * crop only for the rotated unfolded orientation, creates the folded crop from that crop by
     * combining a rotate + fold operation. The folded crop should have less pixels than the
     * unfolded crop due to the fold operation which removes content on both sides of the image.
     * <p>
     * To simplify, in this test case all crops have the same size as the display (no zoom)
     * and are at the center of the image.
     */
    @Test
    public void testGetCrop_hasRotatedUnfoldedSuggestedCrop() {
        for (List<Point> displaySizes : ALL_FOLDABLE_DISPLAYS) {
            setUpWithDisplays(displaySizes);
            Point bitmapSize = new Point(2000, 2000);
            Rect bitmapRect = new Rect(0, 0, 2000, 2000);
            Point largestDisplay = displaySizes.stream().max(
                    Comparator.comparingInt(a -> a.x * a.y)).orElseThrow();
            int unfoldedOne = getOrientation(largestDisplay);
            int unfoldedTwo = getRotatedOrientation(unfoldedOne);
            for (int unfolded: List.of(unfoldedOne, unfoldedTwo)) {
                Rect unfoldedCrop = centerOf(bitmapRect, mDisplaySizes.get(unfolded));
                int rotatedUnfolded = getRotatedOrientation(unfolded);
                Rect rotatedUnfoldedCrop = centerOf(bitmapRect, mDisplaySizes.get(rotatedUnfolded));
                SparseArray<Rect> suggestedCrops = new SparseArray<>();
                suggestedCrops.put(unfolded, unfoldedCrop);
                int rotatedFolded = getFoldedOrientation(rotatedUnfolded);
                Point rotatedFoldedDisplay = mDisplaySizes.get(rotatedFolded);

                for (boolean rtl : List.of(false, true)) {
                    assertThat(mWallpaperCropper.getCrop(
                            rotatedFoldedDisplay, bitmapSize, suggestedCrops, rtl))
                            .isEqualTo(centerOf(rotatedUnfoldedCrop, rotatedFoldedDisplay));
                }
            }
        }
    }

    /**
     * Test that {@link WallpaperCropper#getCrop}, when asked for an unfolded crop with a suggested
     * crop only for the rotated folded orientation, creates the unfolded crop from that crop by
     * combining a rotate + unfold operation. The unfolded crop should have more pixels than the
     * folded crop due to the unfold operation which adds content on two sides of the image.
     * <p>
     * To simplify, in this test case all crops have the same size as the display (no zoom)
     * and are centered inside the image. Also the image is large enough to add content.
     */
    @Test
    public void testGetCrop_hasRotatedFoldedSuggestedCrop() {
        for (List<Point> displaySizes : ALL_FOLDABLE_DISPLAYS) {
            setUpWithDisplays(displaySizes);
            Point bitmapSize = new Point(2000, 2000);
            Rect bitmapRect = new Rect(0, 0, 2000, 2000);

            Point smallestDisplay = displaySizes.stream().min(
                    Comparator.comparingInt(a -> a.x * a.y)).orElseThrow();
            int foldedOne = getOrientation(smallestDisplay);
            int foldedTwo = getRotatedOrientation(foldedOne);
            for (int folded: List.of(foldedOne, foldedTwo)) {
                Rect foldedCrop = centerOf(bitmapRect, mDisplaySizes.get(folded));
                SparseArray<Rect> suggestedCrops = new SparseArray<>();
                suggestedCrops.put(folded, foldedCrop);
                int rotatedFolded = getRotatedOrientation(folded);
                int rotatedUnfolded = getUnfoldedOrientation(rotatedFolded);
                Point rotatedFoldedDisplay = mDisplaySizes.get(rotatedFolded);
                Rect rotatedFoldedCrop = centerOf(bitmapRect, rotatedFoldedDisplay);
                Point rotatedUnfoldedDisplay = mDisplaySizes.get(rotatedUnfolded);

                for (boolean rtl : List.of(false, true)) {
                    Rect rotatedUnfoldedCrop = mWallpaperCropper.getCrop(
                            rotatedUnfoldedDisplay, bitmapSize, suggestedCrops, rtl);
                    assertThat(centerOf(rotatedUnfoldedCrop, rotatedFoldedDisplay))
                            .isEqualTo(rotatedFoldedCrop);
                }
            }
        }
    }

    private static Rect centerOf(Rect container, Point point) {
        checkSubset(container, point);
        int diffWidth = container.width() - point.x;
        int diffHeight = container.height() - point.y;
        int startX = container.left + diffWidth / 2;
        int startY = container.top + diffHeight / 2;
        return new Rect(startX, startY, startX + point.x, startY + point.y);
    }

    private static Rect leftOf(Rect container, Point point) {
        Rect result = centerOf(container, point);
        result.offset(container.left - result.left, 0);
        return result;
    }

    private static Rect rightOf(Rect container, Point point) {
        checkSubset(container, point);
        Rect result = centerOf(container, point);
        result.offset(container.right - result.right, 0);
        return result;
    }

    private static void checkSubset(Rect container, Point point) {
        if (container.width() < point.x || container.height() < point.y) {
            throw new IllegalArgumentException();
        }
    }
}
