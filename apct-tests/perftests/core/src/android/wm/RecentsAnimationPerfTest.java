/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.wm;

import static android.perftests.utils.ManualBenchmarkState.STATS_REPORT_COEFFICIENT_VAR;
import static android.perftests.utils.ManualBenchmarkState.STATS_REPORT_ITERATION;
import static android.perftests.utils.ManualBenchmarkState.STATS_REPORT_MEAN;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.hamcrest.core.AnyOf.anyOf;
import static org.hamcrest.core.Is.is;

import android.app.Activity;
import android.app.ActivityManager.TaskSnapshot;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.SystemClock;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.ManualBenchmarkState.ManualBenchmarkTest;
import android.perftests.utils.PerfManualStatusReporter;
import android.perftests.utils.StubActivity;
import android.util.Pair;
import android.view.IRecentsAnimationController;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.WindowManager;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@RunWith(Parameterized.class)
@LargeTest
public class RecentsAnimationPerfTest extends WindowManagerPerfTestBase {
    private static Intent sRecentsIntent;

    @Rule
    public final PerfManualStatusReporter mPerfStatusReporter = new PerfManualStatusReporter();

    @Rule
    public final ActivityTestRule<StubActivity> mActivityRule = new ActivityTestRule<>(
            StubActivity.class, false /* initialTouchMode */, false /* launchActivity */);

    private long mMeasuredTimeNs;
    private LifecycleListener mLifecycleListener;

    @Parameterized.Parameter(0)
    public int intervalBetweenOperations;

    @Parameterized.Parameters(name = "interval{0}ms")
    public static Collection<Object[]> getParameters() {
        return Arrays.asList(new Object[][] {
                { 0 },
                { 100 },
                { 300 },
        });
    }

