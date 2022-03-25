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

import static android.perftests.utils.ManualBenchmarkState.StatsReport;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.hamcrest.core.AnyOf.anyOf;
import static org.hamcrest.core.Is.is;

import android.app.ActivityManager;
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
import android.util.Pair;
import android.view.IRecentsAnimationController;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.window.TaskSnapshot;

import androidx.test.filters.LargeTest;
import androidx.test.runner.lifecycle.Stage;

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
public class RecentsAnimationPerfTest extends WindowManagerPerfTestBase
        implements ManualBenchmarkState.CustomizedIterationListener {
    private static Intent sRecentsIntent;

    @Rule
    public final PerfManualStatusReporter mPerfStatusReporter = new PerfManualStatusReporter();

    @Rule
    public final PerfTestActivityRule mActivityRule =
            new PerfTestActivityRule(true /* launchActivity */);

    private long mMeasuredTimeNs;

    /**
     * Used to skip each test method if there is error. It cannot be raised in static setup because
     * that will break the amount of target method count.
     */
    private static Exception sSetUpClassException;

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
        getUiAutomation().adoptShellPermissionIdentity();

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
            sSetUpClassException = e;
        }
    }

    @AfterClass
    public static void tearDownClass() {
        sSetUpClassException = null;
        try {
            // Recents activity may stop app switches. Restore the state to avoid affecting
            // the next test.
            ActivityManager.resumeAppSwitches();
        } catch (RemoteException ignored) {
        }
        getUiAutomation().dropShellPermissionIdentity();
    }

    @Before
    public void setUp() {
        Assume.assumeNoException(sSetUpClassException);
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
            statsReport = @StatsReport(flags = StatsReport.FLAG_ITERATION | StatsReport.FLAG_MEAN
                    | StatsReport.FLAG_COEFFICIENT_VAR))
    public void testRecentsAnimation() throws Throwable {
        final ManualBenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        state.setCustomizedIterations(getProfilingIterations(), this);
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
                    mActivityRule.waitForIdleSync(Stage.STOPPED);

                    startTime = SystemClock.elapsedRealtimeNanos();
                    atm.startActivityFromRecents(testActivityTaskId, null /* options */);
                    final long elapsedTimeNs = SystemClock.elapsedRealtimeNanos() - startTime;
                    mMeasuredTimeNs += elapsedTimeNs;
                    state.addExtraResult("startFromRecents", elapsedTimeNs);

                    mActivityRule.waitForIdleSync(Stage.RESUMED);
                }

                makeInterval();
                recentsSemaphore.release();
            }

            @Override
            public void onAnimationCanceled(int[] taskIds, TaskSnapshot[] taskSnapshots)
                    throws RemoteException {
                Assume.assumeNoException(
                        new AssertionError("onAnimationCanceled should not be called"));
            }

            @Override
            public void onTasksAppeared(RemoteAnimationTarget[] app) throws RemoteException {
                /* no-op */
            }
        };

        recentsSemaphore.tryAcquire();
        while (state.keepRunning(mMeasuredTimeNs)) {
            mMeasuredTimeNs = 0;

            final long startTime = SystemClock.elapsedRealtimeNanos();
            atm.startRecentsActivity(sRecentsIntent, 0 /* eventTime */, anim);
            final long elapsedTimeNsOfStart = SystemClock.elapsedRealtimeNanos() - startTime;
            mMeasuredTimeNs += elapsedTimeNsOfStart;
            state.addExtraResult("start", elapsedTimeNsOfStart);

            // Ensure the animation callback is done.
            Assume.assumeTrue(recentsSemaphore.tryAcquire(
                    sIsProfilingMethod() ? 10 * TIME_5_S_IN_NS : TIME_5_S_IN_NS,
                    TimeUnit.NANOSECONDS));
        }
    }

    @Override
    public void onStart(int iteration) {
        startProfiling(RecentsAnimationPerfTest.class.getSimpleName()
                + "_interval_" + intervalBetweenOperations
                + "_MethodTracing_" + iteration + ".trace");
    }

    @Override
    public void onFinished(int iteration) {
        stopProfiling();
    }
}
