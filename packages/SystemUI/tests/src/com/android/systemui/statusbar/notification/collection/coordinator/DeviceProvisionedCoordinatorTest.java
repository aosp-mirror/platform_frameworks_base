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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManagerInternal;
import android.app.Notification;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DeviceProvisionedCoordinatorTest extends SysuiTestCase {
    private static final int NOTIF_UID = 0;

    private static final String SHOW_WHEN_UNPROVISIONED_FLAG =
            Notification.EXTRA_ALLOW_DURING_SETUP;
    private static final String SETUP_NOTIF_PERMISSION =
            Manifest.permission.NOTIFICATION_DURING_SETUP;

    private MockitoSession mMockitoSession;

    @Mock private ActivityManagerInternal mActivityMangerInternal;
    @Mock private IPackageManager mIPackageManager;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private NotifPipeline mNotifPipeline;
    private Notification mNotification;
    private NotificationEntry mEntry;
    private DeviceProvisionedCoordinator mDeviceProvisionedCoordinator;
    private NotifFilter mDeviceProvisionedFilter;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDeviceProvisionedCoordinator = new DeviceProvisionedCoordinator(
                mDeviceProvisionedController, mIPackageManager);

        mNotification = new Notification();
        mEntry = new NotificationEntryBuilder()
                .setNotification(mNotification)
                .setUid(NOTIF_UID)
                .build();

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        mDeviceProvisionedCoordinator.attach(mNotifPipeline);
        verify(mNotifPipeline, times(1)).addPreGroupFilter(filterCaptor.capture());
        mDeviceProvisionedFilter = filterCaptor.getValue();
    }

    @Test
    public void deviceProvisioned() {
        // GIVEN device is provisioned
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);

        // THEN don't filter out the notification
        assertFalse(mDeviceProvisionedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void deviceUnprovisioned() {
        // GIVEN device is unprovisioned
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(false);

        // THEN filter out the notification
        assertTrue(mDeviceProvisionedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void deviceUnprovisionedCanBypass() throws RemoteException {
        // GIVEN device is unprovisioned
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(false);

        // GIVEN notification has a flag to allow the notification during setup
        Bundle extras = new Bundle();
        extras.putBoolean(SHOW_WHEN_UNPROVISIONED_FLAG, true);
        mNotification.extras = extras;

        // GIVEN notification has the permission to display during setup
        when(mIPackageManager.checkUidPermission(SETUP_NOTIF_PERMISSION, NOTIF_UID))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        // THEN don't filter out the notification
        assertFalse(mDeviceProvisionedFilter.shouldFilterOut(mEntry, 0));
    }

    @Test
    public void deviceUnprovisionedTryBypassWithoutPermission() throws RemoteException {
        // GIVEN device is unprovisioned
        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(false);

        // GIVEN notification has a flag to allow the notification during setup
        Bundle extras = new Bundle();
        extras.putBoolean(SHOW_WHEN_UNPROVISIONED_FLAG, true);
        mNotification.extras = extras;

        // GIVEN notification does NOT have permission to display during setup
        when(mIPackageManager.checkUidPermission(SETUP_NOTIF_PERMISSION, NOTIF_UID))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        // THEN filter out the notification
        assertTrue(mDeviceProvisionedFilter.shouldFilterOut(mEntry, 0));
    }

    private RankingBuilder getRankingForUnfilteredNotif() {
        return new RankingBuilder()
                .setKey(mEntry.getKey())
                .setSuppressedVisualEffects(0)
                .setSuspended(false);
    }
}
