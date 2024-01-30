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

package android.accessibility;

import static junit.framework.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.perftests.utils.PerfTestActivity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.benchmark.BenchmarkState;
import androidx.benchmark.junit4.BenchmarkRule;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.compatibility.common.util.TestUtils;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@LargeTest
public class AccessibilityPerfTest {

    private static final String TEXT_KEY = "Child";

    BenchmarkRule mBenchmarkRule = new BenchmarkRule();
    ActivityTestRule<PerfTestActivity> mActivityTestRule =
            new ActivityTestRule(PerfTestActivity.class);

    @Rule
    public RuleChain rules =
            RuleChain.outerRule(mBenchmarkRule).around(mActivityTestRule);

    private static Instrumentation sInstrumentation;

    private Activity mActivity;

    private ViewGroup createTestViewGroup(int children) {
        ViewGroup group = new LinearLayout(mActivity.getBaseContext());
        sInstrumentation.runOnMainSync(() -> {
            mActivity.setContentView(group);
            for (int i = 0; i < children; i++) {
                TextView text = new TextView(mActivity.getBaseContext());
                text.setText(TEXT_KEY);
                group.addView(text);
            }
        });

        return group;
    }

    @BeforeClass
    public static void setUpClass() {
        sInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @Before
    public void setUp() {
        mActivity = mActivityTestRule.getActivity();
    }

    @Test
    public void testCreateAccessibilityNodeInfo() {
        final BenchmarkState state = mBenchmarkRule.getState();
        View view = new View(mActivity.getBaseContext());

        while (state.keepRunning()) {
            view.createAccessibilityNodeInfo();
        }
    }

    @Test
    public void testCreateViewGroupAccessibilityNodeInfo() {
        final BenchmarkState state = mBenchmarkRule.getState();
        ViewGroup group = createTestViewGroup(10);

        while (state.keepRunning()) {
            group.createAccessibilityNodeInfo();
        }
    }

    @Test
    public void testCreateAccessibilityEvent() {
        final BenchmarkState state = mBenchmarkRule.getState();
        View view = new View(mActivity.getBaseContext());

        while (state.keepRunning()) {
            view.onInitializeAccessibilityEvent(
                    new AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED));
        }
    }

    @Test
    public void testPrefetching() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        createTestViewGroup(AccessibilityNodeInfo.MAX_NUMBER_OF_PREFETCHED_NODES);
        UiAutomation uiAutomation = sInstrumentation.getUiAutomation();

        while (state.keepRunning()) {
            state.pauseTiming();
            uiAutomation.clearCache();
            CountDownLatch latch = new CountDownLatch(
                    AccessibilityNodeInfo.MAX_NUMBER_OF_PREFETCHED_NODES);
            uiAutomation.getCache().registerOnNodeAddedListener(
                    (node) -> {
                        latch.countDown();
                    });
            state.resumeTiming();
            // Get the root node, and await for the latch to have seen the expected max number
            // of prefetched nodes.
            uiAutomation.getRootInActiveWindow(
                    AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID
                            | AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE);
            assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void testConnectUiAutomation() throws Exception {
        final BenchmarkState state = mBenchmarkRule.getState();
        while (state.keepRunning()) {
            UiAutomation uiAutomation = sInstrumentation.getUiAutomation();
            state.pauseTiming();
            uiAutomation.destroy();
            TestUtils.waitUntil(
                    "UiAutomation did not disconnect.", 10,
                    () -> uiAutomation.isDestroyed()
            );
            state.resumeTiming();
        }
        // We currently run into an exception
        // if a test ends with UiAutomation explicitly disconnected,
        // which seems to be the result of some commands being run by benchmarking.
        sInstrumentation.getUiAutomation();
    }
}
