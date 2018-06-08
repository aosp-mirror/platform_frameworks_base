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
import static android.support.test.espresso.matcher.RootMatchers.withDecorView;
import static android.support.test.espresso.matcher.ViewMatchers.hasDescendant;
import static android.support.test.espresso.matcher.ViewMatchers.hasFocus;
import static android.support.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayingAtLeast;
import static android.support.test.espresso.matcher.ViewMatchers.isEnabled;
import static android.support.test.espresso.matcher.ViewMatchers.withText;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;

import android.support.test.espresso.NoMatchingRootException;
import android.support.test.espresso.NoMatchingViewException;
import android.support.test.espresso.ViewInteraction;
import android.support.test.espresso.matcher.ViewMatchers;
import android.view.View;
import android.widget.MenuPopupWindow.MenuDropDownListView;

import com.android.internal.view.menu.ListMenuItemView;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/**
 * Espresso utility methods for the context menu.
 */
public final class ContextMenuUtils {
    private ContextMenuUtils() {}

    private static ViewInteraction onContextMenu() {
        // TODO: Have more reliable way to get context menu.
        return onView(ViewMatchers.isAssignableFrom(MenuDropDownListView.class))
                .inRoot(withDecorView(hasFocus()));
    }

    /**
     * Asserts that the context menu is displayed
     *
     * @throws AssertionError if the assertion fails
     */
    private static void assertContextMenuIsDisplayed() {
        onContextMenu().check(matches(isDisplayed()));
    }

    /**
     * Asserts that the context menu is not displayed
     *
     * @throws AssertionError if the assertion fails
     */
    public static void assertContextMenuIsNotDisplayed() {
        try {
            assertContextMenuIsDisplayed();
        } catch (NoMatchingRootException | NoMatchingViewException | AssertionError e) {
            return;
        }
        throw new AssertionError("Context menu is displayed");
    }

    /**
     * Asserts that the context menu contains the specified item and the item has specified enabled
     *  state.
     *
     * @param itemLabel label of the item.
     * @param enabled enabled state of the item.
     * @throws AssertionError if the assertion fails
     */
    private static void asssertContextMenuContainsItemWithEnabledState(String itemLabel,
            boolean enabled) {
        onContextMenu().check(matches(
                hasDescendant(getVisibleMenuItemMatcher(itemLabel, enabled))));
    }

    private static Matcher<View> getVisibleMenuItemMatcher(String itemLabel, boolean enabled) {
        return allOf(
                isAssignableFrom(ListMenuItemView.class),
                hasDescendant(withText(itemLabel)),
                enabled ? isEnabled() : not(isEnabled()),
                isDisplayingAtLeast(90));
    }

    /**
     * Asserts that the context menu contains the specified item and the item is enabled.
     *
     * @param itemLabel label of the item.
     * @throws AssertionError if the assertion fails
     */
    public static void assertContextMenuContainsItemEnabled(String itemLabel) {
        asssertContextMenuContainsItemWithEnabledState(itemLabel, true);
    }

    /**
     * Asserts that the context menu contains the specified item and the item is disabled.
     *
     * @param itemLabel label of the item.
     * @throws AssertionError if the assertion fails
     */
    public static void assertContextMenuContainsItemDisabled(String itemLabel) {
        asssertContextMenuContainsItemWithEnabledState(itemLabel, false);
    }

    /**
     * Asserts that the context menu window is aligned to a given view with a given offset.
     *
     * @param anchor Anchor view.
     * @param offsetX x offset
     * @param offsetY y offset.
     * @throws AssertionError if the assertion fails
     */
    public static void assertContextMenuAlignment(View anchor, int offsetX, int offsetY) {
        int [] expectedLocation = new int[2];
        anchor.getLocationOnScreen(expectedLocation);
        expectedLocation[0] += offsetX;
        expectedLocation[1] += offsetY;

        final boolean rtl = anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        onContextMenu().check(matches(new TypeSafeMatcher<View>() {
            @Override
            public void describeTo(Description description) {
                description.appendText("root view ");
                description.appendText(rtl ? "right" : "left");
                description.appendText("=");
                description.appendText(Integer.toString(offsetX));
                description.appendText(", top=");
                description.appendText(Integer.toString(offsetY));
            }

            @Override
            public boolean matchesSafely(View view) {
                View rootView = view.getRootView();
                int [] actualLocation = new int[2];
                rootView.getLocationOnScreen(actualLocation);
                if (rtl) {
                    actualLocation[0] += rootView.getWidth();
                }
                return expectedLocation[0] == actualLocation[0]
                    && expectedLocation[1] == actualLocation[1];
            }
        }));
    }

    /**
     * Check is the menu item is clickable (i.e. visible and enabled).
     *
     * @param itemLabel Label of the item.
     * @return True if the menu item is clickable.
     */
    public static boolean isMenuItemClickable(String itemLabel) {
        try {
            onContextMenu().check(matches(
                    hasDescendant(getVisibleMenuItemMatcher(itemLabel, true))));
            return true;
        } catch (NoMatchingRootException | NoMatchingViewException | AssertionError e) {
            return false;
        }
    }

    /**
     * Click on a menu item with the specified label
     * @param itemLabel Label of the item.
     */
    public static void clickMenuItem(String itemLabel) {
        onView(getVisibleMenuItemMatcher(itemLabel, true))
                .inRoot(withDecorView(hasFocus())).perform(click());
    }
}
