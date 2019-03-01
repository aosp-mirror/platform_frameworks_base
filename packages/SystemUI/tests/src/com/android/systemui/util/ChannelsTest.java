/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.ArraySet;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.NotificationChannels;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ChannelsTest extends SysuiTestCase {
    private final NotificationManager mMockNotificationManager = mock(NotificationManager.class);

    @Before
    public void setup() throws Exception {
        mContext.addMockSystemService(Context.NOTIFICATION_SERVICE, mMockNotificationManager);
    }

    @Test
    public void testChannelSetup() {
        Set<String> ALL_CHANNELS = new ArraySet<>(Arrays.asList(
                NotificationChannels.ALERTS,
                NotificationChannels.SCREENSHOTS_HEADSUP,
                NotificationChannels.STORAGE,
                NotificationChannels.GENERAL,
                NotificationChannels.BATTERY,
                NotificationChannels.HINTS
        ));
        NotificationChannels.createAll(mContext);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(mMockNotificationManager).createNotificationChannels(captor.capture());
        final List<NotificationChannel> list = captor.getValue();
        assertEquals(ALL_CHANNELS.size(), list.size());
        list.forEach((chan) -> assertTrue(ALL_CHANNELS.contains(chan.getId())));
    }

    @Test
    public void testChannelSetup_noLegacyScreenshot() {
        // Assert old channel cleaned up.
        // TODO: remove that code + this test after P.
        NotificationChannels.createAll(mContext);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(mMockNotificationManager).deleteNotificationChannel(
                NotificationChannels.SCREENSHOTS_LEGACY);
    }

    @Test
    public void testInheritFromLegacy_keepsUserLockedLegacySettings() {
        NotificationChannel legacyChannel = new NotificationChannel("id", "oldName",
                NotificationManager.IMPORTANCE_MIN);
        legacyChannel.setImportance(NotificationManager.IMPORTANCE_NONE);;
        legacyChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                legacyChannel.getAudioAttributes());
        legacyChannel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE |
                NotificationChannel.USER_LOCKED_SOUND);
        NotificationChannel newChannel =
                NotificationChannels.createScreenshotChannel("newName", legacyChannel);
        // NONE importance user locked, so don't use HIGH for new channel.
        assertEquals(NotificationManager.IMPORTANCE_NONE, newChannel.getImportance());
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, newChannel.getSound());
    }

    @Test
    public void testInheritFromLegacy_dropsUnlockedLegacySettings() {
        NotificationChannel legacyChannel = new NotificationChannel("id", "oldName",
                NotificationManager.IMPORTANCE_MIN);
        NotificationChannel newChannel =
                NotificationChannels.createScreenshotChannel("newName", legacyChannel);
        assertEquals(Uri.EMPTY, newChannel.getSound());
        assertEquals("newName", newChannel.getName());
        // MIN importance not user locked, so HIGH wins out.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, newChannel.getImportance());
    }

    @Test
    public void testInheritFromLegacy_noLegacyExists() {
        NotificationChannel newChannel =
                NotificationChannels.createScreenshotChannel("newName", null);
        assertEquals(Uri.EMPTY, newChannel.getSound());
        assertEquals("newName", newChannel.getName());
        assertEquals(NotificationManager.IMPORTANCE_HIGH, newChannel.getImportance());
    }

}
