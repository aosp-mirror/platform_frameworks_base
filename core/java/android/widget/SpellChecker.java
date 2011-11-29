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
import android.text.Spanned;
import android.text.method.WordIterator;
import android.text.style.SpellCheckSpan;
import android.text.style.SuggestionSpan;
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

    private final static int MAX_SPELL_BATCH_SIZE = 50;

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

    // Parsers on chunck of text, cutting text into words that will be checked
    private SpellParser[] mSpellParsers = new SpellParser[0];

    private int mSpanSequenceCounter = 0;

    private Locale mCurrentLocale;

    // Shared by all SpellParsers. Cannot be shared with TextView since it may be used
    // concurrently due to the asynchronous nature of onGetSuggestions.
    private WordIterator mWordIterator;

    public SpellChecker(TextView textView) {
        mTextView = textView;

        // Arbitrary: these arrays will automatically double their sizes on demand
        final int size = ArrayUtils.idealObjectArraySize(1);
        mIds = new int[size];
        mSpellCheckSpans = new SpellCheckSpan[size];

        setLocale(mTextView.getTextServicesLocale());

        mCookie = hashCode();
    }

    private void setLocale(Locale locale) {
        closeSession();

        final TextServicesManager textServicesManager = (TextServicesManager)
                mTextView.getContext().getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        if (!textServicesManager.isSpellCheckerEnabled()) {
            mSpellCheckerSession = null;
        } else {
            mSpellCheckerSession = textServicesManager.newSpellCheckerSession(
                    null /* Bundle not currently used by the textServicesManager */,
                    locale, this,
                    false /* means any available languages from current spell checker */);
        }
        mCurrentLocale = locale;

        // Restore SpellCheckSpans in pool
        for (int i = 0; i < mLength; i++) {
            mSpellCheckSpans[i].setSpellCheckInProgress(false);
            mIds[i] = -1;
        }
        mLength = 0;

        // Change SpellParsers' wordIterator locale
        mWordIterator = new WordIterator(locale);

        // Remove existing misspelled SuggestionSpans
        mTextView.removeMisspelledSpans((Editable) mTextView.getText());

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
            mSpellParsers[i].finish();
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
        editable.setSpan(mSpellCheckSpans[index], start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mIds[index] = mSpanSequenceCounter++;
    }

    public void removeSpellCheckSpan(SpellCheckSpan spellCheckSpan) {
        for (int i = 0; i < mLength; i++) {
            if (mSpellCheckSpans[i] == spellCheckSpan) {
                mSpellCheckSpans[i].setSpellCheckInProgress(false);
                mIds[i] = -1;
                return;
            }
        }
    }

    public void onSelectionChanged() {
        spellCheck();
    }

    public void spellCheck(int start, int end) {
        final Locale locale = mTextView.getTextServicesLocale();
        if (mCurrentLocale == null || (!(mCurrentLocale.equals(locale)))) {
            setLocale(locale);
            // Re-check the entire text
            start = 0;
            end = mTextView.getText().length();
        }

        if (!isSessionActive()) return;

        final int length = mSpellParsers.length;
        for (int i = 0; i < length; i++) {
            final SpellParser spellParser = mSpellParsers[i];
            if (spellParser.isFinished()) {
                spellParser.init(start, end);
                spellParser.parse();
                return;
            }
        }

        // No available parser found in pool, create a new one
        SpellParser[] newSpellParsers = new SpellParser[length + 1];
        System.arraycopy(mSpellParsers, 0, newSpellParsers, 0, length);
        mSpellParsers = newSpellParsers;

        SpellParser spellParser = new SpellParser();
        mSpellParsers[length] = spellParser;
        spellParser.init(start, end);
        spellParser.parse();
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
            if (spellCheckSpan.isSpellCheckInProgress()) continue;

            final int start = editable.getSpanStart(spellCheckSpan);
            final int end = editable.getSpanEnd(spellCheckSpan);

            // Do not check this word if the user is currently editing it
            if (start >= 0 && end > start && (selectionEnd < start || selectionStart > end)) {
                final String word = editable.subSequence(start, end).toString();
                spellCheckSpan.setSpellCheckInProgress(true);
                textInfos[textInfosCount++] = new TextInfo(word, mCookie, mIds[i]);
            }
        }

        if (textInfosCount > 0) {
            if (textInfosCount < textInfos.length) {
                TextInfo[] textInfosCopy = new TextInfo[textInfosCount];
                System.arraycopy(textInfos, 0, textInfosCopy, 0, textInfosCount);
                textInfos = textInfosCopy;
            }
            mSpellCheckerSession.getSuggestions(textInfos, SuggestionSpan.SUGGESTIONS_MAX_SIZE,
                    false /* TODO Set sequentialWords to true for initial spell check */);
        }
    }

    @Override
    public void onGetSuggestions(SuggestionsInfo[] results) {
        Editable editable = (Editable) mTextView.getText();

        for (int i = 0; i < results.length; i++) {
            SuggestionsInfo suggestionsInfo = results[i];
            if (suggestionsInfo.getCookie() != mCookie) continue;
            final int sequenceNumber = suggestionsInfo.getSequence();

            for (int j = 0; j < mLength; j++) {
                if (sequenceNumber == mIds[j]) {
                    final int attributes = suggestionsInfo.getSuggestionsAttributes();
                    boolean isInDictionary =
                            ((attributes & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) > 0);
                    boolean looksLikeTypo =
                            ((attributes & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) > 0);

                    SpellCheckSpan spellCheckSpan = mSpellCheckSpans[j];

                    if (!isInDictionary && looksLikeTypo) {
                        createMisspelledSuggestionSpan(editable, suggestionsInfo, spellCheckSpan);
                    }

                    editable.removeSpan(spellCheckSpan);
                    break;
                }
            }
        }

        final int length = mSpellParsers.length;
        for (int i = 0; i < length; i++) {
            final SpellParser spellParser = mSpellParsers[i];
            if (!spellParser.isFinished()) {
                spellParser.parse();
            }
        }
    }

    private void createMisspelledSuggestionSpan(Editable editable, SuggestionsInfo suggestionsInfo,
            SpellCheckSpan spellCheckSpan) {
        final int start = editable.getSpanStart(spellCheckSpan);
        final int end = editable.getSpanEnd(spellCheckSpan);
        if (start < 0 || end <= start) return; // span was removed in the meantime

        // Other suggestion spans may exist on that region, with identical suggestions, filter
        // them out to avoid duplicates.
        SuggestionSpan[] suggestionSpans = editable.getSpans(start, end, SuggestionSpan.class);
        final int length = suggestionSpans.length;
        for (int i = 0; i < length; i++) {
            final int spanStart = editable.getSpanStart(suggestionSpans[i]);
            final int spanEnd = editable.getSpanEnd(suggestionSpans[i]);
            if (spanStart != start || spanEnd != end) {
                // Nulled (to avoid new array allocation) if not on that exact same region
                suggestionSpans[i] = null;
            }
        }

        final int suggestionsCount = suggestionsInfo.getSuggestionsCount();
        String[] suggestions;
        if (suggestionsCount <= 0) {
            // A negative suggestion count is possible
            suggestions = ArrayUtils.emptyArray(String.class);
        } else {
            int numberOfSuggestions = 0;
            suggestions = new String[suggestionsCount];

            for (int i = 0; i < suggestionsCount; i++) {
                final String spellSuggestion = suggestionsInfo.getSuggestionAt(i);
                if (spellSuggestion == null) break;
                boolean suggestionFound = false;

                for (int j = 0; j < length && !suggestionFound; j++) {
                    if (suggestionSpans[j] == null) break;

                    String[] suggests = suggestionSpans[j].getSuggestions();
                    for (int k = 0; k < suggests.length; k++) {
                        if (spellSuggestion.equals(suggests[k])) {
                            // The suggestion is already provided by an other SuggestionSpan
                            suggestionFound = true;
                            break;
                        }
                    }
                }

                if (!suggestionFound) {
                    suggestions[numberOfSuggestions++] = spellSuggestion;
                }
            }

            if (numberOfSuggestions != suggestionsCount) {
                String[] newSuggestions = new String[numberOfSuggestions];
                System.arraycopy(suggestions, 0, newSuggestions, 0, numberOfSuggestions);
                suggestions = newSuggestions;
            }
        }

        SuggestionSpan suggestionSpan = new SuggestionSpan(mTextView.getContext(), suggestions,
                SuggestionSpan.FLAG_EASY_CORRECT | SuggestionSpan.FLAG_MISSPELLED);
        editable.setSpan(suggestionSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        mTextView.invalidateRegion(start, end);
    }

    private class SpellParser {
        private Object mRange = new Object();

        public void init(int start, int end) {
            ((Editable) mTextView.getText()).setSpan(mRange, start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        public void finish() {
            ((Editable) mTextView.getText()).removeSpan(mRange);
        }

        public boolean isFinished() {
            return ((Editable) mTextView.getText()).getSpanStart(mRange) < 0;
        }

        public void parse() {
            Editable editable = (Editable) mTextView.getText();
            // Iterate over the newly added text and schedule new SpellCheckSpans
            final int start = editable.getSpanStart(mRange);
            final int end = editable.getSpanEnd(mRange);
            mWordIterator.setCharSequence(editable, start, end);

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
                editable.removeSpan(mRange);
                return;
            }

            // We need to expand by one character because we want to include the spans that
            // end/start at position start/end respectively.
            SpellCheckSpan[] spellCheckSpans = editable.getSpans(start - 1, end + 1,
                    SpellCheckSpan.class);
            SuggestionSpan[] suggestionSpans = editable.getSpans(start - 1, end + 1,
                    SuggestionSpan.class);

            int nbWordsChecked = 0;
            boolean scheduleOtherSpellCheck = false;

            while (wordStart <= end) {
                if (wordEnd >= start && wordEnd > wordStart) {
                    // A new word has been created across the interval boundaries with this edit.
                    // Previous spans (ended on start / started on end) removed, not valid anymore
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
                        if (nbWordsChecked == MAX_SPELL_BATCH_SIZE) {
                            scheduleOtherSpellCheck = true;
                            break;
                        }
                        addSpellCheckSpan(editable, wordStart, wordEnd);
                        nbWordsChecked++;
                    }
                }

                // iterate word by word
                wordEnd = mWordIterator.following(wordEnd);
                if (wordEnd == BreakIterator.DONE) break;
                wordStart = mWordIterator.getBeginning(wordEnd);
                if (wordStart == BreakIterator.DONE) {
                    break;
                }
            }

            if (scheduleOtherSpellCheck) {
                editable.setSpan(mRange, wordStart, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                editable.removeSpan(mRange);
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
