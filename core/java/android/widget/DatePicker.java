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
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.widget.NumberPicker.OnValueChangedListener;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * This class is a widget for selecting a date. The date can be selected by a
 * year, month, and day spinners or a {@link CalendarView}. The set of spinners
 * and the calendar view are automatically synchronized. The client can
 * customize whether only the spinners, or only the calendar view, or both to be
 * displayed. Also the minimal and maximal date from which dates to be selected
 * can be customized.
 * <p>
 * See the <a href="{@docRoot}
 * resources/tutorials/views/hello-datepicker.html">Date Picker tutorial</a>.
 * </p>
 * <p>
 * For a dialog using this view, see {@link android.app.DatePickerDialog}.
 * </p>
 *
 * @attr ref android.R.styleable#DatePicker_startYear
 * @attr ref android.R.styleable#DatePicker_endYear
 * @attr ref android.R.styleable#DatePicker_maxDate
 * @attr ref android.R.styleable#DatePicker_minDate
 * @attr ref android.R.styleable#DatePicker_spinnersShown
 * @attr ref android.R.styleable#DatePicker_calendarViewShown
 */
@Widget
public class DatePicker extends FrameLayout {

    private static final String LOG_TAG = DatePicker.class.getSimpleName();

    private static final String DATE_FORMAT = "MM/dd/yyyy";

    private static final int DEFAULT_START_YEAR = 1900;

    private static final int DEFAULT_END_YEAR = 2100;

    private static final boolean DEFAULT_CALENDAR_VIEW_SHOWN = true;

    private static final boolean DEFAULT_SPINNERS_SHOWN = true;

    private final NumberPicker mDaySpinner;

    private final LinearLayout mSpinners;

    private final NumberPicker mMonthSpinner;

    private final NumberPicker mYearSpinner;

    private final CalendarView mCalendarView;

    private OnDateChangedListener mOnDateChangedListener;

    private Locale mMonthLocale;

    private final Calendar mTempDate = Calendar.getInstance();

    private final int mNumberOfMonths = mTempDate.getActualMaximum(Calendar.MONTH) + 1;

    private final String[] mShortMonths = new String[mNumberOfMonths];

    private final java.text.DateFormat mDateFormat = new SimpleDateFormat(DATE_FORMAT);

    private final Calendar mMinDate = Calendar.getInstance();

    private final Calendar mMaxDate = Calendar.getInstance();

    private final Calendar mCurrentDate = Calendar.getInstance();

    /**
     * The callback used to indicate the user changes\d the date.
     */
    public interface OnDateChangedListener {

