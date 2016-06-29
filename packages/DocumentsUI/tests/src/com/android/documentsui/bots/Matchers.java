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
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;

import android.support.test.espresso.ViewInteraction;
import android.view.View;

import org.hamcrest.Matcher;

/**
 * Support methods for working with Espresso related matchers 'n stuff.
 */
public final class Matchers {

    private Matchers() {}

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
