/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;

import static android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED;
import static android.app.usage.UsageEvents.Event.APP_COMPONENT_USED;
import static android.app.usage.UsageEvents.Event.USER_INTERACTION;
import static android.content.pm.PackageManager.MATCH_ANY_USER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.returnsArgAt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.IActivityManager;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStatsManagerInternal;
import android.app.usage.UsageStatsManagerInternal.UsageEventListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Tests for {@link com.android.server.apphibernation.AppHibernationService}
 */
@SmallTest
@Presubmit
public final class AppHibernationServiceTest {
    private static final String PACKAGE_SCHEME = "package";
    private static final String PACKAGE_NAME_1 = "package1";
    private static final String PACKAGE_NAME_2 = "package2";
    private static final String PACKAGE_NAME_3 = "package3";
    private static final int USER_ID_1 = 1;
    private static final int USER_ID_2 = 2;

    private final List<UserInfo> mUserInfos = new ArrayList<>();

    private AppHibernationService mAppHibernationService;
    private BroadcastReceiver mBroadcastReceiver;
    private UsageEventListener mUsageEventListener;

    @Mock
    private Context mContext;
    @Mock
    private IPackageManager mIPackageManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private IActivityManager mIActivityManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private HibernationStateDiskStore<UserLevelState> mUserLevelDiskStore;
    @Mock
    private UsageStatsManagerInternal mUsageStatsManagerInternal;
    @Mock
    private HibernationStateDiskStore<UserLevelState> mHibernationStateDiskStore;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mReceiverCaptor;
    @Captor
    private ArgumentCaptor<UsageEventListener> mUsageEventListenerCaptor;

    @Before
    public void setUp() throws RemoteException {
        // Share class loader to allow access to package-private classes
        System.setProperty("dexmaker.share_classloader", "true");
        MockitoAnnotations.initMocks(this);
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());

        LocalServices.removeServiceForTest(AppHibernationManagerInternal.class);
        mAppHibernationService = new AppHibernationService(new MockInjector(mContext));

        verify(mContext).registerReceiver(mReceiverCaptor.capture(), any());
        mBroadcastReceiver = mReceiverCaptor.getValue();
        verify(mUsageStatsManagerInternal).registerListener(mUsageEventListenerCaptor.capture());
        mUsageEventListener = mUsageEventListenerCaptor.getValue();

        doReturn(mUserInfos).when(mUserManager).getUsers();

        doAnswer(returnsArgAt(2)).when(mIActivityManager).handleIncomingUser(anyInt(), anyInt(),
                anyInt(), anyBoolean(), anyBoolean(), any(), any());

        List<PackageInfo> packages = new ArrayList<>();
        packages.add(makePackageInfo(PACKAGE_NAME_1));
        packages.add(makePackageInfo(PACKAGE_NAME_2));
        packages.add(makePackageInfo(PACKAGE_NAME_3));
        doReturn(new ParceledListSlice<>(packages)).when(mIPackageManager).getInstalledPackages(
                intThat(arg -> (arg & MATCH_ANY_USER) != 0), anyInt());
        mAppHibernationService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        UserInfo userInfo = addUser(USER_ID_1);
        doReturn(true).when(mUserManager).isUserUnlockingOrUnlocked(USER_ID_1);
        mAppHibernationService.onUserUnlocking(new SystemService.TargetUser(userInfo));