        /**
         * Called upon a date change.
         *
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

        TypedArray attributesArray = context.obtainStyledAttributes(attrs, R.styleable.DatePicker);
        boolean spinnersShown = attributesArray.getBoolean(R.styleable.DatePicker_spinnersShown,
                DEFAULT_SPINNERS_SHOWN);
        boolean calendarViewShown = attributesArray.getBoolean(
                R.styleable.DatePicker_calendarViewShown, DEFAULT_CALENDAR_VIEW_SHOWN);
        int startYear = attributesArray
                .getInt(R.styleable.DatePicker_startYear, DEFAULT_START_YEAR);
        int endYear = attributesArray.getInt(R.styleable.DatePicker_endYear, DEFAULT_END_YEAR);
        String minDate = attributesArray.getString(R.styleable.DatePicker_minDate);
        String maxDate = attributesArray.getString(R.styleable.DatePicker_maxDate);
        attributesArray.recycle();

        LayoutInflater inflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.date_picker, this, true);

        OnValueChangedListener onChangeListener = new OnValueChangedListener() {
            public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                updateDate(mYearSpinner.getValue(), mMonthSpinner.getValue(), mDaySpinner
                        .getValue());
            }
        };

        mSpinners = (LinearLayout) findViewById(R.id.pickers);

        // calendar view day-picker
        mCalendarView = (CalendarView) findViewById(R.id.calendar_view);
        mCalendarView.setOnDateChangeListener(new CalendarView.OnDateChangeListener() {
            public void onSelectedDayChange(CalendarView view, int year, int month, int monthDay) {
                updateDate(year, month, monthDay);
            }
        });

        // day
        mDaySpinner = (NumberPicker) findViewById(R.id.day);
        mDaySpinner.setFormatter(NumberPicker.TWO_DIGIT_FORMATTER);
        mDaySpinner.setOnLongPressUpdateInterval(100);
        mDaySpinner.setOnValueChangedListener(onChangeListener);

        // month
        mMonthSpinner = (NumberPicker) findViewById(R.id.month);
        mMonthSpinner.setMinValue(0);
        mMonthSpinner.setMaxValue(mNumberOfMonths - 1);
        mMonthSpinner.setDisplayedValues(getShortMonths());
        mMonthSpinner.setOnLongPressUpdateInterval(200);
        mMonthSpinner.setOnValueChangedListener(onChangeListener);

        // year
        mYearSpinner = (NumberPicker) findViewById(R.id.year);
        mYearSpinner.setOnLongPressUpdateInterval(100);
        mYearSpinner.setOnValueChangedListener(onChangeListener);

        // show only what the user required but make sure we
        // show something and the spinners have higher priority
        if (!spinnersShown && !calendarViewShown) {
            setSpinnersShown(true);
        } else {
            setSpinnersShown(spinnersShown);
            setCalendarViewShown(calendarViewShown);

            // set the min date giving priority of the minDate over startYear
            mTempDate.clear();
            if (!TextUtils.isEmpty(minDate)) {
                if (!parseDate(minDate, mTempDate)) {
                    mTempDate.set(startYear, 0, 1);
                }
            } else {
                mTempDate.set(startYear, 0, 1);
            }
            mMinDate.clear();
            setMinDate(mTempDate.getTimeInMillis());

            // set the max date giving priority of the minDate over startYear
            mTempDate.clear();
            if (!TextUtils.isEmpty(maxDate)) {
                if (!parseDate(maxDate, mTempDate)) {
                    mTempDate.set(endYear, 11, 31);
                }
            } else {
                mTempDate.set(endYear, 11, 31);
            }
            mMaxDate.clear();
            setMaxDate(mTempDate.getTimeInMillis());

            // initialize to current date
            mCurrentDate.setTimeInMillis(System.currentTimeMillis());
            init(mCurrentDate.get(Calendar.YEAR), mCurrentDate.get(Calendar.MONTH), mCurrentDate
                    .get(Calendar.DAY_OF_MONTH), null);
        }

        // re-order the number spinners to match the current date format
        reorderSpinners();
    }

    /**
     * Gets the minimal date supported by this {@link DatePicker} in
     * milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     * <p>
     * Note: The default minimal date is 01/01/1900.
     * <p>
     *
     * @return The minimal supported date.
     */
    public long getMinDate() {
        return mCalendarView.getMinDate();
    }

    /**
     * Sets the minimal date supported by this {@link NumberPicker} in
     * milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     *
     * @param minDate The minimal supported date.
     */
    public void setMinDate(long minDate) {
        mTempDate.setTimeInMillis(minDate);
        if (mTempDate.get(Calendar.YEAR) == mMinDate.get(Calendar.YEAR)
                && mTempDate.get(Calendar.DAY_OF_YEAR) != mMinDate.get(Calendar.DAY_OF_YEAR)) {
            return;
        }
        mMinDate.setTimeInMillis(minDate);
        mYearSpinner.setMinValue(mMinDate.get(Calendar.YEAR));
        mYearSpinner.setMaxValue(mMaxDate.get(Calendar.YEAR));
        mCalendarView.setMinDate(minDate);
        updateSpinners(mYearSpinner.getValue(), mMonthSpinner.getValue(), mDaySpinner.getValue());
    }

    /**
     * Gets the maximal date supported by this {@link DatePicker} in
     * milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     * <p>
     * Note: The default maximal date is 12/31/2100.
     * <p>
     *
     * @return The maximal supported date.
     */
    public long getMaxDate() {
        return mCalendarView.getMaxDate();
    }

