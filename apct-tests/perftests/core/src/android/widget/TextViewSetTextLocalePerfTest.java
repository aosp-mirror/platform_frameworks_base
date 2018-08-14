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

package android.widget;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.StubActivity;
import android.support.test.filters.LargeTest;
import android.support.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

@LargeTest
@RunWith(Parameterized.class)
public class TextViewSetTextLocalePerfTest {
    @Parameters(name = "{0}")
    public static Collection locales() {
        return Arrays.asList(new Object[][] {
            { "SameLocale", "en-US", "en-US" },
            { "DifferentLocale", "en-US", "ja-JP"}
        });
    }

    private String mMetricKey;
    private Locale mFirstLocale;
    private Locale mSecondLocale;

    public TextViewSetTextLocalePerfTest(
            String metricKey, String firstLocale, String secondLocale) {
        mMetricKey = metricKey;
        mFirstLocale = Locale.forLanguageTag(firstLocale);
        mSecondLocale = Locale.forLanguageTag(secondLocale);
    }

    @Rule
    public ActivityTestRule<StubActivity> mActivityRule = new ActivityTestRule(StubActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testSetTextLocale() {
        TextView textView = new TextView(mActivityRule.getActivity());

        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            textView.setTextLocale(mFirstLocale);
            textView.setTextLocale(mSecondLocale);
        }
    }
}
