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
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.NumberPicker.OnValueChangeListener;

import com.android.internal.R;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Locale;

/**
 * A view for selecting the time of day, in either 24 hour or AM/PM mode. The
 * hour, each minute digit, and AM/PM (if applicable) can be conrolled by
 * vertical spinners. The hour can be entered by keyboard input. Entering in two
 * digit hours can be accomplished by hitting two digits within a timeout of
 * about a second (e.g. '1' then '2' to select 12). The minutes can be entered
 * by entering single digits. Under AM/PM mode, the user can hit 'a', 'A", 'p'
 * or 'P' to pick. For a dialog using this view, see
 * {@link android.app.TimePickerDialog}.
 *<p>
 * See the <a href="{@docRoot}guide/topics/ui/controls/pickers.html">Pickers</a>
 * guide.
 * </p>
 */
@Widget
public class TimePicker extends FrameLayout {

    private TimePickerDelegate mDelegate;

    /**
     * The callback interface used to indicate the time has been adjusted.
     */
    public interface OnTimeChangedListener {

        /**
         * @param view The view associated with this listener.
         * @param hourOfDay The current hour.
         * @param minute The current minute.
         */
        void onTimeChanged(TimePicker view, int hourOfDay, int minute);
    }

    public TimePicker(Context context) {
        this(context, null);
    }

