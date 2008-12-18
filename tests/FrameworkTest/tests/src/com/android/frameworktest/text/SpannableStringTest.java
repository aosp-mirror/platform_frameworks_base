package com.android.frameworktest.text;

import android.text.Spannable;
import android.text.SpannableString;

public class SpannableStringTest extends SpannableTest {

    protected Spannable newSpannableWithText(String text) {
        return new SpannableString(text);
    }
}
