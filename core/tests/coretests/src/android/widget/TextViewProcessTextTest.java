/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.text.Selection;
import android.text.Spannable;
import android.widget.TextView.BufferType;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link Intent#ACTION_PROCESS_TEXT} functionality in {@link TextView}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class TextViewProcessTextTest {
    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);
    private Instrumentation mInstrumentation;
    private Activity mActivity;
    private TextView mTextView;

    @Before
    public void before() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.getActivity();
    }

    @Test
    public void testProcessTextActivityResultNonEditable() throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new TextView(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection((Spannable) mTextView.getText(), 0, mTextView.getText().length());

        // We need to run this in the UI thread, as it will create a Toast.
        mActivityRule.runOnUiThread(() -> {
            triggerOnActivityResult(Activity.RESULT_OK, "Text is replaced.");
        });
        mInstrumentation.waitForIdleSync();

        // This is a TextView, which can't be modified. Hence no change should have been made.
        assertEquals(originalText, mTextView.getText().toString());
    }

    @Test
    public void testProcessTextActivityResultCancel() throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new EditText(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection(((EditText) mTextView).getText(), 0, mTextView.getText().length());

        CharSequence newText = "Text is replaced.";
        triggerOnActivityResult(Activity.RESULT_CANCELED, newText);

        assertEquals(originalText, mTextView.getText().toString());
    }

    @Test
    public void testProcessTextActivityNoData() throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new EditText(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection(((EditText) mTextView).getText(), 0, mTextView.getText().length());

        mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, null);

        assertEquals(originalText, mTextView.getText().toString());
    }

    private void triggerOnActivityResult(int resultCode, CharSequence replacementText) {
        Intent data = new Intent();
        data.putExtra(Intent.EXTRA_PROCESS_TEXT, replacementText);
        mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, resultCode, data);
    }
}
