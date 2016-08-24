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
 * limitations under the License
 */

package android.widget.espresso;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.RootMatchers.withDecorView;
import static android.support.test.espresso.matcher.ViewMatchers.hasDescendant;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;

import org.hamcrest.Matcher;

import android.support.test.espresso.NoMatchingRootException;
import android.support.test.espresso.NoMatchingViewException;
import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.ViewInteraction;
import android.support.test.espresso.action.GeneralLocation;
import android.support.test.espresso.action.Press;
import android.support.test.espresso.action.Tap;
import android.view.View;

public final class SuggestionsPopupwindowUtils {
    private static final int id = com.android.internal.R.id.suggestionWindowContainer;

    private SuggestionsPopupwindowUtils() {};

    public static ViewInteraction onSuggestionsPopup() {
        return onView(withId(id)).inRoot(withDecorView(hasDescendant(withId(id))));
    }

    private static ViewInteraction onSuggestionsPopupItem(Matcher<View> matcher) {
        return onView(matcher).inRoot(withDecorView(hasDescendant(withId(id))));
    }

    /**
     * Asserts that the suggestions popup is displayed on screen.
     *
     * @throws AssertionError if the assertion fails
     */
    public static void assertSuggestionsPopupIsDisplayed() {
        onSuggestionsPopup().check(matches(isDisplayed()));
    }

    /**
     * Asserts that the suggestions popup is not displayed on screen.
     *
     * @throws AssertionError if the assertion fails
     */
    public static void assertSuggestionsPopupIsNotDisplayed() {
        try {
            onSuggestionsPopup().check(matches(isDisplayed()));
        } catch (NoMatchingRootException | NoMatchingViewException | AssertionError e) {
            return;
        }
        throw new AssertionError("Suggestions popup is displayed");
    }

    /**
     * Asserts that the suggestions popup contains the specified item.
     *
     * @param itemLabel label of the item.
     * @throws AssertionError if the assertion fails
     */
    public static void assertSuggestionsPopupContainsItem(String itemLabel) {
        onSuggestionsPopupItem(withText(itemLabel)).check(matches(isDisplayed()));
    }

    /**
     * Click on the specified item in the suggestions popup.
     *
     * @param itemLabel label of the item.
     */
    public static void clickSuggestionsPopupItem(String itemLabel) {
        onSuggestionsPopupItem(withText(itemLabel)).perform(new SuggestionItemClickAction());
    }

    /**
     * Click action to avoid checking ViewClickAction#getConstraints().
     * TODO: Use Espresso.onData instead of this.
     */
    private static final class SuggestionItemClickAction implements ViewAction {
        private final ViewClickAction mViewClickAction;

        public SuggestionItemClickAction() {
            mViewClickAction =
                    new ViewClickAction(Tap.SINGLE, GeneralLocation.VISIBLE_CENTER, Press.FINGER);
        }

        @Override
        public Matcher<View> getConstraints() {
            return isDisplayed();
        }

        @Override
        public String getDescription() {
            return mViewClickAction.getDescription();
        }

        @Override
        public void perform(UiController uiController, View view) {
            mViewClickAction.perform(uiController, view);
        }
    }
}
