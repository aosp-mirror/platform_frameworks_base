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

package com.android.server.notification;

import static android.app.NotificationManager.IMPORTANCE_LOW;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.TestableContext;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class CriticalNotificationExtractorTest extends UiServiceTestCase {
    @Mock
    private PackageManager mPackageManagerClient;
    private TestableContext mContext = spy(getContext());
    private CriticalNotificationExtractor mExtractor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.setMockPackageManager(mPackageManagerClient);
        mExtractor = new CriticalNotificationExtractor();
    }

    /** confirm there is no affect on notifcations if the automotive feature flag is not set */
    @Test
    public void testExtractCritically_nonsupporting() throws Exception {
        when(mPackageManagerClient.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0))
                .thenReturn(false);
        mExtractor.initialize(mContext, null);

        assertCriticality(Notification.CATEGORY_CAR_EMERGENCY,
                CriticalNotificationExtractor.NORMAL);

        assertCriticality(Notification.CATEGORY_CAR_WARNING, CriticalNotificationExtractor.NORMAL);

        assertCriticality(Notification.CATEGORY_CAR_INFORMATION,
                CriticalNotificationExtractor.NORMAL);

        assertCriticality(Notification.CATEGORY_CALL,
                CriticalNotificationExtractor.NORMAL);
    }

    @Test
    public void testExtractCritically() throws Exception {
        when(mPackageManagerClient.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, 0))
                .thenReturn(true);
        mExtractor.initialize(mContext, null);

        assertCriticality(Notification.CATEGORY_CAR_EMERGENCY,
                CriticalNotificationExtractor.CRITICAL);

        assertCriticality(Notification.CATEGORY_CAR_WARNING,
                CriticalNotificationExtractor.CRITICAL_LOW);

        assertCriticality(Notification.CATEGORY_CAR_INFORMATION,
                CriticalNotificationExtractor.NORMAL);

        assertCriticality(Notification.CATEGORY_CALL,
                CriticalNotificationExtractor.NORMAL);
    }

    private void assertCriticality(String cat, int criticality) {
        NotificationRecord info = generateRecord(cat);
        mExtractor.process(info);
        assertThat(info.getCriticality(), is(criticality));
    }


    private NotificationRecord generateRecord(String category) {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_LOW);
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setContentTitle("foo")
                .setCategory(category)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification("", "", 0, "", 0,
                0, n, UserHandle.ALL, null, System.currentTimeMillis());
        return new NotificationRecord(getContext(), sbn, channel);
    }
}
