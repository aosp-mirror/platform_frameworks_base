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
import android.os.LocaleList;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.util.Log;
import android.view.ActionMode;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextSelection;
import android.view.textclassifier.logging.SmartSelectionEventTracker;
import android.view.textclassifier.logging.SmartSelectionEventTracker.SelectionEvent;
import android.widget.Editor.SelectionModifierCursorController;

import com.android.internal.util.Preconditions;

import java.text.BreakIterator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Helper class for starting selection action mode
 * (synchronously without the TextClassifier, asynchronously with the TextClassifier).
 */
@UiThread
final class SelectionActionModeHelper {

    private static final String LOG_TAG = "SelectActionModeHelper";

    private final Editor mEditor;
    private final TextView mTextView;
    private final TextClassificationHelper mTextClassificationHelper;

    private TextClassification mTextClassification;
    private AsyncTask mTextClassificationAsyncTask;

    private final SelectionTracker mSelectionTracker;

    SelectionActionModeHelper(@NonNull Editor editor) {
        mEditor = Preconditions.checkNotNull(editor);
        mTextView = mEditor.getTextView();
        mTextClassificationHelper = new TextClassificationHelper(
                mTextView.getTextClassifier(),
                getText(mTextView),
                0, 1, mTextView.getTextLocales());
        mSelectionTracker = new SelectionTracker(mTextView);
    }

    public void startActionModeAsync(boolean adjustSelection) {
        // Check if the smart selection should run for editable text.
        adjustSelection &= !mTextView.isTextEditable()
                || mTextView.getTextClassifier().getSettings()
                        .isSuggestSelectionEnabledForEditableText();

        mSelectionTracker.onOriginalSelection(
                getText(mTextView),
                mTextView.getSelectionStart(),
                mTextView.getSelectionEnd());
        cancelAsyncTask();
        if (skipTextClassification()) {
            startActionMode(null);
        } else {
            resetTextClassificationHelper();
            mTextClassificationAsyncTask = new TextClassificationAsyncTask(
                    mTextView,
                    mTextClassificationHelper.getTimeoutDuration(),
                    adjustSelection
                            ? mTextClassificationHelper::suggestSelection
                            : mTextClassificationHelper::classifyText,
                    this::startActionMode)
                    .execute();
        }
    }

    public void invalidateActionModeAsync() {
        cancelAsyncTask();
        if (skipTextClassification()) {
            invalidateActionMode(null);
        } else {
            resetTextClassificationHelper();
            mTextClassificationAsyncTask = new TextClassificationAsyncTask(
                    mTextView,
                    mTextClassificationHelper.getTimeoutDuration(),
                    mTextClassificationHelper::classifyText,
                    this::invalidateActionMode)
                    .execute();
        }
    }

    public void onSelectionAction(int menuItemId) {
        mSelectionTracker.onSelectionAction(
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(),
                getActionType(menuItemId), mTextClassification);
    }

    public void onSelectionDrag() {
        mSelectionTracker.onSelectionAction(
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(),
                SelectionEvent.ActionType.DRAG, mTextClassification);
    }

    public void onTextChanged(int start, int end) {
        mSelectionTracker.onTextChanged(start, end, mTextClassification);
    }

    public boolean resetSelection(int textIndex) {
        if (mSelectionTracker.resetSelection(textIndex, mEditor)) {
            invalidateActionModeAsync();
            return true;
        }
        return false;
    }

    @Nullable
    public TextClassification getTextClassification() {
        return mTextClassification;
    }

    public void onDestroyActionMode() {
        mSelectionTracker.onSelectionDestroyed();
        cancelAsyncTask();
    }

    private void cancelAsyncTask() {
        if (mTextClassificationAsyncTask != null) {
            mTextClassificationAsyncTask.cancel(true);
            mTextClassificationAsyncTask = null;
        }
        mTextClassification = null;
    }

