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
import android.os.UserHandle;
import android.os.UserManager;
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
 * Running the tests:
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

    private static final String DUMMY_PACKAGE_NAME = "perftests.multiuser.apps.dummyapp";

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
            final int userId = createUserNoFlags();

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void createAndStartUser() throws Exception {
        while (mRunner.keepRunning()) {
            final int userId = createUserNoFlags();

            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_STARTED, latch, userId);
            // Don't use this.startUserInBackground() since only waiting until ACTION_USER_STARTED.
            mIam.startUserInBackground(userId);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void switchUser() throws Exception {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int userId = createUserNoFlags();
            mRunner.resumeTiming();

            switchUser(userId);

            mRunner.pauseTiming();
            switchUser(startUser);
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests switching to an already-created, but no-longer-running, user. */
    @Test
    public void switchUser_stopped() throws Exception {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ true);
            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_UNLOCKED, latch, testUser);
            mRunner.resumeTiming();

            mAm.switchUser(testUser);
            boolean success = latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);

            mRunner.pauseTiming();
            attestTrue("Failed to achieve 2nd ACTION_USER_UNLOCKED for user " + testUser, success);
            switchUser(startUser);
            removeUser(testUser);
            mRunner.resumeTiming();
        }
    }

    /** Tests switching to an already-created already-running non-owner user. */
    @Test
    public void switchUser_running() throws Exception {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int testUser = initializeNewUserAndSwitchBack(/* stopNewUser */ false);
            mRunner.resumeTiming();

            switchUser(testUser);

            mRunner.pauseTiming();
            attestTrue("Failed to switch to user " + testUser, mAm.isUserRunning(testUser));
            switchUser(startUser);
            removeUser(testUser);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void stopUser() throws Exception {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createUserNoFlags();
            final CountDownLatch latch = new CountDownLatch(1);
            registerBroadcastReceiver(Intent.ACTION_USER_STARTED, latch, userId);
            mIam.startUserInBackground(userId);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
            mRunner.resumeTiming();

            stopUser(userId, false);

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void lockedBootCompleted() throws Exception {
        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int startUser = mAm.getCurrentUser();
            final int userId = createUserNoFlags();
            final CountDownLatch latch = new CountDownLatch(1);
            registerUserSwitchObserver(null, latch, userId);
            mRunner.resumeTiming();

            mAm.switchUser(userId);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);

            mRunner.pauseTiming();
            switchUser(startUser);
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    @Test
    public void ephemeralUserStopped() throws Exception {
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
            mRunner.resumeTiming();

            mAm.switchUser(startUser);
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);

            mRunner.pauseTiming();
            switchLatch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests creating a new profile. */
    @Test
    public void managedProfileCreate() throws Exception {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            final int userId = createManagedProfile();

            mRunner.pauseTiming();
            attestTrue("Failed creating profile " + userId, mUm.isManagedProfile(userId));
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests starting (unlocking) a newly-created profile. */
    @Test
    public void managedProfileUnlock() throws Exception {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            mRunner.resumeTiming();

            startUserInBackground(userId);

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests starting (unlocking) an already-created, but no-longer-running, profile. */
    @Test
    public void managedProfileUnlock_stopped() throws Exception {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            // Start the profile initially, then stop it. Similar to setQuietModeEnabled.
            startUserInBackground(userId);
            stopUser(userId, true);
            mRunner.resumeTiming();

            startUserInBackground(userId);

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /**
     * Tests starting (unlocking) and launching an already-installed app in a newly-created profile.
     */
    @Test
    public void managedProfileUnlockAndLaunchApp() throws Exception {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            mRunner.resumeTiming();

            startUserInBackground(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
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
    public void managedProfileUnlockAndLaunchApp_stopped() throws Exception {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            startUserInBackground(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);
            stopUser(userId, true);
            TimeUnit.SECONDS.sleep(1); // Brief cool-down before re-starting profile.
            mRunner.resumeTiming();

            startUserInBackground(userId);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests installing a pre-existing app in a newly-created profile. */
    @Test
    public void managedProfileInstall() throws Exception {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            mRunner.resumeTiming();

            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /**
     * Tests creating a new profile, starting (unlocking) it, installing an app,
     * and launching that app in it.
     */
    @Test
    public void managedProfileCreateUnlockInstallAndLaunchApp() throws Exception {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
            mRunner.resumeTiming();

            final int userId = createManagedProfile();
            startUserInBackground(userId);
            installPreexistingApp(userId, DUMMY_PACKAGE_NAME);
            startApp(userId, DUMMY_PACKAGE_NAME);

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Tests stopping a profile. */
    @Test
    public void managedProfileStopped() throws Exception {
        assumeTrue(mHasManagedUserFeature);

        while (mRunner.keepRunning()) {
            mRunner.pauseTiming();
            final int userId = createManagedProfile();
            startUserInBackground(userId);
            mRunner.resumeTiming();

            stopUser(userId, true);

            mRunner.pauseTiming();
            removeUser(userId);
            mRunner.resumeTiming();
        }
    }

    /** Creates a new user, returning its userId. */
    private int createUserNoFlags() {
        return createUserWithFlags(/* flags= */ 0);
    }

    /** Creates a new user with the given flags, returning its userId. */
    private int createUserWithFlags(int flags) {
        int userId = mUm.createUser("TestUser", flags).id;
        mUsersToRemove.add(userId);
        return userId;
    }

    /** Creates a managed (work) profile under the current user, returning its userId. */
    private int createManagedProfile() {
        final UserInfo userInfo = mUm.createProfileForUser("TestProfile",
                UserInfo.FLAG_MANAGED_PROFILE, mAm.getCurrentUser());
        mUsersToRemove.add(userInfo.id);
        return userInfo.id;
    }

    /**
     * Start user in background and wait for it to unlock (equivalent to ACTION_USER_UNLOCKED).
     * To start in foreground instead, see {@link #switchUser(int)}.
     * This should always be used for profiles since profiles cannot be started in foreground.
     */
    private void startUserInBackground(int userId) {
        final ProgressWaiter waiter = new ProgressWaiter();
        try {
            mIam.startUserInBackgroundWithListener(userId, waiter);
            boolean success = waiter.waitForFinish(TIMEOUT_IN_SECOND);
            attestTrue("Failed to start user " + userId + " in background.", success);
        } catch (RemoteException e) {
            Log.e(TAG, "startUserInBackground failed", e);
        }
    }

    /** Starts the given user in the foreground. */
    private void switchUser(int userId) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        registerUserSwitchObserver(latch, null, userId);
        mAm.switchUser(userId);
        latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
    }

    private void stopUser(int userId, boolean force) throws Exception {
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
        latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
    }

    /**
     * Creates a user and waits for its ACTION_USER_UNLOCKED.
     * Then switches to back to the original user and waits for its switchUser() to finish.
     *
     * @param stopNewUser whether to stop the new user after switching to otherUser.
     * @return userId of the newly created user.
     */
    private int initializeNewUserAndSwitchBack(boolean stopNewUser) throws Exception {
        final int origUser = mAm.getCurrentUser();
        // First, create and switch to testUser, waiting for its ACTION_USER_UNLOCKED
        final int testUser = createUserNoFlags();
        final CountDownLatch latch1 = new CountDownLatch(1);
        registerBroadcastReceiver(Intent.ACTION_USER_UNLOCKED, latch1, testUser);
        mAm.switchUser(testUser);
        attestTrue("Failed to achieve initial ACTION_USER_UNLOCKED for user " + testUser,
                latch1.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS));

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

        try {
            latch.await(TIMEOUT_IN_SECOND, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Thread interrupted unexpectedly.", e);
        }
    }

    /**
     * Launches the given package in the given user.
     * Make sure the keyguard has been dismissed prior to calling.
     */
    private void startApp(int userId, String packageName) throws RemoteException {
        final Context context = InstrumentationRegistry.getContext();
        final WaitResult result = ActivityTaskManager.getService().startActivityAndWait(null,
                context.getPackageName(),
                context.getPackageManager().getLaunchIntentForPackage(packageName), null, null,
                null, 0, 0, null, null, userId);
        attestTrue("User " + userId + " failed to start " + packageName,
                result.result == ActivityManager.START_SUCCESS);
    }

    private void registerUserSwitchObserver(final CountDownLatch switchLatch,
            final CountDownLatch bootCompleteLatch, final int userId) throws Exception {
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

    private void attestTrue(String message, boolean assertion) {
        if (!assertion) {
            Log.w(TAG, message);
        }
    }

    private void attestFalse(String message, boolean assertion) {
        attestTrue(message, !assertion);
    }
}
