/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.Notification;
import android.app.Notification.MediaStyle;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;

import com.android.systemui.ForegroundServiceController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaFeatureFlag;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.notification.NotificationEntryManager.KeyguardEnvironment;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.wm.shell.bubbles.Bubbles;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationFilterTest extends SysuiTestCase {

    private static final int UID_NORMAL = 123;
    private static final int UID_ALLOW_DURING_SETUP = 456;
    private static final String TEST_HIDDEN_NOTIFICATION_KEY = "testHiddenNotificationKey";

    private final StatusBarNotification mMockStatusBarNotification =
            mock(StatusBarNotification.class);

    @Mock
    StatusBarStateController mStatusBarStateController;
    @Mock
    KeyguardEnvironment mEnvironment;
    @Mock
    ForegroundServiceController mFsc;
    @Mock
    NotificationLockscreenUserManager mUserManager;
    @Mock
    MediaFeatureFlag mMediaFeatureFlag;

    private final IPackageManager mMockPackageManager = mock(IPackageManager.class);

    private NotificationFilter mNotificationFilter;
    private ExpandableNotificationRow mRow;
    private NotificationEntry mMediaEntry;
    private MediaSession mMediaSession;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        MockitoAnnotations.initMocks(this);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_NORMAL);

        mMediaSession = new MediaSession(mContext, "TEST_MEDIA_SESSION");
        NotificationEntryBuilder builder = new NotificationEntryBuilder();
        builder.modifyNotification(mContext).setStyle(
                new MediaStyle().setMediaSession(mMediaSession.getSessionToken()));
        mMediaEntry = builder.build();

        when(mMockPackageManager.checkUidPermission(
                eq(Manifest.permission.NOTIFICATION_DURING_SETUP),
                eq(UID_NORMAL)))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockPackageManager.checkUidPermission(
                eq(Manifest.permission.NOTIFICATION_DURING_SETUP),
                eq(UID_ALLOW_DURING_SETUP)))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        mDependency.injectTestDependency(ForegroundServiceController.class, mFsc);
        mDependency.injectTestDependency(NotificationGroupManagerLegacy.class,
                new NotificationGroupManagerLegacy(
                        mock(StatusBarStateController.class),
                        () -> mock(PeopleNotificationIdentifier.class),
                        Optional.of(mock(Bubbles.class)),
                        mock(DumpManager.class)));
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectMockDependency(NotificationLockscreenUserManager.class);
        mDependency.injectTestDependency(KeyguardEnvironment.class, mEnvironment);
        when(mEnvironment.isDeviceProvisioned()).thenReturn(true);
        when(mEnvironment.isNotificationForCurrentProfiles(any())).thenReturn(true);
        NotificationTestHelper testHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mRow = testHelper.createRow();
        mNotificationFilter = new NotificationFilter(
                mStatusBarStateController,
                mEnvironment,
                mFsc,
                mUserManager,
                mMediaFeatureFlag);
    }

    @After
    public void tearDown() {
        mMediaSession.release();
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_FalseIfNoExtra() {
        initStatusBarNotification(false);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_ALLOW_DURING_SETUP);

        assertFalse(
                NotificationFilter.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_FalseIfNoPermission() {
        initStatusBarNotification(true);

        assertFalse(
                NotificationFilter.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    @UiThreadTest
    public void testShowNotificationEvenIfUnprovisioned_TrueIfHasPermissionAndExtra() {
        initStatusBarNotification(true);
        when(mMockStatusBarNotification.getUid()).thenReturn(UID_ALLOW_DURING_SETUP);

        assertTrue(
                NotificationFilter.showNotificationEvenIfUnprovisioned(
                        mMockPackageManager,
                        mMockStatusBarNotification));
    }

    @Test
    public void testShouldFilterHiddenNotifications() {
        initStatusBarNotification(false);
        // setup
        when(mFsc.isSystemAlertWarningNeeded(anyInt(), anyString())).thenReturn(false);

        // test should filter out hidden notifications:
        // hidden
        NotificationEntry entry = new NotificationEntryBuilder()
                .setSuspended(true)
                .build();

        assertTrue(mNotificationFilter.shouldFilterOut(entry));

        // not hidden
        entry = new NotificationEntryBuilder()
                .setSuspended(false)
                .build();
        assertFalse(mNotificationFilter.shouldFilterOut(entry));
    }

    @Test
    public void shouldFilterOtherNotificationWhenDisabled() {
        // GIVEN that the media feature is disabled
        when(mMediaFeatureFlag.getEnabled()).thenReturn(false);
        NotificationFilter filter = new NotificationFilter(
                mStatusBarStateController,
                mEnvironment,
                mFsc,
                mUserManager,
                mMediaFeatureFlag);
        // WHEN the media filter is asked about an entry
        NotificationEntry otherEntry = new NotificationEntryBuilder().build();
        final boolean shouldFilter = filter.shouldFilterOut(otherEntry);
        // THEN it shouldn't be filtered
        assertFalse(shouldFilter);
    }

    @Test
    public void shouldFilterOtherNotificationWhenEnabled() {
        // GIVEN that the media feature is enabled
        when(mMediaFeatureFlag.getEnabled()).thenReturn(true);
        NotificationFilter filter = new NotificationFilter(
                mStatusBarStateController,
                mEnvironment,
                mFsc,
                mUserManager,
                mMediaFeatureFlag);
        // WHEN the media filter is asked about an entry
        NotificationEntry otherEntry = new NotificationEntryBuilder().build();
        final boolean shouldFilter = filter.shouldFilterOut(otherEntry);
        // THEN it shouldn't be filtered
        assertFalse(shouldFilter);
    }

    @Test
    public void shouldFilterMediaNotificationWhenDisabled() {
        // GIVEN that the media feature is disabled
        when(mMediaFeatureFlag.getEnabled()).thenReturn(false);
        NotificationFilter filter = new NotificationFilter(
                mStatusBarStateController,
                mEnvironment,
                mFsc,
                mUserManager,
                mMediaFeatureFlag);
        // WHEN the media filter is asked about a media entry
        final boolean shouldFilter = filter.shouldFilterOut(mMediaEntry);
        // THEN it shouldn't be filtered
        assertFalse(shouldFilter);
    }

    @Test
    public void shouldFilterMediaNotificationWhenEnabled() {
        // GIVEN that the media feature is enabled
        when(mMediaFeatureFlag.getEnabled()).thenReturn(true);
        NotificationFilter filter = new NotificationFilter(
                mStatusBarStateController,
                mEnvironment,
                mFsc,
                mUserManager,
                mMediaFeatureFlag);
        // WHEN the media filter is asked about a media entry
        final boolean shouldFilter = filter.shouldFilterOut(mMediaEntry);
        // THEN it should be filtered
        assertTrue(shouldFilter);
    }

    private void initStatusBarNotification(boolean allowDuringSetup) {
        Bundle bundle = new Bundle();
        bundle.putBoolean(Notification.EXTRA_ALLOW_DURING_SETUP, allowDuringSetup);
        Notification notification = new Notification.Builder(mContext, "test")
                .addExtras(bundle)
                .build();
        when(mMockStatusBarNotification.getNotification()).thenReturn(notification);
    }
}
