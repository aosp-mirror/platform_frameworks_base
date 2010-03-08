/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.os.Handler;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.method.NumberKeyListener;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnLongClickListener;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.EditText;

import com.android.internal.R;

/**
 * A view for selecting a number
 *
 * For a dialog using this view, see {@link android.app.TimePickerDialog}.
 * @hide
 */
@Widget
public class NumberPicker extends LinearLayout {

    /**
     * The callback interface used to indicate the number value has been adjusted.
     */
    public interface OnChangedListener {
        /**
         * @param picker The NumberPicker associated with this listener.
         * @param oldVal The previous value.
         * @param newVal The new value.
         */
        void onChanged(NumberPicker picker, int oldVal, int newVal);
    }

    /**
     * Interface used to format the number into a string for presentation
     */
    public interface Formatter {
        String toString(int value);
    }

    /*
     * Use a custom NumberPicker formatting callback to use two-digit
     * minutes strings like "01".  Keeping a static formatter etc. is the
     * most efficient way to do this; it avoids creating temporary objects
     * on every call to format().
     */
    public static final NumberPicker.Formatter TWO_DIGIT_FORMATTER =
            new NumberPicker.Formatter() {
                final StringBuilder mBuilder = new StringBuilder();
                final java.util.Formatter mFmt = new java.util.Formatter(mBuilder);
                final Object[] mArgs = new Object[1];
                public String toString(int value) {
                    mArgs[0] = value;
                    mBuilder.delete(0, mBuilder.length());
                    mFmt.format("%02d", mArgs);
                    return mFmt.toString();
                }
        };

    private final Handler mHandler;
    private final Runnable mRunnable = new Runnable() {
        public void run() {
            if (mIncrement) {
                changeCurrent(mCurrent + 1);
                mHandler.postDelayed(this, mSpeed);
            } else if (mDecrement) {
                changeCurrent(mCurrent - 1);
                mHandler.postDelayed(this, mSpeed);
            }
        }
    };

    private final EditText mText;
    private final InputFilter mNumberInputFilter;

    private String[] mDisplayedValues;

    /**
     * Lower value of the range of numbers allowed for the NumberPicker
     */
    private int mStart;

    /**
     * Upper value of the range of numbers allowed for the NumberPicker
     */
    private int mEnd;

    /**
     * Current value of this NumberPicker
     */
    private int mCurrent;

    /**
     * Previous value of this NumberPicker.
     */
    private int mPrevious;
    private OnChangedListener mListener;
    private Formatter mFormatter;
    private long mSpeed = 300;

    private boolean mIncrement;
    private boolean mDecrement;

    /**
     * Create a new number picker
     * @param context the application environment
     */
    public NumberPicker(Context context) {
        this(context, null);
    }

    /**
     * Create a new number picker
     * @param context the application environment
     * @param attrs a collection of attributes
     */
    public NumberPicker(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater inflater =
                (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.number_picker, this, true);
        mHandler = new Handler();

        OnClickListener clickListener = new OnClickListener() {
            public void onClick(View v) {
                validateInput(mText);
                if (!mText.hasFocus()) mText.requestFocus();

                // now perform the increment/decrement
                if (R.id.increment == v.getId()) {
                    changeCurrent(mCurrent + 1);
                } else if (R.id.decrement == v.getId()) {
                    changeCurrent(mCurrent - 1);
                }
            }
        };

        OnFocusChangeListener focusListener = new OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {

                /* When focus is lost check that the text field
                 * has valid values.
                 */
                if (!hasFocus) {
                    validateInput(v);
                }
            }
        };

        OnLongClickListener longClickListener = new OnLongClickListener() {
            /**
             * We start the long click here but rely on the {@link NumberPickerButton}
             * to inform us when the long click has ended.
             */
            public boolean onLongClick(View v) {
                /* The text view may still have focus so clear it's focus which will
                 * trigger the on focus changed and any typed values to be pulled.
                 */
                mText.clearFocus();

                if (R.id.increment == v.getId()) {
                    mIncrement = true;
                    mHandler.post(mRunnable);
                } else if (R.id.decrement == v.getId()) {
                    mDecrement = true;
                    mHandler.post(mRunnable);
                }
                return true;
            }
        };

