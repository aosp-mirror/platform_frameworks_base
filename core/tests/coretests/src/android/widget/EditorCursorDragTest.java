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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.text.Layout;
import android.util.ArraySet;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import com.google.common.base.Strings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class EditorCursorDragTest {
    private static final String LOG_TAG = EditorCursorDragTest.class.getSimpleName();

    private static final AtomicLong sTicker = new AtomicLong(1);

    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);

    private Instrumentation mInstrumentation;
    private Activity mActivity;
    private Set<MotionEvent> mMotionEvents = new ArraySet<>();

    @Before
    public void before() throws Throwable {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mActivity = mActivityRule.getActivity();
    }

    @After
    public void after() throws Throwable {
        for (MotionEvent event : mMotionEvents) {
            event.recycle();
        }
        mMotionEvents.clear();
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

        // Swipe along a diagonal path. This should drag the cursor. Because we snap the finger to
        // the handle as the touch moves downwards (and because we have some slop to avoid jumping
        // across lines), the cursor position will end up higher than the finger position.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("3")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("1")));

        // Swipe right-down along a very steep diagonal path. This should not drag the cursor.
        // Normally this would trigger a scroll, but since the full view fits on the screen there
        // is nothing to scroll and the gesture will trigger a selection drag.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("7")));
        onView(withId(R.id.textview)).check(hasSelection(not(emptyString())));

        // Tap to clear the selection.
        int index = text.indexOf("line9");
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(index));
        onView(withId(R.id.textview)).check(hasSelection(emptyString()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(index));

        // Swipe right-up along a very steep diagonal path. This should not drag the cursor.
        // Normally this would trigger a scroll, but since the full view fits on the screen there
        // is nothing to scroll and the gesture will trigger a selection drag.
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

        // Swipe along a diagonal path. This should drag the cursor. Because we snap the finger to
        // the handle as the touch moves downwards (and because we have some slop to avoid jumping
        // across lines), the cursor position will end up higher than the finger position.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("3")));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("1")));

        // Swipe right-down along a very steep diagonal path. This should not drag the cursor.
        // Normally this would trigger a scroll up, but since the view is already at the top there
        // is nothing to scroll and the gesture will trigger a selection drag.
        onView(withId(R.id.textview)).perform(dragOnText(text.indexOf("line1"), text.indexOf("7")));
        onView(withId(R.id.textview)).check(hasSelection(not(emptyString())));

        // Tap to clear the selection.
        int index = text.indexOf("line9");
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(index));
        onView(withId(R.id.textview)).check(hasSelection(emptyString()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(index));

        // Swipe right-up along a very steep diagonal path. This should not drag the cursor. This
        // will trigger a downward scroll and the cursor position will not change.
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
        MotionEvent event1 = downEvent(tv, event1Time, event1Time, 5f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event1));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event2Time = 1002;
        MotionEvent event2 = moveEvent(tv, event1Time, event2Time, 50f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event2));
        assertTrue(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event3Time = 1003;
        MotionEvent event3 = moveEvent(tv, event1Time, event3Time, 100f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event3));
        assertTrue(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event4Time = 2004;
        MotionEvent event4 = upEvent(tv, event1Time, event4Time, 100f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event4));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        // Simulate a quick tap after the drag, near the location where the drag ended.
        long event5Time = 2005;
        MotionEvent event5 = downEvent(tv, event5Time, event5Time, 90f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event5));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event6Time = 2006;
        MotionEvent event6 = upEvent(tv, event5Time, event6Time, 90f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event6));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        // Simulate another quick tap in the same location; now selection should be triggered.
        long event7Time = 2007;
        MotionEvent event7 = downEvent(tv, event7Time, event7Time, 90f, 10f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event7));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertTrue(editor.getSelectionController().isCursorBeingModified());
    }

    @Test
    public void testEditor_onTouchEvent_mouseDrag() throws Throwable {
        String text = "testEditor_onTouchEvent_mouseDrag";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));

        TextView tv = mActivity.findViewById(R.id.textview);
        Editor editor = tv.getEditorForTesting();

        // Simulate a mouse click and drag. This should NOT trigger a cursor drag.
        long event1Time = 1001;
        MotionEvent event1 = mouseDownEvent(tv, event1Time, event1Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event1));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event2Time = 1002;
        MotionEvent event2 = mouseMoveEvent(tv, event1Time, event2Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event2));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertTrue(editor.getSelectionController().isCursorBeingModified());

        long event3Time = 1003;
        MotionEvent event3 = mouseUpEvent(tv, event1Time, event3Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event3));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());
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
        MotionEvent event1 = downEvent(tv, event1Time, event1Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event1));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event2Time = 1002;
        MotionEvent event2 = moveEvent(tv, event1Time, event2Time, 21f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event2));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event3Time = 1003;
        MotionEvent event3 = moveEvent(tv, event1Time, event3Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event3));
        assertTrue(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event4Time = 1004;
        MotionEvent event4 = upEvent(tv, event1Time, event4Time, 120f, 30f);
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
        MotionEvent event1 = downEvent(tv, event1Time, event1Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event1));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event2Time = 1002;
        MotionEvent event2 = upEvent(tv, event1Time, event2Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event2));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());

        long event3Time = 1003;
        MotionEvent event3 = downEvent(tv, event3Time, event3Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event3));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertTrue(editor.getSelectionController().isCursorBeingModified());

        long event4Time = 1004;
        MotionEvent event4 = moveEvent(tv, event3Time, event4Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event4));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertTrue(editor.getSelectionController().isCursorBeingModified());

        long event5Time = 1005;
        MotionEvent event5 = upEvent(tv, event3Time, event5Time, 120f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event5));
        assertFalse(editor.getInsertionController().isCursorBeingModified());
        assertFalse(editor.getSelectionController().isCursorBeingModified());
    }

    @Suppress // b/149712851
    @Test // Reproduces b/147366705
    public void testCursorDrag_nonSelectableTextView() throws Throwable {
        String text = "Hello world!";
        TextView tv = mActivity.findViewById(R.id.nonselectable_textview);
        tv.setText(text);
        Editor editor = tv.getEditorForTesting();
        assertThat(editor).isNotNull();

        // Simulate a tap. No error should be thrown.
        long event1Time = 1001;
        MotionEvent event1 = downEvent(tv, event1Time, event1Time, 20f, 30f);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event1));

        // Swipe left to right. No error should be thrown.
        onView(withId(R.id.nonselectable_textview)).perform(
                dragOnText(text.indexOf("llo"), text.indexOf("!")));
    }

    @Test
    public void testCursorDrag_slop() throws Throwable {
        String text = "line1: This is the 1st line: A\n"
                    + "line2: This is the 2nd line: B\n"
                    + "line3: This is the 3rd line: C\n";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));
        TextView tv = mActivity.findViewById(R.id.textview);

        // Simulate a drag where the finger moves slightly up and down (above and below the original
        // line where the drag started). The cursor should just move along the original line without
        // jumping up or down across lines.
        MotionEventInfo[] events = new MotionEventInfo[]{
                // Start dragging along the second line
                motionEventInfo(text.indexOf("line2"), 1.0f),
                motionEventInfo(text.indexOf("This is the 2nd"), 1.0f),
                // Move to the bottom of the first line; cursor should remain on second line
                motionEventInfo(text.indexOf("he 1st"), 0.0f, text.indexOf("he 2nd")),
                // Move to the top of the third line; cursor should remain on second line
                motionEventInfo(text.indexOf("e: C"), 1.0f, text.indexOf("e: B")),
                motionEventInfo(text.indexOf("B"), 0.0f)
        };
        simulateDrag(tv, events, true);
    }

    @Test
    public void testCursorDrag_snapToHandle() throws Throwable {
        String text = "line1: This is the 1st line: A\n"
                    + "line2: This is the 2nd line: B\n"
                    + "line3: This is the 3rd line: C\n"
                    + "line4: This is the 4th line: D\n";
        onView(withId(R.id.textview)).perform(replaceText(text));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));
        TextView tv = mActivity.findViewById(R.id.textview);

        // When the drag motion moves downward, we delay jumping to the lower line to allow the
        // user's touch to snap to the cursor's handle. Once the finger is over the handle, we
        // position the cursor above the user's actual touch (offset such that the finger remains
        // over the handle rather than on top of the cursor vertical bar). This improves the
        // visibility of the cursor and the text underneath.
        MotionEventInfo[] events = new MotionEventInfo[]{
                // Start dragging along the first line
                motionEventInfo(text.indexOf("line1"), 1.0f),
                motionEventInfo(text.indexOf("This is the 1st"), 1.0f),
                // Move to the middle of the fourth line; cursor should end up on second line
                motionEventInfo(text.indexOf("he 4th"), 0.5f, text.indexOf("he 2nd")),
                // Move to the middle of the second line; cursor should end up on the first line
                motionEventInfo(text.indexOf("he 2nd"), 0.5f, text.indexOf("he 1st"))
        };
        simulateDrag(tv, events, true);

        // If the drag motion hasn't moved downward (ie, we haven't had a chance to snap to the
        // handle), we position the cursor directly at the touch position.
        events = new MotionEventInfo[]{
                // Start dragging along the third line
                motionEventInfo(text.indexOf("line3"), 1.0f),
                motionEventInfo(text.indexOf("This is the 3rd"), 1.0f),
                // Move to the middle of the second line; cursor should end up on the second line
                motionEventInfo(text.indexOf("he 2nd"), 0.5f, text.indexOf("he 2nd")),
        };
        simulateDrag(tv, events, true);
    }

    @Test
    public void testCursorDrag_snapDistance() throws Throwable {
        String text = "line1: This is the 1st line: A\n"
                + "line2: This is the 2nd line: B\n"
                + "line3: This is the 3rd line: C\n";
        onView(withId(R.id.textview)).perform(replaceText(text));
        TextView tv = mActivity.findViewById(R.id.textview);
        Editor editor = tv.getEditorForTesting();
        final int startIndex = text.indexOf("he 2nd");
        Layout layout = tv.getLayout();
        final float cursorStartX = layout.getPrimaryHorizontal(startIndex) + tv.getTotalPaddingLeft();
        final float cursorStartY = layout.getLineTop(1) + tv.getTotalPaddingTop();
        final float dragHandleStartX = 20;
        final float dragHandleStartY = 20;

        // Drag the handle from the 2nd line to the 3rd line.
        tapAtPoint(tv, cursorStartX, cursorStartY);
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(startIndex));
        View handleView = editor.getInsertionController().getHandle();
        final int rawYOfHandleDrag = dragDownUntilLineChange(
                handleView, dragHandleStartX, dragHandleStartY, tv.getSelectionStart());

        // Drag the cursor from the 2nd line to the 3rd line.
        tapAtPoint(tv, cursorStartX, cursorStartY);
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(startIndex));
        final int rawYOfCursorDrag =
                dragDownUntilLineChange(tv, cursorStartX, cursorStartY, tv.getSelectionStart());

        // Drag the handle with touch through from the 2nd line to the 3rd line.
        tv.getEditorForTesting().setFlagInsertionHandleGesturesEnabled(true);
        tapAtPoint(tv, cursorStartX, cursorStartY);
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(startIndex));
        handleView = editor.getInsertionController().getHandle();
        int rawYOfHandleDragWithTouchThrough =
                dragDownUntilLineChange(handleView, dragHandleStartX, dragHandleStartY, tv.getSelectionStart());

        String msg = String.format(
                "rawYOfHandleDrag: %d, rawYOfCursorDrag: %d, rawYOfHandleDragWithTouchThrough: %d",
                rawYOfHandleDrag, rawYOfCursorDrag, rawYOfHandleDragWithTouchThrough);
        final int max = Math.max(
                rawYOfCursorDrag, Math.max(rawYOfHandleDrag, rawYOfHandleDragWithTouchThrough));
        final int min = Math.min(
                rawYOfCursorDrag, Math.min(rawYOfHandleDrag, rawYOfHandleDragWithTouchThrough));
        // The drag step is 5 pixels in dragDownUntilLineChange().
        // The difference among the 3 raw Y values should be no bigger than the drag step.
        assertWithMessage(msg).that(max - min).isLessThan(6);
    }

    private void dispatchTouchEvent(View view, MotionEvent event) {
        mInstrumentation.runOnMainSync(() -> view.dispatchTouchEvent(event));
    }

    private void tapAtPoint(TextView tv, final float x, final float y) {
        long downTime = sTicker.addAndGet(10_000);
        dispatchTouchEvent(tv, downEvent(tv, downTime, downTime, x, y));
        dispatchTouchEvent(tv, upEvent(tv, downTime, downTime + 1, x, y));
    }

    private int dragDownUntilLineChange(View view, final float startX, final float startY,
            final int startOffset) {
        TextView tv = mActivity.findViewById(R.id.textview);
        final int startLine = tv.getLayout().getLineForOffset(startOffset);

        int rawY = 0;
        long downTime = sTicker.addAndGet(10_000);
        long eventTime = downTime;
        // Move horizontally first to initiate the cursor drag.
        dispatchTouchEvent(view, downEvent(view, downTime, eventTime++, startX, startY));
        dispatchTouchEvent(view, moveEvent(view, downTime, eventTime++, startX + 50, startY));
        dispatchTouchEvent(view, moveEvent(view, downTime, eventTime++, startX, startY));
        // Move downwards 5 pixels at a time until a line change occurs.
        for (int i = 0; i < 200; i++) {
            MotionEvent ev = moveEvent(view, downTime, eventTime++, startX, startY + i * 5);
            rawY = (int) ev.getRawY();
            dispatchTouchEvent(view, ev);
            if (tv.getLayout().getLineForOffset(tv.getSelectionStart()) > startLine) {
                break;
            }
        }
        String msg = String.format("The cursor didn't jump from %d!", startOffset);
        assertWithMessage(msg).that(
                tv.getLayout().getLineForOffset(tv.getSelectionStart())).isGreaterThan(startLine);
        dispatchTouchEvent(view, upEvent(view, downTime, eventTime, startX, startY));
        return rawY;
    }

    private MotionEvent obtainTouchEvent(
            View view, int action, long downTime, long eventTime, float x, float y) {
        Rect r = new Rect();
        view.getBoundsOnScreen(r);
        float rawX = x + r.left;
        float rawY = y + r.top;
        MotionEvent event =
                MotionEvent.obtain(downTime, eventTime, action, rawX, rawY, 0);
        view.toLocalMotionEvent(event);
        mMotionEvents.add(event);
        return event;
    }

    private MotionEvent obtainMouseEvent(
            View view, int action, long downTime, long eventTime, float x, float y) {
        MotionEvent event = obtainTouchEvent(view, action, downTime, eventTime, x, y);
        event.setSource(InputDevice.SOURCE_MOUSE);
        if (action != MotionEvent.ACTION_UP) {
            event.setButtonState(MotionEvent.BUTTON_PRIMARY);
        }
        return event;
    }

    private MotionEvent downEvent(View view, long downTime, long eventTime, float x, float y) {
        return obtainTouchEvent(view, MotionEvent.ACTION_DOWN, downTime, eventTime, x, y);
    }

    private MotionEvent moveEvent(View view, long downTime, long eventTime, float x, float y) {
        return obtainTouchEvent(view, MotionEvent.ACTION_MOVE, downTime, eventTime, x, y);
    }

    private MotionEvent upEvent(View view, long downTime, long eventTime, float x, float y) {
        return obtainTouchEvent(view, MotionEvent.ACTION_UP, downTime, eventTime, x, y);
    }

    private MotionEvent mouseDownEvent(View view, long downTime, long eventTime, float x, float y) {
        return obtainMouseEvent(view, MotionEvent.ACTION_DOWN, downTime, eventTime, x, y);
    }

    private MotionEvent mouseMoveEvent(View view, long downTime, long eventTime, float x, float y) {
        return obtainMouseEvent(view, MotionEvent.ACTION_MOVE, downTime, eventTime, x, y);
    }

    private MotionEvent mouseUpEvent(View view, long downTime, long eventTime, float x, float y) {
        return obtainMouseEvent(view, MotionEvent.ACTION_UP, downTime, eventTime, x, y);
    }

    public static MotionEventInfo motionEventInfo(int index, float ratioToLineTop) {
        return new MotionEventInfo(index, ratioToLineTop, index);
    }

    public static MotionEventInfo motionEventInfo(int index, float ratioToLineTop,
            int expectedCursorIndex) {
        return new MotionEventInfo(index, ratioToLineTop, expectedCursorIndex);
    }

    private static class MotionEventInfo {
        public final int index;
        public final float ratioToLineTop; // 0.0 = bottom of line, 0.5 = middle of line, etc
        public final int expectedCursorIndex;

        private MotionEventInfo(int index, float ratioToLineTop, int expectedCursorIndex) {
            this.index = index;
            this.ratioToLineTop = ratioToLineTop;
            this.expectedCursorIndex = expectedCursorIndex;
        }

        public float[] getCoordinates(TextView textView) {
            Layout layout = textView.getLayout();
            int line = layout.getLineForOffset(index);
            float x = layout.getPrimaryHorizontal(index) + textView.getTotalPaddingLeft();
            int bottom = layout.getLineBottom(line);
            int top = layout.getLineTop(line);
            float y = bottom - ((bottom - top) * ratioToLineTop) + textView.getTotalPaddingTop();
            return new float[]{x, y};
        }
    }

    private void simulateDrag(TextView tv, MotionEventInfo[] events, boolean runAssertions)
            throws Exception {
        Editor editor = tv.getEditorForTesting();

        float[] downCoords = events[0].getCoordinates(tv);
        long downEventTime = sTicker.addAndGet(10_000);
        MotionEvent downEvent = downEvent(tv,  downEventTime, downEventTime,
                downCoords[0], downCoords[1]);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(downEvent));

        for (int i = 1; i < events.length; i++) {
            float[] moveCoords = events[i].getCoordinates(tv);
            long eventTime = downEventTime + i;
            MotionEvent event = moveEvent(tv, downEventTime, eventTime, moveCoords[0],
                    moveCoords[1]);
            mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(event));
            assertCursorPosition(tv, events[i].expectedCursorIndex, runAssertions);
        }

        MotionEventInfo lastEvent = events[events.length - 1];
        float[] upCoords = lastEvent.getCoordinates(tv);
        long upEventTime = downEventTime + events.length;
        MotionEvent upEvent = upEvent(tv, downEventTime, upEventTime, upCoords[0], upCoords[1]);
        mInstrumentation.runOnMainSync(() -> editor.onTouchEvent(upEvent));
    }

    private static void assertCursorPosition(TextView tv, int expectedPosition,
            boolean runAssertions) {
        String textAfterExpectedPos = getTextAfterIndex(tv, expectedPosition, 15);
        String textAfterActualPos = getTextAfterIndex(tv, tv.getSelectionStart(), 15);
        String msg = "Expected cursor at " + expectedPosition + ", just before \""
                + textAfterExpectedPos + "\". Cursor is at " + tv.getSelectionStart()
                + ", just before \"" + textAfterActualPos + "\".";
        Log.d(LOG_TAG, msg);
        if (runAssertions) {
            assertWithMessage(msg).that(tv.getSelectionStart()).isEqualTo(expectedPosition);
            assertThat(tv.getSelectionEnd()).isEqualTo(expectedPosition);
        }
    }

    private static String getTextAfterIndex(TextView tv, int position, int maxLength) {
        int end = Math.min(position + maxLength, tv.getText().length());
        try {
            String afterPosition = tv.getText().subSequence(position, end).toString();
            if (afterPosition.indexOf('\n') > 0) {
                afterPosition = afterPosition.substring(0, afterPosition.indexOf('\n'));
            }
            return afterPosition;
        } catch (StringIndexOutOfBoundsException e) {
            Log.d(LOG_TAG, "Invalid target position: position=" + position + ", length="
                    + tv.getText().length() + ", end=" + end);
            return "";
        }
    }
}
