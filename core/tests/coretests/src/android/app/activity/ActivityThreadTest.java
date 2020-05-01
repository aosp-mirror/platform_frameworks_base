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
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertFalse;

import android.app.Activity;
import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.PictureInPictureParams;
import android.app.ResourcesManager;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ActivityRelaunchItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.ResumeActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.util.MergedConfiguration;
import android.view.Display;
import android.view.View;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.content.ReferrerIntent;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Test for verifying {@link android.app.ActivityThread} class.
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.app.activity.ActivityThreadTest
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class ActivityThreadTest {

    // The first sequence number to try with. Use a large number to avoid conflicts with the first a
    // few sequence numbers the framework used to launch the test activity.
    private static final int BASE_SEQ = 10000;

    private final ActivityTestRule<TestActivity> mActivityTestRule =
            new ActivityTestRule<>(TestActivity.class, true /* initialTouchMode */,
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

    @Test
    public void testHandleActivityConfigurationChanged() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final int numOfConfig = activity.mNumOfConfigChanges;
            applyConfigurationChange(activity, BASE_SEQ);
            assertEquals(numOfConfig + 1, activity.mNumOfConfigChanges);
        });
    }

    @Test
    public void testHandleActivity_assetsChanged() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        final IBinder[] token = new IBinder[1];
        final View[] decorView = new View[1];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ActivityThread activityThread = activity.getActivityThread();

            token[0] = activity.getActivityToken();
            decorView[0] = activity.getWindow().getDecorView();

            // Relaunches all activities
            activityThread.handleApplicationInfoChanged(activity.getApplicationInfo());
        });

        final View[] newDecorView = new View[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final ActivityThread activityThread = activity.getActivityThread();

            final Activity newActivity = activityThread.getActivity(token[0]);
            newDecorView[0] = activity.getWindow().getDecorView();
        });

        assertEquals("Window must be preserved", decorView[0], newDecorView[0]);
    }

    @Test
    public void testHandleActivityConfigurationChanged_DropStaleConfigurations() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Set the sequence number to BASE_SEQ.
            applyConfigurationChange(activity, BASE_SEQ);

            final int orientation = activity.mConfig.orientation;
            final int numOfConfig = activity.mNumOfConfigChanges;

            // Try to apply an old configuration change.
            applyConfigurationChange(activity, BASE_SEQ - 1);
            assertEquals(numOfConfig, activity.mNumOfConfigChanges);
            assertEquals(orientation, activity.mConfig.orientation);
        });
    }

    @Test
    public void testHandleActivityConfigurationChanged_ApplyNewConfigurations() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Set the sequence number to BASE_SEQ and record the final sequence number it used.
            final int seq = applyConfigurationChange(activity, BASE_SEQ);

            final int orientation = activity.mConfig.orientation;
            final int numOfConfig = activity.mNumOfConfigChanges;

            // Try to apply an new configuration change.
            applyConfigurationChange(activity, seq + 1);
            assertEquals(numOfConfig + 1, activity.mNumOfConfigChanges);
            assertNotEquals(orientation, activity.mConfig.orientation);
        });
    }

    @Test
    public void testHandleActivityConfigurationChanged_PickNewerPendingConfiguration() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Set the sequence number to BASE_SEQ and record the final sequence number it used.
            final int seq = applyConfigurationChange(activity, BASE_SEQ);

            final int orientation = activity.mConfig.orientation;
            final int numOfConfig = activity.mNumOfConfigChanges;

            final ActivityThread activityThread = activity.getActivityThread();

            final Configuration pendingConfig = new Configuration();
            pendingConfig.orientation = orientation == ORIENTATION_LANDSCAPE
                    ? ORIENTATION_PORTRAIT
                    : ORIENTATION_LANDSCAPE;
            pendingConfig.seq = seq + 2;
            activityThread.updatePendingActivityConfiguration(activity.getActivityToken(),
                    pendingConfig);

            final Configuration newConfig = new Configuration();
            newConfig.orientation = orientation;
            newConfig.seq = seq + 1;

            activityThread.handleActivityConfigurationChanged(activity.getActivityToken(),
                    newConfig, Display.INVALID_DISPLAY);
            assertEquals(numOfConfig + 1, activity.mNumOfConfigChanges);
            assertEquals(pendingConfig.orientation, activity.mConfig.orientation);
        });
    }

    @Test
    public void testHandleActivityConfigurationChanged_OnlyAppliesNewestConfiguration()
            throws Exception {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        final ActivityThread activityThread = activity.getActivityThread();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Configuration config = new Configuration();
            config.seq = BASE_SEQ;
            config.orientation = ORIENTATION_PORTRAIT;

            activityThread.handleActivityConfigurationChanged(activity.getActivityToken(),
                    config, Display.INVALID_DISPLAY);
        });

        final int numOfConfig = activity.mNumOfConfigChanges;
        final IApplicationThread appThread = activityThread.getApplicationThread();

        activity.mConfigLatch = new CountDownLatch(1);
        activity.mTestLatch = new CountDownLatch(1);

        Configuration config = new Configuration();
        config.seq = BASE_SEQ + 1;
        config.smallestScreenWidthDp = 100;
        appThread.scheduleTransaction(newActivityConfigTransaction(activity, config));

        // Wait until the main thread is performing the configuration change for the configuration
        // with sequence number BASE_SEQ + 1 before proceeding. This is to mimic the situation where
        // the activity takes very long time to process configuration changes.
        activity.mTestLatch.await();

        config = new Configuration();
        config.seq = BASE_SEQ + 2;
        config.smallestScreenWidthDp = 200;
        appThread.scheduleTransaction(newActivityConfigTransaction(activity, config));

        config = new Configuration();
        config.seq = BASE_SEQ + 3;
        config.smallestScreenWidthDp = 300;
        appThread.scheduleTransaction(newActivityConfigTransaction(activity, config));

        config = new Configuration();
        config.seq = BASE_SEQ + 4;
        config.smallestScreenWidthDp = 400;
        appThread.scheduleTransaction(newActivityConfigTransaction(activity, config));

        activity.mConfigLatch.countDown();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        activity.mConfigLatch = null;
        activity.mTestLatch = null;

        // Only two more configuration changes: one with seq BASE_SEQ + 1; another with seq
        // BASE_SEQ + 4. Configurations scheduled in between should be dropped.
        assertEquals(numOfConfig + 2, activity.mNumOfConfigChanges);
        assertEquals(400, activity.mConfig.smallestScreenWidthDp);
    }

    @Test
    public void testOrientationChanged_DoesntOverrideVirtualDisplayOrientation() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context appContext = activity.getApplication();
            Configuration originalAppConfig =
                    new Configuration(appContext.getResources().getConfiguration());
            DisplayManager dm = appContext.getSystemService(DisplayManager.class);

            int virtualDisplayWidth;
            int virtualDisplayHeight;
            if (originalAppConfig.orientation == ORIENTATION_PORTRAIT) {
                virtualDisplayWidth = 100;
                virtualDisplayHeight = 200;
            } else {
                virtualDisplayWidth = 200;
                virtualDisplayHeight = 100;
            }
            Display virtualDisplay = dm.createVirtualDisplay("virtual-display",
                    virtualDisplayWidth, virtualDisplayHeight, 200, null, 0).getDisplay();
            Context virtualDisplayContext = appContext.createDisplayContext(virtualDisplay);
            int originalVirtualDisplayOrientation = virtualDisplayContext.getResources()
                    .getConfiguration().orientation;

            Configuration newAppConfig = new Configuration(originalAppConfig);
            newAppConfig.seq++;
            newAppConfig.orientation = newAppConfig.orientation == ORIENTATION_PORTRAIT
                    ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;

            activityThread.updatePendingConfiguration(newAppConfig);
            activityThread.handleConfigurationChanged(newAppConfig);

            try {
                assertEquals("Virtual display orientation should not change when process"
                                + " configuration orientation changes.",
                        originalVirtualDisplayOrientation,
                        virtualDisplayContext.getResources().getConfiguration().orientation);
            } finally {
                // Make sure to reset the process config to prevent side effects to other
                // tests.
                Configuration activityThreadConfig = activityThread.getConfiguration();
                activityThreadConfig.seq = originalAppConfig.seq - 1;

                Configuration resourceManagerConfig = ResourcesManager.getInstance()
                        .getConfiguration();
                resourceManagerConfig.seq = originalAppConfig.seq - 1;

                activityThread.updatePendingConfiguration(originalAppConfig);
                activityThread.handleConfigurationChanged(originalAppConfig);
            }
        });
    }

    @Test
    public void testResumeAfterNewIntent() {
        final Activity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();
        final ArrayList<ReferrerIntent> rIntents = new ArrayList<>();
        rIntents.add(new ReferrerIntent(new Intent(), "android.app.activity"));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.executeTransaction(newNewIntentTransaction(activity, rIntents, false));
        });
        assertThat(activity.isResumed()).isFalse();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.executeTransaction(newNewIntentTransaction(activity, rIntents, true));
        });
        assertThat(activity.isResumed()).isTrue();
    }

    @Test
    public void testHandlePictureInPictureRequested_overriddenToEnter() {
        final Intent startIntent = new Intent();
        startIntent.putExtra(TestActivity.PIP_REQUESTED_OVERRIDE_ENTER, true);
        final TestActivity activity = mActivityTestRule.launchActivity(startIntent);
        final ActivityThread activityThread = activity.getActivityThread();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.handlePictureInPictureRequested(activity.getActivityToken());
        });

        assertTrue(activity.pipRequested());
        assertTrue(activity.enteredPip());
    }

    @Test
    public void testHandlePictureInPictureRequested_overriddenToSkip() {
        final Intent startIntent = new Intent();
        startIntent.putExtra(TestActivity.PIP_REQUESTED_OVERRIDE_SKIP, true);
        final TestActivity activity = mActivityTestRule.launchActivity(startIntent);
        final ActivityThread activityThread = activity.getActivityThread();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.handlePictureInPictureRequested(activity.getActivityToken());
        });

        assertTrue(activity.pipRequested());
        assertTrue(activity.enterPipSkipped());
    }

    @Test
    public void testHandlePictureInPictureRequested_notOverridden() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.handlePictureInPictureRequested(activity.getActivityToken());
        });

        assertTrue(activity.pipRequested());
        assertFalse(activity.enteredPip());
        assertFalse(activity.enterPipSkipped());
    }

    /**
     * Calls {@link ActivityThread#handleActivityConfigurationChanged(IBinder, Configuration, int)}
     * to try to push activity configuration to the activity for the given sequence number.
     * <p>
     * It uses orientation to push the configuration and it tries a different orientation if the
     * first attempt doesn't make through, to rule out the possibility that the previous
     * configuration already has the same orientation.
     *
     * @param activity the test target activity
     * @param seq the specified sequence number
     * @return the sequence number this method tried with the last time, so that the caller can use
     * the next sequence number for next configuration update.
     */
    private int applyConfigurationChange(TestActivity activity, int seq) {
        final ActivityThread activityThread = activity.getActivityThread();

        final int numOfConfig = activity.mNumOfConfigChanges;
        Configuration config = new Configuration();
        config.orientation = ORIENTATION_PORTRAIT;
        config.seq = seq;
        activityThread.handleActivityConfigurationChanged(activity.getActivityToken(), config,
                Display.INVALID_DISPLAY);

        if (activity.mNumOfConfigChanges > numOfConfig) {
            return config.seq;
        }

        config = new Configuration();
        config.orientation = ORIENTATION_LANDSCAPE;
        config.seq = seq + 1;
        activityThread.handleActivityConfigurationChanged(activity.getActivityToken(), config,
                Display.INVALID_DISPLAY);

        return config.seq;
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
        final StopActivityItem stopStateRequest = StopActivityItem.obtain(0 /* configChanges */);

        final ClientTransaction transaction = newTransaction(activity);
        transaction.setLifecycleStateRequest(stopStateRequest);

        return transaction;
    }

    private static ClientTransaction newActivityConfigTransaction(Activity activity,
            Configuration config) {
        final ActivityConfigurationChangeItem item = ActivityConfigurationChangeItem.obtain(config);

        final ClientTransaction transaction = newTransaction(activity);
        transaction.addCallback(item);

        return transaction;
    }

    private static ClientTransaction newNewIntentTransaction(Activity activity,
            List<ReferrerIntent> intents, boolean resume) {
        final NewIntentItem item = NewIntentItem.obtain(intents, resume);

        final ClientTransaction transaction = newTransaction(activity);
        transaction.addCallback(item);

        return transaction;
    }

    private static ClientTransaction newTransaction(Activity activity) {
        final IApplicationThread appThread = activity.getActivityThread().getApplicationThread();
        return ClientTransaction.obtain(appThread, activity.getActivityToken());
    }

    // Test activity
    public static class TestActivity extends Activity {
        static final String PIP_REQUESTED_OVERRIDE_ENTER = "pip_requested_override_enter";
        static final String PIP_REQUESTED_OVERRIDE_SKIP = "pip_requested_override_skip";

        int mNumOfConfigChanges;
        final Configuration mConfig = new Configuration();

        private boolean mPipRequested;
        private boolean mPipEntered;
        private boolean mPipEnterSkipped;

        /**
         * A latch used to notify tests that we're about to wait for configuration latch. This
         * is used to notify test code that preExecute phase for activity configuration change
         * transaction has passed.
         */
        volatile CountDownLatch mTestLatch;
        /**
         * If not {@code null} {@link #onConfigurationChanged(Configuration)} won't return until the
         * latch reaches 0.
         */
        volatile CountDownLatch mConfigLatch;

        @Override
        public void onConfigurationChanged(Configuration config) {
            super.onConfigurationChanged(config);
            mConfig.setTo(config);
            ++mNumOfConfigChanges;

            if (mConfigLatch != null) {
                if (mTestLatch != null) {
                    mTestLatch.countDown();
                }
                try {
                    mConfigLatch.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public boolean onPictureInPictureRequested() {
            mPipRequested = true;
            if (getIntent().getBooleanExtra(PIP_REQUESTED_OVERRIDE_ENTER, false)) {
                enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
                mPipEntered = true;
                return true;
            } else if (getIntent().getBooleanExtra(PIP_REQUESTED_OVERRIDE_SKIP, false)) {
                mPipEnterSkipped = true;
                return false;
            }
            return super.onPictureInPictureRequested();
        }

        boolean pipRequested() {
            return mPipRequested;
        }

        boolean enteredPip() {
            return mPipEntered;
        }

        boolean enterPipSkipped() {
            return mPipEnterSkipped;
        }
    }
}
