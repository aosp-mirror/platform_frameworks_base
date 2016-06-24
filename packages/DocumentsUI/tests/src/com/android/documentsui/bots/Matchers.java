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

package com.android.documentsui.bots;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withClassName;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withResourceName;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.Matchers.endsWith;

import android.support.test.espresso.ViewInteraction;
import android.support.test.espresso.matcher.ViewMatchers;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toolbar;

import com.android.documentsui.R;
import com.android.internal.view.menu.ActionMenuItemView;

import org.hamcrest.Matcher;

/**
 * Handy matchers useful for finding stuff in the UI. Use with Espresso testing.
 */
@SuppressWarnings("unchecked")
public final class Matchers {

    private Matchers() {}

    public static final Matcher<View> TOOLBAR = allOf(
            isAssignableFrom(Toolbar.class),
            withId(R.id.toolbar));

    public static final Matcher<View> ACTIONBAR = allOf(
            withClassName(endsWith("ActionBarContextView")));

    public static final Matcher<View> SEARCH_MENU = allOf(
            withId(R.id.menu_search),
            isDisplayed());

    public static final Matcher<View> SEARCH_BUTTON = allOf(
            isAssignableFrom(ImageView.class),
            withResourceName("search_button"));

    public static final Matcher<View> MENU_SEARCH = allOf(
            isAssignableFrom(ActionMenuItemView.class),
            withResourceName("menu_search"));

    public static final Matcher<View> DROPDOWN_BREADCRUMB = withId(
            R.id.dropdown_breadcrumb);

    public static final Matcher<View> HORIZONTAL_BREADCRUMB = withId(
            R.id.horizontal_breadcrumb);

    // When any 'ol breadcrumb will do. Could be dropdown or horizontal.
    public static final Matcher<View> BREADCRUMB = anyOf(
            DROPDOWN_BREADCRUMB, HORIZONTAL_BREADCRUMB);

    public static final Matcher<View> TEXT_ENTRY = allOf(
            withClassName(endsWith("EditText")));

    public static final Matcher<View> TOOLBAR_OVERFLOW = allOf(
            withClassName(endsWith("OverflowMenuButton")),
            ViewMatchers.isDescendantOfA(TOOLBAR));

    public static final Matcher<View> ACTIONBAR_OVERFLOW = allOf(
            withClassName(endsWith("OverflowMenuButton")),
            ViewMatchers.isDescendantOfA(ACTIONBAR));

    public static final Matcher<View> DIRECTORY_LIST = allOf(
            isAssignableFrom(RecyclerView.class),
            withId(R.id.dir_list));

    public static boolean present(Matcher<View> matcher) {
        return present(onView(matcher), isDisplayed());
    }

    public static boolean present(ViewInteraction vi, Matcher<View> matcher) {
        try {
            vi.check(matches(matcher));
            vi.check(matches(isDisplayed()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
