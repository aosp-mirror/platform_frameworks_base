/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.annotation.WorkerThread;
import android.os.AsyncTask;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.textclassifier.TextClassificationResult;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextSelection;
import android.widget.Editor.SelectionModifierCursorController;

import com.android.internal.util.Preconditions;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Helper class for starting selection action mode
 * (synchronously without the TextClassifier, asynchronously with the TextClassifier).
 */
@UiThread
final class SelectionActionModeHelper {

    /**
     * Maximum time (in milliseconds) to wait for a result before timing out.
     */
    // TODO: Consider making this a ViewConfiguration.
    private static final int TIMEOUT_DURATION = 200;

    private final Editor mEditor;
    private final TextClassificationHelper mTextClassificationHelper;

    private TextClassificationResult mTextClassificationResult;
    private AsyncTask mTextClassificationAsyncTask;

    private final SelectionInfo mSelectionInfo = new SelectionInfo();

    SelectionActionModeHelper(@NonNull Editor editor) {
        mEditor = Preconditions.checkNotNull(editor);
        final TextView textView = mEditor.getTextView();
        mTextClassificationHelper = new TextClassificationHelper(
                textView.getTextClassifier(), textView.getText(), 0, 1);
    }

    public void startActionModeAsync() {
        cancelAsyncTask();
        if (isNoOpTextClassifier() || !hasSelection()) {
            // No need to make an async call for a no-op TextClassifier.
            // Do not call the TextClassifier if there is no selection.
            startActionMode(null);
        } else {
            resetTextClassificationHelper();
            mTextClassificationAsyncTask = new TextClassificationAsyncTask(
                    mEditor.getTextView(), TIMEOUT_DURATION,
                    mTextClassificationHelper::suggestSelection, this::startActionMode)
                    .execute();
        }
    }

    public void startActionMode() {
        startActionMode(null);
    }

    public void invalidateActionModeAsync() {
        cancelAsyncTask();
        if (isNoOpTextClassifier() || !hasSelection()) {
            // No need to make an async call for a no-op TextClassifier.
            // Do not call the TextClassifier if there is no selection.
            invalidateActionMode(null);
        } else {
            resetTextClassificationHelper();
            mTextClassificationAsyncTask = new TextClassificationAsyncTask(
                    mEditor.getTextView(), TIMEOUT_DURATION,
                    mTextClassificationHelper::classifyText, this::invalidateActionMode)
                    .execute();
        }
    }

    public boolean resetOriginalSelection(int textIndex) {
        if (mSelectionInfo.resetOriginalSelection(textIndex, mEditor.getTextView().getText())) {
            invalidateActionModeAsync();
            return true;
        }
        return false;
    }

    @Nullable
    public TextClassificationResult getTextClassificationResult() {
        return mTextClassificationResult;
    }

    public void onDestroyActionMode() {
        mSelectionInfo.onSelectionDestroyed();
        cancelAsyncTask();
    }

    private void cancelAsyncTask() {
        if (mTextClassificationAsyncTask != null) {
            mTextClassificationAsyncTask.cancel(true);
            mTextClassificationAsyncTask = null;
        }
        mTextClassificationResult = null;
    }

    private boolean isNoOpTextClassifier() {
        return mEditor.getTextView().getTextClassifier() == TextClassifier.NO_OP;
    }

    private boolean hasSelection() {
        final TextView textView = mEditor.getTextView();
        return textView.getSelectionEnd() > textView.getSelectionStart();
    }

    private void startActionMode(@Nullable SelectionResult result) {
        final TextView textView = mEditor.getTextView();
        final CharSequence text = textView.getText();
        mSelectionInfo.setOriginalSelection(
                textView.getSelectionStart(), textView.getSelectionEnd());
        if (result != null && text instanceof Spannable) {
            Selection.setSelection((Spannable) text, result.mStart, result.mEnd);
            mTextClassificationResult = result.mResult;
        } else {
            mTextClassificationResult = null;
        }
        if (mEditor.startSelectionActionModeInternal()) {
            final SelectionModifierCursorController controller = mEditor.getSelectionController();
            if (controller != null) {
                controller.show();
            }
            if (result != null) {
                mSelectionInfo.onSelectionStarted(result.mStart, result.mEnd);
            }
        }
        mEditor.setRestartActionModeOnNextRefresh(false);
        mTextClassificationAsyncTask = null;
    }

