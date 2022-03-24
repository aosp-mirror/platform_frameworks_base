/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.accessibility;

import static junit.framework.Assert.assertEquals;

import android.text.ParcelableSpan;
import android.text.SpannableString;
import android.text.style.LocaleSpan;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.accessibility.util.AccessibilityUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

/**
 * Unit tests for AccessibilityUtils.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityUtilsTest {
    @Test
    public void textOrSpanChanged_stringChange_returnTextChange() {
        final CharSequence beforeText = "a";

        final CharSequence afterText = "b";

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertEquals(AccessibilityUtils.TEXT, type);
    }

    @Test
    public void textOrSpanChanged_stringNotChange_returnNoneChange() {
        final CharSequence beforeText = "a";

        final CharSequence afterText = "a";

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertEquals(AccessibilityUtils.NONE, type);
    }

    @Test
    public void textOrSpanChanged_nonSpanToNonParcelableSpan_returnNoneChange() {
        final Object nonParcelableSpan = new Object();
        final CharSequence beforeText = new SpannableString("a");

        final SpannableString afterText = new SpannableString("a");
        afterText.setSpan(nonParcelableSpan, 0, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertEquals(AccessibilityUtils.NONE, type);
    }

    @Test
    public void textOrSpanChanged_nonSpanToParcelableSpan_returnParcelableSpanChange() {
        final ParcelableSpan parcelableSpan = new LocaleSpan(Locale.ENGLISH);
        final CharSequence beforeText = new SpannableString("a");

        final SpannableString afterText = new SpannableString("a");
        afterText.setSpan(parcelableSpan, 0, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertEquals(AccessibilityUtils.PARCELABLE_SPAN, type);
    }

    @Test
    public void textOrSpanChanged_nonParcelableSpanToParcelableSpan_returnParcelableSpanChange() {
        final Object nonParcelableSpan = new Object();
        final ParcelableSpan parcelableSpan = new LocaleSpan(Locale.ENGLISH);
        final SpannableString beforeText = new SpannableString("a");
        beforeText.setSpan(nonParcelableSpan, 0, 1, 0);

        SpannableString afterText = new SpannableString("a");
        afterText.setSpan(parcelableSpan, 0, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertEquals(AccessibilityUtils.PARCELABLE_SPAN, type);
    }

    @Test
    public void textOrSpanChanged_nonParcelableSpanChange_returnNoneChange() {
        final Object nonParcelableSpan = new Object();
        final SpannableString beforeText = new SpannableString("a");
        beforeText.setSpan(nonParcelableSpan, 0, 1, 0);

        final SpannableString afterText = new SpannableString("a");
        afterText.setSpan(nonParcelableSpan, 1, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertEquals(AccessibilityUtils.NONE, type);
    }

    @Test
    public void textOrSpanChanged_parcelableSpanChange_returnParcelableSpanChange() {
        final ParcelableSpan parcelableSpan = new LocaleSpan(Locale.ENGLISH);
        final SpannableString beforeText = new SpannableString("a");
        beforeText.setSpan(parcelableSpan, 0, 1, 0);

        final SpannableString afterText = new SpannableString("a");
        afterText.setSpan(parcelableSpan, 1, 1, 0);

        @AccessibilityUtils.A11yTextChangeType int type = AccessibilityUtils.textOrSpanChanged(
                beforeText, afterText);
        assertEquals(AccessibilityUtils.PARCELABLE_SPAN, type);
    }
}
