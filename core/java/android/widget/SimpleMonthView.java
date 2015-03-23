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
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.StateSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.android.internal.R;
import com.android.internal.widget.ExploreByTouchHelper;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * A calendar-like view displaying a specified month and the appropriate selectable day numbers
 * within the specified month.
 */
class SimpleMonthView extends View {
    private static final int DAYS_IN_WEEK = 7;
    private static final int MAX_WEEKS_IN_MONTH = 6;

    private static final int DEFAULT_SELECTED_DAY = -1;
    private static final int DEFAULT_WEEK_START = Calendar.SUNDAY;

    private static final String DEFAULT_TITLE_FORMAT = "MMMMy";
    private static final String DAY_OF_WEEK_FORMAT = "EEEEE";

    private final TextPaint mMonthPaint = new TextPaint();
    private final TextPaint mDayOfWeekPaint = new TextPaint();
    private final TextPaint mDayPaint = new TextPaint();
    private final Paint mDaySelectorPaint = new Paint();
    private final Paint mDayHighlightPaint = new Paint();

    private final Calendar mCalendar = Calendar.getInstance();
    private final Calendar mDayLabelCalendar = Calendar.getInstance();

    private final MonthViewTouchHelper mTouchHelper;

    private final SimpleDateFormat mTitleFormatter;
    private final SimpleDateFormat mDayOfWeekFormatter;

    private CharSequence mTitle;

    private int mMonth;
    private int mYear;

    private int mPaddedWidth;
    private int mPaddedHeight;

    private final int mMonthHeight;
    private final int mDayOfWeekHeight;
    private final int mDayHeight;
    private final int mCellWidth;
    private final int mDaySelectorRadius;

    /** The day of month for the selected day, or -1 if no day is selected. */
    private int mActivatedDay = -1;

    /**
     * The day of month for today, or -1 if the today is not in the current
     * month.
     */
    private int mToday = DEFAULT_SELECTED_DAY;

    /** The first day of the week (ex. Calendar.SUNDAY). */
    private int mWeekStart = DEFAULT_WEEK_START;

    /** The number of days (ex. 28) in the current month. */
    private int mDaysInMonth;

    /**
     * The day of week (ex. Calendar.SUNDAY) for the first day of the current
     * month.
     */
    private int mDayOfWeekStart;

    /** The day of month for the first (inclusive) enabled day. */
    private int mEnabledDayStart = 1;

    /** The day of month for the last (inclusive) enabled day. */
    private int mEnabledDayEnd = 31;

    /** The number of week rows needed to display the current month. */
    private int mNumWeeks = MAX_WEEKS_IN_MONTH;

    /** Optional listener for handling day click actions. */
    private OnDayClickListener mOnDayClickListener;

    private ColorStateList mDayTextColor;

    private int mTouchedDay = -1;

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
        mMonthHeight = res.getDimensionPixelSize(R.dimen.date_picker_month_height);
        mDayOfWeekHeight = res.getDimensionPixelSize(R.dimen.date_picker_day_of_week_height);
        mDayHeight = res.getDimensionPixelSize(R.dimen.date_picker_day_height);
        mCellWidth = res.getDimensionPixelSize(R.dimen.date_picker_day_width);
        mDaySelectorRadius = res.getDimensionPixelSize(R.dimen.date_picker_day_selector_radius);

        // Set up accessibility components.
        mTouchHelper = new MonthViewTouchHelper(this);
        setAccessibilityDelegate(mTouchHelper);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        final Locale locale = res.getConfiguration().locale;
        final String titleFormat = DateFormat.getBestDateTimePattern(locale, DEFAULT_TITLE_FORMAT);
        mTitleFormatter = new SimpleDateFormat(titleFormat, locale);
        mDayOfWeekFormatter = new SimpleDateFormat(DAY_OF_WEEK_FORMAT, locale);

