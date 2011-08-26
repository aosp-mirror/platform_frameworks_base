// Copyright 2011 Google Inc. All Rights Reserved.

package android.widget;

import android.content.Context;
import android.text.Editable;
import android.text.Selection;
import android.text.Spanned;
import android.text.style.SpellCheckSpan;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.view.textservice.SpellCheckerSession;
import android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;
import android.view.textservice.TextServicesManager;

import com.android.internal.util.ArrayUtils;

import java.util.Locale;


/**
 * Helper class for TextView. Bridge between the TextView and the Dictionnary service.
 *
 * @hide
 */
public class SpellChecker implements SpellCheckerSessionListener {
    private static final String LOG_TAG = "SpellChecker";
    private static final boolean DEBUG_SPELL_CHECK = false;
    private static final int DELAY_BEFORE_SPELL_CHECK = 400; // milliseconds

    private final TextView mTextView;

    final SpellCheckerSession spellCheckerSession;
    final int mCookie;

    // Paired arrays for the (id, spellCheckSpan) pair. mIndex is the next available position
    private int[] mIds;
    private SpellCheckSpan[] mSpellCheckSpans;
    // The actual current number of used slots in the above arrays
    private int mLength;

    private int mSpanSequenceCounter = 0;
    private Runnable mChecker;

    public SpellChecker(TextView textView) {
        mTextView = textView;

        final TextServicesManager textServicesManager = (TextServicesManager) textView.getContext().
                getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE);
        spellCheckerSession = textServicesManager.newSpellCheckerSession(
                null /* not currently used by the textServicesManager */, Locale.getDefault(),
                this, true /* means use the languages defined in Settings */);
        mCookie = hashCode();

