/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.notification;

import static com.android.internal.notification.SystemNotificationChannels.ABUSIVE_BACKGROUND_APPS;
import static com.android.internal.notification.SystemNotificationChannels.ACCESSIBILITY_MAGNIFICATION;
import static com.android.internal.notification.SystemNotificationChannels.ACCESSIBILITY_SECURITY_POLICY;
import static com.android.internal.notification.SystemNotificationChannels.ACCOUNT;
import static com.android.internal.notification.SystemNotificationChannels.ALERTS;
import static com.android.internal.notification.SystemNotificationChannels.CAR_MODE;
import static com.android.internal.notification.SystemNotificationChannels.DEVELOPER;
import static com.android.internal.notification.SystemNotificationChannels.DEVELOPER_IMPORTANT;
import static com.android.internal.notification.SystemNotificationChannels.DEVICE_ADMIN;
import static com.android.internal.notification.SystemNotificationChannels.FOREGROUND_SERVICE;
import static com.android.internal.notification.SystemNotificationChannels.HEAVY_WEIGHT_APP;
import static com.android.internal.notification.SystemNotificationChannels.NETWORK_ALERTS;
import static com.android.internal.notification.SystemNotificationChannels.NETWORK_AVAILABLE;
import static com.android.internal.notification.SystemNotificationChannels.NETWORK_STATUS;
import static com.android.internal.notification.SystemNotificationChannels.OBSOLETE_DO_NOT_DISTURB;
import static com.android.internal.notification.SystemNotificationChannels.PHYSICAL_KEYBOARD;
import static com.android.internal.notification.SystemNotificationChannels.RETAIL_MODE;
import static com.android.internal.notification.SystemNotificationChannels.SECURITY;
import static com.android.internal.notification.SystemNotificationChannels.SYSTEM_CHANGES;
import static com.android.internal.notification.SystemNotificationChannels.UPDATES;
import static com.android.internal.notification.SystemNotificationChannels.USB;
import static com.android.internal.notification.SystemNotificationChannels.VPN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.testing.TestableContext;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class SystemNotificationChannelsTest {

    @Rule public TestableContext mContext = new TestableContext(
            ApplicationProvider.getApplicationContext());

    @Mock private NotificationManager mNm;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(NotificationManager.class, mNm);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void createAll_createsExpectedChannels() {
        ArgumentCaptor<List<NotificationChannel>> createdChannelsCaptor =
                ArgumentCaptor.forClass(List.class);

        SystemNotificationChannels.createAll(mContext);

        verify(mNm).createNotificationChannels(createdChannelsCaptor.capture());
        List<NotificationChannel> createdChannels = createdChannelsCaptor.getValue();
        assertThat(createdChannels.stream().map(NotificationChannel::getId).toList())
                .containsExactly(PHYSICAL_KEYBOARD, SECURITY, CAR_MODE, ACCOUNT, DEVELOPER,
                        DEVELOPER_IMPORTANT, UPDATES, NETWORK_STATUS, NETWORK_ALERTS,
                        NETWORK_AVAILABLE, VPN, DEVICE_ADMIN, ALERTS, RETAIL_MODE, USB,
                        FOREGROUND_SERVICE, HEAVY_WEIGHT_APP, SYSTEM_CHANGES,
                        ACCESSIBILITY_MAGNIFICATION, ACCESSIBILITY_SECURITY_POLICY,
                        ABUSIVE_BACKGROUND_APPS);
    }

    @Test
    public void createAll_deletesObsoleteChannels() {
        ArgumentCaptor<String> deletedChannelCaptor = ArgumentCaptor.forClass(String.class);

        SystemNotificationChannels.createAll(mContext);

        verify(mNm, atLeastOnce()).deleteNotificationChannel(deletedChannelCaptor.capture());
        List<String> deletedChannels = deletedChannelCaptor.getAllValues();
        assertThat(deletedChannels).containsExactly(OBSOLETE_DO_NOT_DISTURB);
    }
}
