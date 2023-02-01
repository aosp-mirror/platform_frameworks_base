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

import java.util.Arrays;
import java.util.Comparator;

/**
 * Class which can compute the logical density for a display resolution. It holds a collection
 * of pre-configured densities, which are used for look-up and interpolation.
 */
public class DensityMapping {

    // Instead of resolutions we store the squared diagonal size. Diagonals make the map
    // keys invariant to rotations and are useful for interpolation because they're scalars.
    // Squared diagonals have the same properties as diagonals (the square function is monotonic)
    // but also allow us to use integer types and avoid floating point arithmetics.
    private final Entry[] mSortedDensityMappingEntries;

    /**
     * Creates a density mapping. The newly created object takes ownership of the passed array.
     */
    static DensityMapping createByOwning(Entry[] densityMappingEntries) {
        return new DensityMapping(densityMappingEntries);
    }

    private DensityMapping(Entry[] densityMappingEntries) {
        Arrays.sort(densityMappingEntries, Comparator.comparingInt(
                entry -> entry.squaredDiagonal));
        mSortedDensityMappingEntries = densityMappingEntries;
        verifyDensityMapping(mSortedDensityMappingEntries);
    }

    /**
     * Returns the logical density for the given resolution.
     *
     * If the resolution matches one of the entries in the mapping, the corresponding density is
     * returned. Otherwise the return value is interpolated using the closest entries in the map.
     */
    public int getDensityForResolution(int width, int height) {
        int squaredDiagonal = width * width + height * height;

        // Search for two pre-configured entries "left" and "right" with the following criteria
        //  * left <= squaredDiagonal
        //  * squaredDiagonal - left is minimal
        //  * right > squaredDiagonal
        //  * right - squaredDiagonal is minimal
        Entry left = Entry.ZEROES;
        Entry right = null;

        for (Entry entry : mSortedDensityMappingEntries) {
            if (entry.squaredDiagonal <= squaredDiagonal) {
                left = entry;
            } else {
                right = entry;
                break;
            }
        }

        // Check if we found an exact match.
        if (left.squaredDiagonal == squaredDiagonal) {
            return left.density;
        }

        // If no configured resolution is higher than the specified resolution, interpolate
        // between (0,0) and (maxConfiguredDiagonal, maxConfiguredDensity).
        if (right == null) {
            right = left;  // largest entry in the sorted array
            left = Entry.ZEROES;
        }

        double leftDiagonal = Math.sqrt(left.squaredDiagonal);
        double rightDiagonal = Math.sqrt(right.squaredDiagonal);
        double diagonal = Math.sqrt(squaredDiagonal);

        return (int) Math.round((diagonal - leftDiagonal) * (right.density - left.density)
                / (rightDiagonal - leftDiagonal) + left.density);
    }

    private static void verifyDensityMapping(Entry[] sortedEntries) {
        for (int i = 1; i < sortedEntries.length; i++) {
            Entry prev = sortedEntries[i - 1];
            Entry curr = sortedEntries[i];

            if (prev.squaredDiagonal == curr.squaredDiagonal) {
                // This will most often happen because there are two entries with the same
                // resolution (AxB and AxB) or rotated resolution (AxB and BxA), but it can also
                // happen in the very rare cases when two different resolutions happen to have
                // the same diagonal (e.g. 100x700 and 500x500).
                throw new IllegalStateException("Found two entries in the density mapping with"
                        + " the same diagonal: " + prev + ", " + curr);
            } else if (prev.density > curr.density) {
                throw new IllegalStateException("Found two entries in the density mapping with"
                        + " increasing diagonal but decreasing density: " + prev + ", " + curr);
            }
        }
    }

    @Override
    public String toString() {
        return "DensityMapping{"
                + "mDensityMappingEntries=" + Arrays.toString(mSortedDensityMappingEntries)
                + '}';
    }

    static class Entry {
        public static final Entry ZEROES = new Entry(0, 0, 0);

        public final int squaredDiagonal;
        public final int density;

        Entry(int width, int height, int density) {
            this.squaredDiagonal = width * width + height * height;
            this.density = density;
        }

        @Override
        public String toString() {
            return "DensityMappingEntry{"
                    + "squaredDiagonal=" + squaredDiagonal
                    + ", density=" + density + '}';
        }
    }
}
