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
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.GetChars;
import android.text.Selection;
import android.text.Spannable;

/**
 * TextViewTest tests {@link TextView}.
 */
public class TextViewTest extends ActivityInstrumentationTestCase2<TextViewActivity> {

    public TextViewTest() {
        super(TextViewActivity.class);
    }

    @SmallTest
    public void testArray() throws Exception {
        TextView tv = new TextView(getActivity());

        char[] c = new char[] { 'H', 'e', 'l', 'l', 'o', ' ',
                                'W', 'o', 'r', 'l', 'd', '!' };

        tv.setText(c, 1, 4);
        CharSequence oldText = tv.getText();

        tv.setText(c, 4, 5);
        CharSequence newText = tv.getText();

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
        final TextView tv = new TextView(getActivity());
        CharSequence originalText = "This is some text.";
        tv.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, tv.getText().toString());
        tv.setTextIsSelectable(true);
        Selection.setSelection((Spannable) tv.getText(), 0, tv.getText().length());

        // We need to run this in the UI thread, as it will create a Toast.
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                CharSequence newText = "Text is replaced.";
                Intent data = new Intent();
                data.putExtra(Intent.EXTRA_PROCESS_TEXT, newText);
                tv.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, data);
            }
        });
        getInstrumentation().waitForIdleSync();

        // This is a TextView, which can't be modified. Hence no change should have been made.
        assertEquals(originalText, tv.getText().toString());
    }

    @SmallTest
    public void testProcessTextActivityResultEditable() {
        EditText tv = new EditText(getActivity());
        CharSequence originalText = "This is some text.";
        tv.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, tv.getText().toString());
        tv.setTextIsSelectable(true);
        Selection.setSelection(tv.getText(), 0, tv.getText().length());

        CharSequence newText = "Text is replaced.";
        Intent data = new Intent();
        data.putExtra(Intent.EXTRA_PROCESS_TEXT, newText);
        tv.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, data);

        assertEquals(newText, tv.getText().toString());
    }

    @SmallTest
    public void testProcessTextActivityResultCancel() {
        EditText tv = new EditText(getActivity());
        CharSequence originalText = "This is some text.";
        tv.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, tv.getText().toString());
        tv.setTextIsSelectable(true);
        Selection.setSelection(tv.getText(), 0, tv.getText().length());

        CharSequence newText = "Text is replaced.";
        Intent data = new Intent();
        data.putExtra(Intent.EXTRA_PROCESS_TEXT, newText);
        tv.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_CANCELED, data);

        assertEquals(originalText, tv.getText().toString());
    }

    @SmallTest
    public void testProcessTextActivityNoData() {
        EditText tv = new EditText(getActivity());
        CharSequence originalText = "This is some text.";
        tv.setText(originalText, TextView.BufferType.SPANNABLE);
        assertEquals(originalText, tv.getText().toString());
        tv.setTextIsSelectable(true);
        Selection.setSelection(tv.getText(), 0, tv.getText().length());

        tv.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, null);

        assertEquals(originalText, tv.getText().toString());
    }
}
