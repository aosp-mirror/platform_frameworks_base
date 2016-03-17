/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.Choreographer;
import android.view.ViewGroup;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerOnLeft;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerOnRight;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

public class EditorCursorTest extends ActivityInstrumentationTestCase2<TextViewActivity> {


    private final static String LTR_STRING = "aaaaaaaaaaaaaaaaaaaaaa";
    private final static String LTR_HINT = "hint";
    private final static String RTL_STRING = "مرحبا الروبوت مرحبا الروبوت مرحبا الروبوت";
    private final static String RTL_HINT = "الروبوت";
    private final static int CURSOR_BLINK_MS = 500;

    private EditText mEditText;

    public EditorCursorTest() {
        super(TextViewActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mEditText = new EditText(getActivity());
        mEditText.setTextSize(30);
        mEditText.setSingleLine(true);
        mEditText.setLines(1);
        mEditText.setPadding(15, 15, 15, 15);
        ViewGroup.LayoutParams editTextLayoutParams = new ViewGroup.LayoutParams(200,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        mEditText.setLayoutParams(editTextLayoutParams);

        final FrameLayout layout = new FrameLayout(getActivity());
        ViewGroup.LayoutParams layoutParams = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        layout.setLayoutParams(layoutParams);
        layout.addView(mEditText);

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                getActivity().setContentView(layout);
            }
        });
        getInstrumentation().waitForIdleSync();
        onView(sameInstance(mEditText)).perform(click());
    }

    @SmallTest
    public void testCursorIsInViewBoundariesWhenOnRightForLtr() {
        // Asserts that when an EditText has LTR text, and cursor is at the end (right),
        // cursor is drawn to the right edge of the view
        setEditTextText(LTR_STRING, LTR_STRING.length());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());
    }

    @SmallTest
    public void testCursorIsInViewBoundariesWhenOnLeftForLtr() {
        // Asserts that when an EditText has LTR text, and cursor is at the beginning,
        // cursor is drawn to the left edge of the view
        setEditTextText(LTR_STRING, 0);

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());
    }

    @SmallTest
    public void testCursorIsInViewBoundariesWhenOnRightForRtl() {
        // Asserts that when an EditText has RTL text, and cursor is at the end,
        // cursor is drawn to the left edge of the view
        setEditTextText(RTL_STRING, 0);

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());
    }

    @SmallTest
    public void testCursorIsInViewBoundariesWhenOnLeftForRtl() {
        // Asserts that when an EditText has RTL text, and cursor is at the beginning,
        // cursor is drawn to the right edge of the view
        setEditTextText(RTL_STRING, RTL_STRING.length());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());
    }

    /* Tests for cursor positioning with hint */
    @SmallTest
    public void testCursorIsOnLeft_withFirstStrongLtrAlgorithm() {
        setEditTextHint(null, TextView.TEXT_DIRECTION_FIRST_STRONG_LTR, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());
        assertThat(mEditText.getHint(), nullValue());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());

        setEditTextHint(RTL_HINT, TextView.TEXT_DIRECTION_FIRST_STRONG_LTR, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());

        setEditTextHint(LTR_HINT, TextView.TEXT_DIRECTION_FIRST_STRONG_LTR, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());
    }

    @SmallTest
    public void testCursorIsOnRight_withFirstStrongRtlAlgorithm() {
        setEditTextHint(null, TextView.TEXT_DIRECTION_FIRST_STRONG_RTL, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());
        assertThat(mEditText.getHint(), nullValue());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());

        setEditTextHint(LTR_HINT, TextView.TEXT_DIRECTION_FIRST_STRONG_RTL, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());

        setEditTextHint(RTL_HINT, TextView.TEXT_DIRECTION_FIRST_STRONG_RTL, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());
    }

    @SmallTest
    public void testCursorIsOnLeft_withLtrAlgorithm() {
        setEditTextHint(null, TextView.TEXT_DIRECTION_LTR, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());
        assertThat(mEditText.getHint(), nullValue());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());

        setEditTextHint(RTL_HINT, TextView.TEXT_DIRECTION_LTR, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());

        setEditTextHint(LTR_HINT, TextView.TEXT_DIRECTION_LTR, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());
    }

    @SmallTest
    public void testCursorIsOnRight_withRtlAlgorithm() {
        setEditTextHint(null, TextView.TEXT_DIRECTION_RTL, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());
        assertThat(mEditText.getHint(), nullValue());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());

        setEditTextHint(LTR_HINT, TextView.TEXT_DIRECTION_RTL, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());

        setEditTextHint(RTL_HINT, TextView.TEXT_DIRECTION_RTL, 0);
        assertThat(mEditText.getText().toString(), isEmptyString());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());
    }

    private void setEditTextProperties(final String text, final String hint,
            final Integer textDirection, final Integer selection) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (textDirection != null) mEditText.setTextDirection(textDirection);
                if (text != null) mEditText.setText(text);
                if (hint != null) mEditText.setHint(hint);
                if (selection != null) mEditText.setSelection(selection);
            }
        });
        getInstrumentation().waitForIdleSync();

        // wait for cursor to be drawn. updateCursorPositions function is called during draw() and
        // only when cursor is visible during blink.
        final CountDownLatch latch = new CountDownLatch(1);
        mEditText.postOnAnimationDelayed(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        }, CURSOR_BLINK_MS);
        try {
            assertThat("Problem while waiting for the cursor to blink",
                    latch.await(10, TimeUnit.SECONDS), equalTo(true));
        } catch (Exception e) {
            fail("Problem while waiting for the cursor to blink");
        }
    }

    private void setEditTextHint(final String hint, final int textDirection, final int selection) {
        setEditTextProperties(null, hint, textDirection, selection);
    }

    private void setEditTextText(final String text, final Integer selection) {
        setEditTextProperties(text, null, null, selection);
    }
}
