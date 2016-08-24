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
import static android.widget.espresso.TextViewActions.mouseClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.mouseDoubleClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.mouseLongClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.mouseDoubleClickAndDragOnText;
import static android.widget.espresso.TextViewActions.mouseDragOnText;
import static android.widget.espresso.TextViewActions.mouseLongClickAndDragOnText;
import static android.widget.espresso.TextViewActions.mouseTripleClickAndDragOnText;
import static android.widget.espresso.TextViewActions.mouseTripleClickOnTextAtIndex;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerAtIndex;
import static android.widget.espresso.TextViewAssertions.hasSelection;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.Espresso.pressBack;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.replaceText;
import static android.support.test.espresso.action.ViewActions.typeTextIntoFocusedView;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;

import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.MotionEvent;

/**
 * Tests mouse interaction of the TextView widget from an Activity
 */
public class TextViewActivityMouseTest extends ActivityInstrumentationTestCase2<TextViewActivity>{

    public TextViewActivityMouseTest() {
        super(TextViewActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        getActivity();
    }

    @SmallTest
    public void testSelectTextByDrag() throws Exception {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));

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

    @SmallTest
    public void testSelectTextByDrag_reverse() throws Exception {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        onView(withId(R.id.textview)).perform(
                mouseDragOnText( helloWorld.indexOf("ld!"), helloWorld.indexOf("llo")));

        onView(withId(R.id.textview)).check(hasSelection("llo wor"));
    }

    @SmallTest
    public void testContextMenu() throws Exception {
        final String text = "abc def ghi.";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(text));

        assertContextMenuIsNotDisplayed();

        onView(withId(R.id.textview)).perform(
                mouseClickOnTextAtIndex(text.indexOf("d"), MotionEvent.BUTTON_SECONDARY));

        assertContextMenuContainsItemDisabled(
                getActivity().getString(com.android.internal.R.string.copy));
        assertContextMenuContainsItemEnabled(
                getActivity().getString(com.android.internal.R.string.undo));

        // Hide context menu.
        pressBack();
        assertContextMenuIsNotDisplayed();

        onView(withId(R.id.textview)).perform(
                mouseDragOnText(text.indexOf("c"), text.indexOf("h")));
        onView(withId(R.id.textview)).perform(
                mouseClickOnTextAtIndex(text.indexOf("d"), MotionEvent.BUTTON_SECONDARY));

        assertContextMenuContainsItemEnabled(
                getActivity().getString(com.android.internal.R.string.copy));
        assertContextMenuContainsItemEnabled(
                getActivity().getString(com.android.internal.R.string.undo));

        // Hide context menu.
        pressBack();

        onView(withId(R.id.textview)).check(hasSelection("c def g"));

        onView(withId(R.id.textview)).perform(
                mouseClickOnTextAtIndex(text.indexOf("i"), MotionEvent.BUTTON_SECONDARY));
        assertContextMenuContainsItemDisabled(
                getActivity().getString(com.android.internal.R.string.copy));
        assertContextMenuContainsItemEnabled(
                getActivity().getString(com.android.internal.R.string.undo));

        // Hide context menu.
        pressBack();

