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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;
import com.android.internal.widget.ExploreByTouchHelper;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;

/**
 * A calendar-like view displaying a specified month and the appropriate selectable day numbers
 * within the specified month.
 */
class SimpleMonthView extends View {
    private static final int DEFAULT_HEIGHT = 32;
    private static final int MIN_HEIGHT = 10;

    private static final int DEFAULT_SELECTED_DAY = -1;
    private static final int DEFAULT_WEEK_START = Calendar.SUNDAY;
    private static final int DEFAULT_NUM_DAYS = 7;
    private static final int DEFAULT_NUM_ROWS = 6;
    private static final int MAX_NUM_ROWS = 6;

    private static final int SELECTED_CIRCLE_ALPHA = 60;

    private static final int DAY_SEPARATOR_WIDTH = 1;

    private final Formatter mFormatter;
    private final StringBuilder mStringBuilder;

    private final int mMiniDayNumberTextSize;
    private final int mMonthLabelTextSize;
    private final int mMonthDayLabelTextSize;
    private final int mMonthHeaderSize;
    private final int mDaySelectedCircleSize;

    /** Single-letter (when available) formatter for the day of week label. */
    private SimpleDateFormat mDayFormatter = new SimpleDateFormat("EEEEE", Locale.getDefault());

    // affects the padding on the sides of this view
    private int mPadding = 0;

    private String mDayOfWeekTypeface;
    private String mMonthTitleTypeface;

    private Paint mDayNumberPaint;
    private Paint mDayNumberDisabledPaint;
    private Paint mDayNumberSelectedPaint;

    private Paint mMonthTitlePaint;
    private Paint mMonthDayLabelPaint;

    private int mMonth;
    private int mYear;

    // Quick reference to the width of this view, matches parent
    private int mWidth;

    // The height this view should draw at in pixels, set by height param
    private int mRowHeight = DEFAULT_HEIGHT;

    // If this view contains the today
    private boolean mHasToday = false;

    // Which day is selected [0-6] or -1 if no day is selected
    private int mSelectedDay = -1;

    // Which day is today [0-6] or -1 if no day is today
    private int mToday = DEFAULT_SELECTED_DAY;

    // Which day of the week to start on [0-6]
    private int mWeekStart = DEFAULT_WEEK_START;

    // How many days to display
    private int mNumDays = DEFAULT_NUM_DAYS;

    // The number of days + a spot for week number if it is displayed
    private int mNumCells = mNumDays;

    private int mDayOfWeekStart = 0;

    // First enabled day
    private int mEnabledDayStart = 1;

    // Last enabled day
    private int mEnabledDayEnd = 31;

    private final Calendar mCalendar = Calendar.getInstance();
    private final Calendar mDayLabelCalendar = Calendar.getInstance();

    private final MonthViewTouchHelper mTouchHelper;

    private int mNumRows = DEFAULT_NUM_ROWS;

    // Optional listener for handling day click actions
    private OnDayClickListener mOnDayClickListener;

    // Whether to prevent setting the accessibility delegate
    private boolean mLockAccessibilityDelegate;

    private int mNormalTextColor;
    private int mDisabledTextColor;
    private int mSelectedDayColor;

    public SimpleMonthView(Context context) {
        this(context, null);
    }

