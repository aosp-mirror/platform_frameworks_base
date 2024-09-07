/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server;

import static android.permission.flags.Flags.FLAG_SENSITIVE_CONTENT_METRICS_BUGFIX;
import static android.permission.flags.Flags.FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManagerInternal;
import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.os.Process;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper.RunWithLooper;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import com.android.server.wm.SensitiveContentPackages.PackageInfo;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@RequiresFlagsEnabled(FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION)
/**
 * Test {@link SensitiveContentProtectionManagerService} for sensitive notification protection,
 * the service protects sensitive content during screen share.
 */
public class SensitiveContentProtectionManagerServiceNotificationTest {
    private static final String NOTIFICATION_KEY_1 = "com.android.server.notification.TEST_KEY_1";
    private static final String NOTIFICATION_KEY_2 = "com.android.server.notification.TEST_KEY_2";

    private static final String NOTIFICATION_PKG_1 = "com.android.server.notification.one";
    private static final String NOTIFICATION_PKG_2 = "com.android.server.notification.two";

    private static final String SCREEN_RECORDER_PACKAGE = "test.screen.recorder.package";
    private static final String EXEMPTED_SCREEN_RECORDER_PACKAGE = "exempt.screen.recorder.package";

    private static final int NOTIFICATION_UID_1 = 5;
    private static final int NOTIFICATION_UID_2 = 6;

    private static final ArraySet<PackageInfo> EMPTY_SET = new ArraySet<>();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public final TestableContext mContext =
            new TestableContext(getInstrumentation().getTargetContext(), null);

    private SensitiveContentProtectionManagerService mSensitiveContentProtectionManagerService;

    @Captor
    ArgumentCaptor<MediaProjectionManager.Callback> mMediaProjectionCallbackCaptor;
    @Captor
    private ArgumentCaptor<ArraySet<PackageInfo>> mPackageInfoCaptor;

    @Mock
    private MediaProjectionManager mProjectionManager;

    @Mock
    private WindowManagerInternal mWindowManager;

    @Mock
    private PackageManagerInternal mPackageManagerInternal;

    @Mock
    private StatusBarNotification mNotification1;

    @Mock
    private StatusBarNotification mNotification2;

    @Mock
    private RankingMap mRankingMap;

    @Mock
    private Ranking mSensitiveRanking;

    @Mock
    private Ranking mNonSensitiveRanking;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mSensitiveContentProtectionManagerService =
                new SensitiveContentProtectionManagerService(mContext);

        mSensitiveContentProtectionManagerService.mNotificationListener =
                spy(mSensitiveContentProtectionManagerService.mNotificationListener);
        doCallRealMethod()
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .onListenerConnected();

        // Setup RankingMap and two possilbe rankings
        when(mSensitiveRanking.hasSensitiveContent()).thenReturn(true);
        when(mNonSensitiveRanking.hasSensitiveContent()).thenReturn(false);
        doReturn(mRankingMap)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        setupSensitiveNotification();

        mSensitiveContentProtectionManagerService.init(mProjectionManager, mWindowManager,
                mPackageManagerInternal, new ArraySet<>(Set.of(EXEMPTED_SCREEN_RECORDER_PACKAGE)));

