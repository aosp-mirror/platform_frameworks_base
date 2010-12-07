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

import com.android.internal.R;

import android.app.Service;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView.OnScrollListener;

import java.security.InvalidParameterException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;

import libcore.icu.LocaleData;

/**
 * Displays a day picker in the form of a calendar. The calendar
 * is represented as a list where each row depicts a week. Each week is
 * composed of items that are selectable days.
 */
public class DayPicker extends FrameLayout {

    /**
     * The number of milliseconds in a day.
     *
     * @hide
     */
    protected static final long MILLIS_IN_DAY = 86400000L;

    /**
     * The number of day in a week.
     *
     * @hide
     */
    protected static final int DAYS_PER_WEEK = 7;

    /**
     * The number of milliseconds in a week.
     *
     * @hide
     */
    protected static final long MILLIS_IN_WEEK = DAYS_PER_WEEK * MILLIS_IN_DAY;

    /**
     * Affects when the month selection will change while scrolling up
     *
     * @hide
     */ 
    protected static final int SCROLL_HYST_WEEKS = 2;

    /**
     * How long the GoTo fling animation should last.
     *
     * @hide
     */
    protected static final int GOTO_SCROLL_DURATION = 1000;

    /**
     * The duration of the adjustment upon a user scroll in milliseconds.
     *
     * @hide
     */
    protected static final int ADJUSTMENT_SCROLL_DURATION = 500;

    /**
     * How long to wait after receiving an onScrollStateChanged notification
     * before acting on it.
     *
     * @hide
     */
    protected static final int SCROLL_CHANGE_DELAY = 40;

    /**
     * The scale used to compensate for different screen density.
     *
     * @hide
     */
    protected static float sScale;

    /**
     * The top offset of the weeks list.
     *
     * @hide
     */
    protected static int mListTopOffset = 2;

    /**
     * The visible height of a week view.
     *
     * @hide
     */
    protected int mWeekMinVisibleHeight = 12;


    /**
     * The visible height of a week view.
     *
     * @hide
     */
    protected int mBottomBuffer = 20;

    /**
     * The number of shown weeks.
     *
     * @hide
     */
    protected int mShownWeekCount = 6;

    /**
     * Flag whether to show the week number.
     *
     * @hide
     */
    protected boolean mShowWeekNumber = true;

    /**
     * The number of day per week to be shown
     *
     * @hide
     */
    protected int mDaysPerWeek = 7;

    /**
     * The friction of the week list while flinging.
     *
     * @hide
     */
    protected float mFriction = .05f;

    /**
     * Scale for adjusting velocity of the week list while flinging.
     *
     * @hide
     */
    protected float mVelocityScale = 0.333f;

    /**
     * The adapter for the weeks list.
     *
     * @hide
     */
    protected WeeksAdapter mAdapter;

    /**
     * The weeks list.
     *
     * @hide
     */
    protected ListView mListView;

    /**
     * The name of the month to display.
     *
     * @hide
     */
    protected TextView mMonthName;

    /**
     * The header with week day names.
     *
     * @hide
     */
    protected ViewGroup mDayNamesHeader;

    /**
     * Cached labels for the week names header.
     *
     * @hide
     */
    protected String[] mDayLabels;

    /**
     * Temporary instance to avoid multiple instantiations.
     *
     * @hide
     */
    protected Calendar mTempCalendar = Calendar.getInstance();

    /**
     * The first day of the week based on the current locale.
     *
     * @hide
     */
    protected int mFirstDayOfWeek = LocaleData.get(Locale.getDefault()).firstDayOfWeek;

    /**
     * The first day of the focused month.
     *
     * @hide
     */
    protected Calendar mFirstDayOfMonth = Calendar.getInstance();

    /**
     * Which month should be displayed/highlighted [0-11]
     *
     * @hide
     */
    protected int mCurrentMonthDisplayed;

    /**
     * Used for tracking during a scroll.
     *
     * @hide
     */
    protected long mPreviousScrollPosition;

    /**
     * Used for tracking which direction the view is scrolling.
     *
     * @hide
     */
    protected boolean mIsScrollingUp = false;

    /**
     * The previous scroll state of the weeks ListView.
     *
     * @hide
     */
    protected int mPreviousScrollState = OnScrollListener.SCROLL_STATE_IDLE;

    /**
     * The current scroll state of the weeks ListView.
     *
     * @hide
     */
    protected int mCurrentScrollState = OnScrollListener.SCROLL_STATE_IDLE;

    /**
     * Listener for changes in the selected day.
     *
     * @hide
     */
    protected OnSelectedDayChangeListener mOnChangeListener;

    /**
     * Command for adjusting the position after a scroll/fling.
     *
     * @hide
     */
    protected ScrollStateRunnable mScrollStateChangedRunnable = new ScrollStateRunnable();

    /**
     * The start date of the range supported by this picker.
     *
     * @hide
     */
    protected Calendar mStartRangeDate = Calendar.getInstance();

    /**
     * The end date of the range supported by this picker.
     *
     * @hide
     */
    protected Calendar mEndRangeDate = Calendar.getInstance();

