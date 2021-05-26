/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.annotation.Nullable;
import android.text.Editable;
import android.text.Selection;
import android.text.Spanned;
import android.text.method.WordIterator;
import android.text.style.SpellCheckSpan;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.util.Range;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionParams;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import java.text.BreakIterator;
import java.util.Locale;


/**
 * Helper class for TextView. Bridge between the TextView and the Dictionary service.
 *
 * @hide
 */
public class SpellChecker implements SpellCheckerSessionListener {
    private static final String TAG = SpellChecker.class.getSimpleName();
    private static final boolean DBG = false;

    // No more than this number of words will be parsed on each iteration to ensure a minimum
    // lock of the UI thread
    public static final int MAX_NUMBER_OF_WORDS = 50;

    // Rough estimate, such that the word iterator interval usually does not need to be shifted
    public static final int AVERAGE_WORD_LENGTH = 7;

    // When parsing, use a character window of that size. Will be shifted if needed
    public static final int WORD_ITERATOR_INTERVAL = AVERAGE_WORD_LENGTH * MAX_NUMBER_OF_WORDS;

    // Pause between each spell check to keep the UI smooth
    private final static int SPELL_PAUSE_DURATION = 400; // milliseconds

    // The maximum length of sentence.
    private static final int MAX_SENTENCE_LENGTH = WORD_ITERATOR_INTERVAL;

    private static final int USE_SPAN_RANGE = -1;

    private final TextView mTextView;

    SpellCheckerSession mSpellCheckerSession;

    final int mCookie;

    // Paired arrays for the (id, spellCheckSpan) pair. A negative id means the associated
    // SpellCheckSpan has been recycled and can be-reused.
    // Contains null SpellCheckSpans after index mLength.
    private int[] mIds;
    private SpellCheckSpan[] mSpellCheckSpans;
    // The mLength first elements of the above arrays have been initialized
    private int mLength;

    // Parsers on chunk of text, cutting text into words that will be checked
    private SpellParser[] mSpellParsers = new SpellParser[0];

    private int mSpanSequenceCounter = 0;

    private Locale mCurrentLocale;

    // Shared by all SpellParsers. Cannot be shared with TextView since it may be used
    // concurrently due to the asynchronous nature of onGetSuggestions.
    private SentenceIteratorWrapper mSentenceIterator;

    @Nullable
    private TextServicesManager mTextServicesManager;

    private Runnable mSpellRunnable;

    public SpellChecker(TextView textView) {
        mTextView = textView;

        // Arbitrary: these arrays will automatically double their sizes on demand
        final int size = 1;
        mIds = ArrayUtils.newUnpaddedIntArray(size);
        mSpellCheckSpans = new SpellCheckSpan[mIds.length];

        setLocale(mTextView.getSpellCheckerLocale());

        mCookie = hashCode();
    }

    void resetSession() {
        closeSession();

        mTextServicesManager = mTextView.getTextServicesManagerForUser();
        if (mCurrentLocale == null
                || mTextServicesManager == null
                || mTextView.length() == 0
                || !mTextServicesManager.isSpellCheckerEnabled()
                || mTextServicesManager.getCurrentSpellCheckerSubtype(true) == null) {
            mSpellCheckerSession = null;
        } else {
            int supportedAttributes = SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY
                    | SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
                    | SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR
                    | SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS;
            SpellCheckerSessionParams params = new SpellCheckerSessionParams.Builder()
                    .setLocale(mCurrentLocale)
                    .setSupportedAttributes(supportedAttributes)
                    .build();
            mSpellCheckerSession = mTextServicesManager.newSpellCheckerSession(
                    params, mTextView.getContext().getMainExecutor(), this);
        }

        // Restore SpellCheckSpans in pool
        for (int i = 0; i < mLength; i++) {
            mIds[i] = -1;
        }
        mLength = 0;

        // Remove existing misspelled SuggestionSpans
        mTextView.removeMisspelledSpans((Editable) mTextView.getText());
    }

    private void setLocale(Locale locale) {
        mCurrentLocale = locale;

        resetSession();

        if (locale != null) {
            // Change SpellParsers' sentenceIterator locale
            mSentenceIterator = new SentenceIteratorWrapper(
                    BreakIterator.getSentenceInstance(locale));
        }

        // This class is the listener for locale change: warn other locale-aware objects
        mTextView.onLocaleChanged();
    }

