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

package android.widget.espresso;

import static androidx.test.espresso.matcher.ViewMatchers.assertThat;

import static com.android.internal.util.Preconditions.checkNotNull;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.number.IsCloseTo.closeTo;

import android.annotation.IntDef;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.ViewAssertion;

import junit.framework.AssertionFailedError;

import org.hamcrest.Matcher;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A collection of assertions on a {@link android.widget.TextView}.
 */
public final class TextViewAssertions {

    private TextViewAssertions() {}

    /**
     * Returns a {@link ViewAssertion} that asserts that the text view has a specified
     * selection.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a text view displayed on screen
     * <ul>
     *
     * @param selection  The expected selection.
     */
    public static ViewAssertion hasSelection(String selection) {
        return hasSelection(is(selection));
    }

    /**
     * Returns a {@link ViewAssertion} that asserts that the text view has a specified
     * selection.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a text view displayed on screen
     * <ul>
     *
     * @param selection  A matcher representing the expected selection.
     */
    public static ViewAssertion hasSelection(Matcher<String> selection) {
        return new TextSelectionAssertion(selection);
    }

    /**
     * Returns a {@link ViewAssertion} that asserts that the text view insertion pointer is at
     * a specified index.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a text view displayed on screen
     * <ul>
     *
     * @param index  The expected index.
     */
    public static ViewAssertion hasInsertionPointerAtIndex(int index) {
        return hasInsertionPointerAtIndex(is(index));
    }

    /**
     * Returns a {@link ViewAssertion} that asserts that the text view insertion pointer is at
     * a specified index.<br>
     * <br>
     * View constraints:
     * <ul>
     * <li>must be a text view displayed on screen
     * <ul>
     *
     * @param index  A matcher representing the expected index.
     */
    public static ViewAssertion hasInsertionPointerAtIndex(final Matcher<Integer> index) {
        return (view, exception) -> {
            if (view instanceof TextView) {
                TextView textView = (TextView) view;
                int selectionStart = textView.getSelectionStart();
                int selectionEnd = textView.getSelectionEnd();
                try {
                    assertThat(selectionStart, index);
                    assertThat(selectionEnd, index);
                } catch (IndexOutOfBoundsException e) {
                    throw new AssertionFailedError(e.getMessage());
                }
            } else {
                throw new AssertionFailedError("TextView not found");
            }
        };
    }

    /**
     * Returns a {@link ViewAssertion} that asserts that the EditText insertion pointer is on
     * the left edge.
     */
    public static ViewAssertion hasInsertionPointerOnLeft() {
        return new CursorPositionAssertion(CursorPositionAssertion.LEFT);
    }

    /**
     * Returns a {@link ViewAssertion} that asserts that the EditText insertion pointer is on
     * the right edge.
     */
    public static ViewAssertion hasInsertionPointerOnRight() {
        return new CursorPositionAssertion(CursorPositionAssertion.RIGHT);
    }

    /**
     * Returns a {@link ViewAssertion} that asserts that the TextView does not contain styled text.
     */
    public static ViewAssertion doesNotHaveStyledText() {
        return (view, exception) -> {
            final CharSequence text = ((TextView) view).getText();
            if (text instanceof Spanned && !TextUtils.hasStyleSpan((Spanned) text)) {
                return;
            }
            throw new AssertionFailedError("TextView has styled text");
        };
    }

    /**
     * A {@link ViewAssertion} to check the selected text in a {@link TextView}.
     */
    private static final class TextSelectionAssertion implements ViewAssertion {

        private final Matcher<String> mSelection;

        public TextSelectionAssertion(Matcher<String> selection) {
            mSelection = checkNotNull(selection);
        }

        @Override
        public void check(View view, NoMatchingViewException exception) {
            if (view instanceof TextView) {
                TextView textView = (TextView) view;
                int selectionStart = textView.getSelectionStart();
                int selectionEnd = textView.getSelectionEnd();
                try {
                    String selectedText = textView.getText()
                            .subSequence(selectionStart, selectionEnd)
                            .toString();
                    assertThat(selectedText, mSelection);
                } catch (IndexOutOfBoundsException e) {
                    throw new AssertionFailedError(e.getMessage());
                }
            } else {
                throw new AssertionFailedError("TextView not found");
            }
        }
    }

    /**
     * {@link ViewAssertion} to check that EditText cursor is on a given position.
     */
    static class CursorPositionAssertion implements ViewAssertion {

        @Retention(RetentionPolicy.SOURCE)
        @IntDef({LEFT, RIGHT})
        public @interface CursorEdgePositionType {}
        public static final int LEFT = 0;
        public static final int RIGHT = 1;

        private final int mPosition;

        private CursorPositionAssertion(@CursorEdgePositionType int position) {
            this.mPosition = position;
        }

        @Override
        public void check(View view, NoMatchingViewException exception) {
            if (!(view instanceof EditText)) {
                throw new AssertionFailedError("View should be an instance of EditText");
            }
            EditText editText = (EditText) view;
            Drawable drawable = editText.getEditorForTesting().getCursorDrawable();
            Rect drawableBounds = drawable.getBounds();
            Rect drawablePadding = new Rect();
            drawable.getPadding(drawablePadding);

            final int diff;
            final String positionStr;
            switch (mPosition) {
                case LEFT:
                    positionStr = "left";
                    diff = drawableBounds.left - editText.getScrollX() + drawablePadding.left;
                    break;
                case RIGHT:
                    positionStr = "right";
                    int maxRight = editText.getWidth() - editText.getCompoundPaddingRight()
                            - editText.getCompoundPaddingLeft() + editText.getScrollX();
                    diff = drawableBounds.right - drawablePadding.right - maxRight;
                    break;
                default:
                    throw new AssertionFailedError("Unknown position for cursor assertion");
            }

            assertThat("Cursor should be on the " + positionStr, Double.valueOf(diff),
                    closeTo(0f, 1f));
        }
    }
}
