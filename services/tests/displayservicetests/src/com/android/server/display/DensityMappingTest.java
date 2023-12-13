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

package com.android.server.display;

import static com.android.server.display.DensityMapping.Entry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DensityMappingTest {

    @Test
    public void testConstructor_withBadConfig_throwsException() {
        assertThrows(IllegalStateException.class, () ->
                DensityMapping.createByOwning(new Entry[]{
                        new Entry(1080, 1920, 320),
                        new Entry(1080, 1920, 320)})
        );

        assertThrows(IllegalStateException.class, () ->
                DensityMapping.createByOwning(new Entry[]{
                        new Entry(1080, 1920, 320),
                        new Entry(1920, 1080, 120)})
        );

        assertThrows(IllegalStateException.class, () ->
                DensityMapping.createByOwning(new Entry[]{
                        new Entry(1080, 1920, 320),
                        new Entry(2160, 3840, 120)})
        );

        assertThrows(IllegalStateException.class, () ->
                DensityMapping.createByOwning(new Entry[]{
                        new Entry(1080, 1920, 320),
                        new Entry(3840, 2160, 120)})
        );

        // Two entries with the same diagonal
        assertThrows(IllegalStateException.class, () ->
                DensityMapping.createByOwning(new Entry[]{
                        new Entry(500, 500, 123),
                        new Entry(100, 700, 456)})
        );
    }

    @Test
    public void testGetDensityForResolution_withResolutionMatch_returnsDensityFromConfig() {
        DensityMapping densityMapping = DensityMapping.createByOwning(new Entry[]{
                new Entry(720, 1280, 213),
                new Entry(1080, 1920, 320),
                new Entry(2160, 3840, 640)});

        assertEquals(213, densityMapping.getDensityForResolution(720, 1280));
        assertEquals(213, densityMapping.getDensityForResolution(1280, 720));

        assertEquals(320, densityMapping.getDensityForResolution(1080, 1920));
        assertEquals(320, densityMapping.getDensityForResolution(1920, 1080));

        assertEquals(640, densityMapping.getDensityForResolution(2160, 3840));
        assertEquals(640, densityMapping.getDensityForResolution(3840, 2160));
    }

    @Test
    public void testGetDensityForResolution_withDiagonalMatch_returnsDensityFromConfig() {
        DensityMapping densityMapping = DensityMapping.createByOwning(
                        new Entry[]{ new Entry(500, 500, 123)});

        // 500x500 has the same diagonal as 100x700
        assertEquals(123, densityMapping.getDensityForResolution(100, 700));
    }

    @Test
    public void testGetDensityForResolution_withOneEntry_withNoMatch_returnsExtrapolatedDensity() {
        DensityMapping densityMapping = DensityMapping.createByOwning(
                new Entry[]{ new Entry(1080, 1920, 320)});

        assertEquals(320, densityMapping.getDensityForResolution(1081, 1920));
        assertEquals(320, densityMapping.getDensityForResolution(1080, 1921));

        assertEquals(640, densityMapping.getDensityForResolution(2160, 3840));
        assertEquals(640, densityMapping.getDensityForResolution(3840, 2160));

        assertEquals(213, densityMapping.getDensityForResolution(720, 1280));
        assertEquals(213, densityMapping.getDensityForResolution(1280, 720));
    }

    @Test
    public void testGetDensityForResolution_withTwoEntries_withNoMatch_returnExtrapolatedDensity() {
        DensityMapping densityMapping = DensityMapping.createByOwning(new Entry[]{
                new Entry(1080, 1920, 320),
                new Entry(2160, 3840, 320)});

        // Resolution is smaller than all entries
        assertEquals(213, densityMapping.getDensityForResolution(720, 1280));
        assertEquals(213, densityMapping.getDensityForResolution(1280, 720));

        // Resolution is bigger than all entries
        assertEquals(320 * 2, densityMapping.getDensityForResolution(2160 * 2, 3840 * 2));
        assertEquals(320 * 2, densityMapping.getDensityForResolution(3840 * 2, 2160 * 2));
    }

    @Test
    public void testGetDensityForResolution_withNoMatch_returnsInterpolatedDensity() {
        {
            DensityMapping densityMapping = DensityMapping.createByOwning(new Entry[]{
                    new Entry(1080, 1920, 320),
                    new Entry(2160, 3840, 320)});

            assertEquals(320, densityMapping.getDensityForResolution(2000, 2000));
        }

        {
            DensityMapping densityMapping = DensityMapping.createByOwning(new Entry[]{
                    new Entry(720, 1280, 213),
                    new Entry(2160, 3840, 640)});

            assertEquals(320, densityMapping.getDensityForResolution(1080, 1920));
            assertEquals(320, densityMapping.getDensityForResolution(1920, 1080));
        }
    }
}
