/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;
import static android.text.format.DateUtils.YEAR_IN_MILLIS;

import android.annotation.IntDef;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.PluralsMessageFormatter;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.InspectableProperty;
import android.widget.RemoteViews.RemoteView;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.DateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.JulianFields;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

//
// TODO
// - listen for the next threshold time to update the view.
// - listen for date format pref changed
// - put the AM/PM in a smaller font
//

/**
 * Displays a given time in a convenient human-readable foramt.
 *
 * @hide
 */
@RemoteView
public class DateTimeView extends TextView {
    private static final int SHOW_TIME = 0;
    private static final int SHOW_MONTH_DAY_YEAR = 1;

    /** @hide */
    @IntDef(value = {UNIT_DISPLAY_LENGTH_SHORTEST, UNIT_DISPLAY_LENGTH_MEDIUM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface UnitDisplayLength {}
    public static final int UNIT_DISPLAY_LENGTH_SHORTEST = 0;
    public static final int UNIT_DISPLAY_LENGTH_MEDIUM = 1;

    /** @hide */
    @IntDef(flag = true, value = {DISAMBIGUATION_TEXT_PAST, DISAMBIGUATION_TEXT_FUTURE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisambiguationTextMask {}
    public static final int DISAMBIGUATION_TEXT_PAST = 0x01;
    public static final int DISAMBIGUATION_TEXT_FUTURE = 0x02;

    private final boolean mCanUseRelativeTimeDisplayConfigs =
            android.view.flags.Flags.dateTimeViewRelativeTimeDisplayConfigs();

    private long mTimeMillis;
    // The LocalDateTime equivalent of mTimeMillis but truncated to minute, i.e. no seconds / nanos.
    private LocalDateTime mLocalTime;

    int mLastDisplay = -1;
    DateFormat mLastFormat;

    private long mUpdateTimeMillis;
    private static final ThreadLocal<ReceiverInfo> sReceiverInfo = new ThreadLocal<ReceiverInfo>();
    private String mNowText;
    private boolean mShowRelativeTime;
    private int mRelativeTimeDisambiguationTextMask;
    private int mRelativeTimeUnitDisplayLength = UNIT_DISPLAY_LENGTH_SHORTEST;

    public DateTimeView(Context context) {
        this(context, null);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public DateTimeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.DateTimeView, 0, 0);

        setShowRelativeTime(a.getBoolean(R.styleable.DateTimeView_showRelative, false));
        if (mCanUseRelativeTimeDisplayConfigs) {
            setRelativeTimeDisambiguationTextMask(
                    a.getInt(
                            R.styleable.DateTimeView_relativeTimeDisambiguationText,
                            // The original implementation showed disambiguation text for future
                            // times only, so continue with that default.
                            DISAMBIGUATION_TEXT_FUTURE));
            setRelativeTimeUnitDisplayLength(
                    a.getInt(
                            R.styleable.DateTimeView_relativeTimeUnitDisplayLength,
                            UNIT_DISPLAY_LENGTH_SHORTEST));
        }

        a.recycle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ReceiverInfo ri = sReceiverInfo.get();
        if (ri == null) {
            ri = new ReceiverInfo();
            sReceiverInfo.set(ri);
        }
        ri.addView(this);
        // The view may not be added to the view hierarchy immediately right after setTime()
        // is called which means it won't get any update from intents before being added.
        // In such case, the view might show the incorrect relative time after being added to the
        // view hierarchy until the next update intent comes.
        // So we update the time here if mShowRelativeTime is enabled to prevent this case.
        if (mShowRelativeTime) {
            update();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        final ReceiverInfo ri = sReceiverInfo.get();
        if (ri != null) {
            ri.removeView(this);
        }
    }

    @android.view.RemotableViewMethod
    @UnsupportedAppUsage
    public void setTime(long timeMillis) {
        mTimeMillis = timeMillis;
        LocalDateTime dateTime = toLocalDateTime(timeMillis, ZoneId.systemDefault());
        mLocalTime = dateTime.withSecond(0);
        update();
    }

    @android.view.RemotableViewMethod
    public void setShowRelativeTime(boolean showRelativeTime) {
        mShowRelativeTime = showRelativeTime;
        updateNowText();
        update();
    }

    /** See {@link R.styleable.DateTimeView_relativeTimeDisambiguationText}. */
    @android.view.RemotableViewMethod
    public void setRelativeTimeDisambiguationTextMask(
            @DisambiguationTextMask int disambiguationTextMask) {
        if (!mCanUseRelativeTimeDisplayConfigs) {
            return;
        }
        mRelativeTimeDisambiguationTextMask = disambiguationTextMask;
        updateNowText();
        update();
    }

    /** See {@link R.styleable.DateTimeView_relativeTimeUnitDisplayLength}. */
    @android.view.RemotableViewMethod
    public void setRelativeTimeUnitDisplayLength(@UnitDisplayLength int unitDisplayLength) {
        if (!mCanUseRelativeTimeDisplayConfigs) {
            return;
        }
        mRelativeTimeUnitDisplayLength = unitDisplayLength;
        updateNowText();
        update();
    }

    /**
     * Returns whether this view shows relative time
     *
     * @return True if it shows relative time, false otherwise
     */
    @InspectableProperty(name = "showReleative", hasAttributeId = false)
    public boolean isShowRelativeTime() {
        return mShowRelativeTime;
    }

    @Override
    @android.view.RemotableViewMethod
    public void setVisibility(@Visibility int visibility) {
        boolean gotVisible = visibility != GONE && getVisibility() == GONE;
        super.setVisibility(visibility);
        if (gotVisible) {
            update();
        }
    }

    @UnsupportedAppUsage
    void update() {
        if (mLocalTime == null || getVisibility() == GONE) {
            return;
        }
        if (mShowRelativeTime) {
            updateRelativeTime();
            return;
        }

        int display;
        ZoneId zoneId = ZoneId.systemDefault();

        // localTime is the local time for mTimeMillis but at zero seconds past the minute.
        LocalDateTime localTime = mLocalTime;
        LocalDateTime localStartOfDay =
                LocalDateTime.of(localTime.toLocalDate(), LocalTime.MIDNIGHT);
        LocalDateTime localTomorrowStartOfDay = localStartOfDay.plusDays(1);
        // now is current local time but at zero seconds past the minute.
        LocalDateTime localNow = LocalDateTime.now(zoneId).withSecond(0);

        long twelveHoursBefore = toEpochMillis(localTime.minusHours(12), zoneId);
        long twelveHoursAfter = toEpochMillis(localTime.plusHours(12), zoneId);
        long midnightBefore = toEpochMillis(localStartOfDay, zoneId);
        long midnightAfter = toEpochMillis(localTomorrowStartOfDay, zoneId);
        long time = toEpochMillis(localTime, zoneId);
        long now = toEpochMillis(localNow, zoneId);

        // Choose the display mode
        choose_display: {
            if ((now >= midnightBefore && now < midnightAfter)
                    || (now >= twelveHoursBefore && now < twelveHoursAfter)) {
                display = SHOW_TIME;
                break choose_display;
            }
            // Else, show month day and year.
            display = SHOW_MONTH_DAY_YEAR;
            break choose_display;
        }

        // Choose the format
        DateFormat format;
        if (display == mLastDisplay && mLastFormat != null) {
            // use cached format
            format = mLastFormat;
        } else {
            switch (display) {
                case SHOW_TIME:
                    format = getTimeFormat();
                    break;
                case SHOW_MONTH_DAY_YEAR:
                    format = DateFormat.getDateInstance(DateFormat.SHORT);
                    break;
                default:
                    throw new RuntimeException("unknown display value: " + display);
            }
            mLastFormat = format;
        }

        // Set the text
        String text = format.format(new Date(time));
        maybeSetText(text);

        // Schedule the next update
        if (display == SHOW_TIME) {
            // Currently showing the time, update at the later of twelve hours after or midnight.
            mUpdateTimeMillis = twelveHoursAfter > midnightAfter ? twelveHoursAfter : midnightAfter;
        } else {
            // Currently showing the date
            if (mTimeMillis < now) {
                // If the time is in the past, don't schedule an update
                mUpdateTimeMillis = 0;
            } else {
                // If hte time is in the future, schedule one at the earlier of twelve hours
                // before or midnight before.
                mUpdateTimeMillis = twelveHoursBefore < midnightBefore
                        ? twelveHoursBefore : midnightBefore;
            }
        }
    }

    private void updateRelativeTime() {
        long now = System.currentTimeMillis();
        long duration = Math.abs(now - mTimeMillis);
        int count;
        long millisIncrease;
        boolean past = (now >= mTimeMillis);
        String result;
        if (duration < MINUTE_IN_MILLIS) {
            maybeSetText(mNowText);
            mUpdateTimeMillis = mTimeMillis + MINUTE_IN_MILLIS + 1;
            return;
        } else if (duration < HOUR_IN_MILLIS) {
            count = (int)(duration / MINUTE_IN_MILLIS);
            result = getContext().getResources().getString(getMinutesStringId(past), count);
            millisIncrease = MINUTE_IN_MILLIS;
        } else if (duration < DAY_IN_MILLIS) {
            count = (int)(duration / HOUR_IN_MILLIS);
            result = getContext().getResources().getString(getHoursStringId(past), count);
            millisIncrease = HOUR_IN_MILLIS;
        } else if (duration < YEAR_IN_MILLIS) {
            // In weird cases it can become 0 because of daylight savings
            LocalDateTime localDateTime = mLocalTime;
            ZoneId zoneId = ZoneId.systemDefault();
            LocalDateTime localNow = toLocalDateTime(now, zoneId);

            count = Math.max(Math.abs(dayDistance(localDateTime, localNow)), 1);
            result = getContext().getResources().getString(getDaysStringId(past), count);
            if (past || count != 1) {
                mUpdateTimeMillis = computeNextMidnight(localNow, zoneId);
                millisIncrease = -1;
            } else {
                millisIncrease = DAY_IN_MILLIS;
            }

        } else {
            count = (int)(duration / YEAR_IN_MILLIS);
            result = getContext().getResources().getString(getYearsStringId(past), count);
            millisIncrease = YEAR_IN_MILLIS;
        }
        if (millisIncrease != -1) {
            if (past) {
                mUpdateTimeMillis = mTimeMillis + millisIncrease * (count + 1) + 1;
            } else {
                mUpdateTimeMillis = mTimeMillis - millisIncrease * count + 1;
            }
        }
        maybeSetText(result);
    }

    private int getMinutesStringId(boolean past) {
        if (!mCanUseRelativeTimeDisplayConfigs) {
            return past
                    ? com.android.internal.R.string.duration_minutes_shortest
                    : com.android.internal.R.string.duration_minutes_shortest_future;
        }

        if (mRelativeTimeUnitDisplayLength == UNIT_DISPLAY_LENGTH_SHORTEST) {
            if (past && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_PAST) != 0) {
                // "1m ago"
                return com.android.internal.R.string.duration_minutes_shortest_past;
            } else if (!past
                    && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_FUTURE) != 0) {
                // "in 1m"
                return com.android.internal.R.string.duration_minutes_shortest_future;
            } else {
                // "1m"
                return com.android.internal.R.string.duration_minutes_shortest;
            }
        } else { // UNIT_DISPLAY_LENGTH_MEDIUM
            if (past && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_PAST) != 0) {
                // "1min ago"
                return com.android.internal.R.string.duration_minutes_medium_past;
            } else if (!past
                    && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_FUTURE) != 0) {
                // "in 1min"
                return com.android.internal.R.string.duration_minutes_medium_future;
            } else {
                // "1min"
                return com.android.internal.R.string.duration_minutes_medium;
            }
        }
    }

