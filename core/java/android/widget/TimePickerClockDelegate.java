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

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.style.TtsSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.StateSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/**
 * A delegate implementing the radial clock-based TimePicker.
 */
class TimePickerClockDelegate extends TimePicker.AbstractTimePickerDelegate implements
        RadialTimePickerView.OnValueSelectedListener {
    private static final String TAG = "TimePickerClockDelegate";

    // Index used by RadialPickerLayout
    private static final int HOUR_INDEX = 0;
    private static final int MINUTE_INDEX = 1;

    // NOT a real index for the purpose of what's showing.
    private static final int AMPM_INDEX = 2;

    // Also NOT a real index, just used for keyboard mode.
    private static final int ENABLE_PICKER_INDEX = 3;

    private static final int[] ATTRS_TEXT_COLOR = new int[] {
            com.android.internal.R.attr.textColor};
    private static final int[] ATTRS_DISABLED_ALPHA = new int[] {
            com.android.internal.R.attr.disabledAlpha};

    // LayoutLib relies on these constants. Change TimePickerClockDelegate_Delegate if
    // modifying these.
    static final int AM = 0;
    static final int PM = 1;

    private static final int HOURS_IN_HALF_DAY = 12;

    private final View mHeaderView;
    private final TextView mHourView;
    private final TextView mMinuteView;
    private final View mAmPmLayout;
    private final CheckedTextView mAmLabel;
    private final CheckedTextView mPmLabel;
    private final RadialTimePickerView mRadialTimePickerView;
    private final TextView mSeparatorView;

    private final String mAmText;
    private final String mPmText;

    private boolean mIsEnabled = true;
    private boolean mAllowAutoAdvance;
    private int mInitialHourOfDay;
    private int mInitialMinute;
    private boolean mIs24HourView;
    private boolean mIsAmPmAtStart;

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
    private String mSelectHours;
    private String mSelectMinutes;

    // Most recent time announcement values for accessibility.
    private CharSequence mLastAnnouncedText;
    private boolean mLastAnnouncedIsHour;

    private Calendar mTempCalendar;

    public TimePickerClockDelegate(TimePicker delegator, Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(delegator, context);

        // process style attributes
        final TypedArray a = mContext.obtainStyledAttributes(attrs,
                R.styleable.TimePicker, defStyleAttr, defStyleRes);
        final LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        final Resources res = mContext.getResources();

        mSelectHours = res.getString(R.string.select_hours);
        mSelectMinutes = res.getString(R.string.select_minutes);

        String[] amPmStrings = TimePickerSpinnerDelegate.getAmPmStrings(context);
        mAmText = amPmStrings[0];
        mPmText = amPmStrings[1];

        final int layoutResourceId = a.getResourceId(R.styleable.TimePicker_internalLayout,
                R.layout.time_picker_material);
        final View mainView = inflater.inflate(layoutResourceId, delegator);

        mHeaderView = mainView.findViewById(R.id.time_header);

        // Set up hour/minute labels.
        mHourView = (TextView) mainView.findViewById(R.id.hours);
        mHourView.setOnClickListener(mClickListener);
        mHourView.setAccessibilityDelegate(
                new ClickActionDelegate(context, R.string.select_hours));
        mSeparatorView = (TextView) mainView.findViewById(R.id.separator);
        mMinuteView = (TextView) mainView.findViewById(R.id.minutes);
        mMinuteView.setOnClickListener(mClickListener);
        mMinuteView.setAccessibilityDelegate(
                new ClickActionDelegate(context, R.string.select_minutes));

        // Now that we have text appearances out of the way, make sure the hour
        // and minute views are correctly sized.
        mHourView.setMinWidth(computeStableWidth(mHourView, 24));
        mMinuteView.setMinWidth(computeStableWidth(mMinuteView, 60));

        final SpannableStringBuilder amLabel = new SpannableStringBuilder()
                .append(amPmStrings[0], new TtsSpan.VerbatimBuilder(amPmStrings[0]).build(), 0);

        // Set up AM/PM labels.
        mAmPmLayout = mainView.findViewById(R.id.ampm_layout);
        mAmLabel = (CheckedTextView) mAmPmLayout.findViewById(R.id.am_label);
        mAmLabel.setText(obtainVerbatim(amPmStrings[0]));
        mAmLabel.setOnClickListener(mClickListener);
        mPmLabel = (CheckedTextView) mAmPmLayout.findViewById(R.id.pm_label);
        mPmLabel.setText(obtainVerbatim(amPmStrings[1]));
        mPmLabel.setOnClickListener(mClickListener);

        // For the sake of backwards compatibility, attempt to extract the text
        // color from the header time text appearance. If it's set, we'll let
        // that override the "real" header text color.
        ColorStateList headerTextColor = null;

        @SuppressWarnings("deprecation")
        final int timeHeaderTextAppearance = a.getResourceId(
                R.styleable.TimePicker_headerTimeTextAppearance, 0);
        if (timeHeaderTextAppearance != 0) {
            final TypedArray textAppearance = mContext.obtainStyledAttributes(null,
                    ATTRS_TEXT_COLOR, 0, timeHeaderTextAppearance);
            final ColorStateList legacyHeaderTextColor = textAppearance.getColorStateList(0);
            headerTextColor = applyLegacyColorFixes(legacyHeaderTextColor);
            textAppearance.recycle();
        }

        if (headerTextColor == null) {
            headerTextColor = a.getColorStateList(R.styleable.TimePicker_headerTextColor);
        }

        if (headerTextColor != null) {
            mHourView.setTextColor(headerTextColor);
            mSeparatorView.setTextColor(headerTextColor);
            mMinuteView.setTextColor(headerTextColor);
            mAmLabel.setTextColor(headerTextColor);
            mPmLabel.setTextColor(headerTextColor);
        }

        // Set up header background, if available.
        if (a.hasValueOrEmpty(R.styleable.TimePicker_headerBackground)) {
            mHeaderView.setBackground(a.getDrawable(R.styleable.TimePicker_headerBackground));
        }

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

    private static final CharSequence obtainVerbatim(String text) {
        return new SpannableStringBuilder().append(text,
                new TtsSpan.VerbatimBuilder(text).build(), 0);
    }

    /**
     * The legacy text color might have been poorly defined. Ensures that it
     * has an appropriate activated state, using the selected state if one
     * exists or modifying the default text color otherwise.
     *
     * @param color a legacy text color, or {@code null}
     * @return a color state list with an appropriate activated state, or
     *         {@code null} if a valid activated state could not be generated
     */
    @Nullable
    private ColorStateList applyLegacyColorFixes(@Nullable ColorStateList color) {
        if (color == null || color.hasState(R.attr.state_activated)) {
            return color;
        }

        final int activatedColor;
        final int defaultColor;
        if (color.hasState(R.attr.state_selected)) {
            activatedColor = color.getColorForState(StateSet.get(
                    StateSet.VIEW_STATE_ENABLED | StateSet.VIEW_STATE_SELECTED), 0);
            defaultColor = color.getColorForState(StateSet.get(
                    StateSet.VIEW_STATE_ENABLED), 0);
        } else {
            activatedColor = color.getDefaultColor();

            // Generate a non-activated color using the disabled alpha.
            final TypedArray ta = mContext.obtainStyledAttributes(ATTRS_DISABLED_ALPHA);
            final float disabledAlpha = ta.getFloat(0, 0.30f);
            defaultColor = multiplyAlphaComponent(activatedColor, disabledAlpha);
        }

        if (activatedColor == 0 || defaultColor == 0) {
            // We somehow failed to obtain the colors.
            return null;
        }

        final int[][] stateSet = new int[][] {{ R.attr.state_activated }, {}};
        final int[] colors = new int[] { activatedColor, defaultColor };
        return new ColorStateList(stateSet, colors);
    }

    private int multiplyAlphaComponent(int color, float alphaMod) {
        final int srcRgb = color & 0xFFFFFF;
        final int srcAlpha = (color >> 24) & 0xFF;
        final int dstAlpha = (int) (srcAlpha * alphaMod + 0.5f);
        return srcRgb | (dstAlpha << 24);
    }

    private static class ClickActionDelegate extends AccessibilityDelegate {
        private final AccessibilityAction mClickAction;

        public ClickActionDelegate(Context context, int resId) {
            mClickAction = new AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, context.getString(resId));
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);

            info.addAction(mClickAction);
        }
    }

    private int computeStableWidth(TextView v, int maxNumber) {
        int maxWidth = 0;

        for (int i = 0; i < maxNumber; i++) {
            final String text = String.format("%02d", i);
            v.setText(text);
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

            final int width = v.getMeasuredWidth();
            if (width > maxWidth) {
                maxWidth = width;
            }
        }

        return maxWidth;
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
    }

    private void updateUI(int index) {
        // Update RadialPicker values
        updateRadialPicker(index);
        // Enable or disable the AM/PM view.
        updateHeaderAmPm();
        // Update Hour and Minutes
        updateHeaderHour(mInitialHourOfDay, false);
        // Update time separator
        updateHeaderSeparator();
        // Update Minutes
        updateHeaderMinute(mInitialMinute, false);
        // Invalidate everything
        mDelegator.invalidate();
    }

    private void updateRadialPicker(int index) {
        mRadialTimePickerView.initialize(mInitialHourOfDay, mInitialMinute, mIs24HourView);
        setCurrentItemShowing(index, false, true);
    }

    private void updateHeaderAmPm() {

        if (mIs24HourView) {
            mAmPmLayout.setVisibility(View.GONE);
        } else {
            // Ensure that AM/PM layout is in the correct position.
            final String dateTimePattern = DateFormat.getBestDateTimePattern(mCurrentLocale, "hm");
            final boolean isAmPmAtStart = dateTimePattern.startsWith("a");
            setAmPmAtStart(isAmPmAtStart);

            updateAmPmLabelStates(mInitialHourOfDay < 12 ? AM : PM);
        }
    }

    private void setAmPmAtStart(boolean isAmPmAtStart) {
        if (mIsAmPmAtStart != isAmPmAtStart) {
            mIsAmPmAtStart = isAmPmAtStart;

            final RelativeLayout.LayoutParams params =
                    (RelativeLayout.LayoutParams) mAmPmLayout.getLayoutParams();
            if (params.getRule(RelativeLayout.RIGHT_OF) != 0 ||
                    params.getRule(RelativeLayout.LEFT_OF) != 0) {
                if (isAmPmAtStart) {
                    params.removeRule(RelativeLayout.RIGHT_OF);
                    params.addRule(RelativeLayout.LEFT_OF, mHourView.getId());
                } else {
                    params.removeRule(RelativeLayout.LEFT_OF);
                    params.addRule(RelativeLayout.RIGHT_OF, mMinuteView.getId());
                }
            }

            mAmPmLayout.setLayoutParams(params);
        }
    }

    /**
     * Set the current hour.
     */
    @Override
    public void setCurrentHour(int currentHour) {
        if (mInitialHourOfDay == currentHour) {
            return;
        }
        mInitialHourOfDay = currentHour;
        updateHeaderHour(currentHour, true);
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
    public int getCurrentHour() {
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
    public void setCurrentMinute(int currentMinute) {
        if (mInitialMinute == currentMinute) {
            return;
        }
        mInitialMinute = currentMinute;
        updateHeaderMinute(currentMinute, true);
        mRadialTimePickerView.setCurrentMinute(currentMinute);
        mDelegator.invalidate();
        onTimeChanged();
    }

    /**
     * @return The current minute.
     */
    @Override
    public int getCurrentMinute() {
        return mRadialTimePickerView.getCurrentMinute();
    }

    /**
     * Set whether in 24 hour or AM/PM mode.
     *
     * @param is24HourView True = 24 hour mode. False = AM/PM.
     */
    @Override
    public void setIs24HourView(boolean is24HourView) {
        if (is24HourView == mIs24HourView) {
            return;
        }
        mIs24HourView = is24HourView;
        generateLegalTimesTree();
        int hour = mRadialTimePickerView.getCurrentHour();
        mInitialHourOfDay = hour;
        updateHeaderHour(hour, false);
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
        mAmLabel.setEnabled(enabled);
        mPmLabel.setEnabled(enabled);
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

    private void updateAmPmLabelStates(int amOrPm) {
        final boolean isAm = amOrPm == AM;
        mAmLabel.setActivated(isAm);
        mAmLabel.setChecked(isAm);

        final boolean isPm = amOrPm == PM;
        mPmLabel.setActivated(isPm);
        mPmLabel.setChecked(isPm);
    }

    /**
     * Called by the picker for updating the header display.
     */
    @Override
    public void onValueSelected(int pickerIndex, int newValue, boolean autoAdvance) {
        switch (pickerIndex) {
            case HOUR_INDEX:
                if (mAllowAutoAdvance && autoAdvance) {
                    updateHeaderHour(newValue, false);
                    setCurrentItemShowing(MINUTE_INDEX, true, false);
                    mDelegator.announceForAccessibility(newValue + ". " + mSelectMinutes);
                } else {
                    updateHeaderHour(newValue, true);
                }
                break;
            case MINUTE_INDEX:
                updateHeaderMinute(newValue, true);
                break;
            case AMPM_INDEX:
                updateAmPmLabelStates(newValue);
                break;
            case ENABLE_PICKER_INDEX:
                if (!isTypedTimeFullyLegal()) {
                    mTypedTimes.clear();
                }
                finishKbMode();
                break;
        }

        if (mOnTimeChangedListener != null) {
            mOnTimeChangedListener.onTimeChanged(mDelegator, getCurrentHour(), getCurrentMinute());
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
            tryAnnounceForAccessibility(text, true);
        }
    }

    private void tryAnnounceForAccessibility(CharSequence text, boolean isHour) {
        if (mLastAnnouncedIsHour != isHour || !text.equals(mLastAnnouncedText)) {
            // TODO: Find a better solution, potentially live regions?
            mDelegator.announceForAccessibility(text);
            mLastAnnouncedText = text;
            mLastAnnouncedIsHour = isHour;
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

    private void updateHeaderMinute(int value, boolean announceForAccessibility) {
        if (value == 60) {
            value = 0;
        }
        final CharSequence text = String.format(mCurrentLocale, "%02d", value);
        mMinuteView.setText(text);
        if (announceForAccessibility) {
            tryAnnounceForAccessibility(text, false);
        }
    }

    /**
     * Show either Hours or Minutes.
     */
    private void setCurrentItemShowing(int index, boolean animateCircle, boolean announce) {
        mRadialTimePickerView.setCurrentItemShowing(index, animateCircle);

        if (index == HOUR_INDEX) {
            if (announce) {
                mDelegator.announceForAccessibility(mSelectHours);
            }
        } else {
            if (announce) {
                mDelegator.announceForAccessibility(mSelectMinutes);
            }
        }

        mHourView.setActivated(index == HOUR_INDEX);
        mMinuteView.setActivated(index == MINUTE_INDEX);
    }

    private void setAmOrPm(int amOrPm) {
        updateAmPmLabelStates(amOrPm);
        mRadialTimePickerView.setAmOrPm(amOrPm);
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
                    mDelegator.announceForAccessibility(
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
        mDelegator.announceForAccessibility(String.format("%d", val));
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
            updateHeaderHour(hour, false);
            updateHeaderMinute(minute, false);
            if (!mIs24HourView) {
                updateAmPmLabelStates(hour < 12 ? AM : PM);
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
            mHourView.setActivated(false);
            mMinuteView.setText(minuteStr);
            mMinuteView.setActivated(false);
            if (!mIs24HourView) {
                updateAmPmLabelStates(values[2]);
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
            final KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            final CharSequence amText = mAmText.toLowerCase(mCurrentLocale);
            final CharSequence pmText = mPmText.toLowerCase(mCurrentLocale);
            final int N = Math.min(amText.length(), pmText.length());
            for (int i = 0; i < N; i++) {
                final char amChar = amText.charAt(i);
                final char pmChar = pmText.charAt(i);
                if (amChar != pmChar) {
                    // There should be 4 events: a down and up for both AM and PM.
                    final KeyEvent[] events = kcm.getEvents(new char[] { amChar, pmChar });
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

    private final View.OnClickListener mClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {

            final int amOrPm;
            switch (v.getId()) {
                case R.id.am_label:
                    setAmOrPm(AM);
                    break;
                case R.id.pm_label:
                    setAmOrPm(PM);
                    break;
                case R.id.hours:
                    setCurrentItemShowing(HOUR_INDEX, true, true);
                    break;
                case R.id.minutes:
                    setCurrentItemShowing(MINUTE_INDEX, true, true);
                    break;
                default:
                    // Failed to handle this click, don't vibrate.
                    return;
            }

            tryVibrate();
        }
    };

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
