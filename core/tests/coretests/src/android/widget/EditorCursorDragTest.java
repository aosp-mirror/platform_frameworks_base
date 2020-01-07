/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.widget.espresso.TextViewActions.clickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.dragOnText;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerAtIndex;
import static android.widget.espresso.TextViewAssertions.hasSelection;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;

import android.app.Activity;
import android.app.Instrumentation;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import com.google.common.base.Strings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class EditorCursorDragTest {
    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);

    private boolean mOriginalFlagValue;
    private Instrumentation mInstrumentation;
    private Activity mActivity;

    @Before
    public void before() throws Throwable {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.getActivity();
        mOriginalFlagValue = Editor.FLAG_ENABLE_CURSOR_DRAG;
        Editor.FLAG_ENABLE_CURSOR_DRAG = true;
    }
    @After
    public void after() throws Throwable {
        Editor.FLAG_ENABLE_CURSOR_DRAG = mOriginalFlagValue;
    }

    @Test
    public void testCursorDrag_horizontal_whenTextViewContentsFitOnScreen() throws Throwable {
        String text = "Hello world!";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        // Swipe left to right to drag the cursor. The cursor should end up at the position where
        // the finger is lifted.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("llo"), text.indexOf("!")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(11));

        // Swipe right to left to drag the cursor. The cursor should end up at the position where
        // the finger is lifted.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("!"), text.indexOf("llo")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(2));
    }

    @Test
    public void testCursorDrag_horizontal_whenTextViewContentsLargerThanScreen() throws Throwable {
        String text = "Hello world!\n\n"
                + Strings.repeat("Bla\n\n", 200) + "Bye";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        // Swipe left to right to drag the cursor. The cursor should end up at the position where
        // the finger is lifted.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("llo"), text.indexOf("!")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(11));

        // Swipe right to left to drag the cursor. The cursor should end up at the position where
        // the finger is lifted.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("!"), text.indexOf("llo")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(2));
    }

    @Test
    public void testCursorDrag_diagonal_whenTextViewContentsFitOnScreen() throws Throwable {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            sb.append("line").append(i).append("\n");
        }
        String text = sb.toString();
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        // Swipe along a diagonal path. This should drag the cursor.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("2")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("2")));

        // Swipe along a steeper diagonal path. This should still drag the cursor.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("3")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("3")));

        // Swipe right-down along a very steep diagonal path. This should not drag the cursor.
        // Normally this would trigger a scroll, but since the full view fits on the screen there
        // is nothing to scroll and the gesture will trigger a selection drag.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("7")));
        onView(withId(R.id.textview)).check(hasSelection(not(emptyString())));

        // Swipe right-up along a very steep diagonal path. This should not drag the cursor.
        // Normally this would trigger a scroll, but since the full view fits on the screen there
        // is nothing to scroll and the gesture will trigger a selection drag.
        int index = text.indexOf("line9");
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(index));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(index));
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line7"), text.indexOf("1")));
        onView(withId(R.id.textview)).check(hasSelection(not(emptyString())));
    }

    @Test
    public void testCursorDrag_diagonal_whenTextViewContentsLargerThanScreen() throws Throwable {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            sb.append("line").append(i).append("\n");
        }
        sb.append(Strings.repeat("0123456789\n", 400)).append("Last");
        String text = sb.toString();
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        // Swipe along a diagonal path. This should drag the cursor.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("2")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("2")));

        // Swipe along a steeper diagonal path. This should still drag the cursor.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("3")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("3")));

        // Swipe right-down along a very steep diagonal path. This should not drag the cursor.
        // Normally this would trigger a scroll up, but since the view is already at the top there
        // is nothing to scroll and the gesture will trigger a selection drag.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("7")));
        onView(withId(R.id.textview)).check(hasSelection(not(emptyString())));

        // Swipe right-up along a very steep diagonal path. This should not drag the cursor. This
        // will trigger a downward scroll and the cursor position will not change.
        int index = text.indexOf("line9");
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(index));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(index));
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line7"), text.indexOf("1")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(index));
    }

    @Test
    public void testCursorDrag_vertical_whenTextViewContentsFitOnScreen() throws Throwable {
        String text = "012345_aaa\n"
                + "0123456789\n"
                + "012345_bbb\n"
                + "0123456789\n"
                + "012345_ccc\n"
                + "0123456789\n"
                + "012345_ddd";
        onView(withId(R.id.textview)).perform(replaceText(text));

        // Swipe up vertically. This should not drag the cursor. Since there's also nothing to
        // scroll, the gesture will trigger a selection drag.
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(0));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("bbb"), text.indexOf("aaa")));
        onView(withId(R.id.textview)).check(hasSelection(not(emptyString())));

        // Swipe down vertically. This should not drag the cursor. Since there's also nothing to
        // scroll, the gesture will trigger a selection drag.
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(0));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("ccc"), text.indexOf("ddd")));
        onView(withId(R.id.textview)).check(hasSelection(not(emptyString())));
    }

    @Test
    public void testCursorDrag_vertical_whenTextViewContentsLargerThanScreen() throws Throwable {
        String text = "012345_aaa\n"
                + "0123456789\n"
                + "012345_bbb\n"
                + "0123456789\n"
                + "012345_ccc\n"
                + "0123456789\n"
                + "012345_ddd\n"
                + Strings.repeat("0123456789\n", 400) + "012345_zzz";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.indexOf("ddd")));
        int initialCursorPosition = text.indexOf("ddd");
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(initialCursorPosition));

        // Swipe up vertically. This should trigger a downward scroll.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("bbb"), text.indexOf("aaa")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(initialCursorPosition));

        // Swipe down vertically. This should trigger an upward scroll.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("ccc"), text.indexOf("ddd")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(initialCursorPosition));
    }
}
