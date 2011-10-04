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
import android.text.style.SpellCheckSpan;
import android.text.style.SuggestionSpan;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;

import com.android.internal.util.ArrayUtils;


/**
 * Helper class for TextView. Bridge between the TextView and the Dictionnary service.
 *
 * @hide
 */
public class SpellChecker implements SpellCheckerSessionListener {

    private final TextView mTextView;

    final SpellCheckerSession mSpellCheckerSession;
    final int mCookie;

    // Paired arrays for the (id, spellCheckSpan) pair. A negative id means the associated
    // SpellCheckSpan has been recycled and can be-reused.
    // May contain null SpellCheckSpans after a given index.
    private int[] mIds;
    private SpellCheckSpan[] mSpellCheckSpans;
    // The mLength first elements of the above arrays have been initialized
    private int mLength;

    private int mSpanSequenceCounter = 0;

    public SpellChecker(TextView textView) {
        mTextView = textView;

        final TextServicesManager textServicesManager = (TextServicesManager) textView.getContext().
                getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        mSpellCheckerSession = textServicesManager.newSpellCheckerSession(
                null /* not currently used by the textServicesManager */,
                null /* null locale means use the languages defined in Settings
                        if referToSpellCheckerLanguageSettings is true */,
                this, true /* means use the languages defined in Settings */);
        mCookie = hashCode();

        // Arbitrary: 4 simultaneous spell check spans. Will automatically double size on demand
        final int size = ArrayUtils.idealObjectArraySize(1);
        mIds = new int[size];
        mSpellCheckSpans = new SpellCheckSpan[size];
        mLength = 0;
    }

    /**
     * @return true if a spell checker session has successfully been created. Returns false if not,
     * for instance when spell checking has been disabled in settings.
     */
    public boolean isSessionActive() {
        return mSpellCheckerSession != null;
    }

    public void closeSession() {
        if (mSpellCheckerSession != null) {
            mSpellCheckerSession.close();
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

    public void addSpellCheckSpan(int wordStart, int wordEnd) {
        final int index = nextSpellCheckSpanIndex();
        ((Editable) mTextView.getText()).setSpan(mSpellCheckSpans[index], wordStart, wordEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    public void spellCheck() {
        if (mSpellCheckerSession == null) return;

        final Editable editable = (Editable) mTextView.getText();
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
            if (textInfosCount < mLength) {
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
        final Editable editable = (Editable) mTextView.getText();
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
    }

    private void createMisspelledSuggestionSpan(Editable editable, SuggestionsInfo suggestionsInfo,
            SpellCheckSpan spellCheckSpan) {
        final int start = editable.getSpanStart(spellCheckSpan);
        final int end = editable.getSpanEnd(spellCheckSpan);

        // Other suggestion spans may exist on that region, with identical suggestions, filter
        // them out to avoid duplicates. First, filter suggestion spans on that exact region.
        SuggestionSpan[] suggestionSpans = editable.getSpans(start, end, SuggestionSpan.class);
        final int length = suggestionSpans.length;
        for (int i = 0; i < length; i++) {
            final int spanStart = editable.getSpanStart(suggestionSpans[i]);
            final int spanEnd = editable.getSpanEnd(suggestionSpans[i]);
            if (spanStart != start || spanEnd != end) {
                suggestionSpans[i] = null;
                break;
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

        // TODO limit to the word rectangle region
        mTextView.invalidate();
    }
}
