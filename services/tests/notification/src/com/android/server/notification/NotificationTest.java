/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.notification;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Build;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationTest extends NotificationTestCase {

    @Mock
    ActivityManager mAm;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testStripsExtendersInLowRamMode() {
        Notification.Builder nb = new Notification.Builder(mContext, "channel");
        nb.extend(new Notification.CarExtender().setColor(Color.RED));
        nb.extend(new Notification.TvExtender().setChannelId("different channel"));
        nb.extend(new Notification.WearableExtender().setDismissalId("dismiss"));
        Notification before = nb.build();

        Notification after = Notification.Builder.maybeCloneStrippedForDelivery(before, true);

        assertEquals("different channel", new Notification.TvExtender(before).getChannelId());
        assertNull(new Notification.TvExtender(after).getChannelId());

        assertEquals(Color.RED, new Notification.CarExtender(before).getColor());
        assertEquals(Notification.COLOR_DEFAULT, new Notification.CarExtender(after).getColor());

        assertEquals("dismiss", new Notification.WearableExtender(before).getDismissalId());
        assertNull(new Notification.WearableExtender(after).getDismissalId());
    }

    @Test
    public void testStripsRemoteViewsInLowRamMode() {
        Context context = spy(getContext());
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.M;
        when(context.getApplicationInfo()).thenReturn(ai);

        final Notification.BigTextStyle style = new Notification.BigTextStyle()
                .bigText("hello")
                .setSummaryText("And the summary");
        Notification before = new Notification.Builder(context, "channel")
                .setContentText("hi")
                .setStyle(style)
                .build();

        Notification after = Notification.Builder.maybeCloneStrippedForDelivery(before, true);
        assertNotNull(before.contentView);
        assertNotNull(before.bigContentView);
        assertNotNull(before.headsUpContentView);
        assertNull(after.contentView);
        assertNull(after.bigContentView);
        assertNull(after.headsUpContentView);
    }

    @Test
    public void testDoesNotStripsExtendersInNormalRamMode() {
        Notification.Builder nb = new Notification.Builder(mContext, "channel");
        nb.extend(new Notification.CarExtender().setColor(Color.RED));
        nb.extend(new Notification.TvExtender().setChannelId("different channel"));
        nb.extend(new Notification.WearableExtender().setDismissalId("dismiss"));
        Notification before = nb.build();
        Notification after = Notification.Builder.maybeCloneStrippedForDelivery(before, false);

        assertTrue(before == after);

        assertEquals("different channel", new Notification.TvExtender(before).getChannelId());
        assertEquals(Color.RED, new Notification.CarExtender(before).getColor());
        assertEquals("dismiss", new Notification.WearableExtender(before).getDismissalId());
    }
}