    public SimpleMonthView(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.datePickerStyle);
    }

    public SimpleMonthView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SimpleMonthView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final Resources res = context.getResources();
        mDayOfWeekTypeface = res.getString(R.string.day_of_week_label_typeface);
        mMonthTitleTypeface = res.getString(R.string.sans_serif);

        mStringBuilder = new StringBuilder(50);
        mFormatter = new Formatter(mStringBuilder, Locale.getDefault());

        mMiniDayNumberTextSize = res.getDimensionPixelSize(R.dimen.datepicker_day_number_size);
        mMonthLabelTextSize = res.getDimensionPixelSize(R.dimen.datepicker_month_label_size);
        mMonthDayLabelTextSize = res.getDimensionPixelSize(
                R.dimen.datepicker_month_day_label_text_size);
        mMonthHeaderSize = res.getDimensionPixelOffset(
                R.dimen.datepicker_month_list_item_header_height);
        mDaySelectedCircleSize = res.getDimensionPixelSize(
                R.dimen.datepicker_day_number_select_circle_radius);

        mRowHeight = (res.getDimensionPixelOffset(R.dimen.datepicker_view_animator_height)
                - mMonthHeaderSize) / MAX_NUM_ROWS;

        // Set up accessibility components.
        mTouchHelper = new MonthViewTouchHelper(this);
        setAccessibilityDelegate(mTouchHelper);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        mLockAccessibilityDelegate = true;

        // Sets up any standard paints that will be used
        initView();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mDayFormatter = new SimpleDateFormat("EEEEE", newConfig.locale);
    }

    void setTextColor(ColorStateList colors) {
        final Resources res = getContext().getResources();

        mNormalTextColor = colors.getColorForState(ENABLED_STATE_SET,
                res.getColor(R.color.datepicker_default_normal_text_color_holo_light));
        mMonthTitlePaint.setColor(mNormalTextColor);
        mMonthDayLabelPaint.setColor(mNormalTextColor);

        mDisabledTextColor = colors.getColorForState(EMPTY_STATE_SET,
                res.getColor(R.color.datepicker_default_disabled_text_color_holo_light));
        mDayNumberDisabledPaint.setColor(mDisabledTextColor);

        mSelectedDayColor = colors.getColorForState(ENABLED_SELECTED_STATE_SET,
                res.getColor(R.color.holo_blue_light));
        mDayNumberSelectedPaint.setColor(mSelectedDayColor);
        mDayNumberSelectedPaint.setAlpha(SELECTED_CIRCLE_ALPHA);
    }

    @Override
    public void setAccessibilityDelegate(AccessibilityDelegate delegate) {
        // Workaround for a JB MR1 issue where accessibility delegates on
        // top-level ListView items are overwritten.
        if (!mLockAccessibilityDelegate) {
            super.setAccessibilityDelegate(delegate);
        }
    }

    public void setOnDayClickListener(OnDayClickListener listener) {
        mOnDayClickListener = listener;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // First right-of-refusal goes the touch exploration helper.
        if (mTouchHelper.dispatchHoverEvent(event)) {
            return true;
        }
        return super.dispatchHoverEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
                final int day = getDayFromLocation(event.getX(), event.getY());
                if (day >= 0) {
                    onDayClick(day);
                }
                break;
        }
        return true;
    }

    /**
     * Sets up the text and style properties for painting.
     */
    private void initView() {
        mMonthTitlePaint = new Paint();
        mMonthTitlePaint.setAntiAlias(true);
        mMonthTitlePaint.setColor(mNormalTextColor);
        mMonthTitlePaint.setTextSize(mMonthLabelTextSize);
        mMonthTitlePaint.setTypeface(Typeface.create(mMonthTitleTypeface, Typeface.BOLD));
        mMonthTitlePaint.setTextAlign(Align.CENTER);
        mMonthTitlePaint.setStyle(Style.FILL);
        mMonthTitlePaint.setFakeBoldText(true);

        mMonthDayLabelPaint = new Paint();
        mMonthDayLabelPaint.setAntiAlias(true);
        mMonthDayLabelPaint.setColor(mNormalTextColor);
        mMonthDayLabelPaint.setTextSize(mMonthDayLabelTextSize);
        mMonthDayLabelPaint.setTypeface(Typeface.create(mDayOfWeekTypeface, Typeface.NORMAL));
        mMonthDayLabelPaint.setTextAlign(Align.CENTER);
        mMonthDayLabelPaint.setStyle(Style.FILL);
        mMonthDayLabelPaint.setFakeBoldText(true);

        mDayNumberSelectedPaint = new Paint();
        mDayNumberSelectedPaint.setAntiAlias(true);
        mDayNumberSelectedPaint.setColor(mSelectedDayColor);
        mDayNumberSelectedPaint.setAlpha(SELECTED_CIRCLE_ALPHA);
        mDayNumberSelectedPaint.setTextAlign(Align.CENTER);
        mDayNumberSelectedPaint.setStyle(Style.FILL);
        mDayNumberSelectedPaint.setFakeBoldText(true);

        mDayNumberPaint = new Paint();
        mDayNumberPaint.setAntiAlias(true);
        mDayNumberPaint.setTextSize(mMiniDayNumberTextSize);
        mDayNumberPaint.setTextAlign(Align.CENTER);
        mDayNumberPaint.setStyle(Style.FILL);
        mDayNumberPaint.setFakeBoldText(false);

        mDayNumberDisabledPaint = new Paint();
        mDayNumberDisabledPaint.setAntiAlias(true);
        mDayNumberDisabledPaint.setColor(mDisabledTextColor);
        mDayNumberDisabledPaint.setTextSize(mMiniDayNumberTextSize);
        mDayNumberDisabledPaint.setTextAlign(Align.CENTER);
        mDayNumberDisabledPaint.setStyle(Style.FILL);
        mDayNumberDisabledPaint.setFakeBoldText(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawMonthTitle(canvas);
        drawWeekDayLabels(canvas);
        drawDays(canvas);
    }

    private static boolean isValidDayOfWeek(int day) {
        return day >= Calendar.SUNDAY && day <= Calendar.SATURDAY;
    }

    private static boolean isValidMonth(int month) {
        return month >= Calendar.JANUARY && month <= Calendar.DECEMBER;
    }

    /**
     * Sets all the parameters for displaying this week. Parameters have a default value and
     * will only update if a new value is included, except for focus month, which will always
     * default to no focus month if no value is passed in. The only required parameter is the
     * week start.
     *
     * @param selectedDay the selected day of the month, or -1 for no selection.
     * @param month the month.
     * @param year the year.
     * @param weekStart which day the week should start on. {@link Calendar#SUNDAY} through
     *        {@link Calendar#SATURDAY}.
     * @param enabledDayStart the first enabled day.
     * @param enabledDayEnd the last enabled day.
     */
    void setMonthParams(int selectedDay, int month, int year, int weekStart, int enabledDayStart,
            int enabledDayEnd) {
        if (mRowHeight < MIN_HEIGHT) {
            mRowHeight = MIN_HEIGHT;
        }

        mSelectedDay = selectedDay;

        if (isValidMonth(month)) {
            mMonth = month;
        }
        mYear = year;

        // Figure out what day today is
        final Time today = new Time(Time.getCurrentTimezone());
        today.setToNow();
        mHasToday = false;
        mToday = -1;

        mCalendar.set(Calendar.MONTH, mMonth);
        mCalendar.set(Calendar.YEAR, mYear);
        mCalendar.set(Calendar.DAY_OF_MONTH, 1);
        mDayOfWeekStart = mCalendar.get(Calendar.DAY_OF_WEEK);

        if (isValidDayOfWeek(weekStart)) {
            mWeekStart = weekStart;
        } else {
            mWeekStart = mCalendar.getFirstDayOfWeek();
        }

        if (enabledDayStart > 0 && enabledDayEnd < 32) {
            mEnabledDayStart = enabledDayStart;
        }
        if (enabledDayEnd > 0 && enabledDayEnd < 32 && enabledDayEnd >= enabledDayStart) {
            mEnabledDayEnd = enabledDayEnd;
        }

        mNumCells = getDaysInMonth(mMonth, mYear);
        for (int i = 0; i < mNumCells; i++) {
            final int day = i + 1;
            if (sameDay(day, today)) {
                mHasToday = true;
                mToday = day;
            }
        }
        mNumRows = calculateNumRows();

        // Invalidate cached accessibility information.
        mTouchHelper.invalidateRoot();
    }

    private static int getDaysInMonth(int month, int year) {
        switch (month) {
            case Calendar.JANUARY:
            case Calendar.MARCH:
            case Calendar.MAY:
            case Calendar.JULY:
            case Calendar.AUGUST:
            case Calendar.OCTOBER:
            case Calendar.DECEMBER:
                return 31;
            case Calendar.APRIL:
            case Calendar.JUNE:
            case Calendar.SEPTEMBER:
            case Calendar.NOVEMBER:
                return 30;
            case Calendar.FEBRUARY:
                return (year % 4 == 0) ? 29 : 28;
            default:
                throw new IllegalArgumentException("Invalid Month");
        }
    }

    public void reuse() {
        mNumRows = DEFAULT_NUM_ROWS;
        requestLayout();
    }

    private int calculateNumRows() {
        int offset = findDayOffset();
        int dividend = (offset + mNumCells) / mNumDays;
        int remainder = (offset + mNumCells) % mNumDays;
        return (dividend + (remainder > 0 ? 1 : 0));
    }

    private boolean sameDay(int day, Time today) {
        return mYear == today.year &&
                mMonth == today.month &&
                day == today.monthDay;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), mRowHeight * mNumRows
                + mMonthHeaderSize);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mWidth = w;

        // Invalidate cached accessibility information.
        mTouchHelper.invalidateRoot();
    }

    private String getMonthAndYearString() {
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                | DateUtils.FORMAT_NO_MONTH_DAY;
        mStringBuilder.setLength(0);
        long millis = mCalendar.getTimeInMillis();
        return DateUtils.formatDateRange(getContext(), mFormatter, millis, millis, flags,
                Time.getCurrentTimezone()).toString();
    }

    private void drawMonthTitle(Canvas canvas) {
        final float x = (mWidth + 2 * mPadding) / 2f;
        final float y = (mMonthHeaderSize - mMonthDayLabelTextSize) / 2f;
        canvas.drawText(getMonthAndYearString(), x, y, mMonthTitlePaint);
    }

    private void drawWeekDayLabels(Canvas canvas) {
        final int y = mMonthHeaderSize - (mMonthDayLabelTextSize / 2);
        final int dayWidthHalf = (mWidth - mPadding * 2) / (mNumDays * 2);

        for (int i = 0; i < mNumDays; i++) {
            final int calendarDay = (i + mWeekStart) % mNumDays;
            mDayLabelCalendar.set(Calendar.DAY_OF_WEEK, calendarDay);

            final String dayLabel = mDayFormatter.format(mDayLabelCalendar.getTime());
            final int x = (2 * i + 1) * dayWidthHalf + mPadding;
            canvas.drawText(dayLabel, x, y, mMonthDayLabelPaint);
        }
    }

    /**
     * Draws the month days.
     */
    private void drawDays(Canvas canvas) {
        int y = (((mRowHeight + mMiniDayNumberTextSize) / 2) - DAY_SEPARATOR_WIDTH)
                + mMonthHeaderSize;
        int dayWidthHalf = (mWidth - mPadding * 2) / (mNumDays * 2);
        int j = findDayOffset();
        for (int day = 1; day <= mNumCells; day++) {
            int x = (2 * j + 1) * dayWidthHalf + mPadding;
            if (mSelectedDay == day) {
                canvas.drawCircle(x, y - (mMiniDayNumberTextSize / 3), mDaySelectedCircleSize,
                        mDayNumberSelectedPaint);
            }

            if (mHasToday && mToday == day) {
                mDayNumberPaint.setColor(mSelectedDayColor);
            } else {
                mDayNumberPaint.setColor(mNormalTextColor);
            }
            final Paint paint = (day < mEnabledDayStart || day > mEnabledDayEnd) ?
                    mDayNumberDisabledPaint : mDayNumberPaint;
            canvas.drawText(String.format("%d", day), x, y, paint);
            j++;
            if (j == mNumDays) {
                j = 0;
                y += mRowHeight;
            }
        }
    }

    private int findDayOffset() {
        return (mDayOfWeekStart < mWeekStart ? (mDayOfWeekStart + mNumDays) : mDayOfWeekStart)
                - mWeekStart;
    }

    /**
     * Calculates the day that the given x position is in, accounting for week
     * number. Returns the day or -1 if the position wasn't in a day.
     *
     * @param x The x position of the touch event
     * @return The day number, or -1 if the position wasn't in a day
     */
    private int getDayFromLocation(float x, float y) {
        int dayStart = mPadding;
        if (x < dayStart || x > mWidth - mPadding) {
            return -1;
        }
        // Selection is (x - start) / (pixels/day) == (x -s) * day / pixels
        int row = (int) (y - mMonthHeaderSize) / mRowHeight;
        int column = (int) ((x - dayStart) * mNumDays / (mWidth - dayStart - mPadding));

        int day = column - findDayOffset() + 1;
        day += row * mNumDays;
        if (day < 1 || day > mNumCells) {
            return -1;
        }
        return day;
    }

    /**
     * Called when the user clicks on a day. Handles callbacks to the
     * {@link OnDayClickListener} if one is set.
     *
     * @param day The day that was clicked
     */
    private void onDayClick(int day) {
        if (mOnDayClickListener != null) {
            Calendar date = Calendar.getInstance();
            date.set(mYear, mMonth, day);
            mOnDayClickListener.onDayClick(this, date);
        }

        // This is a no-op if accessibility is turned off.
        mTouchHelper.sendEventForVirtualView(day, AccessibilityEvent.TYPE_VIEW_CLICKED);
    }

    /**
     * @return The date that has accessibility focus, or {@code null} if no date
     *         has focus
     */
    Calendar getAccessibilityFocus() {
        final int day = mTouchHelper.getFocusedVirtualView();
        Calendar date = null;
        if (day >= 0) {
            date = Calendar.getInstance();
            date.set(mYear, mMonth, day);
        }
        return date;
    }

    /**
     * Clears accessibility focus within the view. No-op if the view does not
     * contain accessibility focus.
     */
    public void clearAccessibilityFocus() {
        mTouchHelper.clearFocusedVirtualView();
    }

    /**
     * Attempts to restore accessibility focus to the specified date.
     *
     * @param day The date which should receive focus
     * @return {@code false} if the date is not valid for this month view, or
     *         {@code true} if the date received focus
     */
    boolean restoreAccessibilityFocus(Calendar day) {
        if ((day.get(Calendar.YEAR) != mYear) || (day.get(Calendar.MONTH) != mMonth) ||
                (day.get(Calendar.DAY_OF_MONTH) > mNumCells)) {
            return false;
        }
        mTouchHelper.setFocusedVirtualView(day.get(Calendar.DAY_OF_MONTH));
        return true;
    }

    /**
     * Provides a virtual view hierarchy for interfacing with an accessibility
     * service.
     */
    private class MonthViewTouchHelper extends ExploreByTouchHelper {
        private static final String DATE_FORMAT = "dd MMMM yyyy";

        private final Rect mTempRect = new Rect();
        private final Calendar mTempCalendar = Calendar.getInstance();

        public MonthViewTouchHelper(View host) {
            super(host);
        }

        public void setFocusedVirtualView(int virtualViewId) {
            getAccessibilityNodeProvider(SimpleMonthView.this).performAction(
                    virtualViewId, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        }

        public void clearFocusedVirtualView() {
            final int focusedVirtualView = getFocusedVirtualView();
            if (focusedVirtualView != ExploreByTouchHelper.INVALID_ID) {
                getAccessibilityNodeProvider(SimpleMonthView.this).performAction(
                        focusedVirtualView,
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
                        null);
            }
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            final int day = getDayFromLocation(x, y);
            if (day >= 0) {
                return day;
            }
            return ExploreByTouchHelper.INVALID_ID;
        }

        @Override
        protected void getVisibleVirtualViews(IntArray virtualViewIds) {
            for (int day = 1; day <= mNumCells; day++) {
                virtualViewIds.add(day);
            }
        }

        @Override
        protected void onPopulateEventForVirtualView(int virtualViewId, AccessibilityEvent event) {
            event.setContentDescription(getItemDescription(virtualViewId));
        }

        @Override
        protected void onPopulateNodeForVirtualView(int virtualViewId, AccessibilityNodeInfo node) {
            getItemBounds(virtualViewId, mTempRect);

            node.setContentDescription(getItemDescription(virtualViewId));
            node.setBoundsInParent(mTempRect);
            node.addAction(AccessibilityNodeInfo.ACTION_CLICK);

            if (virtualViewId == mSelectedDay) {
                node.setSelected(true);
            }

        }

        @Override
        protected boolean onPerformActionForVirtualView(int virtualViewId, int action,
                Bundle arguments) {
            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK:
                    onDayClick(virtualViewId);
                    return true;
            }

            return false;
        }

        /**
         * Calculates the bounding rectangle of a given time object.
         *
         * @param day The day to calculate bounds for
         * @param rect The rectangle in which to store the bounds
         */
        private void getItemBounds(int day, Rect rect) {
            final int offsetX = mPadding;
            final int offsetY = mMonthHeaderSize;
            final int cellHeight = mRowHeight;
            final int cellWidth = ((mWidth - (2 * mPadding)) / mNumDays);
            final int index = ((day - 1) + findDayOffset());
            final int row = (index / mNumDays);
            final int column = (index % mNumDays);
            final int x = (offsetX + (column * cellWidth));
            final int y = (offsetY + (row * cellHeight));

            rect.set(x, y, (x + cellWidth), (y + cellHeight));
        }

        /**
         * Generates a description for a given time object. Since this
         * description will be spoken, the components are ordered by descending
         * specificity as DAY MONTH YEAR.
         *
         * @param day The day to generate a description for
         * @return A description of the time object
         */
        private CharSequence getItemDescription(int day) {
            mTempCalendar.set(mYear, mMonth, day);
            final CharSequence date = DateFormat.format(DATE_FORMAT,
                    mTempCalendar.getTimeInMillis());

            if (day == mSelectedDay) {
                return getContext().getString(R.string.item_is_selected, date);
            }

            return date;
        }
    }

    /**
     * Handles callbacks when the user clicks on a time object.
     */
    public interface OnDayClickListener {
        public void onDayClick(SimpleMonthView view, Calendar day);
    }
}
