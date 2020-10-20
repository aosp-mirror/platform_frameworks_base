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

import static android.view.OnReceiveContentCallback.Payload.SOURCE_AUTOFILL;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_CLIPBOARD;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_DRAG_AND_DROP;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_INPUT_METHOD;
import static android.view.OnReceiveContentCallback.Payload.SOURCE_PROCESS_TEXT;
import static android.widget.TextViewOnReceiveContentCallback.canReuse;
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
import android.util.ArraySet;
import android.view.OnReceiveContentCallback;
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
 * Tests for {@link TextViewOnReceiveContentCallback}. Most of the test cases are in the CTS test
 * {@link android.widget.cts.TextViewOnReceiveContentCallbackTest}. This class tests some internal
 * implementation details, e.g. fallback to the keyboard image API.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class TextViewOnReceiveContentCallbackTest {
    private static final Uri SAMPLE_CONTENT_URI = Uri.parse("content://com.example/path");

    @Rule
    public ActivityTestRule<CustomInputConnectionEditTextActivity> mActivityRule =
            new ActivityTestRule<>(CustomInputConnectionEditTextActivity.class);

    private Instrumentation mInstrumentation;
    private Activity mActivity;
    private CustomInputConnectionEditText mEditText;
    private TextViewOnReceiveContentCallback mDefaultCallback;

    @Before
    public void before() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.getActivity();
        mEditText = mActivity.findViewById(R.id.edittext2);
        mDefaultCallback = mEditText.getEditorForTesting().getDefaultOnReceiveContentCallback();
    }

    @Test
    public void testGetSupportedMimeTypes_fallbackToCommitContent() throws Throwable {
        // Configure the EditText with an EditorInfo/InputConnection that supports some image MIME
        // types.
        mEditText.setContentMimeTypes(new String[] {"image/gif", "image/png"});
        MyInputConnection ic = new MyInputConnection();
        mEditText.setInputConnectionWrapper(ic);

        // Focus into the EditText.
        onView(withId(mEditText.getId())).perform(clickOnTextAtIndex(0));

        // Assert that the callback returns the MIME types declared in the EditorInfo in addition to
        // the default.
        assertThat(mDefaultCallback.getSupportedMimeTypes(mEditText)).containsExactly(
                "text/*", "image/gif", "image/png");
    }

    @Test
    public void testGetSupportedMimeTypes_fallbackToCommitContent_noMimeTypesInEditorInfo()
            throws Throwable {
        // Configure the EditText with an EditorInfo/InputConnection that doesn't declare any MIME
        // types.
        mEditText.setContentMimeTypes(new String[0]);
        MyInputConnection ic = new MyInputConnection();
        mEditText.setInputConnectionWrapper(ic);

        // Focus into the EditText.
        onView(withId(mEditText.getId())).perform(clickOnTextAtIndex(0));

        // Assert that the callback returns the default MIME types.
        assertThat(mDefaultCallback.getSupportedMimeTypes(mEditText)).containsExactly("text/*");
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

        // Invoke the callback with SOURCE_AUTOFILL and assert that it triggers a call to
        // InputConnection.commitContent.
        ClipDescription description = new ClipDescription("", new String[] {"image/gif"});
        ClipData clip = new ClipData(description, new ClipData.Item(SAMPLE_CONTENT_URI));
        OnReceiveContentCallback.Payload payload =
                new OnReceiveContentCallback.Payload.Builder(clip, SOURCE_AUTOFILL).build();
        mDefaultCallback.onReceiveContent(mEditText, payload);
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

        // Invoke the callback and assert that the InputConnection is not invoked.
        ClipDescription description = new ClipDescription("", new String[] {"image/gif"});
        ClipData clip = new ClipData(description, new ClipData.Item(SAMPLE_CONTENT_URI));
        OnReceiveContentCallback.Payload payload =
                new OnReceiveContentCallback.Payload.Builder(clip, SOURCE_AUTOFILL).build();
        mDefaultCallback.onReceiveContent(mEditText, payload);
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

        // Invoke the callback with sources other than SOURCE_AUTOFILL and assert that it does NOT
        // trigger calls to InputConnection.commitContent.
        ClipDescription description = new ClipDescription("", new String[] {"image/gif"});
        ClipData clip = new ClipData(description, new ClipData.Item(SAMPLE_CONTENT_URI));
        OnReceiveContentCallback.Payload payload =
                new OnReceiveContentCallback.Payload.Builder(clip, SOURCE_CLIPBOARD).build();
        mDefaultCallback.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);

        payload = new OnReceiveContentCallback.Payload.Builder(clip, SOURCE_INPUT_METHOD).build();
        mDefaultCallback.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);

        payload = new OnReceiveContentCallback.Payload.Builder(clip, SOURCE_DRAG_AND_DROP).build();
        mDefaultCallback.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);

        payload = new OnReceiveContentCallback.Payload.Builder(clip, SOURCE_PROCESS_TEXT).build();
        mDefaultCallback.onReceiveContent(mEditText, payload);
        verifyZeroInteractions(ic.mMock);
    }

    @Test
    public void testCanReuse() throws Throwable {
        ArraySet<String> mimeTypes = null;
        String[] editorContentMimeTypes = new String[0];
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isFalse();

        mimeTypes = new ArraySet<>();
        editorContentMimeTypes = new String[0];
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isTrue();

        mimeTypes = newArraySet("text/*");
        editorContentMimeTypes = new String[0];
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isTrue();

        mimeTypes = newArraySet("text/*");
        editorContentMimeTypes = new String[] {"text/*"};
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isTrue();

        mimeTypes = newArraySet("image/gif", "image/png", "text/*");
        editorContentMimeTypes = new String[] {"image/gif", "image/png"};
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isTrue();

        mimeTypes = newArraySet("image/gif", "image/png", "text/*");
        editorContentMimeTypes = new String[] {"image/gif", "image/png", "text/*"};
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isTrue();

        mimeTypes = newArraySet("image/gif", "image/png", "text/*");
        editorContentMimeTypes = new String[] {"image/gif"};
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isFalse();

        mimeTypes = newArraySet("image/gif", "image/png", "text/*");
        editorContentMimeTypes = new String[] {"image/gif", "image/png", "image/jpg"};
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isFalse();

        mimeTypes = newArraySet("image/gif", "image/png", "text/*");
        editorContentMimeTypes = new String[] {"image/gif", "image/jpg"};
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isFalse();

        mimeTypes = newArraySet("image/gif", "image/png", "text/*");
        editorContentMimeTypes = new String[] {"image/gif", "image/jpg", "text/*"};
        assertThat(canReuse(mimeTypes, editorContentMimeTypes)).isFalse();
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

    @SafeVarargs
    private static <T> ArraySet<T> newArraySet(T ... elements) {
        return new ArraySet<>(elements);
    }
}
