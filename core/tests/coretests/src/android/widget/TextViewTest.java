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

import android.app.Activity;
import android.content.Intent;
import android.graphics.Paint;
import android.platform.test.annotations.Presubmit;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.GetChars;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.util.Log;
import android.view.View;
import java.util.Locale;

/**
 * TextViewTest tests {@link TextView}.
 */
public class TextViewTest extends ActivityInstrumentationTestCase2<TextViewActivity> {
    private static final String TAG = "TextViewTest";
    private TextView mTextView;

    public TextViewTest() {
        super(TextViewActivity.class);
    }

    @SmallTest
    @Presubmit
    public void testArray() throws Exception {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView = new TextView(getActivity());
            }
        });
        getInstrumentation().waitForIdleSync();

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

    @SmallTest
    public void testProcessTextActivityResultNonEditable() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView = new TextView(getActivity());
            }
        });
        getInstrumentation().waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection((Spannable) mTextView.getText(), 0, mTextView.getText().length());

        // We need to run this in the UI thread, as it will create a Toast.
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                CharSequence newText = "Text is replaced.";
                Intent data = new Intent();
                data.putExtra(Intent.EXTRA_PROCESS_TEXT, newText);
                mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, data);
            }
        });
        getInstrumentation().waitForIdleSync();

        // This is a TextView, which can't be modified. Hence no change should have been made.
        assertEquals(originalText, mTextView.getText().toString());
    }

    @SmallTest
    public void testProcessTextActivityResultEditable() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView = new EditText(getActivity());
            }
        });
        getInstrumentation().waitForIdleSync();
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

    @SmallTest
    public void testProcessTextActivityResultCancel() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView = new EditText(getActivity());
            }
        });
        getInstrumentation().waitForIdleSync();
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

    @SmallTest
    public void testProcessTextActivityNoData() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView = new EditText(getActivity());
            }
        });
        getInstrumentation().waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection(((EditText) mTextView).getText(), 0, mTextView.getText().length());

        mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, null);

        assertEquals(originalText, mTextView.getText().toString());
    }

    @SmallTest
    public void testHyphenationWidth() {
        TextView textView = new TextView(getActivity());
        textView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_FULL);
        textView.setTextLocale(Locale.US);

        Paint paint = textView.getPaint();

        String word = "thisissuperlonglongword";
        float wordWidth = paint.measureText(word, 0, word.length());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; ++i) {
            sb.append(word);
            sb.append(" ");
        }
        textView.setText(sb.toString());

        int width = (int)(wordWidth * 0.7);
        int height = 4096;  // enough for all text.

        textView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        textView.layout(0, 0, width, height);

        Layout layout = textView.getLayout();
        assertNotNull(layout);

        int lineCount = layout.getLineCount();
        boolean hyphenationHappend = false;
        for (int i = 0; i < lineCount; ++i) {
            if (layout.getHyphen(i) != 1) {
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
}