    /**
     * String for formatting the month name in the title text view.
     *
     * @hide
     */
    protected String mMonthNameFormatSrting = "MMMM, yyyy";

    /**
     * The callback used to indicate the user changes the date.
     */
    public interface OnSelectedDayChangeListener {

        /**
         * Called upon change of the selected day.
         *
         * @param view The view associated with this listener.
         * @param year The year that was set.
         * @param month The month that was set [0-11].
         * @param dayOfMonth The day of the month that was set.
         */
        public void onSelectedDayChange(DayPicker view, int year, int month, int dayOfMonth);
    }

    public DayPicker(Context context) {
        this(context, null);
    }

    public DayPicker(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DayPicker(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, 0);

        LayoutInflater layoutInflater = (LayoutInflater) mContext
                .getSystemService(Service.LAYOUT_INFLATER_SERVICE);
        View content = layoutInflater.inflate(R.layout.day_picker, null, false);
        addView(content);

        mListView = (ListView) findViewById(R.id.list);
        mDayNamesHeader = (ViewGroup) content.findViewById(com.android.internal.R.id.day_names);
        mMonthName = (TextView) content.findViewById(com.android.internal.R.id.month_name);

        // Adjust sizes for screen density
        if (sScale == 0) {
            sScale = mContext.getResources().getDisplayMetrics().density;
            if (sScale != 1) {
                mWeekMinVisibleHeight *= sScale;
                mBottomBuffer *= sScale;
                mListTopOffset *= sScale;
            }
        }

        // set default range
        mStartRangeDate.clear();
        mStartRangeDate.set(1900, 0, 1);
        mEndRangeDate.clear();
        mEndRangeDate.set(2100, 0, 1);

        setUpHeader();
        updateHeader();
        setUpListView();
        setUpAdapter();

        // go to today now
        mTempCalendar.setTimeInMillis(System.currentTimeMillis());
        goTo(mTempCalendar, false, true, true);
        invalidate();
    }

    /**
     * Sets the range supported by this day picker. This is the picker will not
     * support dates before <code>startRangeDate</code> and <code>endRangeDate
     * </code>.
     *
     * @param startRangeDate The start date.
     * @param endRangeDate The end date.
     */
    public void setRange(Calendar startRangeDate, Calendar endRangeDate) {
        boolean doSetupAdapter = false;
        if (mStartRangeDate.get(Calendar.DAY_OF_YEAR) != startRangeDate.get(Calendar.DAY_OF_YEAR)
                || mStartRangeDate.get(Calendar.YEAR) != startRangeDate.get(Calendar.YEAR)) {
            mStartRangeDate = startRangeDate;
            doSetupAdapter = true;
        }
        if (mEndRangeDate.get(Calendar.DAY_OF_YEAR) != endRangeDate.get(Calendar.DAY_OF_YEAR)
                || mEndRangeDate.get(Calendar.YEAR) != endRangeDate.get(Calendar.YEAR)) {
            mEndRangeDate = endRangeDate;
            doSetupAdapter = true;
            
        }
        if (doSetupAdapter) {
            setUpAdapter();
        }
    }

    /**
     * Sets the listener to be notified upon day selection changes.
     *
     * @param listener The listener to be called back.
     */
    public void setOnDateChangeListener(OnSelectedDayChangeListener listener) {
        mOnChangeListener = listener;
    }

    /**
     * Gets the selected day.
     *
     * @return The selected day.
     */
    public Calendar getSelectedDay() {
        return mAdapter.mSelectedDay;
    }

    /**
     * Sets the selected day. This is equivalent to a call to
     * {@link #goTo(Calendar, boolean, boolean, boolean)} with
     * the arguments <code>selectedDay</code>, <code>false</code>,
     * <code>true</code>, <code>false</code> respectively.
     *
     * @param selectedDay The selected day.
     */
    public void setSelectedDay(Calendar selectedDay) {
        goTo(selectedDay, false, true, false);
    }

