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
package android.databinding.testapp;

import android.databinding.testapp.databinding.TextViewAdapterTestBinding;
import android.databinding.testapp.vo.TextViewBindingObject;

import android.annotation.TargetApi;
import android.databinding.adapters.TextViewBindingAdapter;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spannable;
import android.text.method.DialerKeyListener;
import android.text.method.DigitsKeyListener;
import android.text.method.KeyListener;
import android.text.method.TextKeyListener;
import android.widget.TextView;

public class TextViewBindingAdapterTest
        extends BindingAdapterTestBase<TextViewAdapterTestBinding, TextViewBindingObject> {

    public TextViewBindingAdapterTest() {
        super(TextViewAdapterTestBinding.class, TextViewBindingObject.class,
                R.layout.text_view_adapter_test);
    }

    public void testNumeric() throws Throwable {
        TextView view = mBinder.numericText;
        assertTrue(view.getKeyListener() instanceof DigitsKeyListener);
        DigitsKeyListener listener = (DigitsKeyListener) view.getKeyListener();
        assertEquals(getExpectedNumericType(), listener.getInputType());

        changeValues();

        assertTrue(view.getKeyListener() instanceof DigitsKeyListener);
        listener = (DigitsKeyListener) view.getKeyListener();
        assertEquals(getExpectedNumericType(), listener.getInputType());
    }

    private int getExpectedNumericType() {
        int expectedType = InputType.TYPE_CLASS_NUMBER;
        if ((mBindingObject.getNumeric() & TextViewBindingAdapter.SIGNED) != 0) {
            expectedType |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        }
        if ((mBindingObject.getNumeric() & TextViewBindingAdapter.DECIMAL) != 0) {
            expectedType |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
        }
        return expectedType;
    }

    public void testDrawables() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            TextView view = mBinder.textDrawableNormal;
            assertEquals(mBindingObject.getDrawableLeft(),
                    ((ColorDrawable) view.getCompoundDrawables()[0]).getColor());
            assertEquals(mBindingObject.getDrawableTop(),
                    ((ColorDrawable) view.getCompoundDrawables()[1]).getColor());
            assertEquals(mBindingObject.getDrawableRight(),
                    ((ColorDrawable) view.getCompoundDrawables()[2]).getColor());
            assertEquals(mBindingObject.getDrawableBottom(),
                    ((ColorDrawable) view.getCompoundDrawables()[3]).getColor());

            changeValues();

            assertEquals(mBindingObject.getDrawableLeft(),
                    ((ColorDrawable) view.getCompoundDrawables()[0]).getColor());
            assertEquals(mBindingObject.getDrawableTop(),
                    ((ColorDrawable) view.getCompoundDrawables()[1]).getColor());
            assertEquals(mBindingObject.getDrawableRight(),
                    ((ColorDrawable) view.getCompoundDrawables()[2]).getColor());
            assertEquals(mBindingObject.getDrawableBottom(),
                    ((ColorDrawable) view.getCompoundDrawables()[3]).getColor());
        }
    }

    public void testDrawableStartEnd() throws Throwable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            TextView view = mBinder.textDrawableStartEnd;
            assertEquals(mBindingObject.getDrawableStart(),
                    ((ColorDrawable) view.getCompoundDrawablesRelative()[0]).getColor());
            assertEquals(mBindingObject.getDrawableEnd(),
                    ((ColorDrawable) view.getCompoundDrawablesRelative()[2]).getColor());

            changeValues();

            assertEquals(mBindingObject.getDrawableStart(),
                    ((ColorDrawable) view.getCompoundDrawablesRelative()[0]).getColor());
            assertEquals(mBindingObject.getDrawableEnd(),
                    ((ColorDrawable) view.getCompoundDrawablesRelative()[2]).getColor());
        }
    }

    public void testSimpleProperties() throws Throwable {
        TextView view = mBinder.textView;

        assertEquals(mBindingObject.getAutoLink(), view.getAutoLinkMask());
        assertEquals(mBindingObject.getDrawablePadding(), view.getCompoundDrawablePadding());
        assertEquals(mBindingObject.getTextSize(), view.getTextSize());
        assertEquals(mBindingObject.getTextColorHint(), view.getHintTextColors().getDefaultColor());
        assertEquals(mBindingObject.getTextColorLink(), view.getLinkTextColors().getDefaultColor());
        assertEquals(mBindingObject.isAutoText(), isAutoTextEnabled(view));
        assertEquals(mBindingObject.getCapitalize(), getCapitalization(view));
        assertEquals(mBindingObject.getImeActionLabel(), view.getImeActionLabel());
        assertEquals(mBindingObject.getImeActionId(), view.getImeActionId());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            assertEquals(mBindingObject.getTextColorHighlight(), view.getHighlightColor());
            assertEquals(mBindingObject.getLineSpacingExtra(), view.getLineSpacingExtra());
            assertEquals(mBindingObject.getLineSpacingMultiplier(),
                    view.getLineSpacingMultiplier());
            assertEquals(mBindingObject.getShadowColor(), view.getShadowColor());
            assertEquals(mBindingObject.getShadowDx(), view.getShadowDx());
            assertEquals(mBindingObject.getShadowDy(), view.getShadowDy());
            assertEquals(mBindingObject.getShadowRadius(), view.getShadowRadius());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                assertEquals(mBindingObject.getMaxLength(), getMaxLength(view));
            }
        }

        changeValues();

        assertEquals(mBindingObject.getAutoLink(), view.getAutoLinkMask());
        assertEquals(mBindingObject.getDrawablePadding(), view.getCompoundDrawablePadding());
        assertEquals(mBindingObject.getTextSize(), view.getTextSize());
        assertEquals(mBindingObject.getTextColorHint(), view.getHintTextColors().getDefaultColor());
        assertEquals(mBindingObject.getTextColorLink(), view.getLinkTextColors().getDefaultColor());
        assertEquals(mBindingObject.isAutoText(), isAutoTextEnabled(view));
        assertEquals(mBindingObject.getCapitalize(), getCapitalization(view));
        assertEquals(mBindingObject.getImeActionLabel(), view.getImeActionLabel());
        assertEquals(mBindingObject.getImeActionId(), view.getImeActionId());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            assertEquals(mBindingObject.getTextColorHighlight(), view.getHighlightColor());
            assertEquals(mBindingObject.getLineSpacingExtra(), view.getLineSpacingExtra());
            assertEquals(mBindingObject.getLineSpacingMultiplier(),
                    view.getLineSpacingMultiplier());
            assertEquals(mBindingObject.getShadowColor(), view.getShadowColor());
            assertEquals(mBindingObject.getShadowDx(), view.getShadowDx());
            assertEquals(mBindingObject.getShadowDy(), view.getShadowDy());
            assertEquals(mBindingObject.getShadowRadius(), view.getShadowRadius());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                assertEquals(mBindingObject.getMaxLength(), getMaxLength(view));
            }
        }

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBindingObject.setCapitalize(TextKeyListener.Capitalize.CHARACTERS);
                mBinder.executePendingBindings();
            }
        });

        assertEquals(mBindingObject.getCapitalize(), getCapitalization(view));

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBindingObject.setCapitalize(TextKeyListener.Capitalize.WORDS);
                mBinder.executePendingBindings();
            }
        });

        assertEquals(mBindingObject.getCapitalize(), getCapitalization(view));
    }

    private static boolean isAutoTextEnabled(TextView view) {
        KeyListener keyListener = view.getKeyListener();
        if (keyListener == null) {
            return false;
        }
        if (!(keyListener instanceof TextKeyListener)) {
            return false;
        }
        TextKeyListener textKeyListener = (TextKeyListener) keyListener;
        return ((textKeyListener.getInputType() & InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) != 0);
    }

    private static TextKeyListener.Capitalize getCapitalization(TextView view) {
        KeyListener keyListener = view.getKeyListener();
        if (keyListener == null) {
            return TextKeyListener.Capitalize.NONE;
        }
        int inputType = keyListener.getInputType();
        if ((inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0) {
            return TextKeyListener.Capitalize.CHARACTERS;
        } else if ((inputType & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0) {
            return TextKeyListener.Capitalize.WORDS;
        } else if ((inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0) {
            return TextKeyListener.Capitalize.SENTENCES;
        } else {
            return TextKeyListener.Capitalize.NONE;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static int getMaxLength(TextView view) {
        InputFilter[] filters = view.getFilters();
        for (InputFilter filter : filters) {
            if (filter instanceof InputFilter.LengthFilter) {
                InputFilter.LengthFilter lengthFilter = (InputFilter.LengthFilter) filter;
                return lengthFilter.getMax();
            }
        }
        return -1;
    }

    public void testAllCaps() throws Throwable {
        TextView view = mBinder.textAllCaps;

        assertEquals(mBindingObject.isTextAllCaps(), view.getTransformationMethod() != null);
        if (view.getTransformationMethod() != null) {
            assertEquals("ALL CAPS",
                    view.getTransformationMethod().getTransformation("all caps", view));
        }

        changeValues();

        assertEquals(mBindingObject.isTextAllCaps(), view.getTransformationMethod() != null);
        if (view.getTransformationMethod() != null) {
            assertEquals("ALL CAPS",
                    view.getTransformationMethod().getTransformation("all caps", view));
        }
    }

    public void testBufferType() throws Throwable {
        TextView view = mBinder.textBufferType;

        assertEquals(mBindingObject.getBufferType(), getBufferType(view));
        changeValues();
        assertEquals(mBindingObject.getBufferType(), getBufferType(view));
    }

    private static TextView.BufferType getBufferType(TextView view) {
        CharSequence text = view.getText();
        if (text instanceof Editable) {
            return TextView.BufferType.EDITABLE;
        }
        if (text instanceof Spannable) {
            return TextView.BufferType.SPANNABLE;
        }
        return TextView.BufferType.NORMAL;
    }

    public void testInputType() throws Throwable {
        TextView view = mBinder.textInputType;
        assertEquals(mBindingObject.getInputType(), view.getInputType());
        changeValues();
        assertEquals(mBindingObject.getInputType(), view.getInputType());
    }

    public void testDigits() throws Throwable {
        TextView view = mBinder.textDigits;
        assertEquals(mBindingObject.getDigits(), getDigits(view));
        changeValues();
        assertEquals(mBindingObject.getDigits(), getDigits(view));
    }

    private static String getDigits(TextView textView) {
        KeyListener keyListener = textView.getKeyListener();
        if (!(keyListener instanceof DigitsKeyListener)) {
            return null;
        }
        DigitsKeyListener digitsKeyListener = (DigitsKeyListener) keyListener;
        String input = "abcdefghijklmnopqrstuvwxyz";
        Spannable spannable = Spannable.Factory.getInstance().newSpannable(input);
        return digitsKeyListener.filter(input, 0, input.length(), spannable, 0, input.length())
                .toString();
    }

    public void testPhoneNumber() throws Throwable {
        TextView textView = mBinder.textPhoneNumber;
        assertEquals(mBindingObject.isPhoneNumber(), isPhoneNumber(textView));
        changeValues();
        assertEquals(mBindingObject.isPhoneNumber(), isPhoneNumber(textView));
    }

    private static boolean isPhoneNumber(TextView view) {
        KeyListener keyListener = view.getKeyListener();
        return (keyListener instanceof DialerKeyListener);
    }

    public void testInputMethod() throws Throwable {
        TextView textView = mBinder.textInputMethod;
        assertTrue(TextViewBindingObject.KeyListener1.class.isInstance(textView.getKeyListener()));
        changeValues();
        assertTrue(TextViewBindingObject.KeyListener2.class.isInstance(textView.getKeyListener()));
    }

}
