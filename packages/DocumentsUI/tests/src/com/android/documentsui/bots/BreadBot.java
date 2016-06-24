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
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;

import android.content.Context;
import android.support.test.espresso.ViewInteraction;
import android.support.test.espresso.matcher.BoundedMatcher;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObjectNotFoundException;

import com.android.documentsui.DragOverTextView;
import com.android.documentsui.DropdownBreadcrumb;
import com.android.documentsui.model.DocumentInfo;

import org.hamcrest.Description;
import org.hamcrest.Matcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import junit.framework.Assert;

/**
 * A test helper class that provides support for controlling the UI Breadcrumb
 * programmatically, and making assertions against the state of the UI.
 * <p>
 * Support for working directly with Roots and Directory view can be found in the respective bots.
 */
public class BreadBot extends Bots.BaseBot {

    public static final String TARGET_PKG = "com.android.documentsui";
    private UiBot mMain;

    public BreadBot(UiDevice device, Context context, int timeout, UiBot main) {
        super(device, context, timeout);
        mMain = main;
    }

    public void assertTitle(String expected) {
        // There is no discrete title part on the horizontal breadcrumb...
        // so we only test on dropdown.
        if (mMain.inDrawerLayout()) {
            Matcher<Object> titleMatcher = dropdownTitleMatcher(expected);
            onView(Matchers.BREADCRUMB)
                    .check(matches(titleMatcher));
        }
    }

    /**
     * Reveals the bread crumb if it was hidden. This will likely be the case
     * when the app is in drawer mode.
     */
    public void revealAsNeeded() throws Exception {
        if (mMain.inDrawerLayout()) {
            onView(Matchers.DROPDOWN_BREADCRUMB).perform(click());
        }
    }

    public void clickItem(String label) throws UiObjectNotFoundException {
        if (mMain.inFixedLayout()) {
            findHorizontalEntry(label).perform(click());
        } else {
            mMain.findMenuWithName(label).click();
        }
    }

    public void assertItemsPresent(String... items) {
        Predicate<String> checker = mMain.inFixedLayout()
                    ? this::hasHorizontalEntry
                    : mMain::hasMenuWithName;

        assertItemsPresent(items, checker);
    }

    public void assertItemsPresent(String[] items, Predicate<String> predicate) {
        List<String> absent = new ArrayList<>();
        for (String item : items) {
            if (!predicate.test(item)) {
                absent.add(item);
            }
        }
        if (!absent.isEmpty()) {
            Assert.fail("Expected iteams " + Arrays.asList(items)
                    + ", but missing " + absent);
        }
    }

    public boolean hasHorizontalEntry(String label) {
        return Matchers.present(findHorizontalEntry(label), withText(label));
    }

    @SuppressWarnings("unchecked")
    public ViewInteraction findHorizontalEntry(String label) {
        // Matchers.HORIZONTAL_BREADCRUMB
        return onView(allOf(isAssignableFrom(DragOverTextView.class), withText(label)));
    }

    private static Matcher<Object> dropdownTitleMatcher(String expected) {
        final Matcher<String> textMatcher = is(expected);
        return new BoundedMatcher<Object, DropdownBreadcrumb>(DropdownBreadcrumb.class) {
            @Override
            public boolean matchesSafely(DropdownBreadcrumb breadcrumb) {
                DocumentInfo selectedDoc = (DocumentInfo) breadcrumb.getSelectedItem();
                return textMatcher.matches(selectedDoc.displayName);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("with breadcrumb title: ");
                textMatcher.describeTo(description);
            }
        };
    }
}