        // Obtain useful mMediaProjectionCallback
        verify(mProjectionManager).addCallback(mMediaProjectionCallbackCaptor.capture(), any());
    }

    @After
    public void tearDown() {
        mSensitiveContentProtectionManagerService.onDestroy();
    }

    private ArraySet<PackageInfo> setupSensitiveNotification() {
        // Setup Notification Values
        when(mNotification1.getKey()).thenReturn(NOTIFICATION_KEY_1);
        when(mNotification1.getPackageName()).thenReturn(NOTIFICATION_PKG_1);
        when(mNotification1.getUid()).thenReturn(NOTIFICATION_UID_1);

        when(mNotification2.getKey()).thenReturn(NOTIFICATION_KEY_2);
        when(mNotification2.getPackageName()).thenReturn(NOTIFICATION_PKG_2);
        when(mNotification2.getUid()).thenReturn(NOTIFICATION_UID_1);

        StatusBarNotification[] mNotifications =
                new StatusBarNotification[] {mNotification1, mNotification2};
        doReturn(mNotifications)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();

        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1)))
                .thenReturn(mSensitiveRanking);
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_2)))
                .thenReturn(mNonSensitiveRanking);

        return new ArraySet<>(
                Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1)));
    }

    private ArraySet<PackageInfo> setupMultipleSensitiveNotificationsFromSamePackageAndUid() {
        // Setup Notification Values
        when(mNotification1.getKey()).thenReturn(NOTIFICATION_KEY_1);
        when(mNotification1.getPackageName()).thenReturn(NOTIFICATION_PKG_1);
        when(mNotification1.getUid()).thenReturn(NOTIFICATION_UID_1);

        when(mNotification2.getKey()).thenReturn(NOTIFICATION_KEY_2);
        when(mNotification2.getPackageName()).thenReturn(NOTIFICATION_PKG_1);
        when(mNotification2.getUid()).thenReturn(NOTIFICATION_UID_1);

        StatusBarNotification[] mNotifications =
                new StatusBarNotification[] {mNotification1, mNotification2};
        doReturn(mNotifications)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();

        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1)))
                .thenReturn(mSensitiveRanking);
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_2)))
                .thenReturn(mSensitiveRanking);

        return new ArraySet<>(
                Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1)));
    }

    private ArraySet<PackageInfo> setupMultipleSensitiveNotificationsFromDifferentPackage() {
        // Setup Notification Values
        when(mNotification1.getKey()).thenReturn(NOTIFICATION_KEY_1);
        when(mNotification1.getPackageName()).thenReturn(NOTIFICATION_PKG_1);
        when(mNotification1.getUid()).thenReturn(NOTIFICATION_UID_1);

        when(mNotification2.getKey()).thenReturn(NOTIFICATION_KEY_2);
        when(mNotification2.getPackageName()).thenReturn(NOTIFICATION_PKG_2);
        when(mNotification2.getUid()).thenReturn(NOTIFICATION_UID_1);

        StatusBarNotification[] mNotifications =
                new StatusBarNotification[] {mNotification1, mNotification2};
        doReturn(mNotifications)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();

        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1)))
                .thenReturn(mSensitiveRanking);
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_2)))
                .thenReturn(mSensitiveRanking);

        return new ArraySet<>(
                Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1),
                        new PackageInfo(NOTIFICATION_PKG_2, NOTIFICATION_UID_1)));
    }

    private ArraySet<PackageInfo> setupMultipleSensitiveNotificationsFromDifferentUid() {
        // Setup Notification Values
        when(mNotification1.getKey()).thenReturn(NOTIFICATION_KEY_1);
        when(mNotification1.getPackageName()).thenReturn(NOTIFICATION_PKG_1);
        when(mNotification1.getUid()).thenReturn(NOTIFICATION_UID_1);

        when(mNotification2.getKey()).thenReturn(NOTIFICATION_KEY_2);
        when(mNotification2.getPackageName()).thenReturn(NOTIFICATION_PKG_1);
        when(mNotification2.getUid()).thenReturn(NOTIFICATION_UID_2);

        StatusBarNotification[] mNotifications =
                new StatusBarNotification[] {mNotification1, mNotification2};
        doReturn(mNotifications)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();

        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1)))
                .thenReturn(mSensitiveRanking);
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_2)))
                .thenReturn(mSensitiveRanking);

        return new ArraySet<>(
                Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1),
                        new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_2)));
    }

    private void setupNoSensitiveNotifications() {
        // Setup Notification Values
        when(mNotification1.getKey()).thenReturn(NOTIFICATION_KEY_1);
        when(mNotification1.getPackageName()).thenReturn(NOTIFICATION_PKG_1);
        when(mNotification1.getUid()).thenReturn(NOTIFICATION_UID_1);

        StatusBarNotification[] mNotifications = new StatusBarNotification[] {mNotification1};
        doReturn(mNotifications)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();

        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1)))
                .thenReturn(mNonSensitiveRanking);
    }

    private void setupNoNotifications() {
        // Setup Notification Values
        StatusBarNotification[] mNotifications = new StatusBarNotification[] {};
        doReturn(mNotifications)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();
    }

    private void setupNullNotifications() {
        // Setup Notification Values
        StatusBarNotification[] mNotifications = new StatusBarNotification[] { null, null};
        doReturn(mNotifications)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();
    }

    private MediaProjectionInfo createMediaProjectionInfo() {
        return new MediaProjectionInfo(SCREEN_RECORDER_PACKAGE, Process.myUserHandle(), null);
    }

    private MediaProjectionInfo createExemptMediaProjectionInfo() {
        return new MediaProjectionInfo(
                EXEMPTED_SCREEN_RECORDER_PACKAGE, Process.myUserHandle(), null);
    }

    @Test
    public void mediaProjectionOnStart_verifyExemptedRecorderPackage() {
        MediaProjectionInfo mediaProjectionInfo = createExemptMediaProjectionInfo();

        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    @RequiresFlagsDisabled(FLAG_SENSITIVE_CONTENT_METRICS_BUGFIX)
    public void mediaProjectionOnStart_flagDisabled_neverSetBlockScreenCaptureForAppsSessionId() {
        setupSensitiveNotification();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager, never()).setBlockScreenCaptureForAppsSessionId(anyLong());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_CONTENT_METRICS_BUGFIX)
    public void mediaProjectionOnStart_setBlockScreenCaptureForAppsSessionId() {
        setupSensitiveNotification();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager).setBlockScreenCaptureForAppsSessionId(anyLong());
    }

    @Test
    public void mediaProjectionOnStart_onProjectionStart_setWmBlockedPackages() {
        ArraySet<PackageInfo> expectedBlockedPackages = setupSensitiveNotification();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager).addBlockScreenCaptureForApps(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStart_noSensitiveNotifications_noBlockedPackages() {
        setupNoSensitiveNotifications();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(any());
    }

    @Test
    public void mediaProjectionOnStart_noNotifications_noBlockedPackages() {
        setupNoNotifications();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(any());
    }

    @Test
    public void mediaProjectionOnStart_multipleNotifications_setWmBlockedPackages() {
        ArraySet<PackageInfo> expectedBlockedPackages =
                setupMultipleSensitiveNotificationsFromSamePackageAndUid();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager).addBlockScreenCaptureForApps(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStart_multiplePackages_setWmBlockedPackages() {
        ArraySet<PackageInfo> expectedBlockedPackages =
                setupMultipleSensitiveNotificationsFromDifferentPackage();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager).addBlockScreenCaptureForApps(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStart_multipleUid_setWmBlockedPackages() {
        ArraySet<PackageInfo> expectedBlockedPackages =
                setupMultipleSensitiveNotificationsFromDifferentUid();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager).addBlockScreenCaptureForApps(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStop_onProjectionEnd_clearWmBlockedPackages() {
        setupSensitiveNotification();

        MediaProjectionInfo mediaProjectionInfo = createMediaProjectionInfo();
        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);
        Mockito.reset(mWindowManager);

        mMediaProjectionCallbackCaptor.getValue().onStop(mediaProjectionInfo);

        verify(mWindowManager).clearBlockedApps();
    }

    @Test
    public void mediaProjectionOnStart_afterOnStop_onProjectionStart_setWmBlockedPackages() {
        ArraySet<PackageInfo> expectedBlockedPackages = setupSensitiveNotification();

        MediaProjectionInfo mediaProjectionInfo = createMediaProjectionInfo();
        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);
        mMediaProjectionCallbackCaptor.getValue().onStop(mediaProjectionInfo);
        Mockito.reset(mWindowManager);

        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);

        verify(mWindowManager).addBlockScreenCaptureForApps(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStart_getActiveNotificationsThrows_noBlockedPackages() {
        doThrow(SecurityException.class)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(any());
    }

    @Test
    public void mediaProjectionOnStart_getCurrentRankingThrows_noBlockedPackages() {
        doThrow(SecurityException.class)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(any());
    }

    @Test
    public void mediaProjectionOnStart_getCurrentRanking_nullRankingMap_noBlockedPackages() {
        doReturn(null)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(any());
    }

    @Test
    public void mediaProjectionOnStart_getCurrentRanking_missingRanking_noBlockedPackages() {
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1))).thenReturn(null);

        doReturn(mRankingMap)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(any());
    }

    @Test
    public void mediaProjectionOnStart_disabledViaDevOption_noBlockedPackages() {
        mockDisabledViaDevelopOption();
        setupSensitiveNotification();

        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnListenerConnected_projectionNotStarted_noop() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();

        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnListenerConnected_projectionStopped_noop() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        MediaProjectionInfo mediaProjectionInfo = createMediaProjectionInfo();
        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);
        mMediaProjectionCallbackCaptor.getValue().onStop(mediaProjectionInfo);
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnListenerConnected_projectionStarted_setWmBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        ArraySet<PackageInfo> expectedBlockedPackages = setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager).addBlockScreenCaptureForApps(expectedBlockedPackages);
    }

    @Test
    public void nlsOnListenerConnected_noSensitiveNotifications_noBlockedPackages() {
        setupNoSensitiveNotifications();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnListenerConnected_noNotifications_noBlockedPackages() {
        setupNoNotifications();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnListenerConnected_nullNotifications_noBlockedPackages() {
        setupNullNotifications();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnListenerConnected_nullRankingMap_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);
        doReturn(null)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnListenerConnected_missingRanking_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1))).thenReturn(null);
        doReturn(mRankingMap)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnListenerConnected_disabledViaDevOption_noBlockedPackages() {
        mockDisabledViaDevelopOption();
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        mSensitiveContentProtectionManagerService.mNotificationListener.onListenerConnected();

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationRankingUpdate_projectionNotStarted_noop() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(mRankingMap);

        verifyNoBlockOrClearInteractionWithWindowManager();
    }

    @Test
    public void nlsOnNotificationRankingUpdate_projectionStopped_noop() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        MediaProjectionInfo mediaProjectionInfo = createMediaProjectionInfo();
        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);
        mMediaProjectionCallbackCaptor.getValue().onStop(mediaProjectionInfo);
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(mRankingMap);

        verifyNoBlockOrClearInteractionWithWindowManager();
    }

    @Test
    public void nlsOnNotificationRankingUpdate_projectionStarted_setWmBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        ArraySet<PackageInfo> expectedBlockedPackages = setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(mRankingMap);

        verify(mWindowManager).addBlockScreenCaptureForApps(expectedBlockedPackages);
    }

    @Test
    public void nlsOnNotificationRankingUpdate_noSensitiveNotifications_noBlockedPackages() {
        setupNoSensitiveNotifications();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationRankingUpdate_noNotifications_noBlockedPackages() {
        setupNoNotifications();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationRankingUpdate_nullRankingMap_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(null);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationRankingUpdate_missingRanking_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1))).thenReturn(null);
        doReturn(mRankingMap)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationRankingUpdate_getActiveNotificationsThrows_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        doThrow(SecurityException.class)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationRankingUpdate_disabledViaDevOption_noBlockedPackages() {
        mockDisabledViaDevelopOption();
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationRankingUpdate(mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationPosted_projectionNotStarted_noop() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationPosted(mNotification1, mRankingMap);

        verifyNoBlockOrClearInteractionWithWindowManager();
    }

    @Test
    public void nlsOnNotificationPosted_projectionStopped_noop() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        MediaProjectionInfo mediaProjectionInfo = createMediaProjectionInfo();
        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);
        mMediaProjectionCallbackCaptor.getValue().onStop(mediaProjectionInfo);
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationPosted(mNotification1, mRankingMap);

        verifyNoBlockOrClearInteractionWithWindowManager();
    }

    @Test
    public void nlsOnNotificationPosted_projectionStarted_setWmBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationPosted(mNotification1, mRankingMap);

        ArraySet<PackageInfo> expectedBlockedPackages = new ArraySet<>(
                Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1)));
        verify(mWindowManager).addBlockScreenCaptureForApps(expectedBlockedPackages);
    }

    @Test
    public void nlsOnNotificationPosted_noSensitiveNotifications_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationPosted(mNotification2, mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationPosted_noNotifications_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationPosted(null, mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationPosted_nullRankingMap_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationPosted(mNotification1, null);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationPosted_missingRanking_noBlockedPackages() {
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        Mockito.reset(mWindowManager);
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1))).thenReturn(null);

        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationPosted(mNotification1, mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    @Test
    public void nlsOnNotificationPosted_disabledViaDevOption_noBlockedPackages() {
        mockDisabledViaDevelopOption();
        // Sets up mNotification1 & mRankingMap to be a sensitive notification, and mNotification2
        // as non-sensitive
        setupSensitiveNotification();
        mMediaProjectionCallbackCaptor.getValue().onStart(createMediaProjectionInfo());
        mSensitiveContentProtectionManagerService.mNotificationListener
                .onNotificationPosted(mNotification1, mRankingMap);

        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    private void verifyNoBlockOrClearInteractionWithWindowManager() {
        verify(mWindowManager, never()).addBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
        verify(mWindowManager, never()).clearBlockedApps();
        verify(mWindowManager, never())
                .removeBlockScreenCaptureForApps(mPackageInfoCaptor.capture());
    }

    private void mockDisabledViaDevelopOption() {
        // mContext (TestableContext) uses [TestableSettingsProvider] and it will be cleared after
        // the test
        Settings.Global.putInt(
                mContext.getContentResolver(),
                Settings.Global.DISABLE_SCREEN_SHARE_PROTECTIONS_FOR_APPS_AND_NOTIFICATIONS,
                1);
    }
}
