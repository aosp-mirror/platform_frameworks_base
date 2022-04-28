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

import static android.widget.espresso.DragHandleUtils.onHandleView;
import static android.widget.espresso.SuggestionsPopupwindowUtils.assertSuggestionsPopupContainsItem;
import static android.widget.espresso.SuggestionsPopupwindowUtils.assertSuggestionsPopupIsDisplayed;
import static android.widget.espresso.SuggestionsPopupwindowUtils.assertSuggestionsPopupIsNotDisplayed;
import static android.widget.espresso.SuggestionsPopupwindowUtils.clickSuggestionsPopupItem;
import static android.widget.espresso.SuggestionsPopupwindowUtils.onSuggestionsPopup;
import static android.widget.espresso.TextViewActions.clickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.longPressOnTextAtIndex;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.clearText;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.withDecorView;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.res.TypedArray;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.SuggestionSpan;
import android.text.style.TextAppearanceSpan;

import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;

import com.android.frameworks.coretests.R;

import org.junit.Rule;
import org.junit.Test;

/**
 * SuggestionsPopupWindowTest tests.
 *
 * TODO: Add tests for when there are no suggestions
 */
@SmallTest
public class SuggestionsPopupWindowTest {

    @Rule
    public final ActivityTestRule<TextViewActivity> mActivityRule =
            new ActivityTestRule<>(TextViewActivity.class);
    private final FloatingToolbarUtils mToolbar = new FloatingToolbarUtils();

    private TextViewActivity getActivity() {
        return mActivityRule.getActivity();
    }

    private void setSuggestionSpan(SuggestionSpan span, int start, int end) {
        final TextView textView = getActivity().findViewById(R.id.textview);
        textView.post(
                () -> {
                    final Spannable text = (Spannable) textView.getText();
                    text.setSpan(span, start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                    Selection.setSelection(text, (start + end) / 2);
                });
        getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testOnTextContextMenuItem() {
        final String text = "abc def ghi";

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(text));

        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                new String[]{"DEF", "Def"}, SuggestionSpan.FLAG_AUTO_CORRECTION);
        setSuggestionSpan(suggestionSpan, text.indexOf('d'), text.indexOf('f') + 1);

        final TextView textView = getActivity().findViewById(R.id.textview);
        textView.post(() -> textView.onTextContextMenuItem(TextView.ID_REPLACE));
        getInstrumentation().waitForIdleSync();

        assertSuggestionsPopupIsDisplayed();
    }

