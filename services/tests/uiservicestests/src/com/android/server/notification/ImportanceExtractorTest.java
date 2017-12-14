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
package com.android.server.notification;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

import static org.junit.Assert.assertEquals;

import com.android.server.UiServiceTestCase;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ImportanceExtractorTest extends UiServiceTestCase {

    @Mock RankingConfig mConfig;

    private String mPkg = "com.android.server.notification";
    private int mId = 1001;
    private int mOtherId = 1002;
    private String mTag = null;
    private int mUid = 1000;
    private int mPid = 2000;
    private int mScore = 10;
    private android.os.UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    private NotificationRecord getNotificationRecord(NotificationChannel channel) {
        final Builder builder = new Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND);

        Notification n = builder.build();
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, mId, mTag, mUid,
                mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        return r;
    }

    //
    // Tests
    //

    @Test
    public void testAppPreferenceChannelNone() throws Exception {
        ImportanceExtractor extractor = new ImportanceExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.getImportance(anyString(), anyInt())).thenReturn(
          NotificationManager.IMPORTANCE_MIN);
        NotificationChannel channel =
                new NotificationChannel("a", "a", NotificationManager.IMPORTANCE_UNSPECIFIED);

        NotificationRecord r = getNotificationRecord(channel);
        int notificationImportance = r.getImportance();

        extractor.process(r);

        assertEquals(NotificationManager.IMPORTANCE_UNSPECIFIED, r.getUserImportance());
        assertEquals(notificationImportance, r.getImportance());
    }

    @Test
    public void testAppPreferenceChannelPreference() throws Exception {
        ImportanceExtractor extractor = new ImportanceExtractor();
        extractor.setConfig(mConfig);

        when(mConfig.getImportance(anyString(), anyInt())).thenReturn(
          NotificationManager.IMPORTANCE_MIN);
        NotificationChannel channel =
                new NotificationChannel("a", "a", NotificationManager.IMPORTANCE_HIGH);

        NotificationRecord r = getNotificationRecord(channel);

        extractor.process(r);

        assertEquals(r.getUserImportance(), NotificationManager.IMPORTANCE_HIGH);
    }
}