    public TimePicker(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.timePickerStyle);
    }

    public TimePicker(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TimePicker(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mDelegate = new LegacyTimePickerDelegate(this, context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Set the current hour.
     */
    public void setCurrentHour(Integer currentHour) {
        mDelegate.setCurrentHour(currentHour);
    }

    /**
     * @return The current hour in the range (0-23).
     */
    public Integer getCurrentHour() {
        return mDelegate.getCurrentHour();
    }

    /**
     * Set the current minute (0-59).
     */
    public void setCurrentMinute(Integer currentMinute) {
        mDelegate.setCurrentMinute(currentMinute);
    }

    /**
     * @return The current minute.
     */
    public Integer getCurrentMinute() {
        return mDelegate.getCurrentMinute();
    }

    /**
     * Set whether in 24 hour or AM/PM mode.
     *
     * @param is24HourView True = 24 hour mode. False = AM/PM.
     */
    public void setIs24HourView(Boolean is24HourView) {
        mDelegate.setIs24HourView(is24HourView);
    }

    /**
     * @return true if this is in 24 hour view else false.
     */
    public boolean is24HourView() {
        return mDelegate.is24HourView();
    }

    /**
     * Set the callback that indicates the time has been adjusted by the user.
     *
     * @param onTimeChangedListener the callback, should not be null.
     */
    public void setOnTimeChangedListener(OnTimeChangedListener onTimeChangedListener) {
        mDelegate.setOnTimeChangedListener(onTimeChangedListener);
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

    @Override
    public int getBaseline() {
        return mDelegate.getBaseline();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mDelegate.onConfigurationChanged(newConfig);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        return mDelegate.onSaveInstanceState(superState);
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        mDelegate.onRestoreInstanceState(ss);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return mDelegate.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        super.onPopulateAccessibilityEvent(event);
        mDelegate.onPopulateAccessibilityEvent(event);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        mDelegate.onInitializeAccessibilityEvent(event);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        mDelegate.onInitializeAccessibilityNodeInfo(info);
    }

    /**
     * A delegate interface that defined the public API of the TimePicker. Allows different
     * TimePicker implementations. This would need to be implemented by the TimePicker delegates
     * for the real behavior.
     */
    private interface TimePickerDelegate {
        void setCurrentHour(Integer currentHour);
        Integer getCurrentHour();

        void setCurrentMinute(Integer currentMinute);
        Integer getCurrentMinute();

        void setIs24HourView(Boolean is24HourView);
        boolean is24HourView();

        void setOnTimeChangedListener(OnTimeChangedListener onTimeChangedListener);

        void setEnabled(boolean enabled);
        boolean isEnabled();

        int getBaseline();

        void onConfigurationChanged(Configuration newConfig);

        Parcelable onSaveInstanceState(Parcelable superState);
        void onRestoreInstanceState(Parcelable state);

        boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event);
        void onPopulateAccessibilityEvent(AccessibilityEvent event);
        void onInitializeAccessibilityEvent(AccessibilityEvent event);
        void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info);
    }

    /**
     * A delegate implementing the basic TimePicker
     */
    private static class LegacyTimePickerDelegate implements TimePickerDelegate {
        // the delegator
        private TimePicker mDelegator;

        private static final boolean DEFAULT_ENABLED_STATE = true;

        private static final int HOURS_IN_HALF_DAY = 12;

        // state
        private boolean mIs24HourView;

        private boolean mIsAm;

        // ui components
        private final NumberPicker mHourSpinner;

        private final NumberPicker mMinuteSpinner;

        private final NumberPicker mAmPmSpinner;

        private final EditText mHourSpinnerInput;

        private final EditText mMinuteSpinnerInput;

        private final EditText mAmPmSpinnerInput;

        private final TextView mDivider;

        // Note that the legacy implementation of the TimePicker is
        // using a button for toggling between AM/PM while the new
        // version uses a NumberPicker spinner. Therefore the code
        // accommodates these two cases to be backwards compatible.
        private final Button mAmPmButton;

        private final String[] mAmPmStrings;

        private boolean mIsEnabled = DEFAULT_ENABLED_STATE;

        // callbacks
        private OnTimeChangedListener mOnTimeChangedListener;

        private Calendar mTempCalendar;

        private Locale mCurrentLocale;

        private boolean mHourWithTwoDigit;
        private char mHourFormat;

        /**
         * A no-op callback used in the constructor to avoid null checks later in
         * the code.
         */
        private static final OnTimeChangedListener NO_OP_CHANGE_LISTENER =
                new OnTimeChangedListener() {
                    public void onTimeChanged(TimePicker view, int hourOfDay, int minute) {
                }
        };

        public LegacyTimePickerDelegate(TimePicker delegator, Context context, AttributeSet attrs,
                int defStyleAttr, int defStyleRes) {
            mDelegator = delegator;

            // initialization based on locale
            setCurrentLocale(Locale.getDefault());

            // process style attributes
            final TypedArray attributesArray = context.obtainStyledAttributes(
                    attrs, R.styleable.TimePicker, defStyleAttr, defStyleRes);
            int layoutResourceId = attributesArray.getResourceId(
                    R.styleable.TimePicker_internalLayout, R.layout.time_picker);
            attributesArray.recycle();

            LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            inflater.inflate(layoutResourceId, mDelegator, true);

            // hour
            mHourSpinner = (NumberPicker) delegator.findViewById(R.id.hour);
            mHourSpinner.setOnValueChangedListener(new NumberPicker.OnValueChangeListener() {
                public void onValueChange(NumberPicker spinner, int oldVal, int newVal) {
                    updateInputState();
                    if (!is24HourView()) {
                        if ((oldVal == HOURS_IN_HALF_DAY - 1 && newVal == HOURS_IN_HALF_DAY) ||
                                (oldVal == HOURS_IN_HALF_DAY && newVal == HOURS_IN_HALF_DAY - 1)) {
                            mIsAm = !mIsAm;
                            updateAmPmControl();
                        }
                    }
                    onTimeChanged();
                }
            });
            mHourSpinnerInput = (EditText) mHourSpinner.findViewById(R.id.numberpicker_input);
            mHourSpinnerInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);

            // divider (only for the new widget style)
            mDivider = (TextView) mDelegator.findViewById(R.id.divider);
            if (mDivider != null) {
                setDividerText();
            }

            // minute
            mMinuteSpinner = (NumberPicker) mDelegator.findViewById(R.id.minute);
            mMinuteSpinner.setMinValue(0);
            mMinuteSpinner.setMaxValue(59);
            mMinuteSpinner.setOnLongPressUpdateInterval(100);
            mMinuteSpinner.setFormatter(NumberPicker.getTwoDigitFormatter());
            mMinuteSpinner.setOnValueChangedListener(new NumberPicker.OnValueChangeListener() {
                public void onValueChange(NumberPicker spinner, int oldVal, int newVal) {
                    updateInputState();
                    int minValue = mMinuteSpinner.getMinValue();
                    int maxValue = mMinuteSpinner.getMaxValue();
                    if (oldVal == maxValue && newVal == minValue) {
                        int newHour = mHourSpinner.getValue() + 1;
                        if (!is24HourView() && newHour == HOURS_IN_HALF_DAY) {
                            mIsAm = !mIsAm;
                            updateAmPmControl();
                        }
                        mHourSpinner.setValue(newHour);
                    } else if (oldVal == minValue && newVal == maxValue) {
                        int newHour = mHourSpinner.getValue() - 1;
                        if (!is24HourView() && newHour == HOURS_IN_HALF_DAY - 1) {
                            mIsAm = !mIsAm;
                            updateAmPmControl();
                        }
                        mHourSpinner.setValue(newHour);
                    }
                    onTimeChanged();
                }
            });
            mMinuteSpinnerInput = (EditText) mMinuteSpinner.findViewById(R.id.numberpicker_input);
            mMinuteSpinnerInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);

            /* Get the localized am/pm strings and use them in the spinner */
            mAmPmStrings = new DateFormatSymbols().getAmPmStrings();

            // am/pm
            View amPmView = mDelegator.findViewById(R.id.amPm);
            if (amPmView instanceof Button) {
                mAmPmSpinner = null;
                mAmPmSpinnerInput = null;
                mAmPmButton = (Button) amPmView;
                mAmPmButton.setOnClickListener(new OnClickListener() {
                    public void onClick(View button) {
                        button.requestFocus();
                        mIsAm = !mIsAm;
                        updateAmPmControl();
                        onTimeChanged();
                    }
                });
            } else {
                mAmPmButton = null;
                mAmPmSpinner = (NumberPicker) amPmView;
                mAmPmSpinner.setMinValue(0);
                mAmPmSpinner.setMaxValue(1);
                mAmPmSpinner.setDisplayedValues(mAmPmStrings);
                mAmPmSpinner.setOnValueChangedListener(new OnValueChangeListener() {
                    public void onValueChange(NumberPicker picker, int oldVal, int newVal) {
                        updateInputState();
                        picker.requestFocus();
                        mIsAm = !mIsAm;
                        updateAmPmControl();
                        onTimeChanged();
                    }
                });
                mAmPmSpinnerInput = (EditText) mAmPmSpinner.findViewById(R.id.numberpicker_input);
                mAmPmSpinnerInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
            }

            if (isAmPmAtStart()) {
                // Move the am/pm view to the beginning
                ViewGroup amPmParent = (ViewGroup) delegator.findViewById(R.id.timePickerLayout);
                amPmParent.removeView(amPmView);
                amPmParent.addView(amPmView, 0);
                // Swap layout margins if needed. They may be not symmetrical (Old Standard Theme
                // for example and not for Holo Theme)
                ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) amPmView.getLayoutParams();
                final int startMargin = lp.getMarginStart();
                final int endMargin = lp.getMarginEnd();
                if (startMargin != endMargin) {
                    lp.setMarginStart(endMargin);
                    lp.setMarginEnd(startMargin);
                }
            }

            getHourFormatData();

            // update controls to initial state
            updateHourControl();
            updateMinuteControl();
            updateAmPmControl();

            setOnTimeChangedListener(NO_OP_CHANGE_LISTENER);

            // set to current time
            setCurrentHour(mTempCalendar.get(Calendar.HOUR_OF_DAY));
            setCurrentMinute(mTempCalendar.get(Calendar.MINUTE));

            if (!isEnabled()) {
                setEnabled(false);
            }

            // set the content descriptions
            setContentDescriptions();

            // If not explicitly specified this view is important for accessibility.
            if (mDelegator.getImportantForAccessibility() == IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                mDelegator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            }
        }

        private void getHourFormatData() {
            final Locale defaultLocale = Locale.getDefault();
            final String bestDateTimePattern = DateFormat.getBestDateTimePattern(defaultLocale,
                    (mIs24HourView) ? "Hm" : "hm");
            final int lengthPattern = bestDateTimePattern.length();
            mHourWithTwoDigit = false;
            char hourFormat = '\0';
            // Check if the returned pattern is single or double 'H', 'h', 'K', 'k'. We also save
            // the hour format that we found.
            for (int i = 0; i < lengthPattern; i++) {
                final char c = bestDateTimePattern.charAt(i);
                if (c == 'H' || c == 'h' || c == 'K' || c == 'k') {
                    mHourFormat = c;
                    if (i + 1 < lengthPattern && c == bestDateTimePattern.charAt(i + 1)) {
                        mHourWithTwoDigit = true;
                    }
                    break;
                }
            }
        }

        private boolean isAmPmAtStart() {
            final Locale defaultLocale = Locale.getDefault();
            final String bestDateTimePattern = DateFormat.getBestDateTimePattern(defaultLocale,
                    "hm" /* skeleton */);

            return bestDateTimePattern.startsWith("a");
        }

        /**
         * The time separator is defined in the Unicode CLDR and cannot be supposed to be ":".
         *
         * See http://unicode.org/cldr/trac/browser/trunk/common/main
         *
         * We pass the correct "skeleton" depending on 12 or 24 hours view and then extract the
         * separator as the character which is just after the hour marker in the returned pattern.
         */
        private void setDividerText() {
            final Locale defaultLocale = Locale.getDefault();
            final String skeleton = (mIs24HourView) ? "Hm" : "hm";
            final String bestDateTimePattern = DateFormat.getBestDateTimePattern(defaultLocale,
                    skeleton);
            final String separatorText;
            int hourIndex = bestDateTimePattern.lastIndexOf('H');
            if (hourIndex == -1) {
                hourIndex = bestDateTimePattern.lastIndexOf('h');
            }
            if (hourIndex == -1) {
                // Default case
                separatorText = ":";
            } else {
                int minuteIndex = bestDateTimePattern.indexOf('m', hourIndex + 1);
                if  (minuteIndex == -1) {
                    separatorText = Character.toString(bestDateTimePattern.charAt(hourIndex + 1));
                } else {
                    separatorText = bestDateTimePattern.substring(hourIndex + 1, minuteIndex);
                }
            }
            mDivider.setText(separatorText);
        }

        @Override
        public void setCurrentHour(Integer currentHour) {
            setCurrentHour(currentHour, true);
        }

        private void setCurrentHour(Integer currentHour, boolean notifyTimeChanged) {
            // why was Integer used in the first place?
            if (currentHour == null || currentHour == getCurrentHour()) {
                return;
            }
            if (!is24HourView()) {
                // convert [0,23] ordinal to wall clock display
                if (currentHour >= HOURS_IN_HALF_DAY) {
                    mIsAm = false;
                    if (currentHour > HOURS_IN_HALF_DAY) {
                        currentHour = currentHour - HOURS_IN_HALF_DAY;
                    }
                } else {
                    mIsAm = true;
                    if (currentHour == 0) {
                        currentHour = HOURS_IN_HALF_DAY;
                    }
                }
                updateAmPmControl();
            }
            mHourSpinner.setValue(currentHour);
            if (notifyTimeChanged) {
                onTimeChanged();
            }
        }

        @Override
        public Integer getCurrentHour() {
            int currentHour = mHourSpinner.getValue();
            if (is24HourView()) {
                return currentHour;
            } else if (mIsAm) {
                return currentHour % HOURS_IN_HALF_DAY;
            } else {
                return (currentHour % HOURS_IN_HALF_DAY) + HOURS_IN_HALF_DAY;
            }
        }

        @Override
        public void setCurrentMinute(Integer currentMinute) {
            if (currentMinute == getCurrentMinute()) {
                return;
            }
            mMinuteSpinner.setValue(currentMinute);
            onTimeChanged();
        }

        @Override
        public Integer getCurrentMinute() {
            return mMinuteSpinner.getValue();
        }

        @Override
        public void setIs24HourView(Boolean is24HourView) {
            if (mIs24HourView == is24HourView) {
                return;
            }
            // cache the current hour since spinner range changes and BEFORE changing mIs24HourView!!
            int currentHour = getCurrentHour();
            // Order is important here.
            mIs24HourView = is24HourView;
            getHourFormatData();
            updateHourControl();
            // set value after spinner range is updated - be aware that because mIs24HourView has
            // changed then getCurrentHour() is not equal to the currentHour we cached before so
            // explicitly ask for *not* propagating any onTimeChanged()
            setCurrentHour(currentHour, false /* no onTimeChanged() */);
            updateMinuteControl();
            updateAmPmControl();
        }

        @Override
        public boolean is24HourView() {
            return mIs24HourView;
        }

        @Override
        public void setOnTimeChangedListener(OnTimeChangedListener onTimeChangedListener) {
            mOnTimeChangedListener = onTimeChangedListener;
        }

        @Override
        public void setEnabled(boolean enabled) {
            mMinuteSpinner.setEnabled(enabled);
            if (mDivider != null) {
                mDivider.setEnabled(enabled);
            }
            mHourSpinner.setEnabled(enabled);
            if (mAmPmSpinner != null) {
                mAmPmSpinner.setEnabled(enabled);
            } else {
                mAmPmButton.setEnabled(enabled);
            }
            mIsEnabled = enabled;
        }

        @Override
        public boolean isEnabled() {
            return mIsEnabled;
        }

        @Override
        public int getBaseline() {
            return mHourSpinner.getBaseline();
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            setCurrentLocale(newConfig.locale);
        }

        @Override
        public Parcelable onSaveInstanceState(Parcelable superState) {
            return new SavedState(superState, getCurrentHour(), getCurrentMinute());
        }

        @Override
        public void onRestoreInstanceState(Parcelable state) {
            SavedState ss = (SavedState) state;
            setCurrentHour(ss.getHour());
            setCurrentMinute(ss.getMinute());
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
            String selectedDateUtterance = DateUtils.formatDateTime(mDelegator.getContext(),
                    mTempCalendar.getTimeInMillis(), flags);
            event.getText().add(selectedDateUtterance);
        }

        @Override
        public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
            event.setClassName(TimePicker.class.getName());
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            info.setClassName(TimePicker.class.getName());
        }

        private void updateInputState() {
            // Make sure that if the user changes the value and the IME is active
            // for one of the inputs if this widget, the IME is closed. If the user
            // changed the value via the IME and there is a next input the IME will
            // be shown, otherwise the user chose another means of changing the
            // value and having the IME up makes no sense.
            InputMethodManager inputMethodManager = InputMethodManager.peekInstance();
            if (inputMethodManager != null) {
                if (inputMethodManager.isActive(mHourSpinnerInput)) {
                    mHourSpinnerInput.clearFocus();
                    inputMethodManager.hideSoftInputFromWindow(mDelegator.getWindowToken(), 0);
                } else if (inputMethodManager.isActive(mMinuteSpinnerInput)) {
                    mMinuteSpinnerInput.clearFocus();
                    inputMethodManager.hideSoftInputFromWindow(mDelegator.getWindowToken(), 0);
                } else if (inputMethodManager.isActive(mAmPmSpinnerInput)) {
                    mAmPmSpinnerInput.clearFocus();
                    inputMethodManager.hideSoftInputFromWindow(mDelegator.getWindowToken(), 0);
                }
            }
    }

        private void updateAmPmControl() {
            if (is24HourView()) {
                if (mAmPmSpinner != null) {
                    mAmPmSpinner.setVisibility(View.GONE);
                } else {
                    mAmPmButton.setVisibility(View.GONE);
                }
            } else {
                int index = mIsAm ? Calendar.AM : Calendar.PM;
                if (mAmPmSpinner != null) {
                    mAmPmSpinner.setValue(index);
                    mAmPmSpinner.setVisibility(View.VISIBLE);
                } else {
                    mAmPmButton.setText(mAmPmStrings[index]);
                    mAmPmButton.setVisibility(View.VISIBLE);
                }
            }
            mDelegator.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        }

        /**
         * Sets the current locale.
         *
         * @param locale The current locale.
         */
        private void setCurrentLocale(Locale locale) {
            if (locale.equals(mCurrentLocale)) {
                return;
            }
            mCurrentLocale = locale;
            mTempCalendar = Calendar.getInstance(locale);
        }

        private void onTimeChanged() {
            mDelegator.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            if (mOnTimeChangedListener != null) {
                mOnTimeChangedListener.onTimeChanged(mDelegator, getCurrentHour(),
                        getCurrentMinute());
            }
        }

        private void updateHourControl() {
            if (is24HourView()) {
                // 'k' means 1-24 hour
                if (mHourFormat == 'k') {
                    mHourSpinner.setMinValue(1);
                    mHourSpinner.setMaxValue(24);
                } else {
                    mHourSpinner.setMinValue(0);
                    mHourSpinner.setMaxValue(23);
                }
            } else {
                // 'K' means 0-11 hour
                if (mHourFormat == 'K') {
                    mHourSpinner.setMinValue(0);
                    mHourSpinner.setMaxValue(11);
                } else {
                    mHourSpinner.setMinValue(1);
                    mHourSpinner.setMaxValue(12);
                }
            }
            mHourSpinner.setFormatter(mHourWithTwoDigit ? NumberPicker.getTwoDigitFormatter() : null);
        }

        private void updateMinuteControl() {
            if (is24HourView()) {
                mMinuteSpinnerInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
            } else {
                mMinuteSpinnerInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);
            }
        }

        private void setContentDescriptions() {
            // Minute
            trySetContentDescription(mMinuteSpinner, R.id.increment,
                    R.string.time_picker_increment_minute_button);
            trySetContentDescription(mMinuteSpinner, R.id.decrement,
                    R.string.time_picker_decrement_minute_button);
            // Hour
            trySetContentDescription(mHourSpinner, R.id.increment,
                    R.string.time_picker_increment_hour_button);
            trySetContentDescription(mHourSpinner, R.id.decrement,
                    R.string.time_picker_decrement_hour_button);
            // AM/PM
            if (mAmPmSpinner != null) {
                trySetContentDescription(mAmPmSpinner, R.id.increment,
                        R.string.time_picker_increment_set_pm_button);
                trySetContentDescription(mAmPmSpinner, R.id.decrement,
                        R.string.time_picker_decrement_set_am_button);
            }
        }

        private void trySetContentDescription(View root, int viewId, int contDescResId) {
            View target = root.findViewById(viewId);
            if (target != null) {
                target.setContentDescription(mDelegator.getContext().getString(contDescResId));
            }
        }
    }

    /**
     * Used to save / restore state of time picker
     */
    private static class SavedState extends BaseSavedState {

        private final int mHour;

        private final int mMinute;

        private SavedState(Parcelable superState, int hour, int minute) {
            super(superState);
            mHour = hour;
            mMinute = minute;
        }

        private SavedState(Parcel in) {
            super(in);
            mHour = in.readInt();
            mMinute = in.readInt();
        }

        public int getHour() {
            return mHour;
        }

        public int getMinute() {
            return mMinute;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mHour);
            dest.writeInt(mMinute);
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
}
