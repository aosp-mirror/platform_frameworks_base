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

import static android.support.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withResourceName;
import static org.hamcrest.CoreMatchers.allOf;

import android.view.View;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.Toolbar;

import com.android.documentsui.R;
import com.android.internal.view.menu.ActionMenuItemView;

import org.hamcrest.Matcher;

/**
 * Handy matchers useful for finding stuff in the UI. Use with Espresso testing.
 */
final class Matchers {
    static final Matcher<View> TOOLBAR = allOf(isAssignableFrom(Toolbar.class),
            withId(R.id.toolbar));
    static final Matcher<View> SEARCH_MENU = allOf(withId(R.id.menu_search), isDisplayed());
    static final Matcher<View> SEARCH_BUTTON = allOf(isAssignableFrom(ImageView.class),
            withResourceName("search_button"));
    static final Matcher<View> BREADCRUMB = allOf(isAssignableFrom(Spinner.class),
            withId(R.id.breadcrumb));
    static final Matcher<View> MENU_SEARCH = allOf(isAssignableFrom(ActionMenuItemView.class),
            withResourceName("menu_search"));
}
