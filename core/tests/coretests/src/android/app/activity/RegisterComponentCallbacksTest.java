/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.activity;

import static android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
import static android.content.Context.OVERRIDABLE_COMPONENT_CALLBACKS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;

import android.app.Activity;
import android.app.WindowConfiguration;
import android.app.activity.ActivityThreadTest.TestActivity;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentCallbacks;
import android.content.TestComponentCallbacks2;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/**
 * Test for verifying {@link Activity#registerComponentCallbacks(ComponentCallbacks)} behavior.
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.app.activity.RegisterComponentCallbacksTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class RegisterComponentCallbacksTest {
    @Rule
    public ActivityScenarioRule rule = new ActivityScenarioRule<>(TestActivity.class);
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    public void testRegisterComponentCallbacks() {
        final ActivityScenario scenario = rule.getScenario();
        final TestComponentCallbacks2 callbacks = new TestComponentCallbacks2();
        final Configuration config = new Configuration();
        config.fontScale = 1.2f;
        config.windowConfiguration.setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_FREEFORM);
        config.windowConfiguration.setBounds(new Rect(0, 0, 100, 100));
        final int trimMemoryLevel = TRIM_MEMORY_RUNNING_LOW;

        scenario.onActivity(activity -> {
            // It should be no-op to unregister a ComponentCallbacks without registration.
            activity.unregisterComponentCallbacks(callbacks);

            activity.registerComponentCallbacks(callbacks);
            // Verify #onConfigurationChanged
            activity.onConfigurationChanged(config);
            assertThat(callbacks.mConfiguration).isEqualTo(config);
            // Verify #onTrimMemory
            activity.onTrimMemory(trimMemoryLevel);
            assertThat(callbacks.mLevel).isEqualTo(trimMemoryLevel);
            // verify #onLowMemory
            activity.onLowMemory();
            assertThat(callbacks.mLowMemoryCalled).isTrue();

            activity.unregisterComponentCallbacks(callbacks);
        });
    }

    @DisableCompatChanges(OVERRIDABLE_COMPONENT_CALLBACKS)
    @Test
    public void testRegisterComponentCallbacksBeforeT() {
        final ActivityScenario scenario = rule.getScenario();
        final TestComponentCallbacks2 callbacks = new TestComponentCallbacks2();
        final Configuration config = new Configuration();
        config.fontScale = 1.2f;
        config.windowConfiguration.setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_FREEFORM);
        config.windowConfiguration.setBounds(new Rect(0, 0, 100, 100));
        final int trimMemoryLevel = TRIM_MEMORY_RUNNING_LOW;

        scenario.onActivity(activity -> {
            // It should be no-op to unregister a ComponentCallbacks without registration.
            activity.unregisterComponentCallbacks(callbacks);

            activity.registerComponentCallbacks(callbacks);
            // Verify #onConfigurationChanged
            activity.onConfigurationChanged(config);
            assertWithMessage("The ComponentCallbacks must be added to #getApplicationContext "
                    + "before T.").that(callbacks.mConfiguration).isNull();
            // Verify #onTrimMemory
            activity.onTrimMemory(trimMemoryLevel);
            assertWithMessage("The ComponentCallbacks must be added to #getApplicationContext "
                    + "before T.").that(callbacks.mLevel).isEqualTo(0);
            // verify #onLowMemory
            activity.onLowMemory();
            assertWithMessage("The ComponentCallbacks must be added to #getApplicationContext "
                    + "before T.").that(callbacks.mLowMemoryCalled).isFalse();

            activity.unregisterComponentCallbacks(callbacks);
        });
    }
}