    private boolean skipTextClassification() {
        // No need to make an async call for a no-op TextClassifier.
        final boolean noOpTextClassifier = mTextView.getTextClassifier() == TextClassifier.NO_OP;
        // Do not call the TextClassifier if there is no selection.
        final boolean noSelection = mTextView.getSelectionEnd() == mTextView.getSelectionStart();
        // Do not call the TextClassifier if this is a password field.
        final boolean password = mTextView.hasPasswordTransformationMethod()
                || TextView.isPasswordInputType(mTextView.getInputType());
        return noOpTextClassifier || noSelection || password;
    }

    private void startActionMode(@Nullable SelectionResult result) {
        final CharSequence text = getText(mTextView);
        if (result != null && text instanceof Spannable) {
            // Do not change the selection if TextClassifier should be dark launched.
            if (!mTextView.getTextClassifier().getSettings().isDarkLaunch()) {
                Selection.setSelection((Spannable) text, result.mStart, result.mEnd);
            }
            mTextClassification = result.mClassification;
        } else {
            mTextClassification = null;
        }
        if (mEditor.startSelectionActionModeInternal()) {
            final SelectionModifierCursorController controller = mEditor.getSelectionController();
            if (controller != null) {
                controller.show();
            }
            if (result != null) {
                mSelectionTracker.onSmartSelection(result);
            }
        }
        mEditor.setRestartActionModeOnNextRefresh(false);
        mTextClassificationAsyncTask = null;
    }

    private void invalidateActionMode(@Nullable SelectionResult result) {
        mTextClassification = result != null ? result.mClassification : null;
        final ActionMode actionMode = mEditor.getTextActionMode();
        if (actionMode != null) {
            actionMode.invalidate();
        }
        mSelectionTracker.onSelectionUpdated(
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(), mTextClassification);
        mTextClassificationAsyncTask = null;
    }

    private void resetTextClassificationHelper() {
        mTextClassificationHelper.init(
                mTextView.getTextClassifier(),
                getText(mTextView),
                mTextView.getSelectionStart(), mTextView.getSelectionEnd(),
                mTextView.getTextLocales());
    }

    /**
     * Tracks and logs smart selection changes.
     * It is important to trigger this object's methods at the appropriate event so that it tracks
     * smart selection events appropriately.
     */
    private static final class SelectionTracker {

        private final TextView mTextView;
        private SelectionMetricsLogger mLogger;

        private int mOriginalStart;
        private int mOriginalEnd;
        private int mSelectionStart;
        private int mSelectionEnd;
        private boolean mAllowReset;
        private final LogAbandonRunnable mDelayedLogAbandon = new LogAbandonRunnable();

        SelectionTracker(TextView textView) {
            mTextView = Preconditions.checkNotNull(textView);
            mLogger = new SelectionMetricsLogger(textView);
        }

        /**
         * Called when the original selection happens, before smart selection is triggered.
         */
        public void onOriginalSelection(CharSequence text, int selectionStart, int selectionEnd) {
            // If we abandoned a selection and created a new one very shortly after, we may still
            // have a pending request to log ABANDON, which we flush here.
            mDelayedLogAbandon.flush();

            mOriginalStart = mSelectionStart = selectionStart;
            mOriginalEnd = mSelectionEnd = selectionEnd;
            mAllowReset = false;
            maybeInvalidateLogger();
            mLogger.logSelectionStarted(text, selectionStart);
        }

        /**
         * Called when selection action mode is started and the results come from a classifier.
         */
        public void onSmartSelection(SelectionResult result) {
            if (isSelectionStarted()) {
                mSelectionStart = result.mStart;
                mSelectionEnd = result.mEnd;
                mAllowReset = mSelectionStart != mOriginalStart || mSelectionEnd != mOriginalEnd;
                mLogger.logSelectionModified(
                        result.mStart, result.mEnd, result.mClassification, result.mSelection);
            }
        }

