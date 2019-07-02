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

import static android.widget.espresso.ContextMenuUtils.assertContextMenuContainsItemDisabled;
import static android.widget.espresso.ContextMenuUtils.assertContextMenuContainsItemEnabled;
import static android.widget.espresso.ContextMenuUtils.assertContextMenuIsNotDisplayed;
import static android.widget.espresso.DragHandleUtils.assertNoSelectionHandles;
import static android.widget.espresso.DragHandleUtils.onHandleView;
import static android.widget.espresso.TextViewActions.mouseClick;
import static android.widget.espresso.TextViewActions.mouseClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.mouseDoubleClickAndDragOnText;
import static android.widget.espresso.TextViewActions.mouseDoubleClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.mouseDragOnText;
import static android.widget.espresso.TextViewActions.mouseLongClickAndDragOnText;
import static android.widget.espresso.TextViewActions.mouseLongClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.mouseTripleClickAndDragOnText;
import static android.widget.espresso.TextViewActions.mouseTripleClickOnTextAtIndex;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerAtIndex;
import static android.widget.espresso.TextViewAssertions.hasSelection;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;

import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests mouse interaction of the TextView widget from an Activity
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
@Suppress // Consistently failing. b/29591177
public class TextViewActivityMouseTest {

    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);

    private Activity mActivity;

    @Before
    public void setUp() {
        mActivity = mActivityRule.getActivity();
        mActivity.getSystemService(TextClassificationManager.class)
                .setTextClassifier(TextClassifier.NO_OP);
    }

    @Test
    public void testSelectTextByDrag() {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));

        assertNoSelectionHandles();

        onView(withId(R.id.textview)).perform(
                mouseDragOnText(helloWorld.indexOf("llo"), helloWorld.indexOf("ld!")));

        onView(withId(R.id.textview)).check(hasSelection("llo wor"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));

        onView(withId(R.id.textview)).perform(mouseClickOnTextAtIndex(helloWorld.indexOf("w")));
        onView(withId(R.id.textview)).check(hasSelection(""));

        assertNoSelectionHandles();
    }

    @Test
    public void testSelectTextByDrag_reverse() {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));
        onView(withId(R.id.textview)).perform(
                mouseDragOnText( helloWorld.indexOf("ld!"), helloWorld.indexOf("llo")));

        onView(withId(R.id.textview)).check(hasSelection("llo wor"));
    }

    @Test
    public void testContextMenu() {
        final String text = "abc def ghi.";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));

        assertContextMenuIsNotDisplayed();

        onView(withId(R.id.textview)).perform(
                mouseClickOnTextAtIndex(text.indexOf("d"), MotionEvent.BUTTON_SECONDARY));

        assertContextMenuContainsItemDisabled(
                mActivity.getString(com.android.internal.R.string.copy));
        assertContextMenuContainsItemDisabled(
                mActivity.getString(com.android.internal.R.string.undo));

        // Hide context menu.
        pressBack();
        assertContextMenuIsNotDisplayed();

        // type something to enable Undo
        onView(withId(R.id.textview)).perform(
                mouseClickOnTextAtIndex(text.indexOf(".")));
        onView(withId(R.id.textview)).perform(typeText(" "));

        onView(withId(R.id.textview)).perform(
                mouseClickOnTextAtIndex(text.indexOf("d"), MotionEvent.BUTTON_SECONDARY));

        assertContextMenuContainsItemDisabled(
                mActivity.getString(com.android.internal.R.string.copy));
        assertContextMenuContainsItemEnabled(
                mActivity.getString(com.android.internal.R.string.undo));

        // Hide context menu.
        pressBack();
        assertContextMenuIsNotDisplayed();

        onView(withId(R.id.textview)).perform(
                mouseDragOnText(text.indexOf("c"), text.indexOf("h")));
        onView(withId(R.id.textview)).perform(
                mouseClickOnTextAtIndex(text.indexOf("d"), MotionEvent.BUTTON_SECONDARY));

        assertContextMenuContainsItemEnabled(
                mActivity.getString(com.android.internal.R.string.copy));
        assertContextMenuContainsItemEnabled(
                mActivity.getString(com.android.internal.R.string.undo));

        // Hide context menu.
        pressBack();

        onView(withId(R.id.textview)).check(hasSelection("c def g"));

        onView(withId(R.id.textview)).perform(
                mouseClickOnTextAtIndex(text.indexOf("i"), MotionEvent.BUTTON_SECONDARY));
        assertContextMenuContainsItemDisabled(
                mActivity.getString(com.android.internal.R.string.copy));
        assertContextMenuContainsItemEnabled(
                mActivity.getString(com.android.internal.R.string.undo));

        // Hide context menu.
        pressBack();

        onView(withId(R.id.textview)).check(hasSelection(""));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("i")));

        // TODO: Add tests for suggestions
    }

    @Test
    public void testDragAndDrop() {
        final String text = "abc def ghi.";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(
                mouseDragOnText(text.indexOf("d"), text.indexOf("f") + 1));

        onView(withId(R.id.textview)).perform(
                mouseDragOnText(text.indexOf("e"), text.length()));

        onView(withId(R.id.textview)).check(matches(withText("abc ghi.def")));
        onView(withId(R.id.textview)).check(hasSelection(""));
        assertNoSelectionHandles();
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex("abc ghi.def".length()));
    }

    @Test
    public void testDragAndDrop_longClick() {
        final String text = "abc def ghi.";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(
                mouseDragOnText(text.indexOf("d"), text.indexOf("f") + 1));

        onView(withId(R.id.textview)).perform(
                mouseLongClickAndDragOnText(text.indexOf("e"), text.length()));

        onView(withId(R.id.textview)).check(matches(withText("abc ghi.def")));
        onView(withId(R.id.textview)).check(hasSelection(""));
        assertNoSelectionHandles();
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex("abc ghi.def".length()));
    }

    @Test
    public void testSelectTextByLongClick() {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));

        onView(withId(R.id.textview)).perform(mouseLongClickOnTextAtIndex(0));
        onView(withId(R.id.textview)).check(hasSelection("Hello"));

        onView(withId(R.id.textview)).perform(mouseLongClickOnTextAtIndex(
                helloWorld.indexOf("world")));
        onView(withId(R.id.textview)).check(hasSelection("world"));

        onView(withId(R.id.textview)).perform(mouseLongClickOnTextAtIndex(
                helloWorld.indexOf("llo")));
        onView(withId(R.id.textview)).check(hasSelection("Hello"));

        onView(withId(R.id.textview)).perform(mouseLongClickOnTextAtIndex(
                helloWorld.indexOf("rld")));
        onView(withId(R.id.textview)).check(hasSelection("world"));

        onView(withId(R.id.textview)).perform(mouseLongClickOnTextAtIndex(helloWorld.length()));
        onView(withId(R.id.textview)).check(hasSelection("!"));
    }

    @Test
    public void testSelectTextByDoubleClick() {
        final String helloWorld = "hello world!";

        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(helloWorld));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(1));
        onView(withId(R.id.textview)).check(hasSelection("hello"));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(
                helloWorld.indexOf("world")));
        onView(withId(R.id.textview)).check(hasSelection("world"));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(
                helloWorld.indexOf("llo")));
        onView(withId(R.id.textview)).check(hasSelection("hello"));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(
                helloWorld.indexOf("rld")));
        onView(withId(R.id.textview)).check(hasSelection("world"));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(helloWorld.length()));
        onView(withId(R.id.textview)).check(hasSelection("!"));
    }

    @Test
    public void testSelectTextByDoubleClickAndDrag() {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(
                mouseDoubleClickAndDragOnText(text.indexOf("f"), text.indexOf("j")));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));
    }

    @Test
    public void testSelectTextByDoubleClickAndDrag_reverse() {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(
                mouseDoubleClickAndDragOnText(text.indexOf("j"), text.indexOf("f")));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));
    }

    @Test
    public void testSelectTextByLongPressAndDrag() {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(
                mouseLongClickAndDragOnText(text.indexOf("f"), text.indexOf("j")));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));
    }

    @Test
    public void testSelectTextByLongPressAndDrag_reverse() {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(
                mouseLongClickAndDragOnText(text.indexOf("j"), text.indexOf("f")));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));
    }

    @Test
    public void testSelectTextByTripleClick() {
        final StringBuilder builder = new StringBuilder();
        builder.append("First paragraph.\n");
        builder.append("Second paragraph.");
        for (int i = 0; i < 10; i++) {
            builder.append(" This paragraph is very long.");
        }
        builder.append('\n');
        builder.append("Third paragraph.");
        final String text = builder.toString();

        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickOnTextAtIndex(text.indexOf("rst")));
        onView(withId(R.id.textview)).check(hasSelection("First paragraph.\n"));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickOnTextAtIndex(text.indexOf("cond")));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf("Second"), text.indexOf("Third"))));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickOnTextAtIndex(text.indexOf("ird")));
        onView(withId(R.id.textview)).check(hasSelection("Third paragraph."));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickOnTextAtIndex(text.indexOf("very long")));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf("Second"), text.indexOf("Third"))));
    }

    @Test
    public void testSelectTextByTripleClickAndDrag() {
        final StringBuilder builder = new StringBuilder();
        builder.append("First paragraph.\n");
        builder.append("Second paragraph.");
        for (int i = 0; i < 10; i++) {
            builder.append(" This paragraph is very long.");
        }
        builder.append('\n');
        builder.append("Third paragraph.");
        final String text = builder.toString();

        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickAndDragOnText(text.indexOf("irst"), text.indexOf("st")));
        onView(withId(R.id.textview)).check(hasSelection("First paragraph.\n"));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickAndDragOnText(text.indexOf("cond"), text.indexOf("Third") - 2));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf("Second"), text.indexOf("Third"))));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickAndDragOnText(text.indexOf("First"), text.indexOf("ird")));
        onView(withId(R.id.textview)).check(hasSelection(text));
    }

    @Test
    public void testSelectTextByTripleClickAndDrag_reverse() {
        final StringBuilder builder = new StringBuilder();
        builder.append("First paragraph.\n");
        builder.append("Second paragraph.");
        for (int i = 0; i < 10; i++) {
            builder.append(" This paragraph is very long.");
        }
        builder.append('\n');
        builder.append("Third paragraph.");
        final String text = builder.toString();

        onView(withId(R.id.textview)).perform(mouseClick());
        onView(withId(R.id.textview)).perform(replaceText(text));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickAndDragOnText(text.indexOf("st"), text.indexOf("irst")));
        onView(withId(R.id.textview)).check(hasSelection("First paragraph.\n"));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickAndDragOnText(text.indexOf("Third") - 2, text.indexOf("cond")));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf("Second"), text.indexOf("Third"))));

        onView(withId(R.id.textview)).perform(
                mouseTripleClickAndDragOnText(text.indexOf("ird"), text.indexOf("First")));
        onView(withId(R.id.textview)).check(hasSelection(text));
    }
}
