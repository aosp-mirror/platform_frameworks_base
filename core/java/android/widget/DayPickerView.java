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

import com.android.internal.widget.ViewPager;
import com.android.internal.R;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

import libcore.icu.LocaleData;

/**
 * This displays a list of months in a calendar format with selectable days.
 */
class DayPickerView extends ViewPager {
    private static final int DEFAULT_START_YEAR = 1900;
    private static final int DEFAULT_END_YEAR = 2100;

    private final Calendar mSelectedDay = Calendar.getInstance();
    private final Calendar mMinDate = Calendar.getInstance();
    private final Calendar mMaxDate = Calendar.getInstance();

    private final ArrayList<View> mMatchParentChildren = new ArrayList<>(1);

    private final DayPickerAdapter mAdapter;

    /** Temporary calendar used for date calculations. */
    private Calendar mTempCalendar;

    private OnDaySelectedListener mOnDaySelectedListener;

    public DayPickerView(Context context) {
        this(context, null);
    }

    public DayPickerView(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.calendarViewStyle);
    }

    public DayPickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DayPickerView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CalendarView, defStyleAttr, defStyleRes);

        final int firstDayOfWeek = a.getInt(R.styleable.CalendarView_firstDayOfWeek,
                LocaleData.get(Locale.getDefault()).firstDayOfWeek);

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
        mAdapter = new DayPickerAdapter(context,
                R.layout.date_picker_month_item_material, R.id.month_view);
        mAdapter.setMonthTextAppearance(monthTextAppearanceResId);
        mAdapter.setDayOfWeekTextAppearance(dayOfWeekTextAppearanceResId);
        mAdapter.setDayTextAppearance(dayTextAppearanceResId);
        mAdapter.setDaySelectorColor(daySelectorColor);

        setAdapter(mAdapter);

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
        mAdapter.setOnDaySelectedListener(new DayPickerAdapter.OnDaySelectedListener() {
            @Override
            public void onDaySelected(DayPickerAdapter adapter, Calendar day) {
                if (mOnDaySelectedListener != null) {
                    mOnDaySelectedListener.onDaySelected(DayPickerView.this, day);
                }
            }

            @Override
            public void onNavigationClick(DayPickerAdapter view, int direction, boolean animate) {
                // ViewPager clamps input values, so we don't need to worry
                // about passing invalid indices.
                final int nextItem = getCurrentItem() + direction;
                setCurrentItem(nextItem, animate);
            }
        });
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        populate();

        // Everything below is mostly copied from FrameLayout.
        int count = getChildCount();

        final boolean measureMatchParentChildren =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY ||
                        MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY;

        int maxHeight = 0;
        int maxWidth = 0;
        int childState = 0;

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                maxWidth = Math.max(maxWidth, child.getMeasuredWidth());
                maxHeight = Math.max(maxHeight, child.getMeasuredHeight());
                childState = combineMeasuredStates(childState, child.getMeasuredState());
                if (measureMatchParentChildren) {
                    if (lp.width == LayoutParams.MATCH_PARENT ||
                            lp.height == LayoutParams.MATCH_PARENT) {
                        mMatchParentChildren.add(child);
                    }
                }
            }
        }

        // Account for padding too
        maxWidth += getPaddingLeft() + getPaddingRight();
        maxHeight += getPaddingTop() + getPaddingBottom();

        // Check against our minimum height and width
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());

        // Check against our foreground's minimum height and width
        final Drawable drawable = getForeground();
        if (drawable != null) {
            maxHeight = Math.max(maxHeight, drawable.getMinimumHeight());
            maxWidth = Math.max(maxWidth, drawable.getMinimumWidth());
        }

        setMeasuredDimension(resolveSizeAndState(maxWidth, widthMeasureSpec, childState),
                resolveSizeAndState(maxHeight, heightMeasureSpec,
                        childState << MEASURED_HEIGHT_STATE_SHIFT));

        count = mMatchParentChildren.size();
        if (count > 1) {
            for (int i = 0; i < count; i++) {
                final View child = mMatchParentChildren.get(i);

                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                final int childWidthMeasureSpec;
                final int childHeightMeasureSpec;

                if (lp.width == LayoutParams.MATCH_PARENT) {
                    childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(
                            getMeasuredWidth() - getPaddingLeft() - getPaddingRight(),
                            MeasureSpec.EXACTLY);
                } else {
                    childWidthMeasureSpec = getChildMeasureSpec(widthMeasureSpec,
                            getPaddingLeft() + getPaddingRight(),
                            lp.width);
                }

                if (lp.height == LayoutParams.MATCH_PARENT) {
                    childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(
                            getMeasuredHeight() - getPaddingTop() - getPaddingBottom(),
                            MeasureSpec.EXACTLY);
                } else {
                    childHeightMeasureSpec = getChildMeasureSpec(heightMeasureSpec,
                            getPaddingTop() + getPaddingBottom(),
                            lp.height);
                }

                child.measure(childWidthMeasureSpec, childHeightMeasureSpec);
            }
        }

        mMatchParentChildren.clear();
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
        if (setSelected) {
            mSelectedDay.setTimeInMillis(timeInMillis);
        }

        final int position = getPositionFromDay(timeInMillis);
        if (position != getCurrentItem()) {
            setCurrentItem(position, animate);
        }

        mTempCalendar.setTimeInMillis(timeInMillis);
        mAdapter.setSelectedDay(mTempCalendar);
    }

    public long getDate() {
        return mSelectedDay.getTimeInMillis();
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
        return getCurrentItem();
    }

    public interface OnDaySelectedListener {
        public void onDaySelected(DayPickerView view, Calendar day);
    }
}