    private void invalidateActionMode(@Nullable SelectionResult result) {
        mTextClassificationResult = result != null ? result.mResult : null;
        final ActionMode actionMode = mEditor.getTextActionMode();
        if (actionMode != null) {
            actionMode.invalidate();
        }
        final TextView textView = mEditor.getTextView();
        mSelectionInfo.onSelectionUpdated(textView.getSelectionStart(), textView.getSelectionEnd());
        mTextClassificationAsyncTask = null;
    }

    private void resetTextClassificationHelper() {
        final TextView textView = mEditor.getTextView();
        mTextClassificationHelper.reset(textView.getTextClassifier(), textView.getText(),
                textView.getSelectionStart(), textView.getSelectionEnd());
    }

    /**
     * Holds information about the selection and uses it to decide on whether or not to update
     * the selection when resetOriginalSelection is called.
     * The expected UX here is to allow the user to re-snap the selection back to the original word
     * that was selected with one tap on that word.
     */
    private static final class SelectionInfo {

        private int mOriginalStart;
        private int mOriginalEnd;
        private int mSelectionStart;
        private int mSelectionEnd;

        private boolean mResetOriginal;

        public void setOriginalSelection(int selectionStart, int selectionEnd) {
            mOriginalStart = selectionStart;
            mOriginalEnd = selectionEnd;
            mResetOriginal = false;
        }

        public void onSelectionStarted(int selectionStart, int selectionEnd) {
            // Set the reset flag to true if the selection changed.
            mSelectionStart = selectionStart;
            mSelectionEnd = selectionEnd;
            mResetOriginal = mSelectionStart != mOriginalStart || mSelectionEnd != mOriginalEnd;
        }

        public void onSelectionUpdated(int selectionStart, int selectionEnd) {
            // If the selection did not change, maintain the reset state. Otherwise, disable reset.
            mResetOriginal &= selectionStart == mSelectionStart && selectionEnd == mSelectionEnd;
        }

        public void onSelectionDestroyed() {
            mResetOriginal = false;
        }

        public boolean resetOriginalSelection(int textIndex, CharSequence text) {
            if (mResetOriginal
                    && textIndex >= mOriginalStart && textIndex <= mOriginalEnd
                    && text instanceof Spannable) {
                Selection.setSelection((Spannable) text, mOriginalStart, mOriginalEnd);
                // Only allow a reset once.
                mResetOriginal = false;
                return true;
            }
            return false;
        }
    }

    /**
     * AsyncTask for running a query on a background thread and returning the result on the
     * UiThread. The AsyncTask times out after a specified time, returning a null result if the
     * query has not yet returned.
     */
    private static final class TextClassificationAsyncTask
            extends AsyncTask<Void, Void, SelectionResult> {

        private final int mTimeOutDuration;
        private final Supplier<SelectionResult> mSelectionResultSupplier;
        private final Consumer<SelectionResult> mSelectionResultCallback;
        private final TextView mTextView;
        private final String mOriginalText;

        /**
         * @param textView the TextView
         * @param timeOut time in milliseconds to timeout the query if it has not completed
         * @param selectionResultSupplier fetches the selection results. Runs on a background thread
         * @param selectionResultCallback receives the selection results. Runs on the UiThread
         */
        TextClassificationAsyncTask(
                @NonNull TextView textView, int timeOut,
                @NonNull Supplier<SelectionResult> selectionResultSupplier,
                @NonNull Consumer<SelectionResult> selectionResultCallback) {
            mTextView = Preconditions.checkNotNull(textView);
            mTimeOutDuration = timeOut;
            mSelectionResultSupplier = Preconditions.checkNotNull(selectionResultSupplier);
            mSelectionResultCallback = Preconditions.checkNotNull(selectionResultCallback);
            // Make a copy of the original text.
            mOriginalText = mTextView.getText().toString();
        }

        @Override
        @WorkerThread
        protected SelectionResult doInBackground(Void... params) {
            final Runnable onTimeOut = this::onTimeOut;
            mTextView.postDelayed(onTimeOut, mTimeOutDuration);
            final SelectionResult result = mSelectionResultSupplier.get();
            mTextView.removeCallbacks(onTimeOut);
            return result;
        }

        @Override
        @UiThread
        protected void onPostExecute(SelectionResult result) {
            result = TextUtils.equals(mOriginalText, mTextView.getText()) ? result : null;
            mSelectionResultCallback.accept(result);
        }

        private void onTimeOut() {
            if (getStatus() == Status.RUNNING) {
                onPostExecute(null);
            }
            cancel(true);
        }
    }