    /**
     * Creates a new adapter if necessary and sets up its parameters. Override
     * this method to provide a custom adapter.
     *
     * @hide
     */
    protected void setUpAdapter() {
        if (mAdapter == null) {
            mAdapter = new WeeksAdapter(getContext());
            mAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    if (mOnChangeListener != null) {
                        Calendar selectedDay = mAdapter.getSelectedDay();
                        mOnChangeListener.onSelectedDayChange(DayPicker.this,
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
     * Sets up the strings to be used by the header. Override this method to use
     * different strings or modify the view params.
     *
     * @hide
     */
    protected void setUpHeader() {
        mDayLabels = new String[mDaysPerWeek];
        for (int i = mFirstDayOfWeek, count = mFirstDayOfWeek + mDaysPerWeek; i < count; i++) {
            int calendarDay = (i < mDaysPerWeek) ? i : 1; // Calendar.MONDAY is
            // 1
            mDayLabels[i - mFirstDayOfWeek] = DateUtils.getDayOfWeekString(calendarDay,
                    DateUtils.LENGTH_SHORTEST);
        }
    }

    /**
     * Sets all the required fields for the list view. Override this method to
     * set a different list view behavior.
     *
     * @hide
     */
    protected void setUpListView() {
        // Configure the listview
        mListView.setDivider(null);
        mListView.setItemsCanFocus(true);
        mListView.setVerticalScrollBarEnabled(false);
        mListView.setOnScrollListener(new OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                DayPicker.this.onScrollStateChanged(view, scrollState);
            }

            public void onScroll(
                    AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
                DayPicker.this.onScroll(view, firstVisibleItem, visibleItemCount, totalItemCount);
            }
        });
        // Make the scrolling behavior nicer
        mListView.setFriction(mFriction);
        mListView.setVelocityScale(mVelocityScale);
    }

    /**
     * Fixes the day names header to provide correct spacing and updates the
     * label text. Override this to set up a custom header.
     *
     * @hide
     */
    protected void updateHeader() {
        TextView label = (TextView) mDayNamesHeader.getChildAt(0);
        if (mShowWeekNumber) {
            label.setVisibility(View.VISIBLE);
        } else {
            label.setVisibility(View.GONE);
        }
        for (int i = 1, count = mDayNamesHeader.getChildCount(); i < count; i++) {
            label = (TextView) mDayNamesHeader.getChildAt(i);
            if (i < mDaysPerWeek + 1) {
                label.setText(mDayLabels[i - 1]);
                label.setVisibility(View.VISIBLE);
            } else {
                label.setVisibility(View.GONE);
            }
        }
        mDayNamesHeader.invalidate();
    }

    /**
     * This moves to the specified time in the view. If the time is not already
     * in range it will move the list so that the first of the month containing
     * the time is at the top of the view. If the new time is already in view
     * the list will not be scrolled unless forceScroll is true. This time may
     * optionally be highlighted as selected as well.
     *
     * @param year The year to move to.
     * @param month The month to move to <strong>starting from zero<strong>.
     * @param dayOfMonth The month day to move to.
     * @param animate Whether to scroll to the given time or just redraw at the
     *            new location.
     * @param setSelected Whether to set the given time as selected
     * @param forceScroll Whether to recenter even if the time is already
     *            visible.
     */
    public void goTo(int year, int month, int dayOfMonth, boolean animate, boolean setSelected,
            boolean forceScroll) {
        mTempCalendar.clear();
        mTempCalendar.set(year, month, dayOfMonth);
        goTo(mTempCalendar, animate, setSelected, forceScroll);
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
     */
    public void goTo(Calendar date, boolean animate, boolean setSelected, boolean forceScroll) {
        long timeInMillis = date.getTimeInMillis();
        if (timeInMillis < mStartRangeDate.getTimeInMillis()
                || timeInMillis > mEndRangeDate.getTimeInMillis()) {
            throw new IllegalArgumentException("Time not between " + mStartRangeDate.getTime()
                    + " and " + mEndRangeDate.getTime());
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
        int position = getWeeksDelta(date);

        // Check if the selected day is now outside of our visible range
        // and if so scroll to the month that contains it
        if (position < firstFullyVisiblePosition || position > lastFullyVisiblePosition
                || forceScroll) {
            mFirstDayOfMonth.setTimeInMillis(date.getTimeInMillis());
            mFirstDayOfMonth.setTimeZone(date.getTimeZone());
            mFirstDayOfMonth.set(Calendar.DAY_OF_MONTH, 1);

            setMonthDisplayed(mFirstDayOfMonth);
            position = getWeeksDelta(mFirstDayOfMonth);

            mPreviousScrollState = OnScrollListener.SCROLL_STATE_FLING;
            if (animate) {
                mListView.smoothScrollToPositionFromTop(position, mListTopOffset,
                        GOTO_SCROLL_DURATION);
            } else {
                mListView.setSelectionFromTop(position, mListTopOffset);
                // Perform any after scroll operations that are needed
                onScrollStateChanged(mListView, OnScrollListener.SCROLL_STATE_IDLE);
            }
        } else if (setSelected) {
            // Otherwise just set the selection
            setMonthDisplayed(date);
        }
    }

    /**
     * Called when a <code>view</code> transitions to a new <code>scrollState
     * </code>.
     *
     * @hide
     */
    protected void onScrollStateChanged(AbsListView view, int scrollState) {
        mScrollStateChangedRunnable.doScrollStateChange(view, scrollState);
    }

    /**
     * Updates the title and selected month if the <code>view</code> has moved to a new
     * month.
     *
     * @hide
     */
    protected void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
            int totalItemCount) {
        WeekView child = (WeekView) view.getChildAt(0);
        if (child == null) {
            return;
        }

        // Figure out where we are
        int offset = child.getBottom() < mWeekMinVisibleHeight ? 1 : 0;
        long currScroll = view.getFirstVisiblePosition() * child.getHeight() - child.getBottom();

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
        if (mIsScrollingUp) {
            child = (WeekView) view.getChildAt(SCROLL_HYST_WEEKS + offset);
        } else if (offset != 0) {
            child = (WeekView) view.getChildAt(offset);
        }

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
        mPreviousScrollPosition = currScroll;
        mPreviousScrollState = mCurrentScrollState;
    }

    /**
     * Sets the month displayed at the top of this view based on time. Override
     * to add custom events when the title is changed.
     *
     * @param calendar A day in the new focus month.
     *
     * @hide
     */
    protected void setMonthDisplayed(Calendar calendar) {
        mMonthName.setText(DateFormat.format(mMonthNameFormatSrting, calendar));
        mMonthName.invalidate();
        mCurrentMonthDisplayed = calendar.get(Calendar.MONTH);
        mAdapter.setFocusMonth(mCurrentMonthDisplayed);
        // TODO Send Accessibility Event
    }

    /**
     * @return Returns the number of weeks between the current week day of the
     *         <code>fromDate</code> and the first day of week of
     *         <code>toDate</code>.
     *
     * @hide
     */
    protected int getWeeksDelta(Calendar toDate) {
        if (toDate.before(mStartRangeDate)) {
            throw new IllegalArgumentException("fromDate: " + mStartRangeDate.getTime()
                    + " does not precede toDate: " + toDate.getTime());
        }
        int fromDateDayOfWeek = mStartRangeDate.get(Calendar.DAY_OF_WEEK);
        long diff = (fromDateDayOfWeek - toDate.getFirstDayOfWeek()) * MILLIS_IN_DAY;
        if (diff < 0) {
            diff = diff + MILLIS_IN_WEEK;
        }
        long refDay = mStartRangeDate.getTimeInMillis() - diff;
        return (int) ((toDate.getTimeInMillis() - refDay) / MILLIS_IN_WEEK);
    }

    /**
     * Command responsible for acting upon scroll state changes.
     *
     * @hide
     */
    protected class ScrollStateRunnable implements Runnable {
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
            removeCallbacks(this);
            mView = view;
            mNewState = scrollState;
            removeCallbacks(this);
            postDelayed(this, SCROLL_CHANGE_DELAY);
        }

        public void run() {
            mCurrentScrollState = mNewState;
            // Fix the position after a scroll or a fling ends
            if (mNewState == OnScrollListener.SCROLL_STATE_IDLE
                    && mPreviousScrollState != OnScrollListener.SCROLL_STATE_IDLE) {
                mPreviousScrollState = mNewState;
                View child = mView.getChildAt(0);
                if (child == null) {
                    // The view is no longer visible, just return
                    return;
                }
                int dist = child.getBottom() - mListTopOffset;
                if (dist > mListTopOffset) {
                    if (mIsScrollingUp) {
                        mView.smoothScrollBy(dist - child.getHeight(), ADJUSTMENT_SCROLL_DURATION);
                    } else {
                        mView.smoothScrollBy(dist, ADJUSTMENT_SCROLL_DURATION);
                    }
                }
            } else {
                mPreviousScrollState = mNewState;
            }
        }
    }

    /**
     * <p>
     * This is a specialized adapter for creating a list of weeks with
     * selectable days. It can be configured to display the week number, start
     * the week on a given day, show a reduced number of days, or display an
     * arbitrary number of weeks at a time.
     * </p>
     *
     * @hide
     */
    public class WeeksAdapter extends BaseAdapter implements OnTouchListener {

        /**
         * The default maximum year supported by the Date Time Picker.
         */
        public static final int DEFAULT_MAX_CALENDAR_YEAR = 2100;

        /**
         * The default minimum year supported by the Date Time Picker.
         */
        public static final int DEFAULT_MIN_CALENDAR_YEAR = 1900;

        /**
         * The number of weeks to display at a time.
         */
        public static final String WEEK_PARAMS_NUM_WEEKS = "num_weeks";

        /**
         * Which month should be in focus currently.
         */
        public static final String WEEK_PARAMS_FOCUS_MONTH = "focus_month";

        /**
         * Whether the week number should be shown. Non-zero to show them.
         */
        public static final String WEEK_PARAMS_SHOW_WEEK = "week_numbers";

        /**
         * Which day the week should start on. {@link Time#SUNDAY} through
         * {@link Time#SATURDAY}.
         */
        public static final String WEEK_PARAMS_WEEK_START = "week_start";

        /**
         * The year of the highlighted day.
         */
        public static final String WEEK_PARAMS_YEAR = "selected_year";

        /**
         * The month of the highlighted day.
         */
        public static final String WEEK_PARAMS_MONTH = "selected_month";

        /**
         * The year of the highlighted day.
         */
        public static final String WEEK_PARAMS_DAY_OF_MONTH = "selected_day_of_month";

        /**
         * The start date of the supported interval.
         */
        public static final String WEEK_PARAMS_START_DATE_RANGE_MILLIS = "start_date_gange_millis";

        /**
         * The end date of the supported interval.
         */
        public static final String WEEK_PARAMS_END_DATE_RANGE_MILLIS = "end_date_gange_millis";

        /**
         * How many days of the week to display [1-7].
         */
        public static final String WEEK_PARAMS_DAYS_PER_WEEK = "days_per_week";

        protected int WEEK_7_OVERHANG_HEIGHT = 7;

        protected int mSelectedWeek;

        protected GestureDetector mGestureDetector;

        protected int mFocusMonth = 0;

        private final Calendar mSelectedDay = Calendar.getInstance();

        private int mTotalWeekCount = -1;

        public WeeksAdapter(Context context) {
            mContext = context;

            if (sScale == 0) {
                sScale = context.getResources().getDisplayMetrics().density;
                if (sScale != 1) {
                    WEEK_7_OVERHANG_HEIGHT *= sScale;
                }
            }
            init();
        }

        /**
         * Set up the gesture detector and selected time
         */
        protected void init() {
            mGestureDetector = new GestureDetector(mContext, new CalendarGestureListener());
            mSelectedWeek = getWeeksDelta(mSelectedDay);
            mTotalWeekCount = getWeeksDelta(mEndRangeDate);
        }

        /**
         * Updates the selected day and related parameters.
         * 
         * @param selectedDay The time to highlight
         */
        public void setSelectedDay(Calendar selectedDay) {
            if (selectedDay.get(Calendar.DAY_OF_YEAR) == mSelectedDay.get(Calendar.DAY_OF_YEAR)
                    && selectedDay.get(Calendar.YEAR) == mSelectedDay.get(Calendar.YEAR)) {
                return;
            }
            mSelectedDay.setTimeInMillis(selectedDay.getTimeInMillis());
            mSelectedDay.setTimeZone(selectedDay.getTimeZone());
            mSelectedWeek = getWeeksDelta(mSelectedDay);
            mFocusMonth = mSelectedDay.get(Calendar.MONTH);
            notifyDataSetChanged();
            invalidate();  // Test
        }

        /**
         * @return The selected day of month.
         */
        public Calendar getSelectedDay() {
            return mSelectedDay;
        }

        /**
         * updates any config options that may have changed and refreshes the
         * view
         */
        public void refresh() {
            notifyDataSetChanged();
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

        @SuppressWarnings("unchecked")
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            WeekView v;
            HashMap<String, Object> drawingParams = null;
            if (convertView != null) {
                v = (WeekView) convertView;
                // We store the drawing parameters in the view so it can be
                // recycled
                drawingParams = (HashMap<String, Object>) v.getTag();
            } else {
                v = getNewView();
                // Set up the new view
                android.widget.AbsListView.LayoutParams params =
                    new android.widget.AbsListView.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                v.setLayoutParams(params);
                v.setClickable(true);
                v.setOnTouchListener(this);

                drawingParams = new HashMap<String, Object>();
            }

            // pass in all the view parameters
            putDrawingParementer(drawingParams, WeekView.VIEW_PARAMS_SHOW_WK_NUM,
                    mShowWeekNumber ? 1 : 0);
            putDrawingParementer(drawingParams, WeekView.VIEW_PARAMS_WEEK_START, mFirstDayOfWeek);
            putDrawingParementer(drawingParams, WeekView.VIEW_PARAMS_NUM_DAYS, mDaysPerWeek);
            putDrawingParementer(drawingParams, WeekView.VIEW_PARAMS_WEEK, position);
            putDrawingParementer(drawingParams, WeekView.VIEW_PARAMS_FOCUS_MONTH, mFocusMonth);
            putDrawingParementer(drawingParams, WeekView.VIEW_PARAMS_SELECTED_DAY,
                    (mSelectedWeek == position) ? mSelectedDay.get(Calendar.DAY_OF_WEEK) : -1);
            v.setWeekParams(drawingParams);

            return v;
        }

        /**
         * Puts the given <code>value</code> for the drawing
         * <code>parameter</code> in the <code>drawingParams</code>.
         */
        private void putDrawingParementer(HashMap<String, Object> drawingParams, String parameter,
                int value) {
            int[] valueArray = (int[]) drawingParams.get(parameter);
            if (valueArray == null) {
                valueArray = new int[1];
                drawingParams.put(parameter, valueArray);
            }
            valueArray[0] = value;
        }

        /**
         * Creates a new WeekView and returns it. Override this to customize the
         * view creation.
         *
         * @return A new WeekView
         */
        protected WeekView getNewView() {
            return new WeekView(mContext);
        }

        /**
         * Changes which month is in focus and updates the view.
         *
         * @param month The month to show as in focus [0-11]
         */
        public void setFocusMonth(int month) {
            if (mFocusMonth == month) {
                return;
            }
            mFocusMonth = month;
            notifyDataSetChanged();
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mGestureDetector.onTouchEvent(event)) {
                WeekView weekView = (WeekView) v;
                weekView.getDayFromLocation(event.getX(), mTempCalendar);
                if (mTempCalendar.get(Calendar.YEAR) != 0) {
                    onDayTapped(mTempCalendar);
                }
                return true;
            }
            return false;
        }

        /**
         * Maintains the same hour/min/sec but moves the day to the tapped day.
         * 
         * @param day The day that was tapped
         */
        protected void onDayTapped(Calendar day) {
            setSelectedDay(day);
        }

        /**
         * This is here so we can identify single tap events and set the
         * selected day correctly
         */
        protected class CalendarGestureListener extends GestureDetector.SimpleOnGestureListener {
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
     *
     * @hide
     */
    public class WeekView extends View {

        /*
         * These params can be passed into the view to control how it appears.
         * {@link #VIEW_PARAMS_WEEK} is the only required field, though the
         * default values are unlikely to fit most layouts correctly.
         */

        /**
         * This sets the height of this week in pixels
         */
        public static final String VIEW_PARAMS_HEIGHT = "height";

        /**
         * This specifies the position (or weeks since the epoch) of this week.
         */
        public static final String VIEW_PARAMS_WEEK = "week";

        /**
         * This sets one of the days in this view as selected
         * {@link Time#SUNDAY} through {@link Time#SATURDAY}.
         */
        public static final String VIEW_PARAMS_SELECTED_DAY = "selected_day";

        /**
         * Which day the week should start on. {@link Time#SUNDAY} through
         * {@link Time#SATURDAY}.
         */
        public static final String VIEW_PARAMS_WEEK_START = "week_start";

        /**
         * How many days to display at a time. Days will be displayed starting
         * with {@link #mFirstDay}.
         */
        public static final String VIEW_PARAMS_NUM_DAYS = "num_days";

        /**
         * Which month is currently in focus, as defined by {@link Time#month}
         * [0-11].
         */
        public static final String VIEW_PARAMS_FOCUS_MONTH = "focus_month";

        /**
         * If this month should display week numbers. false if 0, true
         * otherwise.
         */
        public static final String VIEW_PARAMS_SHOW_WK_NUM = "show_wk_num";

        protected int mDefaultHeight = 32;

        protected int mMinHeight = 10;

        protected static final int DEFAULT_SELECTED_DAY = -1;

        protected static final int DEFAULT_WEEK_START = Calendar.SUNDAY;

        protected static final int DEFAULT_NUM_DAYS = 7;

        protected static final int DEFAULT_SHOW_WK_NUM = 0;

        protected static final int DEFAULT_FOCUS_MONTH = -1;

        protected static final int DAY_SEPARATOR_WIDTH = 1;

        protected int mNumberTextSize = 14;

        // affects the padding on the sides of this view
        protected int mPadding = 0;

        protected final Rect mTempRect = new Rect();

        protected final Paint mDrawPaint = new Paint();

        protected Paint mMonthNumDrawPaint = new Paint();

        protected Drawable mSelectedDayLine;

        protected final int mSelectionBackgroundColor;

        protected final int mFocusedMonthDateColor;

        protected final int mOtherMonthDateColor;

        protected final int mGridLinesColor;

        protected final int mWeekNumberColor;

        // Cache the number strings so we don't have to recompute them each time
        protected String[] mDayNumbers;

        // Quick lookup for checking which days are in the focus month
        protected boolean[] mFocusDay;

        // The first day displayed by this item
        protected Calendar mFirstDay;

        // The month of the first day in this week
        protected int mMonthOfFirstWeekDay = -1;

        // The month of the last day in this week
        protected int mLastWeekDayMonth = -1;

        // The position of this week, equivalent to weeks since the week of Jan
        // 1st, 1900
        protected int mWeek = -1;

        // Quick reference to the width of this view, matches parent
        protected int mWidth;

        // The height this view should draw at in pixels, set by height param
        protected int mHeight = mDefaultHeight;

        // Whether the week number should be shown
        protected boolean mShowWeekNum = false;

        // If this view contains the selected day
        protected boolean mHasSelectedDay = false;

        // Which day is selected [0-6] or -1 if no day is selected
        protected int mSelectedDay = DEFAULT_SELECTED_DAY;

        // How many days to display
        protected int mNumDays = DEFAULT_NUM_DAYS;

        // The number of days + a spot for week number if it is displayed
        protected int mNumCells = mNumDays;

        // The left edge of the selected day
        protected int mSelectedLeft = -1;

        // The right edge of the selected day
        protected int mSelectedRight = -1;

        public WeekView(Context context) {
            super(context);

            TypedValue outTypedValue = new TypedValue();
            context.getTheme().resolveAttribute(R.attr.dayPickerWeekViewStyle, outTypedValue, true);
            TypedArray attributesArray = context.obtainStyledAttributes(outTypedValue.resourceId,
                    R.styleable.DayPickerWeekView);

            mSelectionBackgroundColor = attributesArray.getColor(
                    R.styleable.DayPickerWeekView_selectionBackgroundColor, 0);
            mFocusedMonthDateColor = attributesArray.getColor(
                    R.styleable.DayPickerWeekView_focusedMonthDateColor, 0);
            mOtherMonthDateColor = attributesArray.getColor(
                    R.styleable.DayPickerWeekView_otherMonthDateColor, 0);
            mGridLinesColor = attributesArray.getColor(
                    R.styleable.DayPickerWeekView_gridLinesColor, 0);
            mWeekNumberColor = attributesArray.getColor(
                    R.styleable.DayPickerWeekView_weekNumberColor, 0);
            mSelectedDayLine = attributesArray
                    .getDrawable(R.styleable.DayPickerWeekView_selectedDayLine);
            attributesArray.recycle();

            if (sScale == 0) {
                sScale = context.getResources().getDisplayMetrics().density;
                if (sScale != 1) {
                    mDefaultHeight *= sScale;
                    mMinHeight *= sScale;
                    mNumberTextSize *= sScale;
                }
            }

            // Sets up any standard paints that will be used
            setPaintProperties();
        }

        /**
         * Sets all the parameters for displaying this week. The only required
         * parameter is the week number. Other parameters have a default value
         * and will only update if a new value is included, except for focus
         * month, which will always default to no focus month if no value is
         * passed in. See {@link #VIEW_PARAMS_HEIGHT} for more info on
         * parameters.
         *
         * @param params A map of the new parameters, see
         *            {@link #VIEW_PARAMS_HEIGHT}
         */
        public void setWeekParams(HashMap<String, Object> params) {
            if (!params.containsKey(VIEW_PARAMS_WEEK)) {
                throw new InvalidParameterException(
                        "You must specify the week number for this view");
            }
            setTag(params);
            // We keep the current value for any params not present
            if (params.containsKey(VIEW_PARAMS_HEIGHT)) {
                mHeight = ((int[]) params.get(VIEW_PARAMS_HEIGHT))[0];
                if (mHeight < mMinHeight) {
                    mHeight = mMinHeight;
                }
            }
            if (params.containsKey(VIEW_PARAMS_SELECTED_DAY)) {
                mSelectedDay = ((int[]) params.get(VIEW_PARAMS_SELECTED_DAY))[0];
            }
            mHasSelectedDay = mSelectedDay != -1;
            if (params.containsKey(VIEW_PARAMS_NUM_DAYS)) {
                mNumDays = ((int[]) params.get(VIEW_PARAMS_NUM_DAYS))[0];
            }
            if (params.containsKey(VIEW_PARAMS_SHOW_WK_NUM)) {
                if (((int[]) params.get(VIEW_PARAMS_SHOW_WK_NUM))[0] != 0) {
                    mNumCells = mNumDays + 1;
                    mShowWeekNum = true;
                } else {
                    mShowWeekNum = false;
                }
            } else {
                mNumCells = mShowWeekNum ? mNumDays + 1 : mNumDays;
            }
            mWeek = ((int[]) params.get(VIEW_PARAMS_WEEK))[0];
            mTempCalendar.clear();
            mTempCalendar.set(1900, 0, 1);
            mTempCalendar.add(Calendar.WEEK_OF_YEAR, mWeek);
            if (params.containsKey(VIEW_PARAMS_WEEK_START)) {
                mTempCalendar.setFirstDayOfWeek(((int[]) params.get(VIEW_PARAMS_WEEK_START))[0]);
            } else {
                mTempCalendar.setFirstDayOfWeek(DEFAULT_WEEK_START);
            }

            // Allocate space for caching the day numbers and focus values
            mDayNumbers = new String[mNumCells];
            mFocusDay = new boolean[mNumCells];

            // If we're showing the week number calculate it based on Monday
            int i = 0;
            if (mShowWeekNum) {
                mDayNumbers[0] = Integer.toString(mTempCalendar.get(Calendar.WEEK_OF_YEAR));
                i++;
            }

            // Now adjust our starting day based on the start day of the week
            int diff = mTempCalendar.getFirstDayOfWeek() - mTempCalendar.get(Calendar.DAY_OF_WEEK);
            mTempCalendar.add(Calendar.DAY_OF_MONTH, diff);

            mFirstDay = (Calendar) mTempCalendar.clone();

            mMonthOfFirstWeekDay = mTempCalendar.get(Calendar.MONTH);

            int focusMonth = params.containsKey(VIEW_PARAMS_FOCUS_MONTH) ? ((int[]) params
                    .get(VIEW_PARAMS_FOCUS_MONTH))[0] : DEFAULT_FOCUS_MONTH;

            for (; i < mNumCells; i++) {
                mFocusDay[i] = (mTempCalendar.get(Calendar.MONTH) == focusMonth);
                mDayNumbers[i] = Integer.toString(mTempCalendar.get(Calendar.DAY_OF_MONTH));
                mTempCalendar.add(Calendar.DAY_OF_MONTH, 1);
            }
            // We do one extra add at the end of the loop, if that pushed us to
            // new month undo it
            if (mTempCalendar.get(Calendar.DAY_OF_MONTH) == 1) {
                mTempCalendar.add(Calendar.DAY_OF_MONTH, -1);
            }
            mLastWeekDayMonth = mTempCalendar.get(Calendar.MONTH);

            updateSelectionPositions();
        }

        /**
         * Sets up the text and style properties for painting. Override this if
         * you want to use a different paint.
         */
        protected void setPaintProperties() {
            mDrawPaint.setFakeBoldText(false);
            mDrawPaint.setAntiAlias(true);
            mDrawPaint.setTextSize(mNumberTextSize);
            mDrawPaint.setStyle(Style.FILL);

            mMonthNumDrawPaint.setFakeBoldText(true);
            mMonthNumDrawPaint.setAntiAlias(true);
            mMonthNumDrawPaint.setTextSize(mNumberTextSize);
            mMonthNumDrawPaint.setColor(mFocusedMonthDateColor);
            mMonthNumDrawPaint.setStyle(Style.FILL);
            mMonthNumDrawPaint.setTextAlign(Align.CENTER);
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
         * Returns the number of days this view will display.
         */
        public int getNumDays() {
            return mNumDays;
        }

        /**
         * Calculates the day that the given x position is in, accounting for
         * week number. Returns a Time referencing that day or null if
         *
         * @param x The x position of the touch eventy
         */
        public void getDayFromLocation(float x, Calendar outCalendar) {
            int dayStart = mShowWeekNum ? (mWidth - mPadding * 2) / mNumCells + mPadding : mPadding;
            if (x < dayStart || x > mWidth - mPadding) {
                outCalendar.set(0, 0, 0, 0, 0, 0);
                return;
            }
            // Selection is (x - start) / (pixels/day) == (x -s) * day / pixels
            int dayPosition = (int) ((x - dayStart) * mNumDays / (mWidth - dayStart - mPadding));
            outCalendar.setTimeZone(mFirstDay.getTimeZone());
            outCalendar.setTimeInMillis(mFirstDay.getTimeInMillis());
            outCalendar.add(Calendar.DAY_OF_MONTH, dayPosition);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawBackground(canvas);
            drawWeekNums(canvas);
            drawDaySeparators(canvas);
        }

        /**
         * This draws the selection highlight if a day is selected in this week.
         * Override this method if you wish to have a different background
         * drawn.
         *
         * @param canvas The canvas to draw on
         */
        protected void drawBackground(Canvas canvas) {
            if (!mHasSelectedDay) {
                return;
            }
            mDrawPaint.setColor(mSelectionBackgroundColor);

            mTempRect.top = DAY_SEPARATOR_WIDTH;
            mTempRect.bottom = mHeight;
            mTempRect.left = mShowWeekNum ? mPadding + (mWidth - mPadding * 2) / mNumCells
                    : mPadding;
            mTempRect.right = mSelectedLeft - 2;
            canvas.drawRect(mTempRect, mDrawPaint);

            mTempRect.left = mSelectedRight + 3;
            mTempRect.right = mWidth - mPadding;
            canvas.drawRect(mTempRect, mDrawPaint);
        }

        /**
         * Draws the week and month day numbers for this week. Override this
         * method if you need different placement.
         *
         * @param canvas The canvas to draw on
         */
        protected void drawWeekNums(Canvas canvas) {
            float textHeight = mDrawPaint.getTextSize();
            int y = (int) ((mHeight + textHeight) / 2) - DAY_SEPARATOR_WIDTH;
            int nDays = mNumCells;

            mDrawPaint.setTextAlign(Align.CENTER);
            int i = 0;
            int divisor = 2 * nDays;
            if (mShowWeekNum) {
                mDrawPaint.setColor(mWeekNumberColor);
                int x = (mWidth - mPadding * 2) / divisor + mPadding;
                canvas.drawText(mDayNumbers[0], x, y, mDrawPaint);
                i++;
            }
            for (; i < nDays; i++) {
                mMonthNumDrawPaint.setColor(mFocusDay[i] ? mFocusedMonthDateColor
                        : mOtherMonthDateColor);
                int x = (2 * i + 1) * (mWidth - mPadding * 2) / divisor + mPadding;
                canvas.drawText(mDayNumbers[i], x, y, mMonthNumDrawPaint);
            }
        }

        /**
         * Draws a horizontal line for separating the weeks. Override this
         * method if you want custom separators.
         *
         * @param canvas The canvas to draw on
         */
        protected void drawDaySeparators(Canvas canvas) {
            mDrawPaint.setColor(mGridLinesColor);
            mDrawPaint.setStrokeWidth(DAY_SEPARATOR_WIDTH);
            float x = mShowWeekNum ? mPadding + (mWidth - mPadding * 2) / mNumCells : mPadding;
            canvas.drawLine(x, 0, mWidth - mPadding, 0, mDrawPaint);

            if (mHasSelectedDay) {
                mSelectedDayLine.setBounds(mSelectedLeft - 2, DAY_SEPARATOR_WIDTH,
                        mSelectedLeft + 4, mHeight + 1);
                mSelectedDayLine.draw(canvas);
                mSelectedDayLine.setBounds(mSelectedRight - 3, DAY_SEPARATOR_WIDTH,
                        mSelectedRight + 3, mHeight + 1);
                mSelectedDayLine.draw(canvas);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            mWidth = w;
            updateSelectionPositions();
        }

        /**
         * This calculates the positions for the selected day lines.
         */
        protected void updateSelectionPositions() {
            if (mHasSelectedDay) {
                int selectedPosition = mSelectedDay - mTempCalendar.getFirstDayOfWeek();
                if (selectedPosition < 0) {
                    selectedPosition += 7;
                }
                if (mShowWeekNum) {
                    selectedPosition++;
                }
                mSelectedLeft = selectedPosition * (mWidth - mPadding * 2) / mNumCells + mPadding;
                mSelectedRight = (selectedPosition + 1) * (mWidth - mPadding * 2) / mNumCells
                        + mPadding;
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), mHeight);
        }
    }
}
