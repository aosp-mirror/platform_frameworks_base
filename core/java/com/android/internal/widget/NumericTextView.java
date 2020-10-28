/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.widget;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.StateSet;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * Extension of TextView that can handle displaying and inputting a range of
 * numbers.
 * <p>
 * Clients of this view should never call {@link #setText(CharSequence)} or
 * {@link #setHint(CharSequence)} directly. Instead, they should call
 * {@link #setValue(int)} to modify the currently displayed value.
 */
public class NumericTextView extends TextView {
    private static final int RADIX = 10;
    private static final double LOG_RADIX = Math.log(RADIX);

    private int mMinValue = 0;
    private int mMaxValue = 99;

    /** Number of digits in the maximum value. */
    private int mMaxCount = 2;

    private boolean mShowLeadingZeroes = true;

    private int mValue;

    /** Number of digits entered during editing mode. */
    private int mCount;

    /** Used to restore the value after an aborted edit. */
    private int mPreviousValue;

    private OnValueChangedListener mListener;

    @UnsupportedAppUsage
    public NumericTextView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Generate the hint text color based on disabled state.
        final int textColorDisabled = getTextColors().getColorForState(StateSet.get(0), 0);
        setHintTextColor(textColorDisabled);

        setFocusable(true);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);

        if (focused) {
            mPreviousValue = mValue;
            mValue = 0;
            mCount = 0;

            // Transfer current text to hint.
            setHint(getText());
            setText("");
        } else {
            if (mCount == 0) {
                // No digits were entered, revert to previous value.
                mValue = mPreviousValue;

                setText(getHint());
                setHint("");
            }

            // Ensure the committed value is within range.
            if (mValue < mMinValue) {
                mValue = mMinValue;
            }

            setValue(mValue);

            if (mListener != null) {
                mListener.onValueChanged(this, mValue, true, true);
            }
        }
    }

    /**
     * Sets the currently displayed value.
     * <p>
     * The specified {@code value} must be within the range specified by
     * {@link #setRange(int, int)} (e.g. between {@link #getRangeMinimum()}
     * and {@link #getRangeMaximum()}).
     *
     * @param value the value to display
     */
    public final void setValue(int value) {
        if (mValue != value) {
            mValue = value;

            updateDisplayedValue();
        }
    }

    /**
     * Returns the currently displayed value.
     * <p>
     * If the value is currently being edited, returns the live value which may
     * not be within the range specified by {@link #setRange(int, int)}.
     *
     * @return the currently displayed value
     */
    public final int getValue() {
        return mValue;
    }

    /**
     * Sets the valid range (inclusive).
     *
     * @param minValue the minimum valid value (inclusive)
     * @param maxValue the maximum valid value (inclusive)
     */
    public final void setRange(int minValue, int maxValue) {
        if (mMinValue != minValue) {
            mMinValue = minValue;
        }

        if (mMaxValue != maxValue) {
            mMaxValue = maxValue;
            mMaxCount = 1 + (int) (Math.log(maxValue) / LOG_RADIX);

            updateMinimumWidth();
            updateDisplayedValue();
        }
    }

    /**
     * @return the minimum value value (inclusive)
     */
    public final int getRangeMinimum() {
        return mMinValue;
    }

    /**
     * @return the maximum value value (inclusive)
     */
    public final int getRangeMaximum() {
        return mMaxValue;
    }

    /**
     * Sets whether this view shows leading zeroes.
     * <p>
     * When leading zeroes are shown, the displayed value will be padded
     * with zeroes to the width of the maximum value as specified by
     * {@link #setRange(int, int)} (see also {@link #getRangeMaximum()}.
     * <p>
     * For example, with leading zeroes shown, a maximum of 99 and value of
     * 9 would display "09". A maximum of 100 and a value of 9 would display
     * "009". With leading zeroes hidden, both cases would show "9".
     *
     * @param showLeadingZeroes {@code true} to show leading zeroes,
     *                          {@code false} to hide them
     */
    public final void setShowLeadingZeroes(boolean showLeadingZeroes) {
        if (mShowLeadingZeroes != showLeadingZeroes) {
            mShowLeadingZeroes = showLeadingZeroes;

            updateDisplayedValue();
        }
    }

    public final boolean getShowLeadingZeroes() {
        return mShowLeadingZeroes;
    }

    /**
     * Computes the display value and updates the text of the view.
     * <p>
     * This method should be called whenever the current value or display
     * properties (leading zeroes, max digits) change.
     */
    private void updateDisplayedValue() {
        final String format;
        if (mShowLeadingZeroes) {
            format = "%0" + mMaxCount + "d";
        } else {
            format = "%d";
        }

        // Always use String.format() rather than Integer.toString()
        // to obtain correctly localized values.
        setText(String.format(format, mValue));
    }

    /**
     * Computes the minimum width in pixels required to display all possible
     * values and updates the minimum width of the view.
     * <p>
     * This method should be called whenever the maximum value changes.
     */
    private void updateMinimumWidth() {
        final CharSequence previousText = getText();
        int maxWidth = 0;

        for (int i = 0; i < mMaxValue; i++) {
            setText(String.format("%0" + mMaxCount + "d", i));
            measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);

            final int width = getMeasuredWidth();
            if (width > maxWidth) {
                maxWidth = width;
            }
        }

        setText(previousText);
        setMinWidth(maxWidth);
        setMinimumWidth(maxWidth);
    }

    public final void setOnDigitEnteredListener(OnValueChangedListener listener) {
        mListener = listener;
    }

    public final OnValueChangedListener getOnDigitEnteredListener() {
        return mListener;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return isKeyCodeNumeric(keyCode)
                || (keyCode == KeyEvent.KEYCODE_DEL)
                || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return isKeyCodeNumeric(keyCode)
                || (keyCode == KeyEvent.KEYCODE_DEL)
                || super.onKeyMultiple(keyCode, repeatCount, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKeyUp(keyCode)
                || super.onKeyUp(keyCode, event);
    }

    private boolean handleKeyUp(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            // Backspace removes the least-significant digit, if available.
            if (mCount > 0) {
                mValue /= RADIX;
                mCount--;
            }
        } else if (isKeyCodeNumeric(keyCode)) {
            if (mCount < mMaxCount) {
                final int keyValue = numericKeyCodeToInt(keyCode);
                final int newValue = mValue * RADIX + keyValue;
                if (newValue <= mMaxValue) {
                    mValue = newValue;
                    mCount++;
                }
            }
        } else {
            return false;
        }

        final String formattedValue;
        if (mCount > 0) {
            // If the user types 01, we should always show the leading 0 even if
            // getShowLeadingZeroes() is false. Preserve typed leading zeroes by
            // using the number of digits entered as the format width.
            formattedValue = String.format("%0" + mCount + "d", mValue);
        } else {
            formattedValue = "";
        }

        setText(formattedValue);

        if (mListener != null) {
            final boolean isValid = mValue >= mMinValue;
            final boolean isFinished = mCount >= mMaxCount || mValue * RADIX > mMaxValue;
            mListener.onValueChanged(this, mValue, isValid, isFinished);
        }

        return true;
    }

    private static boolean isKeyCodeNumeric(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_1
                || keyCode == KeyEvent.KEYCODE_2 || keyCode == KeyEvent.KEYCODE_3
                || keyCode == KeyEvent.KEYCODE_4 || keyCode == KeyEvent.KEYCODE_5
                || keyCode == KeyEvent.KEYCODE_6 || keyCode == KeyEvent.KEYCODE_7
                || keyCode == KeyEvent.KEYCODE_8 || keyCode == KeyEvent.KEYCODE_9;
    }

    private static int numericKeyCodeToInt(int keyCode) {
        return keyCode - KeyEvent.KEYCODE_0;
    }

    public interface OnValueChangedListener {
        /**
         * Called when the value displayed by {@code view} changes.
         *
         * @param view the view whose value changed
         * @param value the new value
         * @param isValid {@code true} if the value is valid (e.g. within the
         *                range specified by {@link #setRange(int, int)}),
         *                {@code false} otherwise
         * @param isFinished {@code true} if the no more digits may be entered,
         *                   {@code false} if more digits may be entered
         */
        void onValueChanged(NumericTextView view, int value, boolean isValid, boolean isFinished);
    }
}
