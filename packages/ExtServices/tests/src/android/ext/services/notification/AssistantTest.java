/**
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
 * limitations under the License.
 */

package android.ext.services.notification;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Intent;
import android.ext.services.R;
import android.os.UserHandle;
import android.service.notification.Adjustment;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.test.ServiceTestCase;
import android.testing.TestableContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AssistantTest extends ServiceTestCase<Assistant> {

    private static final String PKG1 = "pkg1";
    private static final int UID1 = 1;
    private static final NotificationChannel P1C1 =
            new NotificationChannel("one", "", IMPORTANCE_LOW);
    private static final NotificationChannel P1C2 =
            new NotificationChannel("p1c2", "", IMPORTANCE_DEFAULT);
    private static final NotificationChannel P1C3 =
            new NotificationChannel("p1c3", "", IMPORTANCE_MIN);
    private static final String PKG2 = "pkg2";

    private static final int UID2 = 2;
    private static final NotificationChannel P2C1 =
            new NotificationChannel("one", "", IMPORTANCE_LOW);

    @Mock INotificationManager mNoMan;

    Assistant mAssistant;

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    public AssistantTest() {
        super(Assistant.class);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Intent startIntent =
                new Intent("android.service.notification.NotificationAssistantService");
        startIntent.setPackage("android.ext.services");
        bindService(startIntent);
        mAssistant = getService();
        mAssistant.setNoMan(mNoMan);
    }

    private StatusBarNotification generateSbn(String pkg, int uid, NotificationChannel channel,
            String tag, String groupKey) {
        Notification n = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setGroup(groupKey)
                .build();

        StatusBarNotification sbn = new StatusBarNotification(pkg, pkg, 0, tag, uid, uid, n,
                UserHandle.SYSTEM, null, 0);

        return sbn;
    }

    private Ranking generateRanking(StatusBarNotification sbn, NotificationChannel channel) {
        Ranking mockRanking = mock(Ranking.class);
        when(mockRanking.getChannel()).thenReturn(channel);
        when(mockRanking.getImportance()).thenReturn(channel.getImportance());
        when(mockRanking.getKey()).thenReturn(sbn.getKey());
        when(mockRanking.getOverrideGroupKey()).thenReturn(null);
        return mockRanking;
    }

    private void almostBlockChannel(String pkg, int uid, NotificationChannel channel) {
        for (int i = 0; i < ChannelImpressions.STREAK_LIMIT; i++) {
            dismissBadNotification(pkg, uid, channel, String.valueOf(i));
        }
    }

    private void dismissBadNotification(String pkg, int uid, NotificationChannel channel,
            String tag) {
        StatusBarNotification sbn = generateSbn(pkg, uid, channel, tag, null);
        mAssistant.setFakeRanking(generateRanking(sbn, channel));
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));
        mAssistant.setFakeRanking(mock(Ranking.class));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_SHADE);
        stats.setSeen();
        mAssistant.onNotificationRemoved(
                sbn, mock(RankingMap.class), stats, NotificationListenerService.REASON_CANCEL);
    }

    @Test
    public void testNoAdjustmentForInitialPost() throws Exception {
        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, null, null);

        mAssistant.setFakeRanking(generateRanking(sbn, P1C1));
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }

    @Test
    public void testTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);
        dismissBadNotification(PKG1, UID1, P1C1, "trigger!");

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "new one!", null);
        mAssistant.setFakeRanking(generateRanking(sbn, P1C1));
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        ArgumentCaptor<Adjustment> captor = ArgumentCaptor.forClass(Adjustment.class);
        verify(mNoMan, times(1)).applyAdjustmentFromAssistant(any(), captor.capture());
        assertEquals(sbn.getKey(), captor.getValue().getKey());
        assertEquals(Ranking.USER_SENTIMENT_NEGATIVE,
                captor.getValue().getSignals().getInt(Adjustment.KEY_USER_SENTIMENT));
    }

    @Test
    public void testMinCannotTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C3);
        dismissBadNotification(PKG1, UID1, P1C3, "trigger!");

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C3, "new one!", null);
        mAssistant.setFakeRanking(generateRanking(sbn, P1C3));
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }

    @Test
    public void testGroupCannotTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "no", "I HAVE A GROUP");
        mAssistant.setFakeRanking(mock(Ranking.class));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_SHADE);
        stats.setSeen();
        mAssistant.onNotificationRemoved(
                sbn, mock(RankingMap.class), stats, NotificationListenerService.REASON_CANCEL);

        sbn = generateSbn(PKG1, UID1, P1C1, "new one!", null);
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }

    @Test
    public void testAodCannotTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "no", null);
        mAssistant.setFakeRanking(mock(Ranking.class));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_AOD);
        stats.setSeen();
        mAssistant.onNotificationRemoved(
                sbn, mock(RankingMap.class), stats, NotificationListenerService.REASON_CANCEL);

        sbn = generateSbn(PKG1, UID1, P1C1, "new one!", null);
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }

    @Test
    public void testInteractedCannotTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "no", null);
        mAssistant.setFakeRanking(mock(Ranking.class));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_SHADE);
        stats.setSeen();
        stats.setExpanded();
        mAssistant.onNotificationRemoved(
                sbn, mock(RankingMap.class), stats, NotificationListenerService.REASON_CANCEL);

        sbn = generateSbn(PKG1, UID1, P1C1, "new one!", null);
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }

    @Test
    public void testAppDismissedCannotTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "no", null);
        mAssistant.setFakeRanking(mock(Ranking.class));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_SHADE);
        stats.setSeen();
        mAssistant.onNotificationRemoved(
                sbn, mock(RankingMap.class), stats, NotificationListenerService.REASON_APP_CANCEL);

        sbn = generateSbn(PKG1, UID1, P1C1, "new one!", null);
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }

    @Test
    public void testAppSeparation() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);
        dismissBadNotification(PKG1, UID1, P1C1, "trigger!");

        StatusBarNotification sbn = generateSbn(PKG2, UID2, P2C1, "new app!", null);
        mAssistant.setFakeRanking(generateRanking(sbn, P2C1));
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }

    @Test
    public void testChannelSeparation() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);
        dismissBadNotification(PKG1, UID1, P1C1, "trigger!");

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C2, "new app!", null);
        mAssistant.setFakeRanking(generateRanking(sbn, P1C2));
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }
}
