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
 * limitations under the License
 */

package android.widget;

import static android.widget.espresso.CustomViewActions.longPressAtRelativeCoordinates;
import static android.widget.espresso.DragHandleUtils.assertNoSelectionHandles;
import static android.widget.espresso.DragHandleUtils.onHandleView;
import static android.widget.espresso.TextViewActions.clickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.doubleTapAndDragOnText;
import static android.widget.espresso.TextViewActions.doubleClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.dragHandle;
import static android.widget.espresso.TextViewActions.Handle;
import static android.widget.espresso.TextViewActions.longPressAndDragOnText;
import static android.widget.espresso.TextViewActions.longPressOnTextAtIndex;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerAtIndex;
import static android.widget.espresso.TextViewAssertions.hasSelection;
import static android.widget.espresso.TextViewAssertions.doesNotHaveStyledText;
import static android.widget.espresso.FloatingToolbarEspressoUtils.assertFloatingToolbarItemIndex;
import static android.widget.espresso.FloatingToolbarEspressoUtils.assertFloatingToolbarIsDisplayed;
import static android.widget.espresso.FloatingToolbarEspressoUtils.assertFloatingToolbarIsNotDisplayed;
import static android.widget.espresso.FloatingToolbarEspressoUtils.assertFloatingToolbarContainsItem;
import static android.widget.espresso.FloatingToolbarEspressoUtils.assertFloatingToolbarDoesNotContainItem;
import static android.widget.espresso.FloatingToolbarEspressoUtils.clickFloatingToolbarItem;
import static android.widget.espresso.FloatingToolbarEspressoUtils.sleepForFloatingToolbarPopup;
import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.longClick;
import static android.support.test.espresso.action.ViewActions.pressKey;
import static android.support.test.espresso.action.ViewActions.replaceText;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.support.test.espresso.NoMatchingViewException;
import android.support.test.espresso.ViewAssertion;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.widget.espresso.CustomViewActions.RelativeCoordinatesProvider;

import android.support.test.espresso.action.EspressoKey;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.text.Selection;
import android.text.Spannable;
import android.text.InputType;
import android.view.KeyEvent;

import com.android.frameworks.coretests.R;

/**
 * Tests the TextView widget from an Activity
 */
@MediumTest
public class TextViewActivityTest extends ActivityInstrumentationTestCase2<TextViewActivity>{

    public TextViewActivityTest() {
        super(TextViewActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        getActivity().getSystemService(TextClassificationManager.class)
                .setTextClassifier(TextClassifier.NO_OP);
    }

    public void testTypedTextIsOnScreen() throws Exception {
        final String helloWorld = "Hello world!";
        // We use replaceText instead of typeTextIntoFocusedView to input text to avoid
        // unintentional interactions with software keyboard.
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));

