/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.view.KeyEvent;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.text.method.KeyListener;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.View;
import android.graphics.Rect;



public class DialerFilter extends RelativeLayout
{
    public DialerFilter(Context context) {
        super(context);
    }

    public DialerFilter(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        // Setup the filter view
        mInputFilters = new InputFilter[] { new InputFilter.AllCaps() };

        mHint = (EditText) findViewById(com.android.internal.R.id.hint);
        if (mHint == null) {
            throw new IllegalStateException("DialerFilter must have a child EditText named hint");
        }
        mHint.setFilters(mInputFilters);

        mLetters = mHint;
        mLetters.setKeyListener(TextKeyListener.getInstance());
        mLetters.setMovementMethod(null);
        mLetters.setFocusable(false);

        // Setup the digits view
        mPrimary = (EditText) findViewById(com.android.internal.R.id.primary);
        if (mPrimary == null) {
            throw new IllegalStateException("DialerFilter must have a child EditText named primary");
        }
        mPrimary.setFilters(mInputFilters);

        mDigits = mPrimary;
        mDigits.setKeyListener(DialerKeyListener.getInstance());
        mDigits.setMovementMethod(null);
        mDigits.setFocusable(false);

        // Look for an icon
        mIcon = (ImageView) findViewById(com.android.internal.R.id.icon);

        // Setup focus & highlight for this view
        setFocusable(true);

        // Default the mode based on the keyboard
        KeyCharacterMap kmap
                = KeyCharacterMap.load(KeyCharacterMap.BUILT_IN_KEYBOARD);
        mIsQwerty = kmap.getKeyboardType() != KeyCharacterMap.NUMERIC;
        if (mIsQwerty) {
            Log.i("DialerFilter", "This device looks to be QWERTY");
//            setMode(DIGITS_AND_LETTERS);
        } else {
            Log.i("DialerFilter", "This device looks to be 12-KEY");
//            setMode(DIGITS_ONLY);
        }

        // XXX Force the mode to QWERTY for now, since 12-key isn't supported
        mIsQwerty = true;
        setMode(DIGITS_AND_LETTERS);
    }

