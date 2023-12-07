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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.projection.MediaProjectionInfo;
import android.media.projection.MediaProjectionManager;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper.RunWithLooper;

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

import java.util.Collections;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class SensitiveContentProtectionManagerServiceTest {
    private static final String NOTIFICATION_KEY_1 = "com.android.server.notification.TEST_KEY_1";
    private static final String NOTIFICATION_KEY_2 = "com.android.server.notification.TEST_KEY_2";

    private static final String NOTIFICATION_PKG_1 = "com.android.server.notification.one";
    private static final String NOTIFICATION_PKG_2 = "com.android.server.notification.two";

    private static final int NOTIFICATION_UID_1 = 5;
    private static final int NOTIFICATION_UID_2 = 6;

    @Rule
    public final TestableContext mContext =
            new TestableContext(getInstrumentation().getTargetContext(), null);

    private SensitiveContentProtectionManagerService mSensitiveContentProtectionManagerService;

    @Captor
    ArgumentCaptor<MediaProjectionManager.Callback> mMediaProjectionCallbackCaptor;

    @Mock
    private MediaProjectionManager mProjectionManager;

    @Mock
    private WindowManagerInternal mWindowManager;

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

        // Setup RankingMap and two possilbe rankings
        when(mSensitiveRanking.hasSensitiveContent()).thenReturn(true);
        when(mNonSensitiveRanking.hasSensitiveContent()).thenReturn(false);
        doReturn(mRankingMap)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        setupSensitiveNotification();

        mSensitiveContentProtectionManagerService.init(mProjectionManager, mWindowManager);

        // Obtain useful mMediaProjectionCallback
        verify(mProjectionManager).addCallback(mMediaProjectionCallbackCaptor.capture(), any());
    }

    @After
    public void tearDown() {
        mSensitiveContentProtectionManagerService.onDestroy();
    }

    private Set<PackageInfo> setupSensitiveNotification() {
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

        return Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1));
    }

    private Set<PackageInfo> setupMultipleSensitiveNotificationsFromSamePackageAndUid() {
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

        return Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1));
    }

    private Set<PackageInfo> setupMultipleSensitiveNotificationsFromDifferentPackage() {
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

        return Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1),
                new PackageInfo(NOTIFICATION_PKG_2, NOTIFICATION_UID_1));
    }

    private Set<PackageInfo> setupMultipleSensitiveNotificationsFromDifferentUid() {
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

        return Set.of(new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_1),
                new PackageInfo(NOTIFICATION_PKG_1, NOTIFICATION_UID_2));
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

    @Test
    public void mediaProjectionOnStart_onProjectionStart_setWmBlockedPackages() {
        Set<PackageInfo> expectedBlockedPackages = setupSensitiveNotification();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStart_noSensitiveNotifications_noBlockedPackages() {
        setupNoSensitiveNotifications();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }

    @Test
    public void mediaProjectionOnStart_noNotifications_noBlockedPackages() {
        setupNoNotifications();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }

    @Test
    public void mediaProjectionOnStart_multipleNotifications_setWmBlockedPackages() {
        Set<PackageInfo> expectedBlockedPackages =
                setupMultipleSensitiveNotificationsFromSamePackageAndUid();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStart_multiplePackages_setWmBlockedPackages() {
        Set<PackageInfo> expectedBlockedPackages =
                setupMultipleSensitiveNotificationsFromDifferentPackage();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStart_multipleUid_setWmBlockedPackages() {
        Set<PackageInfo> expectedBlockedPackages =
                setupMultipleSensitiveNotificationsFromDifferentUid();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStop_onProjectionEnd_clearWmBlockedPackages() {
        setupSensitiveNotification();

        MediaProjectionInfo mediaProjectionInfo = mock(MediaProjectionInfo.class);
        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);
        Mockito.reset(mWindowManager);

        mMediaProjectionCallbackCaptor.getValue().onStop(mediaProjectionInfo);

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }

    @Test
    public void mediaProjectionOnStart_afterOnStop_onProjectionStart_setWmBlockedPackages() {
        Set<PackageInfo> expectedBlockedPackages = setupSensitiveNotification();

        MediaProjectionInfo mediaProjectionInfo = mock(MediaProjectionInfo.class);
        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);
        mMediaProjectionCallbackCaptor.getValue().onStop(mediaProjectionInfo);
        Mockito.reset(mWindowManager);

        mMediaProjectionCallbackCaptor.getValue().onStart(mediaProjectionInfo);

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(expectedBlockedPackages);
    }

    @Test
    public void mediaProjectionOnStart_getActiveNotificationsThrows_noBlockedPackages() {
        doThrow(SecurityException.class)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getActiveNotifications();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }

    @Test
    public void mediaProjectionOnStart_getCurrentRankingThrows_noBlockedPackages() {
        doThrow(SecurityException.class)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }

    @Test
    public void mediaProjectionOnStart_getCurrentRanking_nullRankingMap_noBlockedPackages() {
        doReturn(null)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }

    @Test
    public void mediaProjectionOnStart_getCurrentRanking_missingRanking_noBlockedPackages() {
        when(mRankingMap.getRawRankingObject(eq(NOTIFICATION_KEY_1))).thenReturn(null);

        doReturn(mRankingMap)
                .when(mSensitiveContentProtectionManagerService.mNotificationListener)
                .getCurrentRanking();

        mMediaProjectionCallbackCaptor.getValue().onStart(mock(MediaProjectionInfo.class));

        verify(mWindowManager).setShouldBlockScreenCaptureForApp(Collections.emptySet());
    }
}
