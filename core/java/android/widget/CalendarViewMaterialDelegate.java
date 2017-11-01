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

import android.annotation.StyleRes;
import android.content.Context;
import android.graphics.Rect;
import android.icu.util.Calendar;
import android.util.AttributeSet;
import android.widget.DayPickerView.OnDaySelectedListener;

class CalendarViewMaterialDelegate extends CalendarView.AbstractCalendarViewDelegate {
    private final DayPickerView mDayPickerView;

    private CalendarView.OnDateChangeListener mOnDateChangeListener;

    public CalendarViewMaterialDelegate(CalendarView delegator, Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(delegator, context);

        mDayPickerView = new DayPickerView(context, attrs, defStyleAttr, defStyleRes);
        mDayPickerView.setOnDaySelectedListener(mOnDaySelectedListener);

        delegator.addView(mDayPickerView);
    }

    @Override
    public void setWeekDayTextAppearance(@StyleRes int resId) {
        mDayPickerView.setDayOfWeekTextAppearance(resId);
    }

    @StyleRes
    @Override
    public int getWeekDayTextAppearance() {
        return mDayPickerView.getDayOfWeekTextAppearance();
    }

    @Override
    public void setDateTextAppearance(@StyleRes int resId) {
        mDayPickerView.setDayTextAppearance(resId);
    }

    @StyleRes
    @Override
    public int getDateTextAppearance() {
        return mDayPickerView.getDayTextAppearance();
    }

    @Override
    public void setMinDate(long minDate) {
        mDayPickerView.setMinDate(minDate);
    }

    @Override
    public long getMinDate() {
        return mDayPickerView.getMinDate();
    }

    @Override
    public void setMaxDate(long maxDate) {
        mDayPickerView.setMaxDate(maxDate);
    }

    @Override
    public long getMaxDate() {
        return mDayPickerView.getMaxDate();
    }

    @Override
    public void setFirstDayOfWeek(int firstDayOfWeek) {
        mDayPickerView.setFirstDayOfWeek(firstDayOfWeek);
    }

    @Override
    public int getFirstDayOfWeek() {
        return mDayPickerView.getFirstDayOfWeek();
    }

    @Override
    public void setDate(long date) {
        mDayPickerView.setDate(date, true);
    }

    @Override
    public void setDate(long date, boolean animate, boolean center) {
        mDayPickerView.setDate(date, animate);
    }

    @Override
    public long getDate() {
        return mDayPickerView.getDate();
    }

    @Override
    public void setOnDateChangeListener(CalendarView.OnDateChangeListener listener) {
        mOnDateChangeListener = listener;
    }

    @Override
    public boolean getBoundsForDate(long date, Rect outBounds) {
        boolean result = mDayPickerView.getBoundsForDate(date, outBounds);
        if (result) {
            // Found the date in the current picker. Now need to offset vertically to return correct
            // bounds in the coordinate system of the entire layout
            final int[] dayPickerPositionOnScreen = new int[2];
            final int[] delegatorPositionOnScreen = new int[2];
            mDayPickerView.getLocationOnScreen(dayPickerPositionOnScreen);
            mDelegator.getLocationOnScreen(delegatorPositionOnScreen);
            final int extraVerticalOffset =
                    dayPickerPositionOnScreen[1] - delegatorPositionOnScreen[1];
            outBounds.top += extraVerticalOffset;
            outBounds.bottom += extraVerticalOffset;
            return true;
        }
        return false;
    }

    private final OnDaySelectedListener mOnDaySelectedListener = new OnDaySelectedListener() {
        @Override
        public void onDaySelected(DayPickerView view, Calendar day) {
            if (mOnDateChangeListener != null) {
                final int year = day.get(Calendar.YEAR);
                final int month = day.get(Calendar.MONTH);
                final int dayOfMonth = day.get(Calendar.DAY_OF_MONTH);
                mOnDateChangeListener.onSelectedDayChange(mDelegator, year, month, dayOfMonth);
            }
        }
    };
}
