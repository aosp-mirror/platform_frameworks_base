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
import android.print.PrintDocumentInfo;
import android.util.Pair;

import java.util.ArrayList;
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
     * Gets whether page ranges contains a given page.
     *
     * @param pageRanges The page ranges.
     * @param pageIndex The page for which to check.
     * @return Whether the page is within the ranges.
     */
    public static boolean contains(PageRange[] pageRanges, int pageIndex) {
        final int rangeCount = pageRanges.length;
        for (int i = 0; i < rangeCount; i++) {
            PageRange pageRange = pageRanges[i];
            if (pageRange.contains(pageIndex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether one page range array contains another one.
     *
     * @param ourRanges The container page ranges.
     * @param otherRanges The contained page ranges.
     * @param pageCount The total number of pages.
     * @return Whether the container page ranges contains the contained ones.
     */
    public static boolean contains(PageRange[] ourRanges, PageRange[] otherRanges, int pageCount) {
        if (ourRanges == null || otherRanges == null) {
            return false;
        }

        if (Arrays.equals(ourRanges, ALL_PAGES_RANGE)) {
            return true;
        }

        if (Arrays.equals(otherRanges, ALL_PAGES_RANGE)) {
            otherRanges[0] = new PageRange(0, pageCount - 1);
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
        return (otherRangeIdx >= otherRangeCount);
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
            PageRange currentRange = pageRanges[i];
            PageRange nextRange = pageRanges[i + 1];
            if (currentRange.getEnd() + 1 >= nextRange.getStart()) {
                pageRanges[i] = null;
                pageRanges[i + 1] = new PageRange(currentRange.getStart(),
                        Math.max(currentRange.getEnd(), nextRange.getEnd()));
            } else {
                newRangeCount++;
            }
        }

        if (newRangeCount == oldRangeCount) {
            return pageRanges;
        }

        int normalRangeIndex = 0;
        PageRange[] normalRanges = new PageRange[newRangeCount];
        for (int i = 0; i < oldRangeCount; i++) {
            PageRange normalRange = pageRanges[i];
            if (normalRange != null) {
                normalRanges[normalRangeIndex] = normalRange;
                normalRangeIndex++;
            }
        }

        return normalRanges;
    }

    /**
     * Return the next position after {@code pos} that is not a space character.
     *
     * @param s   The string to parse
     * @param pos The starting position
     *
     * @return The position of the first space character
     */
    private static int readWhiteSpace(CharSequence s, int pos) {
        while (pos < s.length() && s.charAt(pos) == ' ') {
            pos++;
        }

        return pos;
    }

    /**
     * Read a number from a string at a certain position.
     *
     * @param s   The string to parse
     * @param pos The starting position
     *
     * @return The position after the number + the number read or null if the number was not found
     */
    private static Pair<Integer, Integer> readNumber(CharSequence s, int pos) {
        Integer result = 0;
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
            // Number cannot start with 0
            if (result == 0 && s.charAt(pos) == '0') {
                break;
            }
            result = result * 10 + (s.charAt(pos) - '0');
            // Abort on overflow
            if (result < 0) {
                break;
            }
            pos++;
        }

        // 0 is not a valid page number
        if (result == 0) {
            return new Pair<>(pos, null);
        } else {
            return new Pair<>(pos, result);
        }
    }

    /**
     * Read a single character from a string at a certain position.
     *
     * @param s            The string to parse
     * @param pos          The starting position
     * @param expectedChar The character to read
     *
     * @return The position after the character + the character read or null if the character was
     *         not found
     */
    private static Pair<Integer, Character> readChar(CharSequence s, int pos, char expectedChar) {
        if (pos < s.length() && s.charAt(pos) == expectedChar) {
            return new Pair<>(pos + 1, expectedChar);
        } else {
            return new Pair<>(pos, null);
        }
    }

    /**
     * Read a page range character from a string at a certain position.
     *
     * @param s             The string to parse
     * @param pos           The starting position
     * @param maxPageNumber The highest page number to accept.
     *
     * @return The position after the page range + the page range read or null if the page range was
     *         not found
     */
    private static Pair<Integer, PageRange> readRange(CharSequence s, int pos, int maxPageNumber) {
        Pair<Integer, Integer> retInt;
        Pair<Integer, Character> retChar;

        Character comma;
        if (pos == 0) {
            // When we reading the first range, we do not want to have a comma
            comma = ',';
        } else {
            retChar = readChar(s, pos, ',');
            pos = retChar.first;
            comma = retChar.second;
        }

        pos = readWhiteSpace(s, pos);

        retInt = readNumber(s, pos);
        pos = retInt.first;
        Integer start = retInt.second;

        pos = readWhiteSpace(s, pos);

        retChar = readChar(s, pos, '-');
        pos = retChar.first;
        Character separator = retChar.second;

        pos = readWhiteSpace(s, pos);

        retInt = readNumber(s, pos);
        pos = retInt.first;
        Integer end = retInt.second;

        pos = readWhiteSpace(s, pos);

        if (comma != null &&
                // range, maybe unbounded
                ((separator != null && (start != null || end != null)) ||
                        // single page
                        (separator == null && start != null && end == null))) {
            if (start == null) {
                start = 1;
            }

            if (end == null) {
                if (separator == null) {
                    end = start;
                } else {
                    end = maxPageNumber;
                }
            }

            if (start <= end && start >= 1 && end <= maxPageNumber) {
                return new Pair<>(pos, new PageRange(start - 1, end - 1));
            }
        }

        return new Pair<>(pos, null);
    }

    /**
     * Parse a string into an array of page ranges.
     *
     * @param s             The string to parse
     * @param maxPageNumber The highest page number to accept.
     *
     * @return The parsed ranges or null if the string could not be parsed.
     */
    public static PageRange[] parsePageRanges(CharSequence s, int maxPageNumber) {
        ArrayList<PageRange> ranges = new ArrayList<>();

        int pos = 0;
        while (pos < s.length()) {
            Pair<Integer, PageRange> retRange = readRange(s, pos, maxPageNumber);

            if (retRange.second == null) {
                ranges.clear();
                break;
            }

            ranges.add(retRange.second);
            pos = retRange.first;
        }

        return PageRangeUtils.normalize(ranges.toArray(new PageRange[ranges.size()]));
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
        if (pageRanges != null) {
            final int pageRangeCount = pageRanges.length;
            for (int i = 0; i < pageRangeCount; i++) {
                PageRange pageRange = pageRanges[i];
                if (PageRange.ALL_PAGES.equals(pageRange)) {
                    return layoutPageCount;
                }
                pageCount += pageRange.getSize();
            }
        }
        return pageCount;
    }

    public static PageRange asAbsoluteRange(PageRange pageRange, int pageCount) {
        if (PageRange.ALL_PAGES.equals(pageRange)) {
            return new PageRange(0, pageCount - 1);
        }
        return pageRange;
    }

    public static boolean isAllPages(PageRange[] pageRanges) {
        final int pageRangeCount = pageRanges.length;
        for (int i = 0; i < pageRangeCount; i++) {
            PageRange pageRange = pageRanges[i];
            if (isAllPages(pageRange)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAllPages(PageRange pageRange) {
        return PageRange.ALL_PAGES.equals(pageRange);
    }

    public static boolean isAllPages(PageRange[] pageRanges, int pageCount) {
        final int pageRangeCount = pageRanges.length;
        for (int i = 0; i < pageRangeCount; i++) {
            PageRange pageRange = pageRanges[i];
            if (isAllPages(pageRange, pageCount)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAllPages(PageRange pageRanges, int pageCount) {
        return pageRanges.getStart() == 0 && pageRanges.getEnd() == pageCount - 1;
    }

    public static PageRange[] computePrintedPages(PageRange[] requestedPages,
            PageRange[] writtenPages, int pageCount) {
        // Adjust the print job pages based on what was requested and written.
        // The cases are ordered in the most expected to the least expected
        // with a special case first where the app does not know the page count
        // so we ask for all to be written.
        if (Arrays.equals(requestedPages, ALL_PAGES_RANGE)
                && pageCount == PrintDocumentInfo.PAGE_COUNT_UNKNOWN) {
            return ALL_PAGES_RANGE;
        } else if (Arrays.equals(writtenPages, requestedPages)) {
            // We got a document with exactly the pages we wanted. Hence,
            // the printer has to print all pages in the data.
            return ALL_PAGES_RANGE;
        } else if (Arrays.equals(writtenPages, ALL_PAGES_RANGE)) {
            // We requested specific pages but got all of them. Hence,
            // the printer has to print only the requested pages.
            return requestedPages;
        } else if (PageRangeUtils.contains(writtenPages, requestedPages, pageCount)) {
            // We requested specific pages and got more but not all pages.
            // Hence, we have to offset appropriately the printed pages to
            // be based off the start of the written ones instead of zero.
            // The written pages are always non-null and not empty.
            final int offset = -writtenPages[0].getStart();
            PageRangeUtils.offset(requestedPages, offset);
            return requestedPages;
        } else if (Arrays.equals(requestedPages, ALL_PAGES_RANGE)
                && isAllPages(writtenPages, pageCount)) {
            // We requested all pages via the special constant and got all
            // of them as an explicit enumeration. Hence, the printer has
            // to print only the requested pages.
            return ALL_PAGES_RANGE;
        }

        return null;
    }
}