    /**
     * Sets the maximal date supported by this {@link DatePicker} in
     * milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     *
     * @param maxDate The maximal supported date.
     */
    public void setMaxDate(long maxDate) {
        mTempDate.setTimeInMillis(maxDate);
        if (mTempDate.get(Calendar.YEAR) == mMaxDate.get(Calendar.YEAR)
                && mTempDate.get(Calendar.DAY_OF_YEAR) != mMaxDate.get(Calendar.DAY_OF_YEAR)) {
            return;
        }
        mMaxDate.setTimeInMillis(maxDate);
        mYearSpinner.setMinValue(mMinDate.get(Calendar.YEAR));
        mYearSpinner.setMaxValue(mMaxDate.get(Calendar.YEAR));
        mCalendarView.setMaxDate(maxDate);
        updateSpinners(mYearSpinner.getValue(), mMonthSpinner.getValue(), mDaySpinner.getValue());
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mDaySpinner.setEnabled(enabled);
        mMonthSpinner.setEnabled(enabled);
        mYearSpinner.setEnabled(enabled);
        mCalendarView.setEnabled(enabled);
    }

    /**
     * Gets whether the {@link CalendarView} is shown.
     *
     * @return True if the calendar view is shown.
     */
    public boolean getCalendarViewShown() {
        return mCalendarView.isShown();
    }

    /**
     * Sets whether the {@link CalendarView} is shown.
     *
     * @param shown True if the calendar view is to be shown.
     */
    public void setCalendarViewShown(boolean shown) {
        mCalendarView.setVisibility(shown ? VISIBLE : GONE);
    }

    /**
     * Gets whether the spinners are shown.
     *
     * @return True if the spinners are shown.
     */
    public boolean getSpinnersShown() {
        return mSpinners.isShown();
    }

    /**
     * Sets whether the spinners are shown.
     *
     * @param shown True if the spinners are to be shown.
     */
    public void setSpinnersShown(boolean shown) {
        mSpinners.setVisibility(shown ? VISIBLE : GONE);
    }

