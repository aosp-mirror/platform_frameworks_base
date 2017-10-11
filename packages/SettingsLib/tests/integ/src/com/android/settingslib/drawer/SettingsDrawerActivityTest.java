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

package com.android.settingslib.drawer;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.assertion.ViewAssertions.doesNotExist;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withContentDescription;

import android.app.Instrumentation;
import android.content.Intent;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SettingsDrawerActivityTest {

    @Rule
    public ActivityTestRule<TestActivity> mActivityRule =
            new ActivityTestRule<>(TestActivity.class, true, true);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void startActivityWithNoExtra_showNoNavUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                TestActivity.class));

        onView(withContentDescription(com.android.internal.R.string.action_bar_up_description))
                .check(doesNotExist());
    }

    @Test
    public void startActivityWithExtraToHideMenu_showNavUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), TestActivity.class)
                .putExtra(TestActivity.EXTRA_SHOW_MENU, false);
        instrumentation.startActivitySync(intent);

        onView(withContentDescription(com.android.internal.R.string.action_bar_up_description))
                .check(doesNotExist());
    }

    @Test
    public void startActivityWithExtraToShowMenu_showNavUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), TestActivity.class)
                .putExtra(TestActivity.EXTRA_SHOW_MENU, true);
        instrumentation.startActivitySync(intent);

        onView(withContentDescription(com.android.internal.R.string.action_bar_up_description))
                .check(matches(isDisplayed()));
    }

    /**
     * Test Activity in this test.
     *
     * Use this activity because SettingsDrawerActivity hasn't been registered in its
     * AndroidManifest.xml
     */
    public static class TestActivity extends SettingsDrawerActivity {}
}
