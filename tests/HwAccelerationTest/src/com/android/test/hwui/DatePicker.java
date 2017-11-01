/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.test.hwui;

import android.annotation.Widget;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * A view for selecting a month / year / day based on a calendar like layout.
 *
 * <p>See the <a href="{@docRoot}resources/tutorials/views/hello-datepicker.html">Date Picker
 * tutorial</a>.</p>
 *
 * For a dialog using this view, see {@link android.app.DatePickerDialog}.
 */
@Widget
public class DatePicker extends FrameLayout {

    private static final int DEFAULT_START_YEAR = 1900;
    private static final int DEFAULT_END_YEAR = 2100;

    /* UI Components */
    private final CheckBox mYearToggle;
    private final NumberPicker mDayPicker;
    private final NumberPicker mMonthPicker;
    private final NumberPicker mYearPicker;

    /**
     * How we notify users the date has changed.
     */
    private OnDateChangedListener mOnDateChangedListener;

    private int mDay;
    private int mMonth;
    private int mYear;
    private boolean mYearOptional = true;
    private boolean mHasYear;

    /**
     * The callback used to indicate the user changes the date.
     */
    public interface OnDateChangedListener {

        /**
         * @param view The view associated with this listener.
         * @param year The year that was set.
         * @param monthOfYear The month that was set (0-11) for compatibility
         *  with {@link java.util.Calendar}.
         * @param dayOfMonth The day of the month that was set.
         */
        void onDateChanged(DatePicker view, int year, int monthOfYear, int dayOfMonth);
    }

    public DatePicker(Context context) {
        this(context, null);
    }

    public DatePicker(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    @SuppressWarnings("deprecation")
    public DatePicker(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        ContextThemeWrapper themed = new ContextThemeWrapper(context,
                com.android.internal.R.style.Theme_Holo_Light_Dialog_Alert);
        LayoutInflater inflater = (LayoutInflater) themed.getSystemService(
                        Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.date_picker, this, true);

        mDayPicker = findViewById(R.id.day);
        mDayPicker.setFormatter(NumberPicker.getTwoDigitFormatter());
        mDayPicker.setOnLongPressUpdateInterval(100);
        mDayPicker.setOnValueChangedListener(new NumberPicker.OnValueChangeListener() {
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                mDay = newVal;
                notifyDateChanged();
            }
        });
        mMonthPicker = findViewById(R.id.month);
        mMonthPicker.setFormatter(NumberPicker.getTwoDigitFormatter());
        DateFormatSymbols dfs = new DateFormatSymbols();
        String[] months = dfs.getShortMonths();

        /*
         * If the user is in a locale where the month names are numeric,
         * use just the number instead of the "month" character for
         * consistency with the other fields.
         */
        if (months[0].startsWith("1")) {
            for (int i = 0; i < months.length; i++) {
                months[i] = String.valueOf(i + 1);
            }
            mMonthPicker.setMinValue(1);
            mMonthPicker.setMaxValue(12);
        } else {
            mMonthPicker.setMinValue(1);
            mMonthPicker.setMaxValue(12);
            mMonthPicker.setDisplayedValues(months);
        }

        mMonthPicker.setOnLongPressUpdateInterval(200);
        mMonthPicker.setOnValueChangedListener(new NumberPicker.OnValueChangeListener() {
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {

                /* We display the month 1-12 but store it 0-11 so always
                 * subtract by one to ensure our internal state is always 0-11
                 */
                mMonth = newVal - 1;
                // Adjust max day of the month
                adjustMaxDay();
                notifyDateChanged();
                updateDaySpinner();
            }
        });
        mYearPicker = findViewById(R.id.year);
        mYearPicker.setOnLongPressUpdateInterval(100);
        mYearPicker.setOnValueChangedListener(new NumberPicker.OnValueChangeListener() {
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                mYear = newVal;
                // Adjust max day for leap years if needed
                adjustMaxDay();
                notifyDateChanged();
                updateDaySpinner();
            }
        });

