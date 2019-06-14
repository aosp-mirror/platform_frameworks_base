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

package com.android.systemui.bubbles;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class BubbleTest extends SysuiTestCase {
    @Mock
    private StatusBarNotification mStatusBarNotification;
    @Mock
    private Notification mNotif;

    private NotificationEntry mEntry;
    private Bubble mBubble;
    private Bundle mExtras;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mStatusBarNotification.getKey()).thenReturn("key");
        when(mStatusBarNotification.getNotification()).thenReturn(mNotif);
        when(mStatusBarNotification.getUser()).thenReturn(new UserHandle(0));
        mExtras = new Bundle();
        mNotif.extras = mExtras;
        mEntry = new NotificationEntry(mStatusBarNotification);

        mBubble = new Bubble(mContext, mEntry);
    }

    @Test
    public void testGetUpdateMessage_default() {
        final String msg = "Hello there!";
        doReturn(Notification.Style.class).when(mNotif).getNotificationStyle();
        mExtras.putCharSequence(Notification.EXTRA_TEXT, msg);
        assertEquals(msg, mBubble.getUpdateMessage(mContext));
    }

    @Test
    public void testGetUpdateMessage_bigText() {
        final String msg = "A big hello there!";
        doReturn(Notification.BigTextStyle.class).when(mNotif).getNotificationStyle();
        mExtras.putCharSequence(Notification.EXTRA_TEXT, "A small hello there.");
        mExtras.putCharSequence(Notification.EXTRA_BIG_TEXT, msg);

        // Should be big text, not the small text.
        assertEquals(msg, mBubble.getUpdateMessage(mContext));
    }

    @Test
    public void testGetUpdateMessage_media() {
        doReturn(Notification.MediaStyle.class).when(mNotif).getNotificationStyle();

        // Media notifs don't get update messages.
        assertNull(mBubble.getUpdateMessage(mContext));
    }

    @Test
    public void testGetUpdateMessage_inboxStyle() {
        doReturn(Notification.InboxStyle.class).when(mNotif).getNotificationStyle();
        mExtras.putCharSequenceArray(
                Notification.EXTRA_TEXT_LINES,
                new CharSequence[]{
                        "How do you feel about tests?",
                        "They're okay, I guess.",
                        "I hate when they're flaky.",
                        "Really? I prefer them that way."});

        // Should be the last one only.
        assertEquals("Really? I prefer them that way.", mBubble.getUpdateMessage(mContext));
    }

    @Test
    public void testGetUpdateMessage_messagingStyle() {
        doReturn(Notification.MessagingStyle.class).when(mNotif).getNotificationStyle();
        mExtras.putParcelableArray(
                Notification.EXTRA_MESSAGES,
                new Bundle[]{
                        new Notification.MessagingStyle.Message(
                                "Hello", 0, "Josh").toBundle(),
                        new Notification.MessagingStyle.Message(
                                "Oh, hello!", 0, "Mady").toBundle()});

        // Should be the last one only.
        assertEquals("Mady: Oh, hello!", mBubble.getUpdateMessage(mContext));
    }
}
