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

package com.android.server.notification;

import static android.app.role.RoleManager.ROLE_BROWSER;
import static android.app.role.RoleManager.ROLE_DIALER;
import static android.app.role.RoleManager.ROLE_EMERGENCY;
import static android.content.pm.PackageManager.MATCH_ALL;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Arrays.asList;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IUriGrantsManager;
import android.app.StatsManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.role.RoleManager;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.ICompanionDeviceManager;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionManager;
import android.telecom.TelecomManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.internal.config.sysui.TestableFlagResolver;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.InstanceIdSequenceFake;
import com.android.server.LocalServices;
import com.android.server.UiServiceTestCase;
import com.android.server.lights.LightsManager;
import com.android.server.notification.NotificationManagerService.NotificationAssistants;
import com.android.server.notification.NotificationManagerService.NotificationListeners;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.quota.MultiRateLimiter;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
// TODO (b/194833441): remove when notification permission is enabled
public class RoleObserverTest extends UiServiceTestCase {
    private TestableNotificationManagerService mService;
    private NotificationManagerService.RoleObserver mRoleObserver;

    @Mock
    private PreferencesHelper mPreferencesHelper;
    @Mock
    private IPackageManager mPm;
    @Mock
    private UserManager mUm;
    @Mock
    private RoleManager mRoleManager;
    @Mock
    private Looper mMainLooper;
    private TestableLooper mTestableLooper;
    NotificationRecordLoggerFake mNotificationRecordLogger = new NotificationRecordLoggerFake();
    private InstanceIdSequence mNotificationInstanceIdSequence = new InstanceIdSequenceFake(
            1 << 30);
    private List<UserHandle> mUsers;

    private static class TestableNotificationManagerService extends NotificationManagerService {
        TestableNotificationManagerService(Context context,
                NotificationRecordLogger logger,
                InstanceIdSequence notificationInstanceIdSequence) {
            super(context, logger, notificationInstanceIdSequence);
        }

        @Override
        protected void handleSavePolicyFile() { }

