/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.wallpapers;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.WallpaperColors;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class WallpaperLocalColorExtractorTest extends SysuiTestCase {
    private static final int LOW_BMP_WIDTH = 128;
    private static final int LOW_BMP_HEIGHT = 128;
    private static final int HIGH_BMP_WIDTH = 3000;
    private static final int HIGH_BMP_HEIGHT = 4000;
    private static final int VERY_LOW_BMP_WIDTH = 1;
    private static final int VERY_LOW_BMP_HEIGHT = 1;
    private static final int DISPLAY_WIDTH = 1920;
    private static final int DISPLAY_HEIGHT = 1080;

    private static final int PAGES_LOW = 4;
    private static final int PAGES_HIGH = 7;

    private static final int MIN_AREAS = 4;
    private static final int MAX_AREAS = 10;

    private int mMiniBitmapWidth;
    private int mMiniBitmapHeight;

    @Mock
    private Executor mBackgroundExecutor;

    private int mColorsProcessed;
    private int mMiniBitmapUpdatedCount;
    private int mActivatedCount;
    private int mDeactivatedCount;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);
        doAnswer(invocation ->  {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mBackgroundExecutor).execute(any(Runnable.class));
    }

    private void resetCounters() {
        mColorsProcessed = 0;
        mMiniBitmapUpdatedCount = 0;
        mActivatedCount = 0;
        mDeactivatedCount = 0;
    }

    private Bitmap getMockBitmap(int width, int height) {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.getWidth()).thenReturn(width);
        when(bitmap.getHeight()).thenReturn(height);
        return bitmap;
    }

    private WallpaperLocalColorExtractor getSpyWallpaperLocalColorExtractor() {

        WallpaperLocalColorExtractor colorExtractor = new WallpaperLocalColorExtractor(
                mBackgroundExecutor,
                new Object(),
                new WallpaperLocalColorExtractor.WallpaperLocalColorExtractorCallback() {
                    @Override
                    public void onColorsProcessed(List<RectF> regions,
                            List<WallpaperColors> colors) {
                        assertThat(regions.size()).isEqualTo(colors.size());
                        mColorsProcessed += regions.size();
                    }

                    @Override
                    public void onMiniBitmapUpdated() {
                        mMiniBitmapUpdatedCount++;
                    }

                    @Override
                    public void onActivated() {
                        mActivatedCount++;
                    }

                    @Override
                    public void onDeactivated() {
                        mDeactivatedCount++;
                    }
                });
        WallpaperLocalColorExtractor spyColorExtractor = spy(colorExtractor);

        doAnswer(invocation -> {
            mMiniBitmapWidth = invocation.getArgument(1);
            mMiniBitmapHeight = invocation.getArgument(2);
            return getMockBitmap(mMiniBitmapWidth, mMiniBitmapHeight);
        }).when(spyColorExtractor).createMiniBitmap(any(Bitmap.class), anyInt(), anyInt());


        doAnswer(invocation -> getMockBitmap(
                        invocation.getArgument(1),
                        invocation.getArgument(2)))
                .when(spyColorExtractor)
                .createMiniBitmap(any(Bitmap.class), anyInt(), anyInt());

        doReturn(new WallpaperColors(Color.valueOf(0), Color.valueOf(0), Color.valueOf(0)))
                .when(spyColorExtractor).getLocalWallpaperColors(any(Rect.class));

        return spyColorExtractor;
    }

    private RectF randomArea() {
        float width = (float) Math.random();
        float startX = (float) (Math.random() * (1 - width));
        float height = (float) Math.random();
        float startY = (float) (Math.random() * (1 - height));
        return new RectF(startX, startY, startX + width, startY + height);
    }

    private List<RectF> listOfRandomAreas(int min, int max) {
        int nAreas = randomBetween(min, max);
        List<RectF> result = new ArrayList<>();
        for (int i = 0; i < nAreas; i++) {
            result.add(randomArea());
        }
        return result;
    }

    private int randomBetween(int minIncluded, int maxIncluded) {
        return (int) (Math.random() * ((maxIncluded - minIncluded) + 1)) + minIncluded;
    }

    /**
     * Test that for bitmaps of random dimensions, the mini bitmap is always created
     * with either a width <= SMALL_SIDE or a height <= SMALL_SIDE
     */
    @Test
    public void testMiniBitmapCreation() {
        WallpaperLocalColorExtractor spyColorExtractor = getSpyWallpaperLocalColorExtractor();
        int nSimulations = 10;
        for (int i = 0; i < nSimulations; i++) {
            resetCounters();
            int width = randomBetween(LOW_BMP_WIDTH, HIGH_BMP_WIDTH);
            int height = randomBetween(LOW_BMP_HEIGHT, HIGH_BMP_HEIGHT);
            Bitmap bitmap = getMockBitmap(width, height);
            spyColorExtractor.onBitmapChanged(bitmap);

            assertThat(mMiniBitmapUpdatedCount).isEqualTo(1);
            assertThat(Math.min(mMiniBitmapWidth, mMiniBitmapHeight))
                    .isAtMost(WallpaperLocalColorExtractor.SMALL_SIDE);
        }
    }

    /**
     * Test that for bitmaps with both width and height <= SMALL_SIDE,
     * the mini bitmap is always created with both width and height <= SMALL_SIDE
     */
    @Test
    public void testSmallMiniBitmapCreation() {
        WallpaperLocalColorExtractor spyColorExtractor = getSpyWallpaperLocalColorExtractor();
        int nSimulations = 10;
        for (int i = 0; i < nSimulations; i++) {
            resetCounters();
            int width = randomBetween(VERY_LOW_BMP_WIDTH, LOW_BMP_WIDTH);
            int height = randomBetween(VERY_LOW_BMP_HEIGHT, LOW_BMP_HEIGHT);
            Bitmap bitmap = getMockBitmap(width, height);
            spyColorExtractor.onBitmapChanged(bitmap);

            assertThat(mMiniBitmapUpdatedCount).isEqualTo(1);
            assertThat(Math.max(mMiniBitmapWidth, mMiniBitmapHeight))
                    .isAtMost(WallpaperLocalColorExtractor.SMALL_SIDE);
        }
    }

    /**
     * Test that for a new color extractor with information
     * (number of pages, display dimensions, wallpaper bitmap) given in random order,
     * the colors are processed and all the callbacks are properly executed.
     */
    @Test
    public void testNewColorExtraction() {
        Bitmap bitmap = getMockBitmap(HIGH_BMP_WIDTH, HIGH_BMP_HEIGHT);

        int nSimulations = 10;
        for (int i = 0; i < nSimulations; i++) {
            resetCounters();
            WallpaperLocalColorExtractor spyColorExtractor = getSpyWallpaperLocalColorExtractor();
            List<RectF> regions = listOfRandomAreas(MIN_AREAS, MAX_AREAS);
            int nPages = randomBetween(PAGES_LOW, PAGES_HIGH);
            List<Runnable> tasks = Arrays.asList(
                    () -> spyColorExtractor.onPageChanged(nPages),
                    () -> spyColorExtractor.onBitmapChanged(bitmap),
                    () -> spyColorExtractor.setDisplayDimensions(
                            DISPLAY_WIDTH, DISPLAY_HEIGHT),
                    () -> spyColorExtractor.addLocalColorsAreas(
                            regions));
            Collections.shuffle(tasks);
            tasks.forEach(Runnable::run);

            assertThat(mActivatedCount).isEqualTo(1);
            assertThat(mMiniBitmapUpdatedCount).isEqualTo(1);
            assertThat(mColorsProcessed).isEqualTo(regions.size());

            spyColorExtractor.removeLocalColorAreas(regions);
            assertThat(mDeactivatedCount).isEqualTo(1);
        }
    }

    /**
     * Test that the method removeLocalColorAreas behaves properly and does not call
     * the onDeactivated callback unless all color areas are removed.
     */
    @Test
    public void testRemoveColors() {
        Bitmap bitmap = getMockBitmap(HIGH_BMP_WIDTH, HIGH_BMP_HEIGHT);
        int nSimulations = 10;
        for (int i = 0; i < nSimulations; i++) {
            resetCounters();
            WallpaperLocalColorExtractor spyColorExtractor = getSpyWallpaperLocalColorExtractor();
            List<RectF> regions1 = listOfRandomAreas(MIN_AREAS / 2, MAX_AREAS / 2);
            List<RectF> regions2 = listOfRandomAreas(MIN_AREAS / 2, MAX_AREAS / 2);
            List<RectF> regions = new ArrayList<>();
            regions.addAll(regions1);
            regions.addAll(regions2);
            int nPages = randomBetween(PAGES_LOW, PAGES_HIGH);
            List<Runnable> tasks = Arrays.asList(
                    () -> spyColorExtractor.onPageChanged(nPages),
                    () -> spyColorExtractor.onBitmapChanged(bitmap),
                    () -> spyColorExtractor.setDisplayDimensions(
                            DISPLAY_WIDTH, DISPLAY_HEIGHT),
                    () -> spyColorExtractor.removeLocalColorAreas(regions1));

            spyColorExtractor.addLocalColorsAreas(regions);
            assertThat(mActivatedCount).isEqualTo(1);
            Collections.shuffle(tasks);
            tasks.forEach(Runnable::run);

            assertThat(mMiniBitmapUpdatedCount).isEqualTo(1);
            assertThat(mDeactivatedCount).isEqualTo(0);
            spyColorExtractor.removeLocalColorAreas(regions2);
            assertThat(mDeactivatedCount).isEqualTo(1);
        }
    }

    /**
     * Test that if we change some information (wallpaper bitmap, number of pages),
     * the colors are correctly recomputed.
     * Test that if we remove some color areas in the middle of the process,
     * only the remaining areas are recomputed.
     */
    @Test
    public void testRecomputeColorExtraction() {
        Bitmap bitmap = getMockBitmap(HIGH_BMP_WIDTH, HIGH_BMP_HEIGHT);
        WallpaperLocalColorExtractor spyColorExtractor = getSpyWallpaperLocalColorExtractor();
        List<RectF> regions1 = listOfRandomAreas(MIN_AREAS / 2, MAX_AREAS / 2);
        List<RectF> regions2 = listOfRandomAreas(MIN_AREAS / 2, MAX_AREAS / 2);
        List<RectF> regions = new ArrayList<>();
        regions.addAll(regions1);
        regions.addAll(regions2);
        spyColorExtractor.addLocalColorsAreas(regions);
        assertThat(mActivatedCount).isEqualTo(1);
        int nPages = PAGES_LOW;
        spyColorExtractor.onBitmapChanged(bitmap);
        spyColorExtractor.onPageChanged(nPages);
        spyColorExtractor.setDisplayDimensions(DISPLAY_WIDTH, DISPLAY_HEIGHT);

        int nSimulations = 20;
        for (int i = 0; i < nSimulations; i++) {
            resetCounters();

            // verify that if we remove some regions, they are not recomputed after other changes
            if (i == nSimulations / 2) {
                regions.removeAll(regions2);
                spyColorExtractor.removeLocalColorAreas(regions2);
            }

            if (Math.random() >= 0.5) {
                int nPagesNew = randomBetween(PAGES_LOW, PAGES_HIGH);
                if (nPagesNew == nPages) continue;
                nPages = nPagesNew;
                spyColorExtractor.onPageChanged(nPagesNew);
            } else {
                Bitmap newBitmap = getMockBitmap(HIGH_BMP_WIDTH, HIGH_BMP_HEIGHT);
                spyColorExtractor.onBitmapChanged(newBitmap);
                assertThat(mMiniBitmapUpdatedCount).isEqualTo(1);
            }
            assertThat(mColorsProcessed).isEqualTo(regions.size());
        }
        spyColorExtractor.removeLocalColorAreas(regions);
        assertThat(mDeactivatedCount).isEqualTo(1);
    }

    @Test
    public void testCleanUp() {
        resetCounters();
        Bitmap bitmap = getMockBitmap(HIGH_BMP_WIDTH, HIGH_BMP_HEIGHT);
        doNothing().when(bitmap).recycle();
        WallpaperLocalColorExtractor spyColorExtractor = getSpyWallpaperLocalColorExtractor();
        spyColorExtractor.onPageChanged(PAGES_LOW);
        spyColorExtractor.onBitmapChanged(bitmap);
        assertThat(mMiniBitmapUpdatedCount).isEqualTo(1);
        spyColorExtractor.cleanUp();
        spyColorExtractor.addLocalColorsAreas(listOfRandomAreas(MIN_AREAS, MAX_AREAS));
        assertThat(mColorsProcessed).isEqualTo(0);
    }
}
