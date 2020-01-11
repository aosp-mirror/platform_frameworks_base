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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.MotionEvent;

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

    @Test
    public void testEditor_onTouchEvent_quickTapAfterDrag() throws Throwable {
        String text = "Hi world!";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        TextView tv = mActivity.findViewById(R.id.textview);
        Editor editor = tv.getEditorForTesting();

        // Simulate a tap-and-drag gesture.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, 5f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event1));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event2Time = 1002;
        MotionEvent event2 = moveEvent(event1Time, event2Time, 50f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event2));
        assertTrue(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event3Time = 1003;
        MotionEvent event3 = moveEvent(event1Time, event3Time, 100f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event3));
        assertTrue(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event4Time = 2004;
        MotionEvent event4 = upEvent(event1Time, event4Time, 100f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event4));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        // Simulate a quick tap after the drag, near the location where the drag ended.
        long event5Time = 2005;
        MotionEvent event5 = downEvent(event5Time, event5Time, 90f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event5));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event6Time = 2006;
        MotionEvent event6 = upEvent(event5Time, event6Time, 90f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event6));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        // Simulate another quick tap in the same location; now selection should be triggered.
        long event7Time = 2007;
        MotionEvent event7 = downEvent(event7Time, event7Time, 90f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event7));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertTrue(editor.getSelectionController().isCursorBeingModified());
    }

    @Test
    public void testEditor_onTouchEvent_cursorDrag() throws Throwable {
        String text = "testEditor_onTouchEvent_cursorDrag";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        TextView tv = mActivity.findViewById(R.id.textview);
        Editor editor = tv.getEditorForTesting();

        // Simulate a tap-and-drag gesture. This should trigger a cursor drag.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event1));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event2Time = 1002;
        MotionEvent event2 = moveEvent(event1Time, event2Time, 21f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event2));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event3Time = 1003;
        MotionEvent event3 = moveEvent(event1Time, event3Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event3));
        assertTrue(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event4Time = 1004;
        MotionEvent event4 = upEvent(event1Time, event4Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event4));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());
    }

    @Test
    public void testEditor_onTouchEvent_selectionDrag() throws Throwable {
        String text = "testEditor_onTouchEvent_selectionDrag";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        TextView tv = mActivity.findViewById(R.id.textview);
        Editor editor = tv.getEditorForTesting();

        // Simulate a double-tap followed by a drag. This should trigger a selection drag.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(event1Time, event1Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event1));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event2Time = 1002;
        MotionEvent event2 = upEvent(event1Time, event2Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event2));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event3Time = 1003;
        MotionEvent event3 = downEvent(event3Time, event3Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event3));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertTrue(editor.getSelectionController().isCursorBeingModified());

        long event4Time = 1004;
        MotionEvent event4 = moveEvent(event3Time, event4Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event4));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertTrue(editor.getSelectionController().isCursorBeingModified());

        long event5Time = 1005;
        MotionEvent event5 = upEvent(event3Time, event5Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event5));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());
    }

    private static MotionEvent downEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0);
    }

    private static MotionEvent upEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0);
    }

    private static MotionEvent moveEvent(long downTime, long eventTime, float x, float y) {
        return MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 0);
    }
}
