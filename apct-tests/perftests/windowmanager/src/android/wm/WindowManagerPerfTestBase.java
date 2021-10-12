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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.content.Intent;
import android.perftests.utils.PerfTestActivity;
import android.perftests.utils.WindowPerfTestBase;

import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.File;
import java.util.concurrent.TimeUnit;

public class WindowManagerPerfTestBase extends WindowPerfTestBase {
    static final long TIME_5_S_IN_NS = 5 * NANOS_PER_S;

    /**
     * The out directory matching the directory-keys of collector in AndroidTest.xml. The directory
     * is in /data because while enabling method profiling of system server, it cannot write the
     * trace to external storage.
     */
    static final File BASE_OUT_PATH = new File("/data/local/tmp/WmPerfTests");

    static void startProfiling(String outFileName) {
        startProfiling(BASE_OUT_PATH, outFileName);
    }

    /**
     * Provides an activity that is able to wait for a stable lifecycle stage.
     */
    static class PerfTestActivityRule extends PerfTestActivityRuleBase {
        private final LifecycleListener mLifecycleListener = new LifecycleListener();

        PerfTestActivityRule() {
        }

        PerfTestActivityRule(boolean launchActivity) {
            super(launchActivity);
        }

        @Override
        public Statement apply(Statement base, Description description) {
            final Statement wrappedStatement = new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    ActivityLifecycleMonitorRegistry.getInstance()
                            .addLifecycleCallback(mLifecycleListener);
                    base.evaluate();
                    ActivityLifecycleMonitorRegistry.getInstance()
                            .removeLifecycleCallback(mLifecycleListener);
                }
            };
            return super.apply(wrappedStatement, description);
        }

        @Override
        public PerfTestActivity launchActivity(Intent intent) {
            final PerfTestActivity activity = super.launchActivity(intent);
            mLifecycleListener.setTargetActivity(activity);
            return activity;
        }

        void waitForIdleSync(Stage state) {
            mLifecycleListener.waitForIdleSync(state);
        }
    }

    static class LifecycleListener implements ActivityLifecycleCallback {
        private Activity mTargetActivity;
        private Stage mWaitingStage;
        private Stage mReceivedStage;

        void setTargetActivity(Activity activity) {
            mTargetActivity = activity;
            mReceivedStage = mWaitingStage = null;
        }

        void waitForIdleSync(Stage stage) {
            synchronized (this) {
                if (stage != mReceivedStage) {
                    mWaitingStage = stage;
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