    /**
     * @return true if a spell checker session has successfully been created. Returns false if not,
     * for instance when spell checking has been disabled in settings.
     */
    private boolean isSessionActive() {
        return mSpellCheckerSession != null;
    }

    public void closeSession() {
        if (mSpellCheckerSession != null) {
            mSpellCheckerSession.close();
        }

        final int length = mSpellParsers.length;
        for (int i = 0; i < length; i++) {
            mSpellParsers[i].stop();
        }

        if (mSpellRunnable != null) {
            mTextView.removeCallbacks(mSpellRunnable);
        }
    }

    private int nextSpellCheckSpanIndex() {
        for (int i = 0; i < mLength; i++) {
            if (mIds[i] < 0) return i;
        }

        mIds = GrowingArrayUtils.append(mIds, mLength, 0);
        mSpellCheckSpans = GrowingArrayUtils.append(
                mSpellCheckSpans, mLength, new SpellCheckSpan());
        mLength++;
        return mLength - 1;
    }

    private void addSpellCheckSpan(Editable editable, int start, int end) {
        final int index = nextSpellCheckSpanIndex();
        SpellCheckSpan spellCheckSpan = mSpellCheckSpans[index];
        editable.setSpan(spellCheckSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spellCheckSpan.setSpellCheckInProgress(false);
        mIds[index] = mSpanSequenceCounter++;
    }

    public void onSpellCheckSpanRemoved(SpellCheckSpan spellCheckSpan) {
        // Recycle any removed SpellCheckSpan (from this code or during text edition)
        for (int i = 0; i < mLength; i++) {
            if (mSpellCheckSpans[i] == spellCheckSpan) {
                mIds[i] = -1;
                return;
            }
        }
    }

    public void onSelectionChanged() {
        spellCheck();
    }

    void onPerformSpellCheck() {
        // Triggers full content spell check.
        final int start = 0;
        final int end = mTextView.length();
        if (DBG) {
            Log.d(TAG, "performSpellCheckAroundSelection: " + start + ", " + end);
        }
        spellCheck(start, end, /* forceCheckWhenEditingWord= */ true);
    }

    public void spellCheck(int start, int end) {
        spellCheck(start, end, /* forceCheckWhenEditingWord= */ false);
    }

    /**
     * Requests to do spell check for text in the range (start, end).
     */
    public void spellCheck(int start, int end, boolean forceCheckWhenEditingWord) {
        if (DBG) {
            Log.d(TAG, "Start spell-checking: " + start + ", " + end + ", "
                    + forceCheckWhenEditingWord);
        }
        final Locale locale = mTextView.getSpellCheckerLocale();
        final boolean isSessionActive = isSessionActive();
        if (locale == null || mCurrentLocale == null || (!(mCurrentLocale.equals(locale)))) {
            setLocale(locale);
            // Re-check the entire text
            start = 0;
            end = mTextView.getText().length();
        } else {
            final boolean spellCheckerActivated =
                    mTextServicesManager != null && mTextServicesManager.isSpellCheckerEnabled();
            if (isSessionActive != spellCheckerActivated) {
                // Spell checker has been turned of or off since last spellCheck
                resetSession();
            }
        }

        if (!isSessionActive) return;

        // Find first available SpellParser from pool
        final int length = mSpellParsers.length;
        for (int i = 0; i < length; i++) {
            final SpellParser spellParser = mSpellParsers[i];
            if (spellParser.isFinished()) {
                spellParser.parse(start, end, forceCheckWhenEditingWord);
                return;
            }
        }

        if (DBG) {
            Log.d(TAG, "new spell parser.");
        }
        // No available parser found in pool, create a new one
        SpellParser[] newSpellParsers = new SpellParser[length + 1];
        System.arraycopy(mSpellParsers, 0, newSpellParsers, 0, length);
        mSpellParsers = newSpellParsers;

        SpellParser spellParser = new SpellParser();
        mSpellParsers[length] = spellParser;
        spellParser.parse(start, end, forceCheckWhenEditingWord);
    }

    private void spellCheck() {
        spellCheck(/* forceCheckWhenEditingWord= */ false);
    }

    private void spellCheck(boolean forceCheckWhenEditingWord) {
        if (mSpellCheckerSession == null) return;

        Editable editable = (Editable) mTextView.getText();
        final int selectionStart = Selection.getSelectionStart(editable);
        final int selectionEnd = Selection.getSelectionEnd(editable);

        TextInfo[] textInfos = new TextInfo[mLength];
        int textInfosCount = 0;

        if (DBG) {
            Log.d(TAG, "forceCheckWhenEditingWord=" + forceCheckWhenEditingWord
                    + ", mLength=" + mLength + ", cookie = " + mCookie
                    + ", sel start = " + selectionStart + ", sel end = " + selectionEnd);
        }

        for (int i = 0; i < mLength; i++) {
            final SpellCheckSpan spellCheckSpan = mSpellCheckSpans[i];
            if (mIds[i] < 0 || spellCheckSpan.isSpellCheckInProgress()) continue;

            final int start = editable.getSpanStart(spellCheckSpan);
            final int end = editable.getSpanEnd(spellCheckSpan);

            // Check the span if any of following conditions is met:
            // - the user is not currently editing it
            // - or `forceCheckWhenEditingWord` is true.
            final boolean isNotEditing;

            // Defer spell check when typing a word ending with a punctuation like an apostrophe
            // which could end up being a mid-word punctuation.
            if (selectionStart == end + 1
                    && WordIterator.isMidWordPunctuation(
                            mCurrentLocale, Character.codePointBefore(editable, end + 1))) {
                isNotEditing = false;
            } else if (selectionEnd <= start || selectionStart > end) {
                // Allow the overlap of the cursor and the first boundary of the spell check span
                // no to skip the spell check of the following word because the
                // following word will never be spell-checked even if the user finishes composing
                isNotEditing = true;
            } else {
                // When cursor is at the end of spell check span, allow spell check if the
                // character before cursor is a separator.
                isNotEditing = selectionStart == end
                        && selectionStart > 0
                        && isSeparator(Character.codePointBefore(editable, selectionStart));
            }
            if (start >= 0 && end > start && (forceCheckWhenEditingWord || isNotEditing)) {
                spellCheckSpan.setSpellCheckInProgress(true);
                final TextInfo textInfo = new TextInfo(editable, start, end, mCookie, mIds[i]);
                textInfos[textInfosCount++] = textInfo;
                if (DBG) {
                    Log.d(TAG, "create TextInfo: (" + i + "/" + mLength + ") text = "
                            + textInfo.getSequence() + ", cookie = " + mCookie + ", seq = "
                            + mIds[i] + ", sel start = " + selectionStart + ", sel end = "
                            + selectionEnd + ", start = " + start + ", end = " + end);
                }
            }
        }

        if (textInfosCount > 0) {
            if (textInfosCount < textInfos.length) {
                TextInfo[] textInfosCopy = new TextInfo[textInfosCount];
                System.arraycopy(textInfos, 0, textInfosCopy, 0, textInfosCount);
                textInfos = textInfosCopy;
            }

            mSpellCheckerSession.getSentenceSuggestions(
                    textInfos, SuggestionSpan.SUGGESTIONS_MAX_SIZE);
        }
    }

    private static boolean isSeparator(int codepoint) {
        final int type = Character.getType(codepoint);
        return ((1 << type) & ((1 << Character.SPACE_SEPARATOR)
                | (1 << Character.LINE_SEPARATOR)
                | (1 << Character.PARAGRAPH_SEPARATOR)
                | (1 << Character.DASH_PUNCTUATION)
                | (1 << Character.END_PUNCTUATION)
                | (1 << Character.FINAL_QUOTE_PUNCTUATION)
                | (1 << Character.INITIAL_QUOTE_PUNCTUATION)
                | (1 << Character.START_PUNCTUATION)
                | (1 << Character.OTHER_PUNCTUATION))) != 0;
    }

    private SpellCheckSpan onGetSuggestionsInternal(
            SuggestionsInfo suggestionsInfo, int offset, int length) {
        if (suggestionsInfo == null || suggestionsInfo.getCookie() != mCookie) {
            return null;
        }
        final Editable editable = (Editable) mTextView.getText();
        final int sequenceNumber = suggestionsInfo.getSequence();
        for (int k = 0; k < mLength; ++k) {
            if (sequenceNumber == mIds[k]) {
                final SpellCheckSpan spellCheckSpan = mSpellCheckSpans[k];
                final int spellCheckSpanStart = editable.getSpanStart(spellCheckSpan);
                if (spellCheckSpanStart < 0) {
                    // Skips the suggestion if the matched span has been removed.
                    return null;
                }

                final int attributes = suggestionsInfo.getSuggestionsAttributes();
                final boolean isInDictionary =
                        ((attributes & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) > 0);
                final boolean looksLikeTypo =
                        ((attributes & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) > 0);
                final boolean looksLikeGrammarError =
                        ((attributes & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR) > 0);

                // Validates the suggestions range in case the SpellCheckSpan is out-of-date but not
                // removed as expected.
                if (spellCheckSpanStart + offset + length > editable.length()) {
                    return spellCheckSpan;
                }
                //TODO: we need to change that rule for results from a sentence-level spell
                // checker that will probably be in dictionary.
                if (!isInDictionary && (looksLikeTypo || looksLikeGrammarError)) {
                    createMisspelledSuggestionSpan(
                            editable, suggestionsInfo, spellCheckSpan, offset, length);
                } else {
                    // Valid word -- isInDictionary || !looksLikeTypo
                    // Allow the spell checker to remove existing misspelled span by
                    // overwriting the span over the same place
                    final int spellCheckSpanEnd = editable.getSpanEnd(spellCheckSpan);
                    final int start;
                    final int end;
                    if (offset != USE_SPAN_RANGE && length != USE_SPAN_RANGE) {
                        start = spellCheckSpanStart + offset;
                        end = start + length;
                    } else {
                        start = spellCheckSpanStart;
                        end = spellCheckSpanEnd;
                    }
                    if (spellCheckSpanStart >= 0 && spellCheckSpanEnd > spellCheckSpanStart
                            && end > start) {
                        removeErrorSuggestionSpan(editable, start, end, RemoveReason.OBSOLETE);
                    }
                }
                return spellCheckSpan;
            }
        }
        return null;
    }

    private enum RemoveReason {
        /**
         * Indicates the previous SuggestionSpan is replaced by a new SuggestionSpan.
         */
        REPLACE,
        /**
         * Indicates the previous SuggestionSpan is removed because corresponding text is
         * considered as valid words now.
         */
        OBSOLETE,
    }

    private static void removeErrorSuggestionSpan(
            Editable editable, int start, int end, RemoveReason reason) {
        SuggestionSpan[] spans = editable.getSpans(start, end, SuggestionSpan.class);
        for (SuggestionSpan span : spans) {
            if (editable.getSpanStart(span) == start
                    && editable.getSpanEnd(span) == end
                    && (span.getFlags() & (SuggestionSpan.FLAG_MISSPELLED
                    | SuggestionSpan.FLAG_GRAMMAR_ERROR)) != 0) {
                if (DBG) {
                    Log.i(TAG, "Remove existing misspelled/grammar error span on "
                            + editable.subSequence(start, end) + ", reason: " + reason);
                }
                editable.removeSpan(span);
            }
        }
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
        final Editable editable = (Editable) mTextView.getText();
        for (int i = 0; i < results.length; ++i) {
            final SpellCheckSpan spellCheckSpan =
                    onGetSuggestionsInternal(results[i], USE_SPAN_RANGE, USE_SPAN_RANGE);
            if (spellCheckSpan != null) {
                // onSpellCheckSpanRemoved will recycle this span in the pool
                editable.removeSpan(spellCheckSpan);
            }
        }
        scheduleNewSpellCheck();
    }

    @Override
    public void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results) {
        final Editable editable = (Editable) mTextView.getText();
        for (int i = 0; i < results.length; ++i) {
            final SentenceSuggestionsInfo ssi = results[i];
            if (ssi == null) {
                continue;
            }
            SpellCheckSpan spellCheckSpan = null;
            for (int j = 0; j < ssi.getSuggestionsCount(); ++j) {
                final SuggestionsInfo suggestionsInfo = ssi.getSuggestionsInfoAt(j);
                if (suggestionsInfo == null) {
                    continue;
                }
                final int offset = ssi.getOffsetAt(j);
                final int length = ssi.getLengthAt(j);
                final SpellCheckSpan scs = onGetSuggestionsInternal(
                        suggestionsInfo, offset, length);
                if (spellCheckSpan == null && scs != null) {
                    // the spellCheckSpan is shared by all the "SuggestionsInfo"s in the same
                    // SentenceSuggestionsInfo. Removal is deferred after this loop.
                    spellCheckSpan = scs;
                }
            }
            if (spellCheckSpan != null) {
                // onSpellCheckSpanRemoved will recycle this span in the pool
                editable.removeSpan(spellCheckSpan);
            }
        }
        scheduleNewSpellCheck();
    }

