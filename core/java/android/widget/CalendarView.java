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

import android.annotation.Widget;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * This class is a calendar widget for displaying and selecting dates. The range
 * of dates supported by this calendar is configurable. A user can select a date
 * by taping on it and can scroll and fling the calendar to a desired date.
 *
 * @attr ref android.R.styleable#CalendarView_showWeekNumber
 * @attr ref android.R.styleable#CalendarView_firstDayOfWeek
 * @attr ref android.R.styleable#CalendarView_minDate
 * @attr ref android.R.styleable#CalendarView_maxDate
 * @attr ref android.R.styleable#CalendarView_shownWeekCount
 * @attr ref android.R.styleable#CalendarView_selectedWeekBackgroundColor
 * @attr ref android.R.styleable#CalendarView_focusedMonthDateColor
 * @attr ref android.R.styleable#CalendarView_unfocusedMonthDateColor
 * @attr ref android.R.styleable#CalendarView_weekNumberColor
 * @attr ref android.R.styleable#CalendarView_weekSeparatorLineColor
 * @attr ref android.R.styleable#CalendarView_selectedDateVerticalBar
 * @attr ref android.R.styleable#CalendarView_weekDayTextAppearance
 * @attr ref android.R.styleable#CalendarView_dateTextAppearance
 */
@Widget
public class CalendarView extends FrameLayout {
    private static final String LOG_TAG = "CalendarView";

    private static final int MODE_HOLO = 0;
    private static final int MODE_MATERIAL = 1;

    private final CalendarViewDelegate mDelegate;

    /**
     * The callback used to indicate the user changes the date.
     */
    public interface OnDateChangeListener {

        /**
         * Called upon change of the selected day.
         *
         * @param view The view associated with this listener.
         * @param year The year that was set.
         * @param month The month that was set [0-11].
         * @param dayOfMonth The day of the month that was set.
         */
        public void onSelectedDayChange(CalendarView view, int year, int month, int dayOfMonth);
    }

    public CalendarView(Context context) {
        this(context, null);
    }

