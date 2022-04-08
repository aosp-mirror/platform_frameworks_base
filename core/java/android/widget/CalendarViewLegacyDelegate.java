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

package android.widget;

import android.app.Service;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.icu.util.Calendar;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.R;

import libcore.icu.LocaleData;

import java.util.Locale;

/**
 * A delegate implementing the legacy CalendarView
 */
class CalendarViewLegacyDelegate extends CalendarView.AbstractCalendarViewDelegate {
    /**
     * Default value whether to show week number.
     */
    private static final boolean DEFAULT_SHOW_WEEK_NUMBER = true;

    /**
     * The number of milliseconds in a day.e
     */
    private static final long MILLIS_IN_DAY = 86400000L;

    /**
     * The number of day in a week.
     */
    private static final int DAYS_PER_WEEK = 7;

    /**
     * The number of milliseconds in a week.
     */
    private static final long MILLIS_IN_WEEK = DAYS_PER_WEEK * MILLIS_IN_DAY;

    /**
     * Affects when the month selection will change while scrolling upe
     */
    private static final int SCROLL_HYST_WEEKS = 2;

    /**
     * How long the GoTo fling animation should last.
     */
    private static final int GOTO_SCROLL_DURATION = 1000;

    /**
     * The duration of the adjustment upon a user scroll in milliseconds.
     */
    private static final int ADJUSTMENT_SCROLL_DURATION = 500;

    /**
     * How long to wait after receiving an onScrollStateChanged notification
     * before acting on it.
     */
    private static final int SCROLL_CHANGE_DELAY = 40;

    private static final int DEFAULT_SHOWN_WEEK_COUNT = 6;

    private static final int DEFAULT_DATE_TEXT_SIZE = 14;

    private static final int UNSCALED_SELECTED_DATE_VERTICAL_BAR_WIDTH = 6;

    private static final int UNSCALED_WEEK_MIN_VISIBLE_HEIGHT = 12;

    private static final int UNSCALED_LIST_SCROLL_TOP_OFFSET = 2;

    private static final int UNSCALED_BOTTOM_BUFFER = 20;

    private static final int UNSCALED_WEEK_SEPARATOR_LINE_WIDTH = 1;

    private static final int DEFAULT_WEEK_DAY_TEXT_APPEARANCE_RES_ID = -1;

    private final int mWeekSeparatorLineWidth;

    private int mDateTextSize;

    private Drawable mSelectedDateVerticalBar;

    private final int mSelectedDateVerticalBarWidth;

    private int mSelectedWeekBackgroundColor;

    private int mFocusedMonthDateColor;

    private int mUnfocusedMonthDateColor;

    private int mWeekSeparatorLineColor;

    private int mWeekNumberColor;

    private int mWeekDayTextAppearanceResId;

    private int mDateTextAppearanceResId;

    /**
     * The top offset of the weeks list.
     */
    private int mListScrollTopOffset = 2;

    /**
     * The visible height of a week view.
     */
    private int mWeekMinVisibleHeight = 12;

    /**
     * The visible height of a week view.
     */
    private int mBottomBuffer = 20;

    /**
     * The number of shown weeks.
     */
    private int mShownWeekCount;

    /**
     * Flag whether to show the week number.
     */
    private boolean mShowWeekNumber;

    /**
     * The number of day per week to be shown.
     */
    private int mDaysPerWeek = 7;

    /**
     * The friction of the week list while flinging.
     */
    private float mFriction = .05f;

    /**
     * Scale for adjusting velocity of the week list while flinging.
     */
    private float mVelocityScale = 0.333f;

    /**
     * The adapter for the weeks list.
     */
    private WeeksAdapter mAdapter;

    /**
     * The weeks list.
     */
    private ListView mListView;

    /**
     * The name of the month to display.
     */
    private TextView mMonthName;

    /**
     * The header with week day names.
     */
    private ViewGroup mDayNamesHeader;

    /**
     * Cached abbreviations for day of week names.
     */
    private String[] mDayNamesShort;

    /**
     * Cached full-length day of week names.
     */
    private String[] mDayNamesLong;

    /**
     * The first day of the week.
     */
    private int mFirstDayOfWeek;

    /**
     * Which month should be displayed/highlighted [0-11].
     */
    private int mCurrentMonthDisplayed = -1;

    /**
     * Used for tracking during a scroll.
     */
    private long mPreviousScrollPosition;

    /**
     * Used for tracking which direction the view is scrolling.
     */
    private boolean mIsScrollingUp = false;

    /**
     * The previous scroll state of the weeks ListView.
     */
    private int mPreviousScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

    /**
     * The current scroll state of the weeks ListView.
     */
    private int mCurrentScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

    /**
     * Listener for changes in the selected day.
     */
    private CalendarView.OnDateChangeListener mOnDateChangeListener;

    /**
     * Command for adjusting the position after a scroll/fling.
     */
    private ScrollStateRunnable mScrollStateChangedRunnable = new ScrollStateRunnable();

    /**
     * Temporary instance to avoid multiple instantiations.
     */
    private Calendar mTempDate;

    /**
     * The first day of the focused month.
     */
    private Calendar mFirstDayOfMonth;

    /**
     * The start date of the range supported by this picker.
     */
    private Calendar mMinDate;

    /**
     * The end date of the range supported by this picker.
     */
    private Calendar mMaxDate;