    /**
     * Helper class for querying the TextClassifier.
     * It trims text so that only text necessary to provide context of the selected text is
     * sent to the TextClassifier.
     */
    private static final class TextClassificationHelper {

        private static final int TRIM_DELTA = 50;  // characters

        private TextClassifier mTextClassifier;

        /** The original TextView text. **/
        private String mText;
        /** Start index relative to mText. */
        private int mSelectionStart;
        /** End index relative to mText. */
        private int mSelectionEnd;

        /** Trimmed text starting from mTrimStart in mText. */
        private CharSequence mTrimmedText;
        /** Index indicating the start of mTrimmedText in mText. */
        private int mTrimStart;
        /** Start index relative to mTrimmedText */
        private int mRelativeStart;
        /** End index relative to mTrimmedText */
        private int mRelativeEnd;

        TextClassificationHelper(TextClassifier textClassifier,
                CharSequence text, int selectionStart, int selectionEnd) {
            reset(textClassifier, text, selectionStart, selectionEnd);
        }

        @UiThread
        public void reset(TextClassifier textClassifier,
                CharSequence text, int selectionStart, int selectionEnd) {
            mTextClassifier = Preconditions.checkNotNull(textClassifier);
            mText = Preconditions.checkNotNull(text).toString();
            Preconditions.checkArgument(selectionEnd > selectionStart);
            mSelectionStart = selectionStart;
            mSelectionEnd = selectionEnd;
        }

        @WorkerThread
        public SelectionResult classifyText() {
            trimText();
            return new SelectionResult(
                    mSelectionStart,
                    mSelectionEnd,
                    mTextClassifier.getTextClassificationResult(
                            mTrimmedText, mRelativeStart, mRelativeEnd));
        }

        @WorkerThread
        public SelectionResult suggestSelection() {
            trimText();
            final TextSelection sel = mTextClassifier.suggestSelection(
                    mTrimmedText, mRelativeStart, mRelativeEnd);
            mSelectionStart = Math.max(0, sel.getSelectionStartIndex() + mTrimStart);
            mSelectionEnd = Math.min(mText.length(), sel.getSelectionEndIndex() + mTrimStart);
            return classifyText();
        }

        private void trimText() {
            mTrimStart = Math.max(0, mSelectionStart - TRIM_DELTA);
            final int referenceEnd = Math.min(mText.length(), mSelectionEnd + TRIM_DELTA);
            mTrimmedText = mText.subSequence(mTrimStart, referenceEnd);
            mRelativeStart = mSelectionStart - mTrimStart;
            mRelativeEnd = mSelectionEnd - mTrimStart;
        }
    }

    /**
     * Selection result.
     */
    private static final class SelectionResult {
        private final int mStart;
        private final int mEnd;
        private final TextClassificationResult mResult;

        SelectionResult(int start, int end, TextClassificationResult result) {
            mStart = start;
            mEnd = end;
            mResult = Preconditions.checkNotNull(result);
        }
    }
}
