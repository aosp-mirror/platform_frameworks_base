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
        onView(withId(R.id.textview)).perform(typeTextIntoFocusedView(helloWorld));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(helloWorld.indexOf("world")));

        // Delete text at specified index and see if we got the right one.
        onView(withId(R.id.textview)).perform(pressKey(KeyEvent.KEYCODE_FORWARD_DEL));
        onView(withId(R.id.textview)).check(matches(withText("Hello orld!")));
    }
}
