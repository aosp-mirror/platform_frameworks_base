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
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.RootMatchers.withDecorView;
import static android.support.test.espresso.matcher.ViewMatchers.hasDescendant;
import static android.support.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static org.hamcrest.Matchers.allOf;

import android.support.test.espresso.NoMatchingRootException;
import android.support.test.espresso.NoMatchingViewException;
import android.support.test.espresso.ViewInteraction;
import android.widget.Editor;

public class DragHandleUtils {
    private DragHandleUtils() {

    }

    public static void assertNoSelectionHandles() {
        try {
            onHandleView(com.android.internal.R.id.selection_start_handle)
                    .check(matches(isDisplayed()));
        } catch (NoMatchingRootException | NoMatchingViewException | AssertionError e) {
            try {
                onHandleView(com.android.internal.R.id.selection_end_handle)
                        .check(matches(isDisplayed()));
            } catch (NoMatchingRootException | NoMatchingViewException | AssertionError e1) {
                return;
            }
        }
        throw new AssertionError("Selection handle found");
    }

    public static ViewInteraction onHandleView(int id)
            throws NoMatchingRootException, NoMatchingViewException, AssertionError {
        return onView(allOf(withId(id), isAssignableFrom(Editor.HandleView.class)))
                .inRoot(withDecorView(hasDescendant(withId(id))));
    }
}
