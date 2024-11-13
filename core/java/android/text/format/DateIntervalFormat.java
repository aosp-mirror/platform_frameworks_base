/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.text.format;

import static android.text.format.DateUtils.FORMAT_SHOW_TIME;
import static android.text.format.DateUtils.FORMAT_UTC;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.icu.util.Calendar;
import android.icu.util.ULocale;
import android.util.LruCache;

import com.android.internal.annotations.VisibleForTesting;
import com.android.libcore.Flags;

import java.text.FieldPosition;
import java.util.TimeZone;

/**
 * A wrapper of {@link android.icu.text.DateIntervalFormat} used by {@link DateUtilsBridge}.
 *
 * @hide
 */
@VisibleForTesting(visibility = PACKAGE)
public final class DateIntervalFormat {

    private static final LruCache<String, android.icu.text.DateIntervalFormat> CACHED_FORMATTERS =
            new LruCache<>(8);

    private DateIntervalFormat() {
    }

    /**
     * Format a date range.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public static String formatDateRange(long startMs, long endMs, int flags, String olsonId) {
        if ((flags & FORMAT_UTC) != 0) {
            olsonId = "UTC";
        }
        // We create a java.util.TimeZone here to use libcore's data and libcore's olson ID /
        // pseudo-tz logic.
        TimeZone tz = (olsonId != null) ? TimeZone.getTimeZone(olsonId) : TimeZone.getDefault();
        android.icu.util.TimeZone icuTimeZone = DateUtilsBridge.icuTimeZone(tz);
        ULocale icuLocale = ULocale.getDefault();
        return formatDateRange(icuLocale, icuTimeZone, startMs, endMs, flags);
    }

    /**
     * Format a date range. This is our slightly more sensible internal API.
     * A truly reasonable replacement would take a skeleton instead of int flags.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public static String formatDateRange(ULocale icuLocale, android.icu.util.TimeZone icuTimeZone,
            long startMs, long endMs, int flags) {
        Calendar startCalendar = DateUtilsBridge.createIcuCalendar(icuTimeZone, icuLocale, startMs);
        Calendar endCalendar;
        if (startMs == endMs) {
            endCalendar = startCalendar;
        } else {
            endCalendar = DateUtilsBridge.createIcuCalendar(icuTimeZone, icuLocale, endMs);
        }

        // Special handling when the range ends at midnight:
        // - If we're not showing times, and the range is non-empty, we fudge the end date so we
        // don't count the day that's about to start.
        // - If we are showing times, and the range ends at exactly 00:00 of the day following
        // its start (which can be thought of as 24:00 the same day), we fudge the end date so we
        // don't show the dates --- unless the start is anything displayed as 00:00, in which case
        // we include both dates to disambiguate.
        // This is not the behavior of icu4j's DateIntervalFormat, but it's the required behavior
        // of Android's DateUtils.formatDateRange.
        if (isExactlyMidnight(endCalendar)) {
            boolean showTime = (flags & FORMAT_SHOW_TIME) == FORMAT_SHOW_TIME;
            boolean endsDayAfterStart = DateUtilsBridge.dayDistance(startCalendar, endCalendar)
                    == 1;
            if ((!showTime && startMs != endMs)
                    || (endsDayAfterStart
                    && !DateUtilsBridge.isDisplayMidnightUsingSkeleton(startCalendar))) {
                endCalendar.add(Calendar.DAY_OF_MONTH, -1);
            }
        }

        String skeleton = DateUtilsBridge.toSkeleton(startCalendar, endCalendar, flags);
        synchronized (CACHED_FORMATTERS) {
            android.icu.text.DateIntervalFormat formatter =
                    getFormatter(skeleton, icuLocale, icuTimeZone);
            return formatter.format(startCalendar, endCalendar, new StringBuffer(),
                    new FieldPosition(0)).toString();
        }
    }

    private static android.icu.text.DateIntervalFormat getFormatter(String skeleton, ULocale locale,
            android.icu.util.TimeZone icuTimeZone) {
        String key = skeleton + "\t" + locale + "\t" + icuTimeZone;
        android.icu.text.DateIntervalFormat formatter = CACHED_FORMATTERS.get(key);
        if (formatter != null) {
            return formatter;
        }
        formatter = android.icu.text.DateIntervalFormat.getInstance(skeleton, locale);
        formatter.setTimeZone(icuTimeZone);
        CACHED_FORMATTERS.put(key, formatter);
        return formatter;
    }

    private static boolean isExactlyMidnight(Calendar c) {
        return c.get(Calendar.HOUR_OF_DAY) == 0
                && c.get(Calendar.MINUTE) == 0
                && c.get(Calendar.SECOND) == 0
                && c.get(Calendar.MILLISECOND) == 0;
    }


    @VisibleForTesting(visibility = PACKAGE)
    public static boolean isLibcoreVFlagEnabled() {
        // Note that the Flags class is expected to be jarjar-ed in the build-time.
        // See go/repackage_flags
        // The full-qualified name should be like
        // com.android.internal.hidden_from_bootclasspath.com.android.libcore.Flags
        return Flags.vApis();
    }
}
