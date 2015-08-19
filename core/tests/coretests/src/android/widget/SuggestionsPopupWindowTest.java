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
    public void testTextAndAppearanceInSuggestionsPopup() {
        final Activity activity = getActivity();

        final String sampleText = "abc def ghi";
        final String[] candidate = {"DEF", "Def"};
        final SuggestionSpan suggestionSpan = new SuggestionSpan(activity, candidate,
                SuggestionSpan.FLAG_AUTO_CORRECTION);
        final int spanStart = 4;
        final int spanEnd = 7;
        TextAppearanceSpan expectedSpan = new TextAppearanceSpan(activity,
                android.R.style.TextAppearance_SuggestionHighlight);
        TextPaint tmpTp = new TextPaint();
        expectedSpan.updateDrawState(tmpTp);
        final int expectedHighlightTextColor = tmpTp.getColor();
        final float expectedHighlightTextSize = tmpTp.getTextSize();

        // Create and wait until SuggestionsPopupWindow is shown.
        final EditText editText = (EditText) activity.findViewById(R.id.textview);
        final Editor editor = editText.getEditorForTesting();
        assertNotNull(editor);
        activity.runOnUiThread(new Runnable() {
            public void run() {
                SpannableStringBuilder ssb = new SpannableStringBuilder();
                ssb.append(sampleText);
                ssb.setSpan(suggestionSpan, spanStart, spanEnd, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                editText.setText(ssb);

                Selection.setSelection(editText.getText(), spanStart, spanEnd);
                editText.onTextContextMenuItem(TextView.ID_REPLACE);
            }
        });
        getInstrumentation().waitForIdleSync();

        // In this test, the SuggestionsPopupWindow looks like
        // abc def ghi
        //   ----------
        //   | *DEF*  |
        //   | *Def*  |
        //   | DELETE |
        //   ----------
        // *XX* means that XX is highlighted.
        activity.runOnUiThread(new Runnable() {
            public void run() {
                Editor.SuggestionsPopupWindow popupWindow =
                        editor.getSuggestionsPopupWindowForTesting();
                assertNotNull(popupWindow);

                ListView listView = (ListView) popupWindow.getContentViewForTesting();
                assertNotNull(listView);

                int childNum = listView.getChildCount();
                // +1 for "DELETE" command.
                assertEquals(candidate.length + 1, childNum);

                for (int i = 0; i < candidate.length; ++i) {
                    TextView textView = (TextView) listView.getChildAt(i);
                    assertNotNull(textView);

                    Spanned spanned = (Spanned) textView.getText();
                    assertNotNull(spanned);

                    // Check that the suggestion item order is kept.
                    assertEquals(candidate[i], spanned.toString());

                    // Check that the text is highlighted with correct color and text size.
                    TextAppearanceSpan[] taSpan = spanned.getSpans(0, candidate[i].length(),
                            TextAppearanceSpan.class);
                    assertEquals(1, taSpan.length);
                    TextPaint tp = new TextPaint();
                    taSpan[0].updateDrawState(tp);
                    assertEquals(expectedHighlightTextColor, tp.getColor());
                    assertEquals(expectedHighlightTextSize, tp.getTextSize());
                }
            }
        });
    }
}