        onView(withId(R.id.textview)).check(hasSelection(""));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("i")));

        // TODO: Add tests for suggestions
    }

    @SmallTest
    public void testDragAndDrop() throws Exception {
        final String text = "abc def ghi.";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(text));
        onView(withId(R.id.textview)).perform(
                mouseDragOnText(text.indexOf("d"), text.indexOf("f") + 1));

        onView(withId(R.id.textview)).perform(
                mouseDragOnText(text.indexOf("e"), text.length()));

        onView(withId(R.id.textview)).check(matches(withText("abc ghi.def")));
        onView(withId(R.id.textview)).check(hasSelection(""));
        assertNoSelectionHandles();
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex("abc ghi.def".length()));
    }

    @SmallTest
    public void testDragAndDrop_longClick() throws Exception {
        final String text = "abc def ghi.";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(text));
        onView(withId(R.id.textview)).perform(
                mouseDragOnText(text.indexOf("d"), text.indexOf("f") + 1));

        onView(withId(R.id.textview)).perform(
                mouseLongClickAndDragOnText(text.indexOf("e"), text.length()));

        onView(withId(R.id.textview)).check(matches(withText("abc ghi.def")));
        onView(withId(R.id.textview)).check(hasSelection(""));
        assertNoSelectionHandles();
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex("abc ghi.def".length()));
    }

    @SmallTest
    public void testSelectTextByLongClick() throws Exception {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));

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

    @SmallTest
    public void testSelectTextByDoubleClick() throws Exception {
        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(0));
        onView(withId(R.id.textview)).check(hasSelection("Hello"));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(
                helloWorld.indexOf("world")));
        onView(withId(R.id.textview)).check(hasSelection("world"));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(
                helloWorld.indexOf("llo")));
        onView(withId(R.id.textview)).check(hasSelection("Hello"));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(
                helloWorld.indexOf("rld")));
        onView(withId(R.id.textview)).check(hasSelection("world"));

        onView(withId(R.id.textview)).perform(mouseDoubleClickOnTextAtIndex(helloWorld.length()));
        onView(withId(R.id.textview)).check(hasSelection("!"));
    }

    @SmallTest
    public void testSelectTextByDoubleClickAndDrag() throws Exception {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(text));

        onView(withId(R.id.textview)).perform(
                mouseDoubleClickAndDragOnText(text.indexOf("f"), text.indexOf("j")));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));
    }

    @SmallTest
    public void testSelectTextByDoubleClickAndDrag_reverse() throws Exception {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(text));

        onView(withId(R.id.textview)).perform(
                mouseDoubleClickAndDragOnText(text.indexOf("j"), text.indexOf("f")));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));
    }

    @SmallTest
    public void testSelectTextByLongPressAndDrag() throws Exception {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(text));

        onView(withId(R.id.textview)).perform(
                mouseLongClickAndDragOnText(text.indexOf("f"), text.indexOf("j")));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));
    }

    @SmallTest
    public void testSelectTextByLongPressAndDrag_reverse() throws Exception {
        final String text = "abcd efg hijk lmn";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(text));

        onView(withId(R.id.textview)).perform(
                mouseLongClickAndDragOnText(text.indexOf("j"), text.indexOf("f")));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));
    }

    @SmallTest
    public void testSelectTextByTripleClick() throws Exception {
        final StringBuilder builder = new StringBuilder();
        builder.append("First paragraph.\n");
        builder.append("Second paragraph.");
        for (int i = 0; i < 10; i++) {
            builder.append(" This paragraph is very long.");
        }
        builder.append('\n');
        builder.append("Third paragraph.");
        final String text = builder.toString();

        onView(withId(R.id.textview)).perform(click());
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

    @SmallTest
    public void testSelectTextByTripleClickAndDrag() throws Exception {
        final StringBuilder builder = new StringBuilder();
        builder.append("First paragraph.\n");
        builder.append("Second paragraph.");
        for (int i = 0; i < 10; i++) {
            builder.append(" This paragraph is very long.");
        }
        builder.append('\n');
        builder.append("Third paragraph.");
        final String text = builder.toString();

        onView(withId(R.id.textview)).perform(click());
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

    @SmallTest
    public void testSelectTextByTripleClickAndDrag_reverse() throws Exception {
        final StringBuilder builder = new StringBuilder();
        builder.append("First paragraph.\n");
        builder.append("Second paragraph.");
        for (int i = 0; i < 10; i++) {
            builder.append(" This paragraph is very long.");
        }
        builder.append('\n');
        builder.append("Third paragraph.");
        final String text = builder.toString();

        onView(withId(R.id.textview)).perform(click());
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
