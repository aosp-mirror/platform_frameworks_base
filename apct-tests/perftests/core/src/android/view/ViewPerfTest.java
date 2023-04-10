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
import static junit.framework.Assert.fail;

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
        // performHapticFeedback is now asynchronous, so should be very fast. This benchmark
        // is primarily a regression test for the re-introduction of blocking calls in the path.

        // Can't run back-to-back performHapticFeedback, as it will just enqueue on the oneway
        // thread and fill up that buffer. Instead, we invoke at a speed of a fairly high frame
        // rate - and this is still too fast to fully vibrate in reality, but should be able to
        // clear queues.
        int waitPerCallMillis = 5;

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

            try {
                while (state.keepRunning()) {
                    assertTrue("Call to performHapticFeedback was ignored",
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS,
                                    flags));
                    state.pauseTiming();
                    Thread.sleep(waitPerCallMillis);
                    state.resumeTiming();
                }
            } catch (InterruptedException e) {
                fail("Unexpectedly interrupted");
            }
        });
    }
}
