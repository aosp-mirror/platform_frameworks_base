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

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerOnLeft;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerOnRight;

import static junit.framework.Assert.fail;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import android.app.Activity;
import android.app.Instrumentation;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class EditorCursorTest {
    private final static String LTR_STRING = "aaaaaaaaaaaaaaaaaaaaaa";
    private final static String LTR_HINT = "hint";
    private final static String RTL_STRING = "مرحبا الروبوت مرحبا الروبوت مرحبا الروبوت";
    private final static String RTL_HINT = "الروبوت";
    private final static int CURSOR_BLINK_MS = 500;

    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);

    private Instrumentation mInstrumentation;
    private Activity mActivity;
    private EditText mEditText;

    @Before
    public void setUp() throws Throwable {
        mActivity = mActivityRule.getActivity();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();

        mActivityRule.runOnUiThread(() -> {
            mActivity.setContentView(R.layout.activity_editor_cursor_test);
            mEditText = mActivity.findViewById(R.id.edittext);
        });
        mInstrumentation.waitForIdleSync();
        onView(sameInstance(mEditText)).perform(click());
    }

    @Test
    public void testCursorIsInViewBoundariesWhenOnRightForLtr() throws Throwable {
        // Asserts that when an EditText has LTR text, and cursor is at the end (right),
        // cursor is drawn to the right edge of the view
        setEditTextText(LTR_STRING, LTR_STRING.length());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());
    }

    @Test
    public void testCursorIsInViewBoundariesWhenOnLeftForLtr() throws Throwable {
        // Asserts that when an EditText has LTR text, and cursor is at the beginning,
        // cursor is drawn to the left edge of the view
        setEditTextText(LTR_STRING, 0);

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());
    }

    @Test
    public void testCursorIsInViewBoundariesWhenOnRightForRtl() throws Throwable {
        // Asserts that when an EditText has RTL text, and cursor is at the end,
        // cursor is drawn to the left edge of the view
        setEditTextText(RTL_STRING, 0);

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnRight());
    }

    @Test
    public void testCursorIsInViewBoundariesWhenOnLeftForRtl() throws Throwable {
        // Asserts that when an EditText has RTL text, and cursor is at the beginning,
        // cursor is drawn to the right edge of the view
        setEditTextText(RTL_STRING, RTL_STRING.length());

        onView(sameInstance(mEditText)).check(hasInsertionPointerOnLeft());
    }

    /* Tests for cursor positioning with hint */
    @Test
    public void testCursorIsOnLeft_withFirstStrongLtrAlgorithm() throws Throwable {
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

    @Test
    public void testCursorIsOnRight_withFirstStrongRtlAlgorithm() throws Throwable {
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

    @Test
    public void testCursorIsOnLeft_withLtrAlgorithm() throws Throwable {
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

    @Test
    public void testCursorIsOnRight_withRtlAlgorithm() throws Throwable {
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
            final Integer textDirection, final Integer selection) throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            if (textDirection != null) mEditText.setTextDirection(textDirection);
            if (text != null) mEditText.setText(text);
            if (hint != null) mEditText.setHint(hint);
            if (selection != null) mEditText.setSelection(selection);
        });
        mInstrumentation.waitForIdleSync();

        // wait for cursor to be drawn. updateCursorPositions function is called during draw() and
        // only when cursor is visible during blink.
        final CountDownLatch latch = new CountDownLatch(1);
        mEditText.postOnAnimationDelayed(latch::countDown, CURSOR_BLINK_MS);
        try {
            assertThat("Problem while waiting for the cursor to blink",
                    latch.await(10, TimeUnit.SECONDS), equalTo(true));
        } catch (Exception e) {
            fail("Problem while waiting for the cursor to blink");
        }
    }

    private void setEditTextHint(final String hint, final int textDirection, final int selection)
            throws Throwable {
        setEditTextProperties(null, hint, textDirection, selection);
    }

    private void setEditTextText(final String text, final Integer selection) throws Throwable {
        setEditTextProperties(text, null, null, selection);
    }
}
