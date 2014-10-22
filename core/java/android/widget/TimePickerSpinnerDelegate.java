/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/**
 * A view for selecting the time of day, in either 24 hour or AM/PM mode.
 */
class TimePickerSpinnerDelegate extends TimePicker.AbstractTimePickerDelegate implements
        RadialTimePickerView.OnValueSelectedListener {

    private static final String TAG = "TimePickerDelegate";

    // Index used by RadialPickerLayout
    private static final int HOUR_INDEX = 0;
    private static final int MINUTE_INDEX = 1;

    // NOT a real index for the purpose of what's showing.
    private static final int AMPM_INDEX = 2;

    // Also NOT a real index, just used for keyboard mode.
    private static final int ENABLE_PICKER_INDEX = 3;

    private static final int AM = 0;
    private static final int PM = 1;

    private static final boolean DEFAULT_ENABLED_STATE = true;
    private boolean mIsEnabled = DEFAULT_ENABLED_STATE;

    private static final int HOURS_IN_HALF_DAY = 12;

    private View mHeaderView;
    private TextView mHourView;
    private TextView mMinuteView;
    private TextView mAmPmTextView;
    private RadialTimePickerView mRadialTimePickerView;
    private TextView mSeparatorView;

    private String mAmText;
    private String mPmText;

    private boolean mAllowAutoAdvance;
    private int mInitialHourOfDay;
    private int mInitialMinute;
    private boolean mIs24HourView;

    // For hardware IME input.
    private char mPlaceholderText;
    private String mDoublePlaceholderText;
    private String mDeletedKeyFormat;
    private boolean mInKbMode;
    private ArrayList<Integer> mTypedTimes = new ArrayList<Integer>();
    private Node mLegalTimesTree;
    private int mAmKeyCode;
    private int mPmKeyCode;

    // Accessibility strings.
    private String mHourPickerDescription;
    private String mSelectHours;
    private String mMinutePickerDescription;
    private String mSelectMinutes;

    private Calendar mTempCalendar;

    public TimePickerSpinnerDelegate(TimePicker delegator, Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(delegator, context);

        // process style attributes
        final TypedArray a = mContext.obtainStyledAttributes(attrs,
                R.styleable.TimePicker, defStyleAttr, defStyleRes);
        final LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        final Resources res = mContext.getResources();

        mHourPickerDescription = res.getString(R.string.hour_picker_description);
        mSelectHours = res.getString(R.string.select_hours);
        mMinutePickerDescription = res.getString(R.string.minute_picker_description);
        mSelectMinutes = res.getString(R.string.select_minutes);

        String[] amPmStrings = TimePickerClockDelegate.getAmPmStrings(context);
        mAmText = amPmStrings[0];
        mPmText = amPmStrings[1];

        final int layoutResourceId = a.getResourceId(R.styleable.TimePicker_internalLayout,
                R.layout.time_picker_holo);
        final View mainView = inflater.inflate(layoutResourceId, null);
        mDelegator.addView(mainView);

        mHourView = (TextView) mainView.findViewById(R.id.hours);
        mSeparatorView = (TextView) mainView.findViewById(R.id.separator);
        mMinuteView = (TextView) mainView.findViewById(R.id.minutes);
        mAmPmTextView = (TextView) mainView.findViewById(R.id.ampm_label);

        // Set up text appearances from style.
        final int headerTimeTextAppearance = a.getResourceId(
                R.styleable.TimePicker_headerTimeTextAppearance, 0);
        if (headerTimeTextAppearance != 0) {
            mHourView.setTextAppearance(context, headerTimeTextAppearance);
            mSeparatorView.setTextAppearance(context, headerTimeTextAppearance);
            mMinuteView.setTextAppearance(context, headerTimeTextAppearance);
        }

        final int headerSelectedTextColor = a.getColor(
                R.styleable.TimePicker_headerSelectedTextColor,
                res.getColor(R.color.timepicker_default_selector_color_material));
        mHourView.setTextColor(ColorStateList.addFirstIfMissing(mHourView.getTextColors(),
                R.attr.state_selected, headerSelectedTextColor));
        mMinuteView.setTextColor(ColorStateList.addFirstIfMissing(mMinuteView.getTextColors(),
                R.attr.state_selected, headerSelectedTextColor));

        final int headerAmPmTextAppearance = a.getResourceId(
                R.styleable.TimePicker_headerAmPmTextAppearance, 0);
        if (headerAmPmTextAppearance != 0) {
            mAmPmTextView.setTextAppearance(context, headerAmPmTextAppearance);
        }

        mHeaderView = mainView.findViewById(R.id.time_header);
        mHeaderView.setBackground(a.getDrawable(R.styleable.TimePicker_headerBackground));

        a.recycle();

        mRadialTimePickerView = (RadialTimePickerView) mainView.findViewById(
                R.id.radial_picker);

        setupListeners();

        mAllowAutoAdvance = true;

        // Set up for keyboard mode.
        mDoublePlaceholderText = res.getString(R.string.time_placeholder);
        mDeletedKeyFormat = res.getString(R.string.deleted_key);
        mPlaceholderText = mDoublePlaceholderText.charAt(0);
        mAmKeyCode = mPmKeyCode = -1;
        generateLegalTimesTree();

        // Initialize with current time
        final Calendar calendar = Calendar.getInstance(mCurrentLocale);
        final int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        final int currentMinute = calendar.get(Calendar.MINUTE);
        initialize(currentHour, currentMinute, false /* 12h */, HOUR_INDEX);
    }

    private void initialize(int hourOfDay, int minute, boolean is24HourView, int index) {
        mInitialHourOfDay = hourOfDay;
        mInitialMinute = minute;
        mIs24HourView = is24HourView;
        mInKbMode = false;
        updateUI(index);
    }

    private void setupListeners() {
        mHeaderView.setOnKeyListener(mKeyListener);
        mHeaderView.setOnFocusChangeListener(mFocusListener);
        mHeaderView.setFocusable(true);

        mRadialTimePickerView.setOnValueSelectedListener(this);

        mHourView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCurrentItemShowing(HOUR_INDEX, true, true);
                tryVibrate();
            }
        });
        mMinuteView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCurrentItemShowing(MINUTE_INDEX, true, true);
                tryVibrate();
            }
        });
    }

    private void updateUI(int index) {
        // Update RadialPicker values
        updateRadialPicker(index);
        // Enable or disable the AM/PM view.
        updateHeaderAmPm();
        // Update Hour and Minutes
        updateHeaderHour(mInitialHourOfDay, true);
        // Update time separator
        updateHeaderSeparator();
        // Update Minutes
        updateHeaderMinute(mInitialMinute);
        // Invalidate everything
        mDelegator.invalidate();
    }

    private void updateRadialPicker(int index) {
        mRadialTimePickerView.initialize(mInitialHourOfDay, mInitialMinute, mIs24HourView);
        setCurrentItemShowing(index, false, true);
    }

    private int computeMaxWidthOfNumbers(int max) {
        TextView tempView = new TextView(mContext);
        tempView.setTextAppearance(mContext, R.style.TextAppearance_Material_TimePicker_TimeLabel);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tempView.setLayoutParams(lp);
        int maxWidth = 0;
        for (int minutes = 0; minutes < max; minutes++) {
            final String text = String.format("%02d", minutes);
            tempView.setText(text);
            tempView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            maxWidth = Math.max(maxWidth, tempView.getMeasuredWidth());
        }
        return maxWidth;
    }

    private void updateHeaderAmPm() {
        if (mIs24HourView) {
            mAmPmTextView.setVisibility(View.GONE);
        } else {
            mAmPmTextView.setVisibility(View.VISIBLE);
            final String bestDateTimePattern = DateFormat.getBestDateTimePattern(mCurrentLocale,
                    "hm");

            boolean amPmOnLeft = bestDateTimePattern.startsWith("a");
            if (TextUtils.getLayoutDirectionFromLocale(mCurrentLocale) ==
                    View.LAYOUT_DIRECTION_RTL) {
                amPmOnLeft = !amPmOnLeft;
            }

            RelativeLayout.LayoutParams layoutParams = (RelativeLayout.LayoutParams)
                    mAmPmTextView.getLayoutParams();

            if (amPmOnLeft) {
                layoutParams.rightMargin = computeMaxWidthOfNumbers(12 /* for hours */);
                layoutParams.removeRule(RelativeLayout.RIGHT_OF);
                layoutParams.addRule(RelativeLayout.LEFT_OF, R.id.separator);
            } else {
                layoutParams.leftMargin = computeMaxWidthOfNumbers(60 /* for minutes */);
                layoutParams.removeRule(RelativeLayout.LEFT_OF);
                layoutParams.addRule(RelativeLayout.RIGHT_OF, R.id.separator);
            }

            updateAmPmDisplay(mInitialHourOfDay < 12 ? AM : PM);
            mAmPmTextView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tryVibrate();
                    int amOrPm = mRadialTimePickerView.getAmOrPm();
                    if (amOrPm == AM) {
                        amOrPm = PM;
                    } else if (amOrPm == PM){
                        amOrPm = AM;
                    }
                    updateAmPmDisplay(amOrPm);
                    mRadialTimePickerView.setAmOrPm(amOrPm);
                }
            });
        }
    }

    /**
     * Set the current hour.
     */
    @Override
    public void setCurrentHour(Integer currentHour) {
        if (mInitialHourOfDay == currentHour) {
            return;
        }
        mInitialHourOfDay = currentHour;
        updateHeaderHour(currentHour, true /* accessibility announce */);
        updateHeaderAmPm();
        mRadialTimePickerView.setCurrentHour(currentHour);
        mRadialTimePickerView.setAmOrPm(mInitialHourOfDay < 12 ? AM : PM);
        mDelegator.invalidate();
        onTimeChanged();
    }

    /**
     * @return The current hour in the range (0-23).
     */
    @Override
    public Integer getCurrentHour() {
        int currentHour = mRadialTimePickerView.getCurrentHour();
        if (mIs24HourView) {
            return currentHour;
        } else {
            switch(mRadialTimePickerView.getAmOrPm()) {
                case PM:
                    return (currentHour % HOURS_IN_HALF_DAY) + HOURS_IN_HALF_DAY;
                case AM:
                default:
                    return currentHour % HOURS_IN_HALF_DAY;
            }
        }
    }

    /**
     * Set the current minute (0-59).
     */
    @Override
    public void setCurrentMinute(Integer currentMinute) {
        if (mInitialMinute == currentMinute) {
            return;
        }
        mInitialMinute = currentMinute;
        updateHeaderMinute(currentMinute);
        mRadialTimePickerView.setCurrentMinute(currentMinute);
        mDelegator.invalidate();
        onTimeChanged();
    }

    /**
     * @return The current minute.
     */
    @Override
    public Integer getCurrentMinute() {
        return mRadialTimePickerView.getCurrentMinute();
    }

    /**
     * Set whether in 24 hour or AM/PM mode.
     *
     * @param is24HourView True = 24 hour mode. False = AM/PM.
     */
    @Override
    public void setIs24HourView(Boolean is24HourView) {
        if (is24HourView == mIs24HourView) {
            return;
        }
        mIs24HourView = is24HourView;
        generateLegalTimesTree();
        int hour = mRadialTimePickerView.getCurrentHour();
        mInitialHourOfDay = hour;
        updateHeaderHour(hour, false /* no accessibility announce */);
        updateHeaderAmPm();
        updateRadialPicker(mRadialTimePickerView.getCurrentItemShowing());
        mDelegator.invalidate();
    }

    /**
     * @return true if this is in 24 hour view else false.
     */
    @Override
    public boolean is24HourView() {
        return mIs24HourView;
    }

    @Override
    public void setOnTimeChangedListener(TimePicker.OnTimeChangedListener callback) {
        mOnTimeChangedListener = callback;
    }

    @Override
    public void setEnabled(boolean enabled) {
        mHourView.setEnabled(enabled);
        mMinuteView.setEnabled(enabled);
        mAmPmTextView.setEnabled(enabled);
        mRadialTimePickerView.setEnabled(enabled);
        mIsEnabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return mIsEnabled;
    }

    @Override
    public int getBaseline() {
        // does not support baseline alignment
        return -1;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        updateUI(mRadialTimePickerView.getCurrentItemShowing());
    }

    @Override
    public Parcelable onSaveInstanceState(Parcelable superState) {
        return new SavedState(superState, getCurrentHour(), getCurrentMinute(),
                is24HourView(), inKbMode(), getTypedTimes(), getCurrentItemShowing());
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        setInKbMode(ss.inKbMode());
        setTypedTimes(ss.getTypesTimes());
        initialize(ss.getHour(), ss.getMinute(), ss.is24HourMode(), ss.getCurrentItemShowing());
        mRadialTimePickerView.invalidate();
        if (mInKbMode) {
            tryStartingKbMode(-1);
            mHourView.invalidate();
        }
    }

    @Override
    public void setCurrentLocale(Locale locale) {
        super.setCurrentLocale(locale);
        mTempCalendar = Calendar.getInstance(locale);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        onPopulateAccessibilityEvent(event);
        return true;
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (mIs24HourView) {
            flags |= DateUtils.FORMAT_24HOUR;
        } else {
            flags |= DateUtils.FORMAT_12HOUR;
        }
        mTempCalendar.set(Calendar.HOUR_OF_DAY, getCurrentHour());
        mTempCalendar.set(Calendar.MINUTE, getCurrentMinute());
        String selectedDate = DateUtils.formatDateTime(mContext,
                mTempCalendar.getTimeInMillis(), flags);
        event.getText().add(selectedDate);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        event.setClassName(TimePicker.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        info.setClassName(TimePicker.class.getName());
    }

    /**
     * Set whether in keyboard mode or not.
     *
     * @param inKbMode True means in keyboard mode.
     */
    private void setInKbMode(boolean inKbMode) {
        mInKbMode = inKbMode;
    }

    /**
     * @return true if in keyboard mode
     */
    private boolean inKbMode() {
        return mInKbMode;
    }

    private void setTypedTimes(ArrayList<Integer> typeTimes) {
        mTypedTimes = typeTimes;
    }

    /**
     * @return an array of typed times
     */
    private ArrayList<Integer> getTypedTimes() {
        return mTypedTimes;
    }

    /**
     * @return the index of the current item showing
     */
    private int getCurrentItemShowing() {
        return mRadialTimePickerView.getCurrentItemShowing();
    }

    /**
     * Propagate the time change
     */
    private void onTimeChanged() {
        mDelegator.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        if (mOnTimeChangedListener != null) {
            mOnTimeChangedListener.onTimeChanged(mDelegator,
                    getCurrentHour(), getCurrentMinute());
        }
    }

    /**
     * Used to save / restore state of time picker
     */
    private static class SavedState extends View.BaseSavedState {

        private final int mHour;
        private final int mMinute;
        private final boolean mIs24HourMode;
        private final boolean mInKbMode;
        private final ArrayList<Integer> mTypedTimes;
        private final int mCurrentItemShowing;

        private SavedState(Parcelable superState, int hour, int minute, boolean is24HourMode,
                           boolean isKbMode, ArrayList<Integer> typedTimes,
                           int currentItemShowing) {
            super(superState);
            mHour = hour;
            mMinute = minute;
            mIs24HourMode = is24HourMode;
            mInKbMode = isKbMode;
            mTypedTimes = typedTimes;
            mCurrentItemShowing = currentItemShowing;
        }

        private SavedState(Parcel in) {
            super(in);
            mHour = in.readInt();
            mMinute = in.readInt();
            mIs24HourMode = (in.readInt() == 1);
            mInKbMode = (in.readInt() == 1);
            mTypedTimes = in.readArrayList(getClass().getClassLoader());
            mCurrentItemShowing = in.readInt();
        }

        public int getHour() {
            return mHour;
        }

        public int getMinute() {
            return mMinute;
        }

        public boolean is24HourMode() {
            return mIs24HourMode;
        }

        public boolean inKbMode() {
            return mInKbMode;
        }

        public ArrayList<Integer> getTypesTimes() {
            return mTypedTimes;
        }

        public int getCurrentItemShowing() {
            return mCurrentItemShowing;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mHour);
            dest.writeInt(mMinute);
            dest.writeInt(mIs24HourMode ? 1 : 0);
            dest.writeInt(mInKbMode ? 1 : 0);
            dest.writeList(mTypedTimes);
            dest.writeInt(mCurrentItemShowing);
        }

        @SuppressWarnings({"unused", "hiding"})
        public static final Parcelable.Creator<SavedState> CREATOR = new Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    private void tryVibrate() {
        mDelegator.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void updateAmPmDisplay(int amOrPm) {
        if (amOrPm == AM) {
            mAmPmTextView.setText(mAmText);
            mRadialTimePickerView.announceForAccessibility(mAmText);
        } else if (amOrPm == PM){
            mAmPmTextView.setText(mPmText);
            mRadialTimePickerView.announceForAccessibility(mPmText);
        } else {
            mAmPmTextView.setText(mDoublePlaceholderText);
        }
    }

    /**
     * Called by the picker for updating the header display.
     */
    @Override
    public void onValueSelected(int pickerIndex, int newValue, boolean autoAdvance) {
        if (pickerIndex == HOUR_INDEX) {
            updateHeaderHour(newValue, false);
            String announcement = String.format("%d", newValue);
            if (mAllowAutoAdvance && autoAdvance) {
                setCurrentItemShowing(MINUTE_INDEX, true, false);
                announcement += ". " + mSelectMinutes;
            } else {
                mRadialTimePickerView.setContentDescription(
                        mHourPickerDescription + ": " + newValue);
            }

            mRadialTimePickerView.announceForAccessibility(announcement);
        } else if (pickerIndex == MINUTE_INDEX){
            updateHeaderMinute(newValue);
            mRadialTimePickerView.setContentDescription(mMinutePickerDescription + ": " + newValue);
        } else if (pickerIndex == AMPM_INDEX) {
            updateAmPmDisplay(newValue);
        } else if (pickerIndex == ENABLE_PICKER_INDEX) {
            if (!isTypedTimeFullyLegal()) {
                mTypedTimes.clear();
            }
            finishKbMode();
        }
    }

    private void updateHeaderHour(int value, boolean announce) {
        final String bestDateTimePattern = DateFormat.getBestDateTimePattern(mCurrentLocale,
                (mIs24HourView) ? "Hm" : "hm");
        final int lengthPattern = bestDateTimePattern.length();
        boolean hourWithTwoDigit = false;
        char hourFormat = '\0';
        // Check if the returned pattern is single or double 'H', 'h', 'K', 'k'. We also save
        // the hour format that we found.
        for (int i = 0; i < lengthPattern; i++) {
            final char c = bestDateTimePattern.charAt(i);
            if (c == 'H' || c == 'h' || c == 'K' || c == 'k') {
                hourFormat = c;
                if (i + 1 < lengthPattern && c == bestDateTimePattern.charAt(i + 1)) {
                    hourWithTwoDigit = true;
                }
                break;
            }
        }
        final String format;
        if (hourWithTwoDigit) {
            format = "%02d";
        } else {
            format = "%d";
        }
        if (mIs24HourView) {
            // 'k' means 1-24 hour
            if (hourFormat == 'k' && value == 0) {
                value = 24;
            }
        } else {
            // 'K' means 0-11 hour
            value = modulo12(value, hourFormat == 'K');
        }
        CharSequence text = String.format(format, value);
        mHourView.setText(text);
        if (announce) {
            mRadialTimePickerView.announceForAccessibility(text);
        }
    }

    private static int modulo12(int n, boolean startWithZero) {
        int value = n % 12;
        if (value == 0 && !startWithZero) {
            value = 12;
        }
        return value;
    }

    /**
     * The time separator is defined in the Unicode CLDR and cannot be supposed to be ":".
     *
     * See http://unicode.org/cldr/trac/browser/trunk/common/main
     *
     * We pass the correct "skeleton" depending on 12 or 24 hours view and then extract the
     * separator as the character which is just after the hour marker in the returned pattern.
     */
    private void updateHeaderSeparator() {
        final String bestDateTimePattern = DateFormat.getBestDateTimePattern(mCurrentLocale,
                (mIs24HourView) ? "Hm" : "hm");
        final String separatorText;
        // See http://www.unicode.org/reports/tr35/tr35-dates.html for hour formats
        final char[] hourFormats = {'H', 'h', 'K', 'k'};
        int hIndex = lastIndexOfAny(bestDateTimePattern, hourFormats);
        if (hIndex == -1) {
            // Default case
            separatorText = ":";
        } else {
            separatorText = Character.toString(bestDateTimePattern.charAt(hIndex + 1));
        }
        mSeparatorView.setText(separatorText);
    }

    static private int lastIndexOfAny(String str, char[] any) {
        final int lengthAny = any.length;
        if (lengthAny > 0) {
            for (int i = str.length() - 1; i >= 0; i--) {
                char c = str.charAt(i);
                for (int j = 0; j < lengthAny; j++) {
                    if (c == any[j]) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private void updateHeaderMinute(int value) {
        if (value == 60) {
            value = 0;
        }
        CharSequence text = String.format(mCurrentLocale, "%02d", value);
        mRadialTimePickerView.announceForAccessibility(text);
        mMinuteView.setText(text);
    }

    /**
     * Show either Hours or Minutes.
     */
    private void setCurrentItemShowing(int index, boolean animateCircle, boolean announce) {
        mRadialTimePickerView.setCurrentItemShowing(index, animateCircle);

        if (index == HOUR_INDEX) {
            int hours = mRadialTimePickerView.getCurrentHour();
            if (!mIs24HourView) {
                hours = hours % 12;
            }
            mRadialTimePickerView.setContentDescription(mHourPickerDescription + ": " + hours);
            if (announce) {
                mRadialTimePickerView.announceForAccessibility(mSelectHours);
            }
        } else {
            int minutes = mRadialTimePickerView.getCurrentMinute();
            mRadialTimePickerView.setContentDescription(mMinutePickerDescription + ": " + minutes);
            if (announce) {
                mRadialTimePickerView.announceForAccessibility(mSelectMinutes);
            }
        }

        mHourView.setSelected(index == HOUR_INDEX);
        mMinuteView.setSelected(index == MINUTE_INDEX);
    }

    /**
     * For keyboard mode, processes key events.
     *
     * @param keyCode the pressed key.
     *
     * @return true if the key was successfully processed, false otherwise.
     */
    private boolean processKeyUp(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (mInKbMode) {
                if (!mTypedTimes.isEmpty()) {
                    int deleted = deleteLastTypedKey();
                    String deletedKeyStr;
                    if (deleted == getAmOrPmKeyCode(AM)) {
                        deletedKeyStr = mAmText;
                    } else if (deleted == getAmOrPmKeyCode(PM)) {
                        deletedKeyStr = mPmText;
                    } else {
                        deletedKeyStr = String.format("%d", getValFromKeyCode(deleted));
                    }
                    mRadialTimePickerView.announceForAccessibility(
                            String.format(mDeletedKeyFormat, deletedKeyStr));
                    updateDisplay(true);
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_1
                || keyCode == KeyEvent.KEYCODE_2 || keyCode == KeyEvent.KEYCODE_3
                || keyCode == KeyEvent.KEYCODE_4 || keyCode == KeyEvent.KEYCODE_5
                || keyCode == KeyEvent.KEYCODE_6 || keyCode == KeyEvent.KEYCODE_7
                || keyCode == KeyEvent.KEYCODE_8 || keyCode == KeyEvent.KEYCODE_9
                || (!mIs24HourView &&
                (keyCode == getAmOrPmKeyCode(AM) || keyCode == getAmOrPmKeyCode(PM)))) {
            if (!mInKbMode) {
                if (mRadialTimePickerView == null) {
                    // Something's wrong, because time picker should definitely not be null.
                    Log.e(TAG, "Unable to initiate keyboard mode, TimePicker was null.");
                    return true;
                }
                mTypedTimes.clear();
                tryStartingKbMode(keyCode);
                return true;
            }
            // We're already in keyboard mode.
            if (addKeyIfLegal(keyCode)) {
                updateDisplay(false);
            }
            return true;
        }
        return false;
    }

    /**
     * Try to start keyboard mode with the specified key.
     *
     * @param keyCode The key to use as the first press. Keyboard mode will not be started if the
     * key is not legal to start with. Or, pass in -1 to get into keyboard mode without a starting
     * key.
     */
    private void tryStartingKbMode(int keyCode) {
        if (keyCode == -1 || addKeyIfLegal(keyCode)) {
            mInKbMode = true;
            onValidationChanged(false);
            updateDisplay(false);
            mRadialTimePickerView.setInputEnabled(false);
        }
    }

    private boolean addKeyIfLegal(int keyCode) {
        // If we're in 24hour mode, we'll need to check if the input is full. If in AM/PM mode,
        // we'll need to see if AM/PM have been typed.
        if ((mIs24HourView && mTypedTimes.size() == 4) ||
                (!mIs24HourView && isTypedTimeFullyLegal())) {
            return false;
        }

        mTypedTimes.add(keyCode);
        if (!isTypedTimeLegalSoFar()) {
            deleteLastTypedKey();
            return false;
        }

        int val = getValFromKeyCode(keyCode);
        mRadialTimePickerView.announceForAccessibility(String.format("%d", val));
        // Automatically fill in 0's if AM or PM was legally entered.
        if (isTypedTimeFullyLegal()) {
            if (!mIs24HourView && mTypedTimes.size() <= 3) {
                mTypedTimes.add(mTypedTimes.size() - 1, KeyEvent.KEYCODE_0);
                mTypedTimes.add(mTypedTimes.size() - 1, KeyEvent.KEYCODE_0);
            }
            onValidationChanged(true);
        }

        return true;
    }

    /**
     * Traverse the tree to see if the keys that have been typed so far are legal as is,
     * or may become legal as more keys are typed (excluding backspace).
     */
    private boolean isTypedTimeLegalSoFar() {
        Node node = mLegalTimesTree;
        for (int keyCode : mTypedTimes) {
            node = node.canReach(keyCode);
            if (node == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the time that has been typed so far is completely legal, as is.
     */
    private boolean isTypedTimeFullyLegal() {
        if (mIs24HourView) {
            // For 24-hour mode, the time is legal if the hours and minutes are each legal. Note:
            // getEnteredTime() will ONLY call isTypedTimeFullyLegal() when NOT in 24hour mode.
            int[] values = getEnteredTime(null);
            return (values[0] >= 0 && values[1] >= 0 && values[1] < 60);
        } else {
            // For AM/PM mode, the time is legal if it contains an AM or PM, as those can only be
            // legally added at specific times based on the tree's algorithm.
            return (mTypedTimes.contains(getAmOrPmKeyCode(AM)) ||
                    mTypedTimes.contains(getAmOrPmKeyCode(PM)));
        }
    }

    private int deleteLastTypedKey() {
        int deleted = mTypedTimes.remove(mTypedTimes.size() - 1);
        if (!isTypedTimeFullyLegal()) {
            onValidationChanged(false);
        }
        return deleted;
    }

    /**
     * Get out of keyboard mode. If there is nothing in typedTimes, revert to TimePicker's time.
     */
    private void finishKbMode() {
        mInKbMode = false;
        if (!mTypedTimes.isEmpty()) {
            int values[] = getEnteredTime(null);
            mRadialTimePickerView.setCurrentHour(values[0]);
            mRadialTimePickerView.setCurrentMinute(values[1]);
            if (!mIs24HourView) {
                mRadialTimePickerView.setAmOrPm(values[2]);
            }
            mTypedTimes.clear();
        }
        updateDisplay(false);
        mRadialTimePickerView.setInputEnabled(true);
    }

    /**
     * Update the hours, minutes, and AM/PM displays with the typed times. If the typedTimes is
     * empty, either show an empty display (filled with the placeholder text), or update from the
     * timepicker's values.
     *
     * @param allowEmptyDisplay if true, then if the typedTimes is empty, use the placeholder text.
     * Otherwise, revert to the timepicker's values.
     */
    private void updateDisplay(boolean allowEmptyDisplay) {
        if (!allowEmptyDisplay && mTypedTimes.isEmpty()) {
            int hour = mRadialTimePickerView.getCurrentHour();
            int minute = mRadialTimePickerView.getCurrentMinute();
            updateHeaderHour(hour, true);
            updateHeaderMinute(minute);
            if (!mIs24HourView) {
                updateAmPmDisplay(hour < 12 ? AM : PM);
            }
            setCurrentItemShowing(mRadialTimePickerView.getCurrentItemShowing(), true, true);
            onValidationChanged(true);
        } else {
            boolean[] enteredZeros = {false, false};
            int[] values = getEnteredTime(enteredZeros);
            String hourFormat = enteredZeros[0] ? "%02d" : "%2d";
            String minuteFormat = (enteredZeros[1]) ? "%02d" : "%2d";
            String hourStr = (values[0] == -1) ? mDoublePlaceholderText :
                    String.format(hourFormat, values[0]).replace(' ', mPlaceholderText);
            String minuteStr = (values[1] == -1) ? mDoublePlaceholderText :
                    String.format(minuteFormat, values[1]).replace(' ', mPlaceholderText);
            mHourView.setText(hourStr);
            mHourView.setSelected(false);
            mMinuteView.setText(minuteStr);
            mMinuteView.setSelected(false);
            if (!mIs24HourView) {
                updateAmPmDisplay(values[2]);
            }
        }
    }

    private int getValFromKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_0:
                return 0;
            case KeyEvent.KEYCODE_1:
                return 1;
            case KeyEvent.KEYCODE_2:
                return 2;
            case KeyEvent.KEYCODE_3:
                return 3;
            case KeyEvent.KEYCODE_4:
                return 4;
            case KeyEvent.KEYCODE_5:
                return 5;
            case KeyEvent.KEYCODE_6:
                return 6;
            case KeyEvent.KEYCODE_7:
                return 7;
            case KeyEvent.KEYCODE_8:
                return 8;
            case KeyEvent.KEYCODE_9:
                return 9;
            default:
                return -1;
        }
    }

    /**
     * Get the currently-entered time, as integer values of the hours and minutes typed.
     *
     * @param enteredZeros A size-2 boolean array, which the caller should initialize, and which
     * may then be used for the caller to know whether zeros had been explicitly entered as either
     * hours of minutes. This is helpful for deciding whether to show the dashes, or actual 0's.
     *
     * @return A size-3 int array. The first value will be the hours, the second value will be the
     * minutes, and the third will be either AM or PM.
     */
    private int[] getEnteredTime(boolean[] enteredZeros) {
        int amOrPm = -1;
        int startIndex = 1;
        if (!mIs24HourView && isTypedTimeFullyLegal()) {
            int keyCode = mTypedTimes.get(mTypedTimes.size() - 1);
            if (keyCode == getAmOrPmKeyCode(AM)) {
                amOrPm = AM;
            } else if (keyCode == getAmOrPmKeyCode(PM)){
                amOrPm = PM;
            }
            startIndex = 2;
        }
        int minute = -1;
        int hour = -1;
        for (int i = startIndex; i <= mTypedTimes.size(); i++) {
            int val = getValFromKeyCode(mTypedTimes.get(mTypedTimes.size() - i));
            if (i == startIndex) {
                minute = val;
            } else if (i == startIndex+1) {
                minute += 10 * val;
                if (enteredZeros != null && val == 0) {
                    enteredZeros[1] = true;
                }
            } else if (i == startIndex+2) {
                hour = val;
            } else if (i == startIndex+3) {
                hour += 10 * val;
                if (enteredZeros != null && val == 0) {
                    enteredZeros[0] = true;
                }
            }
        }

        return new int[] { hour, minute, amOrPm };
    }

    /**
     * Get the keycode value for AM and PM in the current language.
     */
    private int getAmOrPmKeyCode(int amOrPm) {
        // Cache the codes.
        if (mAmKeyCode == -1 || mPmKeyCode == -1) {
            // Find the first character in the AM/PM text that is unique.
            KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            char amChar;
            char pmChar;
            for (int i = 0; i < Math.max(mAmText.length(), mPmText.length()); i++) {
                amChar = mAmText.toLowerCase(mCurrentLocale).charAt(i);
                pmChar = mPmText.toLowerCase(mCurrentLocale).charAt(i);
                if (amChar != pmChar) {
                    KeyEvent[] events = kcm.getEvents(new char[]{amChar, pmChar});
                    // There should be 4 events: a down and up for both AM and PM.
                    if (events != null && events.length == 4) {
                        mAmKeyCode = events[0].getKeyCode();
                        mPmKeyCode = events[2].getKeyCode();
                    } else {
                        Log.e(TAG, "Unable to find keycodes for AM and PM.");
                    }
                    break;
                }
            }
        }
        if (amOrPm == AM) {
            return mAmKeyCode;
        } else if (amOrPm == PM) {
            return mPmKeyCode;
        }

        return -1;
    }

    /**
     * Create a tree for deciding what keys can legally be typed.
     */
    private void generateLegalTimesTree() {
        // Create a quick cache of numbers to their keycodes.
        final int k0 = KeyEvent.KEYCODE_0;
        final int k1 = KeyEvent.KEYCODE_1;
        final int k2 = KeyEvent.KEYCODE_2;
        final int k3 = KeyEvent.KEYCODE_3;
        final int k4 = KeyEvent.KEYCODE_4;
        final int k5 = KeyEvent.KEYCODE_5;
        final int k6 = KeyEvent.KEYCODE_6;
        final int k7 = KeyEvent.KEYCODE_7;
        final int k8 = KeyEvent.KEYCODE_8;
        final int k9 = KeyEvent.KEYCODE_9;

        // The root of the tree doesn't contain any numbers.
        mLegalTimesTree = new Node();
        if (mIs24HourView) {
            // We'll be re-using these nodes, so we'll save them.
            Node minuteFirstDigit = new Node(k0, k1, k2, k3, k4, k5);
            Node minuteSecondDigit = new Node(k0, k1, k2, k3, k4, k5, k6, k7, k8, k9);
            // The first digit must be followed by the second digit.
            minuteFirstDigit.addChild(minuteSecondDigit);

            // The first digit may be 0-1.
            Node firstDigit = new Node(k0, k1);
            mLegalTimesTree.addChild(firstDigit);

            // When the first digit is 0-1, the second digit may be 0-5.
            Node secondDigit = new Node(k0, k1, k2, k3, k4, k5);
            firstDigit.addChild(secondDigit);
            // We may now be followed by the first minute digit. E.g. 00:09, 15:58.
            secondDigit.addChild(minuteFirstDigit);

            // When the first digit is 0-1, and the second digit is 0-5, the third digit may be 6-9.
            Node thirdDigit = new Node(k6, k7, k8, k9);
            // The time must now be finished. E.g. 0:55, 1:08.
            secondDigit.addChild(thirdDigit);

            // When the first digit is 0-1, the second digit may be 6-9.
            secondDigit = new Node(k6, k7, k8, k9);
            firstDigit.addChild(secondDigit);
            // We must now be followed by the first minute digit. E.g. 06:50, 18:20.
            secondDigit.addChild(minuteFirstDigit);

            // The first digit may be 2.
            firstDigit = new Node(k2);
            mLegalTimesTree.addChild(firstDigit);

            // When the first digit is 2, the second digit may be 0-3.
            secondDigit = new Node(k0, k1, k2, k3);
            firstDigit.addChild(secondDigit);
            // We must now be followed by the first minute digit. E.g. 20:50, 23:09.
            secondDigit.addChild(minuteFirstDigit);

            // When the first digit is 2, the second digit may be 4-5.
            secondDigit = new Node(k4, k5);
            firstDigit.addChild(secondDigit);
            // We must now be followd by the last minute digit. E.g. 2:40, 2:53.
            secondDigit.addChild(minuteSecondDigit);

            // The first digit may be 3-9.
            firstDigit = new Node(k3, k4, k5, k6, k7, k8, k9);
            mLegalTimesTree.addChild(firstDigit);
            // We must now be followed by the first minute digit. E.g. 3:57, 8:12.
            firstDigit.addChild(minuteFirstDigit);
        } else {
            // We'll need to use the AM/PM node a lot.
            // Set up AM and PM to respond to "a" and "p".
            Node ampm = new Node(getAmOrPmKeyCode(AM), getAmOrPmKeyCode(PM));

            // The first hour digit may be 1.
            Node firstDigit = new Node(k1);
            mLegalTimesTree.addChild(firstDigit);
            // We'll allow quick input of on-the-hour times. E.g. 1pm.
            firstDigit.addChild(ampm);

            // When the first digit is 1, the second digit may be 0-2.
            Node secondDigit = new Node(k0, k1, k2);
            firstDigit.addChild(secondDigit);
            // Also for quick input of on-the-hour times. E.g. 10pm, 12am.
            secondDigit.addChild(ampm);

            // When the first digit is 1, and the second digit is 0-2, the third digit may be 0-5.
            Node thirdDigit = new Node(k0, k1, k2, k3, k4, k5);
            secondDigit.addChild(thirdDigit);
            // The time may be finished now. E.g. 1:02pm, 1:25am.
            thirdDigit.addChild(ampm);

            // When the first digit is 1, the second digit is 0-2, and the third digit is 0-5,
            // the fourth digit may be 0-9.
            Node fourthDigit = new Node(k0, k1, k2, k3, k4, k5, k6, k7, k8, k9);
            thirdDigit.addChild(fourthDigit);
            // The time must be finished now. E.g. 10:49am, 12:40pm.
            fourthDigit.addChild(ampm);

            // When the first digit is 1, and the second digit is 0-2, the third digit may be 6-9.
            thirdDigit = new Node(k6, k7, k8, k9);
            secondDigit.addChild(thirdDigit);
            // The time must be finished now. E.g. 1:08am, 1:26pm.
            thirdDigit.addChild(ampm);

            // When the first digit is 1, the second digit may be 3-5.
            secondDigit = new Node(k3, k4, k5);
            firstDigit.addChild(secondDigit);

            // When the first digit is 1, and the second digit is 3-5, the third digit may be 0-9.
            thirdDigit = new Node(k0, k1, k2, k3, k4, k5, k6, k7, k8, k9);
            secondDigit.addChild(thirdDigit);
            // The time must be finished now. E.g. 1:39am, 1:50pm.
            thirdDigit.addChild(ampm);

            // The hour digit may be 2-9.
            firstDigit = new Node(k2, k3, k4, k5, k6, k7, k8, k9);
            mLegalTimesTree.addChild(firstDigit);
            // We'll allow quick input of on-the-hour-times. E.g. 2am, 5pm.
            firstDigit.addChild(ampm);

            // When the first digit is 2-9, the second digit may be 0-5.
            secondDigit = new Node(k0, k1, k2, k3, k4, k5);
            firstDigit.addChild(secondDigit);

            // When the first digit is 2-9, and the second digit is 0-5, the third digit may be 0-9.
            thirdDigit = new Node(k0, k1, k2, k3, k4, k5, k6, k7, k8, k9);
            secondDigit.addChild(thirdDigit);
            // The time must be finished now. E.g. 2:57am, 9:30pm.
            thirdDigit.addChild(ampm);
        }
    }

    /**
     * Simple node class to be used for traversal to check for legal times.
     * mLegalKeys represents the keys that can be typed to get to the node.
     * mChildren are the children that can be reached from this node.
     */
    private class Node {
        private int[] mLegalKeys;
        private ArrayList<Node> mChildren;

        public Node(int... legalKeys) {
            mLegalKeys = legalKeys;
            mChildren = new ArrayList<Node>();
        }

        public void addChild(Node child) {
            mChildren.add(child);
        }

        public boolean containsKey(int key) {
            for (int i = 0; i < mLegalKeys.length; i++) {
                if (mLegalKeys[i] == key) {
                    return true;
                }
            }
            return false;
        }

        public Node canReach(int key) {
            if (mChildren == null) {
                return null;
            }
            for (Node child : mChildren) {
                if (child.containsKey(key)) {
                    return child;
                }
            }
            return null;
        }
    }

    private final View.OnKeyListener mKeyListener = new View.OnKeyListener() {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                return processKeyUp(keyCode);
            }
            return false;
        }
    };

    private final View.OnFocusChangeListener mFocusListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (!hasFocus && mInKbMode && isTypedTimeFullyLegal()) {
                finishKbMode();

                if (mOnTimeChangedListener != null) {
                    mOnTimeChangedListener.onTimeChanged(mDelegator,
                            mRadialTimePickerView.getCurrentHour(),
                            mRadialTimePickerView.getCurrentMinute());
                }
            }
        }
    };
}