        mYearToggle = findViewById(R.id.yearToggle);
        mYearToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mHasYear = isChecked;
                adjustMaxDay();
                notifyDateChanged();
                updateSpinners();
            }
        });

        // attributes
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.DatePicker);

        int mStartYear =
                a.getInt(com.android.internal.R.styleable.DatePicker_startYear, DEFAULT_START_YEAR);
        int mEndYear =
                a.getInt(com.android.internal.R.styleable.DatePicker_endYear, DEFAULT_END_YEAR);
        mYearPicker.setMinValue(mStartYear);
        mYearPicker.setMaxValue(mEndYear);

        a.recycle();

        // initialize to current date
        Calendar cal = Calendar.getInstance();
        init(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), null);

        // re-order the number pickers to match the current date format
        reorderPickers(months);

        if (!isEnabled()) {
            setEnabled(false);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mDayPicker.setEnabled(enabled);
        mMonthPicker.setEnabled(enabled);
        mYearPicker.setEnabled(enabled);
    }

    private void reorderPickers(String[] months) {
        java.text.DateFormat format;
        String order;

        /*
         * If the user is in a locale where the medium date format is
         * still numeric (Japanese and Czech, for example), respect
         * the date format order setting.  Otherwise, use the order
         * that the locale says is appropriate for a spelled-out date.
         */

        if (months[0].startsWith("1")) {
            format = DateFormat.getDateFormat(getContext());
        } else {
            format = DateFormat.getMediumDateFormat(getContext());
        }

        if (format instanceof SimpleDateFormat) {
            order = ((SimpleDateFormat) format).toPattern();
        } else {
            // Shouldn't happen, but just in case.
            order = new String(DateFormat.getDateFormatOrder(getContext()));
        }

        /* Remove the 3 pickers from their parent and then add them back in the
         * required order.
         */
        LinearLayout parent = findViewById(R.id.parent);
        parent.removeAllViews();

        boolean quoted = false;
        boolean didDay = false, didMonth = false, didYear = false;

        for (int i = 0; i < order.length(); i++) {
            char c = order.charAt(i);

            if (c == '\'') {
                quoted = !quoted;
            }

            if (!quoted) {
                if (c == DateFormat.DATE && !didDay) {
                    parent.addView(mDayPicker);
                    didDay = true;
                } else if ((c == DateFormat.MONTH || c == 'L') && !didMonth) {
                    parent.addView(mMonthPicker);
                    didMonth = true;
                } else if (c == DateFormat.YEAR && !didYear) {
                    parent.addView (mYearPicker);
                    didYear = true;
                }
            }
        }

        // Shouldn't happen, but just in case.
        if (!didMonth) {
            parent.addView(mMonthPicker);
        }
        if (!didDay) {
            parent.addView(mDayPicker);
        }
        if (!didYear) {
            parent.addView(mYearPicker);
        }
    }

    public void updateDate(int year, int monthOfYear, int dayOfMonth) {
        if (mYear != year || mMonth != monthOfYear || mDay != dayOfMonth) {
            mYear = (mYearOptional && year == 0) ? getCurrentYear() : year;
            mMonth = monthOfYear;
            mDay = dayOfMonth;
            updateSpinners();
            reorderPickers(new DateFormatSymbols().getShortMonths());
            notifyDateChanged();
        }
    }

    private static int getCurrentYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    private static class SavedState extends BaseSavedState {

        private final int mYear;
        private final int mMonth;
        private final int mDay;
        private final boolean mHasYear;
        private final boolean mYearOptional;

        /**
         * Constructor called from {@link DatePicker#onSaveInstanceState()}
         */
        private SavedState(Parcelable superState, int year, int month, int day, boolean hasYear,
                boolean yearOptional) {
            super(superState);
            mYear = year;
            mMonth = month;
            mDay = day;
            mHasYear = hasYear;
            mYearOptional = yearOptional;
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            mYear = in.readInt();
            mMonth = in.readInt();
            mDay = in.readInt();
            mHasYear = in.readInt() != 0;
            mYearOptional = in.readInt() != 0;
        }

        public int getYear() {
            return mYear;
        }

        public int getMonth() {
            return mMonth;
        }

        public int getDay() {
            return mDay;
        }

        public boolean hasYear() {
            return mHasYear;
        }

        public boolean isYearOptional() {
            return mYearOptional;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mYear);
            dest.writeInt(mMonth);
            dest.writeInt(mDay);
            dest.writeInt(mHasYear ? 1 : 0);
            dest.writeInt(mYearOptional ? 1 : 0);
        }

        @SuppressWarnings("unused")
        public static final Parcelable.Creator<SavedState> CREATOR =
                new Creator<SavedState>() {

                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }

                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }


    /**
     * Override so we are in complete control of save / restore for this widget.
     */
    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        dispatchThawSelfOnly(container);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();

        return new SavedState(superState, mYear, mMonth, mDay, mHasYear, mYearOptional);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mYear = ss.getYear();
        mMonth = ss.getMonth();
        mDay = ss.getDay();
        mHasYear = ss.hasYear();
        mYearOptional = ss.isYearOptional();
        updateSpinners();
    }

    /**
     * Initialize the state.
     * @param year The initial year.
     * @param monthOfYear The initial month.
     * @param dayOfMonth The initial day of the month.
     * @param onDateChangedListener How user is notified date is changed by user, can be null.
     */
    public void init(int year, int monthOfYear, int dayOfMonth,
            OnDateChangedListener onDateChangedListener) {
        init(year, monthOfYear, dayOfMonth, false, onDateChangedListener);
    }

    /**
     * Initialize the state.
     * @param year The initial year or 0 if no year has been specified
     * @param monthOfYear The initial month.
     * @param dayOfMonth The initial day of the month.
     * @param yearOptional True if the user can toggle the year
     * @param onDateChangedListener How user is notified date is changed by user, can be null.
     */
    public void init(int year, int monthOfYear, int dayOfMonth, boolean yearOptional,
            OnDateChangedListener onDateChangedListener) {
        mYear = (yearOptional && year == 0) ? getCurrentYear() : year;
        mMonth = monthOfYear;
        mDay = dayOfMonth;
        mYearOptional = yearOptional;
        mHasYear = !yearOptional || (year != 0);
        mOnDateChangedListener = onDateChangedListener;
        updateSpinners();
    }

    private void updateSpinners() {
        updateDaySpinner();
        mYearToggle.setChecked(mHasYear);
        mYearToggle.setVisibility(mYearOptional ? View.VISIBLE : View.GONE);
        mYearPicker.setValue(mYear);
        mYearPicker.setVisibility(mHasYear ? View.VISIBLE : View.GONE);

        /* The month display uses 1-12 but our internal state stores it
         * 0-11 so add one when setting the display.
         */
        mMonthPicker.setValue(mMonth + 1);
    }

    private void updateDaySpinner() {
        Calendar cal = Calendar.getInstance();
        // if year was not set, use 2000 as it was a leap year
        cal.set(mHasYear ? mYear : 2000, mMonth, 1);
        int max = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        mDayPicker.setMinValue(1);
        mDayPicker.setMaxValue(max);
        mDayPicker.setValue(mDay);
    }

    public int getYear() {
        return (mYearOptional && !mHasYear) ? 0 : mYear;
    }

    public int getMonth() {
        return mMonth;
    }

    public int getDayOfMonth() {
        return mDay;
    }

    private void adjustMaxDay(){
        Calendar cal = Calendar.getInstance();
        // if year was not set, use 2000 as it was a leap year
        cal.set(Calendar.YEAR, mHasYear ? mYear : 2000);
        cal.set(Calendar.MONTH, mMonth);
        int max = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (mDay > max) {
            mDay = max;
        }
    }

    private void notifyDateChanged() {
        if (mOnDateChangedListener != null) {
            int year = (mYearOptional && !mHasYear) ? 0 : mYear;
            mOnDateChangedListener.onDateChanged(DatePicker.this, year, mMonth, mDay);
        }
    }
}
