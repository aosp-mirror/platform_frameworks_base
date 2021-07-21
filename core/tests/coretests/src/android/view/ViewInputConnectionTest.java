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

package android.view;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.text.format.DateUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import com.android.compatibility.common.util.PollingCheck;
import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for internal APIs/behaviors of {@link View} and {@link InputConnection}.
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class ViewInputConnectionTest {
    @Rule
    public ActivityTestRule<ViewInputConnectionTestActivity> mActivityRule =
            new ActivityTestRule<>(ViewInputConnectionTestActivity.class);

    private Instrumentation mInstrumentation;
    private ViewInputConnectionTestActivity mActivity;
    private InputMethodManager mImm;

    @Before
    public void before() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.getActivity();
        PollingCheck.waitFor(5 * DateUtils.SECOND_IN_MILLIS, mActivity::hasWindowFocus);
        assertTrue(mActivity.hasWindowFocus());
        mImm = mActivity.getSystemService(InputMethodManager.class);
    }

    @Test
    public void testInputConnectionCallbacks() throws Throwable {
        // Add two EditText inputs to the layout view.
        final ViewGroup viewGroup = mActivity.findViewById(R.id.root);
        final TestEditText editText1 = new TestEditText(mActivity, false);
        final TestEditText editText2 = new TestEditText(mActivity, false);
        mActivityRule.runOnUiThread(() -> {
            viewGroup.addView(editText1);
            viewGroup.addView(editText2);
        });
        mInstrumentation.waitForIdleSync();

        // Focus into the first EditText.
        mActivityRule.runOnUiThread(editText1::requestFocus);
        mInstrumentation.waitForIdleSync();
        assertThat(editText1.isFocused()).isTrue();
        assertThat(editText2.isFocused()).isFalse();

        // Show the IME for the first EditText. Assert that the appropriate opened/closed callbacks
        // have been invoked (InputConnection opened for the first EditText).
        mActivityRule.runOnUiThread(() -> mImm.showSoftInput(editText1, 0));
        mInstrumentation.waitForIdleSync();
        mActivityRule.runOnUiThread(() -> {
            assertThat(editText1.mCalledOnCreateInputConnection).isTrue();
            assertThat(editText1.mCalledOnInputConnectionOpened).isTrue();
            assertThat(editText1.mCalledOnInputConnectionClosed).isFalse();

            assertThat(editText2.mCalledOnCreateInputConnection).isFalse();
            assertThat(editText2.mCalledOnInputConnectionOpened).isFalse();
            assertThat(editText2.mCalledOnInputConnectionClosed).isFalse();
        });

        // Focus into the second EditText.
        mActivityRule.runOnUiThread(editText2::requestFocus);
        mInstrumentation.waitForIdleSync();
        assertThat(editText1.isFocused()).isFalse();
        assertThat(editText2.isFocused()).isTrue();

        // Show the IME for the second EditText. Assert that the appropriate opened/closed callbacks
        // have been invoked (InputConnection closed for the first EditText and opened for the
        // second EditText).
        mActivityRule.runOnUiThread(() -> mImm.showSoftInput(editText2, 0));
        mInstrumentation.waitForIdleSync();
        mActivityRule.runOnUiThread(() -> {
            assertThat(editText1.mCalledOnCreateInputConnection).isTrue();
            assertThat(editText1.mCalledOnInputConnectionOpened).isTrue();
            assertThat(editText1.mCalledOnInputConnectionClosed).isTrue();

            assertThat(editText2.mCalledOnCreateInputConnection).isTrue();
            assertThat(editText2.mCalledOnInputConnectionOpened).isTrue();
            assertThat(editText2.mCalledOnInputConnectionClosed).isFalse();
        });
    }

    @Test
    public void testInputConnectionCallbacks_nullInputConnection() throws Throwable {
        // Add two EditText inputs to the layout view.
        final ViewGroup viewGroup = mActivity.findViewById(R.id.root);
        final TestEditText editText1 = new TestEditText(mActivity, true);
        final TestEditText editText2 = new TestEditText(mActivity, true);
        mActivityRule.runOnUiThread(() -> {
            viewGroup.addView(editText1);
            viewGroup.addView(editText2);
        });
        mInstrumentation.waitForIdleSync();

        // Focus into the first EditText.
        mActivityRule.runOnUiThread(editText1::requestFocus);
        mInstrumentation.waitForIdleSync();
        assertThat(editText1.isFocused()).isTrue();
        assertThat(editText2.isFocused()).isFalse();

        // Show the IME for the first EditText. Assert that the opened/closed callbacks are not
        // invoked since there's no input connection.
        mActivityRule.runOnUiThread(() -> mImm.showSoftInput(editText1, 0));
        mInstrumentation.waitForIdleSync();
        mActivityRule.runOnUiThread(() -> {
            assertThat(editText1.mCalledOnCreateInputConnection).isTrue();
            assertThat(editText1.mCalledOnInputConnectionOpened).isFalse();
            assertThat(editText1.mCalledOnInputConnectionClosed).isFalse();

            assertThat(editText2.mCalledOnCreateInputConnection).isFalse();
            assertThat(editText2.mCalledOnInputConnectionOpened).isFalse();
            assertThat(editText2.mCalledOnInputConnectionClosed).isFalse();
        });

        // Focus into the second EditText.
        mActivityRule.runOnUiThread(editText2::requestFocus);
        mInstrumentation.waitForIdleSync();
        assertThat(editText1.isFocused()).isFalse();
        assertThat(editText2.isFocused()).isTrue();

        // Show the IME for the second EditText. Assert that the opened/closed callbacks are not
        // invoked since there's no input connection.
        mActivityRule.runOnUiThread(() -> mImm.showSoftInput(editText2, 0));
        mInstrumentation.waitForIdleSync();
        mActivityRule.runOnUiThread(() -> {
            assertThat(editText1.mCalledOnCreateInputConnection).isTrue();
            assertThat(editText1.mCalledOnInputConnectionOpened).isFalse();
            assertThat(editText1.mCalledOnInputConnectionClosed).isFalse();

            assertThat(editText2.mCalledOnCreateInputConnection).isTrue();
            assertThat(editText2.mCalledOnInputConnectionOpened).isFalse();
            assertThat(editText2.mCalledOnInputConnectionClosed).isFalse();
        });
    }

    @Test
    public void testInputConnectionCallbacks_nonEditableInput() throws Throwable {
        final ViewGroup viewGroup = mActivity.findViewById(R.id.root);
        final TestButton view1 = new TestButton(mActivity);
        final TestButton view2 = new TestButton(mActivity);
        mActivityRule.runOnUiThread(() -> {
            viewGroup.addView(view1);
            viewGroup.addView(view2);
        });
        mInstrumentation.waitForIdleSync();

        // Request focus + IME on the first view.
        mActivityRule.runOnUiThread(view1::requestFocus);
        mInstrumentation.waitForIdleSync();
        assertThat(view1.isFocused()).isTrue();
        assertThat(view2.isFocused()).isFalse();
        mActivityRule.runOnUiThread(() -> mImm.showSoftInput(view1, 0));
        mInstrumentation.waitForIdleSync();

        // Assert that the opened/closed callbacks are not invoked since there's no InputConnection.
        mActivityRule.runOnUiThread(() -> {
            assertThat(view1.mCalledOnCreateInputConnection).isTrue();
            assertThat(view1.mCalledOnInputConnectionOpened).isFalse();
            assertThat(view1.mCalledOnInputConnectionClosed).isFalse();

            assertThat(view2.mCalledOnCreateInputConnection).isFalse();
            assertThat(view2.mCalledOnInputConnectionOpened).isFalse();
            assertThat(view2.mCalledOnInputConnectionClosed).isFalse();
        });

        // Request focus + IME on the second view.
        mActivityRule.runOnUiThread(view2::requestFocus);
        mInstrumentation.waitForIdleSync();
        assertThat(view1.isFocused()).isFalse();
        assertThat(view2.isFocused()).isTrue();
        mActivityRule.runOnUiThread(() -> mImm.showSoftInput(view1, 0));
        mInstrumentation.waitForIdleSync();

        // Assert that the opened/closed callbacks are not invoked since there's no InputConnection.
        mActivityRule.runOnUiThread(() -> {
            assertThat(view1.mCalledOnCreateInputConnection).isTrue();
            assertThat(view1.mCalledOnInputConnectionOpened).isFalse();
            assertThat(view1.mCalledOnInputConnectionClosed).isFalse();

            assertThat(view2.mCalledOnCreateInputConnection).isTrue();
            assertThat(view2.mCalledOnInputConnectionOpened).isFalse();
            assertThat(view2.mCalledOnInputConnectionClosed).isFalse();
        });
    }

    private static class TestEditText extends EditText {
        private final boolean mReturnNullInputConnection;

        public boolean mCalledOnCreateInputConnection = false;
        public boolean mCalledOnInputConnectionOpened = false;
        public boolean mCalledOnInputConnectionClosed = false;

        TestEditText(Context context, boolean returnNullInputConnection) {
            super(context);
            mReturnNullInputConnection = returnNullInputConnection;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            mCalledOnCreateInputConnection = true;
            if (mReturnNullInputConnection) {
                return null;
            } else {
                return super.onCreateInputConnection(outAttrs);
            }
        }

        @Override
        public void onInputConnectionOpenedInternal(@NonNull InputConnection inputConnection,
                @NonNull EditorInfo editorInfo, @Nullable Handler handler) {
            mCalledOnInputConnectionOpened = true;
            super.onInputConnectionOpenedInternal(inputConnection, editorInfo, handler);
        }

        @Override
        public void onInputConnectionClosedInternal() {
            mCalledOnInputConnectionClosed = true;
            super.onInputConnectionClosedInternal();
        }
    }

    private static class TestButton extends Button {
        public boolean mCalledOnCreateInputConnection = false;
        public boolean mCalledOnInputConnectionOpened = false;
        public boolean mCalledOnInputConnectionClosed = false;

        TestButton(Context context) {
            super(context);
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            mCalledOnCreateInputConnection = true;
            return super.onCreateInputConnection(outAttrs);
        }

        @Override
        public void onInputConnectionOpenedInternal(@NonNull InputConnection inputConnection,
                @NonNull EditorInfo editorInfo, @Nullable Handler handler) {
            mCalledOnInputConnectionOpened = true;
            super.onInputConnectionOpenedInternal(inputConnection, editorInfo, handler);
        }

        @Override
        public void onInputConnectionClosedInternal() {
            mCalledOnInputConnectionClosed = true;
            super.onInputConnectionClosedInternal();
        }
    }
}
