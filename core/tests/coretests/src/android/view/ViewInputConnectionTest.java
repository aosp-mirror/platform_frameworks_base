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
import static org.junit.Assert.fail;

import android.annotation.DurationMillisLong;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Tests for internal APIs/behaviors of {@link View} and {@link InputConnection}.
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class ViewInputConnectionTest {
    @DurationMillisLong
    private static final long TIMEOUT = 5000;
    @DurationMillisLong
    private static final long EXPECTED_TIMEOUT = 500;

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


    @Test
    public void testInputConnectionCallbacks_nonUiThread() throws Throwable {
        try (InputConnectionHandlingThread thread = new InputConnectionHandlingThread()) {
            final ViewGroup viewGroup = getOnMainSync(() -> mActivity.findViewById(R.id.root));
            final TestOffThreadEditor editor = getOnMainSync(() -> {
                final TestOffThreadEditor myEditor =
                        new TestOffThreadEditor(viewGroup.getContext(), thread.getHandler());
                viewGroup.addView(myEditor);
                myEditor.requestFocus();
                return myEditor;
            });

            mInstrumentation.waitForIdleSync();

            assertThat(editor.mOnCreateInputConnectionCalled.await(TIMEOUT, TimeUnit.MILLISECONDS))
                    .isTrue();

            // Invalidate the currently used InputConnection by moving the focus to a new EditText.
            mActivityRule.runOnUiThread(() -> {
                final EditText editText = new EditText(viewGroup.getContext());
                viewGroup.addView(editText);
                editText.requestFocus();
            });

            // Make sure that InputConnection#closeConnection() gets called on the handler thread.
            assertThat(editor.mInputConnectionClosedCalled.await(TIMEOUT, TimeUnit.MILLISECONDS))
                    .isTrue();
            assertThat(editor.mInputConnectionClosedCallingThreadId.get())
                    .isEqualTo(thread.getThreadId());

            // Make sure that View#onInputConnectionClosed() is not yet dispatched, because
            // InputConnection#closeConnection() is still blocked.
            assertThat(editor.mOnInputConnectionClosedCalled.await(
                    EXPECTED_TIMEOUT, TimeUnit.MILLISECONDS)).isFalse();

            // Unblock InputConnection#closeConnection()
            editor.mInputConnectionClosedBlocker.countDown();

            // Make sure that View#onInputConnectionClosed() is dispatched on the main thread.
            assertThat(editor.mOnInputConnectionClosedCalled.await(TIMEOUT, TimeUnit.MILLISECONDS))
                    .isTrue();
            assertThat(editor.mInputConnectionClosedBlockerTimedOut.get()).isFalse();
            assertThat(editor.mOnInputConnectionClosedCallingThreadId.get())
                    .isEqualTo(getOnMainSync(Process::myTid));
        }
    }

    private <T> T getOnMainSync(@NonNull Supplier<T> supplier) throws Throwable {
        final AtomicReference<T> result = new AtomicReference<>();
        mActivityRule.runOnUiThread(() -> result.set(supplier.get()));
        return result.get();
    }

    private static class TestOffThreadEditor extends View {
        private static final int TEST_VIEW_HEIGHT = 10;

        public CountDownLatch mOnCreateInputConnectionCalled = new CountDownLatch(1);
        public CountDownLatch mInputConnectionClosedCalled = new CountDownLatch(1);
        public CountDownLatch mInputConnectionClosedBlocker = new CountDownLatch(1);
        public AtomicBoolean mInputConnectionClosedBlockerTimedOut = new AtomicBoolean();
        public AtomicReference<Integer> mInputConnectionClosedCallingThreadId =
                new AtomicReference<>();

        public CountDownLatch mOnInputConnectionClosedCalled = new CountDownLatch(1);
        public AtomicReference<Integer> mOnInputConnectionClosedCallingThreadId =
                new AtomicReference<>();

        private final Handler mInputConnectionHandler;

        TestOffThreadEditor(Context context, @NonNull Handler inputConnectionHandler) {
            super(context);
            setBackgroundColor(Color.YELLOW);
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, TEST_VIEW_HEIGHT));
            setFocusableInTouchMode(true);
            setFocusable(true);
            mInputConnectionHandler = inputConnectionHandler;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            mOnCreateInputConnectionCalled.countDown();
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
            return new NoOpInputConnection() {
                @Override
                public Handler getHandler() {
                    return mInputConnectionHandler;
                }

                @Override
                public void closeConnection() {
                    mInputConnectionClosedCallingThreadId.compareAndSet(null, Process.myTid());
                    mInputConnectionClosedCalled.countDown();
                    try {
                        if (mInputConnectionClosedBlocker.await(TIMEOUT, TimeUnit.MILLISECONDS)) {
                            return;
                        }
                    } catch (InterruptedException e) {
                    }
                    mInputConnectionClosedBlockerTimedOut.set(true);
                }
            };
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public void onInputConnectionClosedInternal() {
            mOnInputConnectionClosedCallingThreadId.compareAndSet(null, Process.myTid());
            mOnInputConnectionClosedCalled.countDown();
            super.onInputConnectionClosedInternal();
        }
    }

    static class NoOpInputConnection implements InputConnection {

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            return null;
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            return null;
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            return null;
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            return 0;
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            return null;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            return false;
        }

        @Override
        public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
            return false;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return false;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            return false;
        }

        @Override
        public boolean finishComposingText() {
            return false;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            return false;
        }

        @Override
        public boolean commitCompletion(CompletionInfo text) {
            return false;
        }

        @Override
        public boolean commitCorrection(CorrectionInfo correctionInfo) {
            return false;
        }

        @Override
        public boolean setSelection(int start, int end) {
            return false;
        }

        @Override
        public boolean performEditorAction(int editorAction) {
            return false;
        }

        @Override
        public boolean performContextMenuAction(int id) {
            return false;
        }

        @Override
        public boolean beginBatchEdit() {
            return false;
        }

        @Override
        public boolean endBatchEdit() {
            return false;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return false;
        }

        @Override
        public boolean clearMetaKeyStates(int states) {
            return false;
        }

        @Override
        public boolean reportFullscreenMode(boolean enabled) {
            return false;
        }

        @Override
        public boolean performPrivateCommand(String action, Bundle data) {
            return false;
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode) {
            return false;
        }

        @Override
        public boolean requestCursorUpdates(int cursorUpdateMode, int cursorUpdateFilter) {
            return false;
        }

        @Override
        public Handler getHandler() {
            return null;
        }

        @Override
        public void closeConnection() {

        }

        @Override
        public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
            return false;
        }
    }

    private static final class InputConnectionHandlingThread extends HandlerThread
            implements AutoCloseable {

        private final Handler mHandler;

        InputConnectionHandlingThread() {
            super("IC-callback");
            start();
            mHandler = Handler.createAsync(getLooper());
        }

        @NonNull
        Handler getHandler() {
            return mHandler;
        }

        @Override
        public void close() {
            quitSafely();
            try {
                join(TIMEOUT);
            } catch (InterruptedException e) {
                fail("Failed to stop the thread: " + e);
            }
        }
    }
}
