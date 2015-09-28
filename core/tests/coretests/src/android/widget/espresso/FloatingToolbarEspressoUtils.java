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
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withTagValue;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import android.app.Activity;
import com.android.internal.widget.FloatingToolbar;

/**
 * Espresso utility methods for the floating toolbar.
 */
public class FloatingToolbarEspressoUtils {


    private FloatingToolbarEspressoUtils() {}

    /**
     * Asserts that the floating toolbar is displayed on screen.
     *
     * @throws AssertionError if the assertion fails
     */
    public static void assertFloatingToolbarIsDisplayed(Activity activity) {
        onView(withTagValue(is((Object) FloatingToolbar.FLOATING_TOOLBAR_TAG)))
                .inRoot(withDecorView(not(is(activity.getWindow().getDecorView()))))
                .check(matches(isDisplayed()));
    }

}
