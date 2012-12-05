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

import android.content.Context;
import android.text.Editable;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.WordIterator;
import android.text.style.SpellCheckSpan;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.util.LruCache;
import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;

import com.android.internal.util.ArrayUtils;

import java.text.BreakIterator;
import java.util.Locale;


/**
 * Helper class for TextView. Bridge between the TextView and the Dictionnary service.
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

    private static final int MIN_SENTENCE_LENGTH = 50;

    private static final int USE_SPAN_RANGE = -1;

    private final TextView mTextView;

    SpellCheckerSession mSpellCheckerSession;
    // We assume that the sentence level spell check will always provide better results than words.
    // Although word SC has a sequential option.
    private boolean mIsSentenceSpellCheckSupported;
    final int mCookie;

    // Paired arrays for the (id, spellCheckSpan) pair. A negative id means the associated
    // SpellCheckSpan has been recycled and can be-reused.
    // Contains null SpellCheckSpans after index mLength.
    private int[] mIds;
    private SpellCheckSpan[] mSpellCheckSpans;
    // The mLength first elements of the above arrays have been initialized
    private int mLength;

    // Parsers on chunck of text, cutting text into words that will be checked
    private SpellParser[] mSpellParsers = new SpellParser[0];

    private int mSpanSequenceCounter = 0;

    private Locale mCurrentLocale;

    // Shared by all SpellParsers. Cannot be shared with TextView since it may be used
    // concurrently due to the asynchronous nature of onGetSuggestions.
    private WordIterator mWordIterator;

    private TextServicesManager mTextServicesManager;

    private Runnable mSpellRunnable;

    private static final int SUGGESTION_SPAN_CACHE_SIZE = 10;
    private final LruCache<Long, SuggestionSpan> mSuggestionSpanCache =
            new LruCache<Long, SuggestionSpan>(SUGGESTION_SPAN_CACHE_SIZE);

    public SpellChecker(TextView textView) {
        mTextView = textView;

        // Arbitrary: these arrays will automatically double their sizes on demand
        final int size = ArrayUtils.idealObjectArraySize(1);
        mIds = new int[size];
        mSpellCheckSpans = new SpellCheckSpan[size];

        setLocale(mTextView.getSpellCheckerLocale());

        mCookie = hashCode();
    }

    private void resetSession() {
        closeSession();

        mTextServicesManager = (TextServicesManager) mTextView.getContext().
                getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        if (!mTextServicesManager.isSpellCheckerEnabled()
                || mCurrentLocale == null
                || mTextServicesManager.getCurrentSpellCheckerSubtype(true) == null) {
            mSpellCheckerSession = null;
        } else {
            mSpellCheckerSession = mTextServicesManager.newSpellCheckerSession(
                    null /* Bundle not currently used by the textServicesManager */,
                    mCurrentLocale, this,
                    false /* means any available languages from current spell checker */);
            mIsSentenceSpellCheckSupported = true;
        }

        // Restore SpellCheckSpans in pool
        for (int i = 0; i < mLength; i++) {
            mIds[i] = -1;
        }
        mLength = 0;

        // Remove existing misspelled SuggestionSpans
        mTextView.removeMisspelledSpans((Editable) mTextView.getText());
        mSuggestionSpanCache.evictAll();
    }

    private void setLocale(Locale locale) {
        mCurrentLocale = locale;

        resetSession();

        if (locale != null) {
            // Change SpellParsers' wordIterator locale
            mWordIterator = new WordIterator(locale);
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

        if (mLength == mSpellCheckSpans.length) {
            final int newSize = mLength * 2;
            int[] newIds = new int[newSize];
            SpellCheckSpan[] newSpellCheckSpans = new SpellCheckSpan[newSize];
            System.arraycopy(mIds, 0, newIds, 0, mLength);
            System.arraycopy(mSpellCheckSpans, 0, newSpellCheckSpans, 0, mLength);
            mIds = newIds;
            mSpellCheckSpans = newSpellCheckSpans;
        }

        mSpellCheckSpans[mLength] = new SpellCheckSpan();
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

    public void spellCheck(int start, int end) {
        if (DBG) {
            Log.d(TAG, "Start spell-checking: " + start + ", " + end);
        }
        final Locale locale = mTextView.getSpellCheckerLocale();
        final boolean isSessionActive = isSessionActive();
        if (locale == null || mCurrentLocale == null || (!(mCurrentLocale.equals(locale)))) {
            setLocale(locale);
            // Re-check the entire text
            start = 0;
            end = mTextView.getText().length();
        } else {
            final boolean spellCheckerActivated = mTextServicesManager.isSpellCheckerEnabled();
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
                spellParser.parse(start, end);
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
        spellParser.parse(start, end);
    }

    private void spellCheck() {
        if (mSpellCheckerSession == null) return;

        Editable editable = (Editable) mTextView.getText();
        final int selectionStart = Selection.getSelectionStart(editable);
        final int selectionEnd = Selection.getSelectionEnd(editable);

        TextInfo[] textInfos = new TextInfo[mLength];
        int textInfosCount = 0;

        for (int i = 0; i < mLength; i++) {
            final SpellCheckSpan spellCheckSpan = mSpellCheckSpans[i];
            if (mIds[i] < 0 || spellCheckSpan.isSpellCheckInProgress()) continue;

            final int start = editable.getSpanStart(spellCheckSpan);
            final int end = editable.getSpanEnd(spellCheckSpan);

            // Do not check this word if the user is currently editing it
            final boolean isEditing;
            if (mIsSentenceSpellCheckSupported) {
                // Allow the overlap of the cursor and the first boundary of the spell check span
                // no to skip the spell check of the following word because the
                // following word will never be spell-checked even if the user finishes composing
                isEditing = selectionEnd <= start || selectionStart > end;
            } else {
                isEditing = selectionEnd < start || selectionStart > end;
            }
            if (start >= 0 && end > start && isEditing) {
                final String word = (editable instanceof SpannableStringBuilder) ?
                        ((SpannableStringBuilder) editable).substring(start, end) :
                        editable.subSequence(start, end).toString();
                spellCheckSpan.setSpellCheckInProgress(true);
                textInfos[textInfosCount++] = new TextInfo(word, mCookie, mIds[i]);
                if (DBG) {
                    Log.d(TAG, "create TextInfo: (" + i + "/" + mLength + ")" + word
                            + ", cookie = " + mCookie + ", seq = "
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

            if (mIsSentenceSpellCheckSupported) {
                mSpellCheckerSession.getSentenceSuggestions(
                        textInfos, SuggestionSpan.SUGGESTIONS_MAX_SIZE);
            } else {
                mSpellCheckerSession.getSuggestions(textInfos, SuggestionSpan.SUGGESTIONS_MAX_SIZE,
                        false /* TODO Set sequentialWords to true for initial spell check */);
            }
        }
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
                final int attributes = suggestionsInfo.getSuggestionsAttributes();
                final boolean isInDictionary =
                        ((attributes & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) > 0);
                final boolean looksLikeTypo =
                        ((attributes & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) > 0);

                final SpellCheckSpan spellCheckSpan = mSpellCheckSpans[k];
                //TODO: we need to change that rule for results from a sentence-level spell
                // checker that will probably be in dictionary.
                if (!isInDictionary && looksLikeTypo) {
                    createMisspelledSuggestionSpan(
                            editable, suggestionsInfo, spellCheckSpan, offset, length);
                } else {
                    // Valid word -- isInDictionary || !looksLikeTypo
                    if (mIsSentenceSpellCheckSupported) {
                        // Allow the spell checker to remove existing misspelled span by
                        // overwriting the span over the same place
                        final int spellCheckSpanStart = editable.getSpanStart(spellCheckSpan);
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
                            final Long key = Long.valueOf(TextUtils.packRangeInLong(start, end));
                            final SuggestionSpan tempSuggestionSpan = mSuggestionSpanCache.get(key);
                            if (tempSuggestionSpan != null) {
                                if (DBG) {
                                    Log.i(TAG, "Remove existing misspelled span. "
                                            + editable.subSequence(start, end));
                                }
                                editable.removeSpan(tempSuggestionSpan);
                                mSuggestionSpanCache.remove(key);
                            }
                        }
                    }
                }
                return spellCheckSpan;
            }
        }
        return null;
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

        SuggestionSpan suggestionSpan = new SuggestionSpan(mTextView.getContext(), suggestions,
                SuggestionSpan.FLAG_EASY_CORRECT | SuggestionSpan.FLAG_MISSPELLED);
        // TODO: Remove mIsSentenceSpellCheckSupported by extracting an interface
        // to share the logic of word level spell checker and sentence level spell checker
        if (mIsSentenceSpellCheckSupported) {
            final Long key = Long.valueOf(TextUtils.packRangeInLong(start, end));
            final SuggestionSpan tempSuggestionSpan = mSuggestionSpanCache.get(key);
            if (tempSuggestionSpan != null) {
                if (DBG) {
                    Log.i(TAG, "Cached span on the same position is cleard. "
                            + editable.subSequence(start, end));
                }
                editable.removeSpan(tempSuggestionSpan);
            }
            mSuggestionSpanCache.put(key, suggestionSpan);
        }
        editable.setSpan(suggestionSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        mTextView.invalidateRegion(start, end, false /* No cursor involved */);
    }

    private class SpellParser {
        private Object mRange = new Object();

        public void parse(int start, int end) {
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
            // Iterate over the newly added text and schedule new SpellCheckSpans
            final int start;
            if (mIsSentenceSpellCheckSupported) {
                // TODO: Find the start position of the sentence.
                // Set span with the context
                start =  Math.max(
                        0, editable.getSpanStart(mRange) - MIN_SENTENCE_LENGTH);
            } else {
                start = editable.getSpanStart(mRange);
            }

            final int end = editable.getSpanEnd(mRange);

            int wordIteratorWindowEnd = Math.min(end, start + WORD_ITERATOR_INTERVAL);
            mWordIterator.setCharSequence(editable, start, wordIteratorWindowEnd);

            // Move back to the beginning of the current word, if any
            int wordStart = mWordIterator.preceding(start);
            int wordEnd;
            if (wordStart == BreakIterator.DONE) {
                wordEnd = mWordIterator.following(start);
                if (wordEnd != BreakIterator.DONE) {
                    wordStart = mWordIterator.getBeginning(wordEnd);
                }
            } else {
                wordEnd = mWordIterator.getEnd(wordStart);
            }
            if (wordEnd == BreakIterator.DONE) {
                if (DBG) {
                    Log.i(TAG, "No more spell check.");
                }
                removeRangeSpan(editable);
                return;
            }

            // We need to expand by one character because we want to include the spans that
            // end/start at position start/end respectively.
            SpellCheckSpan[] spellCheckSpans = editable.getSpans(start - 1, end + 1,
                    SpellCheckSpan.class);
            SuggestionSpan[] suggestionSpans = editable.getSpans(start - 1, end + 1,
                    SuggestionSpan.class);

            int wordCount = 0;
            boolean scheduleOtherSpellCheck = false;

            if (mIsSentenceSpellCheckSupported) {
                if (wordIteratorWindowEnd < end) {
                    if (DBG) {
                        Log.i(TAG, "schedule other spell check.");
                    }
                    // Several batches needed on that region. Cut after last previous word
                    scheduleOtherSpellCheck = true;
                }
                int spellCheckEnd = mWordIterator.preceding(wordIteratorWindowEnd);
                boolean correct = spellCheckEnd != BreakIterator.DONE;
                if (correct) {
                    spellCheckEnd = mWordIterator.getEnd(spellCheckEnd);
                    correct = spellCheckEnd != BreakIterator.DONE;
                }
                if (!correct) {
                    if (DBG) {
                        Log.i(TAG, "Incorrect range span.");
                    }
                    removeRangeSpan(editable);
                    return;
                }
                do {
                    // TODO: Find the start position of the sentence.
                    int spellCheckStart = wordStart;
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
                    if (spellCheckEnd < start) {
                        break;
                    }
                    if (spellCheckEnd <= spellCheckStart) {
                        Log.w(TAG, "Trying to spellcheck invalid region, from "
                                + start + " to " + end);
                        break;
                    }
                    if (createSpellCheckSpan) {
                        addSpellCheckSpan(editable, spellCheckStart, spellCheckEnd);
                    }
                } while (false);
                wordStart = spellCheckEnd;
            } else {
                while (wordStart <= end) {
                    if (wordEnd >= start && wordEnd > wordStart) {
                        if (wordCount >= MAX_NUMBER_OF_WORDS) {
                            scheduleOtherSpellCheck = true;
                            break;
                        }
                        // A new word has been created across the interval boundaries with this
                        // edit. The previous spans (that ended on start / started on end) are
                        // not valid anymore and must be removed.
                        if (wordStart < start && wordEnd > start) {
                            removeSpansAt(editable, start, spellCheckSpans);
                            removeSpansAt(editable, start, suggestionSpans);
                        }

                        if (wordStart < end && wordEnd > end) {
                            removeSpansAt(editable, end, spellCheckSpans);
                            removeSpansAt(editable, end, suggestionSpans);
                        }

                        // Do not create new boundary spans if they already exist
                        boolean createSpellCheckSpan = true;
                        if (wordEnd == start) {
                            for (int i = 0; i < spellCheckSpans.length; i++) {
                                final int spanEnd = editable.getSpanEnd(spellCheckSpans[i]);
                                if (spanEnd == start) {
                                    createSpellCheckSpan = false;
                                    break;
                                }
                            }
                        }

                        if (wordStart == end) {
                            for (int i = 0; i < spellCheckSpans.length; i++) {
                                final int spanStart = editable.getSpanStart(spellCheckSpans[i]);
                                if (spanStart == end) {
                                    createSpellCheckSpan = false;
                                    break;
                                }
                            }
                        }

                        if (createSpellCheckSpan) {
                            addSpellCheckSpan(editable, wordStart, wordEnd);
                        }
                        wordCount++;
                    }

                    // iterate word by word
                    int originalWordEnd = wordEnd;
                    wordEnd = mWordIterator.following(wordEnd);
                    if ((wordIteratorWindowEnd < end) &&
                            (wordEnd == BreakIterator.DONE || wordEnd >= wordIteratorWindowEnd)) {
                        wordIteratorWindowEnd =
                                Math.min(end, originalWordEnd + WORD_ITERATOR_INTERVAL);
                        mWordIterator.setCharSequence(
                                editable, originalWordEnd, wordIteratorWindowEnd);
                        wordEnd = mWordIterator.following(originalWordEnd);
                    }
                    if (wordEnd == BreakIterator.DONE) break;
                    wordStart = mWordIterator.getBeginning(wordEnd);
                    if (wordStart == BreakIterator.DONE) {
                        break;
                    }
                }
            }

            if (scheduleOtherSpellCheck) {
                // Update range span: start new spell check from last wordStart
                setRangeSpan(editable, wordStart, end);
            } else {
                removeRangeSpan(editable);
            }

            spellCheck();
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
}
