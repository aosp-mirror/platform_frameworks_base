/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.util;

import android.print.PageRange;

import java.util.Arrays;
import java.util.Comparator;

/**
 * This class contains utility functions for working with page ranges.
 */
public final class PageRangeUtils {

    private static final PageRange[] ALL_PAGES_RANGE = new PageRange[] {PageRange.ALL_PAGES};

    private static final Comparator<PageRange> sComparator = new Comparator<PageRange>() {
        @Override
        public int compare(PageRange lhs, PageRange rhs) {
            return lhs.getStart() - rhs.getStart();
        }
    };

    private PageRangeUtils() {
        /* do nothing - hide constructor */
    }

    /**
     * Checks whether one page range array contains another one.
     *
     * @param ourRanges The container page ranges.
     * @param otherRanges The contained page ranges.
     * @return Whether the container page ranges contains the contained ones.
     */
    public static boolean contains(PageRange[] ourRanges, PageRange[] otherRanges) {
        if (ourRanges == null || otherRanges == null) {
            return false;
        }

        if (Arrays.equals(ourRanges, ALL_PAGES_RANGE)) {
            return true;
        }

        ourRanges = normalize(ourRanges);
        otherRanges = normalize(otherRanges);

        // Note that the code below relies on the ranges being normalized
        // which is they contain monotonically increasing non-intersecting
        // sub-ranges whose start is less that or equal to the end.
        int otherRangeIdx = 0;
        final int ourRangeCount = ourRanges.length;
        final int otherRangeCount = otherRanges.length;
        for (int ourRangeIdx = 0; ourRangeIdx < ourRangeCount; ourRangeIdx++) {
            PageRange ourRange = ourRanges[ourRangeIdx];
            for (; otherRangeIdx < otherRangeCount; otherRangeIdx++) {
                PageRange otherRange = otherRanges[otherRangeIdx];
                if (otherRange.getStart() > ourRange.getEnd()) {
                    break;
                }
                if (otherRange.getStart() < ourRange.getStart()
                        || otherRange.getEnd() > ourRange.getEnd()) {
                    return false;
                }
            }
        }
        if (otherRangeIdx < otherRangeCount) {
            return false;
        }
        return true;
    }

    /**
     * Normalizes a page range, which is the resulting page ranges are
     * non-overlapping with the start lesser than or equal to the end
     * and ordered in an ascending order.
     *
     * @param pageRanges The page ranges to normalize.
     * @return The normalized page ranges.
     */
    public static PageRange[] normalize(PageRange[] pageRanges) {
        if (pageRanges == null) {
            return null;
        }
        final int oldRangeCount = pageRanges.length;
        if (oldRangeCount <= 1) {
            return pageRanges;
        }
        Arrays.sort(pageRanges, sComparator);
        int newRangeCount = 1;
        for (int i = 0; i < oldRangeCount - 1; i++) {
            newRangeCount++;
            PageRange currentRange = pageRanges[i];
            PageRange nextRange = pageRanges[i + 1];
            if (currentRange.getEnd() + 1 >= nextRange.getStart()) {
                newRangeCount--;
                pageRanges[i] = null;
                pageRanges[i + 1] = new PageRange(currentRange.getStart(),
                        Math.max(currentRange.getEnd(), nextRange.getEnd()));
            }
        }
        if (newRangeCount == oldRangeCount) {
            return pageRanges;
        }
        return Arrays.copyOfRange(pageRanges, oldRangeCount - newRangeCount,
                oldRangeCount);
    }

    /**
     * Offsets a the start and end of page ranges with the given value.
     *
     * @param pageRanges The page ranges to offset.
     * @param offset The offset value.
     */
    public static void offset(PageRange[] pageRanges, int offset) {
        if (offset == 0) {
            return;
        }
        final int pageRangeCount = pageRanges.length;
        for (int i = 0; i < pageRangeCount; i++) {
            final int start = pageRanges[i].getStart() + offset;
            final int end = pageRanges[i].getEnd() + offset;
            pageRanges[i] = new PageRange(start, end);
        }
    }

    /**
     * Gets the number of pages in a normalized range array.
     *
     * @param pageRanges Normalized page ranges.
     * @param layoutPageCount Page count after reported after layout pass.
     * @return The page count in the ranges.
     */
    public static int getNormalizedPageCount(PageRange[] pageRanges, int layoutPageCount) {
        int pageCount = 0;
        final int pageRangeCount = pageRanges.length;
        for (int i = 0; i < pageRangeCount; i++) {
            PageRange pageRange = pageRanges[i];
            if (PageRange.ALL_PAGES.equals(pageRange)) {
                return layoutPageCount;
            }
            pageCount += pageRange.getEnd() - pageRange.getStart() + 1;
        }
        return pageCount;
    }
}