        InputFilter inputFilter = new NumberPickerInputFilter();
        mNumberInputFilter = new NumberRangeKeyListener();
        mIncrementButton = (NumberPickerButton) findViewById(R.id.increment);
        mIncrementButton.setOnClickListener(clickListener);
        mIncrementButton.setOnLongClickListener(longClickListener);
        mIncrementButton.setNumberPicker(this);

        mDecrementButton = (NumberPickerButton) findViewById(R.id.decrement);
        mDecrementButton.setOnClickListener(clickListener);
        mDecrementButton.setOnLongClickListener(longClickListener);
        mDecrementButton.setNumberPicker(this);

        mText = (EditText) findViewById(R.id.timepicker_input);
        mText.setOnFocusChangeListener(focusListener);
        mText.setFilters(new InputFilter[] {inputFilter});
        mText.setRawInputType(InputType.TYPE_CLASS_NUMBER);

        if (!isEnabled()) {
            setEnabled(false);
        }
    }

    /**
     * Set the enabled state of this view. The interpretation of the enabled
     * state varies by subclass.
     *
     * @param enabled True if this view is enabled, false otherwise.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mIncrementButton.setEnabled(enabled);
        mDecrementButton.setEnabled(enabled);
        mText.setEnabled(enabled);
    }

    /**
     * Set the callback that indicates the number has been adjusted by the user.
     * @param listener the callback, should not be null.
     */
    public void setOnChangeListener(OnChangedListener listener) {
        mListener = listener;
    }

    /**
     * Set the formatter that will be used to format the number for presentation
     * @param formatter the formatter object.  If formatter is null, String.valueOf()
     * will be used
     */
    public void setFormatter(Formatter formatter) {
        mFormatter = formatter;
    }

    /**
     * Set the range of numbers allowed for the number picker. The current
     * value will be automatically set to the start.
     *
     * @param start the start of the range (inclusive)
     * @param end the end of the range (inclusive)
     */
    public void setRange(int start, int end) {
        setRange(start, end, null/*displayedValues*/);
    }

    /**
     * Set the range of numbers allowed for the number picker. The current
     * value will be automatically set to the start. Also provide a mapping
     * for values used to display to the user.
     *
     * @param start the start of the range (inclusive)
     * @param end the end of the range (inclusive)
     * @param displayedValues the values displayed to the user.
     */
    public void setRange(int start, int end, String[] displayedValues) {
        mDisplayedValues = displayedValues;
        mStart = start;
        mEnd = end;
        mCurrent = start;
        updateView();
    }

    /**
     * Set the current value for the number picker.
     *
     * @param current the current value the start of the range (inclusive)
     * @throws IllegalArgumentException when current is not within the range
     *         of of the number picker
     */
    public void setCurrent(int current) {
        if (current < mStart || current > mEnd) {
            throw new IllegalArgumentException(
                    "current should be >= start and <= end");
        }
        mCurrent = current;
        updateView();
    }

    /**
     * Sets the speed at which the numbers will scroll when the +/-
     * buttons are longpressed
     *
     * @param speed The speed (in milliseconds) at which the numbers will scroll
     * default 300ms
     */
    public void setSpeed(long speed) {
        mSpeed = speed;
    }

    private String formatNumber(int value) {
        return (mFormatter != null)
                ? mFormatter.toString(value)
                : String.valueOf(value);
    }

    /**
     * Sets the current value of this NumberPicker, and sets mPrevious to the previous
     * value.  If current is greater than mEnd less than mStart, the value of mCurrent
     * is wrapped around.
     *
     * Subclasses can override this to change the wrapping behavior
     *
     * @param current the new value of the NumberPicker
     */
    protected void changeCurrent(int current) {
        // Wrap around the values if we go past the start or end
        if (current > mEnd) {
            current = mStart;
        } else if (current < mStart) {
            current = mEnd;
        }
        mPrevious = mCurrent;
        mCurrent = current;
        notifyChange();
        updateView();
    }

    /**
     * Notifies the listener, if registered, of a change of the value of this
     * NumberPicker.
     */
    private void notifyChange() {
        if (mListener != null) {
            mListener.onChanged(this, mPrevious, mCurrent);
        }
    }

    /**
     * Updates the view of this NumberPicker.  If displayValues were specified
     * in {@link #setRange}, the string corresponding to the index specified by
     * the current value will be returned.  Otherwise, the formatter specified
     * in {@link setFormatter} will be used to format the number.
     */
    private void updateView() {
        /* If we don't have displayed values then use the
         * current number else find the correct value in the
         * displayed values for the current number.
         */
        if (mDisplayedValues == null) {
            mText.setText(formatNumber(mCurrent));
        } else {
            mText.setText(mDisplayedValues[mCurrent - mStart]);
        }
        mText.setSelection(mText.getText().length());
    }

    private void validateCurrentView(CharSequence str) {
        int val = getSelectedPos(str.toString());
        if ((val >= mStart) && (val <= mEnd)) {
            if (mCurrent != val) {
                mPrevious = mCurrent;
                mCurrent = val;
                notifyChange();
            }
        }
        updateView();
    }

    private void validateInput(View v) {
        String str = String.valueOf(((TextView) v).getText());
        if ("".equals(str)) {

            // Restore to the old value as we don't allow empty values
            updateView();
        } else {

            // Check the new value and ensure it's in range
            validateCurrentView(str);
        }
    }

    /**
     * @hide
     */
    public void cancelIncrement() {
        mIncrement = false;
    }

    /**
     * @hide
     */
    public void cancelDecrement() {
        mDecrement = false;
    }

    private static final char[] DIGIT_CHARACTERS = new char[] {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
    };

    private NumberPickerButton mIncrementButton;
    private NumberPickerButton mDecrementButton;

    private class NumberPickerInputFilter implements InputFilter {
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {
            if (mDisplayedValues == null) {
                return mNumberInputFilter.filter(source, start, end, dest, dstart, dend);
            }
            CharSequence filtered = String.valueOf(source.subSequence(start, end));
            String result = String.valueOf(dest.subSequence(0, dstart))
                    + filtered
                    + dest.subSequence(dend, dest.length());
            String str = String.valueOf(result).toLowerCase();
            for (String val : mDisplayedValues) {
                val = val.toLowerCase();
                if (val.startsWith(str)) {
                    return filtered;
                }
            }
            return "";
        }
    }

    private class NumberRangeKeyListener extends NumberKeyListener {

        // XXX This doesn't allow for range limits when controlled by a
        // soft input method!
        public int getInputType() {
            return InputType.TYPE_CLASS_NUMBER;
        }

        @Override
        protected char[] getAcceptedChars() {
            return DIGIT_CHARACTERS;
        }

        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                Spanned dest, int dstart, int dend) {

            CharSequence filtered = super.filter(source, start, end, dest, dstart, dend);
            if (filtered == null) {
                filtered = source.subSequence(start, end);
            }

            String result = String.valueOf(dest.subSequence(0, dstart))
                    + filtered
                    + dest.subSequence(dend, dest.length());

            if ("".equals(result)) {
                return result;
            }
            int val = getSelectedPos(result);

            /* Ensure the user can't type in a value greater
             * than the max allowed. We have to allow less than min
             * as the user might want to delete some numbers
             * and then type a new number.
             */
            if (val > mEnd) {
                return "";
            } else {
                return filtered;
            }
        }
    }

    private int getSelectedPos(String str) {
        if (mDisplayedValues == null) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                /* Ignore as if it's not a number we don't care */
            }
        } else {
            for (int i = 0; i < mDisplayedValues.length; i++) {
                /* Don't force the user to type in jan when ja will do */
                str = str.toLowerCase();
                if (mDisplayedValues[i].toLowerCase().startsWith(str)) {
                    return mStart + i;
                }
            }

            /* The user might have typed in a number into the month field i.e.
             * 10 instead of OCT so support that too.
             */
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {

                /* Ignore as if it's not a number we don't care */
            }
        }
        return mStart;
    }

    /**
     * Returns the current value of the NumberPicker
     * @return the current value.
     */
    public int getCurrent() {
        return mCurrent;
    }

    /**
     * Returns the upper value of the range of the NumberPicker
     * @return the uppper number of the range.
     */
    protected int getEndRange() {
        return mEnd;
    }

    /**
     * Returns the lower value of the range of the NumberPicker
     * @return the lower number of the range.
     */
    protected int getBeginRange() {
        return mStart;
    }
}