    CalendarViewLegacyDelegate(CalendarView delegator, Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(delegator, context);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CalendarView, defStyleAttr, defStyleRes);
        mShowWeekNumber = a.getBoolean(R.styleable.CalendarView_showWeekNumber,
                DEFAULT_SHOW_WEEK_NUMBER);
        mFirstDayOfWeek = a.getInt(R.styleable.CalendarView_firstDayOfWeek,
                LocaleData.get(Locale.getDefault()).firstDayOfWeek);
        final String minDate = a.getString(R.styleable.CalendarView_minDate);
        if (!CalendarView.parseDate(minDate, mMinDate)) {
            CalendarView.parseDate(DEFAULT_MIN_DATE, mMinDate);
        }
        final String maxDate = a.getString(R.styleable.CalendarView_maxDate);
        if (!CalendarView.parseDate(maxDate, mMaxDate)) {
            CalendarView.parseDate(DEFAULT_MAX_DATE, mMaxDate);
        }
        if (mMaxDate.before(mMinDate)) {
            throw new IllegalArgumentException("Max date cannot be before min date.");
        }
        mShownWeekCount = a.getInt(R.styleable.CalendarView_shownWeekCount,
                DEFAULT_SHOWN_WEEK_COUNT);
        mSelectedWeekBackgroundColor = a.getColor(
                R.styleable.CalendarView_selectedWeekBackgroundColor, 0);
        mFocusedMonthDateColor = a.getColor(
                R.styleable.CalendarView_focusedMonthDateColor, 0);
        mUnfocusedMonthDateColor = a.getColor(
                R.styleable.CalendarView_unfocusedMonthDateColor, 0);
        mWeekSeparatorLineColor = a.getColor(
                R.styleable.CalendarView_weekSeparatorLineColor, 0);
        mWeekNumberColor = a.getColor(R.styleable.CalendarView_weekNumberColor, 0);
        mSelectedDateVerticalBar = a.getDrawable(
                R.styleable.CalendarView_selectedDateVerticalBar);

        mDateTextAppearanceResId = a.getResourceId(
                R.styleable.CalendarView_dateTextAppearance, R.style.TextAppearance_Small);
        updateDateTextSize();

        mWeekDayTextAppearanceResId = a.getResourceId(
                R.styleable.CalendarView_weekDayTextAppearance,
                DEFAULT_WEEK_DAY_TEXT_APPEARANCE_RES_ID);
        a.recycle();

