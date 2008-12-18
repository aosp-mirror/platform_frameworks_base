package com.android.frameworktest.text;

import android.text.Spannable;
import android.text.SpannableStringBuilder;

public class SpannableStringBuilderTest extends SpannableTest {

    protected Spannable newSpannableWithText(String text) {
        return new SpannableStringBuilder(text);
    }
}
