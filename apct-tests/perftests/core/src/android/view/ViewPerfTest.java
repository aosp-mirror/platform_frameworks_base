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

package android.view;

import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.perftests.utils.PerfTestActivity;
import android.widget.FrameLayout;

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import com.android.perftests.core.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@LargeTest
public class ViewPerfTest {
    @Rule
    public final BenchmarkRule mBenchmarkRule = new BenchmarkRule();

    @Rule
    public final ActivityTestRule<PerfTestActivity> mActivityRule =
            new ActivityTestRule<>(PerfTestActivity.class);

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testSimpleViewInflate() {
        final BenchmarkState state = mBenchmarkRule.getState();
        LayoutInflater inflater = LayoutInflater.from(mContext);
        FrameLayout root = new FrameLayout(mContext);
        while (state.keepRunning()) {
            inflater.inflate(R.layout.test_simple_view, root, false);
        }
    }

    @Test
    public void testTwelveKeyInflate() {
        final BenchmarkState state = mBenchmarkRule.getState();
        LayoutInflater inflater = LayoutInflater.from(mContext);
        FrameLayout root = new FrameLayout(mContext);
        while (state.keepRunning()) {
            inflater.inflate(R.layout.twelve_key_entry, root, false);
        }
    }

    @Test
    public void testPerformHapticFeedback() throws Throwable {
        final BenchmarkState state = mBenchmarkRule.getState();
        mActivityRule.runOnUiThread(() -> {
            state.pauseTiming();
            View view = new View(mContext);
            mActivityRule.getActivity().setContentView(view);
            assertTrue("View needs to be attached to Window to perform haptic feedback",
                    view.isAttachedToWindow());
            state.resumeTiming();

            // Disable settings so perform will never be ignored.
            int flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING;

            while (state.keepRunning()) {
                assertTrue("Call to performHapticFeedback was ignored",
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS, flags));
            }
        });
    }
}
