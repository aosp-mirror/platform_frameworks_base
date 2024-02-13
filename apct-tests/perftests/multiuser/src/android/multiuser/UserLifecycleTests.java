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
package android.multiuser;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.IPackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.perftests.utils.ShellHelper;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManagerGlobal;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FunctionalUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Perf tests for user life cycle events.
 *
 * To run the tests: atest UserLifecycleTests
 *
 *
 * Old methods for running the tests:
 *
 * make MultiUserPerfTests &&
 * adb install -r \
 *     ${ANDROID_PRODUCT_OUT}/data/app/MultiUserPerfTests/MultiUserPerfTests.apk &&
 * adb shell am instrument -e class android.multiuser.UserLifecycleTests \
 *     -w com.android.perftests.multiuser/androidx.test.runner.AndroidJUnitRunner
 *
 * or
 *
 * bit MultiUserPerfTests:android.multiuser.UserLifecycleTests
 *
 * Note: If you use bit for running the tests, benchmark results won't be printed on the host side.
 * But in either case, results can be checked on the device side 'adb logcat -s UserLifecycleTests'
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class UserLifecycleTests {
    private static final String TAG = UserLifecycleTests.class.getSimpleName();

    /** Max runtime for each test (including all runs within that test). */
    // Must be less than the AndroidTest.xml test-timeout to avoid being considered non-responsive.
    private static final long TIMEOUT_MAX_TEST_TIME_MS = 24 * 60_000;

    private static final int TIMEOUT_IN_SECOND = 30;

    /** Name of users/profiles in the test. Users with this name may be freely removed. */
    private static final String TEST_USER_NAME = "UserLifecycleTests_test_user";

    /** Name of placeholder package used when timing how long app launches take. */
    private static final String DUMMY_PACKAGE_NAME = "perftests.multiuser.apps.dummyapp";

    // Copy of UserSystemPackageInstaller allowlist mode constants.
    private static final String PACKAGE_ALLOWLIST_MODE_PROP =
            "persist.debug.user.package_whitelist_mode";
    private static final int USER_TYPE_PACKAGE_ALLOWLIST_MODE_DISABLE = 0;
    private static final int USER_TYPE_PACKAGE_ALLOWLIST_MODE_ENFORCE = 0b001;
    private static final int USER_TYPE_PACKAGE_ALLOWLIST_MODE_IMPLICIT_ALLOWLIST = 0b100;
    private static final int USER_TYPE_PACKAGE_ALLOWLIST_MODE_DEVICE_DEFAULT = -1;

    private UserManager mUm;
    private ActivityManager mAm;
    private IActivityManager mIam;
    private PackageManager mPm;
    private WallpaperManager mWm;
    private ArrayList<Integer> mUsersToRemove;
    private boolean mHasManagedUserFeature;
    private BroadcastWaiter mBroadcastWaiter;
    private UserSwitchWaiter mUserSwitchWaiter;
    private String mUserSwitchTimeoutMs;
    private String mDisableUserSwitchingDialogAnimations;

    private final BenchmarkRunner mRunner = new BenchmarkRunner();
    @Rule
    public BenchmarkResultsReporter mReporter = new BenchmarkResultsReporter(mRunner);

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getContext();
        mUm = UserManager.get(context);
        mAm = context.getSystemService(ActivityManager.class);
        mIam = ActivityManager.getService();
        mUsersToRemove = new ArrayList<>();
        mPm = context.getPackageManager();
        mWm = WallpaperManager.getInstance(context);
        mHasManagedUserFeature = mPm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS);
        mBroadcastWaiter = new BroadcastWaiter(context, TAG, TIMEOUT_IN_SECOND,
                Intent.ACTION_USER_STARTED,
                Intent.ACTION_MEDIA_MOUNTED,
                Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_USER_STOPPED);
        mUserSwitchWaiter = new UserSwitchWaiter(TAG, TIMEOUT_IN_SECOND);
        removeAnyPreviousTestUsers();
        if (mAm.getCurrentUser() != UserHandle.USER_SYSTEM) {
            Log.w(TAG, "WARNING: Tests are being run from user " + mAm.getCurrentUser()
                    + " rather than the system user");
        }
        mUserSwitchTimeoutMs = setSystemProperty(
                "debug.usercontroller.user_switch_timeout_ms", "100000");
        mDisableUserSwitchingDialogAnimations = setSystemProperty(
                "debug.usercontroller.disable_user_switching_dialog_animations", "true");
    }

    @After
    public void tearDown() throws Exception {
        setSystemProperty("debug.usercontroller.user_switch_timeout_ms", mUserSwitchTimeoutMs);
        setSystemProperty("debug.usercontroller.disable_user_switching_dialog_animations",
                mDisableUserSwitchingDialogAnimations);
        mBroadcastWaiter.close();
        mUserSwitchWaiter.close();
        for (int userId : mUsersToRemove) {
            try {
                mUm.removeUser(userId);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    /** Tests creating a new user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void createUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            Log.i(TAG, "Starting timer");
            final int userId = createUserNoFlags();

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests creating a new user, with wait times between iterations. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void createUser_realistic() throws RemoteException {
        while (mRunner.keepRunning()) {
            Log.i(TAG, "Starting timer");
            final int userId = createUserNoFlags();

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests creating and starting a new user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void createAndStartUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            Log.i(TAG, "Starting timer");
            final int userId = createUserNoFlags();

            // Don't use this.startUserInBackgroundAndWaitForUnlock() since only waiting until
            // ACTION_USER_STARTED.
            runThenWaitForBroadcasts(userId, () -> {
                mIam.startUserInBackground(userId);
            }, Intent.ACTION_USER_STARTED);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests creating and starting a new user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void createAndStartUser_realistic() throws RemoteException {
        while (mRunner.keepRunning()) {
            Log.d(TAG, "Starting timer");
            final int userId = createUserNoFlags();

            // Don't use this.startUserInBackgroundAndWaitForUnlock() since only waiting until
            // ACTION_USER_STARTED.
            runThenWaitForBroadcasts(userId, () -> {
                mIam.startUserInBackground(userId);
            }, Intent.ACTION_USER_STARTED);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests starting an uninitialized user.
     * Measures the time until ACTION_USER_STARTED is received.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void startUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createUserNoFlags();

            waitForBroadcastIdle();
            runThenWaitForBroadcasts(userId, () -> {
                mRunner.resumeTiming();
                Log.i(TAG, "Starting timer");

                mIam.startUserInBackground(userId);
            }, Intent.ACTION_USER_STARTED);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests starting an uninitialized user, with wait times in between iterations.
     *
     * The first iteration will take longer due to the process of setting policy permissions for
     * a new user.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void startUser_uninitializedUser() throws RemoteException {
        startUser_measuresAfterFirstIterations(/* numberOfIterationsToSkip */0);
    }

    /**
     * Tests the second iteration of start user that has a problem that it takes too long to run, a
     * bug has been created (b/266574680) and after investigating or fix this problem,
     * this test can be removed.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void startUser_startedOnceBefore() throws RemoteException {
        startUser_measuresAfterFirstIterations(/* numberOfIterationsToSkip */1);
    }

    /**
     * Tests a specific iteration of the start user process.
     * Measures the time until ACTION_USER_STARTED is received.
     * @param numberOfIterationsToSkip number of iterations that must be skipped in the preStartUser
     *                                 method.
     */
    private void startUser_measuresAfterFirstIterations(int numberOfIterationsToSkip)
            throws RemoteException {
        /**
         * Run start user and stop for the next iteration, measures time while mRunner.keepRunning()
         * return true.
         */
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();

            final int userId = createUserNoFlags();

            preStartUser(userId, numberOfIterationsToSkip);

            waitForBroadcastIdle();
            waitCoolDownPeriod();

            runThenWaitForBroadcasts(userId, () -> {
                mRunner.resumeTiming();
                Log.i(TAG, "Starting timer");

                mIam.startUserInBackground(userId);
            }, Intent.ACTION_USER_STARTED);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");

            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests starting an initialized user, with wait times in between iterations stopping between
     * iterations,this test will skip the first two iterations and only measure the next ones.
     *
     * The first iteration will take longer due to the process of setting policy permissions for
     * a new user.
     *
     * The second iteration takes longer than expected and has a bug (b/266574680) to investigate
     * it.
     *
     * The next iterations take the expected time to start a user.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void startUser_startedTwiceBefore() throws RemoteException {
        final int userId = createUserNoFlags();

        //TODO(b/266681181) Reduce iteration number by 1 after investigation and possible fix.
        preStartUser(userId, /* numberOfIterations */2);

        /**
         * Run start user and stop for the next iteration, measures time while mRunner.keepRunning()
         * return true.
         */
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();

            waitForBroadcastIdle();
            waitCoolDownPeriod();

            runThenWaitForBroadcasts(userId, () -> {
                mRunner.resumeTiming();
                Log.i(TAG, "Starting timer");

                mIam.startUserInBackground(userId);
            }, Intent.ACTION_USER_STARTED);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");

            stopUser(userId);
            mRunner.resumeTimingForNextIteration();
        }

        removeUser(userId);
    }


    /**
     * Tests starting & unlocking an uninitialized user.
     * Measures the time until unlock listener is triggered and user is unlocked.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void startAndUnlockUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createUserNoFlags();
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            // Waits for UserState.mUnlockProgress.finish().
            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests starting & unlocking an initialized user, stopping the user at the end simulating real
     * usage where the user is not removed after created and initialized.
     * Measures the time until unlock listener is triggered and user is unlocked.
     * This test will skip the first two iterations and only measure the next ones.
     *
     * The first iteration will take longer due to the process of setting policy permissions for a
     * new user.
     *
     * The second iteration takes longer than expected and has a bug (b/266574680) to investigate
     * it.
     *
     * The next iterations take the expected time to start a user.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void startAndUnlockUser_startedTwiceBefore() throws RemoteException {
        final int userId = createUserNoFlags();

        //TODO(b/266681181) Reduce iteration number by 1 after investigation and possible fix.
        preStartUser(userId, /* numberOfIterations */2);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();

            waitCoolDownPeriod();
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            // Waits for UserState.mUnlockProgress.finish().
            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            stopUser(userId);
            mRunner.resumeTimingForNextIteration();
        }

        removeUser(userId);
    }

    /**
     * Tests starting & unlocking an uninitialized user.
     * Measures the time until unlock listener is triggered and user is unlocked.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void startAndUnlockUser_realistic() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createUserNoFlags();
            mRunner.resumeTiming();
            Log.d(TAG, "Starting timer");

            // Waits for UserState.mUnlockProgress.finish().
            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests switching to an uninitialized user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void switchUser() throws Exception {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int userId = createUserNoFlags();
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            switchUser(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests switching to an uninitialized user with wait times between iterations. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void switchUser_realistic() throws Exception {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = ActivityManager.getCurrentUser();
            final int userId = createUserNoFlags();
            waitCoolDownPeriod();
            Log.d(TAG, "Starting timer");
            mRunner.resumeTiming();

            switchUser(userId);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests switching to a previously-started, but no-longer-running, user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void switchUser_stopped() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ true);
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            switchUser(testUser);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(testUser);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests switching to a previously-started, but no-longer-running, user with wait
     * times between iterations
     **/
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void switchUser_stopped_realistic() throws RemoteException {
        final int currentUserId = ActivityManager.getCurrentUser();
        final int userId = initializeNewUserAndSwitchBack(/* stopNewUser */ true);

        /**
         * Skip the second iteration of start user process that is taking a long time to finish.
         */
        preStartUser(userId, /* numberOfIterations */1);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            waitCoolDownPeriod();
            Log.d(TAG, "Starting timer");
            mRunner.resumeTiming();

            switchUser(userId);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            switchUserNoCheck(currentUserId);
            stopUserAfterWaitingForBroadcastIdle(userId);
            attestFalse("Failed to stop user " + userId, mAm.isUserRunning(userId));
            mRunner.resumeTimingForNextIteration();
        }
        removeUser(userId);
    }

    /** Tests switching to a previously-started, but no-longer-running, user with wait
     * times between iterations and using a static wallpaper */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void switchUser_stopped_staticWallpaper() throws RemoteException {
        assumeTrue(mWm.isWallpaperSupported() && mWm.isSetWallpaperAllowed());
        final int startUser = ActivityManager.getCurrentUser();
        final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ true,
                /* useStaticWallpaper */true);
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            waitCoolDownPeriod();
            Log.d(TAG, "Starting timer");
            mRunner.resumeTiming();

            switchUser(testUser);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            stopUserAfterWaitingForBroadcastIdle(testUser);
            attestFalse("Failed to stop user " + testUser, mAm.isUserRunning(testUser));
            mRunner.resumeTimingForNextIteration();
        }
        removeUser(testUser);
    }

    /** Tests switching to an already-created already-running non-owner background user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void switchUser_running() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ false);
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            switchUser(testUser);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(testUser);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests switching to an already-created already-running non-owner background user, with wait
     * times between iterations */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void switchUser_running_initializedUser() throws RemoteException {
        final int startUser = ActivityManager.getCurrentUser();
        final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ false);
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            waitCoolDownPeriod();
            Log.d(TAG, "Starting timer");
            mRunner.resumeTiming();

            switchUser(testUser);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            waitForBroadcastIdle();
            switchUserNoCheck(startUser);
            mRunner.resumeTimingForNextIteration();
        }
        removeUser(testUser);
    }

    /** Tests switching to an already-created already-running non-owner background user, with wait
     * times between iterations and using a default static wallpaper */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void switchUser_running_staticWallpaper() throws RemoteException {
        assumeTrue(mWm.isWallpaperSupported() && mWm.isSetWallpaperAllowed());
        final int startUser = ActivityManager.getCurrentUser();
        final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ false,
                /* useStaticWallpaper */ true);
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            waitCoolDownPeriod();
            Log.d(TAG, "Starting timer");
            mRunner.resumeTiming();

            switchUser(testUser);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            waitForBroadcastIdle();
            switchUserNoCheck(startUser);
            mRunner.resumeTimingForNextIteration();
        }
        removeUser(testUser);
    }

    /** Tests stopping a background user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void stopUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createUserNoFlags();

            runThenWaitForBroadcasts(userId, ()-> {
                mIam.startUserInBackground(userId);
            }, Intent.ACTION_USER_STARTED, Intent.ACTION_MEDIA_MOUNTED);

            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            stopUser(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests stopping a background user, with wait times between iterations. The hypothesis is
     * that the effects of the user creation could impact the measured times, so in this variant we
     * create one user per run, instead of one per iteration */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void stopUser_realistic() throws RemoteException {
        final int userId = createUserNoFlags();
        waitCoolDownPeriod();
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            runThenWaitForBroadcasts(userId, ()-> {
                mIam.startUserInBackground(userId);
            }, Intent.ACTION_USER_STARTED, Intent.ACTION_MEDIA_MOUNTED);
            waitCoolDownPeriod();
            Log.d(TAG, "Starting timer");
            mRunner.resumeTiming();

            stopUser(userId);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");

            mRunner.resumeTimingForNextIteration();
        }
        removeUser(userId);
    }

    /** Tests reaching LOCKED_BOOT_COMPLETE when switching to uninitialized user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void lockedBootCompleted() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int userId = createUserNoFlags();

            waitForBroadcastIdle();
            mUserSwitchWaiter.runThenWaitUntilBootCompleted(userId, () -> {
                mRunner.resumeTiming();
                Log.i(TAG, "Starting timer");
                mAm.switchUser(userId);
            }, () -> fail("Failed to achieve onLockedBootComplete for user " + userId));

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests reaching LOCKED_BOOT_COMPLETE when switching to uninitialized user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void lockedBootCompleted_realistic() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = ActivityManager.getCurrentUser();
            final int userId = createUserNoFlags();

            waitCoolDownPeriod();
            mUserSwitchWaiter.runThenWaitUntilBootCompleted(userId, () -> {
                mRunner.resumeTiming();
                Log.d(TAG, "Starting timer");
                mAm.switchUser(userId);
            }, () -> fail("Failed to achieve onLockedBootComplete for user " + userId));

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests stopping an ephemeral foreground user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void ephemeralUserStopped() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int userId = createUserWithFlags(UserInfo.FLAG_EPHEMERAL | UserInfo.FLAG_DEMO);
            runThenWaitForBroadcasts(userId, () -> {
                switchUser(userId);
            }, Intent.ACTION_MEDIA_MOUNTED);

            waitForBroadcastIdle();
            mUserSwitchWaiter.runThenWaitUntilSwitchCompleted(startUser, () -> {
                runThenWaitForBroadcasts(userId, () -> {
                    mRunner.resumeTiming();
                    Log.i(TAG, "Starting timer");

                    mAm.switchUser(startUser);
                }, Intent.ACTION_USER_STOPPED);

                mRunner.pauseTiming();
                Log.i(TAG, "Stopping timer");
            }, null);

            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests stopping an ephemeral foreground user. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void ephemeralUserStopped_realistic() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = ActivityManager.getCurrentUser();
            final int userId = createUserWithFlags(UserInfo.FLAG_EPHEMERAL | UserInfo.FLAG_DEMO);
            runThenWaitForBroadcasts(userId, () -> {
                switchUser(userId);
            }, Intent.ACTION_MEDIA_MOUNTED);

            waitCoolDownPeriod();
            mUserSwitchWaiter.runThenWaitUntilSwitchCompleted(startUser, () -> {
                runThenWaitForBroadcasts(userId, () -> {
                    mRunner.resumeTiming();
                    Log.d(TAG, "Starting timer");

                    mAm.switchUser(startUser);
                }, Intent.ACTION_USER_STOPPED);

                mRunner.pauseTiming();
                Log.d(TAG, "Stopping timer");
            }, null);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests creating a new profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileCreate() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            Log.i(TAG, "Starting timer");
            final int userId = createManagedProfile();

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            attestTrue("Failed creating profile " + userId, mUm.isManagedProfile(userId));
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests creating a new profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileCreate_realistic() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            Log.d(TAG, "Starting timer");
            final int userId = createManagedProfile();

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            attestTrue("Failed creating profile " + userId, mUm.isManagedProfile(userId));
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests starting (unlocking) an uninitialized profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlock() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests starting (unlocking) an uninitialized profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlock_realistic() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            mRunner.resumeTiming();
            Log.d(TAG, "Starting timer");

            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests starting (unlocking) a previously-started, but no-longer-running, profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlock_stopped() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            // Start the profile initially, then stop it. Similar to setQuietModeEnabled.
            startUserInBackgroundAndWaitForUnlock(userId);
            stopUserAfterWaitingForBroadcastIdle(userId);
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests starting (unlocking) a previously-started, but no-longer-running, profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlock_stopped_realistic() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);
        final int userId = createManagedProfile();
        // Start the profile initially, then stop it. Similar to setQuietModeEnabled.
        startUserInBackgroundAndWaitForUnlock(userId);
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            stopUserAfterWaitingForBroadcastIdle(userId);
            mRunner.resumeTiming();
            Log.d(TAG, "Starting timer");

            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
        removeUser(userId);
    }

    /**
     * Tests starting (unlocking) & launching an already-installed app in an uninitialized profile.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlockAndLaunchApp() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests starting (unlocking) & launching an already-installed app in an uninitialized profile.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlockAndLaunchApp_realistic() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            mRunner.resumeTiming();
            Log.d(TAG, "Starting timer");

            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests starting (unlocking) and launching a previously-launched app
     * in a previously-started, but no-longer-running, profile.
     * A sort of combination of {@link #managedProfileUnlockAndLaunchApp} and
     * {@link #managedProfileUnlock_stopped}}.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlockAndLaunchApp_stopped() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);
            stopUserAfterWaitingForBroadcastIdle(userId);
            SystemClock.sleep(1_000); // 1 second cool-down before re-starting profile.
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests starting (unlocking) and launching a previously-launched app
     * in a previously-started, but no-longer-running, profile.
     * A sort of combination of {@link #managedProfileUnlockAndLaunchApp} and
     * {@link #managedProfileUnlock_stopped}}.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlockAndLaunchApp_stopped_realistic() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);
            stopUserAfterWaitingForBroadcastIdle(userId);
            SystemClock.sleep(1_000); // 1 second cool-down before re-starting profile.
            mRunner.resumeTiming();
            Log.d(TAG, "Starting timer");

            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests installing a pre-existing app in an uninitialized profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileInstall() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests installing a pre-existing app in an uninitialized profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileInstall_realistic() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            mRunner.resumeTiming();
            Log.d(TAG, "Starting timer");

            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests creating a new profile, starting (unlocking) it, installing an app,
     * and launching that app in it.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileCreateUnlockInstallAndLaunchApp() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            final int userId = createManagedProfile();
            startUserInBackgroundAndWaitForUnlock(userId);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /**
     * Tests creating a new profile, starting (unlocking) it, installing an app,
     * and launching that app in it.
     */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileCreateUnlockInstallAndLaunchApp_realistic() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            mRunner.resumeTiming();
            Log.d(TAG, "Starting timer");

            final int userId = createManagedProfile();
            startUserInBackgroundAndWaitForUnlock(userId);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            removeUser(userId);
            waitCoolDownPeriod();
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests stopping a profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileStopped() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            runThenWaitForBroadcasts(userId, () -> {
                startUserInBackgroundAndWaitForUnlock(userId);
            }, Intent.ACTION_MEDIA_MOUNTED);

            mRunner.resumeTiming();
            Log.i(TAG, "Starting timer");

            stopUser(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTimingForNextIteration();
        }
    }

    /** Tests stopping a profile. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileStopped_realistic() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);
        final int userId = createManagedProfile();
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();

            runThenWaitForBroadcasts(userId, () -> {
                startUserInBackgroundAndWaitForUnlock(userId);
            }, Intent.ACTION_MEDIA_MOUNTED);
            waitCoolDownPeriod();
            mRunner.resumeTiming();
            Log.d(TAG, "Starting timer");

            stopUser(userId);

            mRunner.pauseTiming();
            Log.d(TAG, "Stopping timer");
            mRunner.resumeTimingForNextIteration();
        }
        removeUser(userId);
    }

    // TODO: This is just a POC. Do this properly and add more.
    /** Tests starting (unlocking) a newly-created profile using the user-type-pkg-allowlist. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlock_usingWhitelist() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);
        final int origMode = getUserTypePackageAllowlistMode();
        setUserTypePackageAllowlistMode(USER_TYPE_PACKAGE_ALLOWLIST_MODE_ENFORCE
                | USER_TYPE_PACKAGE_ALLOWLIST_MODE_IMPLICIT_ALLOWLIST);

        try {
            while (mRunner.keepRunning()) {
                mRunner.pauseTiming();
                final int userId = createManagedProfile();
                mRunner.resumeTiming();
                Log.i(TAG, "Starting timer");

                startUserInBackgroundAndWaitForUnlock(userId);

                mRunner.pauseTiming();
                Log.i(TAG, "Stopping timer");
                removeUser(userId);
                mRunner.resumeTimingForNextIteration();
            }
        } finally {
            setUserTypePackageAllowlistMode(origMode);
        }
    }
    /** Tests starting (unlocking) a newly-created profile NOT using the user-type-pkg-allowlist. */
    @Test(timeout = TIMEOUT_MAX_TEST_TIME_MS)
    public void managedProfileUnlock_notUsingWhitelist() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);
        final int origMode = getUserTypePackageAllowlistMode();
        setUserTypePackageAllowlistMode(USER_TYPE_PACKAGE_ALLOWLIST_MODE_DISABLE);

        try {
            while (mRunner.keepRunning()) {
                mRunner.pauseTiming();
                final int userId = createManagedProfile();
                mRunner.resumeTiming();
                Log.i(TAG, "Starting timer");

                startUserInBackgroundAndWaitForUnlock(userId);

                mRunner.pauseTiming();
                Log.i(TAG, "Stopping timer");
                removeUser(userId);
                mRunner.resumeTimingForNextIteration();
            }
        } finally {
            setUserTypePackageAllowlistMode(origMode);
        }
    }

    /** Creates a new user, returning its userId. */
    private int createUserNoFlags() {
        return createUserWithFlags(/* flags= */ 0);
    }

    /** Creates a new user with the given flags, returning its userId. */
    private int createUserWithFlags(int flags) {
        int userId = mUm.createUser(TEST_USER_NAME, flags).id;
        mUsersToRemove.add(userId);
        return userId;
    }

    /** Creates a managed (work) profile under the current user, returning its userId. */
    private int createManagedProfile() {
        final UserInfo userInfo = mUm.createProfileForUser(TEST_USER_NAME,
                UserManager.USER_TYPE_PROFILE_MANAGED, /* flags */ 0, mAm.getCurrentUser());
        attestFalse("Creating managed profile failed. Most likely there is "
                + "already a pre-existing profile on the device.", userInfo == null);
        mUsersToRemove.add(userInfo.id);
        return userInfo.id;
    }

    /**
     * Start user in background and wait for it to unlock by waiting for
     * UserState.mUnlockProgress.finish().
     * <p> To start in foreground instead, see {@link #switchUser(int)}.
     * <p> This should always be used for profiles since profiles cannot be started in foreground.
     */
    private void startUserInBackgroundAndWaitForUnlock(int userId) {
        try {
            attestTrue("Failed to start user " + userId + " in background.",
                    ShellHelper.runShellCommandWithTimeout("am start-user -w " + userId,
                            TIMEOUT_IN_SECOND).startsWith("Success:"));
        } catch (TimeoutException e) {
            fail("Could not start user " + userId + " in " + TIMEOUT_IN_SECOND + " seconds");
        }
    }

    /** Starts the given user in the foreground. */
    private void switchUser(int userId) throws RemoteException {
        boolean success = switchUserNoCheck(userId);
        attestTrue("Failed to properly switch to user " + userId, success);
    }

    /**
     * Starts the given user in the foreground.
     * Returns true if successful. Does not fail the test if unsuccessful.
     * If lack of success should fail the test, use {@link #switchUser(int)} instead.
     */
    private boolean switchUserNoCheck(int userId) throws RemoteException {
        final boolean[] success = {true};
        mUserSwitchWaiter.runThenWaitUntilSwitchCompleted(userId, () -> {
            mAm.switchUser(userId);
        }, () -> success[0] = false);
        return success[0];
    }

    /**
     * Waits for broadcast idle before stopping a user, to prevent timeouts on stop user.
     * Stopping a user heavily depends on broadcast queue, and that gets crowded after user creation
     * or user switches, which leads to a timeout on stopping user and cause the tests to be flaky.
     * Do not call this method while timing is on. i.e. between mRunner.resumeTiming() and
     * mRunner.pauseTiming(). Otherwise it would cause the test results to be spiky.
     */
    private void stopUserAfterWaitingForBroadcastIdle(int userId)
            throws RemoteException {
        waitForBroadcastIdle();
        stopUser(userId);
    }

    private void stopUser(int userId) throws RemoteException {
        final CountDownLatch latch = new CountDownLatch(1);
        mIam.stopUserWithCallback(userId, new IStopUserCallback.Stub() {
            @Override
            public void userStopped(int userId) throws RemoteException {
                latch.countDown();
            }

            @Override
            public void userStopAborted(int userId) throws RemoteException {
            }
        });
        waitForLatch("Failed to properly stop user " + userId, latch);
    }

    private int initializeNewUserAndSwitchBack(boolean stopNewUser) throws RemoteException {
        return initializeNewUserAndSwitchBack(stopNewUser, /* useStaticWallpaper */ false);
    }

    /**
     * Creates a user and waits for its ACTION_USER_UNLOCKED.
     * Then switches to back to the original user and waits for its switchUser() to finish.
     *
     * @param stopNewUser whether to stop the new user after switching to otherUser.
     * @param useStaticWallpaper whether to switch the wallpaper of the default user to a static.
     * @return userId of the newly created user.
     */
    private int initializeNewUserAndSwitchBack(boolean stopNewUser, boolean useStaticWallpaper)
            throws RemoteException {
        final int origUser = mAm.getCurrentUser();
        // First, create and switch to testUser, waiting for its ACTION_USER_UNLOCKED
        final int testUser = createUserNoFlags();
        runThenWaitForBroadcasts(testUser, () -> {
            mAm.switchUser(testUser);
        }, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MEDIA_MOUNTED);

        if (useStaticWallpaper) {
            assertTrue(mWm.isWallpaperSupported() && mWm.isSetWallpaperAllowed());
            try {
                Bitmap blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);
                mWm.setBitmap(blank, /* visibleCropHint */ null, /* allowBackup */ true,
                        /* which */ FLAG_SYSTEM | FLAG_LOCK, testUser);
            } catch (IOException exception) {
                fail("Unable to set static wallpaper.");
            }
        }

        // Second, switch back to origUser, waiting merely for switchUser() to finish
        switchUser(origUser);
        attestTrue("Didn't switch back to user, " + origUser, origUser == mAm.getCurrentUser());

        if (stopNewUser) {
            stopUserAfterWaitingForBroadcastIdle(testUser);
            attestFalse("Failed to stop user " + testUser, mAm.isUserRunning(testUser));
        }

        return testUser;
    }

    /**
     * Installs the given package in the given user.
     */
    private void installPreexistingApp(int userId, String packageName) throws RemoteException {
        final CountDownLatch latch = new CountDownLatch(1);

        final IntentSender sender = new IntentSender((IIntentSender) new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                latch.countDown();
            }
        });

        final IPackageInstaller installer = AppGlobals.getPackageManager().getPackageInstaller();
        installer.installExistingPackage(packageName,
                PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
                PackageManager.INSTALL_REASON_UNKNOWN, sender, userId, null);

        waitForLatch("Failed to install app " + packageName + " on user " + userId, latch);
    }

    /**
     * Launches the given package in the given user.
     * Make sure the keyguard has been dismissed prior to calling.
     */
    private void startApp(int userId, String packageName) {
        final String failMessage = "User " + userId + " failed to start " + packageName;
        final String component = InstrumentationRegistry.getContext().getPackageManager()
                .getLaunchIntentForPackage(packageName).getComponent().flattenToShortString();
        try {
            final String result = ShellHelper.runShellCommandWithTimeout(
                    "am start -W -n " + component + " --user " + userId, TIMEOUT_IN_SECOND);
            assertTrue(failMessage + ", component=" + component + ", result=" + result,
                    result.contains("Status: ok")
                    && !result.contains("Warning:")
                    && !result.contains("Error:"));
        } catch (TimeoutException e) {
            fail(failMessage + " in " + TIMEOUT_IN_SECOND + " seconds");
        }
    }

    private class ProgressWaiter extends IProgressListener.Stub {
        private final CountDownLatch mFinishedLatch = new CountDownLatch(1);

        @Override
        public void onStarted(int id, Bundle extras) {}

        @Override
        public void onProgress(int id, int progress, Bundle extras) {}

        @Override
        public void onFinished(int id, Bundle extras) {
            mFinishedLatch.countDown();
        }

        public boolean waitForFinish(long timeoutSecs) {
            try {
                return mFinishedLatch.await(timeoutSecs, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Thread interrupted unexpectedly.", e);
                return false;
            }
        }
    }

    /**
     * Waits TIMEOUT_IN_SECOND for the broadcast to be received, otherwise declares the given error.
     * It only works for the broadcasts provided in {@link #mBroadcastWaiter}'s instantiation above.
     * @param userId userId associated with the broadcast. It is {@link Intent#EXTRA_USER_HANDLE}
     *               or in case that is null, then it is {@link BroadcastReceiver#getSendingUserId}.
     * @param runnable function to be run after clearing any possible previously received broadcasts
     *                 and before waiting for the new broadcasts. This function should typically do
     *                 something to trigger broadcasts to be sent. Like starting or stopping a user.
     * @param actions actions of the broadcasts, i.e. {@link Intent#ACTION_USER_STARTED}.
     *                If multiple actions are provided, they will be waited in given order.
     */
    private void runThenWaitForBroadcasts(int userId, FunctionalUtils.ThrowingRunnable runnable,
            String... actions) {
        final String unreceivedAction =
                mBroadcastWaiter.runThenWaitForBroadcasts(userId, runnable, actions);

        attestTrue("Failed to achieve " + unreceivedAction + " for user " + userId,
                unreceivedAction == null);
    }

    /** Waits TIMEOUT_IN_SECOND for the latch to complete, otherwise declares the given error. */
    private void waitForLatch(String errMsg, CountDownLatch latch) {
        boolean success = false;
        try {
            success = latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Thread interrupted unexpectedly.", e);
        }
        attestTrue(errMsg, success);
    }

    /** Gets the PACKAGE_ALLOWLIST_MODE_PROP System Property. */
    private int getUserTypePackageAllowlistMode() {
        return SystemProperties.getInt(PACKAGE_ALLOWLIST_MODE_PROP,
                USER_TYPE_PACKAGE_ALLOWLIST_MODE_DEVICE_DEFAULT);
    }

    /** Sets the PACKAGE_ALLOWLIST_MODE_PROP System Property to the given value. */
    private void setUserTypePackageAllowlistMode(int mode) {
        String result = ShellHelper.runShellCommand(
                String.format("setprop %s %d", PACKAGE_ALLOWLIST_MODE_PROP, mode));
        attestFalse("Failed to set sysprop " + PACKAGE_ALLOWLIST_MODE_PROP + ": " + result,
                result != null && result.contains("Failed"));
    }

    private void removeUser(int userId) throws RemoteException {
        stopUserAfterWaitingForBroadcastIdle(userId);
        try {
            ShellHelper.runShellCommandWithTimeout("pm remove-user -w " + userId,
                    TIMEOUT_IN_SECOND);
        } catch (TimeoutException e) {
            Log.e(TAG, String.format("Could not remove user %d in %d seconds",
                    userId, TIMEOUT_IN_SECOND), e);
        }
        if (mUm.getUserInfo(userId) != null) {
            mUsersToRemove.add(userId);
        }
    }

    private void removeAnyPreviousTestUsers() {
        for (UserInfo user : mUm.getUsers()) {
            if (TEST_USER_NAME.equals(user.name)) {
                Log.i(TAG, "Found previous test user " + user.id + ". Removing it.");
                if (mAm.getCurrentUser() == user.id) {
                    try {
                        switchUserNoCheck(UserHandle.USER_SYSTEM);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to correctly switch to system user", e);
                    }
                }
                mUm.removeUser(user.id);
            }
        }
    }

    /**
     * Start the user and stop after that, will repeat numberOfIterations times.
     * Make sure the user is started before proceeding with the test.
     * @param userId identifier of the user that will be started.
     * @param numberOfIterations number of iterations that must be skipped.
     */
    private void preStartUser(int userId, int numberOfIterations) throws RemoteException {
        for (int i = 0; i < numberOfIterations; i++) {
            final ProgressWaiter preWaiter = new ProgressWaiter();

            final boolean preStartComplete = mIam.startUserInBackgroundWithListener(userId,
                    preWaiter) && preWaiter.waitForFinish(TIMEOUT_IN_SECOND * 1000);
            stopUserAfterWaitingForBroadcastIdle(userId);

            assertTrue("Pre start was not performed for user" + userId, preStartComplete);
        }
    }

    private void fail(@NonNull String message) {
        Log.e(TAG, "Test failed on iteration #" + mRunner.getIteration() + ": " + message);
        mRunner.markAsFailed(new AssertionError(message));
    }

    private void attestTrue(@NonNull String message, boolean assertion) {
        if (!assertion) {
            fail(message);
        }
    }

    private void attestFalse(@NonNull String message, boolean assertion) {
        attestTrue(message, !assertion);
    }

    private String setSystemProperty(String name, String value) throws Exception {
        final String oldValue = ShellHelper.runShellCommand("getprop " + name);
        assertEquals("", ShellHelper.runShellCommand("setprop " + name + " " + value));
        return TextUtils.firstNotEmpty(oldValue, "invalid");
    }

    private void waitForBroadcastIdle() {
        try {
            ShellHelper.runShellCommandWithTimeout(
                    "am wait-for-broadcast-idle --flush-broadcast-loopers", TIMEOUT_IN_SECOND);
        } catch (TimeoutException e) {
            Log.e(TAG, "Ending waitForBroadcastIdle because it is taking too long", e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Ignore
        }
    }

    private void waitCoolDownPeriod() {
        // Heuristic value based on local tests. Stability increased compared to no waiting.
        final int tenSeconds = 1000 * 10;
        waitForBroadcastIdle();
        sleep(tenSeconds);
    }
}
