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

import static android.view.ContentInfo.SOURCE_AUTOFILL;
import static android.view.ContentInfo.SOURCE_CLIPBOARD;
import static android.view.ContentInfo.SOURCE_DRAG_AND_DROP;
import static android.view.ContentInfo.SOURCE_INPUT_METHOD;
import static android.view.ContentInfo.SOURCE_PROCESS_TEXT;
import static android.widget.espresso.TextViewActions.clickOnTextAtIndex;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.os.Bundle;
import android.view.ContentInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputContentInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/**
 * Tests for {@link TextViewOnReceiveContentListener}. Most of the test cases are in the CTS test
 * {@link android.widget.cts.TextViewReceiveContentTest}. This class tests some internal
 * implementation details, e.g. fallback to the keyboard image API.
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class TextViewReceiveContentTest {
    private static final Uri SAMPLE_CONTENT_URI = Uri.parse("content://com.example/path");

    @Rule
    public ActivityTestRule<CustomInputConnectionEditTextActivity> mActivityRule =
            new ActivityTestRule<>(CustomInputConnectionEditTextActivity.class);

    private Instrumentation mInstrumentation;
    private Activity mActivity;
    private CustomInputConnectionEditText mEditText;
    private TextViewOnReceiveContentListener mDefaultReceiver;

    @Before
    public void before() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.getActivity();
        mEditText = mActivity.findViewById(R.id.edittext2);
        mDefaultReceiver = mEditText.getEditorForTesting().getDefaultOnReceiveContentListener();
    }

    @Test
    public void testGetFallbackMimeTypesForAutofill() throws Throwable {
        // Configure the EditText with an EditorInfo/InputConnection that supports some image MIME
        // types.
        String[] mimeTypes = {"image/gif", "image/png"};
        mEditText.setContentMimeTypes(mimeTypes);
        MyInputConnection ic = new MyInputConnection();
        mEditText.setInputConnectionWrapper(ic);

        // Focus into the EditText.
        onView(withId(mEditText.getId())).perform(clickOnTextAtIndex(0));

        // Assert that the default listener returns the MIME types declared in the EditorInfo.
        assertThat(mDefaultReceiver.getFallbackMimeTypesForAutofill(mEditText)).isEqualTo(
                mimeTypes);
    }

    @Test
    public void testGetFallbackMimeTypesForAutofill_noMimeTypesInEditorInfo()
            throws Throwable {
        // Configure the EditText with an EditorInfo/InputConnection that doesn't declare any MIME
        // types.
        mEditText.setContentMimeTypes(new String[0]);
        MyInputConnection ic = new MyInputConnection();
        mEditText.setInputConnectionWrapper(ic);

        // Focus into the EditText.
        onView(withId(mEditText.getId())).perform(clickOnTextAtIndex(0));

        // Assert that the default listener returns null as the MIME types.
        assertThat(mDefaultReceiver.getFallbackMimeTypesForAutofill(mEditText)).isNull();
    }

    @Test
    public void testOnReceive_fallbackToCommitContent() throws Throwable {
        // Configure the EditText with an EditorInfo/InputConnection that supports some image MIME
        // types.
        mEditText.setContentMimeTypes(new String[] {"image/gif", "image/png"});
        MyInputConnection ic = new MyInputConnection();
        mEditText.setInputConnectionWrapper(ic);

        // Focus into the EditText.
        onView(withId(mEditText.getId())).perform(clickOnTextAtIndex(0));

        // Invoke the listener with SOURCE_AUTOFILL and assert that it triggers a call to
        // InputConnection.commitContent.
        ClipDescription description = new ClipDescription("", new String[] {"image/gif"});
        ClipData clip = new ClipData(description, new ClipData.Item(SAMPLE_CONTENT_URI));
        ContentInfo payload =
                new ContentInfo.Builder(clip, SOURCE_AUTOFILL).build();
        mDefaultReceiver.onReceiveContent(mEditText, payload);
        verify(ic.mMock, times(1))
                .commitContent(any(InputContentInfo.class), eq(0), eq(null));
        verifyNoMoreInteractions(ic.mMock);
    }

    @Test
    public void testOnReceive_fallbackToCommitContent_noMimeTypesInEditorInfo() throws Throwable {
        // Configure the EditText with an EditorInfo/InputConnection that doesn't declare any MIME
        // types.
        mEditText.setContentMimeTypes(new String[0]);
        MyInputConnection ic = new MyInputConnection();
        mEditText.setInputConnectionWrapper(ic);

        // Focus into the EditText.
        onView(withId(mEditText.getId())).perform(clickOnTextAtIndex(0));

        // Invoke the listener and assert that the InputConnection is not invoked.
        ClipDescription description = new ClipDescription("", new String[] {"image/gif"});
        ClipData clip = new ClipData(description, new ClipData.Item(SAMPLE_CONTENT_URI));
        ContentInfo payload =
                new ContentInfo.Builder(clip, SOURCE_AUTOFILL).build();
        mDefaultReceiver.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);
    }

    @Test
    public void testOnReceive_fallbackToCommitContent_sourceOtherThanAutofill() throws Throwable {
        // Configure the EditText with an EditorInfo/InputConnection that supports some image MIME
        // types.
        mEditText.setContentMimeTypes(new String[] {"image/gif", "image/png"});
        MyInputConnection ic = new MyInputConnection();
        mEditText.setInputConnectionWrapper(ic);

        // Focus into the EditText.
        onView(withId(mEditText.getId())).perform(clickOnTextAtIndex(0));

        // Invoke the listener with sources other than SOURCE_AUTOFILL and assert that it does NOT
        // trigger calls to InputConnection.commitContent.
        ClipDescription description = new ClipDescription("", new String[] {"image/gif"});
        ClipData clip = new ClipData(description, new ClipData.Item(SAMPLE_CONTENT_URI));
        ContentInfo payload =
                new ContentInfo.Builder(clip, SOURCE_CLIPBOARD).build();
        mDefaultReceiver.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);

        payload = new ContentInfo.Builder(clip, SOURCE_INPUT_METHOD).build();
        mDefaultReceiver.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);

        payload = new ContentInfo.Builder(clip, SOURCE_DRAG_AND_DROP).build();
        mDefaultReceiver.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);

        payload = new ContentInfo.Builder(clip, SOURCE_PROCESS_TEXT).build();
        mDefaultReceiver.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);
    }

    private static class MyInputConnection extends InputConnectionWrapper {
        public final InputConnection mMock;

        MyInputConnection() {
            super(null, true);
            mMock = Mockito.mock(InputConnection.class);
        }

        @Override
        public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
            mMock.commitContent(inputContentInfo, flags, opts);
            return true;
        }
    }
}
