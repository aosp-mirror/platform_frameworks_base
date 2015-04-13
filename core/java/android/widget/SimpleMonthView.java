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
import android.graphics.drawable.Drawable;
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

    /** Virtual view ID for previous button. */
    private static final int ITEM_ID_PREV = 0x101;

    /** Virtual view ID for next button. */
    private static final int ITEM_ID_NEXT = 0x100;

    private final TextPaint mMonthPaint = new TextPaint();
    private final TextPaint mDayOfWeekPaint = new TextPaint();
    private final TextPaint mDayPaint = new TextPaint();
    private final Paint mDaySelectorPaint = new Paint();
    private final Paint mDayHighlightPaint = new Paint();

    private final Calendar mCalendar = Calendar.getInstance();
    private final Calendar mDayOfWeekLabelCalendar = Calendar.getInstance();

    private final MonthViewTouchHelper mTouchHelper;

    private final SimpleDateFormat mTitleFormatter;
    private final SimpleDateFormat mDayOfWeekFormatter;

    // Desired dimensions.
    private final int mDesiredMonthHeight;
    private final int mDesiredDayOfWeekHeight;
    private final int mDesiredDayHeight;
    private final int mDesiredCellWidth;
    private final int mDesiredDaySelectorRadius;

    // Next/previous drawables.
    private final Drawable mPrevDrawable;
    private final Drawable mNextDrawable;
    private final Rect mPrevHitArea;
    private final Rect mNextHitArea;
    private final CharSequence mPrevContentDesc;
    private final CharSequence mNextContentDesc;

    private CharSequence mTitle;

    private int mMonth;
    private int mYear;

    // Dimensions as laid out.
    private int mMonthHeight;
    private int mDayOfWeekHeight;
    private int mDayHeight;
    private int mCellWidth;
    private int mDaySelectorRadius;

    private int mPaddedWidth;
    private int mPaddedHeight;

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

    private int mTouchedItem = -1;

    private boolean mPrevEnabled;
    private boolean mNextEnabled;

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
        mDesiredMonthHeight = res.getDimensionPixelSize(R.dimen.date_picker_month_height);
        mDesiredDayOfWeekHeight = res.getDimensionPixelSize(R.dimen.date_picker_day_of_week_height);
        mDesiredDayHeight = res.getDimensionPixelSize(R.dimen.date_picker_day_height);
        mDesiredCellWidth = res.getDimensionPixelSize(R.dimen.date_picker_day_width);
        mDesiredDaySelectorRadius = res.getDimensionPixelSize(R.dimen.date_picker_day_selector_radius);

        mPrevDrawable = context.getDrawable(R.drawable.ic_chevron_left);
        mNextDrawable = context.getDrawable(R.drawable.ic_chevron_right);
        mPrevHitArea = mPrevDrawable != null ? new Rect() : null;
        mNextHitArea = mNextDrawable != null ? new Rect() : null;
        mPrevContentDesc = res.getText(R.string.date_picker_prev_month_button);
        mNextContentDesc = res.getText(R.string.date_picker_next_month_button);

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

    public void setNextEnabled(boolean enabled) {
        mNextEnabled = enabled;
        mTouchHelper.invalidateRoot();
        invalidate();
    }

    public void setPrevEnabled(boolean enabled) {
        mPrevEnabled = enabled;
        mTouchHelper.invalidateRoot();
        invalidate();
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
        final ColorStateList monthColor = applyTextAppearance(mMonthPaint, resId);
        if (monthColor != null) {
            if (mPrevDrawable != null) {
                mPrevDrawable.setTintList(monthColor);
            }
            if (mNextDrawable != null) {
                mNextDrawable.setTintList(monthColor);
            }
        }

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
        final int x = (int) (event.getX() + 0.5f);
        final int y = (int) (event.getY() + 0.5f);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                final int touchedItem = getItemAtLocation(x, y);
                if (mTouchedItem != touchedItem) {
                    mTouchedItem = touchedItem;
                    invalidate();
                }
                break;

            case MotionEvent.ACTION_UP:
                final int clickedItem = getItemAtLocation(x, y);
                onItemClicked(clickedItem, true);
                // Fall through.
            case MotionEvent.ACTION_CANCEL:
                // Reset touched day on stream end.
                mTouchedItem = -1;
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
        drawButtons(canvas);

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
        final TextPaint p = mDayOfWeekPaint;
        final int headerHeight = mMonthHeight;
        final int rowHeight = mDayOfWeekHeight;
        final int colWidth = mCellWidth;

        // Text is vertically centered within the day of week height.
        final float halfLineHeight = (p.ascent() + p.descent()) / 2f;
        final int rowCenter = headerHeight + rowHeight / 2;

        for (int col = 0; col < DAYS_IN_WEEK; col++) {
            final int colCenter = colWidth * col + colWidth / 2;
            final int dayOfWeek = (col + mWeekStart) % DAYS_IN_WEEK;
            final String label = getDayOfWeekLabel(dayOfWeek);
            canvas.drawText(label, colCenter, rowCenter - halfLineHeight, p);
        }
    }

    private String getDayOfWeekLabel(int dayOfWeek) {
        mDayOfWeekLabelCalendar.set(Calendar.DAY_OF_WEEK, dayOfWeek);
        return mDayOfWeekFormatter.format(mDayOfWeekLabelCalendar.getTime());
    }

    /**
     * Draws the month days.
     */
    private void drawDays(Canvas canvas) {
        final TextPaint p = mDayPaint;
        final int headerHeight = mMonthHeight + mDayOfWeekHeight;
        final int rowHeight = mDayHeight;
        final int colWidth = mCellWidth;

        // Text is vertically centered within the row height.
        final float halfLineHeight = (p.ascent() + p.descent()) / 2f;
        int rowCenter = headerHeight + rowHeight / 2;

        for (int day = 1, col = findDayOffset(); day <= mDaysInMonth; day++) {
            final int colCenter = colWidth * col + colWidth / 2;
            int stateMask = 0;

            if (day >= mEnabledDayStart && day <= mEnabledDayEnd) {
                stateMask |= StateSet.VIEW_STATE_ENABLED;
            }

            final boolean isDayActivated = mActivatedDay == day;
            if (isDayActivated) {
                stateMask |= StateSet.VIEW_STATE_ACTIVATED;

                // Adjust the circle to be centered on the row.
                canvas.drawCircle(colCenter, rowCenter, mDaySelectorRadius, mDaySelectorPaint);
            } else if (mTouchedItem == day) {
                stateMask |= StateSet.VIEW_STATE_PRESSED;

                // Adjust the circle to be centered on the row.
                canvas.drawCircle(colCenter, rowCenter, mDaySelectorRadius, mDayHighlightPaint);
            }

            final boolean isDayToday = mToday == day;
            final int dayTextColor;
            if (isDayToday && !isDayActivated) {
                dayTextColor = mDaySelectorPaint.getColor();
            } else {
                final int[] stateSet = StateSet.get(stateMask);
                dayTextColor = mDayTextColor.getColorForState(stateSet, 0);
            }
            p.setColor(dayTextColor);

            canvas.drawText(Integer.toString(day), colCenter, rowCenter - halfLineHeight, p);

            col++;

            if (col == DAYS_IN_WEEK) {
                col = 0;
                rowCenter += rowHeight;
            }
        }
    }

    private void drawButtons(Canvas canvas) {
        if (mPrevEnabled && mPrevDrawable != null) {
            mPrevDrawable.draw(canvas);
        }

        if (mNextEnabled && mNextDrawable != null) {
            mNextDrawable.draw(canvas);
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

    private boolean sameDay(int day, Calendar today) {
        return mYear == today.get(Calendar.YEAR) && mMonth == today.get(Calendar.MONTH)
                && day == today.get(Calendar.DAY_OF_MONTH);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int preferredHeight = mDesiredDayHeight * MAX_WEEKS_IN_MONTH
                + mDesiredDayOfWeekHeight + mDesiredMonthHeight
                + getPaddingTop() + getPaddingBottom();
        final int preferredWidth = mDesiredCellWidth * DAYS_IN_WEEK
                + getPaddingStart() + getPaddingEnd();
        final int resolvedWidth = resolveSize(preferredWidth, widthMeasureSpec);
        final int resolvedHeight = resolveSize(preferredHeight, heightMeasureSpec);
        setMeasuredDimension(resolvedWidth, resolvedHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (!changed) {
            return;
        }

        // Let's initialize a completely reasonable number of variables.
        final int w = right - left;
        final int h = bottom - top;
        final int paddingLeft = getPaddingLeft();
        final int paddingTop = getPaddingTop();
        final int paddingRight = getPaddingRight();
        final int paddingBottom = getPaddingBottom();
        final int paddedRight = w - paddingRight;
        final int paddedBottom = h - paddingBottom;
        final int paddedWidth = paddedRight - paddingLeft;
        final int paddedHeight = paddedBottom - paddingTop;
        if (paddedWidth == mPaddedWidth || paddedHeight == mPaddedHeight) {
            return;
        }

        mPaddedWidth = paddedWidth;
        mPaddedHeight = paddedHeight;

        // We may have been laid out smaller than our preferred size. If so,
        // scale all dimensions to fit.
        final int measuredPaddedHeight = getMeasuredHeight() - paddingTop - paddingBottom;
        final float scaleH = paddedHeight / (float) measuredPaddedHeight;
        final int monthHeight = (int) (mDesiredMonthHeight * scaleH);
        final int cellWidth = mPaddedWidth / DAYS_IN_WEEK;
        mMonthHeight = monthHeight;
        mDayOfWeekHeight = (int) (mDesiredDayOfWeekHeight * scaleH);
        mDayHeight = (int) (mDesiredDayHeight * scaleH);
        mCellWidth = cellWidth;

        // Compute the largest day selector radius that's still within the clip
        // bounds and desired selector radius.
        final int maxSelectorWidth = cellWidth / 2 + Math.min(paddingLeft, paddingRight);
        final int maxSelectorHeight = mDayHeight / 2 + paddingBottom;
        mDaySelectorRadius = Math.min(mDesiredDaySelectorRadius,
                Math.min(maxSelectorWidth, maxSelectorHeight));

        // Vertically center the previous/next drawables within the month
        // header, horizontally center within the day cell, then expand the
        // hit area to ensure it's at least 48x48dp.
        final Drawable prevDrawable = mPrevDrawable;
        if (prevDrawable != null) {
            final int dW = prevDrawable.getIntrinsicWidth();
            final int dH = prevDrawable.getIntrinsicHeight();
            final int iconTop = (monthHeight - dH) / 2;
            final int iconLeft = (cellWidth - dW) / 2;

            // Button bounds don't include padding, but hit area does.
            prevDrawable.setBounds(iconLeft, iconTop, iconLeft + dW, iconTop + dH);
            mPrevHitArea.set(0, 0, paddingLeft + cellWidth, paddingTop + monthHeight);
        }

        final Drawable nextDrawable = mNextDrawable;
        if (nextDrawable != null) {
            final int dW = nextDrawable.getIntrinsicWidth();
            final int dH = nextDrawable.getIntrinsicHeight();
            final int iconTop = (monthHeight - dH) / 2;
            final int iconRight = paddedWidth - (cellWidth - dW) / 2;

            // Button bounds don't include padding, but hit area does.
            nextDrawable.setBounds(iconRight - dW, iconTop, iconRight, iconTop + dH);
            mNextHitArea.set(paddedRight - cellWidth, 0, w, paddingTop + monthHeight);
        }

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
     * Calculates the day of the month or item identifier at the specified
     * touch position. Returns the day of the month or -1 if the position
     * wasn't in a valid day.
     *
     * @param x the x position of the touch event
     * @param y the y position of the touch event
     * @return the day of the month at (x, y), an item identifier, or -1 if the
     *         position wasn't in a valid day or item
     */
    private int getItemAtLocation(int x, int y) {
        if (mNextEnabled && mNextDrawable != null && mNextHitArea.contains(x, y)) {
            return ITEM_ID_NEXT;
        } else if (mPrevEnabled && mPrevDrawable != null && mPrevHitArea.contains(x, y)) {
            return ITEM_ID_PREV;
        }

        final int paddedX = x - getPaddingLeft();
        if (paddedX < 0 || paddedX >= mPaddedWidth) {
            return -1;
        }

        final int headerHeight = mMonthHeight + mDayOfWeekHeight;
        final int paddedY = y - getPaddingTop();
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
     * @param id the day of the month, or an item identifier
     * @param outBounds the rect to populate with bounds
     */
    private boolean getBoundsForItem(int id, Rect outBounds) {
        if (mNextEnabled && id == ITEM_ID_NEXT) {
            if (mNextDrawable != null) {
                outBounds.set(mNextHitArea);
                return true;
            }
        } else if (mPrevEnabled && id == ITEM_ID_PREV) {
            if (mPrevDrawable != null) {
                outBounds.set(mPrevHitArea);
                return true;
            }
        }

        if (id < 1 || id > mDaysInMonth) {
            return false;
        }

        final int index = id - 1 + findDayOffset();

        // Compute left edge.
        final int col = index % DAYS_IN_WEEK;
        final int colWidth = mCellWidth;
        final int left = getPaddingLeft() + col * colWidth;

        // Compute top edge.
        final int row = index / DAYS_IN_WEEK;
        final int rowHeight = mDayHeight;
        final int headerHeight = mMonthHeight + mDayOfWeekHeight;
        final int top = getPaddingTop() + headerHeight + row * rowHeight;

        outBounds.set(left, top, left + colWidth, top + rowHeight);
        return true;
    }

    /**
     * Called when an item is clicked.
     *
     * @param id the day number or item identifier
     */
    private boolean onItemClicked(int id, boolean animate) {
        return onNavigationClicked(id, animate) || onDayClicked(id);
    }

    /**
     * Called when the user clicks on a day. Handles callbacks to the
     * {@link OnDayClickListener} if one is set.
     *
     * @param day the day that was clicked
     */
    private boolean onDayClicked(int day) {
        if (day < 0 || day > mDaysInMonth) {
            return false;
        }

        if (mOnDayClickListener != null) {
            final Calendar date = Calendar.getInstance();
            date.set(mYear, mMonth, day);
            mOnDayClickListener.onDayClick(this, date);
        }

        // This is a no-op if accessibility is turned off.
        mTouchHelper.sendEventForVirtualView(day, AccessibilityEvent.TYPE_VIEW_CLICKED);
        return true;
    }

    /**
     * Called when the user clicks on a navigation button. Handles callbacks to
     * the {@link OnDayClickListener} if one is set.
     *
     * @param id the item identifier
     */
    private boolean onNavigationClicked(int id, boolean animate) {
        final int direction;
        if (id == ITEM_ID_NEXT) {
            direction = 1;
        } else if (id == ITEM_ID_PREV) {
            direction = -1;
        } else {
            return false;
        }

        if (mOnDayClickListener != null) {
            mOnDayClickListener.onNavigationClick(this, direction, animate);
        }

        // This is a no-op if accessibility is turned off.
        mTouchHelper.sendEventForVirtualView(id, AccessibilityEvent.TYPE_VIEW_CLICKED);
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

        @Override
        protected int getVirtualViewAt(float x, float y) {
            final int day = getItemAtLocation((int) (x + 0.5f), (int) (y + 0.5f));
            if (day >= 0) {
                return day;
            }
            return ExploreByTouchHelper.INVALID_ID;
        }

        @Override
        protected void getVisibleVirtualViews(IntArray virtualViewIds) {
            if (mNextEnabled && mNextDrawable != null) {
                virtualViewIds.add(ITEM_ID_PREV);
            }

            if (mPrevEnabled && mPrevDrawable != null) {
                virtualViewIds.add(ITEM_ID_NEXT);
            }

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
            final boolean hasBounds = getBoundsForItem(virtualViewId, mTempRect);

            if (!hasBounds) {
                // The day is invalid, kill the node.
                mTempRect.setEmpty();
                node.setContentDescription("");
                node.setBoundsInParent(mTempRect);
                node.setVisibleToUser(false);
                return;
            }

            node.setText(getItemText(virtualViewId));
            node.setContentDescription(getItemDescription(virtualViewId));
            node.setBoundsInParent(mTempRect);
            node.addAction(AccessibilityAction.ACTION_CLICK);

            if (virtualViewId == mActivatedDay) {
                // TODO: This should use activated once that's supported.
                node.setChecked(true);
            }

        }

        @Override
        protected boolean onPerformActionForVirtualView(int virtualViewId, int action,
                Bundle arguments) {
            switch (action) {
                case AccessibilityNodeInfo.ACTION_CLICK:
                    return onItemClicked(virtualViewId, false);
            }

            return false;
        }

        /**
         * Generates a description for a given virtual view.
         *
         * @param id the day or item identifier to generate a description for
         * @return a description of the virtual view
         */
        private CharSequence getItemDescription(int id) {
            if (id == ITEM_ID_NEXT) {
                return mNextContentDesc;
            } else if (id == ITEM_ID_PREV) {
                return mPrevContentDesc;
            } else if (id >= 1 && id <= mDaysInMonth) {
                mTempCalendar.set(mYear, mMonth, id);
                return DateFormat.format(DATE_FORMAT, mTempCalendar.getTimeInMillis());
            }

            return "";
        }

        /**
         * Generates displayed text for a given virtual view.
         *
         * @param id the day or item identifier to generate text for
         * @return the visible text of the virtual view
         */
        private CharSequence getItemText(int id) {
            if (id == ITEM_ID_NEXT || id == ITEM_ID_PREV) {
                return null;
            } else if (id >= 1 && id <= mDaysInMonth) {
                return Integer.toString(id);
            }

            return null;
        }
    }

    /**
     * Handles callbacks when the user clicks on a time object.
     */
    public interface OnDayClickListener {
        public void onDayClick(SimpleMonthView view, Calendar day);
        public void onNavigationClick(SimpleMonthView view, int direction, boolean animate);
    }
}
