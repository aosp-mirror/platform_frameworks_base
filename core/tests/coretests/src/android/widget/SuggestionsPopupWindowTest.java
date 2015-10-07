/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.Activity;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.SuggestionSpan;
import android.text.style.TextAppearanceSpan;

import com.android.frameworks.coretests.R;

/**
 * SuggestionsPopupWindowTest tests.
 */
public class SuggestionsPopupWindowTest extends ActivityInstrumentationTestCase2<TextViewActivity> {

    public SuggestionsPopupWindowTest() {
        super(TextViewActivity.class);
    }

    @SmallTest
    public void testTextAppearanceInSuggestionsPopup() {
        final Activity activity = getActivity();

        final String sampleText = "abc def ghi";
        final String[] singleWordCandidates = {"DEF", "Def"};
        final SuggestionSpan singleWordSuggestionSpan = new SuggestionSpan(activity,
                singleWordCandidates, SuggestionSpan.FLAG_AUTO_CORRECTION);
        final int singleWordSpanStart = 4;
        final int singleWordSpanEnd = 7;

        final String[] multiWordCandidates = {"ABC DEF GHI", "Abc Def Ghi"};
        final SuggestionSpan multiWordSuggestionSpan = new SuggestionSpan(activity,
                multiWordCandidates, SuggestionSpan.FLAG_AUTO_CORRECTION);
        final int multiWordSpanStart = 0;
        final int multiWordSpanEnd = 11;

        TextAppearanceSpan expectedSpan = new TextAppearanceSpan(activity,
                android.R.style.TextAppearance_SuggestionHighlight);
        TextPaint tmpTp = new TextPaint();
        expectedSpan.updateDrawState(tmpTp);
        final int expectedHighlightTextColor = tmpTp.getColor();
        final float expectedHighlightTextSize = tmpTp.getTextSize();

        final EditText editText = (EditText) activity.findViewById(R.id.textview);
        final Editor editor = editText.getEditorForTesting();
        assertNotNull(editor);

        // Request to show SuggestionsPopupWindow.
        Runnable showSuggestionWindowRunner = new Runnable() {
            @Override
            public void run() {
                SpannableStringBuilder ssb = new SpannableStringBuilder();
                ssb.append(sampleText);
                ssb.setSpan(singleWordSuggestionSpan, singleWordSpanStart, singleWordSpanEnd,
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                ssb.setSpan(multiWordSuggestionSpan, multiWordSpanStart, multiWordSpanEnd,
                        Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                editText.setText(ssb);

                Selection.setSelection(editText.getText(), singleWordSpanStart, singleWordSpanEnd);
                editText.onTextContextMenuItem(TextView.ID_REPLACE);
            }
        };

        // In this test, the SuggestionsPopupWindow looks like
        //   abc def ghi
        // -----------------
        // | abc *DEF* ghi |
        // | abc *Def* ghi |
        // | *ABC DEF GHI* |
        // | *Abc Def Ghi* |
        // | DELETE        |
        // -----------------
        // *XX* means that XX is highlighted.
        Runnable popupVaridator = new Runnable() {
            @Override
            public void run() {
                Editor.SuggestionsPopupWindow popupWindow =
                        editor.getSuggestionsPopupWindowForTesting();
                assertNotNull(popupWindow);

                ListView listView = (ListView) popupWindow.getContentViewForTesting();
                assertNotNull(listView);

                int childNum = listView.getChildCount();
                // +1 for "DELETE" command.
                assertEquals(singleWordCandidates.length + multiWordCandidates.length + 1,
                        childNum);

                for (int i = 0; i < singleWordCandidates.length; ++i) {
                    TextView textView = (TextView) listView.getChildAt(i);
                    assertNotNull(textView);

                    Spanned spanned = (Spanned) textView.getText();
                    assertNotNull(spanned);

                    // Check that the suggestion item order is kept.
                    String expectedText = "abc " + singleWordCandidates[i] + " ghi";
                    assertEquals(expectedText, spanned.toString());

                    // Check that the text is highlighted with correct color and text size.
                    TextAppearanceSpan[] taSpan = spanned.getSpans(singleWordSpanStart,
                            singleWordSpanEnd, TextAppearanceSpan.class);
                    assertEquals(1, taSpan.length);
                    TextPaint tp = new TextPaint();
                    taSpan[0].updateDrawState(tp);
                    assertEquals(expectedHighlightTextColor, tp.getColor());
                    assertEquals(expectedHighlightTextSize, tp.getTextSize());

                    // Check only center word is highlighted.
                    assertEquals(singleWordSpanStart, spanned.getSpanStart(taSpan[0]));
                    assertEquals(singleWordSpanEnd, spanned.getSpanEnd(taSpan[0]));
                }

                for (int i = 0; i < multiWordCandidates.length; ++i) {
                    int indexInListView = singleWordCandidates.length + i;
                    TextView textView = (TextView) listView.getChildAt(indexInListView);
                    assertNotNull(textView);

                    Spanned spanned = (Spanned) textView.getText();
                    assertNotNull(spanned);

                    // Check that the suggestion item order is kept.
                    assertEquals(multiWordCandidates[i], spanned.toString());

                    // Check that the text is highlighted with correct color and text size.
                    TextAppearanceSpan[] taSpan = spanned.getSpans(
                            0, multiWordCandidates[i].length(), TextAppearanceSpan.class);
                    assertEquals(1, taSpan.length);
                    TextPaint tp = new TextPaint();
                    taSpan[0].updateDrawState(tp);
                    assertEquals(expectedHighlightTextColor, tp.getColor());
                    assertEquals(expectedHighlightTextSize, tp.getTextSize());

                    // Check the whole text is highlighted.
                    assertEquals(multiWordSpanStart, spanned.getSpanStart(taSpan[0]));
                    assertEquals(multiWordSpanEnd, spanned.getSpanEnd(taSpan[0]));
                }
            }
        };

        // Show the SuggestionWindow and verify the contents.
        activity.runOnUiThread(showSuggestionWindowRunner);
        getInstrumentation().waitForIdleSync();
        activity.runOnUiThread(popupVaridator);

        // Request to hide the SuggestionPopupWindow and wait until it is hidden.
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                editText.setText("");
            }
        });
        getInstrumentation().waitForIdleSync();

        // Show and verify the contents again.
        activity.runOnUiThread(showSuggestionWindowRunner);
        getInstrumentation().waitForIdleSync();
        activity.runOnUiThread(popupVaridator);
    }
}
