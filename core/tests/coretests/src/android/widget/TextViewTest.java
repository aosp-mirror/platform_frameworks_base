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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Paint;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.MediumTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.text.GetChars;
import android.text.Layout;
import android.text.PrecomputedText;
import android.text.Selection;
import android.text.Spannable;
import android.view.View;
import android.widget.TextView.BufferType;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

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
    public void testProcessTextActivityResultNonEditable() throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new TextView(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection((Spannable) mTextView.getText(), 0, mTextView.getText().length());

        // We need to run this in the UI thread, as it will create a Toast.
        mActivityRule.runOnUiThread(() -> {
            CharSequence newText = "Text is replaced.";
            Intent data = new Intent();
            data.putExtra(Intent.EXTRA_PROCESS_TEXT, newText);
            mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK,
                    data);
        });
        mInstrumentation.waitForIdleSync();

        // This is a TextView, which can't be modified. Hence no change should have been made.
        assertEquals(originalText, mTextView.getText().toString());
    }

    @Test
    public void testProcessTextActivityResultEditable() throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new EditText(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection(((EditText) mTextView).getText(), 0, mTextView.getText().length());

        CharSequence newText = "Text is replaced.";
        Intent data = new Intent();
        data.putExtra(Intent.EXTRA_PROCESS_TEXT, newText);
        mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, data);

        assertEquals(newText, mTextView.getText().toString());
    }

    @Test
    public void testProcessTextActivityResultCancel() throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new EditText(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection(((EditText) mTextView).getText(), 0, mTextView.getText().length());

        CharSequence newText = "Text is replaced.";
        Intent data = new Intent();
        data.putExtra(Intent.EXTRA_PROCESS_TEXT, newText);
        mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_CANCELED,
                data);

        assertEquals(originalText, mTextView.getText().toString());
    }

    @Test
    public void testProcessTextActivityNoData() throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new EditText(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection(((EditText) mTextView).getText(), 0, mTextView.getText().length());

        mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, null);

        assertEquals(originalText, mTextView.getText().toString());
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
            if (layout.getHyphen(i) == 0) {
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

    private String createLongText() {
        int size = 600 * 1000;
        final StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append('a');
        }
        return builder.toString();
    }
}