    public CalendarView(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.calendarViewStyle);
    }

    public CalendarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CalendarView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.CalendarView, defStyleAttr, defStyleRes);
        final int mode = a.getInt(R.styleable.CalendarView_calendarViewMode, MODE_HOLO);
        a.recycle();

        switch (mode) {
            case MODE_HOLO:
                mDelegate = new CalendarViewLegacyDelegate(
                        this, context, attrs, defStyleAttr, defStyleRes);
                break;
            case MODE_MATERIAL:
                mDelegate = new CalendarViewMaterialDelegate(
                        this, context, attrs, defStyleAttr, defStyleRes);
                break;
            default:
                throw new IllegalArgumentException("invalid calendarViewMode attribute");
        }
    }

    /**
     * Sets the number of weeks to be shown.
     *
     * @param count The shown week count.
     *
     * @attr ref android.R.styleable#CalendarView_shownWeekCount
     */
    public void setShownWeekCount(int count) {
        mDelegate.setShownWeekCount(count);
    }

    /**
     * Gets the number of weeks to be shown.
     *
     * @return The shown week count.
     *
     * @attr ref android.R.styleable#CalendarView_shownWeekCount
     */
    public int getShownWeekCount() {
        return mDelegate.getShownWeekCount();
    }

    /**
     * Sets the background color for the selected week.
     *
     * @param color The week background color.
     *
     * @attr ref android.R.styleable#CalendarView_selectedWeekBackgroundColor
     */
    public void setSelectedWeekBackgroundColor(int color) {
        mDelegate.setSelectedWeekBackgroundColor(color);
    }

    /**
     * Gets the background color for the selected week.
     *
     * @return The week background color.
     *
     * @attr ref android.R.styleable#CalendarView_selectedWeekBackgroundColor
     */
    public int getSelectedWeekBackgroundColor() {
        return mDelegate.getSelectedWeekBackgroundColor();
    }

    /**
     * Sets the color for the dates of the focused month.
     *
     * @param color The focused month date color.
     *
     * @attr ref android.R.styleable#CalendarView_focusedMonthDateColor
     */
    public void setFocusedMonthDateColor(int color) {
        mDelegate.setFocusedMonthDateColor(color);
    }

    /**
     * Gets the color for the dates in the focused month.
     *
     * @return The focused month date color.
     *
     * @attr ref android.R.styleable#CalendarView_focusedMonthDateColor
     */
    public int getFocusedMonthDateColor() {
        return mDelegate.getFocusedMonthDateColor();
    }

    /**
     * Sets the color for the dates of a not focused month.
     *
     * @param color A not focused month date color.
     *
     * @attr ref android.R.styleable#CalendarView_unfocusedMonthDateColor
     */
    public void setUnfocusedMonthDateColor(int color) {
        mDelegate.setUnfocusedMonthDateColor(color);
    }

    /**
     * Gets the color for the dates in a not focused month.
     *
     * @return A not focused month date color.
     *
     * @attr ref android.R.styleable#CalendarView_unfocusedMonthDateColor
     */
    public int getUnfocusedMonthDateColor() {
        return mDelegate.getUnfocusedMonthDateColor();
    }

    /**
     * Sets the color for the week numbers.
     *
     * @param color The week number color.
     *
     * @attr ref android.R.styleable#CalendarView_weekNumberColor
     */
    public void setWeekNumberColor(int color) {
        mDelegate.setWeekNumberColor(color);
    }

    /**
     * Gets the color for the week numbers.
     *
     * @return The week number color.
     *
     * @attr ref android.R.styleable#CalendarView_weekNumberColor
     */
    public int getWeekNumberColor() {
        return mDelegate.getWeekNumberColor();
    }

    /**
     * Sets the color for the separator line between weeks.
     *
     * @param color The week separator color.
     *
     * @attr ref android.R.styleable#CalendarView_weekSeparatorLineColor
     */
    public void setWeekSeparatorLineColor(int color) {
        mDelegate.setWeekSeparatorLineColor(color);
    }

    /**
     * Gets the color for the separator line between weeks.
     *
     * @return The week separator color.
     *
     * @attr ref android.R.styleable#CalendarView_weekSeparatorLineColor
     */
    public int getWeekSeparatorLineColor() {
        return mDelegate.getWeekSeparatorLineColor();
    }

    /**
     * Sets the drawable for the vertical bar shown at the beginning and at
     * the end of the selected date.
     *
     * @param resourceId The vertical bar drawable resource id.
     *
     * @attr ref android.R.styleable#CalendarView_selectedDateVerticalBar
     */
    public void setSelectedDateVerticalBar(int resourceId) {
        mDelegate.setSelectedDateVerticalBar(resourceId);
    }

    /**
     * Sets the drawable for the vertical bar shown at the beginning and at
     * the end of the selected date.
     *
     * @param drawable The vertical bar drawable.
     *
     * @attr ref android.R.styleable#CalendarView_selectedDateVerticalBar
     */
    public void setSelectedDateVerticalBar(Drawable drawable) {
        mDelegate.setSelectedDateVerticalBar(drawable);
    }

    /**
     * Gets the drawable for the vertical bar shown at the beginning and at
     * the end of the selected date.
     *
     * @return The vertical bar drawable.
     */
    public Drawable getSelectedDateVerticalBar() {
        return mDelegate.getSelectedDateVerticalBar();
    }

    /**
     * Sets the text appearance for the week day abbreviation of the calendar header.
     *
     * @param resourceId The text appearance resource id.
     *
     * @attr ref android.R.styleable#CalendarView_weekDayTextAppearance
     */
    public void setWeekDayTextAppearance(int resourceId) {
        mDelegate.setWeekDayTextAppearance(resourceId);
    }

    /**
     * Gets the text appearance for the week day abbreviation of the calendar header.
     *
     * @return The text appearance resource id.
     *
     * @attr ref android.R.styleable#CalendarView_weekDayTextAppearance
     */
    public int getWeekDayTextAppearance() {
        return mDelegate.getWeekDayTextAppearance();
    }

    /**
     * Sets the text appearance for the calendar dates.
     *
     * @param resourceId The text appearance resource id.
     *
     * @attr ref android.R.styleable#CalendarView_dateTextAppearance
     */
    public void setDateTextAppearance(int resourceId) {
        mDelegate.setDateTextAppearance(resourceId);
    }

    /**
     * Gets the text appearance for the calendar dates.
     *
     * @return The text appearance resource id.
     *
     * @attr ref android.R.styleable#CalendarView_dateTextAppearance
     */
    public int getDateTextAppearance() {
        return mDelegate.getDateTextAppearance();
    }

    /**
     * Gets the minimal date supported by this {@link CalendarView} in milliseconds
     * since January 1, 1970 00:00:00 in {@link TimeZone#getDefault()} time
     * zone.
     * <p>
     * Note: The default minimal date is 01/01/1900.
     * <p>
     *
     * @return The minimal supported date.
     *
     * @attr ref android.R.styleable#CalendarView_minDate
     */
    public long getMinDate() {
        return mDelegate.getMinDate();
    }

    /**
     * Sets the minimal date supported by this {@link CalendarView} in milliseconds
     * since January 1, 1970 00:00:00 in {@link TimeZone#getDefault()} time
     * zone.
     *
     * @param minDate The minimal supported date.
     *
     * @attr ref android.R.styleable#CalendarView_minDate
     */
    public void setMinDate(long minDate) {
        mDelegate.setMinDate(minDate);
    }

    /**
     * Gets the maximal date supported by this {@link CalendarView} in milliseconds
     * since January 1, 1970 00:00:00 in {@link TimeZone#getDefault()} time
     * zone.
     * <p>
     * Note: The default maximal date is 01/01/2100.
     * <p>
     *
     * @return The maximal supported date.
     *
     * @attr ref android.R.styleable#CalendarView_maxDate
     */
    public long getMaxDate() {
        return mDelegate.getMaxDate();
    }

    /**
     * Sets the maximal date supported by this {@link CalendarView} in milliseconds
     * since January 1, 1970 00:00:00 in {@link TimeZone#getDefault()} time
     * zone.
     *
     * @param maxDate The maximal supported date.
     *
     * @attr ref android.R.styleable#CalendarView_maxDate
     */
    public void setMaxDate(long maxDate) {
        mDelegate.setMaxDate(maxDate);
    }

    /**
     * Sets whether to show the week number.
     *
     * @param showWeekNumber True to show the week number.
     *
     * @attr ref android.R.styleable#CalendarView_showWeekNumber
     */
    public void setShowWeekNumber(boolean showWeekNumber) {
        mDelegate.setShowWeekNumber(showWeekNumber);
    }

    /**
     * Gets whether to show the week number.
     *
     * @return True if showing the week number.
     *
     * @attr ref android.R.styleable#CalendarView_showWeekNumber
     */
    public boolean getShowWeekNumber() {
        return mDelegate.getShowWeekNumber();
    }

    /**
     * Gets the first day of week.
     *
     * @return The first day of the week conforming to the {@link CalendarView}
     *         APIs.
     * @see Calendar#MONDAY
     * @see Calendar#TUESDAY
     * @see Calendar#WEDNESDAY
     * @see Calendar#THURSDAY
     * @see Calendar#FRIDAY
     * @see Calendar#SATURDAY
     * @see Calendar#SUNDAY
     *
     * @attr ref android.R.styleable#CalendarView_firstDayOfWeek
     */
    public int getFirstDayOfWeek() {
        return mDelegate.getFirstDayOfWeek();
    }

    /**
     * Sets the first day of week.
     *
     * @param firstDayOfWeek The first day of the week conforming to the
     *            {@link CalendarView} APIs.
     * @see Calendar#MONDAY
     * @see Calendar#TUESDAY
     * @see Calendar#WEDNESDAY
     * @see Calendar#THURSDAY
     * @see Calendar#FRIDAY
     * @see Calendar#SATURDAY
     * @see Calendar#SUNDAY
     *
     * @attr ref android.R.styleable#CalendarView_firstDayOfWeek
     */
    public void setFirstDayOfWeek(int firstDayOfWeek) {
        mDelegate.setFirstDayOfWeek(firstDayOfWeek);
    }

    /**
     * Sets the listener to be notified upon selected date change.
     *
     * @param listener The listener to be notified.
     */
    public void setOnDateChangeListener(OnDateChangeListener listener) {
        mDelegate.setOnDateChangeListener(listener);
    }

    /**
     * Gets the selected date in milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     *
     * @return The selected date.
     */
    public long getDate() {
        return mDelegate.getDate();
    }

    /**
     * Sets the selected date in milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     *
     * @param date The selected date.
     *
     * @throws IllegalArgumentException of the provided date is before the
     *        minimal or after the maximal date.
     *
     * @see #setDate(long, boolean, boolean)
     * @see #setMinDate(long)
     * @see #setMaxDate(long)
     */
    public void setDate(long date) {
        mDelegate.setDate(date);
    }

    /**
     * Sets the selected date in milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     *
     * @param date The date.
     * @param animate Whether to animate the scroll to the current date.
     * @param center Whether to center the current date even if it is already visible.
     *
     * @throws IllegalArgumentException of the provided date is before the
     *        minimal or after the maximal date.
     *
     * @see #setMinDate(long)
     * @see #setMaxDate(long)
     */
    public void setDate(long date, boolean animate, boolean center) {
        mDelegate.setDate(date, animate, center);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDelegate.onConfigurationChanged(newConfig);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        event.setClassName(CalendarView.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        info.setClassName(CalendarView.class.getName());
    }

    /**
     * A delegate interface that defined the public API of the CalendarView. Allows different
     * CalendarView implementations. This would need to be implemented by the CalendarView delegates
     * for the real behavior.
     */
    private interface CalendarViewDelegate {
        void setShownWeekCount(int count);
        int getShownWeekCount();

        void setSelectedWeekBackgroundColor(int color);
        int getSelectedWeekBackgroundColor();

        void setFocusedMonthDateColor(int color);
        int getFocusedMonthDateColor();

        void setUnfocusedMonthDateColor(int color);
        int getUnfocusedMonthDateColor();

        void setWeekNumberColor(int color);
        int getWeekNumberColor();

        void setWeekSeparatorLineColor(int color);
        int getWeekSeparatorLineColor();

        void setSelectedDateVerticalBar(int resourceId);
        void setSelectedDateVerticalBar(Drawable drawable);
        Drawable getSelectedDateVerticalBar();

        void setWeekDayTextAppearance(int resourceId);
        int getWeekDayTextAppearance();

        void setDateTextAppearance(int resourceId);
        int getDateTextAppearance();

        void setMinDate(long minDate);
        long getMinDate();

        void setMaxDate(long maxDate);
        long getMaxDate();

        void setShowWeekNumber(boolean showWeekNumber);
        boolean getShowWeekNumber();

        void setFirstDayOfWeek(int firstDayOfWeek);
        int getFirstDayOfWeek();

        void setDate(long date);
        void setDate(long date, boolean animate, boolean center);
        long getDate();

        void setOnDateChangeListener(OnDateChangeListener listener);

        void onConfigurationChanged(Configuration newConfig);
    }

    /**
     * An abstract class which can be used as a start for CalendarView implementations
     */
    abstract static class AbstractCalendarViewDelegate implements CalendarViewDelegate {
        /** String for parsing dates. */
        private static final String DATE_FORMAT = "MM/dd/yyyy";

        /** The default minimal date. */
        protected static final String DEFAULT_MIN_DATE = "01/01/1900";

        /** The default maximal date. */
        protected static final String DEFAULT_MAX_DATE = "01/01/2100";

        /** Date format for parsing dates. */
        protected static final DateFormat DATE_FORMATTER = new SimpleDateFormat(DATE_FORMAT);

        protected CalendarView mDelegator;
        protected Context mContext;
        protected Locale mCurrentLocale;

        AbstractCalendarViewDelegate(CalendarView delegator, Context context) {
            mDelegator = delegator;
            mContext = context;

            // Initialization based on locale
            setCurrentLocale(Locale.getDefault());
        }

        protected void setCurrentLocale(Locale locale) {
            if (locale.equals(mCurrentLocale)) {
                return;
            }
            mCurrentLocale = locale;
        }

        /**
         * Parses the given <code>date</code> and in case of success sets
         * the result to the <code>outDate</code>.
         *
         * @return True if the date was parsed.
         */
        protected boolean parseDate(String date, Calendar outDate) {
            try {
                outDate.setTime(DATE_FORMATTER.parse(date));
                return true;
            } catch (ParseException e) {
                Log.w(LOG_TAG, "Date: " + date + " not in format: " + DATE_FORMAT);
                return false;
            }
        }
    }

}
