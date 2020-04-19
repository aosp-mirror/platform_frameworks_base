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

import static android.widget.RichContentReceiver.SOURCE_PROCESS_TEXT;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.text.Selection;
import android.text.Spannable;
import android.widget.TextView.BufferType;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.Set;

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

        MockReceiverWrapper mockReceiverWrapper = new MockReceiverWrapper();
        mTextView.setRichContentReceiver(mockReceiverWrapper);

        // We need to run this in the UI thread, as it will create a Toast.
        mActivityRule.runOnUiThread(() -> {
            triggerOnActivityResult(Activity.RESULT_OK, "Text is replaced.");
        });
        mInstrumentation.waitForIdleSync();

        // This is a TextView, which can't be modified. Hence no change should have been made.
        assertEquals(originalText, mTextView.getText().toString());
        verifyZeroInteractions(mockReceiverWrapper.mMock);
    }

    @Test
    public void testProcessTextActivityResultEditable_defaultRichContentReceiver()
            throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new EditText(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection(((EditText) mTextView).getText(), 0, mTextView.getText().length());

        CharSequence newText = "Text is replaced.";
        triggerOnActivityResult(Activity.RESULT_OK, newText);

        assertEquals(newText, mTextView.getText().toString());
    }

    @Test
    public void testProcessTextActivityResultEditable_customRichContentReceiver() throws Throwable {
        mActivityRule.runOnUiThread(() -> mTextView = new EditText(mActivity));
        mInstrumentation.waitForIdleSync();
        CharSequence originalText = "This is some text.";
        mTextView.setText(originalText, BufferType.SPANNABLE);
        assertEquals(originalText, mTextView.getText().toString());
        mTextView.setTextIsSelectable(true);
        Selection.setSelection(((EditText) mTextView).getText(), 0, mTextView.getText().length());

        MockReceiverWrapper mockReceiverWrapper = new MockReceiverWrapper();
        mTextView.setRichContentReceiver(mockReceiverWrapper);

        CharSequence newText = "Text is replaced.";
        triggerOnActivityResult(Activity.RESULT_OK, newText);

        ClipData expectedClip = ClipData.newPlainText("", newText);
        verify(mockReceiverWrapper.mMock, times(1)).onReceive(
                eq(mTextView), clipEq(expectedClip), eq(SOURCE_PROCESS_TEXT), eq(0));
        verifyNoMoreInteractions(mockReceiverWrapper.mMock);
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

        MockReceiverWrapper mockReceiverWrapper = new MockReceiverWrapper();
        mTextView.setRichContentReceiver(mockReceiverWrapper);

        CharSequence newText = "Text is replaced.";
        triggerOnActivityResult(Activity.RESULT_CANCELED, newText);

        assertEquals(originalText, mTextView.getText().toString());
        verifyZeroInteractions(mockReceiverWrapper.mMock);
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

        MockReceiverWrapper mockReceiverWrapper = new MockReceiverWrapper();
        mTextView.setRichContentReceiver(mockReceiverWrapper);

        mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, Activity.RESULT_OK, null);

        assertEquals(originalText, mTextView.getText().toString());
        verifyZeroInteractions(mockReceiverWrapper.mMock);
    }

    private void triggerOnActivityResult(int resultCode, CharSequence replacementText) {
        Intent data = new Intent();
        data.putExtra(Intent.EXTRA_PROCESS_TEXT, replacementText);
        mTextView.onActivityResult(TextView.PROCESS_TEXT_REQUEST_CODE, resultCode, data);
    }

    // This wrapper is used so that we only mock and verify the public callback methods. In addition
    // to the public methods, the RichContentReceiver interface has some hidden default methods;
    // we don't want to mock or assert calls to these helper functions (they are an implementation
    // detail).
    private static class MockReceiverWrapper implements RichContentReceiver<TextView> {
        private final RichContentReceiver<TextView> mMock;

        @SuppressWarnings("unchecked")
        MockReceiverWrapper() {
            this.mMock = Mockito.mock(RichContentReceiver.class);
        }

        public RichContentReceiver<TextView> getMock() {
            return mMock;
        }

        @Override
        public boolean onReceive(TextView view, ClipData clip, @Source int source,
                @Flags int flags) {
            return mMock.onReceive(view, clip, source, flags);
        }

        @Override
        public Set<String> getSupportedMimeTypes() {
            return mMock.getSupportedMimeTypes();
        }
    }

    private static ClipData clipEq(ClipData expected) {
        return argThat(new ClipDataArgumentMatcher(expected));
    }

    private static class ClipDataArgumentMatcher implements ArgumentMatcher<ClipData> {
        private final ClipData mExpected;

        private ClipDataArgumentMatcher(ClipData expected) {
            this.mExpected = expected;
        }

        @Override
        public boolean matches(ClipData actual) {
            ClipDescription actualDesc = actual.getDescription();
            ClipDescription expectedDesc = mExpected.getDescription();
            return expectedDesc.getLabel().equals(actualDesc.getLabel())
                    && actualDesc.getMimeTypeCount() == 1
                    && expectedDesc.getMimeType(0).equals(actualDesc.getMimeType(0))
                    && actual.getItemCount() == 1
                    && mExpected.getItemAt(0).getText().equals(actual.getItemAt(0).getText());
        }
    }
}
