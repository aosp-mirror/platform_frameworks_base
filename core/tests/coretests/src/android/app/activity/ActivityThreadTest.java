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

import static android.content.Context.DEVICE_ID_INVALID;
import static android.content.Intent.ACTION_EDIT;
import static android.content.Intent.ACTION_VIEW;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.INVALID_DISPLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.Application;
import android.app.IApplicationThread;
import android.app.PictureInPictureParams;
import android.app.ResourcesManager;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ActivityRelaunchItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.ConfigurationChangeItem;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.ResumeActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.content.Context;
import android.content.Intent;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;
import android.util.MergedConfiguration;
import android.view.Display;
import android.view.View;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.content.ReferrerIntent;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Test for verifying {@link android.app.ActivityThread} class.
 * Build/Install/Run:
 *  atest FrameworksCoreTests:android.app.activity.ActivityThreadTest
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
@Presubmit
public class ActivityThreadTest {
    private static final int TIMEOUT_SEC = 10;

    // The first sequence number to try with. Use a large number to avoid conflicts with the first a
    // few sequence numbers the framework used to launch the test activity.
    private static final int BASE_SEQ = 10000;

    @Rule
    public final ActivityTestRule<TestActivity> mActivityTestRule =
            new ActivityTestRule<>(TestActivity.class, true /* initialTouchMode */,
                    false /* launchActivity */);

    private ArrayList<VirtualDisplay> mCreatedVirtualDisplays;

    @After
    public void tearDown() {
        if (mCreatedVirtualDisplays != null) {
            mCreatedVirtualDisplays.forEach(VirtualDisplay::release);
            mCreatedVirtualDisplays = null;
        }
    }

    @Test
    public void testTemporaryDirectory() throws Exception {
        assertEquals(System.getProperty("java.io.tmpdir"), System.getenv("TMPDIR"));
    }

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
            final ActivityClientRecord r = getActivityClientRecord(activity);
            assertFalse(activityThread.performResumeActivity(r, true /* finalStateRequest */,
                    "test"));

            assertFalse(activityThread.performResumeActivity(r, false /* finalStateRequest */,
                    "test"));
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
    public void testOverrideScale() throws Exception {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());
        final Application app = activity.getApplication();
        final ActivityThread activityThread = activity.getActivityThread();
        final IApplicationThread appThread = activityThread.getApplicationThread();
        final DisplayMetrics originalAppMetrics = new DisplayMetrics();
        originalAppMetrics.setTo(app.getResources().getDisplayMetrics());
        final Configuration originalAppConfig =
                new Configuration(app.getResources().getConfiguration());
        final DisplayMetrics originalActivityMetrics = new DisplayMetrics();
        originalActivityMetrics.setTo(activity.getResources().getDisplayMetrics());
        final Configuration originalActivityConfig =
                new Configuration(activity.getResources().getConfiguration());

        final Configuration newConfig = new Configuration(originalAppConfig);
        newConfig.seq = BASE_SEQ + 1;
        newConfig.smallestScreenWidthDp++;

        final float originalScale = CompatibilityInfo.getOverrideInvertedScale();
        float scale = 0.5f;
        CompatibilityInfo.setOverrideInvertedScale(scale);
        try {
            // Send process level config change.
            ClientTransaction transaction = newTransaction(activityThread, null);
            transaction.addCallback(ConfigurationChangeItem.obtain(
                    new Configuration(newConfig), DEVICE_ID_INVALID));
            appThread.scheduleTransaction(transaction);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            assertScreenScale(scale, app, originalAppConfig, originalAppMetrics);
            // The activity's config doesn't change because ConfigurationChangeItem is process level
            // that won't affect activity's override config.
            assertEquals(originalActivityConfig.densityDpi,
                    activity.getResources().getConfiguration().densityDpi);

            scale = 0.8f;
            CompatibilityInfo.setOverrideInvertedScale(scale);
            // Send activity level config change.
            newConfig.seq++;
            newConfig.smallestScreenWidthDp++;
            transaction = newTransaction(activityThread, activity.getActivityToken());
            transaction.addCallback(ActivityConfigurationChangeItem.obtain(
                    new Configuration(newConfig)));
            appThread.scheduleTransaction(transaction);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            assertScreenScale(scale, activity, originalActivityConfig, originalActivityMetrics);

            // Execute a local relaunch item with current scaled config (e.g. simulate recreate),
            // the config should not be scaled again.
            final Configuration currentConfig = activity.getResources().getConfiguration();
            final ClientTransaction localTransaction =
                    newTransaction(activityThread, activity.getActivityToken());
            localTransaction.addCallback(ActivityRelaunchItem.obtain(
                    null /* pendingResults */, null /* pendingIntents */, 0 /* configChanges */,
                    new MergedConfiguration(currentConfig, currentConfig),
                    true /* preserveWindow */));
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> activityThread.executeTransaction(localTransaction));