        // Arbitrary: 4 simultaneous spell check spans. Will automatically double size on demand
        final int size = ArrayUtils.idealObjectArraySize(4);
        mIds = new int[size];
        mSpellCheckSpans = new SpellCheckSpan[size];
        mLength = 0;
    }

    public void addSpellCheckSpan(SpellCheckSpan spellCheckSpan) {
        int length = mIds.length;
        if (mLength >= length) {
            final int newSize = length * 2;
            int[] newIds = new int[newSize];
            SpellCheckSpan[] newSpellCheckSpans = new SpellCheckSpan[newSize];
            System.arraycopy(mIds, 0, newIds, 0, length);
            System.arraycopy(mSpellCheckSpans, 0, newSpellCheckSpans, 0, length);
            mIds = newIds;
            mSpellCheckSpans = newSpellCheckSpans;
        }

        mIds[mLength] = mSpanSequenceCounter++;
        mSpellCheckSpans[mLength] = spellCheckSpan;
        mLength++;

        if (DEBUG_SPELL_CHECK) {
            final Editable mText = (Editable) mTextView.getText();
            int start = mText.getSpanStart(spellCheckSpan);
            int end = mText.getSpanEnd(spellCheckSpan);
            if (start >= 0 && end >= 0) {
                Log.d(LOG_TAG, "Schedule check " + mText.subSequence(start, end));
            } else {
                Log.d(LOG_TAG, "Schedule check   EMPTY!");
            }
        }

        scheduleSpellCheck();
    }

    public void removeSpellCheckSpan(SpellCheckSpan spellCheckSpan) {
        for (int i = 0; i < mLength; i++) {
            if (mSpellCheckSpans[i] == spellCheckSpan) {
                removeAtIndex(i);
                return;
            }
        }
    }

    private void removeAtIndex(int i) {
        System.arraycopy(mIds, i + 1, mIds, i, mLength - i - 1);
        System.arraycopy(mSpellCheckSpans, i + 1, mSpellCheckSpans, i, mLength - i - 1);
        mLength--;
    }

    public void onSelectionChanged() {
        scheduleSpellCheck();
    }

    private void scheduleSpellCheck() {
        if (mLength == 0) return;
        if (mChecker != null) {
            mTextView.removeCallbacks(mChecker);
        }
        if (mChecker == null) {
            mChecker = new Runnable() {
                public void run() {
                  spellCheck();
                }
            };
        }
        mTextView.postDelayed(mChecker, DELAY_BEFORE_SPELL_CHECK);
    }

    private void spellCheck() {
        final Editable editable = (Editable) mTextView.getText();
        final int selectionStart = Selection.getSelectionStart(editable);
        final int selectionEnd = Selection.getSelectionEnd(editable);

        TextInfo[] textInfos = new TextInfo[mLength];
        int textInfosCount = 0;

        for (int i = 0; i < mLength; i++) {
            SpellCheckSpan spellCheckSpan = mSpellCheckSpans[i];

            if (spellCheckSpan.isSpellCheckInProgress()) continue;

            final int start = editable.getSpanStart(spellCheckSpan);
            final int end = editable.getSpanEnd(spellCheckSpan);

            // Do not check this word if the user is currently editing it
            if (start >= 0 && end > start && (selectionEnd < start || selectionStart > end)) {
                final String word = editable.subSequence(start, end).toString();
                spellCheckSpan.setSpellCheckInProgress();
                textInfos[textInfosCount++] = new TextInfo(word, mCookie, mIds[i]);
            }
        }

        if (textInfosCount > 0) {
            if (textInfosCount < mLength) {
                TextInfo[] textInfosCopy = new TextInfo[textInfosCount];
                System.arraycopy(textInfos, 0, textInfosCopy, 0, textInfosCount);
                textInfos = textInfosCopy;
            }
            spellCheckerSession.getSuggestions(textInfos, SuggestionSpan.SUGGESTIONS_MAX_SIZE,
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
            // Starting from the end, to limit the number of array copy while removing
            for (int j = mLength - 1; j >= 0; j--) {
                if (sequenceNumber == mIds[j]) {
                    SpellCheckSpan spellCheckSpan = mSpellCheckSpans[j];
                    final int attributes = suggestionsInfo.getSuggestionsAttributes();
                    boolean isInDictionary =
                            ((attributes & SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY) > 0);
                    boolean looksLikeTypo =
                            ((attributes & SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO) > 0);

                    if (DEBUG_SPELL_CHECK) {
                        final int start = editable.getSpanStart(spellCheckSpan);
                        final int end = editable.getSpanEnd(spellCheckSpan);
                        Log.d(LOG_TAG, "Result sequence=" + suggestionsInfo.getSequence() + " " +
                                editable.subSequence(start, end) +
                                "\t" + (isInDictionary?"IN_DICT" : "NOT_DICT") +
                                "\t" + (looksLikeTypo?"TYPO" : "NOT_TYPO"));
                    }

                    if (!isInDictionary && looksLikeTypo) {
                        String[] suggestions = getSuggestions(suggestionsInfo);
                        if (suggestions.length > 0) {
                            SuggestionSpan suggestionSpan = new SuggestionSpan(
                                    mTextView.getContext(), suggestions,
                                    SuggestionSpan.FLAG_EASY_CORRECT |
                                    SuggestionSpan.FLAG_MISSPELLED);
                            final int start = editable.getSpanStart(spellCheckSpan);
                            final int end = editable.getSpanEnd(spellCheckSpan);
                            editable.setSpan(suggestionSpan, start, end,
                                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            // TODO limit to the word rectangle region
                            mTextView.invalidate();

                            if (DEBUG_SPELL_CHECK) {
                                String suggestionsString = "";
                                for (String s : suggestions) { suggestionsString += s + "|"; }
                                Log.d(LOG_TAG, "  Suggestions for " + sequenceNumber + " " +
                                    editable.subSequence(start, end)+ "  " + suggestionsString);
                            }
                        }
                    }
                    editable.removeSpan(spellCheckSpan);
                }
            }
        }
    }

    private static String[] getSuggestions(SuggestionsInfo suggestionsInfo) {
        final int len = Math.max(0, suggestionsInfo.getSuggestionsCount());
        String[] suggestions = new String[len];
        for (int j = 0; j < len; ++j) {
            suggestions[j] = suggestionsInfo.getSuggestionAt(j);
        }
        return suggestions;
    }
}