    private int getHoursStringId(boolean past) {
        if (!mCanUseRelativeTimeDisplayConfigs) {
            return past
                    ? com.android.internal.R.string.duration_hours_shortest
                    : com.android.internal.R.string.duration_hours_shortest_future;
        }
        if (mRelativeTimeUnitDisplayLength == UNIT_DISPLAY_LENGTH_SHORTEST) {
            if (past && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_PAST) != 0) {
                // "1h ago"
                return com.android.internal.R.string.duration_hours_shortest_past;
            } else if (!past
                    && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_FUTURE) != 0) {
                // "in 1h"
                return com.android.internal.R.string.duration_hours_shortest_future;
            } else {
                // "1h"
                return com.android.internal.R.string.duration_hours_shortest;
            }
        } else { // UNIT_DISPLAY_LENGTH_MEDIUM
            if (past && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_PAST) != 0) {
                // "1hr ago"
                return com.android.internal.R.string.duration_hours_medium_past;
            } else if (!past
                    && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_FUTURE) != 0) {
                // "in 1hr"
                return com.android.internal.R.string.duration_hours_medium_future;
            } else {
                // "1hr"
                return com.android.internal.R.string.duration_hours_medium;
            }
        }
    }

    private int getDaysStringId(boolean past) {
        if (!mCanUseRelativeTimeDisplayConfigs) {
            return past
                    ? com.android.internal.R.string.duration_days_shortest
                    : com.android.internal.R.string.duration_days_shortest_future;
        }
        if (mRelativeTimeUnitDisplayLength == UNIT_DISPLAY_LENGTH_SHORTEST) {
            if (past && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_PAST) != 0) {
                // "1d ago"
                return com.android.internal.R.string.duration_days_shortest_past;
            } else if (!past
                    && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_FUTURE) != 0) {
                // "in 1d"
                return com.android.internal.R.string.duration_days_shortest_future;
            } else {
                // "1d"
                return com.android.internal.R.string.duration_days_shortest;
            }
        } else { // UNIT_DISPLAY_LENGTH_MEDIUM
            if (past && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_PAST) != 0) {
                // "1d ago"
                return com.android.internal.R.string.duration_days_medium_past;
            } else if (!past
                    && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_FUTURE) != 0) {
                // "in 1d"
                return com.android.internal.R.string.duration_days_medium_future;
            } else {
                // "1d"
                return com.android.internal.R.string.duration_days_medium;
            }
        }
    }

    private int getYearsStringId(boolean past) {
        if (!mCanUseRelativeTimeDisplayConfigs) {
            return past
                    ? com.android.internal.R.string.duration_years_shortest
                    : com.android.internal.R.string.duration_years_shortest_future;
        }
        if (mRelativeTimeUnitDisplayLength == UNIT_DISPLAY_LENGTH_SHORTEST) {
            if (past && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_PAST) != 0) {
                // "1y ago"
                return com.android.internal.R.string.duration_years_shortest_past;
            } else if (!past
                    && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_FUTURE) != 0) {
                // "in 1y"
                return com.android.internal.R.string.duration_years_shortest_future;
            } else {
                // "1y"
                return com.android.internal.R.string.duration_years_shortest;
            }
        } else { // UNIT_DISPLAY_LENGTH_MEDIUM
            if (past && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_PAST) != 0) {
                // "1y ago"
                return com.android.internal.R.string.duration_years_medium_past;
            } else if (!past
                    && (mRelativeTimeDisambiguationTextMask & DISAMBIGUATION_TEXT_FUTURE) != 0) {
                // "in 1y"
                return com.android.internal.R.string.duration_years_medium_future;
            } else {
                // "1y"
                return com.android.internal.R.string.duration_years_medium;
            }
        }
    }

    /**
     * Sets text only if the text has actually changed. This prevents needles relayouts of this
     * view when set to wrap_content.
     */
    private void maybeSetText(String text) {
        if (TextUtils.equals(getText(), text)) {
            return;
        }

        setText(text);
    }

    /**
     * Returns the epoch millis for the next midnight in the specified timezone.
     */
    private static long computeNextMidnight(LocalDateTime time, ZoneId zoneId) {
        // This ignores the chance of overflow: it should never happen.
        LocalDate tomorrow = time.toLocalDate().plusDays(1);
        LocalDateTime nextMidnight = LocalDateTime.of(tomorrow, LocalTime.MIDNIGHT);
        return toEpochMillis(nextMidnight, zoneId);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateNowText();
        update();
    }

    private void updateNowText() {
        if (!mShowRelativeTime) {
            return;
        }
        mNowText = getContext().getResources().getString(
                com.android.internal.R.string.now_string_shortest);
    }

    // Return the number of days between the two dates.
    private static int dayDistance(LocalDateTime start, LocalDateTime end) {
        return (int) (end.getLong(JulianFields.JULIAN_DAY)
                - start.getLong(JulianFields.JULIAN_DAY));
    }

    private DateFormat getTimeFormat() {
        return android.text.format.DateFormat.getTimeFormat(getContext());
    }

    void clearFormatAndUpdate() {
        mLastFormat = null;
        update();
    }

    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        if (mShowRelativeTime) {
            // The short version of the time might not be completely understandable and for
            // accessibility we rather have a longer version.
            long now = System.currentTimeMillis();
            long duration = Math.abs(now - mTimeMillis);
            int count;
            boolean past = (now >= mTimeMillis);
            String result;
            Map<String, Object> arguments = new HashMap<>();
            if (duration < MINUTE_IN_MILLIS) {
                result = mNowText;
            } else if (duration < HOUR_IN_MILLIS) {
                count = (int)(duration / MINUTE_IN_MILLIS);
                arguments.put("count", count);
                result = PluralsMessageFormatter.format(
                        getContext().getResources(),
                        arguments,
                        past ? R.string.duration_minutes_relative
                                : R.string.duration_minutes_relative_future);
            } else if (duration < DAY_IN_MILLIS) {
                count = (int)(duration / HOUR_IN_MILLIS);
                arguments.put("count", count);
                result = PluralsMessageFormatter.format(
                        getContext().getResources(),
                        arguments,
                        past ? R.string.duration_hours_relative
                                : R.string.duration_hours_relative_future);
            } else if (duration < YEAR_IN_MILLIS) {
                // In weird cases it can become 0 because of daylight savings
                LocalDateTime localDateTime = mLocalTime;
                ZoneId zoneId = ZoneId.systemDefault();
                LocalDateTime localNow = toLocalDateTime(now, zoneId);

                count = Math.max(Math.abs(dayDistance(localDateTime, localNow)), 1);
                arguments.put("count", count);
                result = PluralsMessageFormatter.format(
                        getContext().getResources(),
                        arguments,
                        past ? R.string.duration_days_relative
                                : R.string.duration_days_relative_future);
            } else {
                count = (int)(duration / YEAR_IN_MILLIS);
                arguments.put("count", count);
                result = PluralsMessageFormatter.format(
                        getContext().getResources(),
                        arguments,
                        past ? R.string.duration_years_relative
                                : R.string.duration_years_relative_future);
            }
            info.setText(result);
        }
    }

    /**
     * @hide
     */
    public static void setReceiverHandler(Handler handler) {
        ReceiverInfo ri = sReceiverInfo.get();
        if (ri == null) {
            ri = new ReceiverInfo();
            sReceiverInfo.set(ri);
        }
        ri.setHandler(handler);
    }

    private static class ReceiverInfo {
        private final ArrayList<DateTimeView> mAttachedViews = new ArrayList<DateTimeView>();
        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_TIME_TICK.equals(action)) {
                    if (System.currentTimeMillis() < getSoonestUpdateTime()) {
                        // The update() function takes a few milliseconds to run because of
                        // all of the time conversions it needs to do, so we can't do that
                        // every minute.
                        return;
                    }
                }
                // ACTION_TIME_CHANGED can also signal a change of 12/24 hr. format.
                updateAll();
            }
        };

        private final ContentObserver mObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                updateAll();
            }
        };

        private Handler mHandler = new Handler();

        public void addView(DateTimeView v) {
            synchronized (mAttachedViews) {
                final boolean register = mAttachedViews.isEmpty();
                mAttachedViews.add(v);
                if (register) {
                    register(getApplicationContextIfAvailable(v.getContext()));
                }
            }
        }

        public void removeView(DateTimeView v) {
            synchronized (mAttachedViews) {
                final boolean removed = mAttachedViews.remove(v);
                // Only unregister once when we remove the last view in the list otherwise we risk
                // trying to unregister a receiver that is no longer registered.
                if (removed && mAttachedViews.isEmpty()) {
                    unregister(getApplicationContextIfAvailable(v.getContext()));
                }
            }
        }

        void updateAll() {
            synchronized (mAttachedViews) {
                final int count = mAttachedViews.size();
                for (int i = 0; i < count; i++) {
                    DateTimeView view = mAttachedViews.get(i);
                    view.post(() -> view.clearFormatAndUpdate());
                }
            }
        }

        long getSoonestUpdateTime() {
            long result = Long.MAX_VALUE;
            synchronized (mAttachedViews) {
                final int count = mAttachedViews.size();
                for (int i = 0; i < count; i++) {
                    final long time = mAttachedViews.get(i).mUpdateTimeMillis;
                    if (time < result) {
                        result = time;
                    }
                }
            }
            return result;
        }

        static final Context getApplicationContextIfAvailable(Context context) {
            final Context ac = context.getApplicationContext();
            return ac != null ? ac : ActivityThread.currentApplication().getApplicationContext();
        }

        void register(Context context) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            context.registerReceiver(mReceiver, filter, null, mHandler);
        }

        void unregister(Context context) {
            context.unregisterReceiver(mReceiver);
        }

        public void setHandler(Handler handler) {
            mHandler = handler;
            synchronized (mAttachedViews) {
                if (!mAttachedViews.isEmpty()) {
                    unregister(mAttachedViews.get(0).getContext());
                    register(mAttachedViews.get(0).getContext());
                }
            }
        }
    }

    private static LocalDateTime toLocalDateTime(long timeMillis, ZoneId zoneId) {
        // java.time types like LocalDateTime / Instant can support the full range of "long millis"
        // with room to spare so we do not need to worry about overflow / underflow and the rsulting
        // exceptions while the input to this class is a long.
        Instant instant = Instant.ofEpochMilli(timeMillis);
        return LocalDateTime.ofInstant(instant, zoneId);
    }

    private static long toEpochMillis(LocalDateTime time, ZoneId zoneId) {
        Instant instant = time.toInstant(zoneId.getRules().getOffset(time));
        return instant.toEpochMilli();
    }
}
