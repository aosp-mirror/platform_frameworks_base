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

import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.graphics.Color;
import android.os.UserHandle;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class ColorizedFgsCoordinatorTest extends SysuiTestCase {

    private static final int NOTIF_USER_ID = 0;
    @Mock private NotifPipeline mNotifPipeline;

    private NotificationEntryBuilder mEntryBuilder;
    private ColorizedFgsCoordinator mColorizedFgsCoordinator;
    private NotifSectioner mFgsSection;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();

        mColorizedFgsCoordinator = new ColorizedFgsCoordinator();

        mEntryBuilder = new NotificationEntryBuilder()
                .setUser(new UserHandle(NOTIF_USER_ID));

        mColorizedFgsCoordinator.attach(mNotifPipeline);

        mFgsSection = mColorizedFgsCoordinator.getSectioner();
    }

    @Test
    public void testIncludeFGSInSection_importanceDefault() {
        // GIVEN the notification represents a colorized foreground service with > min importance
        mEntryBuilder
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, true)
                .setImportance(IMPORTANCE_DEFAULT)
                .modifyNotification(mContext)
                .setColorized(true).setColor(Color.WHITE);

        // THEN the entry is in the fgs section
        assertTrue(mFgsSection.isInSection(mEntryBuilder.build()));
    }

    @Test
    public void testDiscludeFGSInSection_importanceMin() {
        // GIVEN the notification represents a colorized foreground service with min importance
        mEntryBuilder
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, true)
                .setImportance(IMPORTANCE_MIN)
                .modifyNotification(mContext)
                .setColorized(true).setColor(Color.WHITE);

        // THEN the entry is NOT in the fgs section
        assertFalse(mFgsSection.isInSection(mEntryBuilder.build()));
    }

    @Test
    public void testDiscludeNonFGSInSection() {
        // GIVEN the notification represents a colorized notification with high importance that
        // is NOT a foreground service
        mEntryBuilder
                .setImportance(IMPORTANCE_HIGH)
                .setFlag(mContext, FLAG_FOREGROUND_SERVICE, false)
                .modifyNotification(mContext).setColorized(false);

        // THEN the entry is NOT in the fgs section
        assertFalse(mFgsSection.isInSection(mEntryBuilder.build()));
    }

    @Test
    public void testIncludeCallInSection_importanceDefault() {
        // GIVEN the notification represents a call with > min importance
        mEntryBuilder
                .setImportance(IMPORTANCE_DEFAULT)
                .modifyNotification(mContext)
                .setStyle(makeCallStyle());

        // THEN the entry is in the fgs section
        assertTrue(mFgsSection.isInSection(mEntryBuilder.build()));
    }

    @Test
    public void testDiscludeCallInSection_importanceMin() {
        // GIVEN the notification represents a call with min importance
        mEntryBuilder
                .setImportance(IMPORTANCE_MIN)
                .modifyNotification(mContext)
                .setStyle(makeCallStyle());

        // THEN the entry is NOT in the fgs section
        assertFalse(mFgsSection.isInSection(mEntryBuilder.build()));
    }

    private Notification.CallStyle makeCallStyle() {
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent("action"), PendingIntent.FLAG_IMMUTABLE);
        final Person person = new Person.Builder().setName("person").build();
        return Notification.CallStyle.forIncomingCall(person, pendingIntent, pendingIntent);
    }
}
