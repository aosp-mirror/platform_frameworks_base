/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.view.contentcapture;

import static com.android.compatibility.common.util.ActivitiesWatcher.ActivityLifecycle.DESTROYED;

import android.content.Intent;
import android.os.RemoteCallback;
import android.perftests.utils.BenchmarkState;
import android.view.View;

import androidx.test.filters.LargeTest;

import com.android.compatibility.common.util.ActivitiesWatcher.ActivityWatcher;
import com.android.perftests.contentcapture.R;

import org.junit.Test;

@LargeTest
public class LoginTest extends AbstractContentCapturePerfTestCase {

    @Test
    public void testLaunchActivity() throws Throwable {
        enableService();

        testActivityLaunchTime(R.layout.test_login_activity, 0);
    }

    @Test
    public void testLaunchActivity_contain100Views() throws Throwable {
        enableService();

        testActivityLaunchTime(R.layout.test_container_activity, 100);
    }

    @Test
    public void testLaunchActivity_contain300Views() throws Throwable {
        enableService();

        testActivityLaunchTime(R.layout.test_container_activity, 300);
    }

    @Test
    public void testLaunchActivity_contain500Views() throws Throwable {
        enableService();

        testActivityLaunchTime(R.layout.test_container_activity, 500);
    }

    @Test
    public void testLaunchActivity_noService() throws Throwable {
        testActivityLaunchTime(R.layout.test_login_activity, 0);
    }

    @Test
    public void testLaunchActivity_noService_contain100Views() throws Throwable {
        testActivityLaunchTime(R.layout.test_container_activity, 100);
    }

    @Test
    public void testLaunchActivity_noService_contain300Views() throws Throwable {
        testActivityLaunchTime(R.layout.test_container_activity, 300);
    }

    @Test
    public void testLaunchActivity_noService_contain500Views() throws Throwable {
        testActivityLaunchTime(R.layout.test_container_activity, 500);
    }

    @Test
    public void testSendEventsLatency() throws Throwable {
        enableService();

        testSendEventLatency(R.layout.test_container_activity, 0);
    }

    @Test
    public void testSendEventsLatency_contains100Views() throws Throwable {
        enableService();

        testSendEventLatency(R.layout.test_container_activity, 100);
    }

    @Test
    public void testSendEventsLatency_contains300Views() throws Throwable {
        enableService();

        testSendEventLatency(R.layout.test_container_activity, 300);
    }

    @Test
    public void testSendEventsLatency_contains500Views() throws Throwable {
        enableService();

        testSendEventLatency(R.layout.test_container_activity, 500);
    }

    private void testActivityLaunchTime(int layoutId, int numViews) throws Throwable {
        final Object drawNotifier = new Object();
        final Intent intent = getLaunchIntent(layoutId, numViews);
        intent.putExtra(CustomTestActivity.INTENT_EXTRA_FINISH_ON_IDLE, true);
        intent.putExtra(CustomTestActivity.INTENT_EXTRA_DRAW_CALLBACK,
                new RemoteCallback(result -> {
                    synchronized (drawNotifier) {
                        drawNotifier.notifyAll();
                    }
                }));
        final ActivityWatcher watcher = startWatcher();

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mEntryActivity.startActivity(intent);
            synchronized (drawNotifier) {
                try {
                    drawNotifier.wait(GENERIC_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            // Ignore the time to finish the activity
            state.pauseTiming();
            watcher.waitFor(DESTROYED);
            sInstrumentation.waitForIdleSync();
            state.resumeTiming();
        }
    }

    private void testSendEventLatency(int layoutId, int numViews) throws Throwable {
        final Object drawNotifier = new Object();
        final Intent intent = getLaunchIntent(layoutId, numViews);
        intent.putExtra(CustomTestActivity.INTENT_EXTRA_FINISH_ON_IDLE, true);
        intent.putExtra(CustomTestActivity.INTENT_EXTRA_DRAW_CALLBACK,
                new RemoteCallback(result -> {
                    synchronized (drawNotifier) {
                        drawNotifier.notifyAll();
                    }
                }));
        final ActivityWatcher watcher = startWatcher();

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mEntryActivity.startActivity(intent);
            synchronized (drawNotifier) {
                try {
                    drawNotifier.wait(GENERIC_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            waitForSessionPaused();

            // Ignore the time to finish the activity
            state.pauseTiming();
            watcher.waitFor(DESTROYED);
            sInstrumentation.waitForIdleSync();
            state.resumeTiming();
        }
    }

    @Test
    public void testOnVisibilityAggregated_visibleChanged() throws Throwable {
        enableService();
        final CustomTestActivity activity = launchActivity();
        final View root = activity.getWindow().getDecorView();
        final View username = root.findViewById(R.id.username);

        testOnVisibilityAggregated(username);
    }

    @Test
    public void testOnVisibilityAggregated_visibleChanged_noService() throws Throwable {
        final CustomTestActivity activity = launchActivity();
        final View root = activity.getWindow().getDecorView();
        final View username = root.findViewById(R.id.username);

        testOnVisibilityAggregated(username);
    }

    @Test
    public void testOnVisibilityAggregated_visibleChanged_noOptions() throws Throwable {
        enableService();
        clearOptions();
        final CustomTestActivity activity = launchActivity();
        final View root = activity.getWindow().getDecorView();
        final View username = root.findViewById(R.id.username);

        testOnVisibilityAggregated(username);
    }

    @Test
    public void testOnVisibilityAggregated_visibleChanged_notImportant() throws Throwable {
        enableService();
        final CustomTestActivity activity = launchActivity();
        final View root = activity.getWindow().getDecorView();
        final View username = root.findViewById(R.id.username);
        username.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);

        testOnVisibilityAggregated(username);
    }

    private void testOnVisibilityAggregated(View view) throws Throwable {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            // Only count the time of onVisibilityAggregated()
            state.pauseTiming();
            sInstrumentation.runOnMainSync(() -> {
                state.resumeTiming();
                view.onVisibilityAggregated(false);
                state.pauseTiming();
            });
            sInstrumentation.runOnMainSync(() -> {
                state.resumeTiming();
                view.onVisibilityAggregated(true);
                state.pauseTiming();
            });
            state.resumeTiming();
        }
    }
}
