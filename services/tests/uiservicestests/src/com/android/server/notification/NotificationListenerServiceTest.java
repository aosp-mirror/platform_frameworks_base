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

import static android.app.Notification.EXTRA_SMALL_ICON;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.SnoozeCriterion;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationListenerServiceTest extends UiServiceTestCase {

    int targetSdk = 0;

    @Before
    public void setUp() {
        targetSdk = mContext.getApplicationInfo().targetSdkVersion;
    }

    @After
    public void tearDown() {
        mContext.getApplicationInfo().targetSdkVersion = targetSdk;
    }

    @Test
    public void testGetActiveNotifications_notNull() throws Exception {
        TestListenerService service = new TestListenerService();
        INotificationManager noMan = service.getNoMan();
        when(noMan.getActiveNotificationsFromListener(any(), any(), anyInt())).thenReturn(null);

        assertNotNull(service.getActiveNotifications());
        assertNotNull(service.getActiveNotifications(NotificationListenerService.TRIM_FULL));
        assertNotNull(service.getActiveNotifications(new String[0]));
        assertNotNull(service.getActiveNotifications(
                new String[0], NotificationListenerService.TRIM_LIGHT));
    }

    @Test
    public void testRanking() {
        TestListenerService service = new TestListenerService();
        service.applyUpdateLocked(generateUpdate());
        for (int i = 0; i < mKeys.length; i++) {
            String key = mKeys[i];
            Ranking ranking = new Ranking();
            service.getCurrentRanking().getRanking(key, ranking);
            assertEquals(getVisibilityOverride(i), ranking.getLockscreenVisibilityOverride());
            assertEquals(getOverrideGroupKey(key), ranking.getOverrideGroupKey());
            assertEquals(!isIntercepted(i), ranking.matchesInterruptionFilter());
            assertEquals(getSuppressedVisualEffects(i), ranking.getSuppressedVisualEffects());
            assertEquals(getImportance(i), ranking.getImportance());
            assertEquals(getExplanation(key), ranking.getImportanceExplanation());
            assertEquals(getChannel(key, i), ranking.getChannel());
            assertEquals(getPeople(key, i), ranking.getAdditionalPeople());
            assertEquals(getSnoozeCriteria(key, i), ranking.getSnoozeCriteria());
            assertEquals(getShowBadge(i), ranking.canShowBadge());
            assertEquals(getUserSentiment(i), ranking.getUserSentiment());
            assertEquals(getHidden(i), ranking.isSuspended());
            assertEquals(lastAudiblyAlerted(i), ranking.getLastAudiblyAlertedMillis());
            assertActionsEqual(getSmartActions(key, i), ranking.getSmartActions());
            assertEquals(getSmartReplies(key, i), ranking.getSmartReplies());
            assertEquals(canBubble(i), ranking.canBubble());
            assertEquals(isTextChanged(i), ranking.isTextChanged());
            assertEquals(isConversation(i), ranking.isConversation());
            assertEquals(getShortcutInfo(i).getId(), ranking.getConversationShortcutInfo().getId());
            assertEquals(getRankingAdjustment(i), ranking.getRankingAdjustment());
        }
    }

    // Tests parceling of NotificationRankingUpdate, and by extension, RankingMap and Ranking.
    @Test
    public void testRankingUpdate_parcel() {
        NotificationRankingUpdate nru = generateUpdate();
        Parcel parcel = Parcel.obtain();
        nru.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        NotificationRankingUpdate nru1 = NotificationRankingUpdate.CREATOR.createFromParcel(parcel);
        assertEquals(nru, nru1);
    }

    // Tests parceling of RankingMap and RankingMap.equals
    @Test
    public void testRankingMap_parcel() {
        RankingMap rmap = generateUpdate().getRankingMap();
        Parcel parcel = Parcel.obtain();
        rmap.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        RankingMap rmap1 = RankingMap.CREATOR.createFromParcel(parcel);

        detailedAssertEquals(rmap, rmap1);
        assertEquals(rmap, rmap1);
    }

    // Tests parceling of Ranking and Ranking.equals
    @Test
    public void testRanking_parcel() {
        Ranking ranking = generateUpdate().getRankingMap().getRawRankingObject(mKeys[0]);
        Parcel parcel = Parcel.obtain();
        ranking.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        Ranking ranking1 = new Ranking(parcel);
        detailedAssertEquals("rankings differ: ", ranking, ranking1);
        assertEquals(ranking, ranking1);
    }

    // Tests NotificationRankingUpdate.equals(), and by extension, RankingMap and Ranking.
    @Test
    public void testRankingUpdate_equals() {
        NotificationRankingUpdate nru = generateUpdate();
        NotificationRankingUpdate nru2 = generateUpdate();
        detailedAssertEquals(nru, nru2);
        assertEquals(nru, nru2);
        Ranking tweak = nru2.getRankingMap().getRawRankingObject(mKeys[0]);
        tweak.populate(
                tweak.getKey(),
                tweak.getRank(),
                !tweak.matchesInterruptionFilter(), // note the inversion here!
                tweak.getLockscreenVisibilityOverride(),
                tweak.getSuppressedVisualEffects(),
                tweak.getImportance(),
                tweak.getImportanceExplanation(),
                tweak.getOverrideGroupKey(),
                tweak.getChannel(),
                (ArrayList) tweak.getAdditionalPeople(),
                (ArrayList) tweak.getSnoozeCriteria(),
                tweak.canShowBadge(),
                tweak.getUserSentiment(),
                tweak.isSuspended(),
                tweak.getLastAudiblyAlertedMillis(),
                tweak.isNoisy(),
                (ArrayList) tweak.getSmartActions(),
                (ArrayList) tweak.getSmartReplies(),
                tweak.canBubble(),
                tweak.isTextChanged(),
                tweak.isConversation(),
                tweak.getConversationShortcutInfo(),
                tweak.getRankingAdjustment(),
                tweak.isBubble(),
                tweak.getProposedImportance()
        );
        assertNotEquals(nru, nru2);
    }

    @Test
    public void testLegacyIcons_preM() {
        TestListenerService service = new TestListenerService();
        service.attachBaseContext(mContext);
        service.targetSdk = Build.VERSION_CODES.LOLLIPOP_MR1;

        Bitmap largeIcon = Bitmap.createBitmap(100, 200, Bitmap.Config.RGB_565);

        Notification n = new Notification.Builder(mContext, "channel")
                .setSmallIcon(android.R.drawable.star_on)
                .setLargeIcon(Icon.createWithBitmap(largeIcon))
                .setContentTitle("test")
                .build();

        service.createLegacyIconExtras(n);

        assertEquals(android.R.drawable.star_on, n.extras.getInt(EXTRA_SMALL_ICON));
        assertEquals(android.R.drawable.star_on, n.icon);
        assertNotNull(n.largeIcon);
        assertNotNull(n.extras.getParcelable(Notification.EXTRA_LARGE_ICON));
    }

    @Test
    public void testLegacyIcons_mPlus() {
        TestListenerService service = new TestListenerService();
        service.attachBaseContext(mContext);
        service.targetSdk = Build.VERSION_CODES.M;

        Bitmap largeIcon = Bitmap.createBitmap(100, 200, Bitmap.Config.RGB_565);

        Notification n = new Notification.Builder(mContext, "channel")
                .setSmallIcon(android.R.drawable.star_on)
                .setLargeIcon(Icon.createWithBitmap(largeIcon))
                .setContentTitle("test")
                .build();

        service.createLegacyIconExtras(n);

        assertEquals(0, n.extras.getInt(EXTRA_SMALL_ICON));
        assertNull(n.largeIcon);
    }


    // Test data

    private String[] mKeys = new String[] { "key", "key1", "key2", "key3", "key4"};

    private NotificationRankingUpdate generateUpdate() {
        Ranking[] rankings = new Ranking[mKeys.length];
        for (int i = 0; i < mKeys.length; i++) {
            final String key = mKeys[i];
            Ranking ranking = new Ranking();
            ranking.populate(
                    key,
                    i,
                    !isIntercepted(i),
                    getVisibilityOverride(i),
                    getSuppressedVisualEffects(i),
                    getImportance(i),
                    getExplanation(key),
                    getOverrideGroupKey(key),
                    getChannel(key, i),
                    getPeople(key, i),
                    getSnoozeCriteria(key, i),
                    getShowBadge(i),
                    getUserSentiment(i),
                    getHidden(i),
                    lastAudiblyAlerted(i),
                    getNoisy(i),
                    getSmartActions(key, i),
                    getSmartReplies(key, i),
                    canBubble(i),
                    isTextChanged(i),
                    isConversation(i),
                    getShortcutInfo(i),
                    getRankingAdjustment(i),
                    isBubble(i),
                    getProposedImportance(i)
            );
            rankings[i] = ranking;
        }
        NotificationRankingUpdate update = new NotificationRankingUpdate(rankings);
        return update;
    }

    private int getVisibilityOverride(int index) {
        return index * 9;
    }

    private String getOverrideGroupKey(String key) {
        return key + key;
    }

    private boolean isIntercepted(int index) {
        return index % 2 == 0;
    }

    private int getSuppressedVisualEffects(int index) {
        return index * 2;
    }

    private int getImportance(int index) {
        return index;
    }

    private String getExplanation(String key) {
        return key + "explain";
    }

    private NotificationChannel getChannel(String key, int index) {
        return new NotificationChannel(key, key, getImportance(index));
    }

    private boolean getShowBadge(int index) {
        return index % 3 == 0;
    }

    private int getUserSentiment(int index) {
        switch(index % 3) {
            case 0:
                return USER_SENTIMENT_NEGATIVE;
            case 1:
                return USER_SENTIMENT_NEUTRAL;
            case 2:
                return USER_SENTIMENT_POSITIVE;
        }
        return USER_SENTIMENT_NEUTRAL;
    }

    private boolean getHidden(int index) {
        return index % 2 == 0;
    }

    private long lastAudiblyAlerted(int index) {
        return index * 2000;
    }

    private boolean getNoisy(int index) {
        return index < 1;
    }

    private ArrayList<String> getPeople(String key, int index) {
        ArrayList<String> people = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            people.add(i + key);
        }
        return people;
    }

    private ArrayList<SnoozeCriterion> getSnoozeCriteria(String key, int index) {
        ArrayList<SnoozeCriterion> snooze = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            snooze.add(new SnoozeCriterion(key + i, getExplanation(key), key));
        }
        return snooze;
    }

    private ArrayList<Notification.Action> getSmartActions(String key, int index) {
        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            PendingIntent intent = PendingIntent.getBroadcast(
                    getContext(),
                    index /*requestCode*/,
                    new Intent("ACTION_" + key),
                    PendingIntent.FLAG_IMMUTABLE /*flags*/);
            actions.add(new Notification.Action.Builder(null /*icon*/, key, intent).build());
        }
        return actions;
    }

    private ArrayList<CharSequence> getSmartReplies(String key, int index) {
        ArrayList<CharSequence> choices = new ArrayList<>();
        for (int i = 0; i < index; i++) {
            choices.add("choice_" + key + "_" + i);
        }
        return choices;
    }

    private boolean canBubble(int index) {
        return index % 4 == 0;
    }

    private boolean isTextChanged(int index) {
        return index % 4 == 0;
    }

    private boolean isConversation(int index) {
        return index % 4 == 0;
    }

    private ShortcutInfo getShortcutInfo(int index) {
        ShortcutInfo si = new ShortcutInfo(
                index, String.valueOf(index), "packageName", new ComponentName("1", "1"), null,
                "title", 0, "titleResName", "text", 0, "textResName",
                "disabledMessage", 0, "disabledMessageResName",
                null, null, 0, null, 0, 0,
                0, "iconResName", "bitmapPath", null, 0,
                null, null, null, null);
        return si;
    }

    private int getRankingAdjustment(int index) {
        return index % 3 - 1;
    }

    private int getProposedImportance(int index) {
        return index % 5 - 1;
    }

    private boolean isBubble(int index) {
        return index % 4 == 0;
    }

    private void assertActionsEqual(
            List<Notification.Action> expecteds, List<Notification.Action> actuals) {
        assertEquals(expecteds.size(), actuals.size());
        for (int i = 0; i < expecteds.size(); i++) {
            Notification.Action expected = expecteds.get(i);
            Notification.Action actual = actuals.get(i);
            assertEquals(expected.title, actual.title);
        }
    }

    private void detailedAssertEquals(NotificationRankingUpdate a, NotificationRankingUpdate b) {
        assertEquals(a.getRankingMap(), b.getRankingMap());
    }

    private void detailedAssertEquals(String comment, Ranking a, Ranking b) {
        assertEquals(comment, a.getKey(), b.getKey());
        assertEquals(comment, a.getRank(), b.getRank());
        assertEquals(comment, a.matchesInterruptionFilter(), b.matchesInterruptionFilter());
        assertEquals(comment, a.getLockscreenVisibilityOverride(), b.getLockscreenVisibilityOverride());
        assertEquals(comment, a.getSuppressedVisualEffects(), b.getSuppressedVisualEffects());
        assertEquals(comment, a.getImportance(), b.getImportance());
        assertEquals(comment, a.getImportanceExplanation(), b.getImportanceExplanation());
        assertEquals(comment, a.getOverrideGroupKey(), b.getOverrideGroupKey());
        assertEquals(comment, a.getChannel(), b.getChannel());
        assertEquals(comment, a.getAdditionalPeople(), b.getAdditionalPeople());
        assertEquals(comment, a.getSnoozeCriteria(), b.getSnoozeCriteria());
        assertEquals(comment, a.canShowBadge(), b.canShowBadge());
        assertEquals(comment, a.getUserSentiment(), b.getUserSentiment());
        assertEquals(comment, a.isSuspended(), b.isSuspended());
        assertEquals(comment, a.getLastAudiblyAlertedMillis(), b.getLastAudiblyAlertedMillis());
        assertEquals(comment, a.isNoisy(), b.isNoisy());
        assertEquals(comment, a.getSmartReplies(), b.getSmartReplies());
        assertEquals(comment, a.canBubble(), b.canBubble());
        assertEquals(comment, a.isConversation(), b.isConversation());
        assertEquals(comment, a.getConversationShortcutInfo().getId(),
                b.getConversationShortcutInfo().getId());
        assertActionsEqual(a.getSmartActions(), b.getSmartActions());
        assertEquals(a.getProposedImportance(), b.getProposedImportance());
    }

    private void detailedAssertEquals(RankingMap a, RankingMap b) {
        Ranking arank = new Ranking();
        Ranking brank = new Ranking();
        assertArrayEquals(a.getOrderedKeys(), b.getOrderedKeys());
        for (String key : a.getOrderedKeys()) {
            a.getRanking(key, arank);
            b.getRanking(key, brank);
            detailedAssertEquals("ranking for key <" + key + ">", arank, brank);
        }
    }

    public static class TestListenerService extends NotificationListenerService {
        private final IBinder binder = new LocalBinder();
        public int targetSdk = 0;

        public TestListenerService() {
            mWrapper = mock(NotificationListenerWrapper.class);
            mNoMan = mock(INotificationManager.class);
        }

        INotificationManager getNoMan() {
            return mNoMan;
        }

        @Override
        public IBinder onBind(Intent intent) {
            super.onBind(intent);
            return binder;
        }

        public class LocalBinder extends Binder {
            TestListenerService getService() {
                return TestListenerService.this;
            }
        }

        @Override
        protected void attachBaseContext(Context base) {
            super.attachBaseContext(base);
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            ApplicationInfo info = super.getApplicationInfo();
            if (targetSdk != 0) {
                info.targetSdkVersion = targetSdk;
            }
            return info;
        }
    }
}
