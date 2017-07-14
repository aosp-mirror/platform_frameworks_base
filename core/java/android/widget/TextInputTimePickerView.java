/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.os.LocaleList;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.View;

import com.android.internal.R;

/**
 * View to show text input based time picker with hour and minute fields and an optional AM/PM
 * spinner.
 *
 * @hide
 */
public class TextInputTimePickerView extends RelativeLayout {
    public static final int HOURS = 0;
    public static final int MINUTES = 1;
    public static final int AMPM = 2;

    private static final int AM = 0;
    private static final int PM = 1;

    private final EditText mHourEditText;
    private final EditText mMinuteEditText;
    private final TextView mInputSeparatorView;
    private final Spinner mAmPmSpinner;
    private final TextView mErrorLabel;
    private final TextView mHourLabel;
    private final TextView mMinuteLabel;

    private boolean mIs24Hour;
    private boolean mHourFormatStartsAtZero;
    private OnValueTypedListener mListener;

    private boolean mErrorShowing;

    interface OnValueTypedListener {
        void onValueChanged(int inputType, int newValue);
    }

    public TextInputTimePickerView(Context context) {
        this(context, null);
    }

    public TextInputTimePickerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TextInputTimePickerView(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, 0);
    }

    public TextInputTimePickerView(Context context, AttributeSet attrs, int defStyle,
            int defStyleRes) {
        super(context, attrs, defStyle, defStyleRes);

        inflate(context, R.layout.time_picker_text_input_material, this);

        mHourEditText = findViewById(R.id.input_hour);
        mMinuteEditText = findViewById(R.id.input_minute);
        mInputSeparatorView = findViewById(R.id.input_separator);
        mErrorLabel = findViewById(R.id.label_error);
        mHourLabel = findViewById(R.id.label_hour);
        mMinuteLabel = findViewById(R.id.label_minute);

        mHourEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void afterTextChanged(Editable editable) {
                parseAndSetHourInternal(editable.toString());
            }
        });

        mMinuteEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {}

            @Override
            public void afterTextChanged(Editable editable) {
                parseAndSetMinuteInternal(editable.toString());
            }
        });

        mAmPmSpinner = findViewById(R.id.am_pm_spinner);
        final String[] amPmStrings = TimePicker.getAmPmStrings(context);
        ArrayAdapter<CharSequence> adapter =
                new ArrayAdapter<CharSequence>(context, R.layout.simple_spinner_dropdown_item);
        adapter.add(TimePickerClockDelegate.obtainVerbatim(amPmStrings[0]));
        adapter.add(TimePickerClockDelegate.obtainVerbatim(amPmStrings[1]));
        mAmPmSpinner.setAdapter(adapter);
        mAmPmSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int position,
                    long id) {
                if (position == 0) {
                    mListener.onValueChanged(AMPM, AM);
                } else {
                    mListener.onValueChanged(AMPM, PM);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {}
        });
    }

    void setListener(OnValueTypedListener listener) {
        mListener = listener;
    }

    void setHourFormat(int maxCharLength) {
        mHourEditText.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(maxCharLength)});
        mMinuteEditText.setFilters(new InputFilter[] {
                new InputFilter.LengthFilter(maxCharLength)});
        final LocaleList locales = mContext.getResources().getConfiguration().getLocales();
        mHourEditText.setImeHintLocales(locales);
        mMinuteEditText.setImeHintLocales(locales);
    }

    boolean validateInput() {
        final boolean inputValid = parseAndSetHourInternal(mHourEditText.getText().toString())
                && parseAndSetMinuteInternal(mMinuteEditText.getText().toString());
        setError(!inputValid);
        return inputValid;
    }

    void updateSeparator(String separatorText) {
        mInputSeparatorView.setText(separatorText);
    }

    private void setError(boolean enabled) {
        mErrorShowing = enabled;

        mErrorLabel.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        mHourLabel.setVisibility(enabled ? View.INVISIBLE : View.VISIBLE);
        mMinuteLabel.setVisibility(enabled ? View.INVISIBLE : View.VISIBLE);
    }

    /**
     * Computes the display value and updates the text of the view.
     * <p>
     * This method should be called whenever the current value or display
     * properties (leading zeroes, max digits) change.
     */
    void updateTextInputValues(int localizedHour, int minute, int amOrPm, boolean is24Hour,
            boolean hourFormatStartsAtZero) {
        final String format = "%d";

        mIs24Hour = is24Hour;
        mHourFormatStartsAtZero = hourFormatStartsAtZero;

        mAmPmSpinner.setVisibility(is24Hour ? View.INVISIBLE : View.VISIBLE);

        if (amOrPm == AM) {
            mAmPmSpinner.setSelection(0);
        } else {
            mAmPmSpinner.setSelection(1);
        }

        mHourEditText.setText(String.format(format, localizedHour));
        mMinuteEditText.setText(String.format(format, minute));

        if (mErrorShowing) {
            validateInput();
        }
    }

    private boolean parseAndSetHourInternal(String input) {
        try {
            final int hour = Integer.parseInt(input);
            if (!isValidLocalizedHour(hour)) {
                final int minHour = mHourFormatStartsAtZero ? 0 : 1;
                final int maxHour = mIs24Hour ? 23 : 11 + minHour;
                mListener.onValueChanged(HOURS, getHourOfDayFromLocalizedHour(
                        MathUtils.constrain(hour, minHour, maxHour)));
                return false;
            }
            mListener.onValueChanged(HOURS, getHourOfDayFromLocalizedHour(hour));
            return true;
        } catch (NumberFormatException e) {
            // Do nothing since we cannot parse the input.
            return false;
        }
    }

    private boolean parseAndSetMinuteInternal(String input) {
        try {
            final int minutes = Integer.parseInt(input);
            if (minutes < 0 || minutes > 59) {
                mListener.onValueChanged(MINUTES, MathUtils.constrain(minutes, 0, 59));
                return false;
            }
            mListener.onValueChanged(MINUTES, minutes);
            return true;
        } catch (NumberFormatException e) {
            // Do nothing since we cannot parse the input.
            return false;
        }
    }

    private boolean isValidLocalizedHour(int localizedHour) {
        final int minHour = mHourFormatStartsAtZero ? 0 : 1;
        final int maxHour = (mIs24Hour ? 23 : 11) + minHour;
        return localizedHour >= minHour && localizedHour <= maxHour;
    }

    private int getHourOfDayFromLocalizedHour(int localizedHour) {
        int hourOfDay = localizedHour;
        if (mIs24Hour) {
            if (!mHourFormatStartsAtZero && localizedHour == 24) {
                hourOfDay = 0;
            }
        } else {
            if (!mHourFormatStartsAtZero && localizedHour == 12) {
                hourOfDay = 0;
            }
            if (mAmPmSpinner.getSelectedItemPosition() == 1) {
                hourOfDay += 12;
            }
        }
        return hourOfDay;
    }
}