            assertScreenScale(scale, activity, originalActivityConfig, originalActivityMetrics);
        } finally {
            CompatibilityInfo.setOverrideInvertedScale(originalScale);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> restoreConfig(activityThread, originalAppConfig));
        }
        assertScreenScale(originalScale, app, originalAppConfig, originalAppMetrics);
    }

    private static void assertScreenScale(float scale, Context context,
            Configuration origConfig, DisplayMetrics origMetrics) {
        final int expectedDpi = (int) (origConfig.densityDpi * scale + .5f);
        final float expectedDensity = origMetrics.density * scale;
        final int expectedWidthPixels = (int) (origMetrics.widthPixels * scale + .5f);
        final int expectedHeightPixels = (int) (origMetrics.heightPixels * scale + .5f);
        final Configuration expectedConfig = new Configuration(origConfig);
        CompatibilityInfo.scaleConfiguration(scale, expectedConfig);
        final Rect expectedBounds = expectedConfig.windowConfiguration.getBounds();
        final Rect expectedAppBounds = expectedConfig.windowConfiguration.getAppBounds();
        final Rect expectedMaxBounds = expectedConfig.windowConfiguration.getMaxBounds();

        final Configuration currentConfig = context.getResources().getConfiguration();
        final DisplayMetrics currentMetrics = context.getResources().getDisplayMetrics();
        assertEquals(expectedDpi, currentConfig.densityDpi);
        assertEquals(expectedDpi, currentMetrics.densityDpi);
        assertEquals(expectedDensity, currentMetrics.density, 0.001f);
        assertEquals(expectedWidthPixels, currentMetrics.widthPixels);
        assertEquals(expectedHeightPixels, currentMetrics.heightPixels);
        assertEquals(expectedBounds, currentConfig.windowConfiguration.getBounds());
        assertEquals(expectedAppBounds, currentConfig.windowConfiguration.getAppBounds());
        assertEquals(expectedMaxBounds, currentConfig.windowConfiguration.getMaxBounds());
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
    public void testRecreateActivity() {
        relaunchActivityAndAssertPreserveWindow(Activity::recreate);
    }

    private void relaunchActivityAndAssertPreserveWindow(Consumer<Activity> relaunch) {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();

        final IBinder[] token = new IBinder[1];
        final View[] decorView = new View[1];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            token[0] = activity.getActivityToken();
            decorView[0] = activity.getWindow().getDecorView();

            relaunch.accept(activity);
        });

        final View[] newDecorView = new View[1];
        final Activity[] newActivity = new Activity[1];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            newActivity[0] = activityThread.getActivity(token[0]);
            newDecorView[0] = newActivity[0].getWindow().getDecorView();
        });

        assertNotEquals("Activity must be relaunched", activity, newActivity[0]);
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
    public void testHandleActivityConfigurationChanged_SkipWhenNewerConfigurationPending() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Set the sequence number to BASE_SEQ and record the final sequence number it used.
            final int seq = applyConfigurationChange(activity, BASE_SEQ);

            final int orientation = activity.mConfig.orientation;
            final int numOfConfig = activity.mNumOfConfigChanges;

            final ActivityThread activityThread = activity.getActivityThread();

            final Configuration newerConfig = new Configuration();
            newerConfig.orientation = orientation == ORIENTATION_LANDSCAPE
                    ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
            newerConfig.seq = seq + 2;
            activityThread.updatePendingActivityConfiguration(activity.getActivityToken(),
                    newerConfig);

            final Configuration olderConfig = new Configuration();
            olderConfig.orientation = orientation;
            olderConfig.seq = seq + 1;

            final ActivityClientRecord r = getActivityClientRecord(activity);
            activityThread.handleActivityConfigurationChanged(r, olderConfig, INVALID_DISPLAY);
            assertEquals(numOfConfig, activity.mNumOfConfigChanges);
            assertEquals(olderConfig.orientation, activity.mConfig.orientation);

            activityThread.handleActivityConfigurationChanged(r, newerConfig, INVALID_DISPLAY);
            assertEquals(numOfConfig + 1, activity.mNumOfConfigChanges);
            assertEquals(newerConfig.orientation, activity.mConfig.orientation);
        });
    }

    @Test
    public void testHandleActivityConfigurationChanged_EnsureUpdatesProcessedInOrder()
            throws Exception {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        final ActivityThread activityThread = activity.getActivityThread();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Configuration config = new Configuration();
            config.seq = BASE_SEQ;
            config.orientation = ORIENTATION_PORTRAIT;

            activityThread.handleActivityConfigurationChanged(getActivityClientRecord(activity),
                    config, INVALID_DISPLAY);
        });

        final IApplicationThread appThread = activityThread.getApplicationThread();
        final int numOfConfig = activity.mNumOfConfigChanges;

        final Configuration processConfigLandscape = new Configuration();
        processConfigLandscape.orientation = ORIENTATION_LANDSCAPE;
        processConfigLandscape.windowConfiguration.setBounds(new Rect(0, 0, 100, 60));
        processConfigLandscape.seq = BASE_SEQ + 1;

        final Configuration activityConfigLandscape = new Configuration();
        activityConfigLandscape.orientation = ORIENTATION_LANDSCAPE;
        activityConfigLandscape.windowConfiguration.setBounds(new Rect(0, 0, 100, 50));
        activityConfigLandscape.seq = BASE_SEQ + 2;

        final Configuration processConfigPortrait = new Configuration();
        processConfigPortrait.orientation = ORIENTATION_PORTRAIT;
        processConfigPortrait.windowConfiguration.setBounds(new Rect(0, 0, 60, 100));
        processConfigPortrait.seq = BASE_SEQ + 3;

        final Configuration activityConfigPortrait = new Configuration();
        activityConfigPortrait.orientation = ORIENTATION_PORTRAIT;
        activityConfigPortrait.windowConfiguration.setBounds(new Rect(0, 0, 50, 100));
        activityConfigPortrait.seq = BASE_SEQ + 4;

        activity.mConfigLatch = new CountDownLatch(1);
        activity.mTestLatch = new CountDownLatch(1);

        ClientTransaction transaction = newTransaction(activityThread, null);
        transaction.addCallback(ConfigurationChangeItem.obtain(
                processConfigLandscape, DEVICE_ID_INVALID));
        appThread.scheduleTransaction(transaction);

        transaction = newTransaction(activityThread, activity.getActivityToken());
        transaction.addCallback(ActivityConfigurationChangeItem.obtain(activityConfigLandscape));
        transaction.addCallback(ConfigurationChangeItem.obtain(
                processConfigPortrait, DEVICE_ID_INVALID));
        transaction.addCallback(ActivityConfigurationChangeItem.obtain(activityConfigPortrait));
        appThread.scheduleTransaction(transaction);

        activity.mTestLatch.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        activity.mConfigLatch.countDown();

        activity.mConfigLatch = null;
        activity.mTestLatch = null;

        // Check display metrics, bounds should match the portrait activity bounds.
        final Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
        assertEquals(activityConfigPortrait.windowConfiguration.getBounds(), bounds);

        // Ensure changes in window configuration bounds are reported
        assertEquals(numOfConfig + 1, activity.mNumOfConfigChanges);
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

            activityThread.handleActivityConfigurationChanged(getActivityClientRecord(activity),
                    config, INVALID_DISPLAY);
        });

        final int numOfConfig = activity.mNumOfConfigChanges;
        final IApplicationThread appThread = activityThread.getApplicationThread();

        activity.mConfigLatch = new CountDownLatch(1);
        activity.mTestLatch = new CountDownLatch(1);

        Configuration config = new Configuration();
        config.seq = BASE_SEQ + 1;
        config.orientation = ORIENTATION_LANDSCAPE;
        appThread.scheduleTransaction(newActivityConfigTransaction(activity, config));

        // Wait until the main thread is performing the configuration change for the configuration
        // with sequence number BASE_SEQ + 1 before proceeding. This is to mimic the situation where
        // the activity takes very long time to process configuration changes.
        activity.mTestLatch.await(TIMEOUT_SEC, TimeUnit.SECONDS);

        config = new Configuration();
        config.seq = BASE_SEQ + 2;
        config.orientation = ORIENTATION_PORTRAIT;
        appThread.scheduleTransaction(newActivityConfigTransaction(activity, config));

        config = new Configuration();
        config.seq = BASE_SEQ + 3;
        config.orientation = ORIENTATION_LANDSCAPE;
        appThread.scheduleTransaction(newActivityConfigTransaction(activity, config));

        config = new Configuration();
        config.seq = BASE_SEQ + 4;
        config.orientation = ORIENTATION_PORTRAIT;
        appThread.scheduleTransaction(newActivityConfigTransaction(activity, config));

        activity.mConfigLatch.countDown();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        activity.mConfigLatch = null;
        activity.mTestLatch = null;

        // Only two more configuration changes: one with seq BASE_SEQ + 1; another with seq
        // BASE_SEQ + 4. Configurations scheduled in between should be dropped.
        assertEquals(numOfConfig + 2, activity.mNumOfConfigChanges);
        assertEquals(ORIENTATION_PORTRAIT, activity.mConfig.orientation);
    }

    @Test
    public void testOrientationChanged_DoesntOverrideVirtualDisplayOrientation() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context appContext = activity.getApplication();
            Configuration originalAppConfig =
                    new Configuration(appContext.getResources().getConfiguration());

            int virtualDisplayWidth;
            int virtualDisplayHeight;
            if (originalAppConfig.orientation == ORIENTATION_PORTRAIT) {
                virtualDisplayWidth = 100;
                virtualDisplayHeight = 200;
            } else {
                virtualDisplayWidth = 200;
                virtualDisplayHeight = 100;
            }
            final Display virtualDisplay = createVirtualDisplay(appContext,
                    virtualDisplayWidth, virtualDisplayHeight);
            Context virtualDisplayContext = appContext.createDisplayContext(virtualDisplay);
            int originalVirtualDisplayOrientation = virtualDisplayContext.getResources()
                    .getConfiguration().orientation;


            // Perform global config change and verify there is no config change in derived display
            // context.
            Configuration newAppConfig = new Configuration(originalAppConfig);
            newAppConfig.seq++;
            newAppConfig.orientation = newAppConfig.orientation == ORIENTATION_PORTRAIT
                    ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;

            activityThread.updatePendingConfiguration(newAppConfig);
            activityThread.handleConfigurationChanged(newAppConfig, DEVICE_ID_INVALID);

            try {
                assertEquals("Virtual display orientation must not change when process"
                                + " configuration orientation changes.",
                        originalVirtualDisplayOrientation,
                        virtualDisplayContext.getResources().getConfiguration().orientation);
            } finally {
                // Make sure to reset the process config to prevent side effects to other
                // tests.
                restoreConfig(activityThread, originalAppConfig);
            }
        });
    }

    private static void restoreConfig(ActivityThread thread, Configuration originalConfig) {
        thread.getConfiguration().seq = originalConfig.seq - 1;
        ResourcesManager.getInstance().getConfiguration().seq = originalConfig.seq - 1;
        thread.handleConfigurationChanged(originalConfig, DEVICE_ID_INVALID);
    }

    @Test
    public void testActivityOrientationChanged_DoesntOverrideVirtualDisplayOrientation() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Configuration originalActivityConfig =
                    new Configuration(activity.getResources().getConfiguration());

            int virtualDisplayWidth;
            int virtualDisplayHeight;
            if (originalActivityConfig.orientation == ORIENTATION_PORTRAIT) {
                virtualDisplayWidth = 100;
                virtualDisplayHeight = 200;
            } else {
                virtualDisplayWidth = 200;
                virtualDisplayHeight = 100;
            }
            final Display virtualDisplay = createVirtualDisplay(activity,
                    virtualDisplayWidth, virtualDisplayHeight);
            Context virtualDisplayContext = activity.createDisplayContext(virtualDisplay);
            int originalVirtualDisplayOrientation = virtualDisplayContext.getResources()
                    .getConfiguration().orientation;

            // Perform activity config change and verify there is no config change in derived
            // display context.
            Configuration newActivityConfig = new Configuration(originalActivityConfig);
            newActivityConfig.seq++;
            newActivityConfig.orientation = newActivityConfig.orientation == ORIENTATION_PORTRAIT
                    ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;

            final ActivityClientRecord r = getActivityClientRecord(activity);
            activityThread.updatePendingActivityConfiguration(activity.getActivityToken(),
                    newActivityConfig);
            activityThread.handleActivityConfigurationChanged(r, newActivityConfig,
                    INVALID_DISPLAY);

            assertEquals("Virtual display orientation must not change when activity"
                            + " configuration orientation changes.",
                    originalVirtualDisplayOrientation,
                    virtualDisplayContext.getResources().getConfiguration().orientation);
        });
    }

    @Test
    public void testHandleConfigurationChanged_DoesntOverrideActivityConfig() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final Configuration oldActivityConfig =
                    new Configuration(activity.getResources().getConfiguration());
            final DisplayMetrics oldActivityMetrics = new DisplayMetrics();
            activity.getDisplay().getMetrics(oldActivityMetrics);
            final Resources oldAppResources = activity.getApplication().getResources();
            final Configuration oldAppConfig =
                    new Configuration(oldAppResources.getConfiguration());
            final DisplayMetrics oldApplicationMetrics = new DisplayMetrics();
            oldApplicationMetrics.setTo(oldAppResources.getDisplayMetrics());
            assertEquals("Process config must match the top activity config by default",
                    0, oldActivityConfig.diffPublicOnly(oldAppConfig));
            assertEquals("Process config must match the top activity config by default",
                    oldActivityMetrics, oldApplicationMetrics);

            // Update the application configuration separately from activity config
            final Configuration newAppConfig = new Configuration(oldAppConfig);
            newAppConfig.densityDpi += 100;
            newAppConfig.screenHeightDp += 100;
            final Rect newBounds = new Rect(newAppConfig.windowConfiguration.getAppBounds());
            newBounds.bottom += 100;
            newAppConfig.windowConfiguration.setAppBounds(newBounds);
            newAppConfig.windowConfiguration.setBounds(newBounds);
            newAppConfig.seq++;

            final ActivityThread activityThread = activity.getActivityThread();
            activityThread.handleConfigurationChanged(newAppConfig, DEVICE_ID_INVALID);

            // Verify that application config update was applied, but didn't change activity config.
            assertEquals("Activity config must not change if the process config changes",
                    oldActivityConfig, activity.getResources().getConfiguration());

            final DisplayMetrics newActivityMetrics = new DisplayMetrics();
            activity.getDisplay().getMetrics(newActivityMetrics);
            assertEquals("Activity display size must not change if the process config changes",
                    oldActivityMetrics, newActivityMetrics);
            final Resources newAppResources = activity.getApplication().getResources();
            assertEquals("Application config must be updated",
                    newAppConfig, newAppResources.getConfiguration());
            final DisplayMetrics newApplicationMetrics = new DisplayMetrics();
            newApplicationMetrics.setTo(newAppResources.getDisplayMetrics());
            assertNotEquals("Application display size must be updated after config update",
                    oldApplicationMetrics, newApplicationMetrics);
            assertNotEquals("Application display size must be updated after config update",
                    newActivityMetrics, newApplicationMetrics);
        });
    }

    @Test
    public void testResumeAfterNewIntent() {
        final Activity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();
        final ArrayList<ReferrerIntent> rIntents = new ArrayList<>();
        rIntents.add(new ReferrerIntent(new Intent(), "android.app.activity"));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.executeTransaction(newNewIntentTransaction(activity, rIntents, true));
        });
        assertThat(activity.isResumed()).isTrue();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.executeTransaction(newStopTransaction(activity));
            activityThread.executeTransaction(newNewIntentTransaction(activity, rIntents, false));
        });
        assertThat(activity.isResumed()).isFalse();
    }

    @Test
    public void testHandlePictureInPictureRequested_overriddenToEnter() {
        final Intent startIntent = new Intent();
        startIntent.putExtra(TestActivity.PIP_REQUESTED_OVERRIDE_ENTER, true);
        final TestActivity activity = mActivityTestRule.launchActivity(startIntent);
        final ActivityThread activityThread = activity.getActivityThread();
        final ActivityClientRecord r = getActivityClientRecord(activity);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.handlePictureInPictureRequested(r);
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
        final ActivityClientRecord r = getActivityClientRecord(activity);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.handlePictureInPictureRequested(r);
        });

        assertTrue(activity.pipRequested());
        assertTrue(activity.enterPipSkipped());
    }

    @Test
    public void testHandlePictureInPictureRequested_notOverridden() {
        final TestActivity activity = mActivityTestRule.launchActivity(new Intent());
        final ActivityThread activityThread = activity.getActivityThread();
        final ActivityClientRecord r = getActivityClientRecord(activity);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            activityThread.handlePictureInPictureRequested(r);
        });

        assertTrue(activity.pipRequested());
        assertFalse(activity.enteredPip());
        assertFalse(activity.enterPipSkipped());
    }

    /**
     * Calls {@link ActivityThread#handleActivityConfigurationChanged(ActivityClientRecord,
     * Configuration, int)} to try to push activity configuration to the activity for the given
     * sequence number.
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
        final ActivityClientRecord r = getActivityClientRecord(activity);

        final int numOfConfig = activity.mNumOfConfigChanges;
        Configuration config = new Configuration();
        config.orientation = ORIENTATION_PORTRAIT;
        config.seq = seq;
        activityThread.handleActivityConfigurationChanged(r, config, INVALID_DISPLAY);

        if (activity.mNumOfConfigChanges > numOfConfig) {
            return config.seq;
        }

        config = new Configuration();
        config.orientation = ORIENTATION_LANDSCAPE;
        config.seq = seq + 1;
        activityThread.handleActivityConfigurationChanged(r, config, INVALID_DISPLAY);

        return config.seq;
    }

    private Display createVirtualDisplay(Context context, int w, int h) {
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        final VirtualDisplay virtualDisplay = dm.createVirtualDisplay("virtual-display", w, h,
                200 /* densityDpi */, null /* surface */, 0 /* flags */);
        if (mCreatedVirtualDisplays == null) {
            mCreatedVirtualDisplays = new ArrayList<>();
        }
        mCreatedVirtualDisplays.add(virtualDisplay);
        return virtualDisplay.getDisplay();
    }

    private static ActivityClientRecord getActivityClientRecord(Activity activity) {
        final ActivityThread thread = activity.getActivityThread();
        final IBinder token = activity.getActivityToken();
        return thread.getActivityClient(token);
    }

    private static ClientTransaction newRelaunchResumeTransaction(Activity activity) {
        final ClientTransactionItem callbackItem = ActivityRelaunchItem.obtain(null,
                null, 0, new MergedConfiguration(), false /* preserveWindow */);
        final ResumeActivityItem resumeStateRequest =
                ResumeActivityItem.obtain(true /* isForward */,
                        false /* shouldSendCompatFakeFocus*/);

        final ClientTransaction transaction = newTransaction(activity);
        transaction.addCallback(callbackItem);
        transaction.setLifecycleStateRequest(resumeStateRequest);

        return transaction;
    }

    private static ClientTransaction newResumeTransaction(Activity activity) {
        final ResumeActivityItem resumeStateRequest =
                ResumeActivityItem.obtain(true /* isForward */,
                        false /* shouldSendCompatFakeFocus */);

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
        return newTransaction(activity.getActivityThread(), activity.getActivityToken());
    }

    private static ClientTransaction newTransaction(ActivityThread activityThread,
            @Nullable IBinder activityToken) {
        return ClientTransaction.obtain(activityThread.getApplicationThread(), activityToken);
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
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getWindow().getDecorView().setKeepScreenOn(true);
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

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
                    mConfigLatch.await(TIMEOUT_SEC, TimeUnit.SECONDS);
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
