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

package android.widget;

import android.app.Activity;
import android.os.Looper;
import android.os.Bundle;
import android.perftests.utils.PerfStatusReporter;
import android.util.Log;
import android.view.View;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.StubActivity;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.rule.ActivityTestRule;
import android.support.test.InstrumentationRegistry;

import com.android.perftests.core.R;

import java.util.Locale;
import java.util.Collection;
import java.util.Arrays;

import org.junit.Test;
import org.junit.Rule;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@LargeTest
@RunWith(Parameterized.class)
public class TextViewAutoSizeLayoutPerfTest {
    @Parameters(name = "{0}")
    public static Collection layouts() {
        return Arrays.asList(new Object[][] {
                { "Basic TextView - no autosize", R.layout.test_basic_textview_layout},
                { "Autosize TextView 5 sizes", R.layout.test_autosize_textview_5},
                { "Autosize TextView 10 sizes", R.layout.test_autosize_textview_10},
                { "Autosize TextView 50 sizes", R.layout.test_autosize_textview_50},
                { "Autosize TextView 100 sizes", R.layout.test_autosize_textview_100},
                { "Autosize TextView 300 sizes", R.layout.test_autosize_textview_300},
                { "Autosize TextView 500 sizes", R.layout.test_autosize_textview_500},
                { "Autosize TextView 1000 sizes", R.layout.test_autosize_textview_1000},
                { "Autosize TextView 10000 sizes", R.layout.test_autosize_textview_10000},
                { "Autosize TextView 100000 sizes", R.layout.test_autosize_textview_100000}
        });
    }

    private int mLayoutId;

    public TextViewAutoSizeLayoutPerfTest(String key, int layoutId) {
        mLayoutId = layoutId;
    }

    @Rule
    public ActivityTestRule<StubActivity> mActivityRule =
            new ActivityTestRule(StubActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testConstruction() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            assertTrue("We should be running on the main thread",
                    Looper.getMainLooper().getThread() == Thread.currentThread());
            assertTrue("We should be running on the main thread",
                    Looper.myLooper() == Looper.getMainLooper());
            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            Activity activity = mActivityRule.getActivity();
            activity.setContentView(mLayoutId);

            while (state.keepRunning()) {
                TextView textView = new TextView(activity);
                // TextView#onLayout() gets called, which triggers TextView#autoSizeText()
                // which is the method we want to benchmark.
                textView.requestLayout();
            }
        });
    }
}
