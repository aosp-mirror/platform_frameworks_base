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

import android.annotation.Nullable;
import android.annotation.Widget;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.text.InputType;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.NumberPicker.OnValueChangeListener;

import com.android.internal.R;

import java.text.DateFormatSymbols;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import libcore.icu.ICU;

/**
 * Provides a widget for selecting a date.
 * <p>
 * When the {@link android.R.styleable#DatePicker_datePickerMode} attribute is
 * set to {@code spinner}, the date can be selected using year, month, and day
 * spinners or a {@link CalendarView}. The set of spinners and the calendar
 * view are automatically synchronized. The client can customize whether only
 * the spinners, or only the calendar view, or both to be displayed.
 * </p>
 * <p>
 * When the {@link android.R.styleable#DatePicker_datePickerMode} attribute is
 * set to {@code calendar}, the month and day can be selected using a
 * calendar-style view while the year can be selected separately using a list.
 * </p>
 * <p>
 * See the <a href="{@docRoot}guide/topics/ui/controls/pickers.html">Pickers</a>
 * guide.
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
 * @attr ref android.R.styleable#DatePicker_dayOfWeekBackground
 * @attr ref android.R.styleable#DatePicker_dayOfWeekTextAppearance
 * @attr ref android.R.styleable#DatePicker_headerBackground
 * @attr ref android.R.styleable#DatePicker_headerMonthTextAppearance
 * @attr ref android.R.styleable#DatePicker_headerDayOfMonthTextAppearance
 * @attr ref android.R.styleable#DatePicker_headerYearTextAppearance
 * @attr ref android.R.styleable#DatePicker_yearListItemTextAppearance
 * @attr ref android.R.styleable#DatePicker_yearListSelectorColor
 * @attr ref android.R.styleable#DatePicker_calendarTextColor
 * @attr ref android.R.styleable#DatePicker_datePickerMode
 */
@Widget
public class DatePicker extends FrameLayout {
    private static final String LOG_TAG = DatePicker.class.getSimpleName();

    private static final int MODE_SPINNER = 1;
    private static final int MODE_CALENDAR = 2;

    private final DatePickerDelegate mDelegate;

    /**
     * The callback used to indicate the user changed the date.
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
        this(context, attrs, R.attr.datePickerStyle);
    }

    public DatePicker(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DatePicker(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DatePicker,
                defStyleAttr, defStyleRes);
        final int mode = a.getInt(R.styleable.DatePicker_datePickerMode, MODE_SPINNER);
        final int firstDayOfWeek = a.getInt(R.styleable.DatePicker_firstDayOfWeek, 0);
        a.recycle();

        switch (mode) {
            case MODE_CALENDAR:
                mDelegate = createCalendarUIDelegate(context, attrs, defStyleAttr, defStyleRes);
                break;
            case MODE_SPINNER:
            default:
                mDelegate = createSpinnerUIDelegate(context, attrs, defStyleAttr, defStyleRes);
                break;
        }

        if (firstDayOfWeek != 0) {
            setFirstDayOfWeek(firstDayOfWeek);
        }
    }

    private DatePickerDelegate createSpinnerUIDelegate(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        return new DatePickerSpinnerDelegate(this, context, attrs, defStyleAttr, defStyleRes);
    }

    private DatePickerDelegate createCalendarUIDelegate(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        return new DatePickerCalendarDelegate(this, context, attrs, defStyleAttr,
                defStyleRes);
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
        mDelegate.init(year, monthOfYear, dayOfMonth, onDateChangedListener);
    }

    /**
     * Update the current date.
     *
     * @param year The year.
     * @param month The month which is <strong>starting from zero</strong>.
     * @param dayOfMonth The day of the month.
     */
    public void updateDate(int year, int month, int dayOfMonth) {
        mDelegate.updateDate(year, month, dayOfMonth);
    }

    /**
     * @return The selected year.
     */
    public int getYear() {
        return mDelegate.getYear();
    }

    /**
     * @return The selected month.
     */
    public int getMonth() {
        return mDelegate.getMonth();
    }

    /**
     * @return The selected day of month.
     */
    public int getDayOfMonth() {
        return mDelegate.getDayOfMonth();
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
        return mDelegate.getMinDate().getTimeInMillis();
    }