        /**
         * Called when selection bounds change.
         */
        public void onSelectionUpdated(
                int selectionStart, int selectionEnd,
                @Nullable TextClassification classification) {
            if (isSelectionStarted()) {
                mSelectionStart = selectionStart;
                mSelectionEnd = selectionEnd;
                mAllowReset = false;
                mLogger.logSelectionModified(selectionStart, selectionEnd, classification, null);
            }
        }

        /**
         * Called when the selection action mode is destroyed.
         */
        public void onSelectionDestroyed() {
            mAllowReset = false;
            // Wait a few ms to see if the selection was destroyed because of a text change event.
            mDelayedLogAbandon.schedule(100 /* ms */);
        }

        /**
         * Called when an action is taken on a smart selection.
         */
        public void onSelectionAction(
                int selectionStart, int selectionEnd,
                @SelectionEvent.ActionType int action,
                @Nullable TextClassification classification) {
            if (isSelectionStarted()) {
                mAllowReset = false;
                mLogger.logSelectionAction(selectionStart, selectionEnd, action, classification);
            }
        }

        /**
         * Returns true if the current smart selection should be reset to normal selection based on
         * information that has been recorded about the original selection and the smart selection.
         * The expected UX here is to allow the user to select a word inside of the smart selection
         * on a single tap.
         */
        public boolean resetSelection(int textIndex, Editor editor) {
            final TextView textView = editor.getTextView();
            if (isSelectionStarted()
                    && mAllowReset
                    && textIndex >= mSelectionStart && textIndex <= mSelectionEnd
                    && getText(textView) instanceof Spannable) {
                mAllowReset = false;
                boolean selected = editor.selectCurrentWord();
                if (selected) {
                    mSelectionStart = editor.getTextView().getSelectionStart();
                    mSelectionEnd = editor.getTextView().getSelectionEnd();
                    mLogger.logSelectionAction(
                            textView.getSelectionStart(), textView.getSelectionEnd(),
                            SelectionEvent.ActionType.RESET, null /* classification */);
                }
                return selected;
            }
            return false;
        }

        public void onTextChanged(int start, int end, TextClassification classification) {
            if (isSelectionStarted() && start == mSelectionStart && end == mSelectionEnd) {
                onSelectionAction(start, end, SelectionEvent.ActionType.OVERTYPE, classification);
            }
        }

        private void maybeInvalidateLogger() {
            if (mLogger.isEditTextLogger() != mTextView.isTextEditable()) {
                mLogger = new SelectionMetricsLogger(mTextView);
            }
        }

        private boolean isSelectionStarted() {
            return mSelectionStart >= 0 && mSelectionEnd >= 0 && mSelectionStart != mSelectionEnd;
        }

        /** A helper for keeping track of pending abandon logging requests. */
        private final class LogAbandonRunnable implements Runnable {
            private boolean mIsPending;

            /** Schedules an abandon to be logged with the given delay. Flush if necessary. */
            void schedule(int delayMillis) {
                if (mIsPending) {
                    Log.e(LOG_TAG, "Force flushing abandon due to new scheduling request");
                    flush();
                }
                mIsPending = true;
                mTextView.postDelayed(this, delayMillis);
            }

            /** If there is a pending log request, execute it now. */
            void flush() {
                mTextView.removeCallbacks(this);
                run();
            }

            @Override
            public void run() {
                if (mIsPending) {
                    mLogger.logSelectionAction(
                            mSelectionStart, mSelectionEnd,
                            SelectionEvent.ActionType.ABANDON, null /* classification */);
                    mSelectionStart = mSelectionEnd = -1;
                    mIsPending = false;
                }
            }
        }
    }

    // TODO: Write tests
    /**
     * Metrics logging helper.
     *
     * This logger logs selection by word indices. The initial (start) single word selection is
     * logged at [0, 1) -- end index is exclusive. Other word indices are logged relative to the
     * initial single word selection.
     * e.g. New York city, NY. Suppose the initial selection is "York" in
     * "New York city, NY", then "York" is at [0, 1), "New" is at [-1, 0], and "city" is at [1, 2).
     * "New York" is at [-1, 1).
     * Part selection of a word e.g. "or" is counted as selecting the
     * entire word i.e. equivalent to "York", and each special character is counted as a word, e.g.
     * "," is at [2, 3). Whitespaces are ignored.
     */
    private static final class SelectionMetricsLogger {

