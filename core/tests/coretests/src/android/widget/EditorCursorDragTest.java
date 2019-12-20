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

import static android.widget.espresso.TextViewActions.dragOnText;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerAtIndex;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

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

        // Drag left to right. The cursor should end up at the position where the finger is lifted.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("llo"), text.indexOf("!")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(11));

        // Drag right to left. The cursor should end up at the position where the finger is lifted.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("!"), text.indexOf("llo")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(2));
    }

    @Test
    public void testCursorDrag_horizontal_whenTextViewContentsLargerThanScreen() throws Throwable {
        String text = "Hello world!"
                + Strings.repeat("\n", 500) + "012345middle"
                + Strings.repeat("\n", 10) + "012345last";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        // Drag left to right. The cursor should end up at the position where the finger is lifted.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("llo"), text.indexOf("!")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(11));

        // Drag right to left. The cursor should end up at the position where the finger is lifted.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("!"), text.indexOf("llo")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(2));
    }

    @Test
    public void testCursorDrag_diagonal_whenTextViewContentsLargerThanScreen() throws Throwable {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            sb.append("line").append(i).append("\n");
        }
        sb.append(Strings.repeat("0123456789\n\n", 500)).append("Last line");
        String text = sb.toString();
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        // Drag along a diagonal path.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("2")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("2")));

        // Drag along a steeper diagonal path.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("9")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("9")));

        // Drag along an almost vertical path.
        // TODO(b/145833335): Consider whether this should scroll instead of dragging the cursor.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("ne1"), text.indexOf("9")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("9")));

        // Drag along a vertical path from line 1 to line 9.
        // TODO(b/145833335): Consider whether this should scroll instead of dragging the cursor.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("e1"), text.indexOf("e9")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("e9")));

        // Drag along a vertical path from line 9 to line 1.
        // TODO(b/145833335): Consider whether this should scroll instead of dragging the cursor.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("e9"), text.indexOf("e1")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("e1")));
    }

    @Test
    public void testCursorDrag_vertical_whenTextViewContentsFitOnScreen() throws Throwable {
        String text = "012first\n\n" + Strings.repeat("012345\n\n", 10) + "012last";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        // Drag down. Since neither the TextView nor its container require scrolling, the cursor
        // drag should execute and the cursor should end up at the position where the finger is
        // lifted.
        onView(withId(R.id.textview)).perform(
                dragOnText(text.indexOf("first"), text.indexOf("last")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length() - 4));

        // Drag up. Since neither the TextView nor its container require scrolling, the cursor
        // drag should execute and the cursor should end up at the position where the finger is
        // lifted.
        onView(withId(R.id.textview)).perform(
                dragOnText(text.indexOf("last"), text.indexOf("first")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(3));
    }

    @Test
    public void testCursorDrag_vertical_whenTextViewContentsLargerThanScreen() throws Throwable {
        String text = "012345first\n\n"
                + Strings.repeat("0123456789\n\n", 10) + "012345middle"
                + Strings.repeat("0123456789\n\n", 500) + "012345last";
        onView(withId(R.id.textview)).perform(replaceText(text));
        int initialCursorPosition = 0;
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(initialCursorPosition));

        // Drag up.
        // TODO(b/145833335): Consider whether this should scroll instead of dragging the cursor.
        onView(withId(R.id.textview)).perform(
                dragOnText(text.indexOf("middle"), text.indexOf("first")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("first")));

        // Drag down.
        // TODO(b/145833335): Consider whether this should scroll instead of dragging the cursor.
        onView(withId(R.id.textview)).perform(
                dragOnText(text.indexOf("first"), text.indexOf("middle")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("middle")));
    }
}