    private void scheduleNewSpellCheck() {
        if (DBG) {
            Log.i(TAG, "schedule new spell check.");
        }
        if (mSpellRunnable == null) {
            mSpellRunnable = new Runnable() {
                @Override
                public void run() {
                    final int length = mSpellParsers.length;
                    for (int i = 0; i < length; i++) {
                        final SpellParser spellParser = mSpellParsers[i];
                        if (!spellParser.isFinished()) {
                            spellParser.parse();
                            break; // run one spell parser at a time to bound running time
                        }
                    }
                }
            };
        } else {
            mTextView.removeCallbacks(mSpellRunnable);
        }

        mTextView.postDelayed(mSpellRunnable, SPELL_PAUSE_DURATION);
    }

    // When calling this method, RESULT_ATTR_LOOKS_LIKE_TYPO or RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR
    // (or both) should be set in suggestionsInfo.
    private void createMisspelledSuggestionSpan(Editable editable, SuggestionsInfo suggestionsInfo,
            SpellCheckSpan spellCheckSpan, int offset, int length) {
        final int spellCheckSpanStart = editable.getSpanStart(spellCheckSpan);
        final int spellCheckSpanEnd = editable.getSpanEnd(spellCheckSpan);
        if (spellCheckSpanStart < 0 || spellCheckSpanEnd <= spellCheckSpanStart)
            return; // span was removed in the meantime

        final int start;
        final int end;
        if (offset != USE_SPAN_RANGE && length != USE_SPAN_RANGE) {
            start = spellCheckSpanStart + offset;
            end = start + length;
        } else {
            start = spellCheckSpanStart;
            end = spellCheckSpanEnd;
        }

        final int suggestionsCount = suggestionsInfo.getSuggestionsCount();
        String[] suggestions;
        if (suggestionsCount > 0) {
            suggestions = new String[suggestionsCount];
            for (int i = 0; i < suggestionsCount; i++) {
                suggestions[i] = suggestionsInfo.getSuggestionAt(i);
            }
        } else {
            suggestions = ArrayUtils.emptyArray(String.class);
        }

        final int suggestionsAttrs = suggestionsInfo.getSuggestionsAttributes();
        int flags = 0;
        if ((suggestionsAttrs & SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS) == 0) {
            flags |= SuggestionSpan.FLAG_EASY_CORRECT;
        }
        if ((suggestionsAttrs & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) != 0) {
            flags |= SuggestionSpan.FLAG_MISSPELLED;
        }
        if ((suggestionsAttrs & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR) != 0) {
            flags |= SuggestionSpan.FLAG_GRAMMAR_ERROR;
        }
        SuggestionSpan suggestionSpan =
                new SuggestionSpan(mTextView.getContext(), suggestions, flags);
        removeErrorSuggestionSpan(editable, start, end, RemoveReason.REPLACE);
        editable.setSpan(suggestionSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        mTextView.invalidateRegion(start, end, false /* No cursor involved */);
    }

    /**
     * A wrapper of sentence iterator which only processes the specified window of the given text.
     */
    private static class SentenceIteratorWrapper {
        private BreakIterator mSentenceIterator;
        private int mStartOffset;
        private int mEndOffset;

        SentenceIteratorWrapper(BreakIterator sentenceIterator) {
            mSentenceIterator = sentenceIterator;
        }

        /**
         * Set the char sequence and the text window to process.
         */
        public void setCharSequence(CharSequence sequence, int start, int end) {
            mStartOffset = Math.max(0, start);
            mEndOffset = Math.min(end, sequence.length());
            mSentenceIterator.setText(sequence.subSequence(mStartOffset, mEndOffset).toString());
        }

        /**
         * See {@link BreakIterator#preceding(int)}
         */
        public int preceding(int offset) {
            if (offset < mStartOffset) {
                return BreakIterator.DONE;
            }
            int result = mSentenceIterator.preceding(offset - mStartOffset);
            return result == BreakIterator.DONE ? BreakIterator.DONE : result + mStartOffset;
        }

        /**
         * See {@link BreakIterator#following(int)}
         */
        public int following(int offset) {
            if (offset > mEndOffset) {
                return BreakIterator.DONE;
            }
            int result = mSentenceIterator.following(offset - mStartOffset);
            return result == BreakIterator.DONE ? BreakIterator.DONE : result + mStartOffset;
        }

        /**
         * See {@link BreakIterator#isBoundary(int)}
         */
        public boolean isBoundary(int offset) {
            if (offset < mStartOffset || offset > mEndOffset) {
                return false;
            }
            return mSentenceIterator.isBoundary(offset - mStartOffset);
        }
    }

    private class SpellParser {
        private Object mRange = new Object();

        // Forces to do spell checker even user is editing the word.
        private boolean mForceCheckWhenEditingWord;

        public void parse(int start, int end, boolean forceCheckWhenEditingWord) {
            mForceCheckWhenEditingWord = forceCheckWhenEditingWord;
            final int max = mTextView.length();
            final int parseEnd;
            if (end > max) {
                Log.w(TAG, "Parse invalid region, from " + start + " to " + end);
                parseEnd = max;
            } else {
                parseEnd = end;
            }
            if (parseEnd > start) {
                setRangeSpan((Editable) mTextView.getText(), start, parseEnd);
                parse();
            }
        }

        public boolean isFinished() {
            return ((Editable) mTextView.getText()).getSpanStart(mRange) < 0;
        }

        public void stop() {
            removeRangeSpan((Editable) mTextView.getText());
            mForceCheckWhenEditingWord = false;
        }

        private void setRangeSpan(Editable editable, int start, int end) {
            if (DBG) {
                Log.d(TAG, "set next range span: " + start + ", " + end);
            }
            editable.setSpan(mRange, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        private void removeRangeSpan(Editable editable) {
            if (DBG) {
                Log.d(TAG, "Remove range span." + editable.getSpanStart(editable)
                        + editable.getSpanEnd(editable));
            }
            editable.removeSpan(mRange);
        }

        public void parse() {
            Editable editable = (Editable) mTextView.getText();
            final int textChangeStart = editable.getSpanStart(mRange);
            final int textChangeEnd = editable.getSpanEnd(mRange);

            Range<Integer> sentenceBoundary = detectSentenceBoundary(editable, textChangeStart,
                    textChangeEnd);
            int sentenceStart = sentenceBoundary.getLower();
            int sentenceEnd = sentenceBoundary.getUpper();

            if (sentenceStart == sentenceEnd) {
                if (DBG) {
                    Log.i(TAG, "No more spell check.");
                }
                stop();
                return;
            }

            boolean scheduleOtherSpellCheck = false;

            if (sentenceEnd < textChangeEnd) {
                if (DBG) {
                    Log.i(TAG, "schedule other spell check.");
                }
                // Several batches needed on that region. Cut after last previous word
                scheduleOtherSpellCheck = true;
            }
            int spellCheckEnd = sentenceEnd;
            do {
                int spellCheckStart = sentenceStart;
                boolean createSpellCheckSpan = true;
                // Cancel or merge overlapped spell check spans
                for (int i = 0; i < mLength; ++i) {
                    final SpellCheckSpan spellCheckSpan = mSpellCheckSpans[i];
                    if (mIds[i] < 0 || spellCheckSpan.isSpellCheckInProgress()) {
                        continue;
                    }
                    final int spanStart = editable.getSpanStart(spellCheckSpan);
                    final int spanEnd = editable.getSpanEnd(spellCheckSpan);
                    if (spanEnd < spellCheckStart || spellCheckEnd < spanStart) {
                        // No need to merge
                        continue;
                    }
                    if (spanStart <= spellCheckStart && spellCheckEnd <= spanEnd) {
                        // There is a completely overlapped spell check span
                        // skip this span
                        createSpellCheckSpan = false;
                        if (DBG) {
                            Log.i(TAG, "The range is overrapped. Skip spell check.");
                        }
                        break;
                    }
                    // This spellCheckSpan is replaced by the one we are creating
                    editable.removeSpan(spellCheckSpan);
                    spellCheckStart = Math.min(spanStart, spellCheckStart);
                    spellCheckEnd = Math.max(spanEnd, spellCheckEnd);
                }

                if (DBG) {
                    Log.d(TAG, "addSpellCheckSpan: "
                            + ", End = " + spellCheckEnd + ", Start = " + spellCheckStart
                            + ", next = " + scheduleOtherSpellCheck + "\n"
                            + editable.subSequence(spellCheckStart, spellCheckEnd));
                }

                // Stop spell checking when there are no characters in the range.
                if (spellCheckEnd <= spellCheckStart) {
                    Log.w(TAG, "Trying to spellcheck invalid region, from "
                            + sentenceStart + " to " + spellCheckEnd);
                    break;
                }
                if (createSpellCheckSpan) {
                    addSpellCheckSpan(editable, spellCheckStart, spellCheckEnd);
                }
            } while (false);
            sentenceStart = spellCheckEnd;
            if (scheduleOtherSpellCheck && sentenceStart != BreakIterator.DONE
                    && sentenceStart <= textChangeEnd) {
                // Update range span: start new spell check from last wordStart
                setRangeSpan(editable, sentenceStart, textChangeEnd);
            } else {
                removeRangeSpan(editable);
            }
            spellCheck(mForceCheckWhenEditingWord);
        }

        private <T> void removeSpansAt(Editable editable, int offset, T[] spans) {
            final int length = spans.length;
            for (int i = 0; i < length; i++) {
                final T span = spans[i];
                final int start = editable.getSpanStart(span);
                if (start > offset) continue;
                final int end = editable.getSpanEnd(span);
                if (end < offset) continue;
                editable.removeSpan(span);
            }
        }
    }

    private Range<Integer> detectSentenceBoundary(CharSequence sequence,
            int textChangeStart, int textChangeEnd) {
        // Only process a substring of the full text due to performance concern.
        final int iteratorWindowStart = findSeparator(sequence,
                Math.max(0, textChangeStart - MAX_SENTENCE_LENGTH),
                Math.max(0, textChangeStart - 2 * MAX_SENTENCE_LENGTH));
        final int iteratorWindowEnd = findSeparator(sequence,
                Math.min(textChangeStart + 2 * MAX_SENTENCE_LENGTH, textChangeEnd),
                Math.min(textChangeStart + 3 * MAX_SENTENCE_LENGTH, sequence.length()));
        if (DBG) {
            Log.d(TAG, "Set iterator window as [" + iteratorWindowStart + ", " + iteratorWindowEnd
                    + ").");
        }
        mSentenceIterator.setCharSequence(sequence, iteratorWindowStart, iteratorWindowEnd);

        // Detect the offset of sentence begin/end on the substring.
        int sentenceStart = mSentenceIterator.isBoundary(textChangeStart) ? textChangeStart
                : mSentenceIterator.preceding(textChangeStart);
        int sentenceEnd = mSentenceIterator.following(sentenceStart);
        if (sentenceEnd == BreakIterator.DONE) {
            sentenceEnd = iteratorWindowEnd;
        }
        if (DBG) {
            if (sentenceStart != sentenceEnd) {
                Log.d(TAG, "Sentence detected [" + sentenceStart + ", " + sentenceEnd + ").");
            }
        }

        if (sentenceEnd - sentenceStart <= MAX_SENTENCE_LENGTH) {
            // Add more sentences until the MAX_SENTENCE_LENGTH limitation is reached.
            while (sentenceEnd < textChangeEnd) {
                int nextEnd = mSentenceIterator.following(sentenceEnd);
                if (nextEnd == BreakIterator.DONE
                        || nextEnd - sentenceStart > MAX_SENTENCE_LENGTH) {
                    break;
                }
                sentenceEnd = nextEnd;
            }
        } else {
            // If the sentence containing `textChangeStart` is longer than MAX_SENTENCE_LENGTH,
            // the sentence will be sliced into sub-sentences of about MAX_SENTENCE_LENGTH
            // characters each. This is done by processing the unchecked part of that sentence :
            //   [textChangeStart, sentenceEnd)
            //
            // - If the `uncheckedLength` is bigger than MAX_SENTENCE_LENGTH, then check the
            //   [textChangeStart, textChangeStart + MAX_SENTENCE_LENGTH), and leave the rest
            //   part for the next check.
            //
            // - If the `uncheckedLength` is smaller than or equal to MAX_SENTENCE_LENGTH,
            //   then check [sentenceEnd - MAX_SENTENCE_LENGTH, sentenceEnd).
            //
            // The offset should be rounded up to word boundary.
            int uncheckedLength = sentenceEnd - textChangeStart;
            if (uncheckedLength > MAX_SENTENCE_LENGTH) {
                sentenceEnd = findSeparator(sequence, textChangeStart + MAX_SENTENCE_LENGTH,
                        sentenceEnd);
                sentenceStart = roundUpToWordStart(sequence, textChangeStart, sentenceStart);
            } else {
                sentenceStart = roundUpToWordStart(sequence, sentenceEnd - MAX_SENTENCE_LENGTH,
                        sentenceStart);
            }
        }
        return new Range<>(sentenceStart, Math.max(sentenceStart, sentenceEnd));
    }

    private int roundUpToWordStart(CharSequence sequence, int position, int frontBoundary) {
        if (isSeparator(sequence.charAt(position))) {
            return position;
        }
        int separator = findSeparator(sequence, position, frontBoundary);
        return separator != frontBoundary ? separator + 1 : frontBoundary;
    }

    /**
     * Search the range [start, end) of sequence and returns the position of the first separator.
     * If end is smaller than start, do a reverse search.
     * Returns `end` if no separator is found.
     */
    private static int findSeparator(CharSequence sequence, int start, int end) {
        final int step = start < end ? 1 : -1;
        for (int i = start; i != end; i += step) {
            if (isSeparator(sequence.charAt(i))) {
                return i;
            }
        }
        return end;
    }

    public static boolean haveWordBoundariesChanged(final Editable editable, final int start,
            final int end, final int spanStart, final int spanEnd) {
        final boolean haveWordBoundariesChanged;
        if (spanEnd != start && spanStart != end) {
            haveWordBoundariesChanged = true;
            if (DBG) {
                Log.d(TAG, "(1) Text inside the span has been modified. Remove.");
            }
        } else if (spanEnd == start && start < editable.length()) {
            final int codePoint = Character.codePointAt(editable, start);
            haveWordBoundariesChanged = Character.isLetterOrDigit(codePoint);
            if (DBG) {
                Log.d(TAG, "(2) Characters have been appended to the spanned text. "
                        + (haveWordBoundariesChanged ? "Remove.<" : "Keep. <") + (char)(codePoint)
                        + ">, " + editable + ", " + editable.subSequence(spanStart, spanEnd) + ", "
                        + start);
            }
        } else if (spanStart == end && end > 0) {
            final int codePoint = Character.codePointBefore(editable, end);
            haveWordBoundariesChanged = Character.isLetterOrDigit(codePoint);
            if (DBG) {
                Log.d(TAG, "(3) Characters have been prepended to the spanned text. "
                        + (haveWordBoundariesChanged ? "Remove.<" : "Keep.<") + (char)(codePoint)
                        + ">, " + editable + ", " + editable.subSequence(spanStart, spanEnd) + ", "
                        + end);
            }
        } else {
            if (DBG) {
                Log.d(TAG, "(4) Characters adjacent to the spanned text were deleted. Keep.");
            }
            haveWordBoundariesChanged = false;
        }
        return haveWordBoundariesChanged;
    }
}
