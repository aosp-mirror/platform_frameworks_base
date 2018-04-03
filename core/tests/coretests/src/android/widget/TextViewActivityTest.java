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

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.longClick;
import static android.support.test.espresso.action.ViewActions.pressKey;
import static android.support.test.espresso.action.ViewActions.replaceText;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import static android.widget.espresso.CustomViewActions.longPressAtRelativeCoordinates;
import static android.widget.espresso.DragHandleUtils.onHandleView;
import static android.widget.espresso.FloatingToolbarEspressoUtils
        .assertFloatingToolbarContainsItem;
import static android.widget.espresso.FloatingToolbarEspressoUtils
        .assertFloatingToolbarDoesNotContainItem;
import static android.widget.espresso.FloatingToolbarEspressoUtils.assertFloatingToolbarIsDisplayed;
import static android.widget.espresso.FloatingToolbarEspressoUtils.assertFloatingToolbarItemIndex;
import static android.widget.espresso.FloatingToolbarEspressoUtils.clickFloatingToolbarItem;
import static android.widget.espresso.FloatingToolbarEspressoUtils.sleepForFloatingToolbarPopup;
import static android.widget.espresso.TextViewActions.Handle;
import static android.widget.espresso.TextViewActions.clickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.doubleClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.doubleTapAndDragOnText;
import static android.widget.espresso.TextViewActions.dragHandle;
import static android.widget.espresso.TextViewActions.longPressAndDragOnText;
import static android.widget.espresso.TextViewActions.longPressOnTextAtIndex;
import static android.widget.espresso.TextViewAssertions.doesNotHaveStyledText;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerAtIndex;
import static android.widget.espresso.TextViewAssertions.hasSelection;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.action.EspressoKey;
import android.support.test.filters.MediumTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.UiDevice;
import android.test.suitebuilder.annotation.Suppress;
import android.text.InputType;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextLinksParams;
import android.widget.espresso.CustomViewActions.RelativeCoordinatesProvider;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests the TextView widget from an Activity
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class TextViewActivityTest {

    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);

    private Activity mActivity;
    private Instrumentation mInstrumentation;

    @Before
    public void setUp() {
        mActivity = mActivityRule.getActivity();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity.getSystemService(TextClassificationManager.class)
                .setTextClassifier(TextClassifier.NO_OP);
    }

    @Test
    public void testTypedTextIsOnScreen() {
        final String helloWorld = "Hello world!";
        // We use replaceText instead of typeTextIntoFocusedView to input text to avoid
        // unintentional interactions with software keyboard.
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));

        onView(withId(R.id.textview)).check(matches(withText(helloWorld)));
    }
    @Test
    public void testPositionCursorAtTextAtIndex() {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(helloWorld.indexOf("world")));

        // Delete text at specified index and see if we got the right one.
        onView(withId(R.id.textview)).perform(pressKey(KeyEvent.KEYCODE_FORWARD_DEL));
        onView(withId(R.id.textview)).check(matches(withText("Hello orld!")));
    }

    @Test
    public void testPositionCursorAtTextAtIndex_arabic() {
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

    @Test
    public void testPositionCursorAtTextAtIndex_devanagari() {
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

    @Test
    public void testLongPressToSelect() {
        final String helloWorld = "Hello Kirk!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                longPressOnTextAtIndex(helloWorld.indexOf("Kirk")));

        onView(withId(R.id.textview)).check(hasSelection("Kirk"));
    }

    @Test
    public void testLongPressEmptySpace() {
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

    @Test
    public void testLongPressAndDragToSelect() {
        final String helloWorld = "Hello little handsome boy!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                longPressAndDragOnText(helloWorld.indexOf("little"), helloWorld.indexOf(" boy!")));

        onView(withId(R.id.textview)).check(hasSelection("little handsome"));
    }

    @Test
    public void testLongPressAndDragToSelect_emoji() {
        final String text = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02\uD83D\uDE03";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(longPressAndDragOnText(4, 6));
        onView(withId(R.id.textview)).check(hasSelection("\uD83D\uDE02"));

        onView(withId(R.id.textview)).perform(click());

        onView(withId(R.id.textview)).perform(longPressAndDragOnText(4, 2));
        onView(withId(R.id.textview)).check(hasSelection("\uD83D\uDE01"));
    }

    @Test
    public void testDragAndDrop() {
        final String text = "abc def ghi.";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf("e")));

        onView(withId(R.id.textview)).perform(
                longPressAndDragOnText(text.indexOf("e"), text.length()));

        onView(withId(R.id.textview)).check(matches(withText("abc ghi.def")));
        onView(withId(R.id.textview)).check(hasSelection(""));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex("abc ghi.def".length()));

        // Test undo returns to the original state.
        onView(withId(R.id.textview)).perform(pressKey(
                (new EspressoKey.Builder()).withCtrlPressed(true).withKeyCode(KeyEvent.KEYCODE_Z)
                        .build()));
        onView(withId(R.id.textview)).check(matches(withText(text)));
    }

    @Test
    public void testDoubleTapToSelect() {
        final String helloWorld = "Hello SuetYi!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));

        onView(withId(R.id.textview)).perform(
                doubleClickOnTextAtIndex(helloWorld.indexOf("SuetYi")));

        onView(withId(R.id.textview)).check(hasSelection("SuetYi"));
    }

    @Test
    public void testDoubleTapAndDragToSelect() {
        final String helloWorld = "Hello young beautiful person!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(doubleTapAndDragOnText(helloWorld.indexOf("young"),
                        helloWorld.indexOf(" person!")));

        onView(withId(R.id.textview)).check(hasSelection("young beautiful"));
    }

    @Test
    public void testDoubleTapAndDragToSelect_multiLine() {
        final String helloWorld = "abcd\n" + "efg\n" + "hijklm\n" + "nop";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf("m"), helloWorld.indexOf("a")));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijklm"));
    }

    @Test
    public void testSelectBackwordsByTouch() {
        final String helloWorld = "Hello king of the Jungle!";
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf(" Jungle!"), helloWorld.indexOf("king")));

        onView(withId(R.id.textview)).check(hasSelection("king of the"));
    }

    @Test
    public void testToolbarAppearsAfterSelection() {
        final String text = "Toolbar appears after selection.";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(
                longPressOnTextAtIndex(text.indexOf("appears")));

        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
    }

    @Test
    public void testToolbarAppearsAfterSelection_withFirstStringLtrAlgorithmAndRtlHint()
            throws Throwable {
        // after the hint layout change, the floating toolbar was not visible in the case below
        // this test tests that the floating toolbar is displayed on the screen and is visible to
        // user.
        mActivityRule.runOnUiThread(() -> {
            final TextView textView = mActivity.findViewById(R.id.textview);
            textView.setTextDirection(TextView.TEXT_DIRECTION_FIRST_STRONG_LTR);
            textView.setInputType(InputType.TYPE_CLASS_TEXT);
            textView.setSingleLine(true);
            textView.setHint("الروبوت");
        });
        mInstrumentation.waitForIdleSync();

        onView(withId(R.id.textview)).perform(replaceText("test"));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(1));
        clickFloatingToolbarItem(mActivity.getString(com.android.internal.R.string.cut));
        onView(withId(R.id.textview)).perform(longClick());
        sleepForFloatingToolbarPopup();

        assertFloatingToolbarIsDisplayed();
    }

    @Test
    public void testToolbarAppearsAfterLinkClicked() throws Throwable {
        TextLinks.TextLink textLink = addLinkifiedTextToTextView(R.id.textview);
        int position = (textLink.getStart() + textLink.getEnd()) / 2;
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(position));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

    }

    @Test
    public void testToolbarAppearsAfterLinkClickedNonselectable() throws Throwable {
        TextLinks.TextLink textLink = addLinkifiedTextToTextView(R.id.nonselectable_textview);
        int position = (textLink.getStart() + textLink.getEnd()) / 2;
        onView(withId(R.id.nonselectable_textview)).perform(clickOnTextAtIndex(position));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
    }

    @Test
    public void testSelectionRemovedWhenNonselectableTextLosesFocus() throws Throwable {
        // Add a link to both selectable and nonselectable TextViews:
        TextLinks.TextLink textLink = addLinkifiedTextToTextView(R.id.textview);
        int selectablePosition = (textLink.getStart() + textLink.getEnd()) / 2;
        textLink = addLinkifiedTextToTextView(R.id.nonselectable_textview);
        int nonselectablePosition = (textLink.getStart() + textLink.getEnd()) / 2;
        TextView selectableTextView = mActivity.findViewById(R.id.textview);
        TextView nonselectableTextView = mActivity.findViewById(R.id.nonselectable_textview);

        onView(withId(R.id.nonselectable_textview))
                .perform(clickOnTextAtIndex(nonselectablePosition));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertTrue(nonselectableTextView.hasSelection());

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(selectablePosition));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        assertTrue(selectableTextView.hasSelection());
        assertFalse(nonselectableTextView.hasSelection());
    }

    @Test
    public void testSelectionRemovedFromNonselectableTextWhenWindowLosesFocus() throws Throwable {
        TextLinks.TextLink textLink = addLinkifiedTextToTextView(R.id.nonselectable_textview);
        int nonselectablePosition = (textLink.getStart() + textLink.getEnd()) / 2;
        TextView nonselectableTextView = mActivity.findViewById(R.id.nonselectable_textview);

        onView(withId(R.id.nonselectable_textview))
                .perform(clickOnTextAtIndex(nonselectablePosition));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertTrue(nonselectableTextView.hasSelection());

        UiDevice device = UiDevice.getInstance(mInstrumentation);
        device.openNotification();
        Thread.sleep(2000);
        device.pressBack();
        Thread.sleep(2000);

        assertFalse(nonselectableTextView.hasSelection());
    }

    private TextLinks.TextLink addLinkifiedTextToTextView(int id) throws Throwable {
        TextView textView = mActivity.findViewById(id);
        useSystemDefaultTextClassifier();
        TextClassificationManager textClassificationManager =
                mActivity.getSystemService(TextClassificationManager.class);
        TextClassifier textClassifier = textClassificationManager.getTextClassifier();
        Spannable content = new SpannableString("Call me at +19148277737");
        TextLinks.Request request = new TextLinks.Request.Builder(content).build();
        TextLinks links = textClassifier.generateLinks(request);
        TextLinksParams applyParams = new TextLinksParams.Builder()
                .setApplyStrategy(TextLinks.APPLY_STRATEGY_REPLACE)
                .build();
        applyParams.apply(content, links);

        mActivityRule.runOnUiThread(() -> {
            textView.setText(content);
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        });
        mInstrumentation.waitForIdleSync();

        // Wait for the UI thread to refresh
        Thread.sleep(1000);

        return links.getLinks().iterator().next();
    }

    @Test
    public void testToolbarAndInsertionHandle() {
        final String text = "text";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));

        onHandleView(com.android.internal.R.id.insertion_handle).perform(click());
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.copy));
        assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.cut));
    }

    @Test
    public void testToolbarAndSelectionHandle() {
        final String text = "abcd efg hijk";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf("f")));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.copy));
        assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.cut));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.length()));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();

        assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.copy));
        assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.cut));
    }

    @Test
    public void testInsertionHandle() {
        final String text = "abcd efg hijk ";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        final TextView textView = mActivity.findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("a")));

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("f")));
    }

    @Test
    public void testInsertionHandle_multiLine() {
        final String text = "abcd\n" + "efg\n" + "hijk\n";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        final TextView textView = mActivity.findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("a")));

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("f")));
    }

    @Test
    public void testSelectionHandles() {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('k') + 1));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg hijk"));
    }

    @Test
    public void testSelectionHandles_bidi() {
        final String text = "abc \u0621\u0622\u0623 def";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('\u0622')));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));

        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        final TextView textView = mActivity.findViewById(R.id.textview);
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

    @Test
    public void testSelectionHandles_multiLine() {
        final String text = "abcd\n" + "efg\n" + "hijk\n" + "lmn\n" + "opqr";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = mActivity.findViewById(R.id.textview);
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

    @Suppress // Consistently failing.
    @Test
    public void testSelectionHandles_multiLine_rtl() {
        // Arabic text.
        final String text = "\u062A\u062B\u062C\n" + "\u062D\u062E\u062F\n"
                + "\u0630\u0631\u0632\n" + "\u0633\u0634\u0635\n" + "\u0636\u0637\u0638\n"
                + "\u0639\u063A\u063B";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('\u0634')));

        final TextView textView = mActivity.findViewById(R.id.textview);
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

    @Test
    public void testSelectionHandles_doesNotPassAnotherHandle() {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('l')));
        onView(withId(R.id.textview)).check(hasSelection("g"));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("e"));
    }

    @Test
    public void testSelectionHandles_doesNotPassAnotherHandle_multiLine() {
        final String text = "abcd\n" + "efg\n" + "hijk\n" + "lmn\n" + "opqr";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('r') + 1));
        onView(withId(R.id.textview)).check(hasSelection("k"));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("h"));
    }

    @Test
    public void testSelectionHandles_snapToWordBoundary() {
        final String text = "abcd efg hijk lmn opqr";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = mActivity.findViewById(R.id.textview);

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

    @Test
    public void testSelectionHandles_snapToWordBoundary_multiLine() {
        final String text = "abcd efg\n" + "hijk lmn\n" + "opqr stu";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('m')));

        final TextView textView = mActivity.findViewById(R.id.textview);

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

    @Test
    public void testSelectionHandles_visibleEvenWithEmptyMenu() {
        ((TextView) mActivity.findViewById(R.id.textview)).setCustomSelectionActionModeCallback(
                new ActionMode.Callback() {
                    @Override
                    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                        menu.clear();
                        return true;
                    }

                    @Override
                    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                        menu.clear();
                        return true;
                    }

                    @Override
                    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                        return false;
                    }

                    @Override
                    public void onDestroyActionMode(ActionMode mode) {}
                });
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));
    }

    @Test
    public void testSetSelectionAndActionMode() throws Throwable {
        final TextView textView = mActivity.findViewById(R.id.textview);
        final ActionMode.Callback amCallback = mock(ActionMode.Callback.class);
        when(amCallback.onCreateActionMode(any(ActionMode.class), any(Menu.class)))
                .thenReturn(true);
        when(amCallback.onPrepareActionMode(any(ActionMode.class), any(Menu.class)))
                .thenReturn(true);
        textView.setCustomSelectionActionModeCallback(amCallback);

        final String text = "abc def";
        onView(withId(R.id.textview)).perform(replaceText(text));
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, 3));
        mInstrumentation.waitForIdleSync();
        // Don't automatically start action mode.
        verify(amCallback, never()).onCreateActionMode(any(ActionMode.class), any(Menu.class));
        // Make sure that "Select All" is included in the selection action mode when the entire text
        // is not selected.
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('e')));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        // Changing the selection range by API should not interrupt the selection action mode.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, 3));
        mInstrumentation.waitForIdleSync();
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        // Make sure that "Select All" is no longer included when the entire text is selected by
        // API.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, text.length()));
        mInstrumentation.waitForIdleSync();

        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        // Make sure that shrinking the selection range to cursor (an empty range) by API
        // terminates selection action mode and does not trigger the insertion action mode.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0));
        mInstrumentation.waitForIdleSync();

        // Make sure that user click can trigger the insertion action mode.
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onHandleView(com.android.internal.R.id.insertion_handle).perform(click());
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        // Make sure that an existing insertion action mode keeps alive after the insertion point is
        // moved by API.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0));
        mInstrumentation.waitForIdleSync();

        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.copy));
        // Make sure that selection action mode is started after selection is created by API when
        // insertion action mode is active.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 1, text.length()));
        mInstrumentation.waitForIdleSync();

        sleepForFloatingToolbarPopup();
        assertFloatingToolbarIsDisplayed();
        assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.copy));
    }

    @Test
    public void testTransientState() throws Throwable {
        final String text = "abc def";
        onView(withId(R.id.textview)).perform(replaceText(text));

        final TextView textView = mActivity.findViewById(R.id.textview);
        assertFalse(textView.hasTransientState());

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('b')));
        // hasTransientState should return true when user generated selection is active.
        assertTrue(textView.hasTransientState());
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.indexOf('d')));
        // hasTransientState should return false as the selection has been cleared.
        assertFalse(textView.hasTransientState());
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, text.length()));
        mInstrumentation.waitForIdleSync();

        // hasTransientState should return false when selection is created by API.
        assertFalse(textView.hasTransientState());
    }

    @Test
    public void testResetMenuItemTitle() throws Throwable {
        mActivity.getSystemService(TextClassificationManager.class).setTextClassifier(null);
        final TextView textView = mActivity.findViewById(R.id.textview);
        final int itemId = 1;
        final String title1 = " AFIGBO";
        final int index = title1.indexOf('I');
        final String title2 = title1.substring(index);
        final String[] title = new String[]{title1};
        mActivityRule.runOnUiThread(() -> textView.setCustomSelectionActionModeCallback(
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
        mInstrumentation.waitForIdleSync();

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

    @Test
    public void testAssistItemIsAtIndexZero() throws Throwable {
        useSystemDefaultTextClassifier();
        final TextView textView = mActivity.findViewById(R.id.textview);
        mActivityRule.runOnUiThread(() -> textView.setCustomSelectionActionModeCallback(
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
        mInstrumentation.waitForIdleSync();
        final String text = "droid@android.com";

        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('@')));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarItemIndex(android.R.id.textAssist, 0);
    }

    @Test
    public void testNoAssistItemForPasswordField() throws Throwable {
        useSystemDefaultTextClassifier();
        final TextView textView = mActivity.findViewById(R.id.textview);
        mActivityRule.runOnUiThread(() -> {
            textView.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        });
        mInstrumentation.waitForIdleSync();
        final String password = "afigbo@android.com";

        onView(withId(R.id.textview)).perform(replaceText(password));
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(password.indexOf('@')));
        sleepForFloatingToolbarPopup();
        assertFloatingToolbarDoesNotContainItem(android.R.id.textAssist);
    }

    @Test
    public void testPastePlainText_menuAction() {
        initializeClipboardWithText(TextStyle.STYLED);

        onView(withId(R.id.textview)).perform(replaceText(""));
        onView(withId(R.id.textview)).perform(longClick());
        sleepForFloatingToolbarPopup();
        clickFloatingToolbarItem(
                mActivity.getString(com.android.internal.R.string.paste_as_plain_text));
        mInstrumentation.waitForIdleSync();

        onView(withId(R.id.textview)).check(matches(withText("styledtext")));
        onView(withId(R.id.textview)).check(doesNotHaveStyledText());
    }

    @Test
    public void testPastePlainText_noMenuItemForPlainText() {
        initializeClipboardWithText(TextStyle.PLAIN);

        onView(withId(R.id.textview)).perform(replaceText(""));
        onView(withId(R.id.textview)).perform(longClick());
        sleepForFloatingToolbarPopup();

        assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.paste_as_plain_text));
    }

    private void useSystemDefaultTextClassifier() {
        mActivity.getSystemService(TextClassificationManager.class).setTextClassifier(null);
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
        mActivity.getWindow().getDecorView().post(() ->
                mActivity.getSystemService(ClipboardManager.class).setPrimaryClip(clip));
        mInstrumentation.waitForIdleSync();
    }

    private enum TextStyle {
        PLAIN, STYLED
    }
}