    /**
     * Reorders the spinners according to the date format in the current
     * {@link Locale}.
     */
    private void reorderSpinners() {
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
         * Remove the 3 spinners from their parent and then add them back in the
         * required order.
         */
        LinearLayout parent = mSpinners;
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
                    parent.addView(mDaySpinner);
                    didDay = true;
                } else if ((c == DateFormat.MONTH || c == 'L') && !didMonth) {
                    parent.addView(mMonthSpinner);
                    didMonth = true;
                } else if (c == DateFormat.YEAR && !didYear) {
                    parent.addView(mYearSpinner);
                    didYear = true;
                }
            }
        }

        // Shouldn't happen, but just in case.
        if (!didMonth) {
            parent.addView(mMonthSpinner);
        }
        if (!didDay) {
            parent.addView(mDaySpinner);
        }
        if (!didYear) {
            parent.addView(mYearSpinner);
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
        if (mCurrentDate.get(Calendar.YEAR) != year
                || mCurrentDate.get(Calendar.MONTH) != dayOfMonth
                || mCurrentDate.get(Calendar.DAY_OF_MONTH) != month) {
            updateSpinners(year, month, dayOfMonth);
            updateCalendarView();
            notifyDateChanged();
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
        return new SavedState(superState, mYearSpinner.getValue(), mMonthSpinner.getValue(),
                mDaySpinner.getValue());
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        updateSpinners(ss.mYear, ss.mMonth, ss.mDay);
    }

    /**
     * Initialize the state. If the provided values designate an inconsistent
     * date the values are normalized before updating the spinners.
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
     * Parses the given <code>date</code> and in case of success sets the result
     * to the <code>outDate</code>.
     *
     * @return True if the date was parsed.
     */
    private boolean parseDate(String date, Calendar outDate) {
        try {
            outDate.setTime(mDateFormat.parse(date));
            return true;
        } catch (ParseException e) {
            Log.w(LOG_TAG, "Date: " + date + " not in format: " + DATE_FORMAT);
            return false;
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

    /**
     * Updates the spinners with the given <code>year</code>, <code>month</code>
     * , and <code>dayOfMonth</code>. If the provided values designate an
     * inconsistent date the values are normalized before updating the spinners.
     */
    private void updateSpinners(int year, int month, int dayOfMonth) {
        mCurrentDate.set(Calendar.YEAR, year);
        int deltaMonths = getDelataMonth(month);
        mCurrentDate.add(Calendar.MONTH, deltaMonths);
        int deltaDays = getDelataDayOfMonth(dayOfMonth);
        mCurrentDate.add(Calendar.DAY_OF_MONTH, deltaDays);

        if (mCurrentDate.before(mMinDate)) {
            mCurrentDate.setTimeInMillis(mMinDate.getTimeInMillis());
        } else if (mCurrentDate.after(mMaxDate)) {
            mCurrentDate.setTimeInMillis(mMaxDate.getTimeInMillis());
        }

        mYearSpinner.setValue(mCurrentDate.get(Calendar.YEAR));
        mMonthSpinner.setValue(mCurrentDate.get(Calendar.MONTH));
        mDaySpinner.setMinValue(1);
        mDaySpinner.setMaxValue(mCurrentDate.getActualMaximum(Calendar.DAY_OF_MONTH));
        mDaySpinner.setValue(mCurrentDate.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * @return The delta days of moth from the current date and the given
     *         <code>dayOfMonth</code>.
     */
    private int getDelataDayOfMonth(int dayOfMonth) {
        int prevDayOfMonth = mCurrentDate.get(Calendar.DAY_OF_MONTH);
        if (prevDayOfMonth == dayOfMonth) {
            return 0;
        }
        int maxDayOfMonth = mCurrentDate.getActualMaximum(Calendar.DAY_OF_MONTH);
        if (dayOfMonth == 1 && prevDayOfMonth == maxDayOfMonth) {
            return 1;
        }
        if (dayOfMonth == maxDayOfMonth && prevDayOfMonth == 1) {
            return -1;
        }
        return dayOfMonth - prevDayOfMonth;
    }

    /**
     * @return The delta months from the current date and the given
     *         <code>month</code>.
     */
    private int getDelataMonth(int month) {
        int prevMonth = mCurrentDate.get(Calendar.MONTH);
        if (prevMonth == month) {
            return 0;
        }
        if (month == 0 && prevMonth == 11) {
            return 1;
        }
        if (month == 11 && prevMonth == 0) {
            return -1;
        }
        return month - prevMonth;
    }

    /**
     * Updates the calendar view with the given year, month, and day selected by
     * the number spinners.
     */
    private void updateCalendarView() {
        mTempDate.setTimeInMillis(mCalendarView.getDate());
        if (mTempDate.get(Calendar.YEAR) != mYearSpinner.getValue()
                || mTempDate.get(Calendar.MONTH) != mMonthSpinner.getValue()
                || mTempDate.get(Calendar.DAY_OF_MONTH) != mDaySpinner.getValue()) {
            mTempDate.clear();
            mTempDate.set(mYearSpinner.getValue(), mMonthSpinner.getValue(),
                    mDaySpinner.getValue());
            mCalendarView.setDate(mTempDate.getTimeInMillis(), false, false);
        }
    }

    /**
     * @return The selected year.
     */
    public int getYear() {
        return mYearSpinner.getValue();
    }

    /**
     * @return The selected month.
     */
    public int getMonth() {
        return mMonthSpinner.getValue();
    }

    /**
     * @return The selected day of month.
     */
    public int getDayOfMonth() {
        return mDaySpinner.getValue();
    }

    /**
     * Notifies the listener, if such, for a change in the selected date.
     */
    private void notifyDateChanged() {
        if (mOnDateChangedListener != null) {
            mOnDateChangedListener.onDateChanged(DatePicker.this, mYearSpinner.getValue(),
                    mMonthSpinner.getValue(), mDaySpinner.getValue());
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
