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

import com.android.internal.R;

import android.annotation.Widget;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.widget.NumberPicker.OnChangeListener;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * A view for selecting a month / year / day based on a calendar like layout.
 * <p>
 * See the <a href="{@docRoot}
 * resources/tutorials/views/hello-datepicker.html">Date Picker tutorial</a>.
 * </p>
 * For a dialog using this view, see {@link android.app.DatePickerDialog}.
 */
@Widget
public class DatePicker extends FrameLayout {

    private static final int DEFAULT_START_YEAR = 1900;

    private static final int DEFAULT_END_YEAR = 2100;

    private final NumberPicker mDayPicker;

    private final NumberPicker mMonthPicker;

    private final NumberPicker mYearPicker;

    private final DayPicker mMiniMonthDayPicker;

    private OnDateChangedListener mOnDateChangedListener;

    private Locale mMonthLocale;

    private final Calendar mTempCalendar = Calendar.getInstance();

    private final int mNumberOfMonths = mTempCalendar.getActualMaximum(Calendar.MONTH) + 1;

    private final String[] mShortMonths = new String[mNumberOfMonths];

    /**
     * The callback used to indicate the user changes the date.
     */
    public interface OnDateChangedListener {

        /**
         * @param view The view associated with this listener.
         * @param year The year that was set.
         * @param monthOfYear The month that was set (0-11) for compatibility
         *            with {@link java.util.Calendar}.
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

        LayoutInflater inflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.date_picker, this, true);

        OnChangeListener onChangeListener = new OnChangeListener() {
            public void onChanged(NumberPicker picker, int oldVal, int newVal) {
                notifyDateChanged();
                updateMiniMonth();
            }
        };

        // day
        mDayPicker = (NumberPicker) findViewById(R.id.day);
        mDayPicker.setFormatter(NumberPicker.TWO_DIGIT_FORMATTER);
        mDayPicker.setSpeed(100);
        mDayPicker.setOnChangeListener(onChangeListener);

        // month
        mMonthPicker = (NumberPicker) findViewById(R.id.month);
        mMonthPicker.setRange(0, mNumberOfMonths - 1, getShortMonths());
        mMonthPicker.setSpeed(200);
        mMonthPicker.setOnChangeListener(onChangeListener);

        // year
        mYearPicker = (NumberPicker) findViewById(R.id.year);
        mYearPicker.setSpeed(100);
        mYearPicker.setOnChangeListener(onChangeListener);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DatePicker);
        int mStartYear = a.getInt(R.styleable.DatePicker_startYear, DEFAULT_START_YEAR);
        int mEndYear = a.getInt(R.styleable.DatePicker_endYear, DEFAULT_END_YEAR);
        mYearPicker.setRange(mStartYear, mEndYear);
        a.recycle();

        // mini-month day-picker
        mMiniMonthDayPicker = (DayPicker) findViewById(R.id.mini_month_day_picker);
        mTempCalendar.clear();
        mTempCalendar.set(mStartYear, 0, 1);
        Calendar endRangeDate = (Calendar) mTempCalendar.clone();
        endRangeDate.set(mEndYear, 11, 31);
        mMiniMonthDayPicker.setRange(mTempCalendar, endRangeDate);
        mMiniMonthDayPicker.setOnDateChangeListener(new DayPicker.OnSelectedDayChangeListener() {
            public void onSelectedDayChange(DayPicker view, int year, int month, int monthDay) {
                updateDate(year, month, monthDay);
            }
        });
        
        // initialize to current date
        mTempCalendar.setTimeInMillis(System.currentTimeMillis());
        init(mTempCalendar.get(Calendar.YEAR), mTempCalendar.get(Calendar.MONTH),
                mTempCalendar.get(Calendar.DAY_OF_MONTH), null);

        // re-order the number pickers to match the current date format
        reorderPickers();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mDayPicker.setEnabled(enabled);
        mMonthPicker.setEnabled(enabled);
        mYearPicker.setEnabled(enabled);
        mMiniMonthDayPicker.setEnabled(enabled);
    }

    /**
     * Reorders the pickers according to the date format in the current locale.
     */
    private void reorderPickers() {
        java.text.DateFormat format;
        String order;

        /*
         * If the user is in a locale where the medium date format is still
         * numeric (Japanese and Czech, for example), respect the date format
         * order setting. Otherwise, use the order that the locale says is
         * appropriate for a spelled-out date.
         */

        if (getShortMonths()[0].startsWith("1")) {
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

        /*
         * Remove the 3 pickers from their parent and then add them back in the
         * required order.
         */
        LinearLayout parent = (LinearLayout) findViewById(R.id.pickers);
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
                    parent.addView(mYearPicker);
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

    /**
     * Updates the current date.
     *
     * @param year The year.
     * @param month The month which is <strong>starting from zero</strong>.
     * @param dayOfMonth The day of the month.
     */
    public void updateDate(int year, int month, int dayOfMonth) {
        if (mYearPicker.getCurrent() != year
                || mDayPicker.getCurrent() != dayOfMonth
                || mMonthPicker.getCurrent() != month) {
            updatePickers(year, month, dayOfMonth);
            updateMiniMonth();
            notifyDateChanged();
        }
    }

    /**
     * @return The short month abbreviations.
     */
    private String[] getShortMonths() {
        final Locale currentLocale = Locale.getDefault();
        if (currentLocale.equals(mMonthLocale)) {
            return mShortMonths;
        } else {
            for (int i = 0; i < mNumberOfMonths; i++) {
                mShortMonths[i] = DateUtils.getMonthString(Calendar.JANUARY + i,
                        DateUtils.LENGTH_MEDIUM);
            }
            mMonthLocale = currentLocale;
            return mShortMonths;
        }
    }

    // Override so we are in complete control of save / restore for this widget.
    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        dispatchThawSelfOnly(container);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        return new SavedState(superState, mYearPicker.getCurrent(), mMonthPicker.getCurrent(),
                mDayPicker.getCurrent());
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        updatePickers(ss.mYear, ss.mMonth, ss.mDay);
    }

    /**
     * Initialize the state. If the provided values designate an inconsistent
     * date the values are normalized before updating the pickers.
     *
     * @param year The initial year.
     * @param monthOfYear The initial month <strong>starting from zero</strong>.
     * @param dayOfMonth The initial day of the month.
     * @param onDateChangedListener How user is notified date is changed by
     *            user, can be null.
     */
    public void init(int year, int monthOfYear, int dayOfMonth,
            OnDateChangedListener onDateChangedListener) {
        mOnDateChangedListener = onDateChangedListener;
        updateDate(year, monthOfYear, dayOfMonth);
    }

    /**
     * Updates the pickers with the given <code>year</code>, <code>month</code>,
     * and <code>dayOfMonth</code>. If the provided values designate an inconsistent
     * date the values are normalized before updating the pickers.
     */
    private void updatePickers(int year, int month, int dayOfMonth) {
        // make sure the date is normalized
        mTempCalendar.clear();
        mTempCalendar.set(year, month, dayOfMonth);
        mYearPicker.setCurrent(mTempCalendar.get(Calendar.YEAR));
        mMonthPicker.setCurrent(mTempCalendar.get(Calendar.MONTH));
        mDayPicker.setRange(1, mTempCalendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        mDayPicker.setCurrent(mTempCalendar.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * Updates the mini-month with the given year, month, and day selected by the
     * number pickers.
     */
    private void updateMiniMonth() {
        Calendar selectedDay = mMiniMonthDayPicker.getSelectedDay();
        if (selectedDay.get(Calendar.YEAR) != mYearPicker.getCurrent()
                || selectedDay.get(Calendar.MONTH) != mMonthPicker.getCurrent()
                || selectedDay.get(Calendar.DAY_OF_MONTH) != mDayPicker.getCurrent()) {
            mMiniMonthDayPicker.goTo(mYearPicker.getCurrent(), mMonthPicker.getCurrent(),
                    mDayPicker.getCurrent(), false, true, false);
        }
    }

    /**
     * @return The selected year.
     */
    public int getYear() {
        return mYearPicker.getCurrent();
    }

    /**
     * @return The selected month.
     */
    public int getMonth() {
        return mMonthPicker.getCurrent();
    }

    /**
     * @return The selected day of month.
     */
    public int getDayOfMonth() {
        return mDayPicker.getCurrent();
    }

    /**
     * Notifies the listener, if such, for a change in the selected date.
     */
    private void notifyDateChanged() {
        if (mOnDateChangedListener != null) {
            mOnDateChangedListener.onDateChanged(DatePicker.this, mYearPicker.getCurrent(),
                    mMonthPicker.getCurrent(), mDayPicker.getCurrent());
        }
    }

    /**
     * Class for managing state storing/restoring.
     */
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

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mYear);
            dest.writeInt(mMonth);
            dest.writeInt(mDay);
        }

        @SuppressWarnings("all")
        // suppress unused and hiding
        public static final Parcelable.Creator<SavedState> CREATOR = new Creator<SavedState>() {

            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
}
