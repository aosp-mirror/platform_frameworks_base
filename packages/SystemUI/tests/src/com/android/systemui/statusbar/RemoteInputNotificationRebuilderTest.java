/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;

import android.app.Notification;
import android.app.RemoteInputHistoryItem;
import android.net.Uri;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class RemoteInputNotificationRebuilderTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;
    @Mock
    private ExpandableNotificationRow mRow;

    private RemoteInputNotificationRebuilder mRebuilder;
    private NotificationEntry mEntry;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mRebuilder = new RemoteInputNotificationRebuilder(mContext);
        mEntry = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setUid(TEST_UID)
                .setNotification(new Notification())
                .setUser(UserHandle.CURRENT)
                .build();
        mEntry.setRow(mRow);
    }

    @Test
    public void testRebuildWithRemoteInput_noExistingInput_image() {
        Uri uri = mock(Uri.class);
        String mimeType = "image/jpeg";
        String text = "image inserted";
        StatusBarNotification newSbn =
                mRebuilder.rebuildWithRemoteInputInserted(
                        mEntry, text, false, mimeType, uri);
        RemoteInputHistoryItem[] messages = (RemoteInputHistoryItem[]) newSbn.getNotification()
                .extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
        assertEquals(1, messages.length);
        assertEquals(text, messages[0].getText());
        assertEquals(mimeType, messages[0].getMimeType());
        assertEquals(uri, messages[0].getUri());
    }

    @Test
    public void testRebuildWithRemoteInput_noExistingInputNoSpinner() {
        StatusBarNotification newSbn =
                mRebuilder.rebuildWithRemoteInputInserted(
                        mEntry, "A Reply", false, null, null);
        RemoteInputHistoryItem[] messages = (RemoteInputHistoryItem[]) newSbn.getNotification()
                .extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
        assertEquals(1, messages.length);
        assertEquals("A Reply", messages[0].getText());
        assertFalse(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false));
        assertTrue(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_HIDE_SMART_REPLIES, false));
    }

    @Test
    public void testRebuildWithRemoteInput_noExistingInputWithSpinner() {
        StatusBarNotification newSbn =
                mRebuilder.rebuildWithRemoteInputInserted(
                        mEntry, "A Reply", true, null, null);
        RemoteInputHistoryItem[] messages = (RemoteInputHistoryItem[]) newSbn.getNotification()
                .extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
        assertEquals(1, messages.length);
        assertEquals("A Reply", messages[0].getText());
        assertTrue(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false));
        assertTrue(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_HIDE_SMART_REPLIES, false));
    }

    @Test
    public void testRebuildWithRemoteInput_withExistingInput() {
        // Setup a notification entry with 1 remote input.
        StatusBarNotification newSbn =
                mRebuilder.rebuildWithRemoteInputInserted(
                        mEntry, "A Reply", false, null, null);
        NotificationEntry entry = new NotificationEntryBuilder()
                .setSbn(newSbn)
                .build();

        // Try rebuilding to add another reply.
        newSbn = mRebuilder.rebuildWithRemoteInputInserted(
                entry, "Reply 2", true, null, null);
        RemoteInputHistoryItem[] messages = (RemoteInputHistoryItem[]) newSbn.getNotification()
                .extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
        assertEquals(2, messages.length);
        assertEquals("Reply 2", messages[0].getText());
        assertEquals("A Reply", messages[1].getText());
    }

    @Test
    public void testRebuildWithRemoteInput_withExistingInput_image() {
        // Setup a notification entry with 1 remote input.
        Uri uri = mock(Uri.class);
        String mimeType = "image/jpeg";
        String text = "image inserted";
        StatusBarNotification newSbn =
                mRebuilder.rebuildWithRemoteInputInserted(
                        mEntry, text, false, mimeType, uri);
        NotificationEntry entry = new NotificationEntryBuilder()
                .setSbn(newSbn)
                .build();

        // Try rebuilding to add another reply.
        newSbn = mRebuilder.rebuildWithRemoteInputInserted(
                entry, "Reply 2", true, null, null);
        RemoteInputHistoryItem[] messages = (RemoteInputHistoryItem[]) newSbn.getNotification()
                .extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
        assertEquals(2, messages.length);
        assertEquals("Reply 2", messages[0].getText());
        assertEquals(text, messages[1].getText());
        assertEquals(mimeType, messages[1].getMimeType());
        assertEquals(uri, messages[1].getUri());
    }

    @Test
    public void testRebuildNotificationForCanceledSmartReplies() {
        // Try rebuilding to remove spinner and hide buttons.
        StatusBarNotification newSbn =
                mRebuilder.rebuildForCanceledSmartReplies(mEntry);
        assertFalse(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_SHOW_REMOTE_INPUT_SPINNER, false));
        assertTrue(newSbn.getNotification().extras
                .getBoolean(Notification.EXTRA_HIDE_SMART_REPLIES, false));
    }
}
