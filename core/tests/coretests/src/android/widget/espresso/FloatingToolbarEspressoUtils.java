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

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static android.support.test.espresso.matcher.RootMatchers.withDecorView;
import static android.support.test.espresso.matcher.ViewMatchers.hasDescendant;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.isRoot;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withTagValue;
import static android.support.test.espresso.matcher.ViewMatchers.withText;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;

import android.support.test.espresso.NoMatchingRootException;
import android.support.test.espresso.NoMatchingViewException;
import android.support.test.espresso.UiController;
import android.support.test.espresso.ViewAction;
import android.support.test.espresso.ViewInteraction;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.widget.FloatingToolbar;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Espresso utility methods for the floating toolbar.
 */
public class FloatingToolbarEspressoUtils {
    private final static Object TAG = FloatingToolbar.FLOATING_TOOLBAR_TAG;

    private FloatingToolbarEspressoUtils() {}

    private static ViewInteraction onFloatingToolBar() {
        return onView(withTagValue(is(TAG)))
                .inRoot(allOf(
                        isPlatformPopup(),
                        withDecorView(hasDescendant(withTagValue(is(TAG))))));
    }

    /**
     * Creates a {@link ViewInteraction} for the floating bar menu item with the given matcher.
     *
     * @param matcher The matcher for the menu item.
     */
    public static ViewInteraction onFloatingToolBarItem(Matcher<View> matcher) {
        return onView(matcher)
                .inRoot(withDecorView(hasDescendant(withTagValue(is(TAG)))));
    }

    /**
     * Asserts that the floating toolbar is displayed on screen.
     *
     * @throws AssertionError if the assertion fails
     */
    public static void assertFloatingToolbarIsDisplayed() {
        onFloatingToolBar().check(matches(isDisplayed()));
    }

    /**
     * Asserts that the floating toolbar is not displayed on screen.
     *
     * @throws AssertionError if the assertion fails
     * @deprecated Negative assertions are taking too long to timeout in Espresso.
     */
    @Deprecated
    public static void assertFloatingToolbarIsNotDisplayed() {
        try {
            onFloatingToolBar().check(matches(isDisplayed()));
        } catch (NoMatchingRootException | NoMatchingViewException | AssertionError e) {
            return;
        }
        throw new AssertionError("Floating toolbar is displayed");
    }

    private static void toggleOverflow() {
        final int id = com.android.internal.R.id.overflow;
        onView(allOf(withId(id), isDisplayed()))
                .inRoot(withDecorView(hasDescendant(withId(id))))
                .perform(click());
        onView(isRoot()).perform(SLEEP);
    }

    public static void sleepForFloatingToolbarPopup() {
        onView(isRoot()).perform(SLEEP);
    }

    /**
     * Asserts that the floating toolbar contains the specified item.
     *
     * @param itemLabel label of the item.
     * @throws AssertionError if the assertion fails
     */
    public static void assertFloatingToolbarContainsItem(String itemLabel) {
        try{
            onFloatingToolBar().check(matches(hasDescendant(withText(itemLabel))));
        } catch (AssertionError e) {
            try{
                toggleOverflow();
            } catch (NoMatchingViewException | NoMatchingRootException e2) {
                // No overflow items.
                throw e;
            }
            try{
                onFloatingToolBar().check(matches(hasDescendant(withText(itemLabel))));
            } finally {
                toggleOverflow();
            }
        }
    }

    /**
     * Asserts that the floating toolbar contains a specified item at a specified index.
     *
     * @param menuItemId id of the menu item
     * @param index expected index of the menu item in the floating toolbar
     * @throws AssertionError if the assertion fails
     */
    public static void assertFloatingToolbarItemIndex(final int menuItemId, final int index) {
        onFloatingToolBar().check(matches(new TypeSafeMatcher<View>() {
            private List<Integer> menuItemIds = new ArrayList<>();

            @Override
            public boolean matchesSafely(View view) {
                collectMenuItemIds(view);
                return menuItemIds.size() > index && menuItemIds.get(index) == menuItemId;
            }

            @Override
            public void describeTo(Description description) {}

            private void collectMenuItemIds(View view) {
                if (view.getTag() instanceof MenuItem) {
                    menuItemIds.add(((MenuItem) view.getTag()).getItemId());
                } else if (view instanceof ViewGroup) {
                    ViewGroup viewGroup = (ViewGroup) view;
                    for (int i = 0; i < viewGroup.getChildCount(); i++) {
                        collectMenuItemIds(viewGroup.getChildAt(i));
                    }
                }
            }
        }));
    }

    /**
     * Asserts that the floating toolbar doesn't contain the specified item.
     *
     * @param itemLabel label of the item.
     * @throws AssertionError if the assertion fails
     */
    public static void assertFloatingToolbarDoesNotContainItem(String itemLabel) {
        final Predicate<View> hasMenuItemLabel = view ->
                view.getTag() instanceof MenuItem
                        && itemLabel.equals(((MenuItem) view.getTag()).getTitle().toString());
        assertFloatingToolbarMenuItem(hasMenuItemLabel, false);
    }

    /**
     * Asserts that the floating toolbar does not contain a menu item with the specified id.
     *
     * @param menuItemId id of the menu item
     * @throws AssertionError if the assertion fails
     */
    public static void assertFloatingToolbarDoesNotContainItem(final int menuItemId) {
        final Predicate<View> hasMenuItemId = view ->
                view.getTag() instanceof MenuItem
                        && ((MenuItem) view.getTag()).getItemId() == menuItemId;
        assertFloatingToolbarMenuItem(hasMenuItemId, false);
    }

    private static void assertFloatingToolbarMenuItem(
            final Predicate<View> predicate, final boolean positiveAssertion) {
        onFloatingToolBar().check(matches(new TypeSafeMatcher<View>() {
            @Override
            public boolean matchesSafely(View view) {
                return positiveAssertion == containsItem(view);
            }

            @Override
            public void describeTo(Description description) {}

            private boolean containsItem(View view) {
                if (predicate.test(view)) {
                    return true;
                } else if (view instanceof ViewGroup) {
                    ViewGroup viewGroup = (ViewGroup) view;
                    for (int i = 0; i < viewGroup.getChildCount(); i++) {
                        if (containsItem(viewGroup.getChildAt(i))) {
                            return true;
                        }
                    }
                }
                return false;
            }
        }));
    }

    /**
     * Click specified item on the floating tool bar.
     *
     * @param itemLabel label of the item.
     */
    public static void clickFloatingToolbarItem(String itemLabel) {
        try{
            onFloatingToolBarItem(withText(itemLabel)).check(matches(isDisplayed()));
        } catch (AssertionError e) {
            // Try to find the item in the overflow menu.
            toggleOverflow();
        }
        onFloatingToolBarItem(withText(itemLabel)).perform(click());
    }

    /**
     * ViewAction to sleep to wait floating toolbar's animation.
     */
    private static final ViewAction SLEEP = new ViewAction() {
        private static final long SLEEP_DURATION = 400;

        @Override
        public Matcher<View> getConstraints() {
            return isDisplayed();
        }

        @Override
        public String getDescription() {
            return "Sleep " + SLEEP_DURATION + " ms.";
        }

        @Override
        public void perform(UiController uiController, View view) {
            uiController.loopMainThreadForAtLeast(SLEEP_DURATION);
        }
    };
}
