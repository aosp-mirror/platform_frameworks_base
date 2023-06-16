/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.content.res;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.compat.testing.PlatformCompatChangeRule;
import android.os.Bundle;
import android.platform.test.annotations.PlatinumTest;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.util.PollingCheck;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.matcher.BoundedMatcher;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.frameworks.coretests.R;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test for verifying non-linear font scaling behavior.
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.content.res.FontScaleConverterActivityTest
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
@Presubmit
public class FontScaleConverterActivityTest {
    @Rule
    public ActivityScenarioRule<TestActivity> rule = new ActivityScenarioRule<>(TestActivity.class);
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @After
    public void teardown() {
        restoreSystemFontScaleToDefault();
    }

    @PlatinumTest(focusArea = "accessibility")
    @Test
    public void testFontsScaleNonLinearly() {
        final ActivityScenario<TestActivity> scenario = rule.getScenario();

        setSystemFontScale(2f);

        var densityRef = new AtomicReference<Float>();
        scenario.onActivity(activity -> {
            assertThat(activity.getResources().getConfiguration().fontScale)
                .isWithin(0.05f)
                .of(2f);
            densityRef.compareAndSet(null, activity.getResources().getDisplayMetrics().density);
        });
        var density = densityRef.get();
        assertThat(density).isNotNull();

        onView(withId(R.id.text8sp)).check(matches(withTextSizeInRange(
                15f * density,
                16f * density
        )));
        onView(withId(R.id.text18sp)).check(matches(withTextSizeInRange(
                20f * density,
                36f * density
        )));
        onView(withId(R.id.text35sp)).check(matches(withTextSizeInRange(
                35 * density,
                60 * density
        )));
    }

    @PlatinumTest(focusArea = "accessibility")
    @Test
    public void testOnConfigurationChanged_doesNotCrash() {
        final ActivityScenario<TestActivity> scenario = rule.getScenario();

        scenario.onActivity(activity -> {
            var config = new Configuration(activity.getResources().getConfiguration());
            config.fontScale = 1.2f;

            activity.onConfigurationChanged(config);
            activity.finish();
        });
    }

    @PlatinumTest(focusArea = "accessibility")
    @Test
    public void testUpdateConfiguration_doesNotCrash() {
        final ActivityScenario<TestActivity> scenario = rule.getScenario();

        scenario.onActivity(activity -> {
            activity.updateConfiguration();
            activity.finish();
        });
    }

    private void setSystemFontScale(float fontScale) {
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            Settings.System.putFloat(
                    InstrumentationRegistry.getInstrumentation().getContext().getContentResolver(),
                    Settings.System.FONT_SCALE,
                    fontScale
            );
        });

        PollingCheck.waitFor(/* timeout= */ 5000, () -> {
            AtomicBoolean isActivityAtCorrectScale = new AtomicBoolean(false);
            rule.getScenario().onActivity(activity ->
                    isActivityAtCorrectScale.set(
                            activity.getResources()
                                .getConfiguration()
                                .fontScale == fontScale
                    )
            );
            return isActivityAtCorrectScale.get() && InstrumentationRegistry
                    .getInstrumentation()
                    .getContext()
                    .getResources()
                    .getConfiguration()
                    .fontScale == fontScale;
        });
    }

    private static void restoreSystemFontScaleToDefault() {
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            // TODO(b/279083734): would use Settings.System.resetToDefaults() if it existed
            Settings.System.putString(
                    InstrumentationRegistry.getInstrumentation()
                            .getContext()
                            .getContentResolver(),
                    Settings.System.FONT_SCALE,
                    null,
                    /* overrideableByRestore= */ true);
        });

        PollingCheck.waitFor(
                /* timeout= */ 5000,
                () -> InstrumentationRegistry.getInstrumentation()
                                        .getContext()
                                        .getResources()
                                        .getConfiguration()
                                        .fontScale == 1
        );
    }

    private Matcher<View> withTextSizeInRange(float sizeStartPx, float sizeEndPx) {
        return new BoundedMatcher<>(TextView.class) {
            private static final float TOLERANCE = 0.05f;

            @Override
            public void describeMismatch(Object item, Description description) {
                super.describeMismatch(item, description);
                description.appendText("was ").appendValue(((TextView) item).getTextSize());
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("withTextSize between " + sizeStartPx + " and " + sizeEndPx);
            }

            @Override
            protected boolean matchesSafely(TextView textView) {
                var textSize = textView.getTextSize();
                return sizeStartPx - TOLERANCE < textSize && textSize < sizeEndPx + TOLERANCE;
            }
        };
    }

    /** Test Activity */
    public static class TestActivity extends Activity {
        final Configuration mConfig = new Configuration();

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().getDecorView().setKeepScreenOn(true);
            setShowWhenLocked(true);
            setTurnScreenOn(true);

            setContentView(R.layout.font_scale_converter_activity);
        }

        public Configuration updateConfiguration() {
            Settings.System.getConfiguration(getContentResolver(), mConfig);
            return mConfig;
        }
    }
}
