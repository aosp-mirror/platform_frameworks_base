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

package com.android.settingslib.utils;

import android.content.Context;
import android.icu.text.MeasureFormat;
import android.icu.text.MeasureFormat.FormatWidth;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import com.android.settingslib.R;
import com.android.settingslib.utils.StringUtil;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Utility class for keeping power related strings consistent**/
public class PowerUtil {
    private static final long SEVEN_MINUTES_MILLIS = TimeUnit.MINUTES.toMillis(7);
    private static final long FIFTEEN_MINUTES_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final long ONE_DAY_MILLIS = TimeUnit.DAYS.toMillis(1);

    /**
     * This method produces the text used in various places throughout the system to describe the
     * remaining battery life of the phone in a consistent manner.
     *
     * @param context
     * @param drainTimeMs The estimated time remaining before the phone dies in milliseconds.
     * @param percentageString An optional percentage of battery remaining string.
     * @param basedOnUsage Whether this estimate is based on usage or simple extrapolation.
     * @return a properly formatted and localized string describing how much time remains
     * before the battery runs out.
     */
    public static String getBatteryRemainingStringFormatted(Context context, long drainTimeMs,
            @Nullable String percentageString, boolean basedOnUsage) {
        if (drainTimeMs > 0) {
            if (drainTimeMs <= SEVEN_MINUTES_MILLIS) {
                // show a imminent shutdown warning if less than 7 minutes remain
                return getShutdownImminentString(context, percentageString);
            } else if (drainTimeMs <= FIFTEEN_MINUTES_MILLIS) {
                // show a less than 15 min remaining warning if appropriate
                CharSequence timeString = StringUtil.formatElapsedTime(context,
                        FIFTEEN_MINUTES_MILLIS,
                        false /* withSeconds */);
                return getUnderFifteenString(context, timeString, percentageString);
            } else if (drainTimeMs >= ONE_DAY_MILLIS) {
                // just say more than one day if over 24 hours
                return getMoreThanOneDayString(context, percentageString);
            } else {
                // show a regular time remaining string
                return getRegularTimeRemainingString(context, drainTimeMs,
                        percentageString, basedOnUsage);
            }
        }
        return null;
    }

    private static String getShutdownImminentString(Context context, String percentageString) {
        return TextUtils.isEmpty(percentageString)
                ? context.getString(R.string.power_remaining_duration_only_shutdown_imminent)
                : context.getString(
                        R.string.power_remaining_duration_shutdown_imminent,
                        percentageString);
    }

    private static String getUnderFifteenString(Context context, CharSequence timeString,
            String percentageString) {
        return TextUtils.isEmpty(percentageString)
                ? context.getString(R.string.power_remaining_less_than_duration_only, timeString)
                : context.getString(
                        R.string.power_remaining_less_than_duration,
                        percentageString,
                        timeString);

    }

    private static String getMoreThanOneDayString(Context context, String percentageString) {
        final Locale currentLocale = context.getResources().getConfiguration().getLocales().get(0);
        final MeasureFormat frmt = MeasureFormat.getInstance(currentLocale, FormatWidth.SHORT);

        final Measure daysMeasure = new Measure(1, MeasureUnit.DAY);

        return TextUtils.isEmpty(percentageString)
                ? context.getString(R.string.power_remaining_only_more_than_subtext,
                        frmt.formatMeasures(daysMeasure))
                : context.getString(
                        R.string.power_remaining_more_than_subtext,
                        percentageString,
                        frmt.formatMeasures(daysMeasure));
    }

    private static String getRegularTimeRemainingString(Context context, long drainTimeMs,
            String percentageString, boolean basedOnUsage) {
        // round to the nearest 15 min to not appear oversly precise
        final long roundedTimeMs = roundToNearestThreshold(drainTimeMs,
                FIFTEEN_MINUTES_MILLIS);
        CharSequence timeString = StringUtil.formatElapsedTime(context,
                roundedTimeMs,
                false /* withSeconds */);
        if (TextUtils.isEmpty(percentageString)) {
            int id = basedOnUsage
                    ? R.string.power_remaining_duration_only_enhanced
                    : R.string.power_remaining_duration_only;
            return context.getString(id, timeString);
        } else {
            int id = basedOnUsage
                    ? R.string.power_discharging_duration_enhanced
                    : R.string.power_discharging_duration;
            return context.getString(id, percentageString, timeString);
        }
    }

    public static long convertUsToMs(long timeUs) {
        return timeUs / 1000;
    }

    public static long convertMsToUs(long timeMs) {
        return timeMs * 1000;
    }

    private static long roundToNearestThreshold(long drainTime, long threshold) {
        final long remainder = drainTime % threshold;
        if (remainder < threshold / 2) {
            return drainTime - remainder;
        } else {
            return drainTime - remainder + threshold;
        }
    }
}
