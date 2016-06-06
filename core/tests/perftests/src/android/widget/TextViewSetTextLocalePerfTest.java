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

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import android.perftest.BenchmarkState;

import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.rule.ActivityTestRule;
import android.support.test.InstrumentationRegistry;

import java.util.Locale;
import java.util.Collection;
import java.util.Arrays;

import org.junit.Test;
import org.junit.Rule;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(Parameterized.class)
public class TextViewSetTextLocalePerfTest {

    @Parameters
    public static Collection locales() {
        return Arrays.asList(new Object[][] {
            { "TextView_setTextLocale_SameLocale", "en-US", "en-US" },
            { "TextView_setTextLocale_DifferentLocale", "en-US", "ja-JP"}
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

    @Test
    public void testSetTextLocale() {
        TextView textView = new TextView(mActivityRule.getActivity());

        BenchmarkState state = new BenchmarkState();

        while (state.keepRunning()) {
            textView.setTextLocale(mFirstLocale);
            textView.setTextLocale(mSecondLocale);
        }

        Log.i("TextViewSetTextLocalePerfTest", mMetricKey + ": " + state.summaryLine());
        final Bundle status = new Bundle();
        status.putLong(mMetricKey, state.median());
        InstrumentationRegistry.getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }
}