    /**
     * Only show the icon view when focused, if there is one.
     */
    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);

        if (mIcon != null) {
            mIcon.setVisibility(focused ? View.VISIBLE : View.GONE);
        }
    }


    public boolean isQwertyKeyboard() {
        return mIsQwerty;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean handled = false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                break;

            case KeyEvent.KEYCODE_DEL:
                switch (mMode) {
                    case DIGITS_AND_LETTERS:
                        handled = mDigits.onKeyDown(keyCode, event);
                        handled &= mLetters.onKeyDown(keyCode, event);
                        break;

                    case DIGITS_AND_LETTERS_NO_DIGITS:
                        handled = mLetters.onKeyDown(keyCode, event);
                        if (mLetters.getText().length() == mDigits.getText().length()) {
                            setMode(DIGITS_AND_LETTERS);
                        }
                        break;

                    case DIGITS_AND_LETTERS_NO_LETTERS:
                        if (mDigits.getText().length() == mLetters.getText().length()) {
                            mLetters.onKeyDown(keyCode, event);
                            setMode(DIGITS_AND_LETTERS);
                        }
                        handled = mDigits.onKeyDown(keyCode, event);
                        break;

                    case DIGITS_ONLY:
                        handled = mDigits.onKeyDown(keyCode, event);
                        break;

                    case LETTERS_ONLY:
                        handled = mLetters.onKeyDown(keyCode, event);
                        break;
                }
                break;

            default:
                //mIsQwerty = msg.getKeyIsQwertyKeyboard();

                switch (mMode) {
                    case DIGITS_AND_LETTERS:
                        handled = mLetters.onKeyDown(keyCode, event);

                        // pass this throw so the shift state is correct (for example,
                        // on a standard QWERTY keyboard, * and 8 are on the same key)
                        if (KeyEvent.isModifierKey(keyCode)) {
                            mDigits.onKeyDown(keyCode, event);
                            handled = true;
                            break;
                        }

                        // Only check to see if the digit is valid if the key is a printing key
                        // in the TextKeyListener. This prevents us from hiding the digits
                        // line when keys like UP and DOWN are hit.
                        // XXX note that KEYCODE_TAB is special-cased here for 
                        // devices that share tab and 0 on a single key.
                        boolean isPrint = event.isPrintingKey();
                        if (isPrint || keyCode == KeyEvent.KEYCODE_SPACE
                                || keyCode == KeyEvent.KEYCODE_TAB) {
                            char c = event.getMatch(DialerKeyListener.CHARACTERS);
                            if (c != 0) {
                                handled &= mDigits.onKeyDown(keyCode, event);
                            } else {
                                setMode(DIGITS_AND_LETTERS_NO_DIGITS);
                            }
                        }
                        break;

                    case DIGITS_AND_LETTERS_NO_LETTERS:
                    case DIGITS_ONLY:
                        handled = mDigits.onKeyDown(keyCode, event);
                        break;

                    case DIGITS_AND_LETTERS_NO_DIGITS:
                    case LETTERS_ONLY:
                        handled = mLetters.onKeyDown(keyCode, event);
                        break;
                }
        }

        if (!handled) {
            return super.onKeyDown(keyCode, event);
        } else {
            return true;
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean a = mLetters.onKeyUp(keyCode, event);
        boolean b = mDigits.onKeyUp(keyCode, event);
        return a || b;
    }

    public int getMode() {
        return mMode;
    }

    /**
     * Change the mode of the widget.
     *
     * @param newMode The mode to switch to.
     */
    public void setMode(int newMode) {
        switch (newMode) {
            case DIGITS_AND_LETTERS:
                makeDigitsPrimary();
                mLetters.setVisibility(View.VISIBLE);
                mDigits.setVisibility(View.VISIBLE);
                break;

            case DIGITS_ONLY:
                makeDigitsPrimary();
                mLetters.setVisibility(View.GONE);
                mDigits.setVisibility(View.VISIBLE);
                break;

            case LETTERS_ONLY:
                makeLettersPrimary();
                mLetters.setVisibility(View.VISIBLE);
                mDigits.setVisibility(View.GONE);
                break;

            case DIGITS_AND_LETTERS_NO_LETTERS:
                makeDigitsPrimary();
                mLetters.setVisibility(View.INVISIBLE);
                mDigits.setVisibility(View.VISIBLE);
                break;

            case DIGITS_AND_LETTERS_NO_DIGITS:
                makeLettersPrimary();
                mLetters.setVisibility(View.VISIBLE);
                mDigits.setVisibility(View.INVISIBLE);
                break;

        }
        int oldMode = mMode;
        mMode = newMode;
        onModeChange(oldMode, newMode);
    }

    private void makeLettersPrimary() {
        if (mPrimary == mDigits) {
            swapPrimaryAndHint(true);
        }
    }

    private void makeDigitsPrimary() {
        if (mPrimary == mLetters) {
            swapPrimaryAndHint(false);
        }
    }

    private void swapPrimaryAndHint(boolean makeLettersPrimary) {
        Editable lettersText = mLetters.getText();
        Editable digitsText = mDigits.getText();
        KeyListener lettersInput = mLetters.getKeyListener();
        KeyListener digitsInput = mDigits.getKeyListener();

        if (makeLettersPrimary) {
            mLetters = mPrimary;
            mDigits = mHint;
        } else {
            mLetters = mHint;
            mDigits = mPrimary;
        }

        mLetters.setKeyListener(lettersInput);
        mLetters.setText(lettersText);
        lettersText = mLetters.getText();
        Selection.setSelection(lettersText, lettersText.length());

        mDigits.setKeyListener(digitsInput);
        mDigits.setText(digitsText);
        digitsText = mDigits.getText();
        Selection.setSelection(digitsText, digitsText.length());

        // Reset the filters
        mPrimary.setFilters(mInputFilters);
        mHint.setFilters(mInputFilters);
    }


    public CharSequence getLetters() {
        if (mLetters.getVisibility() == View.VISIBLE) {
            return mLetters.getText();
        } else {
            return "";
        }
    }

    public CharSequence getDigits() {
        if (mDigits.getVisibility() == View.VISIBLE) {
            return mDigits.getText();
        } else {
            return "";
        }
    }

    public CharSequence getFilterText() {
        if (mMode != DIGITS_ONLY) {
            return getLetters();
        } else {
            return getDigits();
        }
    }

    public void append(String text) {
        switch (mMode) {
            case DIGITS_AND_LETTERS:
                mDigits.getText().append(text);
                mLetters.getText().append(text);
                break;

            case DIGITS_AND_LETTERS_NO_LETTERS:
            case DIGITS_ONLY:
                mDigits.getText().append(text);
                break;

            case DIGITS_AND_LETTERS_NO_DIGITS:
            case LETTERS_ONLY:
                mLetters.getText().append(text);
                break;
        }
    }

    /**
     * Clears both the digits and the filter text.
     */
    public void clearText() {
        Editable text;

        text = mLetters.getText();
        text.clear();

        text = mDigits.getText();
        text.clear();

        // Reset the mode based on the hardware type
        if (mIsQwerty) {
            setMode(DIGITS_AND_LETTERS);
        } else {
            setMode(DIGITS_ONLY);
        }
    }

    public void setLettersWatcher(TextWatcher watcher) {
        CharSequence text = mLetters.getText();
        Spannable span = (Spannable)text;
        span.setSpan(watcher, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    public void setDigitsWatcher(TextWatcher watcher) {
        CharSequence text = mDigits.getText();
        Spannable span = (Spannable)text;
        span.setSpan(watcher, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    public void setFilterWatcher(TextWatcher watcher) {
        if (mMode != DIGITS_ONLY) {
            setLettersWatcher(watcher);
        } else {
            setDigitsWatcher(watcher);
        }
    }

    public void removeFilterWatcher(TextWatcher watcher) {
        Spannable text;
        if (mMode != DIGITS_ONLY) {
            text = mLetters.getText();
        } else {
            text = mDigits.getText();
        }
        text.removeSpan(watcher);
    }

    /**
     * Called right after the mode changes to give subclasses the option to
     * restyle, etc.
     */
    protected void onModeChange(int oldMode, int newMode) {
    }

    /** This mode has both lines */
    public static final int DIGITS_AND_LETTERS = 1;
    /** This mode is when after starting in {@link #DIGITS_AND_LETTERS} mode the filter
     *  has removed all possibility of the digits matching, leaving only the letters line */
    public static final int DIGITS_AND_LETTERS_NO_DIGITS = 2;
    /** This mode is when after starting in {@link #DIGITS_AND_LETTERS} mode the filter
     *  has removed all possibility of the letters matching, leaving only the digits line */
    public static final int DIGITS_AND_LETTERS_NO_LETTERS = 3;
    /** This mode has only the digits line */
    public static final int DIGITS_ONLY = 4;
    /** This mode has only the letters line */
    public static final int LETTERS_ONLY = 5;

    EditText mLetters;
    EditText mDigits;
    EditText mPrimary;
    EditText mHint;
    InputFilter mInputFilters[];
    ImageView mIcon;
    int mMode;
    private boolean mIsQwerty;
}