        onView(withId(R.id.textview)).check(matches(withText(helloWorld)));
    }

    public void testPositionCursorAtTextAtIndex() throws Exception {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(helloWorld.indexOf("world")));

        // Delete text at specified index and see if we got the right one.
        onView(withId(R.id.textview)).perform(pressKey(KeyEvent.KEYCODE_FORWARD_DEL));
        onView(withId(R.id.textview)).check(matches(withText("Hello orld!")));
    }

    public void testPositionCursorAtTextAtIndex_arabic() throws Exception {
        // Arabic text. The expected cursorable boundary is
        // | \u0623 \u064F | \u067A | \u0633 \u0652 |
        final String text = "\u0623\u064F\u067A\u0633\u0652";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(0));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(1));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(anyOf(is(0), is(2))));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(2));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(2));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(3));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(3));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(4));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(anyOf(is(3), is(5))));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(5));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(5));
    }

    public void testPositionCursorAtTextAtIndex_devanagari() throws Exception {
        // Devanagari text. The expected cursorable boundary is | \u0915 \u093E |
        final String text = "\u0915\u093E";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(0));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(1));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(anyOf(is(0), is(2))));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(2));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(2));
    }

    public void testLongPressToSelect() throws Exception {
        final String helloWorld = "Hello Kirk!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                longPressOnTextAtIndex(helloWorld.indexOf("Kirk")));

        onView(withId(R.id.textview)).check(hasSelection("Kirk"));
    }

    public void testLongPressEmptySpace() throws Exception {
        final String helloWorld = "Hello big round sun!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        // Move cursor somewhere else
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(helloWorld.indexOf("big")));
        // Long-press at end of line.
        onView(withId(R.id.textview)).perform(longPressAtRelativeCoordinates(
                RelativeCoordinatesProvider.HorizontalReference.RIGHT, -5,
                RelativeCoordinatesProvider.VerticalReference.CENTER, 0));

        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(helloWorld.length()));
    }

    public void testLongPressAndDragToSelect() throws Exception {
        final String helloWorld = "Hello little handsome boy!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                longPressAndDragOnText(helloWorld.indexOf("little"), helloWorld.indexOf(" boy!")));

        onView(withId(R.id.textview)).check(hasSelection("little handsome"));
    }

    public void testLongPressAndDragToSelect_emoji() throws Exception {
        final String text = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02\uD83D\uDE03";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(longPressAndDragOnText(4, 6));
        onView(withId(R.id.textview)).check(hasSelection("\uD83D\uDE02"));

        onView(withId(R.id.textview)).perform(click());

        onView(withId(R.id.textview)).perform(longPressAndDragOnText(4, 2));
        onView(withId(R.id.textview)).check(hasSelection("\uD83D\uDE01"));
    }

    public void testDragAndDrop() throws Exception {
        final String text = "abc def ghi.";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf("e")));

        onView(withId(R.id.textview)).perform(
                longPressAndDragOnText(text.indexOf("e"), text.length()));

        onView(withId(R.id.textview)).check(matches(withText("abc ghi.def")));
        onView(withId(R.id.textview)).check(hasSelection(""));
        assertNoSelectionHandles();
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex("abc ghi.def".length()));

        // Test undo returns to the original state.
        onView(withId(R.id.textview)).perform(pressKey(
                (new EspressoKey.Builder()).withCtrlPressed(true).withKeyCode(KeyEvent.KEYCODE_Z)
                        .build()));
        onView(withId(R.id.textview)).check(matches(withText(text)));
    }

    public void testDoubleTapToSelect() throws Exception {
        final String helloWorld = "Hello SuetYi!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));

        onView(withId(R.id.textview)).perform(
                doubleClickOnTextAtIndex(helloWorld.indexOf("SuetYi")));

        onView(withId(R.id.textview)).check(hasSelection("SuetYi"));
    }

    public void testDoubleTapAndDragToSelect() throws Exception {
        final String helloWorld = "Hello young beautiful girl!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf("young"), helloWorld.indexOf(" girl!")));

        onView(withId(R.id.textview)).check(hasSelection("young beautiful"));
    }

    public void testDoubleTapAndDragToSelect_multiLine() throws Exception {
        final String helloWorld = "abcd\n" + "efg\n" + "hijklm\n" + "nop";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf("m"), helloWorld.indexOf("a")));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijklm"));
    }

    public void testSelectBackwordsByTouch() throws Exception {
        final String helloWorld = "Hello king of the Jungle!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf(" Jungle!"), helloWorld.indexOf("king")));

        onView(withId(R.id.textview)).check(hasSelection("king of the"));
    }

    public void testToolbarAppearsAfterSelection() throws Exception {
        final String text = "Toolbar appears after selection.";
        assertFloatingToolbarIsNotDisplayed();
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(
                longPressOnTextAtIndex(text.indexOf("appears")));

        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        final String text2 = "Toolbar disappears after typing text.";
        onView(withId(R.id.textview)).perform(replaceText(text2));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsNotDisplayed();
    }

    public void testToolbarAppearsAfterSelection_withFirstStringLtrAlgorithmAndRtlHint()
            throws Exception {
        // after the hint layout change, the floating toolbar was not visible in the case below
        // this test tests that the floating toolbar is displayed on the screen and is visible to
        // user.
        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        textView.post(new Runnable() {
            @Override
            public void run() {
                textView.setTextDirection(TextView.TEXT_DIRECTION_FIRST_STRONG_LTR);
                textView.setInputType(InputType.TYPE_CLASS_TEXT);
                textView.setSingleLine(true);
                textView.setHint("الروبوت");
            }
        });
        getInstrumentation().waitForIdleSync();

        onView(withId(R.id.textview)).perform(replaceText("test"));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(1));
        clickFloatingToolbarItem(
                getActivity().getString(com.android.internal.R.string.cut));
        onView(withId(R.id.textview)).perform(longClick());
        sleepForFloatingToolbarPopup();

        assertFloatingToolbarIsDisplayed();
    }

    public void testToolbarAndInsertionHandle() throws Exception {
        final String text = "text";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        assertFloatingToolbarIsNotDisplayed();

        onHandleView(com.android.internal.R.id.insertion_handle).perform(click());
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        assertFloatingToolbarContainsItem(
                getActivity().getString(com.android.internal.R.string.selectAll));
        assertFloatingToolbarDoesNotContainItem(
                getActivity().getString(com.android.internal.R.string.copy));
        assertFloatingToolbarDoesNotContainItem(
                getActivity().getString(com.android.internal.R.string.cut));
    }

    public void testToolbarAndSelectionHandle() throws Exception {
        final String text = "abcd efg hijk";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf("f")));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        assertFloatingToolbarContainsItem(
                getActivity().getString(com.android.internal.R.string.selectAll));
        assertFloatingToolbarContainsItem(
                getActivity().getString(com.android.internal.R.string.copy));
        assertFloatingToolbarContainsItem(
                getActivity().getString(com.android.internal.R.string.cut));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.length()));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        assertFloatingToolbarDoesNotContainItem(
                getActivity().getString(com.android.internal.R.string.selectAll));
        assertFloatingToolbarContainsItem(
                getActivity().getString(com.android.internal.R.string.copy));
        assertFloatingToolbarContainsItem(
                getActivity().getString(com.android.internal.R.string.cut));
    }

    public void testInsertionHandle() throws Exception {
        final String text = "abcd efg hijk ";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("a")));

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("f")));
    }

    public void testInsertionHandle_multiLine() throws Exception {
        final String text = "abcd\n" + "efg\n" + "hijk\n";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("a")));

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("f")));
    }

    public void testSelectionHandles() throws Exception {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(replaceText(text));

        assertNoSelectionHandles();

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('k') + 1));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg hijk"));
    }

    public void testSelectionHandles_bidi() throws Exception {
        final String text = "abc \u0621\u0622\u0623 def";
        onView(withId(R.id.textview)).perform(replaceText(text));

        assertNoSelectionHandles();

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('\u0622')));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));

        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('\u0623'),
                        false));
        onView(withId(R.id.textview)).check(hasSelection("\u0623"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('\u0621'),
                        false));
        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("abc \u0621\u0622\u0623"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.length()));
        onView(withId(R.id.textview)).check(hasSelection("abc \u0621\u0622\u0623 def"));
    }

    public void testSelectionHandles_multiLine() throws Exception {
        final String text = "abcd\n" + "efg\n" + "hijk\n" + "lmn\n" + "opqr";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('e')));
        onView(withId(R.id.textview)).check(hasSelection("efg\nhijk"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijk"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('n') + 1));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijk\nlmn"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('r') + 1));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijk\nlmn\nopqr"));
    }

    public void testSelectionHandles_multiLine_rtl() throws Exception {
        // Arabic text.
        final String text = "\u062A\u062B\u062C\n" + "\u062D\u062E\u062F\n"
                + "\u0630\u0631\u0632\n" + "\u0633\u0634\u0635\n" + "\u0636\u0637\u0638\n"
                + "\u0639\u063A\u063B";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('\u0634')));

        final TextView textView = (TextView)getActivity().findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('\u062E')));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf('\u062D'), text.indexOf('\u0635') + 1)));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('\u062A')));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf('\u062A'), text.indexOf('\u0635') + 1)));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('\u0638')));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf('\u062A'), text.indexOf('\u0638') + 1)));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('\u063B')));
        onView(withId(R.id.textview)).check(hasSelection(text));
    }


    public void testSelectionHandles_doesNotPassAnotherHandle() throws Exception {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('l')));
        onView(withId(R.id.textview)).check(hasSelection("g"));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("e"));
    }

    public void testSelectionHandles_doesNotPassAnotherHandle_multiLine() throws Exception {
        final String text = "abcd\n" + "efg\n" + "hijk\n" + "lmn\n" + "opqr";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('r') + 1));
        onView(withId(R.id.textview)).check(hasSelection("k"));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("h"));
    }

    public void testSelectionHandles_snapToWordBoundary() throws Exception {
        final String text = "abcd efg hijk lmn opqr";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('d') + 1));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));


        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('c')));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg hijk"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('d')));
        onView(withId(R.id.textview)).check(hasSelection("d efg hijk"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('b')));
        onView(withId(R.id.textview)).check(hasSelection("bcd efg hijk"));

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('n')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('o')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('q')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn opqr"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('p')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn o"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('r')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn opq"));
    }

    public void testSelectionHandles_snapToWordBoundary_multiLine() throws Exception {
        final String text = "abcd efg\n" + "hijk lmn\n" + "opqr stu";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('m')));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('c')));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg\nhijk lmn"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('g')));
        onView(withId(R.id.textview)).check(hasSelection("g\nhijk lmn"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('m')));
        onView(withId(R.id.textview)).check(hasSelection("lmn"));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('u')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn\nopqr stu"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('p')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn\no"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('i')));
        onView(withId(R.id.textview)).check(hasSelection("hijk"));
    }

    public void testSetSelectionAndActionMode() throws Exception {
        final String text = "abc def";
        onView(withId(R.id.textview)).perform(replaceText(text));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        assertFloatingToolbarIsNotDisplayed();
        textView.post(() -> Selection.setSelection((Spannable) textView.getText(), 0, 3));
        getInstrumentation().waitForIdleSync();
        sleepForFloatingToolbarPopup();
        // Don't automatically start action mode.
        assertFloatingToolbarIsNotDisplayed();
        // Make sure that "Select All" is included in the selection action mode when the entire text
        // is not selected.
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('e')));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        // Changing the selection range by API should not interrupt the selection action mode.
        textView.post(() -> Selection.setSelection((Spannable) textView.getText(), 0, 3));
        getInstrumentation().waitForIdleSync();
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertFloatingToolbarContainsItem(
                getActivity().getString(com.android.internal.R.string.selectAll));
        // Make sure that "Select All" is no longer included when the entire text is selected by
        // API.
        textView.post(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, text.length()));
        getInstrumentation().waitForIdleSync();
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertFloatingToolbarDoesNotContainItem(
                getActivity().getString(com.android.internal.R.string.selectAll));
        // Make sure that shrinking the selection range to cursor (an empty range) by API
        // terminates selection action mode and does not trigger the insertion action mode.
        textView.post(() -> Selection.setSelection((Spannable) textView.getText(), 0));
        getInstrumentation().waitForIdleSync();
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsNotDisplayed();
        // Make sure that user click can trigger the insertion action mode.
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onHandleView(com.android.internal.R.id.insertion_handle).perform(click());
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        // Make sure that an existing insertion action mode keeps alive after the insertion point is
        // moved by API.
        textView.post(() -> Selection.setSelection((Spannable) textView.getText(), 0));
        getInstrumentation().waitForIdleSync();
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertFloatingToolbarDoesNotContainItem(
                getActivity().getString(com.android.internal.R.string.copy));
        // Make sure that selection action mode is started after selection is created by API when
        // insertion action mode is active.
        textView.post(
                () -> Selection.setSelection((Spannable) textView.getText(), 1, text.length()));
        getInstrumentation().waitForIdleSync();
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertFloatingToolbarContainsItem(
                getActivity().getString(com.android.internal.R.string.copy));
    }

    public void testTransientState() throws Exception {
        final String text = "abc def";
        onView(withId(R.id.textview)).perform(replaceText(text));

        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        assertFalse(textView.hasTransientState());

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('b')));
        // hasTransientState should return true when user generated selection is active.
        assertTrue(textView.hasTransientState());
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.indexOf('d')));
        // hasTransientState should return false as the selection has been cleared.
        assertFalse(textView.hasTransientState());
        textView.post(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, text.length()));
        getInstrumentation().waitForIdleSync();
        // hasTransientState should return false when selection is created by API.
        assertFalse(textView.hasTransientState());
    }

    public void testResetMenuItemTitle() throws Exception {
        getActivity().getSystemService(TextClassificationManager.class).setTextClassifier(null);
        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        final int itemId = 1;
        final String title1 = " AFIGBO";
        final int index = title1.indexOf('I');
        final String title2 = title1.substring(index);
        final String[] title = new String[]{title1};
        textView.post(() -> textView.setCustomSelectionActionModeCallback(
                new ActionMode.Callback() {
                    @Override
                    public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                        return true;
                    }

                    @Override
                    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                        menu.removeItem(itemId);
                        menu.add(Menu.NONE /* group */, itemId, 0 /* order */, title[0]);
                        return true;
                    }

                    @Override
                    public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                        return false;
                    }

                    @Override
                    public void onDestroyActionMode(ActionMode actionMode) {
                    }
                }));
        onView(withId(R.id.textview)).perform(replaceText(title1));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(index));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarContainsItem(title1);

        // Change the menu item title.
        title[0] = title2;
        // Change the selection to invalidate the action mode without restarting it.
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, index));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarContainsItem(title2);
    }

    public void testAssistItemIsAtIndexZero() throws Exception {
        getActivity().getSystemService(TextClassificationManager.class).setTextClassifier(null);
        final TextView textView = (TextView) getActivity().findViewById(R.id.textview);
        textView.post(() -> textView.setCustomSelectionActionModeCallback(
                new ActionMode.Callback() {
                    @Override
                    public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                        // Create another item at order position 0 to confirm that it will never be
                        // placed before the textAssist item.
                        menu.add(Menu.NONE, 0 /* id */, 0 /* order */, "Test");
                        return true;
                    }

                    @Override
                    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                        return true;
                    }

                    @Override
                    public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                        return false;
                    }

                    @Override
                    public void onDestroyActionMode(ActionMode actionMode) {
                    }
                }));
        final String text = "droid@android.com";

        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('@')));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarItemIndex(android.R.id.textAssist, 0);
    }

    public void testPastePlainText_menuAction() throws Exception {
        initializeClipboardWithText(TextStyle.STYLED);

        onView(withId(R.id.textview)).perform(replaceText(""));
        onView(withId(R.id.textview)).perform(longClick());
        sleepForFloatingToolbarPopup();
        clickFloatingToolbarItem(
                getActivity().getString(com.android.internal.R.string.paste_as_plain_text));
        getInstrumentation().waitForIdleSync();

        onView(withId(R.id.textview)).check(matches(withText("styledtext")));
        onView(withId(R.id.textview)).check(doesNotHaveStyledText());
    }

    public void testPastePlainText_noMenuItemForPlainText() {
        initializeClipboardWithText(TextStyle.PLAIN);

        onView(withId(R.id.textview)).perform(replaceText(""));
        onView(withId(R.id.textview)).perform(longClick());
        sleepForFloatingToolbarPopup();

        assertFloatingToolbarDoesNotContainItem(
                getActivity().getString(com.android.internal.R.string.paste_as_plain_text));
    }

    private void initializeClipboardWithText(TextStyle textStyle) {
        final ClipData clip;
        switch (textStyle) {
            case STYLED:
                clip = ClipData.newHtmlText("html", "styledtext", "<b>styledtext</b>");
                break;
            case PLAIN:
                clip = ClipData.newPlainText("plain", "plaintext");
                break;
            default:
                throw new IllegalArgumentException("Invalid text style");
        }
        getActivity().getWindow().getDecorView().post(() ->
            getActivity().getSystemService(ClipboardManager.class).setPrimaryClip( clip));
        getInstrumentation().waitForIdleSync();
    }

    private enum TextStyle {
        PLAIN, STYLED
    }
}
