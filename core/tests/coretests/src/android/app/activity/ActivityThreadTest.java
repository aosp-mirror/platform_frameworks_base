/*
 * Copyright 2018 The Android Open Source Project
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

package android.app.activity;

import static android.content.Intent.ACTION_EDIT;
import static android.content.Intent.ACTION_VIEW;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.servertransaction.ActivityRelaunchItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.ResumeActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.content.Intent;
import android.os.IBinder;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.MergedConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for verifying {@link android.app.ActivityThread} class.
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.app.activity.ActivityThreadTest
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class ActivityThreadTest {

    private final ActivityTestRule mActivityTestRule =
            new ActivityTestRule(TestActivity.class, true /* initialTouchMode */,
                    false /* launchActivity */);

    @Test
    public void testDoubleRelaunch() throws Exception {
        final Activity activity = mActivityTestRule.launchActivity(new Intent());
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();

        appThread.scheduleTransaction(newRelaunchResumeTransaction(activity));
        appThread.scheduleTransaction(newRelaunchResumeTransaction(activity));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testResumeAfterRelaunch() throws Exception {
        final Activity activity = mActivityTestRule.launchActivity(new Intent());
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();

        appThread.scheduleTransaction(newRelaunchResumeTransaction(activity));
        appThread.scheduleTransaction(newResumeTransaction(activity));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testSleepAndStop() throws Exception {
        final Activity activity = mActivityTestRule.launchActivity(new Intent());
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();

        appThread.scheduleSleeping(activity.getActivityToken(), true /* sleeping */);
        appThread.scheduleTransaction(newStopTransaction(activity));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    /** Verify that repeated resume requests to activity will be ignored. */
    @Test
    public void testRepeatedResume() throws Exception {
        final Activity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.executeTransaction(newResumeTransaction(activity));
            assertNull(activityThread.performResumeActivity(activity.getActivityToken(),
                    true /* finalStateRequest */, "test"));

            assertNull(activityThread.performResumeActivity(activity.getActivityToken(),
                    false /* finalStateRequest */, "test"));
        });
    }

    /** Verify that custom intent set via Activity#setIntent() is preserved on relaunch. */
    @Test
    public void testCustomIntentPreservedOnRelaunch() throws Exception {
        final Intent initIntent = new Intent();
        initIntent.setAction(ACTION_VIEW);
        final Activity activity = mActivityTestRule.launchActivity(initIntent);
        IBinder token = activity.getActivityToken();

        final ActivityThread activityThread = activity.getActivityThread();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Recreate and check that intent is still the same.
            activity.recreate();

            final Activity newActivity = activityThread.getActivity(token);
            assertTrue("Original intent must be preserved after recreate",
                    initIntent.filterEquals(newActivity.getIntent()));

            // Set custom intent, recreate and check if it is preserved.
            final Intent customIntent = new Intent();
            customIntent.setAction(ACTION_EDIT);
            newActivity.setIntent(customIntent);

            activity.recreate();

            final Activity lastActivity = activityThread.getActivity(token);
            assertTrue("Custom intent must be preserved after recreate",
                    customIntent.filterEquals(lastActivity.getIntent()));
        });
    }

    private static ClientTransaction newRelaunchResumeTransaction(Activity activity) {
        final ClientTransactionItem callbackItem = ActivityRelaunchItem.obtain(null,
                null, 0, new MergedConfiguration(), false /* preserveWindow */);
        final ResumeActivityItem resumeStateRequest =
                ResumeActivityItem.obtain(true /* isForward */);

        final ClientTransaction transaction = newTransaction(activity);
        transaction.addCallback(callbackItem);
        transaction.setLifecycleStateRequest(resumeStateRequest);

        return transaction;
    }

    private static ClientTransaction newResumeTransaction(Activity activity) {
        final ResumeActivityItem resumeStateRequest =
                ResumeActivityItem.obtain(true /* isForward */);

        final ClientTransaction transaction = newTransaction(activity);
        transaction.setLifecycleStateRequest(resumeStateRequest);

        return transaction;
    }

    private static ClientTransaction newStopTransaction(Activity activity) {
        final StopActivityItem stopStateRequest =
                StopActivityItem.obtain(false /* showWindow */, 0 /* configChanges */);

        final ClientTransaction transaction = newTransaction(activity);
        transaction.setLifecycleStateRequest(stopStateRequest);

        return transaction;
    }

    private static ClientTransaction newTransaction(Activity activity) {
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();
        return ClientTransaction.obtain(appThread, activity.getActivityToken());
    }

    // Test activity
    public static class TestActivity extends Activity {
    }
}
