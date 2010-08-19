/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.Widget;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.widget.NumberPicker;
import android.widget.NumberPicker.OnChangedListener;

import com.android.internal.R;

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

    public DatePicker(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.date_picker, this, true);

        mDayPicker = (NumberPicker) findViewById(R.id.day);
        mDayPicker.setFormatter(NumberPicker.TWO_DIGIT_FORMATTER);
        mDayPicker.setSpeed(100);
        mDayPicker.setOnChangeListener(new OnChangedListener() {
            public void onChanged(NumberPicker picker, int oldVal, int newVal) {
                mDay = newVal;
                notifyDateChanged();
            }
        });
        mMonthPicker = (NumberPicker) findViewById(R.id.month);
        mMonthPicker.setFormatter(NumberPicker.TWO_DIGIT_FORMATTER);
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
        }

        mMonthPicker.setRange(1, 12, months);
        mMonthPicker.setSpeed(200);
        mMonthPicker.setOnChangeListener(new OnChangedListener() {
            public void onChanged(NumberPicker picker, int oldVal, int newVal) {
                
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
        mYearPicker = (NumberPicker) findViewById(R.id.year);
        mYearPicker.setSpeed(100);
        mYearPicker.setOnChangeListener(new OnChangedListener() {
            public void onChanged(NumberPicker picker, int oldVal, int newVal) {
                mYear = newVal;
                // Adjust max day for leap years if needed
                adjustMaxDay();
                notifyDateChanged();
                updateDaySpinner();
            }
        });
        
        // attributes
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DatePicker);

        int mStartYear = a.getInt(R.styleable.DatePicker_startYear, DEFAULT_START_YEAR);
        int mEndYear = a.getInt(R.styleable.DatePicker_endYear, DEFAULT_END_YEAR);
        mYearPicker.setRange(mStartYear, mEndYear);
        
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
        LinearLayout parent = (LinearLayout) findViewById(R.id.parent);
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
            mYear = year;
            mMonth = monthOfYear;
            mDay = dayOfMonth;
            updateSpinners();
            reorderPickers(new DateFormatSymbols().getShortMonths());
            notifyDateChanged();
        }
    }

    private static class SavedState extends BaseSavedState {

        private final int mYear;
        private final int mMonth;
        private final int mDay;

        /**
         * Constructor called from {@link DatePicker#onSaveInstanceState()}
         */
        private SavedState(Parcelable superState, int year, int month, int day) {
            super(superState);
            mYear = year;
            mMonth = month;
            mDay = day;
        }
        
        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            mYear = in.readInt();
            mMonth = in.readInt();
            mDay = in.readInt();
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

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mYear);
            dest.writeInt(mMonth);
            dest.writeInt(mDay);
        }

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
        
        return new SavedState(superState, mYear, mMonth, mDay);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mYear = ss.getYear();
        mMonth = ss.getMonth();
        mDay = ss.getDay();
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
        mYear = year;
        mMonth = monthOfYear;
        mDay = dayOfMonth;
        mOnDateChangedListener = onDateChangedListener;
        updateSpinners();
    }

    private void updateSpinners() {
        updateDaySpinner();
        mYearPicker.setCurrent(mYear);
        
        /* The month display uses 1-12 but our internal state stores it
         * 0-11 so add one when setting the display.
         */
        mMonthPicker.setCurrent(mMonth + 1);
    }

    private void updateDaySpinner() {
        Calendar cal = Calendar.getInstance();
        cal.set(mYear, mMonth, mDay);
        int max = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        mDayPicker.setRange(1, max);
        mDayPicker.setCurrent(mDay);
    }

    public int getYear() {
        return mYear;
    }

    public int getMonth() {
        return mMonth;
    }

    public int getDayOfMonth() {
        return mDay;
    }

    private void adjustMaxDay(){
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, mYear);
        cal.set(Calendar.MONTH, mMonth);
        int max = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (mDay > max) {
            mDay = max;
        }
    }

    private void notifyDateChanged() {
        if (mOnDateChangedListener != null) {
            mOnDateChangedListener.onDateChanged(DatePicker.this, mYear, mMonth, mDay);
        }
    }
}
