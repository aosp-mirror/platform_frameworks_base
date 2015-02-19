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

import com.android.internal.R;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SimpleMonthView.OnDayClickListener;

import java.util.Calendar;

/**
 * An adapter for a list of {@link android.widget.SimpleMonthView} items.
 */
class SimpleMonthAdapter extends BaseAdapter {
    private final Calendar mMinDate = Calendar.getInstance();
    private final Calendar mMaxDate = Calendar.getInstance();

    private final Context mContext;

    private Calendar mSelectedDay = Calendar.getInstance();
    private ColorStateList mCalendarTextColors = ColorStateList.valueOf(Color.BLACK);
    private OnDaySelectedListener mOnDaySelectedListener;

    private int mFirstDayOfWeek;

    public SimpleMonthAdapter(Context context) {
        mContext = context;
    }

    public void setRange(Calendar min, Calendar max) {
        mMinDate.setTimeInMillis(min.getTimeInMillis());
        mMaxDate.setTimeInMillis(max.getTimeInMillis());

        notifyDataSetInvalidated();
    }

    public void setFirstDayOfWeek(int firstDayOfWeek) {
        mFirstDayOfWeek = firstDayOfWeek;

        notifyDataSetInvalidated();
    }

    public int getFirstDayOfWeek() {
        return mFirstDayOfWeek;
    }

    /**
     * Updates the selected day and related parameters.
     *
     * @param day The day to highlight
     */
    public void setSelectedDay(Calendar day) {
        mSelectedDay = day;

        notifyDataSetChanged();
    }

    /**
     * Sets the listener to call when the user selects a day.
     *
     * @param listener The listener to call.
     */
    public void setOnDaySelectedListener(OnDaySelectedListener listener) {
        mOnDaySelectedListener = listener;
    }

    void setCalendarTextColor(ColorStateList colors) {
        mCalendarTextColors = colors;
    }

    /**
     * Sets the text color, size, style, hint color, and highlight color from
     * the specified TextAppearance resource. This is mostly copied from
     * {@link TextView#setTextAppearance(Context, int)}.
     */
    void setCalendarTextAppearance(int resId) {
        final TypedArray a = mContext.obtainStyledAttributes(resId, R.styleable.TextAppearance);

        final ColorStateList textColor = a.getColorStateList(R.styleable.TextAppearance_textColor);
        if (textColor != null) {
            mCalendarTextColors = textColor;
        }

        // TODO: Support font size, etc.

        a.recycle();
    }

    @Override
    public int getCount() {
        final int diffYear = mMaxDate.get(Calendar.YEAR) - mMinDate.get(Calendar.YEAR);
        final int diffMonth = mMaxDate.get(Calendar.MONTH) - mMinDate.get(Calendar.MONTH);
        return diffMonth + 12 * diffYear + 1;
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
    public boolean hasStableIds() {
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final SimpleMonthView v;
        if (convertView != null) {
            v = (SimpleMonthView) convertView;
        } else {
            v = new SimpleMonthView(mContext);

            // Set up the new view
            final AbsListView.LayoutParams params = new AbsListView.LayoutParams(
                    AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.MATCH_PARENT);
            v.setLayoutParams(params);
            v.setClickable(true);
            v.setOnDayClickListener(mOnDayClickListener);

            if (mCalendarTextColors != null) {
                v.setTextColor(mCalendarTextColors);
            }
        }

        final int minMonth = mMinDate.get(Calendar.MONTH);
        final int minYear = mMinDate.get(Calendar.YEAR);
        final int currentMonth = position + minMonth;
        final int month = currentMonth % 12;
        final int year = currentMonth / 12 + minYear;
        final int selectedDay;
        if (isSelectedDayInMonth(year, month)) {
            selectedDay = mSelectedDay.get(Calendar.DAY_OF_MONTH);
        } else {
            selectedDay = -1;
        }

        // Invokes requestLayout() to ensure that the recycled view is set with the appropriate
        // height/number of weeks before being displayed.
        v.reuse();

        final int enabledDayRangeStart;
        if (minMonth == month && minYear == year) {
            enabledDayRangeStart = mMinDate.get(Calendar.DAY_OF_MONTH);
        } else {
            enabledDayRangeStart = 1;
        }

        final int enabledDayRangeEnd;
        if (mMaxDate.get(Calendar.MONTH) == month && mMaxDate.get(Calendar.YEAR) == year) {
            enabledDayRangeEnd = mMaxDate.get(Calendar.DAY_OF_MONTH);
        } else {
            enabledDayRangeEnd = 31;
        }

        v.setMonthParams(selectedDay, month, year, mFirstDayOfWeek,
                enabledDayRangeStart, enabledDayRangeEnd);
        v.invalidate();

        return v;
    }

    private boolean isSelectedDayInMonth(int year, int month) {
        return mSelectedDay.get(Calendar.YEAR) == year && mSelectedDay.get(Calendar.MONTH) == month;
    }

    private boolean isCalendarInRange(Calendar value) {
        return value.compareTo(mMinDate) >= 0 && value.compareTo(mMaxDate) <= 0;
    }

    private final OnDayClickListener mOnDayClickListener = new OnDayClickListener() {
        @Override
        public void onDayClick(SimpleMonthView view, Calendar day) {
            if (day != null && isCalendarInRange(day)) {
                setSelectedDay(day);

                if (mOnDaySelectedListener != null) {
                    mOnDaySelectedListener.onDaySelected(SimpleMonthAdapter.this, day);
                }
            }
        }
    };

    public interface OnDaySelectedListener {
        public void onDaySelected(SimpleMonthAdapter view, Calendar day);
    }
}
