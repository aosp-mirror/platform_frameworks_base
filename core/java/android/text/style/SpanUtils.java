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

package android.text.style;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.Spanned;
import android.util.LongArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @hide
 */
public class SpanUtils {
    private SpanUtils() {}  // Do not instantiate

    /**
     * Toggle the bold state of the given range.
     *
     * If there is at least one character is not bold in the given range, make the entire region to
     * be bold. If all characters of the given range is already bolded, this method removes bold
     * style from the given selection.
     *
     * @param spannable a spannable string
     * @param min minimum inclusive index of the selection.
     * @param max maximum exclusive index of the selection.
     * @return true if the selected region is toggled.
     */
    public static boolean toggleBold(@NonNull Spannable spannable,
            @IntRange(from = 0) int min, @IntRange(from = 0) int max) {

        if (min == max) {
            return false;
        }

        final StyleSpan[] boldSpans = spannable.getSpans(min, max, StyleSpan.class);
        final ArrayList<StyleSpan> filteredBoldSpans = new ArrayList<>();
        for (StyleSpan span : boldSpans) {
            if ((span.getStyle() & Typeface.BOLD) == Typeface.BOLD) {
                filteredBoldSpans.add(span);
            }
        }

        if (!isCovered(spannable, filteredBoldSpans, min, max)) {
            // At least one character doesn't have bold style. Making given region bold.
            spannable.setSpan(
                    new StyleSpan(Typeface.BOLD), min, max, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            return true;
        }

        // Span covers the entire selection. Removing spans from tha region.
        for (int si = 0; si < filteredBoldSpans.size(); ++si) {
            final StyleSpan span = filteredBoldSpans.get(si);
            final int start = spannable.getSpanStart(span);
            final int end = spannable.getSpanEnd(span);
            final int flag = spannable.getSpanFlags(span);

            // If BOLD_ITALIC style is attached, need to set ITALIC span to the subtracted range.
            final boolean needItalicSpan = (span.getStyle() & Typeface.ITALIC) == Typeface.ITALIC;

            if (start < min) {
                if (end > max) {
                    // selection: ------------|===================|----------------
                    //      span:     <-------------------------------->
                    //    result:     <------->                   <---->
                    spannable.setSpan(span, start, min, flag);
                    spannable.setSpan(new StyleSpan(span.getStyle()), max, end, flag);
                    if (needItalicSpan) {
                        spannable.setSpan(new StyleSpan(Typeface.ITALIC), min, max, flag);
                    }
                } else {
                    // selection: ------------|===================|----------------
                    //      span:     <----------->
                    //    result:     <------->
                    spannable.setSpan(span, start, min, flag);
                    if (needItalicSpan) {
                        spannable.setSpan(new StyleSpan(Typeface.ITALIC), min, end, flag);
                    }
                }
            } else {
                if (end > max) {
                    // selection: ------------|===================|----------------
                    //      span:                     <------------------------>
                    //    result:                                 <------------>
                    spannable.setSpan(span, max, end, flag);
                    if (needItalicSpan) {
                        spannable.setSpan(new StyleSpan(Typeface.ITALIC), max, end, flag);
                    }
                } else {
                    // selection: ------------|===================|----------------
                    //      span:                 <----------->
                    //    result:
                    spannable.removeSpan(span);
                    if (needItalicSpan) {
                        spannable.setSpan(new StyleSpan(Typeface.ITALIC), start, end, flag);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Toggle the italic state of the given range.
     *
     * If there is at least one character is not italic in the given range, make the entire region
     * to be italic. If all characters of the given range is already italic, this method removes
     * italic style from the given selection.
     *
     * @param spannable a spannable string
     * @param min minimum inclusive index of the selection.
     * @param max maximum exclusive index of the selection.
     * @return true if the selected region is toggled.
     */
    public static boolean toggleItalic(@NonNull Spannable spannable,
            @IntRange(from = 0) int min, @IntRange(from = 0) int max) {

        if (min == max) {
            return false;
        }

        final StyleSpan[] boldSpans = spannable.getSpans(min, max, StyleSpan.class);
        final ArrayList<StyleSpan> filteredBoldSpans = new ArrayList<>();
        for (StyleSpan span : boldSpans) {
            if ((span.getStyle() & Typeface.ITALIC) == Typeface.ITALIC) {
                filteredBoldSpans.add(span);
            }
        }

        if (!isCovered(spannable, filteredBoldSpans, min, max)) {
            // At least one character doesn't have italic style. Making given region italic.
            spannable.setSpan(
                    new StyleSpan(Typeface.ITALIC), min, max, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            return true;
        }

        // Span covers the entire selection. Removing spans from tha region.
        for (int si = 0; si < filteredBoldSpans.size(); ++si) {
            final StyleSpan span = filteredBoldSpans.get(si);
            final int start = spannable.getSpanStart(span);
            final int end = spannable.getSpanEnd(span);
            final int flag = spannable.getSpanFlags(span);

            // If BOLD_ITALIC style is attached, need to set BOLD span to the subtracted range.
            final boolean needBoldSpan = (span.getStyle() & Typeface.BOLD) == Typeface.BOLD;

            if (start < min) {
                if (end > max) {
                    // selection: ------------|===================|----------------
                    //      span:     <-------------------------------->
                    //    result:     <------->                   <---->
                    spannable.setSpan(span, start, min, flag);
                    spannable.setSpan(new StyleSpan(span.getStyle()), max, end, flag);
                    if (needBoldSpan) {
                        spannable.setSpan(new StyleSpan(Typeface.BOLD), min, max, flag);
                    }
                } else {
                    // selection: ------------|===================|----------------
                    //      span:     <----------->
                    //    result:     <------->
                    spannable.setSpan(span, start, min, flag);
                    if (needBoldSpan) {
                        spannable.setSpan(new StyleSpan(Typeface.BOLD), min, end, flag);
                    }
                }
            } else {
                if (end > max) {
                    // selection: ------------|===================|----------------
                    //      span:                     <------------------------>
                    //    result:                                 <------------>
                    spannable.setSpan(span, max, end, flag);
                    if (needBoldSpan) {
                        spannable.setSpan(new StyleSpan(Typeface.BOLD), max, end, flag);
                    }
                } else {
                    // selection: ------------|===================|----------------
                    //      span:                 <----------->
                    //    result:
                    spannable.removeSpan(span);
                    if (needBoldSpan) {
                        spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, flag);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Toggle the underline state of the given range.
     *
     * If there is at least one character is not underlined in the given range, make the entire
     * region to underlined. If all characters of the given range is already underlined, this
     * method removes underline from the given selection.
     *
     * @param spannable a spannable string
     * @param min minimum inclusive index of the selection.
     * @param max maximum exclusive index of the selection.
     * @return true if the selected region is toggled.
     */
    public static boolean toggleUnderline(@NonNull Spannable spannable,
            @IntRange(from = 0) int min, @IntRange(from = 0) int max) {

        if (min == max) {
            return false;
        }

        final List<UnderlineSpan> spans =
                Arrays.asList(spannable.getSpans(min, max, UnderlineSpan.class));

        if (!isCovered(spannable, spans, min, max)) {
            // At least one character doesn't have underline style. Making given region underline.
            spannable.setSpan(new UnderlineSpan(), min, max, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            return true;
        }
        // Span covers the entire selection. Removing spans from tha region.
        for (int si = 0; si < spans.size(); ++si) {
            final UnderlineSpan span = spans.get(si);
            final int start = spannable.getSpanStart(span);
            final int end = spannable.getSpanEnd(span);
            final int flag = spannable.getSpanFlags(span);

            if (start < min) {
                if (end > max) {
                    // selection: ------------|===================|----------------
                    //      span:     <-------------------------------->
                    //    result:     <------->                   <---->
                    spannable.setSpan(span, start, min, flag);
                    spannable.setSpan(new UnderlineSpan(), max, end, flag);
                } else {
                    // selection: ------------|===================|----------------
                    //      span:     <----------->
                    //    result:     <------->
                    spannable.setSpan(span, start, min, flag);
                }
            } else {
                if (end > max) {
                    // selection: ------------|===================|----------------
                    //      span:                     <------------------------>
                    //    result:                                 <------------>
                    spannable.setSpan(span, max, end, flag);
                } else {
                    // selection: ------------|===================|----------------
                    //      span:                 <----------->
                    //    result:
                    spannable.removeSpan(span);
                }
            }
        }
        return true;
    }

    private static long pack(int from, int to) {
        return ((long) from) << 32 | (long) to;
    }

    private static int min(long packed) {
        return (int) (packed >> 32);
    }

    private static int max(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }

    private static boolean hasIntersection(int aMin, int aMax, int bMin, int bMax) {
        return aMin < bMax && bMin < aMax;
    }

    private static long intersection(int aMin, int aMax, int bMin, int bMax) {
        return pack(Math.max(aMin, bMin), Math.min(aMax, bMax));
    }

    private static <T> boolean isCovered(@NonNull Spannable spannable, @NonNull List<T> spans,
            @IntRange(from = 0) int min, @IntRange(from = 0) int max) {

        if (min == max) {
            return false;
        }

        LongArray uncoveredRanges = new LongArray();
        LongArray nextUncoveredRanges = new LongArray();

        uncoveredRanges.add(pack(min, max));

        for (int si = 0; si < spans.size(); ++si) {
            final T span = spans.get(si);
            final int start = spannable.getSpanStart(span);
            final int end = spannable.getSpanEnd(span);

            for (int i = 0; i < uncoveredRanges.size(); ++i) {
                final long packed = uncoveredRanges.get(i);
                final int uncoveredStart = min(packed);
                final int uncoveredEnd = max(packed);

                if (!hasIntersection(start, end, uncoveredStart, uncoveredEnd)) {
                    // This span doesn't affect this uncovered range. Try next span.
                    nextUncoveredRanges.add(packed);
                } else {
                    // This span has an intersection with uncovered range. Update the uncovered
                    // range.
                    long intersectionPack = intersection(start, end, uncoveredStart, uncoveredEnd);
                    int intersectStart = min(intersectionPack);
                    int intersectEnd = max(intersectionPack);

                    // Uncovered Range           : ----------|=======================|-------------
                    //    Intersection           :                 <---------->
                    // Remaining uncovered ranges: ----------|=====|----------|======|-------------
                    if (uncoveredStart != intersectStart) {
                        // There is still uncovered area on the left.
                        nextUncoveredRanges.add(pack(uncoveredStart, intersectStart));
                    }
                    if (intersectEnd != uncoveredEnd) {
                        // There is still uncovered area on the right.
                        nextUncoveredRanges.add(pack(intersectEnd, uncoveredEnd));
                    }
                }
            }

            if (nextUncoveredRanges.size() == 0) {
                return true;
            }

            // Swap the uncoveredRanges and nextUncoveredRanges and clear the next one.
            final LongArray tmp = nextUncoveredRanges;
            nextUncoveredRanges = uncoveredRanges;
            uncoveredRanges = tmp;
            nextUncoveredRanges.clear();
        }

        return false;
    }
}