        setClickable(true);
        initPaints(res);
    }

    /**
     * Applies the specified text appearance resource to a paint, returning the
     * text color if one is set in the text appearance.
     *
     * @param p the paint to modify
     * @param resId the resource ID of the text appearance
     * @return the text color, if available
     */
    private ColorStateList applyTextAppearance(Paint p, int resId) {
        final TypedArray ta = mContext.obtainStyledAttributes(null,
                R.styleable.TextAppearance, 0, resId);

        final String fontFamily = ta.getString(R.styleable.TextAppearance_fontFamily);
        if (fontFamily != null) {
            p.setTypeface(Typeface.create(fontFamily, 0));
        }

        p.setTextSize(ta.getDimensionPixelSize(
                R.styleable.TextAppearance_textSize, (int) p.getTextSize()));

        final ColorStateList textColor = ta.getColorStateList(R.styleable.TextAppearance_textColor);
        if (textColor != null) {
            final int enabledColor = textColor.getColorForState(ENABLED_STATE_SET, 0);
            p.setColor(enabledColor);
        }

        ta.recycle();

        return textColor;
    }

    public void setMonthTextAppearance(int resId) {
        applyTextAppearance(mMonthPaint, resId);
        invalidate();
    }

    public void setDayOfWeekTextAppearance(int resId) {
        applyTextAppearance(mDayOfWeekPaint, resId);
        invalidate();
    }

    public void setDayTextAppearance(int resId) {
        final ColorStateList textColor = applyTextAppearance(mDayPaint, resId);
        if (textColor != null) {
            mDayTextColor = textColor;
        }

        invalidate();
    }

    public CharSequence getTitle() {
        if (mTitle == null) {
            mTitle = mTitleFormatter.format(mCalendar.getTime());
        }
        return mTitle;
    }

    /**
     * Sets up the text and style properties for painting.
     */
    private void initPaints(Resources res) {
        final String monthTypeface = res.getString(R.string.date_picker_month_typeface);
        final String dayOfWeekTypeface = res.getString(R.string.date_picker_day_of_week_typeface);
        final String dayTypeface = res.getString(R.string.date_picker_day_typeface);

        final int monthTextSize = res.getDimensionPixelSize(
                R.dimen.date_picker_month_text_size);
        final int dayOfWeekTextSize = res.getDimensionPixelSize(
                R.dimen.date_picker_day_of_week_text_size);
        final int dayTextSize = res.getDimensionPixelSize(
                R.dimen.date_picker_day_text_size);

        mMonthPaint.setAntiAlias(true);
        mMonthPaint.setTextSize(monthTextSize);
        mMonthPaint.setTypeface(Typeface.create(monthTypeface, 0));
        mMonthPaint.setTextAlign(Align.CENTER);
        mMonthPaint.setStyle(Style.FILL);

        mDayOfWeekPaint.setAntiAlias(true);
        mDayOfWeekPaint.setTextSize(dayOfWeekTextSize);
        mDayOfWeekPaint.setTypeface(Typeface.create(dayOfWeekTypeface, 0));
        mDayOfWeekPaint.setTextAlign(Align.CENTER);
        mDayOfWeekPaint.setStyle(Style.FILL);

        mDaySelectorPaint.setAntiAlias(true);
        mDaySelectorPaint.setStyle(Style.FILL);

        mDayHighlightPaint.setAntiAlias(true);
        mDayHighlightPaint.setStyle(Style.FILL);

        mDayPaint.setAntiAlias(true);
        mDayPaint.setTextSize(dayTextSize);
        mDayPaint.setTypeface(Typeface.create(dayTypeface, 0));
        mDayPaint.setTextAlign(Align.CENTER);
        mDayPaint.setStyle(Style.FILL);
    }

    void setMonthTextColor(ColorStateList monthTextColor) {
        final int enabledColor = monthTextColor.getColorForState(ENABLED_STATE_SET, 0);
        mMonthPaint.setColor(enabledColor);
        invalidate();
    }

    void setDayOfWeekTextColor(ColorStateList dayOfWeekTextColor) {
        final int enabledColor = dayOfWeekTextColor.getColorForState(ENABLED_STATE_SET, 0);
        mDayOfWeekPaint.setColor(enabledColor);
        invalidate();
    }

    void setDayTextColor(ColorStateList dayTextColor) {
        mDayTextColor = dayTextColor;
        invalidate();
    }

    void setDaySelectorColor(ColorStateList dayBackgroundColor) {
        final int activatedColor = dayBackgroundColor.getColorForState(
                StateSet.get(StateSet.VIEW_STATE_ENABLED | StateSet.VIEW_STATE_ACTIVATED), 0);
        mDaySelectorPaint.setColor(activatedColor);
        invalidate();
    }

    void setDayHighlightColor(ColorStateList dayHighlightColor) {
        final int pressedColor = dayHighlightColor.getColorForState(
                StateSet.get(StateSet.VIEW_STATE_ENABLED | StateSet.VIEW_STATE_PRESSED), 0);
        mDayHighlightPaint.setColor(pressedColor);
        invalidate();
    }

    public void setOnDayClickListener(OnDayClickListener listener) {
        mOnDayClickListener = listener;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // First right-of-refusal goes the touch exploration helper.
        return mTouchHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                final int touchedDay = getDayAtLocation(event.getX(), event.getY());
                if (mTouchedDay != touchedDay) {
                    mTouchedDay = touchedDay;
                    invalidate();
                }
                break;

            case MotionEvent.ACTION_UP:
                final int clickedDay = getDayAtLocation(event.getX(), event.getY());
                onDayClicked(clickedDay);
                // Fall through.
            case MotionEvent.ACTION_CANCEL:
                // Reset touched day on stream end.
                mTouchedDay = -1;
                invalidate();
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int paddingLeft = getPaddingLeft();
        final int paddingTop = getPaddingTop();
        canvas.translate(paddingLeft, paddingTop);

        drawMonth(canvas);
        drawDaysOfWeek(canvas);
        drawDays(canvas);

        canvas.translate(-paddingLeft, -paddingTop);
    }

    private void drawMonth(Canvas canvas) {
        final float x = mPaddedWidth / 2f;

        // Vertically centered within the month header height.
        final float lineHeight = mMonthPaint.ascent() + mMonthPaint.descent();
        final float y = (mMonthHeight - lineHeight) / 2f;

        canvas.drawText(getTitle().toString(), x, y, mMonthPaint);
    }

    private void drawDaysOfWeek(Canvas canvas) {
        final float cellWidthHalf = mPaddedWidth / (DAYS_IN_WEEK * 2);

        // Vertically centered within the cell height.
        final float lineHeight = mDayOfWeekPaint.ascent() + mDayOfWeekPaint.descent();
        final float y = mMonthHeight + (mDayOfWeekHeight - lineHeight) / 2f;

        for (int i = 0; i < DAYS_IN_WEEK; i++) {
            final int calendarDay = (i + mWeekStart) % DAYS_IN_WEEK;
            mDayLabelCalendar.set(Calendar.DAY_OF_WEEK, calendarDay);

            final String dayLabel = mDayOfWeekFormatter.format(mDayLabelCalendar.getTime());
            final float x = (2 * i + 1) * cellWidthHalf;
            canvas.drawText(dayLabel, x, y, mDayOfWeekPaint);
        }
    }

    /**
     * Draws the month days.
     */
    private void drawDays(Canvas canvas) {
        final int cellWidthHalf = mPaddedWidth / (DAYS_IN_WEEK * 2);

        // Vertically centered within the cell height.
        final float halfLineHeight = (mDayPaint.ascent() + mDayPaint.descent()) / 2;
        float centerY = mMonthHeight + mDayOfWeekHeight + mDayHeight / 2f;

        for (int day = 1, j = findDayOffset(); day <= mDaysInMonth; day++) {
            final int x = (2 * j + 1) * cellWidthHalf;
            int stateMask = 0;

            if (day >= mEnabledDayStart && day <= mEnabledDayEnd) {
                stateMask |= StateSet.VIEW_STATE_ENABLED;
            }

            final boolean isDayActivated = mActivatedDay == day;
            if (isDayActivated) {
                stateMask |= StateSet.VIEW_STATE_ACTIVATED;

                // Adjust the circle to be centered on the row.
                canvas.drawCircle(x, centerY, mDaySelectorRadius, mDaySelectorPaint);
            } else if (mTouchedDay == day) {
                stateMask |= StateSet.VIEW_STATE_PRESSED;

                // Adjust the circle to be centered on the row.
                canvas.drawCircle(x, centerY, mDaySelectorRadius, mDayHighlightPaint);
            }

            final boolean isDayToday = mToday == day;
            final int dayTextColor;
            if (isDayToday && !isDayActivated) {
                dayTextColor = mDaySelectorPaint.getColor();
            } else {
                final int[] stateSet = StateSet.get(stateMask);
                dayTextColor = mDayTextColor.getColorForState(stateSet, 0);
            }
            mDayPaint.setColor(dayTextColor);

            canvas.drawText("" + day, x, centerY - halfLineHeight, mDayPaint);

            j++;

            if (j == DAYS_IN_WEEK) {
                j = 0;
                centerY += mDayHeight;
            }
        }
    }

    private static boolean isValidDayOfWeek(int day) {
        return day >= Calendar.SUNDAY && day <= Calendar.SATURDAY;
    }

    private static boolean isValidMonth(int month) {
        return month >= Calendar.JANUARY && month <= Calendar.DECEMBER;
    }

    /**
     * Sets the selected day.
     *
     * @param dayOfMonth the selected day of the month, or {@code -1} to clear
     *                   the selection
     */
    public void setSelectedDay(int dayOfMonth) {
        mActivatedDay = dayOfMonth;

        // Invalidate cached accessibility information.
        mTouchHelper.invalidateRoot();
        invalidate();
    }

    /**
     * Sets the first day of the week.
     *
     * @param weekStart which day the week should start on, valid values are
     *                  {@link Calendar#SUNDAY} through {@link Calendar#SATURDAY}
     */
    public void setFirstDayOfWeek(int weekStart) {
        if (isValidDayOfWeek(weekStart)) {
            mWeekStart = weekStart;
        } else {
            mWeekStart = mCalendar.getFirstDayOfWeek();
        }

        // Invalidate cached accessibility information.
        mTouchHelper.invalidateRoot();
        invalidate();
    }

    /**
     * Sets all the parameters for displaying this week.
     * <p>
     * Parameters have a default value and will only update if a new value is
     * included, except for focus month, which will always default to no focus
     * month if no value is passed in. The only required parameter is the week
     * start.
     *
     * @param selectedDay the selected day of the month, or -1 for no selection
     * @param month the month
     * @param year the year
     * @param weekStart which day the week should start on, valid values are
     *                  {@link Calendar#SUNDAY} through {@link Calendar#SATURDAY}
     * @param enabledDayStart the first enabled day
     * @param enabledDayEnd the last enabled day
     */
    void setMonthParams(int selectedDay, int month, int year, int weekStart, int enabledDayStart,
            int enabledDayEnd) {
        mActivatedDay = selectedDay;

        if (isValidMonth(month)) {
            mMonth = month;
        }
        mYear = year;

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

        // Figure out what day today is.
        final Calendar today = Calendar.getInstance();
        mToday = -1;
        mDaysInMonth = getDaysInMonth(mMonth, mYear);
        for (int i = 0; i < mDaysInMonth; i++) {
            final int day = i + 1;
            if (sameDay(day, today)) {
                mToday = day;
            }
        }
        mNumWeeks = calculateNumRows();

        // Invalidate the old title.
        mTitle = null;

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
        mNumWeeks = MAX_WEEKS_IN_MONTH;
        requestLayout();
    }

    private int calculateNumRows() {
        final int offset = findDayOffset();
        final int dividend = (offset + mDaysInMonth) / DAYS_IN_WEEK;
        final int remainder = (offset + mDaysInMonth) % DAYS_IN_WEEK;
        return dividend + (remainder > 0 ? 1 : 0);
    }

    private boolean sameDay(int day, Calendar today) {
        return mYear == today.get(Calendar.YEAR) && mMonth == today.get(Calendar.MONTH)
                && day == today.get(Calendar.DAY_OF_MONTH);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int preferredHeight = mDayHeight * mNumWeeks + mDayOfWeekHeight + mMonthHeight
                + getPaddingTop() + getPaddingBottom();
        final int preferredWidth = mCellWidth * DAYS_IN_WEEK
                + getPaddingStart() + getPaddingEnd();
        final int resolvedWidth = resolveSize(preferredWidth, widthMeasureSpec);
        final int resolvedHeight = resolveSize(preferredHeight, heightMeasureSpec);
        setMeasuredDimension(resolvedWidth, resolvedHeight);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mPaddedWidth = w - getPaddingLeft() - getPaddingRight();
        mPaddedHeight = w - getPaddingTop() - getPaddingBottom();

        // Invalidate cached accessibility information.
        mTouchHelper.invalidateRoot();
    }

    private int findDayOffset() {
        final int offset = mDayOfWeekStart - mWeekStart;
        if (mDayOfWeekStart < mWeekStart) {
            return offset + DAYS_IN_WEEK;
        }
        return offset;
    }

    /**
     * Calculates the day of the month at the specified touch position. Returns
     * the day of the month or -1 if the position wasn't in a valid day.
     *
     * @param x the x position of the touch event
     * @param y the y position of the touch event
     * @return the day of the month at (x, y) or -1 if the position wasn't in a
     *         valid day
     */
    private int getDayAtLocation(float x, float y) {
        final int paddedX = (int) (x - getPaddingLeft() + 0.5f);
        if (paddedX < 0 || paddedX >= mPaddedWidth) {
            return -1;
        }

        final int headerHeight = mMonthHeight + mDayOfWeekHeight;
        final int paddedY = (int) (y - getPaddingTop() + 0.5f);
        if (paddedY < headerHeight || paddedY >= mPaddedHeight) {
            return -1;
        }

        final int row = (paddedY - headerHeight) / mDayHeight;
        final int col = (paddedX * DAYS_IN_WEEK) / mPaddedWidth;
        final int index = col + row * DAYS_IN_WEEK;
        final int day = index + 1 - findDayOffset();
        if (day < 1 || day > mDaysInMonth) {
            return -1;
        }

        return day;
    }

    /**
     * Calculates the bounds of the specified day.
     *
     * @param day the day of the month
     * @param outBounds the rect to populate with bounds
     */
    private boolean getBoundsForDay(int day, Rect outBounds) {
        if (day < 1 || day > mDaysInMonth) {
            return false;
        }

        final int index = day - 1 + findDayOffset();
        final int row = index / DAYS_IN_WEEK;
        final int col = index % DAYS_IN_WEEK;

        final int headerHeight = mMonthHeight + mDayOfWeekHeight;
        final int paddedY = row * mDayHeight + headerHeight;
        final int paddedX = col * mPaddedWidth;

        final int y = paddedY + getPaddingTop();
        final int x = paddedX + getPaddingLeft();

        final int cellHeight = mDayHeight;
        final int cellWidth = mPaddedWidth / DAYS_IN_WEEK;
        outBounds.set(x, y, (x + cellWidth), (y + cellHeight));

        return true;
    }

    /**
     * Called when the user clicks on a day. Handles callbacks to the
     * {@link OnDayClickListener} if one is set.
     *
     * @param day the day that was clicked
     */
    private void onDayClicked(int day) {
        if (mOnDayClickListener != null) {
            Calendar date = Calendar.getInstance();
            date.set(mYear, mMonth, day);
            mOnDayClickListener.onDayClick(this, date);
        }

        // This is a no-op if accessibility is turned off.
        mTouchHelper.sendEventForVirtualView(day, AccessibilityEvent.TYPE_VIEW_CLICKED);
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

        @Override
        protected int getVirtualViewAt(float x, float y) {
            final int day = getDayAtLocation(x, y);
            if (day >= 0) {
                return day;
            }
            return ExploreByTouchHelper.INVALID_ID;
        }

        @Override
        protected void getVisibleVirtualViews(IntArray virtualViewIds) {
            for (int day = 1; day <= mDaysInMonth; day++) {
                virtualViewIds.add(day);
            }
        }

        @Override
        protected void onPopulateEventForVirtualView(int virtualViewId, AccessibilityEvent event) {
            event.setContentDescription(getItemDescription(virtualViewId));
        }

        @Override
        protected void onPopulateNodeForVirtualView(int virtualViewId, AccessibilityNodeInfo node) {
            final boolean hasBounds = getBoundsForDay(virtualViewId, mTempRect);

            if (!hasBounds) {
                // The day is invalid, kill the node.
                mTempRect.setEmpty();
                node.setContentDescription("");
                node.setBoundsInParent(mTempRect);
                node.setVisibleToUser(false);
                return;
            }

            node.setContentDescription(getItemDescription(virtualViewId));
            node.setBoundsInParent(mTempRect);
            node.addAction(AccessibilityAction.ACTION_CLICK);

            if (virtualViewId == mActivatedDay) {
                node.setSelected(true);
            }

        }

        @Override
        protected boolean onPerformActionForVirtualView(int virtualViewId, int action,
                Bundle arguments) {
            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK:
                    onDayClicked(virtualViewId);
                    return true;
            }

            return false;
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

            if (day == mActivatedDay) {
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