    /**
     * Sets the minimal date supported by this {@link NumberPicker} in
     * milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     *
     * @param minDate The minimal supported date.
     */
    public void setMinDate(long minDate) {
        mDelegate.setMinDate(minDate);
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
        return mDelegate.getMaxDate().getTimeInMillis();
    }

    /**
     * Sets the maximal date supported by this {@link DatePicker} in
     * milliseconds since January 1, 1970 00:00:00 in
     * {@link TimeZone#getDefault()} time zone.
     *
     * @param maxDate The maximal supported date.
     */
    public void setMaxDate(long maxDate) {
        mDelegate.setMaxDate(maxDate);
    }

    /**
     * Sets the callback that indicates the current date is valid.
     *
     * @param callback the callback, may be null
     * @hide
     */
    public void setValidationCallback(@Nullable ValidationCallback callback) {
        mDelegate.setValidationCallback(callback);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (mDelegate.isEnabled() == enabled) {
            return;
        }
        super.setEnabled(enabled);
        mDelegate.setEnabled(enabled);
    }

    @Override
    public boolean isEnabled() {
        return mDelegate.isEnabled();
    }

    /** @hide */
    @Override
    public boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        return mDelegate.dispatchPopulateAccessibilityEvent(event);
    }

    /** @hide */
    @Override
    public void onPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        super.onPopulateAccessibilityEventInternal(event);
        mDelegate.onPopulateAccessibilityEvent(event);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return DatePicker.class.getName();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDelegate.onConfigurationChanged(newConfig);
    }

    /**
     * Sets the first day of week.
     *
     * @param firstDayOfWeek The first day of the week conforming to the
     *            {@link CalendarView} APIs.
     * @see Calendar#SUNDAY
     * @see Calendar#MONDAY
     * @see Calendar#TUESDAY
     * @see Calendar#WEDNESDAY
     * @see Calendar#THURSDAY
     * @see Calendar#FRIDAY
     * @see Calendar#SATURDAY
     *
     * @attr ref android.R.styleable#DatePicker_firstDayOfWeek
     */
    public void setFirstDayOfWeek(int firstDayOfWeek) {
        if (firstDayOfWeek < Calendar.SUNDAY || firstDayOfWeek > Calendar.SATURDAY) {
            throw new IllegalArgumentException("firstDayOfWeek must be between 1 and 7");
        }
        mDelegate.setFirstDayOfWeek(firstDayOfWeek);
    }

    /**
     * Gets the first day of week.
     *
     * @return The first day of the week conforming to the {@link CalendarView}
     *         APIs.
     * @see Calendar#SUNDAY
     * @see Calendar#MONDAY
     * @see Calendar#TUESDAY
     * @see Calendar#WEDNESDAY
     * @see Calendar#THURSDAY
     * @see Calendar#FRIDAY
     * @see Calendar#SATURDAY
     *
     * @attr ref android.R.styleable#DatePicker_firstDayOfWeek
     */
    public int getFirstDayOfWeek() {
        return mDelegate.getFirstDayOfWeek();
    }

    /**
     * Returns whether the {@link CalendarView} is shown.
     * <p>
     * <strong>Note:</strong> This method returns {@code false} when the
     * {@link android.R.styleable#DatePicker_datePickerMode} attribute is set
     * to {@code calendar}.
     *
     * @return {@code true} if the calendar view is shown
     * @see #getCalendarView()
     */
    public boolean getCalendarViewShown() {
        return mDelegate.getCalendarViewShown();
    }

    /**
     * Returns the {@link CalendarView} used by this picker.
     * <p>
     * <strong>Note:</strong> This method returns {@code null} when the
     * {@link android.R.styleable#DatePicker_datePickerMode} attribute is set
     * to {@code calendar}.
     *
     * @return the calendar view
     * @see #getCalendarViewShown()
     */
    public CalendarView getCalendarView() {
        return mDelegate.getCalendarView();
    }

    /**
     * Sets whether the {@link CalendarView} is shown.
     * <p>
     * <strong>Note:</strong> Calling this method has no effect when the
     * {@link android.R.styleable#DatePicker_datePickerMode} attribute is set
     * to {@code calendar}.
     *
     * @param shown {@code true} to show the calendar view, {@code false} to
     *              hide it
     */
    public void setCalendarViewShown(boolean shown) {
        mDelegate.setCalendarViewShown(shown);
    }

    /**
     * Returns whether the spinners are shown.
     * <p>
     * <strong>Note:</strong> his method returns {@code false} when the
     * {@link android.R.styleable#DatePicker_datePickerMode} attribute is set
     * to {@code calendar}.
     *
     * @return {@code true} if the spinners are shown
     */
    public boolean getSpinnersShown() {
        return mDelegate.getSpinnersShown();
    }

    /**
     * Sets whether the spinners are shown.
     * <p>
     * Calling this method has no effect when the
     * {@link android.R.styleable#DatePicker_datePickerMode} attribute is set
     * to {@code calendar}.
     *
     * @param shown {@code true} to show the spinners, {@code false} to hide
     *              them
     */
    public void setSpinnersShown(boolean shown) {
        mDelegate.setSpinnersShown(shown);
    }

    @Override
    protected void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        dispatchThawSelfOnly(container);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        return mDelegate.onSaveInstanceState(superState);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        BaseSavedState ss = (BaseSavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mDelegate.onRestoreInstanceState(ss);
    }

    /**
     * A delegate interface that defined the public API of the DatePicker. Allows different
     * DatePicker implementations. This would need to be implemented by the DatePicker delegates
     * for the real behavior.
     *
     * @hide
     */
    interface DatePickerDelegate {
        void init(int year, int monthOfYear, int dayOfMonth,
                  OnDateChangedListener onDateChangedListener);

        void updateDate(int year, int month, int dayOfMonth);

        int getYear();
        int getMonth();
        int getDayOfMonth();

        void setFirstDayOfWeek(int firstDayOfWeek);
        int getFirstDayOfWeek();

        void setMinDate(long minDate);
        Calendar getMinDate();

        void setMaxDate(long maxDate);
        Calendar getMaxDate();

        void setEnabled(boolean enabled);
        boolean isEnabled();

        CalendarView getCalendarView();

        void setCalendarViewShown(boolean shown);
        boolean getCalendarViewShown();

        void setSpinnersShown(boolean shown);
        boolean getSpinnersShown();

        void setValidationCallback(ValidationCallback callback);

        void onConfigurationChanged(Configuration newConfig);

        Parcelable onSaveInstanceState(Parcelable superState);
        void onRestoreInstanceState(Parcelable state);

        boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event);
        void onPopulateAccessibilityEvent(AccessibilityEvent event);
    }

    /**
     * An abstract class which can be used as a start for DatePicker implementations
     */
    abstract static class AbstractDatePickerDelegate implements DatePickerDelegate {
        // The delegator
        protected DatePicker mDelegator;

        // The context
        protected Context mContext;

        // The current locale
        protected Locale mCurrentLocale;

        // Callbacks
        protected OnDateChangedListener mOnDateChangedListener;
        protected ValidationCallback mValidationCallback;

        public AbstractDatePickerDelegate(DatePicker delegator, Context context) {
            mDelegator = delegator;
            mContext = context;

            setCurrentLocale(Locale.getDefault());
        }

        protected void setCurrentLocale(Locale locale) {
            if (!locale.equals(mCurrentLocale)) {
                mCurrentLocale = locale;
                onLocaleChanged(locale);
            }
        }

        @Override
        public void setValidationCallback(ValidationCallback callback) {
            mValidationCallback = callback;
        }

        protected void onValidationChanged(boolean valid) {
            if (mValidationCallback != null) {
                mValidationCallback.onValidationChanged(valid);
            }
        }

        protected void onLocaleChanged(Locale locale) {
            // Stub.
        }
    }

    /**
     * A callback interface for updating input validity when the date picker
     * when included into a dialog.
     *
     * @hide
     */
    public static interface ValidationCallback {
        void onValidationChanged(boolean valid);
    }

    /**
     * A delegate implementing the basic DatePicker
     */
    private static class DatePickerSpinnerDelegate extends AbstractDatePickerDelegate {

        private static final String DATE_FORMAT = "MM/dd/yyyy";

        private static final int DEFAULT_START_YEAR = 1900;

        private static final int DEFAULT_END_YEAR = 2100;

        private static final boolean DEFAULT_CALENDAR_VIEW_SHOWN = true;

        private static final boolean DEFAULT_SPINNERS_SHOWN = true;

        private static final boolean DEFAULT_ENABLED_STATE = true;

        private final LinearLayout mSpinners;

        private final NumberPicker mDaySpinner;

        private final NumberPicker mMonthSpinner;

        private final NumberPicker mYearSpinner;

        private final EditText mDaySpinnerInput;

        private final EditText mMonthSpinnerInput;

        private final EditText mYearSpinnerInput;

        private final CalendarView mCalendarView;

        private String[] mShortMonths;

        private final java.text.DateFormat mDateFormat = new SimpleDateFormat(DATE_FORMAT);

        private int mNumberOfMonths;

        private Calendar mTempDate;

        private Calendar mMinDate;

        private Calendar mMaxDate;

        private Calendar mCurrentDate;

        private boolean mIsEnabled = DEFAULT_ENABLED_STATE;

        DatePickerSpinnerDelegate(DatePicker delegator, Context context, AttributeSet attrs,
                int defStyleAttr, int defStyleRes) {
            super(delegator, context);

            mDelegator = delegator;
            mContext = context;

            // initialization based on locale
            setCurrentLocale(Locale.getDefault());

            final TypedArray attributesArray = context.obtainStyledAttributes(attrs,
                    R.styleable.DatePicker, defStyleAttr, defStyleRes);
            boolean spinnersShown = attributesArray.getBoolean(R.styleable.DatePicker_spinnersShown,
                    DEFAULT_SPINNERS_SHOWN);
            boolean calendarViewShown = attributesArray.getBoolean(
                    R.styleable.DatePicker_calendarViewShown, DEFAULT_CALENDAR_VIEW_SHOWN);
            int startYear = attributesArray.getInt(R.styleable.DatePicker_startYear,
                    DEFAULT_START_YEAR);
            int endYear = attributesArray.getInt(R.styleable.DatePicker_endYear, DEFAULT_END_YEAR);
            String minDate = attributesArray.getString(R.styleable.DatePicker_minDate);
            String maxDate = attributesArray.getString(R.styleable.DatePicker_maxDate);
            int layoutResourceId = attributesArray.getResourceId(
                    R.styleable.DatePicker_legacyLayout, R.layout.date_picker_legacy);
            attributesArray.recycle();

            LayoutInflater inflater = (LayoutInflater) context
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            inflater.inflate(layoutResourceId, mDelegator, true);

            OnValueChangeListener onChangeListener = new OnValueChangeListener() {
                public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                    updateInputState();
                    mTempDate.setTimeInMillis(mCurrentDate.getTimeInMillis());
                    // take care of wrapping of days and months to update greater fields
                    if (picker == mDaySpinner) {
                        int maxDayOfMonth = mTempDate.getActualMaximum(Calendar.DAY_OF_MONTH);
                        if (oldVal == maxDayOfMonth && newVal == 1) {
                            mTempDate.add(Calendar.DAY_OF_MONTH, 1);
                        } else if (oldVal == 1 && newVal == maxDayOfMonth) {
                            mTempDate.add(Calendar.DAY_OF_MONTH, -1);
                        } else {
                            mTempDate.add(Calendar.DAY_OF_MONTH, newVal - oldVal);
                        }
                    } else if (picker == mMonthSpinner) {
                        if (oldVal == 11 && newVal == 0) {
                            mTempDate.add(Calendar.MONTH, 1);
                        } else if (oldVal == 0 && newVal == 11) {
                            mTempDate.add(Calendar.MONTH, -1);
                        } else {
                            mTempDate.add(Calendar.MONTH, newVal - oldVal);
                        }
                    } else if (picker == mYearSpinner) {
                        mTempDate.set(Calendar.YEAR, newVal);
                    } else {
                        throw new IllegalArgumentException();
                    }
                    // now set the date to the adjusted one
                    setDate(mTempDate.get(Calendar.YEAR), mTempDate.get(Calendar.MONTH),
                            mTempDate.get(Calendar.DAY_OF_MONTH));
                    updateSpinners();
                    updateCalendarView();
                    notifyDateChanged();
                }
            };

            mSpinners = (LinearLayout) mDelegator.findViewById(R.id.pickers);

            // calendar view day-picker
            mCalendarView = (CalendarView) mDelegator.findViewById(R.id.calendar_view);
            mCalendarView.setOnDateChangeListener(new CalendarView.OnDateChangeListener() {
                public void onSelectedDayChange(CalendarView view, int year, int month, int monthDay) {
                    setDate(year, month, monthDay);
                    updateSpinners();
                    notifyDateChanged();
                }
            });

            // day
            mDaySpinner = (NumberPicker) mDelegator.findViewById(R.id.day);
            mDaySpinner.setFormatter(NumberPicker.getTwoDigitFormatter());
            mDaySpinner.setOnLongPressUpdateInterval(100);
            mDaySpinner.setOnValueChangedListener(onChangeListener);
            mDaySpinnerInput = (EditText) mDaySpinner.findViewById(R.id.numberpicker_input);

            // month
            mMonthSpinner = (NumberPicker) mDelegator.findViewById(R.id.month);
            mMonthSpinner.setMinValue(0);
            mMonthSpinner.setMaxValue(mNumberOfMonths - 1);
            mMonthSpinner.setDisplayedValues(mShortMonths);
            mMonthSpinner.setOnLongPressUpdateInterval(200);
            mMonthSpinner.setOnValueChangedListener(onChangeListener);
            mMonthSpinnerInput = (EditText) mMonthSpinner.findViewById(R.id.numberpicker_input);

            // year
            mYearSpinner = (NumberPicker) mDelegator.findViewById(R.id.year);
            mYearSpinner.setOnLongPressUpdateInterval(100);
            mYearSpinner.setOnValueChangedListener(onChangeListener);
            mYearSpinnerInput = (EditText) mYearSpinner.findViewById(R.id.numberpicker_input);

            // show only what the user required but make sure we
            // show something and the spinners have higher priority
            if (!spinnersShown && !calendarViewShown) {
                setSpinnersShown(true);
            } else {
                setSpinnersShown(spinnersShown);
                setCalendarViewShown(calendarViewShown);
            }

            // set the min date giving priority of the minDate over startYear
            mTempDate.clear();
            if (!TextUtils.isEmpty(minDate)) {
                if (!parseDate(minDate, mTempDate)) {
                    mTempDate.set(startYear, 0, 1);
                }
            } else {
                mTempDate.set(startYear, 0, 1);
            }
            setMinDate(mTempDate.getTimeInMillis());

            // set the max date giving priority of the maxDate over endYear
            mTempDate.clear();
            if (!TextUtils.isEmpty(maxDate)) {
                if (!parseDate(maxDate, mTempDate)) {
                    mTempDate.set(endYear, 11, 31);
                }
            } else {
                mTempDate.set(endYear, 11, 31);
            }
            setMaxDate(mTempDate.getTimeInMillis());

            // initialize to current date
            mCurrentDate.setTimeInMillis(System.currentTimeMillis());
            init(mCurrentDate.get(Calendar.YEAR), mCurrentDate.get(Calendar.MONTH), mCurrentDate
                    .get(Calendar.DAY_OF_MONTH), null);

            // re-order the number spinners to match the current date format
            reorderSpinners();

            // accessibility
            setContentDescriptions();

            // If not explicitly specified this view is important for accessibility.
            if (mDelegator.getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                mDelegator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            }
        }

        @Override
        public void init(int year, int monthOfYear, int dayOfMonth,
                         OnDateChangedListener onDateChangedListener) {
            setDate(year, monthOfYear, dayOfMonth);
            updateSpinners();
            updateCalendarView();
            mOnDateChangedListener = onDateChangedListener;
        }

        @Override
        public void updateDate(int year, int month, int dayOfMonth) {
            if (!isNewDate(year, month, dayOfMonth)) {
                return;
            }
            setDate(year, month, dayOfMonth);
            updateSpinners();
            updateCalendarView();
            notifyDateChanged();
        }

        @Override
        public int getYear() {
            return mCurrentDate.get(Calendar.YEAR);
        }

        @Override
        public int getMonth() {
            return mCurrentDate.get(Calendar.MONTH);
        }

        @Override
        public int getDayOfMonth() {
            return mCurrentDate.get(Calendar.DAY_OF_MONTH);
        }

        @Override
        public void setFirstDayOfWeek(int firstDayOfWeek) {
            mCalendarView.setFirstDayOfWeek(firstDayOfWeek);
        }

        @Override
        public int getFirstDayOfWeek() {
            return mCalendarView.getFirstDayOfWeek();
        }

        @Override
        public void setMinDate(long minDate) {
            mTempDate.setTimeInMillis(minDate);
            if (mTempDate.get(Calendar.YEAR) == mMinDate.get(Calendar.YEAR)
                    && mTempDate.get(Calendar.DAY_OF_YEAR) != mMinDate.get(Calendar.DAY_OF_YEAR)) {
                return;
            }
            mMinDate.setTimeInMillis(minDate);
            mCalendarView.setMinDate(minDate);
            if (mCurrentDate.before(mMinDate)) {
                mCurrentDate.setTimeInMillis(mMinDate.getTimeInMillis());
                updateCalendarView();
            }
            updateSpinners();
        }

        @Override
        public Calendar getMinDate() {
            final Calendar minDate = Calendar.getInstance();
            minDate.setTimeInMillis(mCalendarView.getMinDate());
            return minDate;
        }

        @Override
        public void setMaxDate(long maxDate) {
            mTempDate.setTimeInMillis(maxDate);
            if (mTempDate.get(Calendar.YEAR) == mMaxDate.get(Calendar.YEAR)
                    && mTempDate.get(Calendar.DAY_OF_YEAR) != mMaxDate.get(Calendar.DAY_OF_YEAR)) {
                return;
            }
            mMaxDate.setTimeInMillis(maxDate);
            mCalendarView.setMaxDate(maxDate);
            if (mCurrentDate.after(mMaxDate)) {
                mCurrentDate.setTimeInMillis(mMaxDate.getTimeInMillis());
                updateCalendarView();
            }
            updateSpinners();
        }

        @Override
        public Calendar getMaxDate() {
            final Calendar maxDate = Calendar.getInstance();
            maxDate.setTimeInMillis(mCalendarView.getMaxDate());
            return maxDate;
        }

        @Override
        public void setEnabled(boolean enabled) {
            mDaySpinner.setEnabled(enabled);
            mMonthSpinner.setEnabled(enabled);
            mYearSpinner.setEnabled(enabled);
            mCalendarView.setEnabled(enabled);
            mIsEnabled = enabled;
        }

        @Override
        public boolean isEnabled() {
            return mIsEnabled;
        }

        @Override
        public CalendarView getCalendarView() {
            return mCalendarView;
        }

        @Override
        public void setCalendarViewShown(boolean shown) {
            mCalendarView.setVisibility(shown ? VISIBLE : GONE);
        }

        @Override
        public boolean getCalendarViewShown() {
            return (mCalendarView.getVisibility() == View.VISIBLE);
        }

        @Override
        public void setSpinnersShown(boolean shown) {
            mSpinners.setVisibility(shown ? VISIBLE : GONE);
        }

        @Override
        public boolean getSpinnersShown() {
            return mSpinners.isShown();
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            setCurrentLocale(newConfig.locale);
        }

        @Override
        public Parcelable onSaveInstanceState(Parcelable superState) {
            return new SavedState(superState, getYear(), getMonth(), getDayOfMonth());
        }

        @Override
        public void onRestoreInstanceState(Parcelable state) {
            SavedState ss = (SavedState) state;
            setDate(ss.mYear, ss.mMonth, ss.mDay);
            updateSpinners();
            updateCalendarView();
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            onPopulateAccessibilityEvent(event);
            return true;
        }

        @Override
        public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
            final int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR;
            String selectedDateUtterance = DateUtils.formatDateTime(mContext,
                    mCurrentDate.getTimeInMillis(), flags);
            event.getText().add(selectedDateUtterance);
        }

        /**
         * Sets the current locale.
         *
         * @param locale The current locale.
         */
        @Override
        protected void setCurrentLocale(Locale locale) {
            super.setCurrentLocale(locale);

            mTempDate = getCalendarForLocale(mTempDate, locale);
            mMinDate = getCalendarForLocale(mMinDate, locale);
            mMaxDate = getCalendarForLocale(mMaxDate, locale);
            mCurrentDate = getCalendarForLocale(mCurrentDate, locale);

            mNumberOfMonths = mTempDate.getActualMaximum(Calendar.MONTH) + 1;
            mShortMonths = new DateFormatSymbols().getShortMonths();

            if (usingNumericMonths()) {
                // We're in a locale where a date should either be all-numeric, or all-text.
                // All-text would require custom NumberPicker formatters for day and year.
                mShortMonths = new String[mNumberOfMonths];
                for (int i = 0; i < mNumberOfMonths; ++i) {
                    mShortMonths[i] = String.format("%d", i + 1);
                }
            }
        }

        /**
         * Tests whether the current locale is one where there are no real month names,
         * such as Chinese, Japanese, or Korean locales.
         */
        private boolean usingNumericMonths() {
            return Character.isDigit(mShortMonths[Calendar.JANUARY].charAt(0));
        }

        /**
         * Gets a calendar for locale bootstrapped with the value of a given calendar.
         *
         * @param oldCalendar The old calendar.
         * @param locale The locale.
         */
        private Calendar getCalendarForLocale(Calendar oldCalendar, Locale locale) {
            if (oldCalendar == null) {
                return Calendar.getInstance(locale);
            } else {
                final long currentTimeMillis = oldCalendar.getTimeInMillis();
                Calendar newCalendar = Calendar.getInstance(locale);
                newCalendar.setTimeInMillis(currentTimeMillis);
                return newCalendar;
            }
        }

        /**
         * Reorders the spinners according to the date format that is
         * explicitly set by the user and if no such is set fall back
         * to the current locale's default format.
         */
        private void reorderSpinners() {
            mSpinners.removeAllViews();
            // We use numeric spinners for year and day, but textual months. Ask icu4c what
            // order the user's locale uses for that combination. http://b/7207103.
            String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "yyyyMMMdd");
            char[] order = ICU.getDateFormatOrder(pattern);
            final int spinnerCount = order.length;
            for (int i = 0; i < spinnerCount; i++) {
                switch (order[i]) {
                    case 'd':
                        mSpinners.addView(mDaySpinner);
                        setImeOptions(mDaySpinner, spinnerCount, i);
                        break;
                    case 'M':
                        mSpinners.addView(mMonthSpinner);
                        setImeOptions(mMonthSpinner, spinnerCount, i);
                        break;
                    case 'y':
                        mSpinners.addView(mYearSpinner);
                        setImeOptions(mYearSpinner, spinnerCount, i);
                        break;
                    default:
                        throw new IllegalArgumentException(Arrays.toString(order));
                }
            }
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

        private boolean isNewDate(int year, int month, int dayOfMonth) {
            return (mCurrentDate.get(Calendar.YEAR) != year
                    || mCurrentDate.get(Calendar.MONTH) != month
                    || mCurrentDate.get(Calendar.DAY_OF_MONTH) != dayOfMonth);
        }

        private void setDate(int year, int month, int dayOfMonth) {
            mCurrentDate.set(year, month, dayOfMonth);
            if (mCurrentDate.before(mMinDate)) {
                mCurrentDate.setTimeInMillis(mMinDate.getTimeInMillis());
            } else if (mCurrentDate.after(mMaxDate)) {
                mCurrentDate.setTimeInMillis(mMaxDate.getTimeInMillis());
            }
        }

        private void updateSpinners() {
            // set the spinner ranges respecting the min and max dates
            if (mCurrentDate.equals(mMinDate)) {
                mDaySpinner.setMinValue(mCurrentDate.get(Calendar.DAY_OF_MONTH));
                mDaySpinner.setMaxValue(mCurrentDate.getActualMaximum(Calendar.DAY_OF_MONTH));
                mDaySpinner.setWrapSelectorWheel(false);
                mMonthSpinner.setDisplayedValues(null);
                mMonthSpinner.setMinValue(mCurrentDate.get(Calendar.MONTH));
                mMonthSpinner.setMaxValue(mCurrentDate.getActualMaximum(Calendar.MONTH));
                mMonthSpinner.setWrapSelectorWheel(false);
            } else if (mCurrentDate.equals(mMaxDate)) {
                mDaySpinner.setMinValue(mCurrentDate.getActualMinimum(Calendar.DAY_OF_MONTH));
                mDaySpinner.setMaxValue(mCurrentDate.get(Calendar.DAY_OF_MONTH));
                mDaySpinner.setWrapSelectorWheel(false);
                mMonthSpinner.setDisplayedValues(null);
                mMonthSpinner.setMinValue(mCurrentDate.getActualMinimum(Calendar.MONTH));
                mMonthSpinner.setMaxValue(mCurrentDate.get(Calendar.MONTH));
                mMonthSpinner.setWrapSelectorWheel(false);
            } else {
                mDaySpinner.setMinValue(1);
                mDaySpinner.setMaxValue(mCurrentDate.getActualMaximum(Calendar.DAY_OF_MONTH));
                mDaySpinner.setWrapSelectorWheel(true);
                mMonthSpinner.setDisplayedValues(null);
                mMonthSpinner.setMinValue(0);
                mMonthSpinner.setMaxValue(11);
                mMonthSpinner.setWrapSelectorWheel(true);
            }

            // make sure the month names are a zero based array
            // with the months in the month spinner
            String[] displayedValues = Arrays.copyOfRange(mShortMonths,
                    mMonthSpinner.getMinValue(), mMonthSpinner.getMaxValue() + 1);
            mMonthSpinner.setDisplayedValues(displayedValues);

            // year spinner range does not change based on the current date
            mYearSpinner.setMinValue(mMinDate.get(Calendar.YEAR));
            mYearSpinner.setMaxValue(mMaxDate.get(Calendar.YEAR));
            mYearSpinner.setWrapSelectorWheel(false);

            // set the spinner values
            mYearSpinner.setValue(mCurrentDate.get(Calendar.YEAR));
            mMonthSpinner.setValue(mCurrentDate.get(Calendar.MONTH));
            mDaySpinner.setValue(mCurrentDate.get(Calendar.DAY_OF_MONTH));

            if (usingNumericMonths()) {
                mMonthSpinnerInput.setRawInputType(InputType.TYPE_CLASS_NUMBER);
            }
        }

        /**
         * Updates the calendar view with the current date.
         */
        private void updateCalendarView() {
            mCalendarView.setDate(mCurrentDate.getTimeInMillis(), false, false);
        }


        /**
         * Notifies the listener, if such, for a change in the selected date.
         */
        private void notifyDateChanged() {
            mDelegator.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            if (mOnDateChangedListener != null) {
                mOnDateChangedListener.onDateChanged(mDelegator, getYear(), getMonth(),
                        getDayOfMonth());
            }
        }

        /**
         * Sets the IME options for a spinner based on its ordering.
         *
         * @param spinner The spinner.
         * @param spinnerCount The total spinner count.
         * @param spinnerIndex The index of the given spinner.
         */
        private void setImeOptions(NumberPicker spinner, int spinnerCount, int spinnerIndex) {
            final int imeOptions;
            if (spinnerIndex < spinnerCount - 1) {
                imeOptions = EditorInfo.IME_ACTION_NEXT;
            } else {
                imeOptions = EditorInfo.IME_ACTION_DONE;
            }
            TextView input = (TextView) spinner.findViewById(R.id.numberpicker_input);
            input.setImeOptions(imeOptions);
        }

        private void setContentDescriptions() {
            // Day
            trySetContentDescription(mDaySpinner, R.id.increment,
                    R.string.date_picker_increment_day_button);
            trySetContentDescription(mDaySpinner, R.id.decrement,
                    R.string.date_picker_decrement_day_button);
            // Month
            trySetContentDescription(mMonthSpinner, R.id.increment,
                    R.string.date_picker_increment_month_button);
            trySetContentDescription(mMonthSpinner, R.id.decrement,
                    R.string.date_picker_decrement_month_button);
            // Year
            trySetContentDescription(mYearSpinner, R.id.increment,
                    R.string.date_picker_increment_year_button);
            trySetContentDescription(mYearSpinner, R.id.decrement,
                    R.string.date_picker_decrement_year_button);
        }

        private void trySetContentDescription(View root, int viewId, int contDescResId) {
            View target = root.findViewById(viewId);
            if (target != null) {
                target.setContentDescription(mContext.getString(contDescResId));
            }
        }

        private void updateInputState() {
            // Make sure that if the user changes the value and the IME is active
            // for one of the inputs if this widget, the IME is closed. If the user
            // changed the value via the IME and there is a next input the IME will
            // be shown, otherwise the user chose another means of changing the
            // value and having the IME up makes no sense.
            InputMethodManager inputMethodManager = InputMethodManager.peekInstance();
            if (inputMethodManager != null) {
                if (inputMethodManager.isActive(mYearSpinnerInput)) {
                    mYearSpinnerInput.clearFocus();
                    inputMethodManager.hideSoftInputFromWindow(mDelegator.getWindowToken(), 0);
                } else if (inputMethodManager.isActive(mMonthSpinnerInput)) {
                    mMonthSpinnerInput.clearFocus();
                    inputMethodManager.hideSoftInputFromWindow(mDelegator.getWindowToken(), 0);
                } else if (inputMethodManager.isActive(mDaySpinnerInput)) {
                    mDaySpinnerInput.clearFocus();
                    inputMethodManager.hideSoftInputFromWindow(mDelegator.getWindowToken(), 0);
                }
            }
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