        mAppHibernationService.sIsServiceEnabled = true;
    }

    @Test
    public void testSetHibernatingForUser_packageIsHibernating() {
        // WHEN we hibernate a package for a user
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, true);

        // THEN the package is marked hibernating for the user
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_1));
    }

    @Test
    public void testSetHibernatingForUser_newPackageAdded_packageIsHibernating() {
        // WHEN a new package is added and it is hibernated
        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED,
                Uri.fromParts(PACKAGE_SCHEME, PACKAGE_NAME_2, null /* fragment */));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, USER_ID_1);
        mBroadcastReceiver.onReceive(mContext, intent);

        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_2, USER_ID_1, true);

        // THEN the new package is hibernated
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_2, USER_ID_1));
    }

    @Test
    public void testSetHibernatingForUser_newUserUnlocked_packageIsHibernating()
            throws RemoteException {
        // WHEN a new user is added and a package from the user is hibernated
        UserInfo user2 = addUser(USER_ID_2);
        doReturn(true).when(mUserManager).isUserUnlockingOrUnlocked(USER_ID_2);
        mAppHibernationService.onUserUnlocking(new SystemService.TargetUser(user2));
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_2, true);

        // THEN the new user's package is hibernated
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_2));
    }

    @Test
    public void testIsHibernatingForUser_packageReplaced_stillReturnsHibernating() {
        // GIVEN a package is currently hibernated
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, true);

        // WHEN the package is removed but marked as replacing
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED,
                Uri.fromParts(PACKAGE_SCHEME, PACKAGE_NAME_2, null /* fragment */));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, USER_ID_1);
        intent.putExtra(Intent.EXTRA_REPLACING, true);
        mBroadcastReceiver.onReceive(mContext, intent);

        // THEN the package is still hibernating
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_1));
    }

    @Test
    public void testSetHibernatingGlobally_packageIsHibernatingGlobally() throws RemoteException {
        // WHEN we hibernate a package
        mAppHibernationService.setHibernatingGlobally(PACKAGE_NAME_1, true);

        // THEN the package is marked hibernating for the user
        assertTrue(mAppHibernationService.isHibernatingGlobally(PACKAGE_NAME_1));
    }

    @Test
    public void testGetHibernatingPackagesForUser_returnsCorrectPackages() throws RemoteException {
        // GIVEN an unlocked user with all packages installed
        UserInfo userInfo =
                addUser(USER_ID_2, new String[]{PACKAGE_NAME_1, PACKAGE_NAME_2, PACKAGE_NAME_3});
        doReturn(true).when(mUserManager).isUserUnlockingOrUnlocked(USER_ID_2);
        mAppHibernationService.onUserUnlocking(new SystemService.TargetUser(userInfo));

        // WHEN packages are hibernated for the user
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_2, true);
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_2, USER_ID_2, true);

        // THEN the hibernating packages returned matches
        List<String> hibernatingPackages =
                mAppHibernationService.getHibernatingPackagesForUser(USER_ID_2);
        assertEquals(2, hibernatingPackages.size());
        assertTrue(hibernatingPackages.contains(PACKAGE_NAME_1));
        assertTrue(hibernatingPackages.contains(PACKAGE_NAME_2));
    }

    @Test
    public void testUserLevelStatesInitializedFromDisk() throws RemoteException {
        // GIVEN states stored on disk that match with package manager's force-stop states
        List<UserLevelState> diskStates = new ArrayList<>();
        diskStates.add(makeUserLevelState(PACKAGE_NAME_1, false /* hibernated */));
        diskStates.add(makeUserLevelState(PACKAGE_NAME_2, true /* hibernated */));
        doReturn(diskStates).when(mUserLevelDiskStore).readHibernationStates();

        List<PackageInfo> packageInfos = new ArrayList<>();
        packageInfos.add(makePackageInfo(PACKAGE_NAME_1));
        PackageInfo stoppedPkg = makePackageInfo(PACKAGE_NAME_2);
        stoppedPkg.applicationInfo.flags |= ApplicationInfo.FLAG_STOPPED;
        packageInfos.add(stoppedPkg);

        // WHEN a user is unlocked and the states are initialized
        UserInfo user2 = addUser(USER_ID_2, packageInfos);
        doReturn(true).when(mUserManager).isUserUnlockingOrUnlocked(USER_ID_2);
        mAppHibernationService.onUserUnlocking(new SystemService.TargetUser(user2));

        // THEN the hibernation states are initialized to the disk states
        assertFalse(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_2));
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_2, USER_ID_2));
    }

    @Test
    public void testNonForceStoppedAppsNotHibernatedOnUnlock() throws RemoteException {
        // GIVEN a package that is hibernated on disk but not force-stopped
        List<UserLevelState> diskStates = new ArrayList<>();
        diskStates.add(makeUserLevelState(PACKAGE_NAME_1, true /* hibernated */));
        doReturn(diskStates).when(mUserLevelDiskStore).readHibernationStates();

        // WHEN a user is unlocked and the states are initialized
        UserInfo user2 = addUser(USER_ID_2, new String[]{PACKAGE_NAME_1});
        doReturn(true).when(mUserManager).isUserUnlockingOrUnlocked(USER_ID_2);
        mAppHibernationService.onUserUnlocking(new SystemService.TargetUser(user2));

        // THEN the app is not hibernating for the user
        assertFalse(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_2));
    }

    @Test
    public void testUnhibernatedPackageForUserUnhibernatesPackageGloballyOnUnlock()
            throws RemoteException {
        // GIVEN a package that is globally hibernating
        mAppHibernationService.setHibernatingGlobally(PACKAGE_NAME_1, true);

        // WHEN a user is unlocked and the package is not hibernating for the user
        UserInfo user2 = addUser(USER_ID_2);
        doReturn(true).when(mUserManager).isUserUnlockingOrUnlocked(USER_ID_2);
        mAppHibernationService.onUserUnlocking(new SystemService.TargetUser(user2));

        // THEN the package is no longer globally hibernating
        assertFalse(mAppHibernationService.isHibernatingGlobally(PACKAGE_NAME_1));
    }

    @Test
    public void testUnhibernatingPackageForUserSendsBootCompleteBroadcast()
            throws RemoteException {
        // GIVEN a hibernating package for a user
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, true);

        // WHEN we unhibernate the package
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, false);

        // THEN we send the boot complete broadcasts
        ArgumentCaptor<Intent> intentArgumentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mIActivityManager, times(2)).broadcastIntentWithFeature(any(), any(),
                intentArgumentCaptor.capture(), any(), any(), anyInt(), any(), any(), any(), any(),
                anyInt(), any(), anyBoolean(), anyBoolean(), eq(USER_ID_1));
        List<Intent> capturedIntents = intentArgumentCaptor.getAllValues();
        assertEquals(capturedIntents.get(0).getAction(), Intent.ACTION_LOCKED_BOOT_COMPLETED);
        assertEquals(capturedIntents.get(1).getAction(), Intent.ACTION_BOOT_COMPLETED);
    }

    @Test
    public void testHibernatingPackageIsUnhibernatedForUserWhenUserInteracted() {
        // GIVEN a package that is currently hibernated for a user
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, true);

        // WHEN the package is interacted with by user
        generateUsageEvent(USER_INTERACTION);

        // THEN the package is not hibernating anymore
        assertFalse(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_1));
    }

    @Test
    public void testHibernatingPackageIsUnhibernatedForUserWhenActivityResumed() {
        // GIVEN a package that is currently hibernated for a user
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, true);

        // WHEN the package has activity resumed
        generateUsageEvent(ACTIVITY_RESUMED);

        // THEN the package is not hibernating anymore
        assertFalse(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_1));
    }

    @Test
    public void testHibernatingPackageIsUnhibernatedForUserWhenComponentUsed() {
        // GIVEN a package that is currently hibernated for a user
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, true);

        // WHEN a package component is used
        generateUsageEvent(APP_COMPONENT_USED);

        // THEN the package is not hibernating anymore
        assertFalse(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_1));
    }

    @Test
    public void testHibernatingPackageIsUnhibernatedGloballyWhenUserInteracted() {
        // GIVEN a package that is currently hibernated globally
        mAppHibernationService.setHibernatingGlobally(PACKAGE_NAME_1, true);

        // WHEN the user interacts with the package
        generateUsageEvent(USER_INTERACTION);

        // THEN the package is not hibernating globally anymore
        assertFalse(mAppHibernationService.isHibernatingGlobally(PACKAGE_NAME_1));
    }

    @Test
    public void testHibernatingPackageIsUnhibernatedGloballyWhenActivityResumed() {
        // GIVEN a package that is currently hibernated globally
        mAppHibernationService.setHibernatingGlobally(PACKAGE_NAME_1, true);

        // WHEN activity in package resumed
        generateUsageEvent(ACTIVITY_RESUMED);

        // THEN the package is not hibernating globally anymore
        assertFalse(mAppHibernationService.isHibernatingGlobally(PACKAGE_NAME_1));
    }

    @Test
    public void testHibernatingPackageIsUnhibernatedGloballyWhenComponentUsed() {
        // GIVEN a package that is currently hibernated globally
        mAppHibernationService.setHibernatingGlobally(PACKAGE_NAME_1, true);

        // WHEN a package component is used
        generateUsageEvent(APP_COMPONENT_USED);

        // THEN the package is not hibernating globally anymore
        assertFalse(mAppHibernationService.isHibernatingGlobally(PACKAGE_NAME_1));
    }

    /**
     * Mock a usage event occurring.
     *
     * @param usageEventId id of a usage event
     */
    private void generateUsageEvent(int usageEventId) {
        Event event = new Event(usageEventId, 0 /* timestamp */);
        event.mPackage = PACKAGE_NAME_1;
        mUsageEventListener.onUsageEvent(USER_ID_1, event);
    }

    /**
     * Add a mock user with one package.
     */
    private UserInfo addUser(int userId) throws RemoteException {
        return addUser(userId, new String[]{PACKAGE_NAME_1});
    }

    /**
     * Add a mock user with the packages specified.
     */
    private UserInfo addUser(int userId, String[] packageNames) throws RemoteException {
        List<PackageInfo> userPackages = new ArrayList<>();
        for (String pkgName : packageNames) {
            userPackages.add(makePackageInfo(pkgName));
        }
        return addUser(userId, userPackages);
    }

    /**
     * Add a mock user with the package infos specified.
     */
    private UserInfo addUser(int userId, List<PackageInfo> userPackages) throws RemoteException {
        UserInfo userInfo = new UserInfo(userId, "user_" + userId, 0 /* flags */);
        mUserInfos.add(userInfo);
        doReturn(new ParceledListSlice<>(userPackages)).when(mIPackageManager)
                .getInstalledPackages(intThat(arg -> (arg & MATCH_ANY_USER) == 0), eq(userId));
        return userInfo;
    }

    private static PackageInfo makePackageInfo(String packageName) {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = packageName;
        pkg.applicationInfo = new ApplicationInfo();
        return pkg;
    }

    private static UserLevelState makeUserLevelState(String packageName, boolean hibernated) {
        UserLevelState state = new UserLevelState();
        state.packageName = packageName;
        state.hibernated = hibernated;
        return state;
    }

    private class MockInjector implements AppHibernationService.Injector {
        private final Context mContext;

        MockInjector(Context context) {
            mContext = context;
        }

        @Override
        public IActivityManager getActivityManager() {
            return mIActivityManager;
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public IPackageManager getPackageManager() {
            return mIPackageManager;
        }

        @Override
        public PackageManagerInternal getPackageManagerInternal() {
            return mPackageManagerInternal;
        }

        @Override
        public UserManager getUserManager() {
            return mUserManager;
        }

        @Override
        public UsageStatsManagerInternal getUsageStatsManagerInternal() {
            return mUsageStatsManagerInternal;
        }

        @Override
        public Executor getBackgroundExecutor() {
            // Just execute immediately in tests.
            return r -> r.run();
        }

        @Override
        public HibernationStateDiskStore<GlobalLevelState> getGlobalLevelDiskStore() {
            return mock(HibernationStateDiskStore.class);
        }

        @Override
        public HibernationStateDiskStore<UserLevelState> getUserLevelDiskStore(int userId) {
            return mUserLevelDiskStore;
        }

        @Override
        public boolean isOatArtifactDeletionEnabled() {
            return true;
        }
    }
}