        DisplayMetrics displayMetrics = mDelegator.getResources().getDisplayMetrics();
        mWeekMinVisibleHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                UNSCALED_WEEK_MIN_VISIBLE_HEIGHT, displayMetrics);
        mListScrollTopOffset = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                UNSCALED_LIST_SCROLL_TOP_OFFSET, displayMetrics);
        mBottomBuffer = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                UNSCALED_BOTTOM_BUFFER, displayMetrics);
        mSelectedDateVerticalBarWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                UNSCALED_SELECTED_DATE_VERTICAL_BAR_WIDTH, displayMetrics);
        mWeekSeparatorLineWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                UNSCALED_WEEK_SEPARATOR_LINE_WIDTH, displayMetrics);

        LayoutInflater layoutInflater = (LayoutInflater) mContext
                .getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        View content = layoutInflater.inflate(R.layout.calendar_view, null, false);
        mDelegator.addView(content);

        mListView = mDelegator.findViewById(R.id.list);
        mDayNamesHeader = content.findViewById(R.id.day_names);
        mMonthName = content.findViewById(R.id.month_name);

        setUpHeader();
        setUpListView();
        setUpAdapter();

        // go to today or whichever is close to today min or max date
        mTempDate.setTimeInMillis(System.currentTimeMillis());
        if (mTempDate.before(mMinDate)) {
            goTo(mMinDate, false, true, true);
        } else if (mMaxDate.before(mTempDate)) {
            goTo(mMaxDate, false, true, true);
        } else {
            goTo(mTempDate, false, true, true);
        }

        mDelegator.invalidate();
    }

    @Override
    public void setShownWeekCount(int count) {
        if (mShownWeekCount != count) {
            mShownWeekCount = count;
            mDelegator.invalidate();
        }
    }

    @Override
    public int getShownWeekCount() {
        return mShownWeekCount;
    }

    @Override
    public void setSelectedWeekBackgroundColor(int color) {
        if (mSelectedWeekBackgroundColor != color) {
            mSelectedWeekBackgroundColor = color;
            final int childCount = mListView.getChildCount();
            for (int i = 0; i < childCount; i++) {
                WeekView weekView = (WeekView) mListView.getChildAt(i);
                if (weekView.mHasSelectedDay) {
                    weekView.invalidate();
                }
            }
        }
    }

    @Override
    public int getSelectedWeekBackgroundColor() {
        return mSelectedWeekBackgroundColor;
    }

    @Override
    public void setFocusedMonthDateColor(int color) {
        if (mFocusedMonthDateColor != color) {
            mFocusedMonthDateColor = color;
            final int childCount = mListView.getChildCount();
            for (int i = 0; i < childCount; i++) {
                WeekView weekView = (WeekView) mListView.getChildAt(i);
                if (weekView.mHasFocusedDay) {
                    weekView.invalidate();
                }
            }
        }
    }

    @Override
    public int getFocusedMonthDateColor() {
        return mFocusedMonthDateColor;
    }

    @Override
    public void setUnfocusedMonthDateColor(int color) {
        if (mUnfocusedMonthDateColor != color) {
            mUnfocusedMonthDateColor = color;
            final int childCount = mListView.getChildCount();
            for (int i = 0; i < childCount; i++) {
                WeekView weekView = (WeekView) mListView.getChildAt(i);
                if (weekView.mHasUnfocusedDay) {
                    weekView.invalidate();
                }
            }
        }
    }

    @Override
    public int getUnfocusedMonthDateColor() {
        return mUnfocusedMonthDateColor;
    }

    @Override
    public void setWeekNumberColor(int color) {
        if (mWeekNumberColor != color) {
            mWeekNumberColor = color;
            if (mShowWeekNumber) {
                invalidateAllWeekViews();
            }
        }
    }

    @Override
    public int getWeekNumberColor() {
        return mWeekNumberColor;
    }

    @Override
    public void setWeekSeparatorLineColor(int color) {
        if (mWeekSeparatorLineColor != color) {
            mWeekSeparatorLineColor = color;
            invalidateAllWeekViews();
        }
    }

    @Override
    public int getWeekSeparatorLineColor() {
        return mWeekSeparatorLineColor;
    }

    @Override
    public void setSelectedDateVerticalBar(int resourceId) {
        Drawable drawable = mDelegator.getContext().getDrawable(resourceId);
        setSelectedDateVerticalBar(drawable);
    }

    @Override
    public void setSelectedDateVerticalBar(Drawable drawable) {
        if (mSelectedDateVerticalBar != drawable) {
            mSelectedDateVerticalBar = drawable;
            final int childCount = mListView.getChildCount();
            for (int i = 0; i < childCount; i++) {
                WeekView weekView = (WeekView) mListView.getChildAt(i);
                if (weekView.mHasSelectedDay) {
                    weekView.invalidate();
                }
            }
        }
    }

    @Override
    public Drawable getSelectedDateVerticalBar() {
        return mSelectedDateVerticalBar;
    }

    @Override
    public void setWeekDayTextAppearance(int resourceId) {
        if (mWeekDayTextAppearanceResId != resourceId) {
            mWeekDayTextAppearanceResId = resourceId;
            setUpHeader();
        }
    }

    @Override
    public int getWeekDayTextAppearance() {
        return mWeekDayTextAppearanceResId;
    }

    @Override
    public void setDateTextAppearance(int resourceId) {
        if (mDateTextAppearanceResId != resourceId) {
            mDateTextAppearanceResId = resourceId;
            updateDateTextSize();
            invalidateAllWeekViews();
        }
    }

    @Override
    public int getDateTextAppearance() {
        return mDateTextAppearanceResId;
    }

    @Override
    public void setMinDate(long minDate) {
        mTempDate.setTimeInMillis(minDate);
        if (isSameDate(mTempDate, mMinDate)) {
            return;
        }
        mMinDate.setTimeInMillis(minDate);
        // make sure the current date is not earlier than
        // the new min date since the latter is used for
        // calculating the indices in the adapter thus
        // avoiding out of bounds error
        Calendar date = mAdapter.mSelectedDate;
        if (date.before(mMinDate)) {
            mAdapter.setSelectedDay(mMinDate);
        }
        // reinitialize the adapter since its range depends on min date
        mAdapter.init();
        if (date.before(mMinDate)) {
            setDate(mTempDate.getTimeInMillis());
        } else {
            // we go to the current date to force the ListView to query its
            // adapter for the shown views since we have changed the adapter
            // range and the base from which the later calculates item indices
            // note that calling setDate will not work since the date is the same
            goTo(date, false, true, false);
        }
    }

    @Override
    public long getMinDate() {
        return mMinDate.getTimeInMillis();
    }

    @Override
    public void setMaxDate(long maxDate) {
        mTempDate.setTimeInMillis(maxDate);
        if (isSameDate(mTempDate, mMaxDate)) {
            return;
        }
        mMaxDate.setTimeInMillis(maxDate);
        // reinitialize the adapter since its range depends on max date
        mAdapter.init();
        Calendar date = mAdapter.mSelectedDate;
        if (date.after(mMaxDate)) {
            setDate(mMaxDate.getTimeInMillis());
        } else {
            // we go to the current date to force the ListView to query its
            // adapter for the shown views since we have changed the adapter
            // range and the base from which the later calculates item indices
            // note that calling setDate will not work since the date is the same
            goTo(date, false, true, false);
        }
    }

    @Override
    public long getMaxDate() {
        return mMaxDate.getTimeInMillis();
    }

    @Override
    public void setShowWeekNumber(boolean showWeekNumber) {
        if (mShowWeekNumber == showWeekNumber) {
            return;
        }
        mShowWeekNumber = showWeekNumber;
        mAdapter.notifyDataSetChanged();
        setUpHeader();
    }

    @Override
    public boolean getShowWeekNumber() {
        return mShowWeekNumber;
    }

    @Override
    public void setFirstDayOfWeek(int firstDayOfWeek) {
        if (mFirstDayOfWeek == firstDayOfWeek) {
            return;
        }
        mFirstDayOfWeek = firstDayOfWeek;
        mAdapter.init();
        mAdapter.notifyDataSetChanged();
        setUpHeader();
    }

    @Override
    public int getFirstDayOfWeek() {
        return mFirstDayOfWeek;
    }

    @Override
    public void setDate(long date) {
        setDate(date, false, false);
    }

    @Override
    public void setDate(long date, boolean animate, boolean center) {
        mTempDate.setTimeInMillis(date);
        if (isSameDate(mTempDate, mAdapter.mSelectedDate)) {
            return;
        }
        goTo(mTempDate, animate, true, center);
    }

    @Override
    public long getDate() {
        return mAdapter.mSelectedDate.getTimeInMillis();
    }

    @Override
    public void setOnDateChangeListener(CalendarView.OnDateChangeListener listener) {
        mOnDateChangeListener = listener;
    }

    @Override
    public boolean getBoundsForDate(long date, Rect outBounds) {
        Calendar calendarDate = Calendar.getInstance();
        calendarDate.setTimeInMillis(date);
        int listViewEntryCount = mListView.getCount();
        for (int i = 0; i < listViewEntryCount; i++) {
            WeekView currWeekView = (WeekView) mListView.getChildAt(i);
            if (currWeekView.getBoundsForDate(calendarDate, outBounds)) {
                // Found the date in this week. Now need to offset vertically to return correct
                // bounds in the coordinate system of the entire layout
                final int[] weekViewPositionOnScreen = new int[2];
                final int[] delegatorPositionOnScreen = new int[2];
                currWeekView.getLocationOnScreen(weekViewPositionOnScreen);
                mDelegator.getLocationOnScreen(delegatorPositionOnScreen);
                final int extraVerticalOffset =
                        weekViewPositionOnScreen[1] - delegatorPositionOnScreen[1];
                outBounds.top += extraVerticalOffset;
                outBounds.bottom += extraVerticalOffset;
                return true;
            }
        }
        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        setCurrentLocale(newConfig.locale);
    }

    /**
     * Sets the current locale.
     *
     * @param locale The current locale.
     */
    @Override
    protected void setCurrentLocale(Locale locale) {
        super.setCurrentLocale(locale);

        mTempDate = getCalendarForLocale(mTempDate, locale);
        mFirstDayOfMonth = getCalendarForLocale(mFirstDayOfMonth, locale);
        mMinDate = getCalendarForLocale(mMinDate, locale);
        mMaxDate = getCalendarForLocale(mMaxDate, locale);
    }
    private void updateDateTextSize() {
        TypedArray dateTextAppearance = mDelegator.getContext().obtainStyledAttributes(
                mDateTextAppearanceResId, R.styleable.TextAppearance);
        mDateTextSize = dateTextAppearance.getDimensionPixelSize(
                R.styleable.TextAppearance_textSize, DEFAULT_DATE_TEXT_SIZE);
        dateTextAppearance.recycle();
    }

    /**
     * Invalidates all week views.
     */
    private void invalidateAllWeekViews() {
        final int childCount = mListView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View view = mListView.getChildAt(i);
            view.invalidate();
        }
    }

    /**
     * Gets a calendar for locale bootstrapped with the value of a given calendar.
     *
     * @param oldCalendar The old calendar.
     * @param locale The locale.
     */
    private static Calendar getCalendarForLocale(Calendar oldCalendar, Locale locale) {
        if (oldCalendar == null) {
            return Calendar.getInstance(locale);
        } else {
            final long currentTimeMillis = oldCalendar.getTimeInMillis();
            Calendar newCalendar = Calendar.getInstance(locale);
            newCalendar.setTimeInMillis(currentTimeMillis);
            return newCalendar;
        }
    }

    /**
     * @return True if the <code>firstDate</code> is the same as the <code>
     * secondDate</code>.
     */
    private static boolean isSameDate(Calendar firstDate, Calendar secondDate) {
        return (firstDate.get(Calendar.DAY_OF_YEAR) == secondDate.get(Calendar.DAY_OF_YEAR)
                && firstDate.get(Calendar.YEAR) == secondDate.get(Calendar.YEAR));
    }

    /**
     * Creates a new adapter if necessary and sets up its parameters.
     */
    private void setUpAdapter() {
        if (mAdapter == null) {
            mAdapter = new WeeksAdapter(mContext);
            mAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    if (mOnDateChangeListener != null) {
                        Calendar selectedDay = mAdapter.getSelectedDay();
                        mOnDateChangeListener.onSelectedDayChange(mDelegator,
                                selectedDay.get(Calendar.YEAR),
                                selectedDay.get(Calendar.MONTH),
                                selectedDay.get(Calendar.DAY_OF_MONTH));
                    }
                }
            });
            mListView.setAdapter(mAdapter);
        }

        // refresh the view with the new parameters
        mAdapter.notifyDataSetChanged();
    }

    /**
     * Sets up the strings to be used by the header.
     */
    private void setUpHeader() {
        mDayNamesShort = new String[mDaysPerWeek];
        mDayNamesLong = new String[mDaysPerWeek];
        for (int i = mFirstDayOfWeek, count = mFirstDayOfWeek + mDaysPerWeek; i < count; i++) {
            int calendarDay = (i > Calendar.SATURDAY) ? i - Calendar.SATURDAY : i;
            mDayNamesShort[i - mFirstDayOfWeek] = DateUtils.getDayOfWeekString(calendarDay,
                    DateUtils.LENGTH_SHORTEST);
            mDayNamesLong[i - mFirstDayOfWeek] = DateUtils.getDayOfWeekString(calendarDay,
                    DateUtils.LENGTH_LONG);
        }

        TextView label = (TextView) mDayNamesHeader.getChildAt(0);
        if (mShowWeekNumber) {
            label.setVisibility(View.VISIBLE);
        } else {
            label.setVisibility(View.GONE);
        }
        for (int i = 1, count = mDayNamesHeader.getChildCount(); i < count; i++) {
            label = (TextView) mDayNamesHeader.getChildAt(i);
            if (mWeekDayTextAppearanceResId > -1) {
                label.setTextAppearance(mWeekDayTextAppearanceResId);
            }
            if (i < mDaysPerWeek + 1) {
                label.setText(mDayNamesShort[i - 1]);
                label.setContentDescription(mDayNamesLong[i - 1]);
                label.setVisibility(View.VISIBLE);
            } else {
                label.setVisibility(View.GONE);
            }
        }
        mDayNamesHeader.invalidate();
    }

    /**
     * Sets all the required fields for the list view.
     */
    private void setUpListView() {
        // Configure the listview
        mListView.setDivider(null);
        mListView.setItemsCanFocus(true);
        mListView.setVerticalScrollBarEnabled(false);
        mListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                CalendarViewLegacyDelegate.this.onScrollStateChanged(view, scrollState);
            }

            public void onScroll(
                    AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
                CalendarViewLegacyDelegate.this.onScroll(view, firstVisibleItem,
                        visibleItemCount, totalItemCount);
            }
        });
        // Make the scrolling behavior nicer
        mListView.setFriction(mFriction);
        mListView.setVelocityScale(mVelocityScale);
    }

    /**
     * This moves to the specified time in the view. If the time is not already
     * in range it will move the list so that the first of the month containing
     * the time is at the top of the view. If the new time is already in view
     * the list will not be scrolled unless forceScroll is true. This time may
     * optionally be highlighted as selected as well.
     *
     * @param date The time to move to.
     * @param animate Whether to scroll to the given time or just redraw at the
     *            new location.
     * @param setSelected Whether to set the given time as selected.
     * @param forceScroll Whether to recenter even if the time is already
     *            visible.
     *
     * @throws IllegalArgumentException if the provided date is before the
     *         range start or after the range end.
     */
    private void goTo(Calendar date, boolean animate, boolean setSelected,
            boolean forceScroll) {
        if (date.before(mMinDate) || date.after(mMaxDate)) {
            throw new IllegalArgumentException("timeInMillis must be between the values of "
                    + "getMinDate() and getMaxDate()");
        }
        // Find the first and last entirely visible weeks
        int firstFullyVisiblePosition = mListView.getFirstVisiblePosition();
        View firstChild = mListView.getChildAt(0);
        if (firstChild != null && firstChild.getTop() < 0) {
            firstFullyVisiblePosition++;
        }
        int lastFullyVisiblePosition = firstFullyVisiblePosition + mShownWeekCount - 1;
        if (firstChild != null && firstChild.getTop() > mBottomBuffer) {
            lastFullyVisiblePosition--;
        }
        if (setSelected) {
            mAdapter.setSelectedDay(date);
        }
        // Get the week we're going to
        int position = getWeeksSinceMinDate(date);

        // Check if the selected day is now outside of our visible range
        // and if so scroll to the month that contains it
        if (position < firstFullyVisiblePosition || position > lastFullyVisiblePosition
                || forceScroll) {
            mFirstDayOfMonth.setTimeInMillis(date.getTimeInMillis());
            mFirstDayOfMonth.set(Calendar.DAY_OF_MONTH, 1);

            setMonthDisplayed(mFirstDayOfMonth);

            // the earliest time we can scroll to is the min date
            if (mFirstDayOfMonth.before(mMinDate)) {
                position = 0;
            } else {
                position = getWeeksSinceMinDate(mFirstDayOfMonth);
            }

            mPreviousScrollState = AbsListView.OnScrollListener.SCROLL_STATE_FLING;
            if (animate) {
                mListView.smoothScrollToPositionFromTop(position, mListScrollTopOffset,
                        GOTO_SCROLL_DURATION);
            } else {
                mListView.setSelectionFromTop(position, mListScrollTopOffset);
                // Perform any after scroll operations that are needed
                onScrollStateChanged(mListView, AbsListView.OnScrollListener.SCROLL_STATE_IDLE);
            }
        } else if (setSelected) {
            // Otherwise just set the selection
            setMonthDisplayed(date);
        }
    }

    /**
     * Called when a <code>view</code> transitions to a new <code>scrollState
     * </code>.
     */
    private void onScrollStateChanged(AbsListView view, int scrollState) {
        mScrollStateChangedRunnable.doScrollStateChange(view, scrollState);
    }

    /**
     * Updates the title and selected month if the <code>view</code> has moved to a new
     * month.
     */
    private void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                          int totalItemCount) {
        WeekView child = (WeekView) view.getChildAt(0);
        if (child == null) {
            return;
        }

        // Figure out where we are
        long currScroll =
                view.getFirstVisiblePosition() * child.getHeight() - child.getBottom();

        // If we have moved since our last call update the direction
        if (currScroll < mPreviousScrollPosition) {
            mIsScrollingUp = true;
        } else if (currScroll > mPreviousScrollPosition) {
            mIsScrollingUp = false;
        } else {
            return;
        }

        // Use some hysteresis for checking which month to highlight. This
        // causes the month to transition when two full weeks of a month are
        // visible when scrolling up, and when the first day in a month reaches
        // the top of the screen when scrolling down.
        int offset = child.getBottom() < mWeekMinVisibleHeight ? 1 : 0;
        if (mIsScrollingUp) {
            child = (WeekView) view.getChildAt(SCROLL_HYST_WEEKS + offset);
        } else if (offset != 0) {
            child = (WeekView) view.getChildAt(offset);
        }

        if (child != null) {
            // Find out which month we're moving into
            int month;
            if (mIsScrollingUp) {
                month = child.getMonthOfFirstWeekDay();
            } else {
                month = child.getMonthOfLastWeekDay();
            }

            // And how it relates to our current highlighted month
            int monthDiff;
            if (mCurrentMonthDisplayed == 11 && month == 0) {
                monthDiff = 1;
            } else if (mCurrentMonthDisplayed == 0 && month == 11) {
                monthDiff = -1;
            } else {
                monthDiff = month - mCurrentMonthDisplayed;
            }

            // Only switch months if we're scrolling away from the currently
            // selected month
            if ((!mIsScrollingUp && monthDiff > 0) || (mIsScrollingUp && monthDiff < 0)) {
                Calendar firstDay = child.getFirstDay();
                if (mIsScrollingUp) {
                    firstDay.add(Calendar.DAY_OF_MONTH, -DAYS_PER_WEEK);
                } else {
                    firstDay.add(Calendar.DAY_OF_MONTH, DAYS_PER_WEEK);
                }
                setMonthDisplayed(firstDay);
            }
        }
        mPreviousScrollPosition = currScroll;
        mPreviousScrollState = mCurrentScrollState;
    }

    /**
     * Sets the month displayed at the top of this view based on time. Override
     * to add custom events when the title is changed.
     *
     * @param calendar A day in the new focus month.
     */
    private void setMonthDisplayed(Calendar calendar) {
        mCurrentMonthDisplayed = calendar.get(Calendar.MONTH);
        mAdapter.setFocusMonth(mCurrentMonthDisplayed);
        final int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_MONTH_DAY
                | DateUtils.FORMAT_SHOW_YEAR;
        final long millis = calendar.getTimeInMillis();
        String newMonthName = DateUtils.formatDateRange(mContext, millis, millis, flags);
        mMonthName.setText(newMonthName);
        mMonthName.invalidate();
    }

    /**
     * @return Returns the number of weeks between the current <code>date</code>
     *         and the <code>mMinDate</code>.
     */
    private int getWeeksSinceMinDate(Calendar date) {
        if (date.before(mMinDate)) {
            throw new IllegalArgumentException("fromDate: " + mMinDate.getTime()
                    + " does not precede toDate: " + date.getTime());
        }
        long endTimeMillis = date.getTimeInMillis()
                + date.getTimeZone().getOffset(date.getTimeInMillis());
        long startTimeMillis = mMinDate.getTimeInMillis()
                + mMinDate.getTimeZone().getOffset(mMinDate.getTimeInMillis());
        long dayOffsetMillis = (mMinDate.get(Calendar.DAY_OF_WEEK) - mFirstDayOfWeek)
                * MILLIS_IN_DAY;
        return (int) ((endTimeMillis - startTimeMillis + dayOffsetMillis) / MILLIS_IN_WEEK);
    }

    /**
     * Command responsible for acting upon scroll state changes.
     */
    private class ScrollStateRunnable implements Runnable {
        private AbsListView mView;

        private int mNewState;

        /**
         * Sets up the runnable with a short delay in case the scroll state
         * immediately changes again.
         *
         * @param view The list view that changed state
         * @param scrollState The new state it changed to
         */
        public void doScrollStateChange(AbsListView view, int scrollState) {
            mView = view;
            mNewState = scrollState;
            mDelegator.removeCallbacks(this);
            mDelegator.postDelayed(this, SCROLL_CHANGE_DELAY);
        }

        public void run() {
            mCurrentScrollState = mNewState;
            // Fix the position after a scroll or a fling ends
            if (mNewState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                    && mPreviousScrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                View child = mView.getChildAt(0);
                if (child == null) {
                    // The view is no longer visible, just return
                    return;
                }
                int dist = child.getBottom() - mListScrollTopOffset;
                if (dist > mListScrollTopOffset) {
                    if (mIsScrollingUp) {
                        mView.smoothScrollBy(dist - child.getHeight(),
                                ADJUSTMENT_SCROLL_DURATION);
                    } else {
                        mView.smoothScrollBy(dist, ADJUSTMENT_SCROLL_DURATION);
                    }
                }
            }
            mPreviousScrollState = mNewState;
        }
    }

    /**
     * <p>
     * This is a specialized adapter for creating a list of weeks with
     * selectable days. It can be configured to display the week number, start
     * the week on a given day, show a reduced number of days, or display an
     * arbitrary number of weeks at a time.
     * </p>
     */
    private class WeeksAdapter extends BaseAdapter implements View.OnTouchListener {

        private int mSelectedWeek;

        private GestureDetector mGestureDetector;

        private int mFocusedMonth;

        private final Calendar mSelectedDate = Calendar.getInstance();

        private int mTotalWeekCount;

        public WeeksAdapter(Context context) {
            mContext = context;
            mGestureDetector = new GestureDetector(mContext, new WeeksAdapter.CalendarGestureListener());
            init();
        }

        /**
         * Set up the gesture detector and selected time
         */
        private void init() {
            mSelectedWeek = getWeeksSinceMinDate(mSelectedDate);
            mTotalWeekCount = getWeeksSinceMinDate(mMaxDate);
            if (mMinDate.get(Calendar.DAY_OF_WEEK) != mFirstDayOfWeek
                    || mMaxDate.get(Calendar.DAY_OF_WEEK) != mFirstDayOfWeek) {
                mTotalWeekCount++;
            }
            notifyDataSetChanged();
        }

        /**
         * Updates the selected day and related parameters.
         *
         * @param selectedDay The time to highlight
         */
        public void setSelectedDay(Calendar selectedDay) {
            if (selectedDay.get(Calendar.DAY_OF_YEAR) == mSelectedDate.get(Calendar.DAY_OF_YEAR)
                    && selectedDay.get(Calendar.YEAR) == mSelectedDate.get(Calendar.YEAR)) {
                return;
            }
            mSelectedDate.setTimeInMillis(selectedDay.getTimeInMillis());
            mSelectedWeek = getWeeksSinceMinDate(mSelectedDate);
            mFocusedMonth = mSelectedDate.get(Calendar.MONTH);
            notifyDataSetChanged();
        }

        /**
         * @return The selected day of month.
         */
        public Calendar getSelectedDay() {
            return mSelectedDate;
        }

        @Override
        public int getCount() {
            return mTotalWeekCount;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            WeekView weekView = null;
            if (convertView != null) {
                weekView = (WeekView) convertView;
            } else {
                weekView = new WeekView(mContext);
                AbsListView.LayoutParams params =
                        new AbsListView.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT);
                weekView.setLayoutParams(params);
                weekView.setClickable(true);
                weekView.setOnTouchListener(this);
            }

            int selectedWeekDay = (mSelectedWeek == position) ? mSelectedDate.get(
                    Calendar.DAY_OF_WEEK) : -1;
            weekView.init(position, selectedWeekDay, mFocusedMonth);

            return weekView;
        }

        /**
         * Changes which month is in focus and updates the view.
         *
         * @param month The month to show as in focus [0-11]
         */
        public void setFocusMonth(int month) {
            if (mFocusedMonth == month) {
                return;
            }
            mFocusedMonth = month;
            notifyDataSetChanged();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mListView.isEnabled() && mGestureDetector.onTouchEvent(event)) {
                WeekView weekView = (WeekView) v;
                // if we cannot find a day for the given location we are done
                if (!weekView.getDayFromLocation(event.getX(), mTempDate)) {
                    return true;
                }
                // it is possible that the touched day is outside the valid range
                // we draw whole weeks but range end can fall not on the week end
                if (mTempDate.before(mMinDate) || mTempDate.after(mMaxDate)) {
                    return true;
                }
                onDateTapped(mTempDate);
                return true;
            }
            return false;
        }

        /**
         * Maintains the same hour/min/sec but moves the day to the tapped day.
         *
         * @param day The day that was tapped
         */
        private void onDateTapped(Calendar day) {
            setSelectedDay(day);
            setMonthDisplayed(day);
        }

        /**
         * This is here so we can identify single tap events and set the
         * selected day correctly
         */
        class CalendarGestureListener extends GestureDetector.SimpleOnGestureListener {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                return true;
            }
        }
    }

    /**
     * <p>
     * This is a dynamic view for drawing a single week. It can be configured to
     * display the week number, start the week on a given day, or show a reduced
     * number of days. It is intended for use as a single view within a
     * ListView. See {@link WeeksAdapter} for usage.
     * </p>
     */
    private class WeekView extends View {

        private final Rect mTempRect = new Rect();

        private final Paint mDrawPaint = new Paint();

        private final Paint mMonthNumDrawPaint = new Paint();

        // Cache the number strings so we don't have to recompute them each time
        private String[] mDayNumbers;

        // Quick lookup for checking which days are in the focus month
        private boolean[] mFocusDay;

        // Whether this view has a focused day.
        private boolean mHasFocusedDay;

        // Whether this view has only focused days.
        private boolean mHasUnfocusedDay;

        // The first day displayed by this item
        private Calendar mFirstDay;

        // The month of the first day in this week
        private int mMonthOfFirstWeekDay = -1;

        // The month of the last day in this week
        private int mLastWeekDayMonth = -1;

        // The position of this week, equivalent to weeks since the week of Jan
        // 1st, 1900
        private int mWeek = -1;

        // Quick reference to the width of this view, matches parent
        private int mWidth;

        // The height this view should draw at in pixels, set by height param
        private int mHeight;

        // If this view contains the selected day
        private boolean mHasSelectedDay = false;

        // Which day is selected [0-6] or -1 if no day is selected
        private int mSelectedDay = -1;

        // The number of days + a spot for week number if it is displayed
        private int mNumCells;

        // The left edge of the selected day
        private int mSelectedLeft = -1;

        // The right edge of the selected day
        private int mSelectedRight = -1;

        public WeekView(Context context) {
            super(context);

            // Sets up any standard paints that will be used
            initializePaints();
        }

        /**
         * Initializes this week view.
         *
         * @param weekNumber The number of the week this view represents. The
         *            week number is a zero based index of the weeks since
         *            {@link android.widget.CalendarView#getMinDate()}.
         * @param selectedWeekDay The selected day of the week from 0 to 6, -1 if no
         *            selected day.
         * @param focusedMonth The month that is currently in focus i.e.
         *            highlighted.
         */
        public void init(int weekNumber, int selectedWeekDay, int focusedMonth) {
            mSelectedDay = selectedWeekDay;
            mHasSelectedDay = mSelectedDay != -1;
            mNumCells = mShowWeekNumber ? mDaysPerWeek + 1 : mDaysPerWeek;
            mWeek = weekNumber;
            mTempDate.setTimeInMillis(mMinDate.getTimeInMillis());

            mTempDate.add(Calendar.WEEK_OF_YEAR, mWeek);
            mTempDate.setFirstDayOfWeek(mFirstDayOfWeek);

            // Allocate space for caching the day numbers and focus values
            mDayNumbers = new String[mNumCells];
            mFocusDay = new boolean[mNumCells];

            // If we're showing the week number calculate it based on Monday
            int i = 0;
            if (mShowWeekNumber) {
                mDayNumbers[0] = String.format(Locale.getDefault(), "%d",
                        mTempDate.get(Calendar.WEEK_OF_YEAR));
                i++;
            }

            // Now adjust our starting day based on the start day of the week
            int diff = mFirstDayOfWeek - mTempDate.get(Calendar.DAY_OF_WEEK);
            mTempDate.add(Calendar.DAY_OF_MONTH, diff);

            mFirstDay = (Calendar) mTempDate.clone();
            mMonthOfFirstWeekDay = mTempDate.get(Calendar.MONTH);

            mHasUnfocusedDay = true;
            for (; i < mNumCells; i++) {
                final boolean isFocusedDay = (mTempDate.get(Calendar.MONTH) == focusedMonth);
                mFocusDay[i] = isFocusedDay;
                mHasFocusedDay |= isFocusedDay;
                mHasUnfocusedDay &= !isFocusedDay;
                // do not draw dates outside the valid range to avoid user confusion
                if (mTempDate.before(mMinDate) || mTempDate.after(mMaxDate)) {
                    mDayNumbers[i] = "";
                } else {
                    mDayNumbers[i] = String.format(Locale.getDefault(), "%d",
                            mTempDate.get(Calendar.DAY_OF_MONTH));
                }
                mTempDate.add(Calendar.DAY_OF_MONTH, 1);
            }
            // We do one extra add at the end of the loop, if that pushed us to
            // new month undo it
            if (mTempDate.get(Calendar.DAY_OF_MONTH) == 1) {
                mTempDate.add(Calendar.DAY_OF_MONTH, -1);
            }
            mLastWeekDayMonth = mTempDate.get(Calendar.MONTH);

            updateSelectionPositions();
        }

        /**
         * Initialize the paint instances.
         */
        private void initializePaints() {
            mDrawPaint.setFakeBoldText(false);
            mDrawPaint.setAntiAlias(true);
            mDrawPaint.setStyle(Paint.Style.FILL);

            mMonthNumDrawPaint.setFakeBoldText(true);
            mMonthNumDrawPaint.setAntiAlias(true);
            mMonthNumDrawPaint.setStyle(Paint.Style.FILL);
            mMonthNumDrawPaint.setTextAlign(Paint.Align.CENTER);
            mMonthNumDrawPaint.setTextSize(mDateTextSize);
        }

        /**
         * Returns the month of the first day in this week.
         *
         * @return The month the first day of this view is in.
         */
        public int getMonthOfFirstWeekDay() {
            return mMonthOfFirstWeekDay;
        }

        /**
         * Returns the month of the last day in this week
         *
         * @return The month the last day of this view is in
         */
        public int getMonthOfLastWeekDay() {
            return mLastWeekDayMonth;
        }

        /**
         * Returns the first day in this view.
         *
         * @return The first day in the view.
         */
        public Calendar getFirstDay() {
            return mFirstDay;
        }

        /**
         * Calculates the day that the given x position is in, accounting for
         * week number.
         *
         * @param x The x position of the touch event.
         * @return True if a day was found for the given location.
         */
        public boolean getDayFromLocation(float x, Calendar outCalendar) {
            final boolean isLayoutRtl = isLayoutRtl();

            int start;
            int end;

            if (isLayoutRtl) {
                start = 0;
                end = mShowWeekNumber ? mWidth - mWidth / mNumCells : mWidth;
            } else {
                start = mShowWeekNumber ? mWidth / mNumCells : 0;
                end = mWidth;
            }

            if (x < start || x > end) {
                outCalendar.clear();
                return false;
            }

            // Selection is (x - start) / (pixels/day) which is (x - start) * day / pixels
            int dayPosition = (int) ((x - start) * mDaysPerWeek / (end - start));

            if (isLayoutRtl) {
                dayPosition = mDaysPerWeek - 1 - dayPosition;
            }

            outCalendar.setTimeInMillis(mFirstDay.getTimeInMillis());
            outCalendar.add(Calendar.DAY_OF_MONTH, dayPosition);

            return true;
        }

        public boolean getBoundsForDate(Calendar date, Rect outBounds) {
            Calendar currDay = Calendar.getInstance();
            currDay.setTime(mFirstDay.getTime());
            for (int i = 0; i < mDaysPerWeek; i++) {
                if ((date.get(Calendar.YEAR) == currDay.get(Calendar.YEAR))
                    && (date.get(Calendar.MONTH) == currDay.get(Calendar.MONTH))
                    && (date.get(Calendar.DAY_OF_MONTH) == currDay.get(Calendar.DAY_OF_MONTH))) {
                    // We found the matching date. Follow the logic in the draw pass that divides
                    // the available horizontal space equally between all the entries in this week.
                    // Note that if we're showing week number, the start entry will be that number.
                    int cellSize = mWidth / mNumCells;
                    if (isLayoutRtl()) {
                        outBounds.left = cellSize *
                                (mShowWeekNumber ? (mNumCells - i - 2) : (mNumCells - i - 1));
                    } else {
                        outBounds.left = cellSize * (mShowWeekNumber ? i + 1 : i);
                    }
                    outBounds.top = 0;
                    outBounds.right = outBounds.left + cellSize;
                    outBounds.bottom = getHeight();
                    return true;
                }
                // Add one day
                currDay.add(Calendar.DAY_OF_MONTH, 1);
            }
            return false;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawBackground(canvas);
            drawWeekNumbersAndDates(canvas);
            drawWeekSeparators(canvas);
            drawSelectedDateVerticalBars(canvas);
        }

        /**
         * This draws the selection highlight if a day is selected in this week.
         *
         * @param canvas The canvas to draw on
         */
        private void drawBackground(Canvas canvas) {
            if (!mHasSelectedDay) {
                return;
            }
            mDrawPaint.setColor(mSelectedWeekBackgroundColor);

            mTempRect.top = mWeekSeparatorLineWidth;
            mTempRect.bottom = mHeight;

            final boolean isLayoutRtl = isLayoutRtl();

            if (isLayoutRtl) {
                mTempRect.left = 0;
                mTempRect.right = mSelectedLeft - 2;
            } else {
                mTempRect.left = mShowWeekNumber ? mWidth / mNumCells : 0;
                mTempRect.right = mSelectedLeft - 2;
            }
            canvas.drawRect(mTempRect, mDrawPaint);

            if (isLayoutRtl) {
                mTempRect.left = mSelectedRight + 3;
                mTempRect.right = mShowWeekNumber ? mWidth - mWidth / mNumCells : mWidth;
            } else {
                mTempRect.left = mSelectedRight + 3;
                mTempRect.right = mWidth;
            }
            canvas.drawRect(mTempRect, mDrawPaint);
        }

        /**
         * Draws the week and month day numbers for this week.
         *
         * @param canvas The canvas to draw on
         */
        private void drawWeekNumbersAndDates(Canvas canvas) {
            final float textHeight = mDrawPaint.getTextSize();
            final int y = (int) ((mHeight + textHeight) / 2) - mWeekSeparatorLineWidth;
            final int nDays = mNumCells;
            final int divisor = 2 * nDays;

            mDrawPaint.setTextAlign(Paint.Align.CENTER);
            mDrawPaint.setTextSize(mDateTextSize);

            int i = 0;

            if (isLayoutRtl()) {
                for (; i < nDays - 1; i++) {
                    mMonthNumDrawPaint.setColor(mFocusDay[i] ? mFocusedMonthDateColor
                            : mUnfocusedMonthDateColor);
                    int x = (2 * i + 1) * mWidth / divisor;
                    canvas.drawText(mDayNumbers[nDays - 1 - i], x, y, mMonthNumDrawPaint);
                }
                if (mShowWeekNumber) {
                    mDrawPaint.setColor(mWeekNumberColor);
                    int x = mWidth - mWidth / divisor;
                    canvas.drawText(mDayNumbers[0], x, y, mDrawPaint);
                }
            } else {
                if (mShowWeekNumber) {
                    mDrawPaint.setColor(mWeekNumberColor);
                    int x = mWidth / divisor;
                    canvas.drawText(mDayNumbers[0], x, y, mDrawPaint);
                    i++;
                }
                for (; i < nDays; i++) {
                    mMonthNumDrawPaint.setColor(mFocusDay[i] ? mFocusedMonthDateColor
                            : mUnfocusedMonthDateColor);
                    int x = (2 * i + 1) * mWidth / divisor;
                    canvas.drawText(mDayNumbers[i], x, y, mMonthNumDrawPaint);
                }
            }
        }

        /**
         * Draws a horizontal line for separating the weeks.
         *
         * @param canvas The canvas to draw on.
         */
        private void drawWeekSeparators(Canvas canvas) {
            // If it is the topmost fully visible child do not draw separator line
            int firstFullyVisiblePosition = mListView.getFirstVisiblePosition();
            if (mListView.getChildAt(0).getTop() < 0) {
                firstFullyVisiblePosition++;
            }
            if (firstFullyVisiblePosition == mWeek) {
                return;
            }
            mDrawPaint.setColor(mWeekSeparatorLineColor);
            mDrawPaint.setStrokeWidth(mWeekSeparatorLineWidth);
            float startX;
            float stopX;
            if (isLayoutRtl()) {
                startX = 0;
                stopX = mShowWeekNumber ? mWidth - mWidth / mNumCells : mWidth;
            } else {
                startX = mShowWeekNumber ? mWidth / mNumCells : 0;
                stopX = mWidth;
            }
            canvas.drawLine(startX, 0, stopX, 0, mDrawPaint);
        }

        /**
         * Draws the selected date bars if this week has a selected day.
         *
         * @param canvas The canvas to draw on
         */
        private void drawSelectedDateVerticalBars(Canvas canvas) {
            if (!mHasSelectedDay) {
                return;
            }
            mSelectedDateVerticalBar.setBounds(
                    mSelectedLeft - mSelectedDateVerticalBarWidth / 2,
                    mWeekSeparatorLineWidth,
                    mSelectedLeft + mSelectedDateVerticalBarWidth / 2,
                    mHeight);
            mSelectedDateVerticalBar.draw(canvas);
            mSelectedDateVerticalBar.setBounds(
                    mSelectedRight - mSelectedDateVerticalBarWidth / 2,
                    mWeekSeparatorLineWidth,
                    mSelectedRight + mSelectedDateVerticalBarWidth / 2,
                    mHeight);
            mSelectedDateVerticalBar.draw(canvas);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            mWidth = w;
            updateSelectionPositions();
        }

        /**
         * This calculates the positions for the selected day lines.
         */
        private void updateSelectionPositions() {
            if (mHasSelectedDay) {
                final boolean isLayoutRtl = isLayoutRtl();
                int selectedPosition = mSelectedDay - mFirstDayOfWeek;
                if (selectedPosition < 0) {
                    selectedPosition += 7;
                }
                if (mShowWeekNumber && !isLayoutRtl) {
                    selectedPosition++;
                }
                if (isLayoutRtl) {
                    mSelectedLeft = (mDaysPerWeek - 1 - selectedPosition) * mWidth / mNumCells;

                } else {
                    mSelectedLeft = selectedPosition * mWidth / mNumCells;
                }
                mSelectedRight = mSelectedLeft + mWidth / mNumCells;
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            mHeight = (mListView.getHeight() - mListView.getPaddingTop() - mListView
                    .getPaddingBottom()) / mShownWeekCount;
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), mHeight);
        }
    }

}
