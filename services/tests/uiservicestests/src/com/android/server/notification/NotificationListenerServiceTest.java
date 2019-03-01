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

import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_POSITIVE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.SnoozeCriterion;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationListenerServiceTest extends UiServiceTestCase {

    private String[] mKeys = new String[] { "key", "key1", "key2", "key3"};

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
    public void testRanking() throws Exception {
        TestListenerService service = new TestListenerService();
        service.applyUpdateLocked(generateUpdate());
        for (int i = 0; i < mKeys.length; i++) {
            String key = mKeys[i];
            Ranking ranking = new Ranking();
            service.getCurrentRanking().getRanking(key, ranking);
            assertEquals(getVisibilityOverride(i), ranking.getVisibilityOverride());
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
        }
    }

    private NotificationRankingUpdate generateUpdate() {
        List<String> interceptedKeys = new ArrayList<>();
        Bundle visibilityOverrides = new Bundle();
        Bundle overrideGroupKeys = new Bundle();
        Bundle suppressedVisualEffects = new Bundle();
        Bundle explanation = new Bundle();
        Bundle channels = new Bundle();
        Bundle overridePeople = new Bundle();
        Bundle snoozeCriteria = new Bundle();
        Bundle showBadge = new Bundle();
        int[] importance = new int[mKeys.length];
        Bundle userSentiment = new Bundle();
        Bundle mHidden = new Bundle();

        for (int i = 0; i < mKeys.length; i++) {
            String key = mKeys[i];
            visibilityOverrides.putInt(key, getVisibilityOverride(i));
            overrideGroupKeys.putString(key, getOverrideGroupKey(key));
            if (isIntercepted(i)) {
                interceptedKeys.add(key);
            }
            suppressedVisualEffects.putInt(key, getSuppressedVisualEffects(i));
            importance[i] = getImportance(i);
            explanation.putString(key, getExplanation(key));
            channels.putParcelable(key, getChannel(key, i));
            overridePeople.putStringArrayList(key, getPeople(key, i));
            snoozeCriteria.putParcelableArrayList(key, getSnoozeCriteria(key, i));
            showBadge.putBoolean(key, getShowBadge(i));
            userSentiment.putInt(key, getUserSentiment(i));
            mHidden.putBoolean(key, getHidden(i));
        }
        NotificationRankingUpdate update = new NotificationRankingUpdate(mKeys,
                interceptedKeys.toArray(new String[0]), visibilityOverrides,
                suppressedVisualEffects, importance, explanation, overrideGroupKeys,
                channels, overridePeople, snoozeCriteria, showBadge, userSentiment, mHidden);
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

    public static class TestListenerService extends NotificationListenerService {
        private final IBinder binder = new LocalBinder();

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
    }
}