        private static final String LOG_TAG = "SelectionMetricsLogger";
        private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\s+");

        private final SmartSelectionEventTracker mDelegate;
        private final boolean mEditTextLogger;
        private final BreakIterator mWordIterator;
        private int mStartIndex;
        private String mText;

        SelectionMetricsLogger(TextView textView) {
            Preconditions.checkNotNull(textView);
            final @SmartSelectionEventTracker.WidgetType int widgetType = textView.isTextEditable()
                    ? SmartSelectionEventTracker.WidgetType.EDITTEXT
                    : SmartSelectionEventTracker.WidgetType.TEXTVIEW;
            mDelegate = new SmartSelectionEventTracker(textView.getContext(), widgetType);
            mEditTextLogger = textView.isTextEditable();
            mWordIterator = BreakIterator.getWordInstance(textView.getTextLocale());
        }

        public void logSelectionStarted(CharSequence text, int index) {
            try {
                Preconditions.checkNotNull(text);
                Preconditions.checkArgumentInRange(index, 0, text.length(), "index");
                if (mText == null || !mText.contentEquals(text)) {
                    mText = text.toString();
                }
                mWordIterator.setText(mText);
                mStartIndex = index;
                mDelegate.logEvent(SelectionEvent.selectionStarted(0));
            } catch (Exception e) {
                // Avoid crashes due to logging.
                Log.d(LOG_TAG, e.getMessage());
            }
        }

        public void logSelectionModified(int start, int end,
                @Nullable TextClassification classification, @Nullable TextSelection selection) {
            try {
                Preconditions.checkArgumentInRange(start, 0, mText.length(), "start");
                Preconditions.checkArgumentInRange(end, start, mText.length(), "end");
                int[] wordIndices = getWordDelta(start, end);
                if (selection != null) {
                    mDelegate.logEvent(SelectionEvent.selectionModified(
                            wordIndices[0], wordIndices[1], selection));
                } else if (classification != null) {
                    mDelegate.logEvent(SelectionEvent.selectionModified(
                            wordIndices[0], wordIndices[1], classification));
                } else {
                    mDelegate.logEvent(SelectionEvent.selectionModified(
                            wordIndices[0], wordIndices[1]));
                }
            } catch (Exception e) {
                // Avoid crashes due to logging.
                Log.d(LOG_TAG, e.getMessage());
            }
        }

        public void logSelectionAction(
                int start, int end,
                @SelectionEvent.ActionType int action,
                @Nullable TextClassification classification) {
            try {
                Preconditions.checkArgumentInRange(start, 0, mText.length(), "start");
                Preconditions.checkArgumentInRange(end, start, mText.length(), "end");
                int[] wordIndices = getWordDelta(start, end);
                if (classification != null) {
                    mDelegate.logEvent(SelectionEvent.selectionAction(
                            wordIndices[0], wordIndices[1], action, classification));
                } else {
                    mDelegate.logEvent(SelectionEvent.selectionAction(
                            wordIndices[0], wordIndices[1], action));
                }
            } catch (Exception e) {
                // Avoid crashes due to logging.
                Log.d(LOG_TAG, e.getMessage());
            }
        }

        public boolean isEditTextLogger() {
            return mEditTextLogger;
        }