    @Test
    public void testSelectionActionMode() {
        final String text = "abc def ghi";

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(text));

        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                new String[]{"DEF", "Def"}, SuggestionSpan.FLAG_AUTO_CORRECTION);
        setSuggestionSpan(suggestionSpan, text.indexOf('d'), text.indexOf('f') + 1);

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('e')));
        mToolbar.clickFloatingToolbarOverflowItem(
                getActivity().getString(com.android.internal.R.string.replace));

        assertSuggestionsPopupIsDisplayed();
    }

    @Test
    public void testInsertionActionMode() throws Throwable {
        final String text = "abc def ghi";

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(text));
        Thread.sleep(500);

        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                new String[]{"DEF", "Def"}, SuggestionSpan.FLAG_AUTO_CORRECTION);
        setSuggestionSpan(suggestionSpan, text.indexOf('d'), text.indexOf('f') + 1);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.indexOf('e')));
        onHandleView(com.android.internal.R.id.insertion_handle).perform(click());
        mToolbar.clickFloatingToolbarItem(
                getActivity().getString(com.android.internal.R.string.replace));

        assertSuggestionsPopupIsDisplayed();
    }

    private void showSuggestionsPopup() {
        final TextView textView = getActivity().findViewById(R.id.textview);
        textView.post(() -> textView.onTextContextMenuItem(TextView.ID_REPLACE));
        getInstrumentation().waitForIdleSync();
        assertSuggestionsPopupIsDisplayed();
    }

    @Test
    public void testSuggestionItems() {
        final String text = "abc def ghi";

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(text));

        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                new String[]{"DEF", "Def"}, SuggestionSpan.FLAG_AUTO_CORRECTION);
        setSuggestionSpan(suggestionSpan, text.indexOf('d'), text.indexOf('f') + 1);

        showSuggestionsPopup();

        assertSuggestionsPopupIsDisplayed();
        assertSuggestionsPopupContainsItem("DEF");
        assertSuggestionsPopupContainsItem("Def");
        assertSuggestionsPopupContainsItem(
                getActivity().getString(com.android.internal.R.string.delete));

        // Select an item.
        clickSuggestionsPopupItem("DEF");
        assertSuggestionsPopupIsNotDisplayed();
        onView(withId(R.id.textview)).check(matches(withText("abc DEF ghi")));

        showSuggestionsPopup();
        assertSuggestionsPopupIsDisplayed();
        assertSuggestionsPopupContainsItem("def");
        assertSuggestionsPopupContainsItem("Def");
        assertSuggestionsPopupContainsItem(
                getActivity().getString(com.android.internal.R.string.delete));

        // Delete
        clickSuggestionsPopupItem(
                getActivity().getString(com.android.internal.R.string.delete));
        assertSuggestionsPopupIsNotDisplayed();
        onView(withId(R.id.textview)).check(matches(withText("abc ghi")));
    }

    @Test
    public void testMisspelled() {
        final String text = "abc def ghi";

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(text));

        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                new String[]{"DEF", "Def"}, SuggestionSpan.FLAG_MISSPELLED);
        setSuggestionSpan(suggestionSpan, text.indexOf('d'), text.indexOf('f') + 1);

        showSuggestionsPopup();

        assertSuggestionsPopupIsDisplayed();
        assertSuggestionsPopupContainsItem("DEF");
        assertSuggestionsPopupContainsItem("Def");
        assertSuggestionsPopupContainsItem(
                getActivity().getString(com.android.internal.R.string.addToDictionary));
        assertSuggestionsPopupContainsItem(
                getActivity().getString(com.android.internal.R.string.delete));

        // Click "Add to dictionary".
        clickSuggestionsPopupItem(
                getActivity().getString(com.android.internal.R.string.addToDictionary));
        // TODO: Check if add to dictionary dialog is displayed.
    }

    @Test
    public void testEasyCorrect() {
        final String text = "abc def ghi";

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(text));

        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                new String[]{"DEF", "Def"},
                SuggestionSpan.FLAG_EASY_CORRECT | SuggestionSpan.FLAG_MISSPELLED);
        setSuggestionSpan(suggestionSpan, text.indexOf('d'), text.indexOf('f') + 1);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.indexOf('e')));

        assertSuggestionsPopupIsDisplayed();
        assertSuggestionsPopupContainsItem("DEF");
        assertSuggestionsPopupContainsItem("Def");
        assertSuggestionsPopupContainsItem(
                getActivity().getString(com.android.internal.R.string.delete));

        // Select an item.
        clickSuggestionsPopupItem("DEF");
        assertSuggestionsPopupIsNotDisplayed();
        onView(withId(R.id.textview)).check(matches(withText("abc DEF ghi")));

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.indexOf('e')));
        assertSuggestionsPopupIsNotDisplayed();

        showSuggestionsPopup();
        assertSuggestionsPopupIsDisplayed();
        assertSuggestionsPopupContainsItem("def");
        assertSuggestionsPopupContainsItem("Def");
        assertSuggestionsPopupContainsItem(
                getActivity().getString(com.android.internal.R.string.delete));
    }

    @Test
    public void testTextAppearanceInSuggestionsPopup() {
        final String text = "abc def ghi";

        final String[] singleWordCandidates = {"DEF", "Def"};
        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                singleWordCandidates, SuggestionSpan.FLAG_MISSPELLED);
        final String[] multiWordCandidates = {"ABC DEF GHI", "Abc Def Ghi"};
        final SuggestionSpan multiWordSuggestionSpan = new SuggestionSpan(getActivity(),
                multiWordCandidates, SuggestionSpan.FLAG_MISSPELLED);

        final TypedArray array =
                getActivity().obtainStyledAttributes(com.android.internal.R.styleable.Theme);
        final int id = array.getResourceId(
                com.android.internal.R.styleable.Theme_textEditSuggestionHighlightStyle, 0);
        array.recycle();
        final TextAppearanceSpan expectedSpan = new TextAppearanceSpan(getActivity(), id);
        final TextPaint tmpTp = new TextPaint();
        expectedSpan.updateDrawState(tmpTp);
        final int expectedHighlightTextColor = tmpTp.getColor();
        final float expectedHighlightTextSize = tmpTp.getTextSize();
        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);

        // In this test, the SuggestionsPopupWindow looks like
        //   abc def ghi
        // -----------------
        // | abc *DEF* ghi |
        // | abc *Def* ghi |
        // | *ABC DEF GHI* |
        // | *Abc Def Ghi* |
        // -----------------
        // | DELETE        |
        // -----------------
        // *XX* means that XX is highlighted.
        for (int i = 0; i < 2; i++) {
            onView(withId(R.id.textview)).perform(click());
            onView(withId(R.id.textview)).perform(replaceText(text));
            setSuggestionSpan(suggestionSpan, text.indexOf('d'), text.indexOf('f') + 1);
            setSuggestionSpan(multiWordSuggestionSpan, 0, text.length());

            showSuggestionsPopup();
            assertSuggestionsPopupIsDisplayed();
            assertSuggestionsPopupContainsItem("abc DEF ghi");
            assertSuggestionsPopupContainsItem("abc Def ghi");
            assertSuggestionsPopupContainsItem("ABC DEF GHI");
            assertSuggestionsPopupContainsItem("Abc Def Ghi");
            assertSuggestionsPopupContainsItem(
                    getActivity().getString(com.android.internal.R.string.delete));

            onSuggestionsPopup().check((view, e) -> {
                final ListView listView = view.findViewById(
                        com.android.internal.R.id.suggestionContainer);
                assertNotNull(listView);
                final int childNum = listView.getChildCount();
                assertEquals(singleWordCandidates.length + multiWordCandidates.length, childNum);

                for (int j = 0; j < childNum; j++) {
                    final TextView suggestion = (TextView) listView.getChildAt(j);
                    assertNotNull(suggestion);
                    final Spanned spanned = (Spanned) suggestion.getText();
                    assertNotNull(spanned);

                    // Check that the suggestion item order is kept.
                    final String expectedText;
                    if (j < singleWordCandidates.length) {
                        expectedText = "abc " + singleWordCandidates[j] + " ghi";
                    } else {
                        expectedText = multiWordCandidates[j - singleWordCandidates.length];
                    }
                    assertEquals(expectedText, spanned.toString());

                    // Check that the text is highlighted with correct color and text size.
                    final TextAppearanceSpan[] taSpan = spanned.getSpans(
                            text.indexOf('d'), text.indexOf('f') + 1, TextAppearanceSpan.class);
                    assertEquals(1, taSpan.length);
                    TextPaint tp = new TextPaint();
                    taSpan[0].updateDrawState(tp);
                    assertEquals(expectedHighlightTextColor, tp.getColor());
                    assertEquals(expectedHighlightTextSize, tp.getTextSize(), 0f);

                    // Check the correct part of the text is highlighted.
                    final int expectedStart;
                    final int expectedEnd;
                    if (j < singleWordCandidates.length) {
                        expectedStart = text.indexOf('d');
                        expectedEnd = text.indexOf('f') + 1;
                    } else {
                        expectedStart = 0;
                        expectedEnd = text.length();
                    }
                    assertEquals(expectedStart, spanned.getSpanStart(taSpan[0]));
                    assertEquals(expectedEnd, spanned.getSpanEnd(taSpan[0]));
                }
            });
            pressBack();
            onView(withId(R.id.textview))
                    .inRoot(withDecorView(is(getActivity().getWindow().getDecorView())))
                    .perform(clearText());
        }
    }

    @Test
    public void testCursorVisibility() {
        final TextView textView = getActivity().findViewById(R.id.textview);
        final String text = "abc";

        assertTrue(textView.isCursorVisible());

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(text));
        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                new String[]{"ABC"}, SuggestionSpan.FLAG_AUTO_CORRECTION);
        setSuggestionSpan(suggestionSpan, text.indexOf('a'), text.indexOf('c') + 1);
        showSuggestionsPopup();

        assertSuggestionsPopupIsDisplayed();
        assertSuggestionsPopupContainsItem("ABC");
        assertFalse(textView.isCursorVisible());

        // Delete an item.
        clickSuggestionsPopupItem(
                getActivity().getString(com.android.internal.R.string.delete));
        assertSuggestionsPopupIsNotDisplayed();
        assertTrue(textView.isCursorVisible());
    }

    @Test
    public void testCursorVisibilityWhenImeConsumesInput() {
        final TextView textView = getActivity().findViewById(R.id.textview);
        final String text = "abc";

        assertTrue(textView.isCursorVisible());

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(text));
        setImeConsumesInputWithExpect(textView, true /* imeConsumesInput */,
                false /* expectedCursorVisibility */);
        final SuggestionSpan suggestionSpan = new SuggestionSpan(getActivity(),
                new String[]{"ABC"}, SuggestionSpan.FLAG_AUTO_CORRECTION);
        setSuggestionSpan(suggestionSpan, text.indexOf('a'), text.indexOf('c') + 1);
        showSuggestionsPopup();

        assertSuggestionsPopupIsDisplayed();
        assertSuggestionsPopupContainsItem("ABC");
        assertFalse(textView.isCursorVisible());

        // Delete an item.
        clickSuggestionsPopupItem(
                getActivity().getString(com.android.internal.R.string.delete));
        assertSuggestionsPopupIsNotDisplayed();
        assertFalse(textView.isCursorVisible());

        // Set IME not consumes input, cursor should be back to visible.
        setImeConsumesInputWithExpect(textView, false /* imeConsumesInput */,
                true /* expectedCursorVisibility */);
    }

    private void setImeConsumesInputWithExpect(
            final TextView textView, boolean imeConsumesInput, boolean expectedCursorVisibility) {
        textView.post(() -> textView.setImeConsumesInput(imeConsumesInput));
        getInstrumentation().waitForIdleSync();
        if (expectedCursorVisibility) {
            assertTrue(textView.isCursorVisible());
        } else {
            assertFalse(textView.isCursorVisible());
        }
    }
}
