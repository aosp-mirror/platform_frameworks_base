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

import static android.support.test.espresso.matcher.ViewMatchers.assertThat;
import static com.android.internal.util.Preconditions.checkNotNull;
import static org.hamcrest.Matchers.is;

import android.support.test.espresso.NoMatchingViewException;
import android.support.test.espresso.ViewAssertion;
import android.view.View;
import android.widget.TextView;

import junit.framework.AssertionFailedError;
import org.hamcrest.Matcher;

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
        return new TextSelectionAssertion(is(selection));
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
}
