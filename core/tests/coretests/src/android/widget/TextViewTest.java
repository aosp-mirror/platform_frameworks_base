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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.text.GetChars;
import android.text.Layout;
import android.text.PrecomputedText;
import android.text.method.OffsetMapping;
import android.text.method.TransformationMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView.BufferType;

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Locale;

/**
 * TextViewTest tests {@link TextView}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class TextViewTest {
    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);
    private Instrumentation mInstrumentation;
    private Activity mActivity;
    private TextView mTextView;

    @Before
    public void setup() {
        mActivity = mActivityRule.getActivity();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @Presubmit
    @UiThreadTest
    @Test
    public void testArray() {
        mTextView = new TextView(mActivity);

        char[] c = new char[] { 'H', 'e', 'l', 'l', 'o', ' ',
                                'W', 'o', 'r', 'l', 'd', '!' };

        mTextView.setText(c, 1, 4);
        CharSequence oldText = mTextView.getText();

        mTextView.setText(c, 4, 5);
        CharSequence newText = mTextView.getText();

        assertTrue(newText == oldText);

        assertEquals(5, newText.length());
        assertEquals('o', newText.charAt(0));
        assertEquals("o Wor", newText.toString());

        assertEquals(" Wo", newText.subSequence(1, 4));

        char[] c2 = new char[7];
        ((GetChars) newText).getChars(1, 4, c2, 2);
        assertEquals('\0', c2[1]);
        assertEquals(' ', c2[2]);
        assertEquals('W', c2[3]);
        assertEquals('o', c2[4]);
        assertEquals('\0', c2[5]);
    }

    @Test
    @UiThreadTest
    public void testHyphenationWidth() {
        mTextView = new TextView(mActivity);
        mTextView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL);
        mTextView.setTextLocale(Locale.US);

        Paint paint = mTextView.getPaint();

        String word = "thisissuperlonglongword";
        float wordWidth = paint.measureText(word, 0, word.length());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; ++i) {
            sb.append(word);
            sb.append(" ");
        }
        mTextView.setText(sb.toString());

        int width = (int)(wordWidth * 0.7);
        int height = 4096;  // enough for all text.

        mTextView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        mTextView.layout(0, 0, width, height);

        Layout layout = mTextView.getLayout();
        assertNotNull(layout);

        int lineCount = layout.getLineCount();
        boolean hyphenationHappend = false;
        for (int i = 0; i < lineCount; ++i) {
            if (layout.getStartHyphenEdit(i) == Paint.START_HYPHEN_EDIT_NO_EDIT
                    && layout.getEndHyphenEdit(i) == Paint.END_HYPHEN_EDIT_NO_EDIT) {
                continue;  // Hyphantion does not happen.
            }
            hyphenationHappend = true;

            int start = layout.getLineStart(i);
            int end = layout.getLineEnd(i);

            float withoutHyphenLength = paint.measureText(sb, start, end);
            float withHyphenLength = layout.getLineWidth(i);

            assertTrue("LineWidth should take account of hyphen length.",
                    withHyphenLength > withoutHyphenLength);
        }
        assertTrue("Hyphenation must happen on TextView narrower than the word width",
                hyphenationHappend);
    }

    @Test
    @UiThreadTest
    public void testCopyShouldNotThrowException() throws Throwable {
        mTextView = new TextView(mActivity);
        mTextView.setTextIsSelectable(true);
        mTextView.setText(createLongText());
        mTextView.onTextContextMenuItem(TextView.ID_SELECT_ALL);
        mTextView.onTextContextMenuItem(TextView.ID_COPY);
    }

    @Test
    @UiThreadTest
    public void testCutShouldNotThrowException() throws Throwable {
        mTextView = new TextView(mActivity);
        mTextView.setTextIsSelectable(true);
        mTextView.setText(createLongText());
        mTextView.onTextContextMenuItem(TextView.ID_SELECT_ALL);
        mTextView.onTextContextMenuItem(TextView.ID_CUT);
    }

    @Test
    public void testUseDynamicLayout() {
        mTextView = new TextView(mActivity);
        mTextView.setTextIsSelectable(true);
        String text = "HelloWorld";
        PrecomputedText precomputed =
                PrecomputedText.create(text, mTextView.getTextMetricsParams());

        mTextView.setTextIsSelectable(false);
        mTextView.setText(text);
        assertFalse(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(true);
        mTextView.setText(text);
        assertTrue(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(false);
        mTextView.setText(precomputed);
        assertFalse(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(true);
        mTextView.setText(precomputed);
        assertTrue(mTextView.useDynamicLayout());
    }

    @Test
    public void testUseDynamicLayout_SPANNABLE() {
        mTextView = new TextView(mActivity);
        mTextView.setTextIsSelectable(true);
        String text = "HelloWorld";
        PrecomputedText precomputed =
                PrecomputedText.create(text, mTextView.getTextMetricsParams());

        mTextView.setTextIsSelectable(false);
        mTextView.setText(text, BufferType.SPANNABLE);
        android.util.Log.e("TextViewTest", "Text:" + mTextView.getText().getClass().getName());
        assertTrue(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(true);
        mTextView.setText(text, BufferType.SPANNABLE);
        assertTrue(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(false);
        mTextView.setText(precomputed, BufferType.SPANNABLE);
        assertFalse(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(true);
        mTextView.setText(precomputed, BufferType.SPANNABLE);
        assertTrue(mTextView.useDynamicLayout());
    }

    @Test
    public void testUseDynamicLayout_EDITABLE() {
        mTextView = new TextView(mActivity);
        mTextView.setTextIsSelectable(true);
        String text = "HelloWorld";
        PrecomputedText precomputed =
                PrecomputedText.create(text, mTextView.getTextMetricsParams());

        mTextView.setTextIsSelectable(false);
        mTextView.setText(text, BufferType.EDITABLE);
        assertTrue(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(true);
        mTextView.setText(text, BufferType.EDITABLE);
        assertTrue(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(false);
        mTextView.setText(precomputed, BufferType.EDITABLE);
        assertTrue(mTextView.useDynamicLayout());

        mTextView.setTextIsSelectable(true);
        mTextView.setText(precomputed, BufferType.EDITABLE);
        assertTrue(mTextView.useDynamicLayout());
    }

    @Test
    @UiThreadTest
    public void testConstructor_doesNotLeaveTextNull() {
        mTextView = new NullSetTextTextView(mActivity);
        // Check that mText and mTransformed are empty string instead of null.
        assertEquals("", mTextView.getText().toString());
        assertEquals("", mTextView.getTransformed().toString());
    }

    @Test
    @UiThreadTest
    public void testPortraitDoesntSupportFullscreenIme() {
        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);
        mTextView = new NullSetTextTextView(mActivity);
        mTextView.requestFocus();
        assertEquals("IME_FLAG_NO_FULLSCREEN should be set",
                mTextView.getImeOptions(),
                mTextView.getImeOptions() & EditorInfo.IME_FLAG_NO_FULLSCREEN);

        mTextView.clearFocus();
        mActivity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        mTextView = new NullSetTextTextView(mActivity);
        mTextView.requestFocus();
        assertEquals("IME_FLAG_NO_FULLSCREEN should not be set",
                0, mTextView.getImeOptions() & EditorInfo.IME_FLAG_NO_FULLSCREEN);
    }

    @Test
    @UiThreadTest
    public void setSetImeConsumesInput_recoveryToVisible() {
        mTextView = new TextView(mActivity);
        mTextView.setCursorVisible(true);
        assertTrue(mTextView.isCursorVisible());

        mTextView.setImeConsumesInput(true);
        assertFalse(mTextView.isCursorVisible());

        mTextView.setImeConsumesInput(false);
        assertTrue(mTextView.isCursorVisible());
    }

    @Test
    @UiThreadTest
    public void setSetImeConsumesInput_recoveryToInvisible() {
        mTextView = new TextView(mActivity);
        mTextView.setCursorVisible(false);
        assertFalse(mTextView.isCursorVisible());

        mTextView.setImeConsumesInput(true);
        assertFalse(mTextView.isCursorVisible());

        mTextView.setImeConsumesInput(false);
        assertFalse(mTextView.isCursorVisible());
    }

    @Test(expected = NullPointerException.class)
    @UiThreadTest
    public void setTextCharArrayNullThrows() {
        mTextView = new TextView(mActivity);
        mTextView.setText((char[]) null, 0, 0);
    }

    @Test
    @UiThreadTest
    public void setTextCharArrayValidAfterSetTextString() {
        mTextView = new TextView(mActivity);
        mTextView.setText(new char[] { 'h', 'i'}, 0, 2);
        CharSequence charWrapper = mTextView.getText();
        mTextView.setText("null out char wrapper");
        assertEquals("hi", charWrapper.toString());
    }

    @Test
    @UiThreadTest
    public void transformedToOriginal_noOffsetMapping() {
        mTextView = new TextView(mActivity);
        final String text = "Hello world";
        mTextView.setText(text);
        for (int offset = 0; offset < text.length(); ++offset) {
            assertThat(mTextView.transformedToOriginal(offset, OffsetMapping.MAP_STRATEGY_CURSOR))
                    .isEqualTo(offset);
            assertThat(mTextView.transformedToOriginal(offset,
                    OffsetMapping.MAP_STRATEGY_CHARACTER)).isEqualTo(offset);
        }
    }

    @Test
    @UiThreadTest
    public void originalToTransformed_noOffsetMapping() {
        mTextView = new TextView(mActivity);
        final String text = "Hello world";
        mTextView.setText(text);
        for (int offset = 0; offset < text.length(); ++offset) {
            assertThat(mTextView.originalToTransformed(offset, OffsetMapping.MAP_STRATEGY_CURSOR))
                    .isEqualTo(offset);
            assertThat(mTextView.originalToTransformed(offset,
                    OffsetMapping.MAP_STRATEGY_CHARACTER)).isEqualTo(offset);
        }
    }

    @Test
    @UiThreadTest
    public void originalToTransformed_hasOffsetMapping() {
        mTextView = new TextView(mActivity);
        final CharSequence text = "Hello world";
        final TransformedText transformedText = mock(TransformedText.class);
        when(transformedText.originalToTransformed(anyInt(), anyInt())).then((invocation) -> {
            // plus 1 for character strategy and minus 1 for cursor strategy.
            if ((int) invocation.getArgument(1) == OffsetMapping.MAP_STRATEGY_CHARACTER) {
                return (int) invocation.getArgument(0) + 1;
            }
            return (int) invocation.getArgument(0) - 1;
        });

        final TransformationMethod transformationMethod =
                new TestTransformationMethod(transformedText);
        mTextView.setText(text);
        mTextView.setTransformationMethod(transformationMethod);

        assertThat(mTextView.originalToTransformed(1, OffsetMapping.MAP_STRATEGY_CHARACTER))
                .isEqualTo(2);
        verify(transformedText, times(1))
                .originalToTransformed(1, OffsetMapping.MAP_STRATEGY_CHARACTER);

        assertThat(mTextView.originalToTransformed(1, OffsetMapping.MAP_STRATEGY_CURSOR))
                .isEqualTo(0);
        verify(transformedText, times(1))
                .originalToTransformed(1, OffsetMapping.MAP_STRATEGY_CURSOR);
    }

    @Test
    @UiThreadTest
    public void transformedToOriginal_hasOffsetMapping() {
        mTextView = new TextView(mActivity);
        final CharSequence text = "Hello world";
        final TransformedText transformedText = mock(TransformedText.class);
        when(transformedText.transformedToOriginal(anyInt(), anyInt())).then((invocation) -> {
            // plus 1 for character strategy and minus 1 for cursor strategy.
            if ((int) invocation.getArgument(1) == OffsetMapping.MAP_STRATEGY_CHARACTER) {
                return (int) invocation.getArgument(0) + 1;
            }
            return (int) invocation.getArgument(0) - 1;
        });

        final TransformationMethod transformationMethod =
                new TestTransformationMethod(transformedText);
        mTextView.setText(text);
        mTextView.setTransformationMethod(transformationMethod);

        assertThat(mTextView.transformedToOriginal(1, OffsetMapping.MAP_STRATEGY_CHARACTER))
                .isEqualTo(2);
        verify(transformedText, times(1))
                .transformedToOriginal(1, OffsetMapping.MAP_STRATEGY_CHARACTER);

        assertThat(mTextView.transformedToOriginal(1, OffsetMapping.MAP_STRATEGY_CURSOR))
                .isEqualTo(0);
        verify(transformedText, times(1))
                .transformedToOriginal(1, OffsetMapping.MAP_STRATEGY_CURSOR);
    }

    private String createLongText() {
        int size = 600 * 1000;
        final StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append('a');
        }
        return builder.toString();
    }

    private class NullSetTextTextView extends TextView {
        NullSetTextTextView(Context context) {
            super(context);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            // #setText will be called from the TextView constructor. Here we reproduce
            // the situation when the method sets mText and mTransformed to null.
            try {
                final Field textField = TextView.class.getDeclaredField("mText");
                textField.setAccessible(true);
                textField.set(this, null);
                final Field transformedField = TextView.class.getDeclaredField("mTransformed");
                transformedField.setAccessible(true);
                transformedField.set(this, null);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // Empty.
            }
        }
    }

    private interface TransformedText extends OffsetMapping, CharSequence { }

    private static class TestTransformationMethod implements TransformationMethod {
        private final CharSequence mTransformedText;

        TestTransformationMethod(CharSequence transformedText) {
            this.mTransformedText = transformedText;
        }

        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            return mTransformedText;
        }

        @Override
        public void onFocusChanged(View view, CharSequence sourceText, boolean focused,
                int direction, Rect previouslyFocusedRect) {
        }
    }
}
