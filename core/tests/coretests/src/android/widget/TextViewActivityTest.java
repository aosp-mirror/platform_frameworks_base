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

import static android.widget.espresso.TextViewActions.clickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.doubleTapAndDragOnText;
import static android.widget.espresso.TextViewActions.doubleClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.longPressAndDragOnText;
import static android.widget.espresso.TextViewActions.longPressOnTextAtIndex;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerAtIndex;
import static android.widget.espresso.TextViewAssertions.hasSelection;
import static android.widget.espresso.FloatingToolbarEspressoUtils.assertFloatingToolbarIsDisplayed;
import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.pressKey;
import static android.support.test.espresso.action.ViewActions.typeTextIntoFocusedView;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;

import com.android.frameworks.coretests.R;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.KeyEvent;

/**
 * Tests the TextView widget from an Activity
 */
public class TextViewActivityTest extends ActivityInstrumentationTestCase2<TextViewActivity>{

    public TextViewActivityTest() {
        super(TextViewActivity.class);
    }

    @SmallTest
    public void testTypedTextIsOnScreen() throws Exception {
        getActivity();

        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));

        onView(withId(R.id.textview)).check(matches(withText(helloWorld)));
    }

    @SmallTest
    public void testPositionCursorAtTextAtIndex() throws Exception {
        getActivity();

        final String helloWorld = "Hello world!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(helloWorld.indexOf("world")));

        // Delete text at specified index and see if we got the right one.
        onView(withId(R.id.textview)).perform(pressKey(KeyEvent.KEYCODE_FORWARD_DEL));
        onView(withId(R.id.textview)).check(matches(withText("Hello orld!")));
    }

    @SmallTest
    public void testLongPressToSelect() throws Exception {
        getActivity();

        final String helloWorld = "Hello Kirk!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        onView(withId(R.id.textview)).perform(
                longPressOnTextAtIndex(helloWorld.indexOf("Kirk")));

        onView(withId(R.id.textview)).check(hasSelection("Kirk"));
    }

    @SmallTest
    public void testLongPressEmptySpace() throws Exception {
        getActivity();

        final String helloWorld = "Hello big round sun!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        // Move cursor somewhere else
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(helloWorld.indexOf("big")));
        // Long-press at end of line.
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(helloWorld.length()));

        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(helloWorld.length()));
    }

    @SmallTest
    public void testLongPressAndDragToSelect() throws Exception {
        getActivity();

        final String helloWorld = "Hello little handsome boy!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        onView(withId(R.id.textview)).perform(
                longPressAndDragOnText(helloWorld.indexOf("little"), helloWorld.indexOf(" boy!")));

        onView(withId(R.id.textview)).check(hasSelection("little handsome"));
    }

    @SmallTest
    public void testDoubleTapToSelect() throws Exception {
        getActivity();

        final String helloWorld = "Hello SuetYi!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        onView(withId(R.id.textview)).perform(
                doubleClickOnTextAtIndex(helloWorld.indexOf("SuetYi")));

        onView(withId(R.id.textview)).check(hasSelection("SuetYi"));
    }

    @SmallTest
    public void testDoubleTapAndDragToSelect() throws Exception {
        getActivity();

        final String helloWorld = "Hello young beautiful girl!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf("young"), helloWorld.indexOf(" girl!")));

        onView(withId(R.id.textview)).check(hasSelection("young beautiful"));
    }

    @SmallTest
    public void testSelectBackwordsByTouch() throws Exception {
        getActivity();

        final String helloWorld = "Hello king of the Jungle!";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf(" Jungle!"), helloWorld.indexOf("king")));

        onView(withId(R.id.textview)).check(hasSelection("king of the"));
    }

    @SmallTest
    public void testToolbarAppearsAfterSelection() throws Exception {
        getActivity();

        // It'll be nice to check that the toolbar is not visible (or does not exist) here
        // I can't currently find a way to do this. I'll get to it later.

        final String text = "Toolbar appears after selection.";
        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(text));
        onView(withId(R.id.textview)).perform(
                longPressOnTextAtIndex(text.indexOf("appears")));

        // It takes the toolbar less than 100ms to start to animate into screen.
        // Ideally, we'll wait using the UiController, but I guess this works for now.
        Thread.sleep(100);
        assertFloatingToolbarIsDisplayed(getActivity());
    }
}
