/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.icu.util.Calendar;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.R;
import com.android.internal.widget.ViewPager;
import com.android.internal.widget.ViewPager.OnPageChangeListener;

class DayPickerView extends ViewGroup {
    private static final int DEFAULT_LAYOUT = R.layout.day_picker_content_material;
    private static final int DEFAULT_START_YEAR = 1900;
    private static final int DEFAULT_END_YEAR = 2100;

    private static final int[] ATTRS_TEXT_COLOR = new int[] { R.attr.textColor };

    private final Calendar mSelectedDay = Calendar.getInstance();
    private final Calendar mMinDate = Calendar.getInstance();
    private final Calendar mMaxDate = Calendar.getInstance();

    private final AccessibilityManager mAccessibilityManager;

    private final ViewPager mViewPager;
    private final ImageButton mPrevButton;
    private final ImageButton mNextButton;

    private final DayPickerPagerAdapter mAdapter;

    /** Temporary calendar used for date calculations. */
    private Calendar mTempCalendar;

    private OnDaySelectedListener mOnDaySelectedListener;

    public DayPickerView(Context context) {
        this(context, null);
    }

    public DayPickerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, R.attr.calendarViewStyle);
    }

    public DayPickerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DayPickerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mAccessibilityManager = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CalendarView, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.CalendarView,
                attrs, a, defStyleAttr, defStyleRes);

        final int firstDayOfWeek = a.getInt(R.styleable.CalendarView_firstDayOfWeek,
                Calendar.getInstance().getFirstDayOfWeek());

        final String minDate = a.getString(R.styleable.CalendarView_minDate);
        final String maxDate = a.getString(R.styleable.CalendarView_maxDate);

        final int monthTextAppearanceResId = a.getResourceId(
                R.styleable.CalendarView_monthTextAppearance,
                R.style.TextAppearance_Material_Widget_Calendar_Month);
        final int dayOfWeekTextAppearanceResId = a.getResourceId(
                R.styleable.CalendarView_weekDayTextAppearance,
                R.style.TextAppearance_Material_Widget_Calendar_DayOfWeek);
        final int dayTextAppearanceResId = a.getResourceId(
                R.styleable.CalendarView_dateTextAppearance,
                R.style.TextAppearance_Material_Widget_Calendar_Day);

        final ColorStateList daySelectorColor = a.getColorStateList(
                R.styleable.CalendarView_daySelectorColor);

        a.recycle();

        // Set up adapter.
        mAdapter = new DayPickerPagerAdapter(context,
                R.layout.date_picker_month_item_material, R.id.month_view);
        mAdapter.setMonthTextAppearance(monthTextAppearanceResId);
        mAdapter.setDayOfWeekTextAppearance(dayOfWeekTextAppearanceResId);
        mAdapter.setDayTextAppearance(dayTextAppearanceResId);
        mAdapter.setDaySelectorColor(daySelectorColor);

        final LayoutInflater inflater = LayoutInflater.from(context);
        final ViewGroup content = (ViewGroup) inflater.inflate(DEFAULT_LAYOUT, this, false);

        // Transfer all children from content to here.
        while (content.getChildCount() > 0) {
            final View child = content.getChildAt(0);
            content.removeViewAt(0);
            addView(child);
        }

        mPrevButton = findViewById(R.id.prev);
        mPrevButton.setOnClickListener(mOnClickListener);

        mNextButton = findViewById(R.id.next);
        mNextButton.setOnClickListener(mOnClickListener);

        mViewPager = findViewById(R.id.day_picker_view_pager);
        mViewPager.setAdapter(mAdapter);
        mViewPager.setOnPageChangeListener(mOnPageChangedListener);

        // Proxy the month text color into the previous and next buttons.
        if (monthTextAppearanceResId != 0) {
            final TypedArray ta = mContext.obtainStyledAttributes(null,
                    ATTRS_TEXT_COLOR, 0, monthTextAppearanceResId);
            final ColorStateList monthColor = ta.getColorStateList(0);
            if (monthColor != null) {
                mPrevButton.setImageTintList(monthColor);
                mNextButton.setImageTintList(monthColor);
            }
            ta.recycle();
        }

        // Set up min and max dates.
        final Calendar tempDate = Calendar.getInstance();
        if (!CalendarView.parseDate(minDate, tempDate)) {
            tempDate.set(DEFAULT_START_YEAR, Calendar.JANUARY, 1);
        }
        final long minDateMillis = tempDate.getTimeInMillis();

        if (!CalendarView.parseDate(maxDate, tempDate)) {
            tempDate.set(DEFAULT_END_YEAR, Calendar.DECEMBER, 31);
        }
        final long maxDateMillis = tempDate.getTimeInMillis();

        if (maxDateMillis < minDateMillis) {
            throw new IllegalArgumentException("maxDate must be >= minDate");
        }

        final long setDateMillis = MathUtils.constrain(
                System.currentTimeMillis(), minDateMillis, maxDateMillis);

        setFirstDayOfWeek(firstDayOfWeek);
        setMinDate(minDateMillis);
        setMaxDate(maxDateMillis);
        setDate(setDateMillis, false);

        // Proxy selection callbacks to our own listener.
        mAdapter.setOnDaySelectedListener(new DayPickerPagerAdapter.OnDaySelectedListener() {
            @Override
            public void onDaySelected(DayPickerPagerAdapter adapter, Calendar day) {
                if (mOnDaySelectedListener != null) {
                    mOnDaySelectedListener.onDaySelected(DayPickerView.this, day);
                }
            }
        });
    }

    private void updateButtonVisibility(int position) {
        final boolean hasPrev = position > 0;
        final boolean hasNext = position < (mAdapter.getCount() - 1);
        mPrevButton.setVisibility(hasPrev ? View.VISIBLE : View.INVISIBLE);
        mNextButton.setVisibility(hasNext ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final ViewPager viewPager = mViewPager;
        measureChild(viewPager, widthMeasureSpec, heightMeasureSpec);

        final int measuredWidthAndState = viewPager.getMeasuredWidthAndState();
        final int measuredHeightAndState = viewPager.getMeasuredHeightAndState();
        setMeasuredDimension(measuredWidthAndState, measuredHeightAndState);

        final int pagerWidth = viewPager.getMeasuredWidth();
        final int pagerHeight = viewPager.getMeasuredHeight();
        final int buttonWidthSpec = MeasureSpec.makeMeasureSpec(pagerWidth, MeasureSpec.AT_MOST);
        final int buttonHeightSpec = MeasureSpec.makeMeasureSpec(pagerHeight, MeasureSpec.AT_MOST);
        mPrevButton.measure(buttonWidthSpec, buttonHeightSpec);
        mNextButton.measure(buttonWidthSpec, buttonHeightSpec);
    }

    @Override
    public void onRtlPropertiesChanged(@ResolvedLayoutDir int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        requestLayout();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        final ImageButton leftButton;
        final ImageButton rightButton;
        if (isLayoutRtl()) {
            leftButton = mNextButton;
            rightButton = mPrevButton;
        } else {
            leftButton = mPrevButton;
            rightButton = mNextButton;
        }

        final int width = right - left;
        final int height = bottom - top;
        mViewPager.layout(0, 0, width, height);

        final SimpleMonthView monthView = (SimpleMonthView) mViewPager.getChildAt(0);
        final int monthHeight = monthView.getMonthHeight();
        final int cellWidth = monthView.getCellWidth();

        // Vertically center the previous/next buttons within the month
        // header, horizontally center within the day cell.
        final int leftDW = leftButton.getMeasuredWidth();
        final int leftDH = leftButton.getMeasuredHeight();
        final int leftIconTop = monthView.getPaddingTop() + (monthHeight - leftDH) / 2;
        final int leftIconLeft = monthView.getPaddingLeft() + (cellWidth - leftDW) / 2;
        leftButton.layout(leftIconLeft, leftIconTop, leftIconLeft + leftDW, leftIconTop + leftDH);

        final int rightDW = rightButton.getMeasuredWidth();
        final int rightDH = rightButton.getMeasuredHeight();
        final int rightIconTop = monthView.getPaddingTop() + (monthHeight - rightDH) / 2;
        final int rightIconRight = width - monthView.getPaddingRight() - (cellWidth - rightDW) / 2;
        rightButton.layout(rightIconRight - rightDW, rightIconTop,
                rightIconRight, rightIconTop + rightDH);
    }

    public void setDayOfWeekTextAppearance(int resId) {
        mAdapter.setDayOfWeekTextAppearance(resId);
    }

    public int getDayOfWeekTextAppearance() {
        return mAdapter.getDayOfWeekTextAppearance();
    }

    public void setDayTextAppearance(int resId) {
        mAdapter.setDayTextAppearance(resId);
    }

    public int getDayTextAppearance() {
        return mAdapter.getDayTextAppearance();
    }

    /**
     * Sets the currently selected date to the specified timestamp. Jumps
     * immediately to the new date. To animate to the new date, use
     * {@link #setDate(long, boolean)}.
     *
     * @param timeInMillis the target day in milliseconds
     */
    public void setDate(long timeInMillis) {
        setDate(timeInMillis, false);
    }

    /**
     * Sets the currently selected date to the specified timestamp. Jumps
     * immediately to the new date, optionally animating the transition.
     *
     * @param timeInMillis the target day in milliseconds
     * @param animate whether to smooth scroll to the new position
     */
    public void setDate(long timeInMillis, boolean animate) {
        setDate(timeInMillis, animate, true);
    }

    /**
     * Moves to the month containing the specified day, optionally setting the
     * day as selected.
     *
     * @param timeInMillis the target day in milliseconds
     * @param animate whether to smooth scroll to the new position
     * @param setSelected whether to set the specified day as selected
     */
    private void setDate(long timeInMillis, boolean animate, boolean setSelected) {
        boolean dateClamped = false;
        // Clamp the target day in milliseconds to the min or max if outside the range.
        if (timeInMillis < mMinDate.getTimeInMillis()) {
            timeInMillis = mMinDate.getTimeInMillis();
            dateClamped = true;
        } else if (timeInMillis > mMaxDate.getTimeInMillis()) {
            timeInMillis = mMaxDate.getTimeInMillis();
            dateClamped = true;
        }

        getTempCalendarForTime(timeInMillis);

        if (setSelected || dateClamped) {
            mSelectedDay.setTimeInMillis(timeInMillis);
        }

        final int position = getPositionFromDay(timeInMillis);
        if (position != mViewPager.getCurrentItem()) {
            mViewPager.setCurrentItem(position, animate);
        }

        mAdapter.setSelectedDay(mTempCalendar);
    }

    public long getDate() {
        return mSelectedDay.getTimeInMillis();
    }

    public boolean getBoundsForDate(long timeInMillis, Rect outBounds) {
        final int position = getPositionFromDay(timeInMillis);
        if (position != mViewPager.getCurrentItem()) {
            return false;
        }

        mTempCalendar.setTimeInMillis(timeInMillis);
        return mAdapter.getBoundsForDate(mTempCalendar, outBounds);
    }

    public void setFirstDayOfWeek(int firstDayOfWeek) {
        mAdapter.setFirstDayOfWeek(firstDayOfWeek);
    }

    public int getFirstDayOfWeek() {
        return mAdapter.getFirstDayOfWeek();
    }

    public void setMinDate(long timeInMillis) {
        mMinDate.setTimeInMillis(timeInMillis);
        onRangeChanged();
    }

    public long getMinDate() {
        return mMinDate.getTimeInMillis();
    }

    public void setMaxDate(long timeInMillis) {
        mMaxDate.setTimeInMillis(timeInMillis);
        onRangeChanged();
    }

    public long getMaxDate() {
        return mMaxDate.getTimeInMillis();
    }

    /**
     * Handles changes to date range.
     */
    public void onRangeChanged() {
        mAdapter.setRange(mMinDate, mMaxDate);

        // Changing the min/max date changes the selection position since we
        // don't really have stable IDs. Jumps immediately to the new position.
        setDate(mSelectedDay.getTimeInMillis(), false, false);

        updateButtonVisibility(mViewPager.getCurrentItem());
    }

    /**
     * Sets the listener to call when the user selects a day.
     *
     * @param listener The listener to call.
     */
    public void setOnDaySelectedListener(OnDaySelectedListener listener) {
        mOnDaySelectedListener = listener;
    }

    private int getDiffMonths(Calendar start, Calendar end) {
        final int diffYears = end.get(Calendar.YEAR) - start.get(Calendar.YEAR);
        return end.get(Calendar.MONTH) - start.get(Calendar.MONTH) + 12 * diffYears;
    }

    private int getPositionFromDay(long timeInMillis) {
        final int diffMonthMax = getDiffMonths(mMinDate, mMaxDate);
        final int diffMonth = getDiffMonths(mMinDate, getTempCalendarForTime(timeInMillis));
        return MathUtils.constrain(diffMonth, 0, diffMonthMax);
    }

    private Calendar getTempCalendarForTime(long timeInMillis) {
        if (mTempCalendar == null) {
            mTempCalendar = Calendar.getInstance();
        }
        mTempCalendar.setTimeInMillis(timeInMillis);
        return mTempCalendar;
    }

    /**
     * Gets the position of the view that is most prominently displayed within the list view.
     */
    public int getMostVisiblePosition() {
        return mViewPager.getCurrentItem();
    }

    public void setPosition(int position) {
        mViewPager.setCurrentItem(position, false);
    }

    private final OnPageChangeListener mOnPageChangedListener = new OnPageChangeListener() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            final float alpha = Math.abs(0.5f - positionOffset) * 2.0f;
            mPrevButton.setAlpha(alpha);
            mNextButton.setAlpha(alpha);
        }

        @Override
        public void onPageScrollStateChanged(int state) {}

        @Override
        public void onPageSelected(int position) {
            updateButtonVisibility(position);
        }
    };

    private final OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final int direction;
            if (v == mPrevButton) {
                direction = -1;
            } else if (v == mNextButton) {
                direction = 1;
            } else {
                return;
            }

            // Animation is expensive for accessibility services since it sends
            // lots of scroll and content change events.
            final boolean animate = !mAccessibilityManager.isEnabled();

            // ViewPager clamps input values, so we don't need to worry
            // about passing invalid indices.
            final int nextItem = mViewPager.getCurrentItem() + direction;
            mViewPager.setCurrentItem(nextItem, animate);
        }
    };

    public interface OnDaySelectedListener {
        void onDaySelected(DayPickerView view, Calendar day);
    }
}
