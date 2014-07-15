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

import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import com.android.internal.R;
import com.android.internal.widget.AccessibleDateAnimator;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;

/**
 * A delegate for picking up a date (day / month / year).
 */
class DatePickerDelegate extends DatePicker.AbstractDatePickerDelegate implements
        View.OnClickListener, DatePickerController {

    private static final int UNINITIALIZED = -1;
    private static final int MONTH_AND_DAY_VIEW = 0;
    private static final int YEAR_VIEW = 1;

    private static final int DEFAULT_START_YEAR = 1900;
    private static final int DEFAULT_END_YEAR = 2100;

    private static final int PULSE_ANIMATOR_DURATION = 544;

    private static final int ANIMATION_DURATION = 300;
    private static final int ANIMATION_DELAY = 650;

    private static final int MONTH_INDEX = 0;
    private static final int DAY_INDEX = 1;
    private static final int YEAR_INDEX = 2;

    private SimpleDateFormat mYearFormat = new SimpleDateFormat("y", Locale.getDefault());
    private SimpleDateFormat mDayFormat = new SimpleDateFormat("d", Locale.getDefault());

    private TextView mDayOfWeekView;
    private LinearLayout mDateLayout;
    private LinearLayout mMonthAndDayLayout;
    private TextView mSelectedMonthTextView;
    private TextView mSelectedDayTextView;
    private TextView mSelectedYearView;
    private DayPickerView mDayPickerView;
    private YearPickerView mYearPickerView;

    private ViewGroup mLayoutButtons;

    private boolean mIsEnabled = true;

    // Accessibility strings.
    private String mDayPickerDescription;
    private String mSelectDay;
    private String mYearPickerDescription;
    private String mSelectYear;

    private AccessibleDateAnimator mAnimator;

    private DatePicker.OnDateChangedListener mDateChangedListener;

    private boolean mDelayAnimation = true;

    private int mCurrentView = UNINITIALIZED;

    private Calendar mCurrentDate;
    private Calendar mTempDate;
    private Calendar mMinDate;
    private Calendar mMaxDate;

    // For showing the done button when in a Dialog
    private Button mDoneButton;
    private boolean mShowDoneButton;
    private DatePicker.DatePickerDismissCallback mDismissCallback;

    private HashSet<OnDateChangedListener> mListeners = new HashSet<OnDateChangedListener>();

    private int mDayOfWeekTextAppearanceResId;
    private int mMonthTextAppearanceResId;
    private int mDayOfMonthTextAppearanceResId;
    private int mYearTextAppearanceResId;

    private int mYearListItemTextAppearanceResId;

    private int mDayOfWeekBackgroundColor;
    private int mMonthAndDayBackgroundColor;

    private ColorStateList mCalendarTextColors;

    public DatePickerDelegate(DatePicker delegator, Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {

        super(delegator, context);

        final Locale locale = Locale.getDefault();
        mMinDate = getCalendarForLocale(mMinDate, locale);
        mMaxDate = getCalendarForLocale(mMaxDate, locale);
        mTempDate = getCalendarForLocale(mMaxDate, locale);

        mCurrentDate = getCalendarForLocale(mCurrentDate, locale);

        mMinDate.set(DEFAULT_START_YEAR, 1, 1);
        mMaxDate.set(DEFAULT_END_YEAR, 12, 31);

        // process style attributes
        final TypedArray a = mContext.obtainStyledAttributes(attrs,
                R.styleable.DatePicker, defStyleAttr, defStyleRes);

        final LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        final int layoutResourceId = a.getResourceId(
                R.styleable.DatePicker_internalLayout, R.layout.date_picker_holo);

        View mainView = inflater.inflate(layoutResourceId, null);
        mDelegator.addView(mainView);

        mDayOfWeekView = (TextView) mainView.findViewById(R.id.date_picker_header);
        mDateLayout = (LinearLayout) mainView.findViewById(R.id.day_picker_selector_layout);
        mMonthAndDayLayout = (LinearLayout) mainView.findViewById(
                R.id.date_picker_month_and_day_layout);
        mMonthAndDayLayout.setOnClickListener(this);
        mSelectedMonthTextView = (TextView) mainView.findViewById(R.id.date_picker_month);
        mSelectedDayTextView = (TextView) mainView.findViewById(R.id.date_picker_day);
        mSelectedYearView = (TextView) mainView.findViewById(R.id.date_picker_year);
        mSelectedYearView.setOnClickListener(this);

        // Use Theme attributes if possible
        mDayOfWeekTextAppearanceResId = a.getResourceId(
                R.styleable.DatePicker_dateSelectorDayOfWeekTextAppearance, -1);
        if (mDayOfWeekTextAppearanceResId != -1) {
            mDayOfWeekView.setTextAppearance(context, mDayOfWeekTextAppearanceResId);
        }

        mMonthTextAppearanceResId = a.getResourceId(
                R.styleable.DatePicker_dateSelectorMonthTextAppearance, -1);
        if (mMonthTextAppearanceResId != -1) {
            mSelectedMonthTextView.setTextAppearance(context, mMonthTextAppearanceResId);
        }

        mDayOfMonthTextAppearanceResId = a.getResourceId(
                R.styleable.DatePicker_dateSelectorDayOfMonthTextAppearance, -1);
        if (mDayOfMonthTextAppearanceResId != -1) {
            mSelectedDayTextView.setTextAppearance(context, mDayOfMonthTextAppearanceResId);
        }

        mYearTextAppearanceResId = a.getResourceId(
                R.styleable.DatePicker_dateSelectorYearTextAppearance, -1);
        if (mYearTextAppearanceResId != -1) {
            mSelectedYearView.setTextAppearance(context, mYearTextAppearanceResId);
        }

        Resources res = mDelegator.getResources();

        mDayOfWeekBackgroundColor = a.getColor(
                R.styleable.DatePicker_dateSelectorDayOfWeekBackgroundColor,
                res.getColor(
                        R.color.datepicker_default_header_dayofweek_background_color_holo_light));
        mDayOfWeekView.setBackgroundColor(mDayOfWeekBackgroundColor);

        mMonthAndDayBackgroundColor = a.getColor(R.styleable.DatePicker_dateSelectorBackgroundColor,
                res.getColor(R.color.datepicker_default_header_selector_background_holo_light));
        mMonthAndDayLayout.setBackgroundColor(mMonthAndDayBackgroundColor);

        mDayPickerView = new DayPickerView(mContext, this);
        mYearPickerView = new YearPickerView(mContext);
        mYearPickerView.init(this);

        ColorStateList colors = a.getColorStateList(R.styleable.DatePicker_calendarTextColor);
        setCalendarTextColor(colors);

        mDayPickerDescription = res.getString(R.string.day_picker_description);
        mSelectDay = res.getString(R.string.select_day);
        mYearPickerDescription = res.getString(R.string.year_picker_description);
        mSelectYear = res.getString(R.string.select_year);

        mAnimator = (AccessibleDateAnimator) mainView.findViewById(R.id.animator);
        mAnimator.addView(mDayPickerView);
        mAnimator.addView(mYearPickerView);
        mAnimator.setDateMillis(mCurrentDate.getTimeInMillis());
        Animation animation = new AlphaAnimation(0.0f, 1.0f);
        animation.setDuration(ANIMATION_DURATION);
        mAnimator.setInAnimation(animation);
        Animation animation2 = new AlphaAnimation(1.0f, 0.0f);
        animation2.setDuration(ANIMATION_DURATION);
        mAnimator.setOutAnimation(animation2);

        mLayoutButtons = (ViewGroup) mainView.findViewById(R.id.layout_buttons);
        mDoneButton = (Button) mainView.findViewById(R.id.done);
        mDoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryVibrate();
                if (mDismissCallback != null) {
                    mDismissCallback.dismiss(mDelegator, false, mCurrentDate.get(Calendar.YEAR),
                            mCurrentDate.get(Calendar.MONTH),
                            mCurrentDate.get(Calendar.DAY_OF_MONTH));
                }
            }
        });

        updateDisplay(false);
        setCurrentView(MONTH_AND_DAY_VIEW);
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
     * Compute the array representing the order of Month / Day / Year views in their layout.
     * Will be used for I18N purpose as the order of them depends on the Locale.
     */
    private int[] getMonthDayYearIndexes(String pattern) {
        int[] result = new int[3];

        final String filteredPattern = pattern.replaceAll("'.*?'", "");

        final int dayIndex = filteredPattern.indexOf('d');
        final int monthMIndex = filteredPattern.indexOf("M");
        final int monthIndex = (monthMIndex != -1) ? monthMIndex : filteredPattern.indexOf("L");
        final int yearIndex = filteredPattern.indexOf("y");

        if (yearIndex < monthIndex) {
            result[YEAR_INDEX] = 0;

            if (monthIndex < dayIndex) {
                result[MONTH_INDEX] = 1;
                result[DAY_INDEX] = 2;
            } else {
                result[MONTH_INDEX] = 2;
                result[DAY_INDEX] = 1;
            }
        } else {
            result[YEAR_INDEX] = 2;

            if (monthIndex < dayIndex) {
                result[MONTH_INDEX] = 0;
                result[DAY_INDEX] = 1;
            } else {
                result[MONTH_INDEX] = 1;
                result[DAY_INDEX] = 0;
            }
        }
        return result;
    }

    private void updateDisplay(boolean announce) {
        if (mDayOfWeekView != null) {
            mDayOfWeekView.setText(mCurrentDate.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG,
                    Locale.getDefault()));
        }
        final String bestDateTimePattern =
                DateFormat.getBestDateTimePattern(mCurrentLocale, "yMMMd");

        // Compute indices of Month, Day and Year views
        int[] viewIndices = getMonthDayYearIndexes(bestDateTimePattern);

        // Restart from a clean state
        mMonthAndDayLayout.removeAllViews();
        mDateLayout.removeView(mSelectedYearView);

        // Position the Year View at the correct location
        if (viewIndices[YEAR_INDEX] == 0) {
            mDateLayout.addView(mSelectedYearView, 0);
        } else {
            mDateLayout.addView(mSelectedYearView, 1);
        }

        // Position Day and Month Views
        if (viewIndices[MONTH_INDEX] > viewIndices[DAY_INDEX]) {
            // Day View is first
            mMonthAndDayLayout.addView(mSelectedDayTextView);
            mMonthAndDayLayout.addView(mSelectedMonthTextView);
        } else {
            // Month View is first
            mMonthAndDayLayout.addView(mSelectedMonthTextView);
            mMonthAndDayLayout.addView(mSelectedDayTextView);
        }

        mSelectedMonthTextView.setText(mCurrentDate.getDisplayName(Calendar.MONTH, Calendar.SHORT,
                Locale.getDefault()).toUpperCase(Locale.getDefault()));
        mSelectedDayTextView.setText(mDayFormat.format(mCurrentDate.getTime()));
        mSelectedYearView.setText(mYearFormat.format(mCurrentDate.getTime()));

        // Accessibility.
        long millis = mCurrentDate.getTimeInMillis();
        mAnimator.setDateMillis(millis);
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR;
        String monthAndDayText = DateUtils.formatDateTime(mContext, millis, flags);
        mMonthAndDayLayout.setContentDescription(monthAndDayText);

        if (announce) {
            flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR;
            String fullDateText = DateUtils.formatDateTime(mContext, millis, flags);
            mAnimator.announceForAccessibility(fullDateText);
        }
        updatePickers();
    }

    private void setCurrentView(final int viewIndex) {
        long millis = mCurrentDate.getTimeInMillis();

        switch (viewIndex) {
            case MONTH_AND_DAY_VIEW:
                ObjectAnimator pulseAnimator = getPulseAnimator(mMonthAndDayLayout, 0.9f,
                        1.05f);
                if (mDelayAnimation) {
                    pulseAnimator.setStartDelay(ANIMATION_DELAY);
                    mDelayAnimation = false;
                }
                mDayPickerView.onDateChanged();
                if (mCurrentView != viewIndex) {
                    mMonthAndDayLayout.setSelected(true);
                    mSelectedYearView.setSelected(false);
                    mAnimator.setDisplayedChild(MONTH_AND_DAY_VIEW);
                    mCurrentView = viewIndex;
                }
                pulseAnimator.start();

                int flags = DateUtils.FORMAT_SHOW_DATE;
                String dayString = DateUtils.formatDateTime(mContext, millis, flags);
                mAnimator.setContentDescription(mDayPickerDescription + ": " + dayString);
                mAnimator.announceForAccessibility(mSelectDay);
                break;
            case YEAR_VIEW:
                pulseAnimator = getPulseAnimator(mSelectedYearView, 0.85f, 1.1f);
                if (mDelayAnimation) {
                    pulseAnimator.setStartDelay(ANIMATION_DELAY);
                    mDelayAnimation = false;
                }
                mYearPickerView.onDateChanged();
                if (mCurrentView != viewIndex) {
                    mMonthAndDayLayout.setSelected(false);
                    mSelectedYearView.setSelected(true);
                    mAnimator.setDisplayedChild(YEAR_VIEW);
                    mCurrentView = viewIndex;
                }
                pulseAnimator.start();

                CharSequence yearString = mYearFormat.format(millis);
                mAnimator.setContentDescription(mYearPickerDescription + ": " + yearString);
                mAnimator.announceForAccessibility(mSelectYear);
                break;
        }
    }

    @Override
    public void init(int year, int monthOfYear, int dayOfMonth,
            DatePicker.OnDateChangedListener callBack) {
        mDateChangedListener = callBack;
        mCurrentDate.set(Calendar.YEAR, year);
        mCurrentDate.set(Calendar.MONTH, monthOfYear);
        mCurrentDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        updateDisplay(false);
    }

    @Override
    public void updateDate(int year, int month, int dayOfMonth) {
        mCurrentDate.set(Calendar.YEAR, year);
        mCurrentDate.set(Calendar.MONTH, month);
        mCurrentDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
        mDateChangedListener.onDateChanged(mDelegator, year, month, dayOfMonth);
        updateDisplay(false);
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
    public void setMinDate(long minDate) {
        mTempDate.setTimeInMillis(minDate);
        if (mTempDate.get(Calendar.YEAR) == mMinDate.get(Calendar.YEAR)
                && mTempDate.get(Calendar.DAY_OF_YEAR) != mMinDate.get(Calendar.DAY_OF_YEAR)) {
            return;
        }
        if (mCurrentDate.before(mTempDate)) {
            mCurrentDate.setTimeInMillis(minDate);
            updatePickers();
            updateDisplay(false);
        }
        mMinDate.setTimeInMillis(minDate);
        mDayPickerView.goTo(getSelectedDay(), false, true, true);
    }

    @Override
    public Calendar getMinDate() {
        return mMinDate;
    }

    @Override
    public void setMaxDate(long maxDate) {
        mTempDate.setTimeInMillis(maxDate);
        if (mTempDate.get(Calendar.YEAR) == mMaxDate.get(Calendar.YEAR)
                && mTempDate.get(Calendar.DAY_OF_YEAR) != mMaxDate.get(Calendar.DAY_OF_YEAR)) {
            return;
        }
        if (mCurrentDate.after(mTempDate)) {
            mCurrentDate.setTimeInMillis(maxDate);
            updatePickers();
            updateDisplay(false);
        }
        mMaxDate.setTimeInMillis(maxDate);
        mDayPickerView.goTo(getSelectedDay(), false, true, true);
    }

    @Override
    public Calendar getMaxDate() {
        return mMaxDate;
    }

    @Override
    public int getFirstDayOfWeek() {
        return mCurrentDate.getFirstDayOfWeek();
    }

    @Override
    public int getMinYear() {
        return mMinDate.get(Calendar.YEAR);
    }

    @Override
    public int getMaxYear() {
        return mMaxDate.get(Calendar.YEAR);
    }

    @Override
    public int getMinMonth() {
        return mMinDate.get(Calendar.MONTH);
    }

    @Override
    public int getMaxMonth() {
        return mMaxDate.get(Calendar.MONTH);
    }

    @Override
    public int getMinDay() {
        return mMinDate.get(Calendar.DAY_OF_MONTH);
    }

    @Override
    public int getMaxDay() {
        return mMaxDate.get(Calendar.DAY_OF_MONTH);
    }

    @Override
    public void setEnabled(boolean enabled) {
        mMonthAndDayLayout.setEnabled(enabled);
        mSelectedYearView.setEnabled(enabled);
        mAnimator.setEnabled(enabled);
        mIsEnabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return mIsEnabled;
    }

    @Override
    public void setDateSelectorDayOfWeekBackgroundColor(int color) {
        if (mDayOfWeekBackgroundColor != color) {
            mDayOfWeekBackgroundColor = color;
            mDayOfWeekView.setBackgroundColor(color);
        }
    }

    @Override
    public int getDateSelectorDayOfWeekBackgroundColor() {
        return mDayOfWeekBackgroundColor;
    }

    @Override
    public void setDateSelectorDayOfWeekTextAppearance(int resId) {
        if (mDayOfWeekTextAppearanceResId != resId && resId > 0) {
            mDayOfWeekTextAppearanceResId = resId;
            mDayOfWeekView.setTextAppearance(mContext, resId);
        }
    }

    @Override
    public int getDateSelectorDayOfWeekTextAppearance() {
        return mDayOfWeekTextAppearanceResId;
    }

    @Override
    public void setDateSelectorBackgroundColor(int color) {
        if (mMonthAndDayBackgroundColor != color) {
            mMonthAndDayBackgroundColor = color;
            mMonthAndDayLayout.setBackgroundColor(color);
        }
    }

    @Override
    public int getDateSelectorBackgroundColor() {
        return mMonthAndDayBackgroundColor;
    }

    @Override
    public void setDateSelectorMonthTextAppearance(int resId) {
        if (mMonthTextAppearanceResId != resId && resId > 0) {
            mMonthTextAppearanceResId = resId;
            mSelectedMonthTextView.setTextAppearance(mContext, resId);
        }
    }

    @Override
    public int getDateSelectorMonthTextAppearance() {
        return mMonthTextAppearanceResId;
    }

    @Override
    public void setDateSelectorDayOfMonthTextAppearance(int resId) {
        if (mDayOfMonthTextAppearanceResId != resId && resId > 0) {
            mDayOfMonthTextAppearanceResId = resId;
            mSelectedDayTextView.setTextAppearance(mContext, resId);
        }
    }

    @Override
    public int getDateSelectorDayOfMonthTextAppearance() {
        return mDayOfMonthTextAppearanceResId;
    }

    @Override
    public void setDateSelectorYearTextAppearance(int resId) {
        if (mYearTextAppearanceResId != resId && resId > 0) {
            mYearTextAppearanceResId = resId;
            mSelectedYearView.setTextAppearance(mContext, resId);
        }
    }

    @Override
    public int getDateSelectorYearTextAppearance() {
        return mYearTextAppearanceResId;
    }

    @Override
    public void setDateSelectorYearListItemTextAppearance(int resId) {
        if (mYearListItemTextAppearanceResId != resId) {
            mYearListItemTextAppearanceResId = resId;
            mYearPickerView.setItemTextAppearance(resId);
        }
    }

    @Override
    public int getDateSelectorYearListItemTextAppearance() {
        return mYearListItemTextAppearanceResId;
    }

    @Override
    public void setDateSelectorYearListSelectedCircleColor(int color) {
        mYearPickerView.setYearSelectedCircleColor(color);
    }

    @Override
    public int getDateSelectorYearListSelectedCircleColor() {
        return mYearPickerView.getYearSelectedCircleColor();
    }

    @Override
    public void setCalendarTextColor(ColorStateList colors) {
        if (colors == null) {
            return;
        }
        if (mCalendarTextColors == null || !mCalendarTextColors.equals(colors)) {
            mCalendarTextColors = colors;
            mDayPickerView.setCalendarTextColor(colors);
        }
    }

    @Override
    public ColorStateList getCalendarTextColors() {
        return mCalendarTextColors;
    }

    @Override
    public CalendarView getCalendarView() {
        throw new UnsupportedOperationException(
                "CalendarView does not exists for the new DatePicker");
    }

    @Override
    public void setCalendarViewShown(boolean shown) {
        // No-op for compatibility with the old DatePicker.
    }

    @Override
    public boolean getCalendarViewShown() {
        return false;
    }

    @Override
    public void setSpinnersShown(boolean shown) {
        // No-op for compatibility with the old DatePicker.
    }

    @Override
    public boolean getSpinnersShown() {
        return false;
    }

    @Override
    public void setShowDoneButton(boolean showDoneButton) {
        mShowDoneButton = showDoneButton;
        updateDoneButtonVisibility();
    }

    private void updateDoneButtonVisibility() {
        mLayoutButtons.setVisibility(mShowDoneButton ? View.VISIBLE : View.GONE);
    }

    @Override
    public void setDismissCallback(DatePicker.DatePickerDismissCallback callback) {
        mDismissCallback = callback;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        mYearFormat = new SimpleDateFormat("y", newConfig.locale);
        mDayFormat = new SimpleDateFormat("d", newConfig.locale);
    }

    @Override
    public void dispatchRestoreInstanceState(SparseArray<Parcelable> container) {
        // Nothing to do
    }

    @Override
    public Parcelable onSaveInstanceState(Parcelable superState) {
        final int year = mCurrentDate.get(Calendar.YEAR);
        final int month = mCurrentDate.get(Calendar.MONTH);
        final int day = mCurrentDate.get(Calendar.DAY_OF_MONTH);

        int listPosition = -1;
        int listPositionOffset = -1;

        if (mCurrentView == MONTH_AND_DAY_VIEW) {
            listPosition = mDayPickerView.getMostVisiblePosition();
        } else if (mCurrentView == YEAR_VIEW) {
            listPosition = mYearPickerView.getFirstVisiblePosition();
            listPositionOffset = mYearPickerView.getFirstPositionOffset();
        }

        return new SavedState(superState, year, month, day, mMinDate.getTimeInMillis(),
                mMaxDate.getTimeInMillis(), mCurrentView, listPosition, listPositionOffset);
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;

        mCurrentDate.set(ss.getSelectedDay(), ss.getSelectedMonth(), ss.getSelectedYear());
        mCurrentView = ss.getCurrentView();
        mMinDate.setTimeInMillis(ss.getMinDate());
        mMaxDate.setTimeInMillis(ss.getMaxDate());

        updateDisplay(false);
        setCurrentView(mCurrentView);

        final int listPosition = ss.getListPosition();
        if (listPosition != -1) {
            if (mCurrentView == MONTH_AND_DAY_VIEW) {
                mDayPickerView.postSetSelection(listPosition);
            } else if (mCurrentView == YEAR_VIEW) {
                mYearPickerView.postSetSelectionFromTop(listPosition, ss.getListPositionOffset());
            }
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        onPopulateAccessibilityEvent(event);
        return true;
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        event.getText().add(mCurrentDate.getTime().toString());
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        event.setClassName(DatePicker.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        info.setClassName(DatePicker.class.getName());
    }

    @Override
    public void onYearSelected(int year) {
        adjustDayInMonthIfNeeded(mCurrentDate.get(Calendar.MONTH), year);
        mCurrentDate.set(Calendar.YEAR, year);
        updatePickers();
        setCurrentView(MONTH_AND_DAY_VIEW);
        updateDisplay(true);
        updateDoneButtonEnableState();
    }

    // If the newly selected month / year does not contain the currently selected day number,
    // change the selected day number to the last day of the selected month or year.
    //      e.g. Switching from Mar to Apr when Mar 31 is selected -> Apr 30
    //      e.g. Switching from 2012 to 2013 when Feb 29, 2012 is selected -> Feb 28, 2013
    private void adjustDayInMonthIfNeeded(int month, int year) {
        int day = mCurrentDate.get(Calendar.DAY_OF_MONTH);
        int daysInMonth = getDaysInMonth(month, year);
        if (day > daysInMonth) {
            mCurrentDate.set(Calendar.DAY_OF_MONTH, daysInMonth);
        }
    }

    public static int getDaysInMonth(int month, int year) {
        switch (month) {
            case Calendar.JANUARY:
            case Calendar.MARCH:
            case Calendar.MAY:
            case Calendar.JULY:
            case Calendar.AUGUST:
            case Calendar.OCTOBER:
            case Calendar.DECEMBER:
                return 31;
            case Calendar.APRIL:
            case Calendar.JUNE:
            case Calendar.SEPTEMBER:
            case Calendar.NOVEMBER:
                return 30;
            case Calendar.FEBRUARY:
                return (year % 4 == 0) ? 29 : 28;
            default:
                throw new IllegalArgumentException("Invalid Month");
        }
    }

    @Override
    public void onDayOfMonthSelected(int year, int month, int day) {
        mCurrentDate.set(Calendar.YEAR, year);
        mCurrentDate.set(Calendar.MONTH, month);
        mCurrentDate.set(Calendar.DAY_OF_MONTH, day);
        updatePickers();
        updateDisplay(true);
        updateDoneButtonEnableState();
    }

    private void updateDoneButtonEnableState() {
        if (mShowDoneButton) {
            final boolean enabled = mCurrentDate.equals(mMinDate) ||
                    mCurrentDate.equals(mMaxDate) ||
                    (mCurrentDate.after(mMinDate) && mCurrentDate.before(mMaxDate));
            mDoneButton.setEnabled(enabled);
        }
    }

    private void updatePickers() {
        Iterator<OnDateChangedListener> iterator = mListeners.iterator();
        while (iterator.hasNext()) {
            iterator.next().onDateChanged();
        }
    }

    @Override
    public void registerOnDateChangedListener(OnDateChangedListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void unregisterOnDateChangedListener(OnDateChangedListener listener) {
        mListeners.remove(listener);
    }

    @Override
    public Calendar getSelectedDay() {
        return mCurrentDate;
    }

    @Override
    public void tryVibrate() {
        mDelegator.performHapticFeedback(HapticFeedbackConstants.CALENDAR_DATE);
    }

    @Override
    public void onClick(View v) {
        tryVibrate();
        if (v.getId() == R.id.date_picker_year) {
            setCurrentView(YEAR_VIEW);
        } else if (v.getId() == R.id.date_picker_month_and_day_layout) {
            setCurrentView(MONTH_AND_DAY_VIEW);
        }
    }

    /**
     * Class for managing state storing/restoring.
     */
    private static class SavedState extends View.BaseSavedState {

        private final int mSelectedYear;
        private final int mSelectedMonth;
        private final int mSelectedDay;
        private final long mMinDate;
        private final long mMaxDate;
        private final int mCurrentView;
        private final int mListPosition;
        private final int mListPositionOffset;

        /**
         * Constructor called from {@link DatePicker#onSaveInstanceState()}
         */
        private SavedState(Parcelable superState, int year, int month, int day,
                long minDate, long maxDate, int currentView, int listPosition,
                int listPositionOffset) {
            super(superState);
            mSelectedYear = year;
            mSelectedMonth = month;
            mSelectedDay = day;
            mMinDate = minDate;
            mMaxDate = maxDate;
            mCurrentView = currentView;
            mListPosition = listPosition;
            mListPositionOffset = listPositionOffset;
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            mSelectedYear = in.readInt();
            mSelectedMonth = in.readInt();
            mSelectedDay = in.readInt();
            mMinDate = in.readLong();
            mMaxDate = in.readLong();
            mCurrentView = in.readInt();
            mListPosition = in.readInt();
            mListPositionOffset = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mSelectedYear);
            dest.writeInt(mSelectedMonth);
            dest.writeInt(mSelectedDay);
            dest.writeLong(mMinDate);
            dest.writeLong(mMaxDate);
            dest.writeInt(mCurrentView);
            dest.writeInt(mListPosition);
            dest.writeInt(mListPositionOffset);
        }

        public int getSelectedDay() {
            return mSelectedDay;
        }

        public int getSelectedMonth() {
            return mSelectedMonth;
        }

        public int getSelectedYear() {
            return mSelectedYear;
        }

        public long getMinDate() {
            return mMinDate;
        }

        public long getMaxDate() {
            return mMaxDate;
        }

        public int getCurrentView() {
            return mCurrentView;
        }

        public int getListPosition() {
            return mListPosition;
        }

        public int getListPositionOffset() {
            return mListPositionOffset;
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

    /**
     * Render an animator to pulsate a view in place.
     * @param labelToAnimate the view to pulsate.
     * @return The animator object. Use .start() to begin.
     */
    public static ObjectAnimator getPulseAnimator(View labelToAnimate, float decreaseRatio,
                                                  float increaseRatio) {
        Keyframe k0 = Keyframe.ofFloat(0f, 1f);
        Keyframe k1 = Keyframe.ofFloat(0.275f, decreaseRatio);
        Keyframe k2 = Keyframe.ofFloat(0.69f, increaseRatio);
        Keyframe k3 = Keyframe.ofFloat(1f, 1f);

        PropertyValuesHolder scaleX = PropertyValuesHolder.ofKeyframe(View.SCALE_X, k0, k1, k2, k3);
        PropertyValuesHolder scaleY = PropertyValuesHolder.ofKeyframe(View.SCALE_Y, k0, k1, k2, k3);
        ObjectAnimator pulseAnimator =
                ObjectAnimator.ofPropertyValuesHolder(labelToAnimate, scaleX, scaleY);
        pulseAnimator.setDuration(PULSE_ANIMATOR_DURATION);

        return pulseAnimator;
    }
}