    @BeforeClass
    public static void setUpClass() {
        // Get the permission to invoke startRecentsActivity.
        sUiAutomation.adoptShellPermissionIdentity();

        final Context context = getInstrumentation().getContext();
        final PackageManager pm = context.getPackageManager();
        final ComponentName defaultHome = pm.getHomeActivities(new ArrayList<>());

        try {
            final ComponentName recentsComponent =
                    ComponentName.unflattenFromString(context.getResources().getString(
                            com.android.internal.R.string.config_recentsComponentName));
            final int enabledState = pm.getComponentEnabledSetting(recentsComponent);
            Assume.assumeThat(enabledState, anyOf(
                    is(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
                    is(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)));

            final boolean homeIsRecents =
                    recentsComponent.getPackageName().equals(defaultHome.getPackageName());
            sRecentsIntent =
                    new Intent().setComponent(homeIsRecents ? defaultHome : recentsComponent);
        } catch (Exception e) {
            Assume.assumeNoException(e);
        }
    }

    @AfterClass
    public static void tearDownClass() {
        sUiAutomation.dropShellPermissionIdentity();
    }

    @Before
    @Override
    public void setUp() {
        super.setUp();
        final Activity testActivity = mActivityRule.launchActivity(null /* intent */);
        try {
            mActivityRule.runOnUiThread(() -> testActivity.getWindow()
                    .addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        } catch (Throwable ignored) { }
        mLifecycleListener = new LifecycleListener(testActivity);
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(mLifecycleListener);
    }

    @After
    public void tearDown() {
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(mLifecycleListener);
    }

    /** Simulate the timing of touch. */
    private void makeInterval() {
        SystemClock.sleep(intervalBetweenOperations);
    }

    /**
     * <pre>
     * Steps:
     * (1) Start recents activity (only make it visible).
     * (2) Finish animation, take turns to execute (a), (b).
     *     (a) Move recents activity to top.
     * ({@link com.android.server.wm.RecentsAnimationController#REORDER_MOVE_TO_TOP})
     *         Move test app to top by startActivityFromRecents.
     *     (b) Cancel (it is similar to swipe a little distance and give up to enter recents).
     * ({@link com.android.server.wm.RecentsAnimationController#REORDER_MOVE_TO_ORIGINAL_POSITION})
     * (3) Loop (1).
     * </pre>
     */
    @Test
    @ManualBenchmarkTest(
            warmupDurationNs = TIME_1_S_IN_NS,
            targetTestDurationNs = TIME_5_S_IN_NS,
            statsReportFlags =
                    STATS_REPORT_ITERATION | STATS_REPORT_MEAN | STATS_REPORT_COEFFICIENT_VAR)
    public void testRecentsAnimation() throws Throwable {
        final ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final IActivityTaskManager atm = ActivityTaskManager.getService();

        final ArrayList<Pair<String, Boolean>> finishCases = new ArrayList<>();
        // Real launch the recents activity.
        finishCases.add(new Pair<>("finishMoveToTop", true));
        // Return to the original top.
        finishCases.add(new Pair<>("finishCancel", false));

        // Ensure startRecentsActivity won't be called before finishing the animation.
        final Semaphore recentsSemaphore = new Semaphore(1);

        final int testActivityTaskId = mActivityRule.getActivity().getTaskId();
        final IRecentsAnimationRunner.Stub anim = new IRecentsAnimationRunner.Stub() {
            int mIteration;

            @Override
            public void onAnimationStart(IRecentsAnimationController controller,
                    RemoteAnimationTarget[] apps, RemoteAnimationTarget[] wallpapers,
                    Rect homeContentInsets, Rect minimizedHomeBounds) throws RemoteException {
                final Pair<String, Boolean> finishCase = finishCases.get(mIteration++ % 2);
                final boolean moveRecentsToTop = finishCase.second;
                makeInterval();

                long startTime = SystemClock.elapsedRealtimeNanos();
                controller.finish(moveRecentsToTop, false /* sendUserLeaveHint */);
                final long elapsedTimeNsOfFinish = SystemClock.elapsedRealtimeNanos() - startTime;
                mMeasuredTimeNs += elapsedTimeNsOfFinish;
                state.addExtraResult(finishCase.first, elapsedTimeNsOfFinish);

                if (moveRecentsToTop) {
                    mLifecycleListener.waitForIdleSync(Stage.STOPPED);

                    startTime = SystemClock.elapsedRealtimeNanos();
                    atm.startActivityFromRecents(testActivityTaskId, null /* options */);
                    final long elapsedTimeNs = SystemClock.elapsedRealtimeNanos() - startTime;
                    mMeasuredTimeNs += elapsedTimeNs;
                    state.addExtraResult("startFromRecents", elapsedTimeNs);

                    mLifecycleListener.waitForIdleSync(Stage.RESUMED);
                }

                makeInterval();
                recentsSemaphore.release();
            }

            @Override
            public void onAnimationCanceled(TaskSnapshot taskSnapshot) throws RemoteException {
                Assume.assumeNoException(
                        new AssertionError("onAnimationCanceled should not be called"));
            }
        };

        while (state.keepRunning(mMeasuredTimeNs)) {
            Assume.assumeTrue(recentsSemaphore.tryAcquire(TIME_5_S_IN_NS, TimeUnit.NANOSECONDS));

            final long startTime = SystemClock.elapsedRealtimeNanos();
            atm.startRecentsActivity(sRecentsIntent, null /* unused */, anim);
            final long elapsedTimeNsOfStart = SystemClock.elapsedRealtimeNanos() - startTime;
            mMeasuredTimeNs += elapsedTimeNsOfStart;
            state.addExtraResult("start", elapsedTimeNsOfStart);
        }

        // Ensure the last round of animation callback is done.
        recentsSemaphore.tryAcquire(TIME_5_S_IN_NS, TimeUnit.NANOSECONDS);
        recentsSemaphore.release();
    }

    private static class LifecycleListener implements ActivityLifecycleCallback {
        private final Activity mTargetActivity;
        private Stage mWaitingStage;
        private Stage mReceivedStage;

        LifecycleListener(Activity activity) {
            mTargetActivity = activity;
        }

        void waitForIdleSync(Stage state) {
            synchronized (this) {
                if (state != mReceivedStage) {
                    mWaitingStage = state;
                    try {
                        wait(TimeUnit.NANOSECONDS.toMillis(TIME_5_S_IN_NS));
                    } catch (InterruptedException impossible) { }
                }
                mWaitingStage = mReceivedStage = null;
            }
            getInstrumentation().waitForIdleSync();
        }

        @Override
        public void onActivityLifecycleChanged(Activity activity, Stage stage) {
            if (mTargetActivity != activity) {
                return;
            }

            synchronized (this) {
                mReceivedStage = stage;
                if (mWaitingStage == mReceivedStage) {
                    notifyAll();
                }
            }
        }
    }
}
