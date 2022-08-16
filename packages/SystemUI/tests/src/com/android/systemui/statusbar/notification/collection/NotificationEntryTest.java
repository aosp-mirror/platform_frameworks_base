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

package com.android.systemui.statusbar.notification.collection;

import static android.app.Notification.CATEGORY_ALARM;
import static android.app.Notification.CATEGORY_CALL;
import static android.app.Notification.CATEGORY_EVENT;
import static android.app.Notification.CATEGORY_MESSAGE;
import static android.app.Notification.CATEGORY_REMINDER;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;

import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;
import static com.android.systemui.statusbar.NotificationEntryHelper.modifySbn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.ArrayList;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class NotificationEntryTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test";
    private static final int TEST_UID = 0;
    private static final int UID_NORMAL = 123;
    private static final NotificationChannel NOTIFICATION_CHANNEL =
            new NotificationChannel("id", "name", NotificationChannel.USER_LOCKED_IMPORTANCE);

    private int mId;

    private NotificationEntry mEntry;
    private NotificationChannel mChannel = Mockito.mock(NotificationChannel.class);
    private final FakeSystemClock mClock = new FakeSystemClock();

    @Before
    public void setup() {
        Notification.Builder n = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");

        mEntry = new NotificationEntryBuilder()
                .setPkg(TEST_PACKAGE_NAME)
                .setOpPkg(TEST_PACKAGE_NAME)
                .setUid(TEST_UID)
                .setChannel(mChannel)
                .setId(mId++)
                .setNotification(n.build())
                .setUser(new UserHandle(ActivityManager.getCurrentUser()))
                .build();

        doReturn(false).when(mChannel).isBlockable();
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_foreground() {
        mEntry.getSbn().getNotification().flags = Notification.FLAG_FOREGROUND_SERVICE;

        assertTrue(mEntry.isExemptFromDndVisualSuppression());
        assertFalse(mEntry.shouldSuppressAmbient());
    }

    @Test
    public void testBlockableEntryWhenCritical() {
        doReturn(true).when(mChannel).isBlockable();

        assertTrue(mEntry.isBlockable());
    }


    @Test
    public void testBlockableEntryWhenCriticalAndChannelNotBlockable() {
        doReturn(true).when(mChannel).isBlockable();
        doReturn(true).when(mChannel).isImportanceLockedByCriticalDeviceFunction();

        assertTrue(mEntry.isBlockable());
    }

    @Test
    public void testNonBlockableEntryWhenCriticalAndChannelNotBlockable() {
        doReturn(false).when(mChannel).isBlockable();
        doReturn(true).when(mChannel).isImportanceLockedByCriticalDeviceFunction();

        assertFalse(mEntry.isBlockable());
    }

    @Test
    public void testBlockableWhenEntryHasNoChannel() {
        StatusBarNotification sbn = new SbnBuilder().build();
        Ranking ranking = new RankingBuilder()
                .setChannel(null)
                .setKey(sbn.getKey())
                .build();

        NotificationEntry entry =
                new NotificationEntry(sbn, ranking, mClock.uptimeMillis());

        assertFalse(entry.isBlockable());
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_media() {
        Notification.Builder n = new Notification.Builder(mContext, "")
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mock(MediaSession.Token.class)))
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");
        NotificationEntry e1 = new NotificationEntryBuilder()
                .setNotification(n.build())
                .build();

        assertTrue(e1.isExemptFromDndVisualSuppression());
        assertFalse(e1.shouldSuppressAmbient());
    }

    @Test
    public void testIsExemptFromDndVisualSuppression_system() {
        doReturn(true).when(mChannel).isImportanceLockedByCriticalDeviceFunction();
        doReturn(false).when(mChannel).isBlockable();

        assertTrue(mEntry.isExemptFromDndVisualSuppression());
        assertFalse(mEntry.shouldSuppressAmbient());
    }

    @Test
    public void testIsNotExemptFromDndVisualSuppression_hiddenCategories() {
        NotificationEntry entry = new NotificationEntryBuilder()
                .setUid(UID_NORMAL)
                .build();
        doReturn(true).when(mChannel).isImportanceLockedByCriticalDeviceFunction();
        modifyRanking(entry).setSuppressedVisualEffects(SUPPRESSED_EFFECT_AMBIENT).build();

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "").setCategory(CATEGORY_CALL).build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());
        assertTrue(entry.shouldSuppressAmbient());

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "")
                                .setCategory(CATEGORY_REMINDER)
                                .build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "").setCategory(CATEGORY_ALARM).build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "").setCategory(CATEGORY_EVENT).build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());

        modifySbn(entry)
                .setNotification(
                        new Notification.Builder(mContext, "")
                                .setCategory(CATEGORY_MESSAGE)
                                .build())
                .build();
        assertFalse(entry.isExemptFromDndVisualSuppression());
    }

    @Test
    public void testCreateNotificationDataEntry_RankingUpdate() {
        StatusBarNotification sbn = new SbnBuilder().build();
        sbn.getNotification().actions =
                new Notification.Action[]{createContextualAction("appGeneratedAction")};

        ArrayList<Notification.Action> systemGeneratedSmartActions =
                createActions("systemGeneratedAction");

        SnoozeCriterion snoozeCriterion = new SnoozeCriterion("id", "explanation", "confirmation");
        ArrayList<SnoozeCriterion> snoozeCriterions = new ArrayList<>();
        snoozeCriterions.add(snoozeCriterion);

        Ranking ranking = new RankingBuilder()
                .setKey(sbn.getKey())
                .setSmartActions(systemGeneratedSmartActions)
                .setChannel(NOTIFICATION_CHANNEL)
                .setUserSentiment(Ranking.USER_SENTIMENT_NEGATIVE)
                .setSnoozeCriteria(snoozeCriterions)
                .build();

        NotificationEntry entry =
                new NotificationEntry(sbn, ranking, mClock.uptimeMillis());

        assertEquals(systemGeneratedSmartActions, entry.getSmartActions());
        assertEquals(NOTIFICATION_CHANNEL, entry.getChannel());
        assertEquals(Ranking.USER_SENTIMENT_NEGATIVE, entry.getUserSentiment());
        assertEquals(snoozeCriterions, entry.getSnoozeCriteria());
    }

    @Test
    public void notificationDataEntry_testIsLastMessageFromReply() {
        Person.Builder person = new Person.Builder()
                .setName("name")
                .setKey("abc")
                .setUri("uri")
                .setBot(true);

        // EXTRA_MESSAGING_PERSON is the same Person as the sender in last message in EXTRA_MESSAGES
        Bundle bundle = new Bundle();
        bundle.putParcelable(Notification.EXTRA_MESSAGING_PERSON, person.build());
        Bundle[] messagesBundle = new Bundle[]{new Notification.MessagingStyle.Message(
                "text", 0, person.build()).toBundle()};
        bundle.putParcelableArray(Notification.EXTRA_MESSAGES, messagesBundle);

        Notification notification = new Notification.Builder(mContext, "test")
                .addExtras(bundle)
                .build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(notification)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();
        entry.setHasSentReply();

        assertTrue(entry.isLastMessageFromReply());
    }

    @Test
    public void notificationDataEntry_testIsLastMessageFromReply_invalidPerson_noCrash() {
        Person.Builder person = new Person.Builder()
                .setName("name")
                .setKey("abc")
                .setUri("uri")
                .setBot(true);

        Bundle bundle = new Bundle();
        // should be Person.class
        bundle.putParcelable(Notification.EXTRA_MESSAGING_PERSON, new Bundle());
        Bundle[] messagesBundle = new Bundle[]{new Notification.MessagingStyle.Message(
                "text", 0, person.build()).toBundle()};
        bundle.putParcelableArray(Notification.EXTRA_MESSAGES, messagesBundle);

        Notification notification = new Notification.Builder(mContext, "test")
                .addExtras(bundle)
                .build();

        NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg("pkg")
                .setOpPkg("pkg")
                .setTag("tag")
                .setNotification(notification)
                .setUser(mContext.getUser())
                .setOverrideGroupKey("")
                .build();
        entry.setHasSentReply();

        entry.isLastMessageFromReply();

        // no crash, good
    }


    private Notification.Action createContextualAction(String title) {
        return new Notification.Action.Builder(
                Icon.createWithResource(getContext(), android.R.drawable.sym_def_app_icon),
                title,
                PendingIntent.getBroadcast(getContext(), 0, new Intent("Action"),
                    PendingIntent.FLAG_IMMUTABLE))
                .setContextual(true)
                .build();
    }

    private Notification.Action createAction(String title) {
        return new Notification.Action.Builder(
                Icon.createWithResource(getContext(), android.R.drawable.sym_def_app_icon),
                title,
                PendingIntent.getBroadcast(getContext(), 0, new Intent("Action"),
                    PendingIntent.FLAG_IMMUTABLE)).build();
    }

    private ArrayList<Notification.Action> createActions(String... titles) {
        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (String title : titles) {
            actions.add(createAction(title));
        }
        return actions;
    }
}
