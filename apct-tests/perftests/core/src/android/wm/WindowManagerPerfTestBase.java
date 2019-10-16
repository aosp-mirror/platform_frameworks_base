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
import android.app.UiAutomation;
import android.content.Intent;
import android.perftests.utils.PerfTestActivity;

import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import org.junit.BeforeClass;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.concurrent.TimeUnit;

public class WindowManagerPerfTestBase {
    static final UiAutomation sUiAutomation = getInstrumentation().getUiAutomation();
    static final long NANOS_PER_S = 1000L * 1000 * 1000;
    static final long TIME_1_S_IN_NS = 1 * NANOS_PER_S;
    static final long TIME_5_S_IN_NS = 5 * NANOS_PER_S;

    @BeforeClass
    public static void setUpOnce() {
        // In order to be closer to the real use case.
        sUiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        sUiAutomation.executeShellCommand("wm dismiss-keyguard");
        getInstrumentation().getContext().startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /**
     * Provides an activity that keeps screen on and is able to wait for a stable lifecycle stage.
     */
    static class PerfTestActivityRule extends ActivityTestRule<PerfTestActivity> {
        private final Intent mStartIntent =
                new Intent().putExtra(PerfTestActivity.INTENT_EXTRA_KEEP_SCREEN_ON, true);
        private final LifecycleListener mLifecycleListener = new LifecycleListener();

        PerfTestActivityRule() {
            this(false /* launchActivity */);
        }

        PerfTestActivityRule(boolean launchActivity) {
            super(PerfTestActivity.class, false /* initialTouchMode */, launchActivity);
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
        protected Intent getActivityIntent() {
            return mStartIntent;
        }

        @Override
        public PerfTestActivity launchActivity(Intent intent) {
            final PerfTestActivity activity = super.launchActivity(intent);
            mLifecycleListener.setTargetActivity(activity);
            return activity;
        }

        PerfTestActivity launchActivity() {
            return launchActivity(mStartIntent);
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