        @Override
        protected void loadPolicyFile() { }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // Shell permisssions will override permissions of our app, so add all necessary permissions
        // for this test here:
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                "android.permission.READ_CONTACTS");

        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mock(WindowManagerInternal.class));
        mContext.addMockSystemService(AppOpsManager.class, mock(AppOpsManager.class));

        mUsers = new ArrayList<>();
        mUsers.add(new UserHandle(0));
        mUsers.add(new UserHandle(10));
        when(mUm.getUserHandles(anyBoolean())).thenReturn(mUsers);

        when(mMainLooper.isCurrentThread()).thenReturn(true);

        mService = new TestableNotificationManagerService(mContext, mNotificationRecordLogger,
                mNotificationInstanceIdSequence);
        mRoleObserver = mService.new RoleObserver(mContext, mRoleManager, mPm, mMainLooper);
        mTestableLooper = TestableLooper.get(this);

        try {
            mService.init(mService.new WorkerHandler(mTestableLooper.getLooper()),
                    mock(RankingHandler.class), new Handler(mTestableLooper.getLooper()),
                    mock(IPackageManager.class), mock(PackageManager.class),
                    mock(LightsManager.class),
                    mock(NotificationListeners.class), mock(NotificationAssistants.class),
                    mock(ConditionProviders.class), mock(ICompanionDeviceManager.class),
                    mock(SnoozeHelper.class), mock(NotificationUsageStats.class),
                    mock(AtomicFile.class), mock(ActivityManager.class),
                    mock(GroupHelper.class), mock(IActivityManager.class),
                    mock(ActivityTaskManagerInternal.class),
                    mock(UsageStatsManagerInternal.class),
                    mock(DevicePolicyManagerInternal.class), mock(IUriGrantsManager.class),
                    mock(UriGrantsManagerInternal.class),
                    mock(AppOpsManager.class), mUm, mock(NotificationHistoryManager.class),
                    mock(StatsManager.class),
                    mock(ActivityManagerInternal.class),
                    mock(MultiRateLimiter.class), mock(PermissionHelper.class),
                    mock(UsageStatsManagerInternal.class), mock(TelecomManager.class),
                    mock(NotificationChannelLogger.class), new TestableFlagResolver(),
                    mock(PermissionManager.class),
                    mock(PowerManager.class),
                    new NotificationManagerService.PostNotificationTrackerFactory() {});
        } catch (SecurityException e) {
            if (!e.getMessage().contains("Permission Denial: not allowed to send broadcast")) {
                throw e;
            }
        }
        mService.setPreferencesHelper(mPreferencesHelper);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testInit_forNonBlockableDefaultApps() throws Exception {
        List<String> dialer0 = new ArrayList<>();
        dialer0.add("dialer");
        List<String> emer0 = new ArrayList<>();
        emer0.add("emergency");

        ArraySet<Pair<String, Integer>> dialer0Pair = new ArraySet<>();
        dialer0Pair.add(new Pair("dialer", 30));
        when(mPm.getPackageUid("dialer", MATCH_ALL, 0)).thenReturn(30);

        ArraySet<Pair<String, Integer>> emer0Pair = new ArraySet<>();
        emer0Pair.add(new Pair("emergency", 40));
        when(mPm.getPackageUid("emergency", MATCH_ALL, 0)).thenReturn(40);


        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0)))
                .thenReturn(dialer0);
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_EMERGENCY,
                mUsers.get(0)))
                .thenReturn(emer0);

        mRoleObserver.init();

        // verify internal records of current state of the world
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_DIALER, dialer0.get(0), mUsers.get(0).getIdentifier()));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_DIALER, dialer0.get(0), mUsers.get(1).getIdentifier()));

        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_EMERGENCY, emer0.get(0), mUsers.get(0).getIdentifier()));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_EMERGENCY, emer0.get(0), mUsers.get(1).getIdentifier()));

        // make sure we're listening to updates
        verify(mRoleManager, times(1)).addOnRoleHoldersChangedListenerAsUser(
                any(), any(), eq(UserHandle.ALL));

        // make sure we told pref helper about the state of the world
        verify(mPreferencesHelper, times(1)).updateDefaultApps(0, null, dialer0Pair);
        verify(mPreferencesHelper, times(1)).updateDefaultApps(0, null, emer0Pair);
    }

    @Test
    public void testInit_forTrampolines() throws Exception {
        when(mPm.getPackageUid("com.browser", MATCH_ALL, 0)).thenReturn(30);
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_BROWSER,
                mUsers.get(0)))
                .thenReturn(asList("com.browser"));

        mRoleObserver.init();

        assertTrue(mRoleObserver.isUidExemptFromTrampolineRestrictions(30));

        // make sure we're listening to updates
        verify(mRoleManager, times(1)).addOnRoleHoldersChangedListenerAsUser(any(), any(),
                eq(UserHandle.ALL));
    }

    @Test
    public void testSwapDefault_forNonBlockableDefaultApps() throws Exception {
        List<String> dialer0 = new ArrayList<>();
        dialer0.add("dialer");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0)))
                .thenReturn(dialer0);

        mRoleObserver.init();

        List<String> newDefault = new ArrayList<>();
        newDefault.add("phone");

        ArraySet<Pair<String, Integer>> newDefaultPair = new ArraySet<>();
        newDefaultPair.add(new Pair("phone", 30));
        when(mPm.getPackageUid("phone", MATCH_ALL, 0)).thenReturn(30);

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0)))
                .thenReturn(newDefault);

        mRoleObserver.onRoleHoldersChanged(ROLE_DIALER, UserHandle.of(0));

        verify(mPreferencesHelper, times(1)).updateDefaultApps(
                0, new ArraySet<>(dialer0), newDefaultPair);
    }

    @Test
    public void testSwapDefault_forTrampolines() throws Exception {
        List<String> dialer0 = new ArrayList<>();
        when(mPm.getPackageUid("com.browser", MATCH_ALL, 0)).thenReturn(30);
        when(mPm.getPackageUid("com.browser2", MATCH_ALL, 0)).thenReturn(31);
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_BROWSER,
                mUsers.get(0)))
                .thenReturn(asList("com.browser"));
        mRoleObserver.init();
        assertTrue(mRoleObserver.isUidExemptFromTrampolineRestrictions(30));
        assertFalse(mRoleObserver.isUidExemptFromTrampolineRestrictions(31));
        // Default changed
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_BROWSER,
                mUsers.get(0)))
                .thenReturn(asList("com.browser2"));
        mRoleObserver.onRoleHoldersChanged(ROLE_BROWSER, UserHandle.of(0));

        assertFalse(mRoleObserver.isUidExemptFromTrampolineRestrictions(30));
        assertTrue(mRoleObserver.isUidExemptFromTrampolineRestrictions(31));
    }

    @Test
    public void testSwapDefault_multipleOverlappingApps_forNonBlockableDefaultApps()
            throws Exception {
        List<String> dialer0 = new ArrayList<>();
        dialer0.add("dialer");
        dialer0.add("phone");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0)))
                .thenReturn(dialer0);

        mRoleObserver.init();

        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "phone", 0));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "emerPhone", 0));
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "dialer", 0));

        List<String> newDefault = new ArrayList<>();
        newDefault.add("phone");
        newDefault.add("emerPhone");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0)))
                .thenReturn(newDefault);

        ArraySet<String> expectedRemove = new ArraySet<>();
        expectedRemove.add("dialer");

        ArraySet<Pair<String, Integer>> expectedAddPair = new ArraySet<>();
        expectedAddPair.add(new Pair("emerPhone", 30));
        when(mPm.getPackageUid("emerPhone", MATCH_ALL, 0)).thenReturn(30);

        mRoleObserver.onRoleHoldersChanged(ROLE_DIALER, UserHandle.of(0));

        verify(mPreferencesHelper, times(1)).updateDefaultApps(
                0, expectedRemove, expectedAddPair);

        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "phone", 0));
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "emerPhone", 0));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "dialer", 0));
    }

    @Test
    public void testSwapDefault_newUser_forNonBlockableDefaultApps() throws Exception {
        List<String> dialer0 = new ArrayList<>();
        dialer0.add("dialer");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0)))
                .thenReturn(dialer0);

        mRoleObserver.init();

        List<String> dialer10 = new ArrayList<>();
        dialer10.add("phone");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(1)))
                .thenReturn(dialer10);

        ArraySet<Pair<String, Integer>> expectedAddPair = new ArraySet<>();
        expectedAddPair.add(new Pair("phone", 30));
        when(mPm.getPackageUid("phone", MATCH_ALL, 10)).thenReturn(30);

        mRoleObserver.onRoleHoldersChanged(ROLE_DIALER, UserHandle.of(10));

        ArraySet<String> expectedRemove = new ArraySet<>();
        ArraySet<String> expectedAdd = new ArraySet<>();
        expectedAdd.add("phone");

        verify(mPreferencesHelper, times(1)).updateDefaultApps(
                10, expectedRemove, expectedAddPair);

        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "phone", 10));
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "dialer", 0));
    }

    @Test
    public void testSwapDefault_newUser_forTrampolines() throws Exception {
        List<String> dialer0 = new ArrayList<>();
        when(mPm.getPackageUid("com.browser", MATCH_ALL, 0)).thenReturn(30);
        when(mPm.getPackageUid("com.browser2", MATCH_ALL, 10)).thenReturn(1031);
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_BROWSER,
                mUsers.get(0)))
                .thenReturn(asList("com.browser"));
        mRoleObserver.init();
        assertTrue(mRoleObserver.isUidExemptFromTrampolineRestrictions(30));
        assertFalse(mRoleObserver.isUidExemptFromTrampolineRestrictions(1031));
        // New user
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_BROWSER,
                mUsers.get(1)))
                .thenReturn(asList("com.browser2"));
        mRoleObserver.onRoleHoldersChanged(ROLE_BROWSER, UserHandle.of(10));

        assertTrue(mRoleObserver.isUidExemptFromTrampolineRestrictions(30));
        assertTrue(mRoleObserver.isUidExemptFromTrampolineRestrictions(1031));
    }
}