        private int[] getWordDelta(int start, int end) {
            int[] wordIndices = new int[2];

            if (start == mStartIndex) {
                wordIndices[0] = 0;
            } else if (start < mStartIndex) {
                wordIndices[0] = -countWordsForward(start);
            } else {  // start > mStartIndex
                wordIndices[0] = countWordsBackward(start);

                // For the selection start index, avoid counting a partial word backwards.
                if (!mWordIterator.isBoundary(start)
                        && !isWhitespace(
                                mWordIterator.preceding(start),
                                mWordIterator.following(start))) {
                    // We counted a partial word. Remove it.
                    wordIndices[0]--;
                }
            }

            if (end == mStartIndex) {
                wordIndices[1] = 0;
            } else if (end < mStartIndex) {
                wordIndices[1] = -countWordsForward(end);
            } else {  // end > mStartIndex
                wordIndices[1] = countWordsBackward(end);
            }

            return wordIndices;
        }

        private int countWordsBackward(int from) {
            Preconditions.checkArgument(from >= mStartIndex);
            int wordCount = 0;
            int offset = from;
            while (offset > mStartIndex) {
                int start = mWordIterator.preceding(offset);
                if (!isWhitespace(start, offset)) {
                    wordCount++;
                }
                offset = start;
            }
            return wordCount;
        }

        private int countWordsForward(int from) {
            Preconditions.checkArgument(from <= mStartIndex);
            int wordCount = 0;
            int offset = from;
            while (offset < mStartIndex) {
                int end = mWordIterator.following(offset);
                if (!isWhitespace(offset, end)) {
                    wordCount++;
                }
                offset = end;
            }
            return wordCount;
        }

        private boolean isWhitespace(int start, int end) {
            return PATTERN_WHITESPACE.matcher(mText.substring(start, end)).matches();
        }
    }

