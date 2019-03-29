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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationManager.EXTRA_BLOCKED_STATE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MAX;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.app.role.RoleManager.ROLE_DIALER;
import static android.app.role.RoleManager.ROLE_EMERGENCY;
import static android.app.role.RoleManager.ROLE_SMS;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Build.VERSION_CODES.O_MR1;
import static android.os.Build.VERSION_CODES.P;
import static android.service.notification.Adjustment.KEY_IMPORTANCE;
import static android.service.notification.Adjustment.KEY_USER_SENTIMENT;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.IUriGrantsManager;
import android.app.Notification;
import android.app.Notification.MessagingStyle.Message;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.role.RoleManager;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.ICompanionDeviceManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;
import android.service.notification.NotifyingApp;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.TestablePermissions;
import android.text.Html;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;

import com.android.internal.R;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.server.LocalServices;
import com.android.server.UiServiceTestCase;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.notification.NotificationManagerService.NotificationAssistants;
import com.android.server.notification.NotificationManagerService.NotificationListeners;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class RoleObserverTest extends UiServiceTestCase {
    private TestableNotificationManagerService mService;
    private NotificationManagerService.RoleObserver mRoleObserver;

    private TestableContext mContext = spy(getContext());

    @Mock
    private PreferencesHelper mPreferencesHelper;
    @Mock
    private UserManager mUm;
    @Mock
    private Executor mExecutor;
    @Mock
    private RoleManager mRoleManager;

    private List<UserInfo> mUsers;

    private static class TestableNotificationManagerService extends NotificationManagerService {

        TestableNotificationManagerService(Context context) {
            super(context);
        }

        @Override
        protected void handleSavePolicyFile() {
            return;
        }

        @Override
        protected void loadPolicyFile() {
            return;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mock(WindowManagerInternal.class));

        mUsers = new ArrayList<>();
        mUsers.add(new UserInfo(0, "system", 0));
        mUsers.add(new UserInfo(10, "second", 0));
        when(mUm.getUsers()).thenReturn(mUsers);

        mService = new TestableNotificationManagerService(mContext);
        mRoleObserver = mService.new RoleObserver(mRoleManager, mExecutor);

        try {
            mService.init(mock(Looper.class),
                    mock(IPackageManager.class), mock(PackageManager.class),
                    mock(LightsManager.class),
                    mock(NotificationListeners.class), mock(NotificationAssistants.class),
                    mock(ConditionProviders.class), mock(ICompanionDeviceManager.class),
                    mock(SnoozeHelper.class), mock(NotificationUsageStats.class),
                    mock(AtomicFile.class), mock(ActivityManager.class),
                    mock(GroupHelper.class), mock(IActivityManager.class),
                    mock(UsageStatsManagerInternal.class),
                    mock(DevicePolicyManagerInternal.class), mock(IUriGrantsManager.class),
                    mock(UriGrantsManagerInternal.class),
                    mock(AppOpsManager.class), mUm);
        } catch (SecurityException e) {
            if (!e.getMessage().contains("Permission Denial: not allowed to send broadcast")) {
                throw e;
            }
        }
        mService.setPreferencesHelper(mPreferencesHelper);
    }

    @Test
    public void testInit() {
        List<String> dialer0 = new ArrayList<>();
        dialer0.add("dialer");
        List<String> emer0 = new ArrayList<>();
        emer0.add("emergency");
        List<String> sms10 = new ArrayList<>();
        sms10.add("sms");
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0).getUserHandle())).
                thenReturn(dialer0);
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_EMERGENCY,
                mUsers.get(0).getUserHandle())).
                thenReturn(emer0);
        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_SMS,
                mUsers.get(1).getUserHandle())).
                thenReturn(sms10);

        mRoleObserver.init();

        // verify internal records of current state of the world
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_DIALER, dialer0.get(0), mUsers.get(0).id));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_DIALER, dialer0.get(0), mUsers.get(1).id));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_SMS, dialer0.get(0), mUsers.get(1).id));

        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_EMERGENCY, emer0.get(0), mUsers.get(0).id));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_EMERGENCY, emer0.get(0), mUsers.get(1).id));

        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_SMS, sms10.get(0), mUsers.get(0).id));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_DIALER, sms10.get(0), mUsers.get(0).id));
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(
                ROLE_SMS, sms10.get(0), mUsers.get(1).id));

        // make sure we're listening to updates
        verify(mRoleManager, times(1)).addOnRoleHoldersChangedListenerAsUser(
                eq(mExecutor), any(), eq(UserHandle.ALL));

        // make sure we told pref helper about the state of the world
        verify(mPreferencesHelper, times(1)).updateDefaultApps(0, null, new ArraySet<>(dialer0));
        verify(mPreferencesHelper, times(1)).updateDefaultApps(0, null, new ArraySet<>(emer0));
        verify(mPreferencesHelper, times(1)).updateDefaultApps(10, null, new ArraySet<>(sms10));
    }

    @Test
    public void testSwapDefault() {
        List<String> dialer0 = new ArrayList<>();
        dialer0.add("dialer");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0).getUserHandle())).
                thenReturn(dialer0);

        mRoleObserver.init();

        List<String> newDefault = new ArrayList<>();
        newDefault.add("phone");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0).getUserHandle())).
                thenReturn(newDefault);

        mRoleObserver.onRoleHoldersChanged(ROLE_DIALER, UserHandle.of(0));

        verify(mPreferencesHelper, times(1)).updateDefaultApps(
                0, new ArraySet<>(dialer0), new ArraySet<>(newDefault));
    }

    @Test
    public void testSwapDefault_multipleOverlappingApps() {
        List<String> dialer0 = new ArrayList<>();
        dialer0.add("dialer");
        dialer0.add("phone");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0).getUserHandle())).
                thenReturn(dialer0);

        mRoleObserver.init();

        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "phone", 0));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "emerPhone", 0));
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "dialer", 0));

        List<String> newDefault = new ArrayList<>();
        newDefault.add("phone");
        newDefault.add("emerPhone");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0).getUserHandle())).
                thenReturn(newDefault);

        mRoleObserver.onRoleHoldersChanged(ROLE_DIALER, UserHandle.of(0));

        ArraySet<String> expectedRemove = new ArraySet<>();
        expectedRemove.add("dialer");
        ArraySet<String> expectedAdd = new ArraySet<>();
        expectedAdd.add("emerPhone");

        verify(mPreferencesHelper, times(1)).updateDefaultApps(
                0, expectedRemove, expectedAdd);

        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "phone", 0));
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "emerPhone", 0));
        assertFalse(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "dialer", 0));
    }

    @Test
    public void testSwapDefault_newUser() {
        List<String> dialer0 = new ArrayList<>();
        dialer0.add("dialer");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(0).getUserHandle())).
                thenReturn(dialer0);

        mRoleObserver.init();

        List<String> dialer10 = new ArrayList<>();
        dialer10.add("phone");

        when(mRoleManager.getRoleHoldersAsUser(
                ROLE_DIALER,
                mUsers.get(1).getUserHandle())).
                thenReturn(dialer10);

        mRoleObserver.onRoleHoldersChanged(ROLE_DIALER, UserHandle.of(10));

        ArraySet<String> expectedRemove = new ArraySet<>();
        ArraySet<String> expectedAdd = new ArraySet<>();
        expectedAdd.add("phone");

        verify(mPreferencesHelper, times(1)).updateDefaultApps(
                10, expectedRemove, expectedAdd);

        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "phone", 10));
        assertTrue(mRoleObserver.isApprovedPackageForRoleForUser(ROLE_DIALER, "dialer", 0));
    }
}
