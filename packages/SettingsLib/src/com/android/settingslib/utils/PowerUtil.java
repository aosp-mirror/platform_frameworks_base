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
import android.icu.text.DateFormat;
import android.icu.text.MeasureFormat;
import android.icu.text.MeasureFormat.FormatWidth;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.android.settingslib.R;

import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Utility class for keeping power related strings consistent**/
public class PowerUtil {

    private static final long SEVEN_MINUTES_MILLIS = TimeUnit.MINUTES.toMillis(7);
    private static final long FIFTEEN_MINUTES_MILLIS = TimeUnit.MINUTES.toMillis(15);
    private static final long ONE_DAY_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final long TWO_DAYS_MILLIS = TimeUnit.DAYS.toMillis(2);
    private static final long ONE_HOUR_MILLIS = TimeUnit.HOURS.toMillis(1);

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
            } else if (drainTimeMs >= TWO_DAYS_MILLIS) {
                // just say more than two day if over 48 hours
                return getMoreThanTwoDaysString(context, percentageString);
            } else if (drainTimeMs >= ONE_DAY_MILLIS) {
                // show remaining days & hours if more than a day
                return getMoreThanOneDayString(context, drainTimeMs,
                        percentageString, basedOnUsage);
            } else {
                // show the time of day we think you'll run out
                return getRegularTimeRemainingString(context, drainTimeMs,
                        percentageString, basedOnUsage);
            }
        }
        return null;
    }

    /**
     * Method to produce a shortened string describing the remaining battery. Suitable for Quick
     * Settings and other areas where space is constrained.
     *
     * @param context context to fetch descriptions from
     * @param drainTimeMs The estimated time remaining before the phone dies in milliseconds.
     *
     * @return a properly formatted and localized short string describing how much time remains
     * before the battery runs out.
     */
    @Nullable
    public static String getBatteryRemainingShortStringFormatted(
            Context context, long drainTimeMs) {
        if (drainTimeMs <= 0) {
            return null;
        }

        if (drainTimeMs <= ONE_DAY_MILLIS) {
            return getRegularTimeRemainingShortString(context, drainTimeMs);
        } else {
            return getMoreThanOneDayShortString(context, drainTimeMs,
                R.string.power_remaining_duration_only_short);
        }
    }

    /**
     * This method produces the text used in Settings battery tip to describe the effect after
     * use the tip.
     *
     * @param context
     * @param drainTimeMs The estimated time remaining before the phone dies in milliseconds.
     * @return a properly formatted and localized string
     */
    public static String getBatteryTipStringFormatted(Context context, long drainTimeMs) {
        if (drainTimeMs <= 0) {
            return null;
        }
        if (drainTimeMs <= ONE_DAY_MILLIS) {
            return context.getString(R.string.power_suggestion_extend_battery,
                getDateTimeStringFromMs(context, drainTimeMs));
        } else {
            return getMoreThanOneDayShortString(context, drainTimeMs,
                R.string.power_remaining_only_more_than_subtext);
        }
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
                        timeString,
                        percentageString);

    }

    private static String getMoreThanOneDayString(Context context, long drainTimeMs,
            String percentageString, boolean basedOnUsage) {
        final long roundedTimeMs = roundTimeToNearestThreshold(drainTimeMs, ONE_HOUR_MILLIS);
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
            return context.getString(id, timeString, percentageString);
        }
    }

    private static String getMoreThanOneDayShortString(Context context, long drainTimeMs,
            int resId) {
        final long roundedTimeMs = roundTimeToNearestThreshold(drainTimeMs, ONE_HOUR_MILLIS);
        CharSequence timeString = StringUtil.formatElapsedTime(context, roundedTimeMs,
                false /* withSeconds */);

        return context.getString(resId, timeString);
    }

    private static String getMoreThanTwoDaysString(Context context, String percentageString) {
        final Locale currentLocale = context.getResources().getConfiguration().getLocales().get(0);
        final MeasureFormat frmt = MeasureFormat.getInstance(currentLocale, FormatWidth.SHORT);

        final Measure daysMeasure = new Measure(2, MeasureUnit.DAY);

        return TextUtils.isEmpty(percentageString)
                ? context.getString(R.string.power_remaining_only_more_than_subtext,
                        frmt.formatMeasures(daysMeasure))
                : context.getString(
                        R.string.power_remaining_more_than_subtext,
                        frmt.formatMeasures(daysMeasure),
                        percentageString);
    }

    private static String getRegularTimeRemainingString(Context context, long drainTimeMs,
            String percentageString, boolean basedOnUsage) {

        CharSequence timeString = getDateTimeStringFromMs(context, drainTimeMs);

        if (TextUtils.isEmpty(percentageString)) {
            int id = basedOnUsage
                    ? R.string.power_discharge_by_only_enhanced
                    : R.string.power_discharge_by_only;
            return context.getString(id, timeString);
        } else {
            int id = basedOnUsage
                    ? R.string.power_discharge_by_enhanced
                    : R.string.power_discharge_by;
            return context.getString(id, timeString, percentageString);
        }
    }

    private static CharSequence getDateTimeStringFromMs(Context context, long drainTimeMs) {
        // Get the time of day we think device will die rounded to the nearest 15 min.
        final long roundedTimeOfDayMs =
                roundTimeToNearestThreshold(
                        System.currentTimeMillis() + drainTimeMs,
                        FIFTEEN_MINUTES_MILLIS);

        // convert the time to a properly formatted string.
        String skeleton = android.text.format.DateFormat.getTimeFormatString(context);
        DateFormat fmt = DateFormat.getInstanceForSkeleton(skeleton);
        Date date = Date.from(Instant.ofEpochMilli(roundedTimeOfDayMs));
        return fmt.format(date);
    }

    private static String getRegularTimeRemainingShortString(Context context, long drainTimeMs) {
        // Get the time remaining rounded to the nearest 15 min
        final long roundedTimeMs = roundTimeToNearestThreshold(drainTimeMs, FIFTEEN_MINUTES_MILLIS);
        CharSequence timeString = StringUtil.formatElapsedTime(context, roundedTimeMs,
                false /* withSeconds */);

        return context.getString(R.string.power_remaining_duration_only_short, timeString);
    }

    public static long convertUsToMs(long timeUs) {
        return timeUs / 1000;
    }

    public static long convertMsToUs(long timeMs) {
        return timeMs * 1000;
    }

    /**
     * Rounds a time to the nearest multiple of the provided threshold. Note: This function takes
     * the absolute value of the inputs since it is only meant to be used for times, not general
     * purpose rounding.
     *
     * ex: roundTimeToNearestThreshold(41, 24) = 48
     * @param drainTime The amount to round
     * @param threshold The value to round to a multiple of
     * @return The rounded value as a long
     */
    public static long roundTimeToNearestThreshold(long drainTime, long threshold) {
        long time = Math.abs(drainTime);
        long multiple = Math.abs(threshold);
        final long remainder = time % multiple;
        if (remainder < multiple / 2) {
            return time - remainder;
        } else {
            return time - remainder + multiple;
        }
    }
}