    /**
     * AsyncTask for running a query on a background thread and returning the result on the
     * UiThread. The AsyncTask times out after a specified time, returning a null result if the
     * query has not yet returned.
     */
    private static final class TextClassificationAsyncTask
            extends AsyncTask<Void, Void, SelectionResult> {

        private final long mTimeOutDuration;
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
                @NonNull TextView textView, long timeOut,
                @NonNull Supplier<SelectionResult> selectionResultSupplier,
                @NonNull Consumer<SelectionResult> selectionResultCallback) {
            super(textView != null ? textView.getHandler() : null);
            mTextView = Preconditions.checkNotNull(textView);
            mTimeOutDuration = timeOut;
            mSelectionResultSupplier = Preconditions.checkNotNull(selectionResultSupplier);
            mSelectionResultCallback = Preconditions.checkNotNull(selectionResultCallback);
            // Make a copy of the original text.
            mOriginalText = getText(mTextView).toString();
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
            result = TextUtils.equals(mOriginalText, getText(mTextView)) ? result : null;
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

        private static final int TRIM_DELTA = 120;  // characters

        private TextClassifier mTextClassifier;

        /** The original TextView text. **/
        private String mText;
        /** Start index relative to mText. */
        private int mSelectionStart;
        /** End index relative to mText. */
        private int mSelectionEnd;
        private LocaleList mLocales;

        /** Trimmed text starting from mTrimStart in mText. */
        private CharSequence mTrimmedText;
        /** Index indicating the start of mTrimmedText in mText. */
        private int mTrimStart;
        /** Start index relative to mTrimmedText */
        private int mRelativeStart;
        /** End index relative to mTrimmedText */
        private int mRelativeEnd;

        /** Information about the last classified text to avoid re-running a query. */
        private CharSequence mLastClassificationText;
        private int mLastClassificationSelectionStart;
        private int mLastClassificationSelectionEnd;
        private LocaleList mLastClassificationLocales;
        private SelectionResult mLastClassificationResult;

        /** Whether the TextClassifier has been initialized. */
        private boolean mHot;

        TextClassificationHelper(TextClassifier textClassifier,
                CharSequence text, int selectionStart, int selectionEnd, LocaleList locales) {
            init(textClassifier, text, selectionStart, selectionEnd, locales);
        }

        @UiThread
        public void init(TextClassifier textClassifier,
                CharSequence text, int selectionStart, int selectionEnd, LocaleList locales) {
            mTextClassifier = Preconditions.checkNotNull(textClassifier);
            mText = Preconditions.checkNotNull(text).toString();
            mLastClassificationText = null; // invalidate.
            Preconditions.checkArgument(selectionEnd > selectionStart);
            mSelectionStart = selectionStart;
            mSelectionEnd = selectionEnd;
            mLocales = locales;
        }

        @WorkerThread
        public SelectionResult classifyText() {
            mHot = true;
            return performClassification(null /* selection */);
        }

        @WorkerThread
        public SelectionResult suggestSelection() {
            mHot = true;
            trimText();
            final TextSelection selection = mTextClassifier.suggestSelection(
                    mTrimmedText, mRelativeStart, mRelativeEnd, mLocales);
            // Do not classify new selection boundaries if TextClassifier should be dark launched.
            if (!mTextClassifier.getSettings().isDarkLaunch()) {
                mSelectionStart = Math.max(0, selection.getSelectionStartIndex() + mTrimStart);
                mSelectionEnd = Math.min(
                        mText.length(), selection.getSelectionEndIndex() + mTrimStart);
            }
            return performClassification(selection);
        }

        /**
         * Maximum time (in milliseconds) to wait for a textclassifier result before timing out.
         */
        // TODO: Consider making this a ViewConfiguration.
        public long getTimeoutDuration() {
            if (mHot) {
                return 200;
            } else {
                // Return a slightly larger number than usual when the TextClassifier is first
                // initialized. Initialization would usually take longer than subsequent calls to
                // the TextClassifier. The impact of this on the UI is that we do not show the
                // selection handles or toolbar until after this timeout.
                return 500;
            }
        }

        private SelectionResult performClassification(@Nullable TextSelection selection) {
            if (!Objects.equals(mText, mLastClassificationText)
                    || mSelectionStart != mLastClassificationSelectionStart
                    || mSelectionEnd != mLastClassificationSelectionEnd
                    || !Objects.equals(mLocales, mLastClassificationLocales)) {

                mLastClassificationText = mText;
                mLastClassificationSelectionStart = mSelectionStart;
                mLastClassificationSelectionEnd = mSelectionEnd;
                mLastClassificationLocales = mLocales;

                trimText();
                mLastClassificationResult = new SelectionResult(
                        mSelectionStart,
                        mSelectionEnd,
                        mTextClassifier.classifyText(
                                mTrimmedText, mRelativeStart, mRelativeEnd, mLocales),
                        selection);

            }
            return mLastClassificationResult;
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
        private final TextClassification mClassification;
        @Nullable private final TextSelection mSelection;

        SelectionResult(int start, int end,
                TextClassification classification, @Nullable TextSelection selection) {
            mStart = start;
            mEnd = end;
            mClassification = Preconditions.checkNotNull(classification);
            mSelection = selection;
        }
    }

    @SelectionEvent.ActionType
    private static int getActionType(int menuItemId) {
        switch (menuItemId) {
            case TextView.ID_SELECT_ALL:
                return SelectionEvent.ActionType.SELECT_ALL;
            case TextView.ID_CUT:
                return SelectionEvent.ActionType.CUT;
            case TextView.ID_COPY:
                return SelectionEvent.ActionType.COPY;
            case TextView.ID_PASTE:  // fall through
            case TextView.ID_PASTE_AS_PLAIN_TEXT:
                return SelectionEvent.ActionType.PASTE;
            case TextView.ID_SHARE:
                return SelectionEvent.ActionType.SHARE;
            case TextView.ID_ASSIST:
                return SelectionEvent.ActionType.SMART_SHARE;
            default:
                return SelectionEvent.ActionType.OTHER;
        }
    }

    private static CharSequence getText(TextView textView) {
        // Extracts the textView's text.
        // TODO: Investigate why/when TextView.getText() is null.
        final CharSequence text = textView.getText();
        if (text != null) {
            return text;
        }
        return "";
    }
}
