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
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.MathUtils;

import java.util.Calendar;
import java.util.Locale;

import libcore.icu.LocaleData;

class CalendarViewMaterialDelegate extends CalendarView.AbstractCalendarViewDelegate {
    private final DayPickerView mDayPickerView;

    private CalendarView.OnDateChangeListener mOnDateChangeListener;

    public CalendarViewMaterialDelegate(CalendarView delegator, Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(delegator, context);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.CalendarView, defStyleAttr, defStyleRes);
        final int firstDayOfWeek = a.getInt(R.styleable.CalendarView_firstDayOfWeek,
                LocaleData.get(Locale.getDefault()).firstDayOfWeek);

        final long minDate = parseDateToMillis(a.getString(
                R.styleable.CalendarView_minDate), DEFAULT_MIN_DATE);
        final long maxDate = parseDateToMillis(a.getString(
                R.styleable.CalendarView_maxDate), DEFAULT_MAX_DATE);
        if (maxDate < minDate) {
            throw new IllegalArgumentException("max date cannot be before min date");
        }

        final long setDate = MathUtils.constrain(System.currentTimeMillis(), minDate, maxDate);
        final int dateTextAppearanceResId = a.getResourceId(
                R.styleable.CalendarView_dateTextAppearance,
                R.style.TextAppearance_DeviceDefault_Small);

        a.recycle();

        mDayPickerView = new DayPickerView(context);
        mDayPickerView.setFirstDayOfWeek(firstDayOfWeek);
        mDayPickerView.setCalendarTextAppearance(dateTextAppearanceResId);
        mDayPickerView.setMinDate(minDate);
        mDayPickerView.setMaxDate(maxDate);
        mDayPickerView.setDate(setDate, false, true);
        mDayPickerView.setOnDaySelectedListener(mOnDaySelectedListener);

        delegator.addView(mDayPickerView);
    }

    private long parseDateToMillis(String dateStr, String defaultDateStr) {
        final Calendar tempCalendar = Calendar.getInstance();
        if (TextUtils.isEmpty(dateStr) || !parseDate(dateStr, tempCalendar)) {
            parseDate(defaultDateStr, tempCalendar);
        }
        return tempCalendar.getTimeInMillis();
    }

    @Override
    public void setShownWeekCount(int count) {
        // Deprecated.
    }

    @Override
    public int getShownWeekCount() {
        // Deprecated.
        return 0;
    }

    @Override
    public void setSelectedWeekBackgroundColor(int color) {
        // TODO: Should use a ColorStateList. Deprecate?
    }

    @Override
    public int getSelectedWeekBackgroundColor() {
        return 0;
    }

    @Override
    public void setFocusedMonthDateColor(int color) {
        // TODO: Should use a ColorStateList. Deprecate?
    }

    @Override
    public int getFocusedMonthDateColor() {
        return 0;
    }

    @Override
    public void setUnfocusedMonthDateColor(int color) {
        // TODO: Should use a ColorStateList. Deprecate?
    }

    @Override
    public int getUnfocusedMonthDateColor() {
        return 0;
    }

    @Override
    public void setWeekDayTextAppearance(int resourceId) {

    }

    @Override
    public int getWeekDayTextAppearance() {
        return 0;
    }

    @Override
    public void setDateTextAppearance(int resourceId) {

    }

    @Override
    public int getDateTextAppearance() {
        return 0;
    }

    @Override
    public void setWeekNumberColor(int color) {
        // Deprecated.
    }

    @Override
    public int getWeekNumberColor() {
        // Deprecated.
        return 0;
    }

    @Override
    public void setWeekSeparatorLineColor(int color) {
        // Deprecated.
    }

    @Override
    public int getWeekSeparatorLineColor() {
        // Deprecated.
        return 0;
    }

    @Override
    public void setSelectedDateVerticalBar(int resourceId) {
        // Deprecated.
    }

    @Override
    public void setSelectedDateVerticalBar(Drawable drawable) {
        // Deprecated.
    }

    @Override
    public Drawable getSelectedDateVerticalBar() {
        // Deprecated.
        return null;
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
    public void setShowWeekNumber(boolean showWeekNumber) {
        // Deprecated.
    }

    @Override
    public boolean getShowWeekNumber() {
        // Deprecated.
        return false;
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
        mDayPickerView.setDate(date, true, false);
    }

    @Override
    public void setDate(long date, boolean animate, boolean center) {
        mDayPickerView.setDate(date, animate, center);
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
    public void onConfigurationChanged(Configuration newConfig) {
        // Nothing to do here, configuration changes are already propagated
        // by ViewGroup.
    }

    private final DayPickerView.OnDaySelectedListener mOnDaySelectedListener =
            new DayPickerView.OnDaySelectedListener() {
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
