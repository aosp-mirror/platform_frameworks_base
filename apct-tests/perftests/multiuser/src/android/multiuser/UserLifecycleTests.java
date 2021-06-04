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

import static org.junit.Assume.assumeTrue;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.app.UserSwitchObserver;
import android.app.WaitResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.IPackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.perftests.utils.ShellHelper;
import android.util.Log;
import android.view.WindowManagerGlobal;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    private static final int TIMEOUT_IN_SECOND = 30;
    private static final int CHECK_USER_REMOVED_INTERVAL_MS = 200;

    /** Name of users/profiles in the test. Users with this name may be freely removed. */
    private static final String TEST_USER_NAME = "UserLifecycleTests_test_user";

    /** Name of dummy package used when timing how long app launches take. */
    private static final String DUMMY_PACKAGE_NAME = "perftests.multiuser.apps.dummyapp";

    // Copy of UserSystemPackageInstaller whitelist mode constants.
    private static final String PACKAGE_WHITELIST_MODE_PROP =
            "persist.debug.user.package_whitelist_mode";
    private static final int USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE = 0;
    private static final int USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE = 0b001;
    private static final int USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST = 0b100;
    private static final int USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT = -1;

    private UserManager mUm;
    private ActivityManager mAm;
    private IActivityManager mIam;
    private PackageManager mPm;
    private ArrayList<Integer> mUsersToRemove;
    private boolean mHasManagedUserFeature;

    private final BenchmarkRunner mRunner = new BenchmarkRunner();
    @Rule
    public BenchmarkResultsReporter mReporter = new BenchmarkResultsReporter(mRunner);

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getContext();
        mUm = UserManager.get(context);
        mAm = context.getSystemService(ActivityManager.class);
        mIam = ActivityManager.getService();
        mUsersToRemove = new ArrayList<>();
        mPm = context.getPackageManager();
        mHasManagedUserFeature = mPm.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS);
        removeAnyPreviousTestUsers();
        if (mAm.getCurrentUser() != UserHandle.USER_SYSTEM) {
            Log.w(TAG, "WARNING: Tests are being run from user " + mAm.getCurrentUser()
                    + " rather than the system user");
        }
    }

    @After
    public void tearDown() {
        for (int userId : mUsersToRemove) {
            try {
                mUm.removeUser(userId);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Test
    public void createUser() {
        while (mRunner.keepRunning()) {
            Log.i(TAG, "Starting timer");
            final int userId = createUserNoFlags();

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void createAndStartUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            Log.i(TAG, "Starting timer");
            final int userId = createUserNoFlags();

            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_STARTED, latch, userId);
            // Don't use this.startUserInBackgroundAndWaitForUnlock() since only waiting until
            // ACTION_USER_STARTED.
            mIam.startUserInBackground(userId);
            waitForLatch("Failed to achieve ACTION_USER_STARTED for user " + userId, latch);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /**
     * Measures the time until ACTION_USER_STARTED is received.
     */
    @Test
    public void startUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createUserNoFlags();
            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_STARTED, latch, userId);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            mIam.startUserInBackground(userId);
            waitForLatch("Failed to achieve ACTION_USER_STARTED for user " + userId, latch);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /**
     * Measures the time until unlock listener is triggered and user is unlocked.
     */
    @Test
    public void startAndUnlockUser() {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createUserNoFlags();
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            // Waits for UserState.mUnlockProgress.finish().
            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void switchUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int userId = createUserNoFlags();
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            switchUser(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests switching to an already-created, but no-longer-running, user. */
    @Test
    public void switchUser_stopped() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ true);
            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_UNLOCKED, latch, testUser);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            mAm.switchUser(testUser);
            waitForLatch("Failed to achieve 2nd ACTION_USER_UNLOCKED for user " + testUser, latch);


            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(testUser);
            mRunner.resumeTiming();
        }
    }

    /** Tests switching to an already-created already-running non-owner user. */
    @Test
    public void switchUser_running() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ false);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            switchUser(testUser);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(testUser);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void stopUser() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createUserNoFlags();
            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_STARTED, latch, userId);
            mIam.startUserInBackground(userId);
            waitForLatch("Failed to achieve ACTION_USER_STARTED for user " + userId, latch);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            stopUser(userId, false);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void lockedBootCompleted() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int userId = createUserNoFlags();
            final CountDownLatch latch = new CountDownLatch(1);
            registerUserSwitchObserver(null, latch, userId);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            mAm.switchUser(userId);
            waitForLatch("Failed to achieve onLockedBootComplete for user " + userId, latch);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            switchUserNoCheck(startUser);
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void ephemeralUserStopped() throws RemoteException {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int userId = createUserWithFlags(UserInfo.FLAG_EPHEMERAL | UserInfo.FLAG_DEMO);
            switchUser(userId);
            final CountDownLatch latch = new CountDownLatch(1);
            InstrumentationRegistry.getContext().registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_USER_STOPPED.equals(intent.getAction()) && intent.getIntExtra(
                            Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL) == userId) {
                        latch.countDown();
                    }
                }
            }, new IntentFilter(Intent.ACTION_USER_STOPPED));
            final CountDownLatch switchLatch = new CountDownLatch(1);
            registerUserSwitchObserver(switchLatch, null, startUser);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            mAm.switchUser(startUser);
            waitForLatch("Failed to achieve ACTION_USER_STOPPED for user " + userId, latch);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            try {
                switchLatch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Thread interrupted unexpectedly while waiting for switch.", e);
            }
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests creating a new profile. */
    @Test
    public void managedProfileCreate() {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            Log.i(TAG, "Starting timer");
            final int userId = createManagedProfile();

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            attestTrue("Failed creating profile " + userId, mUm.isManagedProfile(userId));
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests starting (unlocking) a newly-created profile. */
    @Test
    public void managedProfileUnlock() {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests starting (unlocking) an already-created, but no-longer-running, profile. */
    @Test
    public void managedProfileUnlock_stopped() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            // Start the profile initially, then stop it. Similar to setQuietModeEnabled.
            startUserInBackgroundAndWaitForUnlock(userId);
            stopUser(userId, true);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            startUserInBackgroundAndWaitForUnlock(userId);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /**
     * Tests starting (unlocking) and launching an already-installed app in a newly-created profile.
     */
    @Test
    public void managedProfileUnlockAndLaunchApp() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /**
     * Tests starting (unlocking) and launching a previously-launched app
     * in an already-created, but no-longer-running, profile.
     * A sort of combination of {@link #managedProfileUnlockAndLaunchApp} and
     * {@link #managedProfileUnlock_stopped}}.
     */
    @Test
    public void managedProfileUnlockAndLaunchApp_stopped() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);
            stopUser(userId, true);
            SystemClock.sleep(1_000); // 1 second cool-down before re-starting profile.
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            startUserInBackgroundAndWaitForUnlock(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests installing a pre-existing app in a newly-created profile. */
    @Test
    public void managedProfileInstall() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /**
     * Tests creating a new profile, starting (unlocking) it, installing an app,
     * and launching that app in it.
     */
    @Test
    public void managedProfileCreateUnlockInstallAndLaunchApp() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            final int userId = createManagedProfile();
            startUserInBackgroundAndWaitForUnlock(userId);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests stopping a profile. */
    @Test
    public void managedProfileStopped() throws RemoteException {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            startUserInBackgroundAndWaitForUnlock(userId);
            Log.i(TAG, "Starting timer");
            mRunner.resumeTiming();

            stopUser(userId, true);

            mRunner.pauseTiming();
            Log.i(TAG, "Stopping timer");
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    // TODO: This is just a POC. Do this properly and add more.
    /** Tests starting (unlocking) a newly-created profile using the user-type-pkg-whitelist. */
    @Test
    public void managedProfileUnlock_usingWhitelist() {
        assumeTrue(mHasManagedUserFeature);
        final int origMode = getUserTypePackageWhitelistMode();
        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_ENFORCE
                | USER_TYPE_PACKAGE_WHITELIST_MODE_IMPLICIT_WHITELIST);

        try {
            while (mRunner.keepRunning()) {
                mRunner.pauseTiming();
                final int userId = createManagedProfile();
                Log.i(TAG, "Starting timer");
                mRunner.resumeTiming();

                startUserInBackgroundAndWaitForUnlock(userId);

                mRunner.pauseTiming();
                Log.i(TAG, "Stopping timer");
                removeUser(userId);
                mRunner.resumeTiming();
            }
        } finally {
            setUserTypePackageWhitelistMode(origMode);
        }
    }
    /** Tests starting (unlocking) a newly-created profile NOT using the user-type-pkg-whitelist. */
    @Test
    public void managedProfileUnlock_notUsingWhitelist() {
        assumeTrue(mHasManagedUserFeature);
        final int origMode = getUserTypePackageWhitelistMode();
        setUserTypePackageWhitelistMode(USER_TYPE_PACKAGE_WHITELIST_MODE_DISABLE);

        try {
            while (mRunner.keepRunning()) {
                mRunner.pauseTiming();
                final int userId = createManagedProfile();
                Log.i(TAG, "Starting timer");
                mRunner.resumeTiming();

                startUserInBackgroundAndWaitForUnlock(userId);

                mRunner.pauseTiming();
                Log.i(TAG, "Stopping timer");
                removeUser(userId);
                mRunner.resumeTiming();
            }
        } finally {
            setUserTypePackageWhitelistMode(origMode);
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
        final ProgressWaiter waiter = new ProgressWaiter();
        boolean success = false;
        try {
            mIam.startUserInBackgroundWithListener(userId, waiter);
            success = waiter.waitForFinish(TIMEOUT_IN_SECOND);
        } catch (RemoteException e) {
            Log.e(TAG, "startUserInBackgroundAndWaitForUnlock failed", e);
        }
        attestTrue("Failed to start user " + userId + " in background.", success);
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
        final CountDownLatch latch = new CountDownLatch(1);
        registerUserSwitchObserver(latch, null, userId);
        mAm.switchUser(userId);
        try {
            return latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Thread interrupted unexpectedly.", e);
            return false;
        }
    }

    private void stopUser(int userId, boolean force) throws RemoteException {
        final CountDownLatch latch = new CountDownLatch(1);
        mIam.stopUser(userId, force /* force */, new IStopUserCallback.Stub() {
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

    /**
     * Creates a user and waits for its ACTION_USER_UNLOCKED.
     * Then switches to back to the original user and waits for its switchUser() to finish.
     *
     * @param stopNewUser whether to stop the new user after switching to otherUser.
     * @return userId of the newly created user.
     */
    private int initializeNewUserAndSwitchBack(boolean stopNewUser) throws RemoteException {
        final int origUser = mAm.getCurrentUser();
        // First, create and switch to testUser, waiting for its ACTION_USER_UNLOCKED
        final int testUser = createUserNoFlags();
        final CountDownLatch latch1 = new CountDownLatch(1);
        registerBroadcastReceiver(Intent.ACTION_USER_UNLOCKED, latch1, testUser);
        mAm.switchUser(testUser);
        waitForLatch("Failed to achieve initial ACTION_USER_UNLOCKED for user " + testUser, latch1);

        // Second, switch back to origUser, waiting merely for switchUser() to finish
        switchUser(origUser);
        attestTrue("Didn't switch back to user, " + origUser, origUser == mAm.getCurrentUser());

        if (stopNewUser) {
            stopUser(testUser, true);
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
    private void startApp(int userId, String packageName) throws RemoteException {
        final Context context = InstrumentationRegistry.getContext();
        final WaitResult result = ActivityTaskManager.getService().startActivityAndWait(null,
                context.getPackageName(), context.getAttributionTag(),
                context.getPackageManager().getLaunchIntentForPackage(packageName), null, null,
                null, 0, 0, null, null, userId);
        attestTrue("User " + userId + " failed to start " + packageName,
                result.result == ActivityManager.START_SUCCESS);
    }

    private void registerUserSwitchObserver(final CountDownLatch switchLatch,
            final CountDownLatch bootCompleteLatch, final int userId) throws RemoteException {
        ActivityManager.getService().registerUserSwitchObserver(
                new UserSwitchObserver() {
                    @Override
                    public void onUserSwitchComplete(int newUserId) throws RemoteException {
                        if (switchLatch != null && userId == newUserId) {
                            switchLatch.countDown();
                        }
                    }

                    @Override
                    public void onLockedBootComplete(int newUserId) {
                        if (bootCompleteLatch != null && userId == newUserId) {
                            bootCompleteLatch.countDown();
                        }
                    }
                }, TAG);
    }

    private void registerBroadcastReceiver(final String action, final CountDownLatch latch,
            final int userId) {
        InstrumentationRegistry.getContext().registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (action.equals(intent.getAction()) && intent.getIntExtra(
                        Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL) == userId) {
                    latch.countDown();
                }
            }
        }, UserHandle.of(userId), new IntentFilter(action), null, null);
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

    /** Gets the PACKAGE_WHITELIST_MODE_PROP System Property. */
    private int getUserTypePackageWhitelistMode() {
        return SystemProperties.getInt(PACKAGE_WHITELIST_MODE_PROP,
                USER_TYPE_PACKAGE_WHITELIST_MODE_DEVICE_DEFAULT);
    }

    /** Sets the PACKAGE_WHITELIST_MODE_PROP System Property to the given value. */
    private void setUserTypePackageWhitelistMode(int mode) {
        String result = ShellHelper.runShellCommand(
                String.format("setprop %s %d", PACKAGE_WHITELIST_MODE_PROP, mode));
        attestFalse("Failed to set sysprop " + PACKAGE_WHITELIST_MODE_PROP + ": " + result,
                result != null && result.contains("Failed"));
    }

    private void removeUser(int userId) {
        try {
            mUm.removeUser(userId);
            final long startTime = System.currentTimeMillis();
            final long timeoutInMs = TIMEOUT_IN_SECOND * 1000;
            while (mUm.getUserInfo(userId) != null &&
                    System.currentTimeMillis() - startTime < timeoutInMs) {
                TimeUnit.MILLISECONDS.sleep(CHECK_USER_REMOVED_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Ignore
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

    private void attestTrue(@NonNull String message, boolean assertion) {
        if (!assertion) {
            Log.e(TAG, "Test failed on iteration #" + mRunner.getIteration() + ": " + message);
            mRunner.markAsFailed(new AssertionError(message));
        }
    }

    private void attestFalse(@NonNull String message, boolean assertion) {
        attestTrue(message, !assertion);
    }
}
