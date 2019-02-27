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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Build;
import android.os.UserHandle;
import android.service.notification.Adjustment;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.test.ServiceTestCase;
import android.testing.TestableContext;
import android.util.AtomicFile;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.FastXmlSerializer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

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
    @Mock AtomicFile mFile;
    @Mock IPackageManager mPackageManager;
    @Mock SmsHelper mSmsHelper;

    Assistant mAssistant;
    Application mApplication;

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

        mApplication = (Application) InstrumentationRegistry.getInstrumentation().
                getTargetContext().getApplicationContext();
        // Force the test to use the correct application instead of trying to use a mock application
        setApplication(mApplication);

        setupService();
        mAssistant = getService();

        // Override the AssistantSettings factory.
        mAssistant.mSettingsFactory = AssistantSettings::createForTesting;

        bindService(startIntent);

        mAssistant.mSettings.mDismissToViewRatioLimit = 0.8f;
        mAssistant.mSettings.mStreakLimit = 2;
        mAssistant.mSettings.mNewInterruptionModel = true;
        mAssistant.setNoMan(mNoMan);
        mAssistant.setFile(mFile);
        mAssistant.setPackageManager(mPackageManager);

        ApplicationInfo info = mock(ApplicationInfo.class);
        when(mPackageManager.getApplicationInfo(anyString(), anyInt(), anyInt()))
                .thenReturn(info);
        info.targetSdkVersion = Build.VERSION_CODES.P;
        when(mFile.startWrite()).thenReturn(mock(FileOutputStream.class));
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
        for (int i = 0; i < ChannelImpressions.DEFAULT_STREAK_LIMIT; i++) {
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
    public void testGroupChildCanTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "no", "I HAVE A GROUP");
        mAssistant.setFakeRanking(generateRanking(sbn, P1C1));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_SHADE);
        stats.setSeen();
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));
        mAssistant.onNotificationRemoved(
                sbn, mock(RankingMap.class), stats, NotificationListenerService.REASON_CANCEL);

        sbn = generateSbn(PKG1, UID1, P1C1, "new one!", "group");
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        ArgumentCaptor<Adjustment> captor = ArgumentCaptor.forClass(Adjustment.class);
        verify(mNoMan, times(1)).applyAdjustmentFromAssistant(any(), captor.capture());
        assertEquals(sbn.getKey(), captor.getValue().getKey());
        assertEquals(Ranking.USER_SENTIMENT_NEGATIVE,
                captor.getValue().getSignals().getInt(Adjustment.KEY_USER_SENTIMENT));
    }

    @Test
    public void testGroupSummaryCannotTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "no", "I HAVE A GROUP");
        sbn.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;
        mAssistant.setFakeRanking(generateRanking(sbn, P1C1));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_SHADE);
        stats.setSeen();
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));
        mAssistant.onNotificationRemoved(
                sbn, mock(RankingMap.class), stats, NotificationListenerService.REASON_CANCEL);

        sbn = generateSbn(PKG1, UID1, P1C1, "new one!", "group");
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        verify(mNoMan, never()).applyAdjustmentFromAssistant(any(), any());
    }

    @Test
    public void testAodCannotTriggerAdjustment() throws Exception {
        almostBlockChannel(PKG1, UID1, P1C1);

        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "no", null);
        mAssistant.setFakeRanking(generateRanking(sbn, P1C1));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_AOD);
        stats.setSeen();
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));
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
        mAssistant.setFakeRanking(generateRanking(sbn, P1C1));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_SHADE);
        stats.setSeen();
        stats.setExpanded();
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));
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
        mAssistant.setFakeRanking(generateRanking(sbn, P1C1));
        NotificationStats stats = new NotificationStats();
        stats.setDismissalSurface(NotificationStats.DISMISSAL_SHADE);
        stats.setSeen();
        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));
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

    @Test
    public void testReadXml() throws Exception {
        String key1 = mAssistant.getKey("pkg1", 1, "channel1");
        int streak1 = 2;
        int views1 = 5;
        int dismiss1 = 9;

        int streak1a = 3;
        int views1a = 10;
        int dismiss1a = 99;
        String key1a = mAssistant.getKey("pkg1", 1, "channel1a");

        int streak2 = 7;
        int views2 = 77;
        int dismiss2 = 777;
        String key2 = mAssistant.getKey("pkg2", 2, "channel2");

        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<assistant version=\"1\">\n"
                + "<impression-set key=\"" + key1 + "\" "
                + "dismisses=\"" + dismiss1 + "\" views=\"" + views1
                + "\" streak=\"" + streak1 + "\"/>\n"
                + "<impression-set key=\"" + key1a + "\" "
                + "dismisses=\"" + dismiss1a + "\" views=\"" + views1a
                + "\" streak=\"" + streak1a + "\"/>\n"
                + "<impression-set key=\"" + key2 + "\" "
                + "dismisses=\"" + dismiss2 + "\" views=\"" + views2
                + "\" streak=\"" + streak2 + "\"/>\n"
                + "</assistant>\n";
        mAssistant.readXml(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())));

        ChannelImpressions c1 = mAssistant.getImpressions(key1);
        assertEquals(2, c1.getStreak());
        assertEquals(5, c1.getViews());
        assertEquals(9, c1.getDismissals());

        ChannelImpressions c1a = mAssistant.getImpressions(key1a);
        assertEquals(3, c1a.getStreak());
        assertEquals(10, c1a.getViews());
        assertEquals(99, c1a.getDismissals());

        ChannelImpressions c2 = mAssistant.getImpressions(key2);
        assertEquals(7, c2.getStreak());
        assertEquals(77, c2.getViews());
        assertEquals(777, c2.getDismissals());
    }

    @Test
    public void testRoundTripXml() throws Exception {
        String key1 = mAssistant.getKey("pkg1", 1, "channel1");
        ChannelImpressions ci1 = new ChannelImpressions();
        String key2 = mAssistant.getKey("pkg1", 1, "channel2");
        ChannelImpressions ci2 = new ChannelImpressions();
        for (int i = 0; i < 3; i++) {
            ci2.incrementViews();
            ci2.incrementDismissals();
        }
        ChannelImpressions ci3 = new ChannelImpressions();
        String key3 = mAssistant.getKey("pkg3", 3, "channel2");
        for (int i = 0; i < 9; i++) {
            ci3.incrementViews();
            if (i % 3 == 0) {
                ci3.incrementDismissals();
            }
        }

        mAssistant.insertImpressions(key1, ci1);
        mAssistant.insertImpressions(key2, ci2);
        mAssistant.insertImpressions(key3, ci3);

        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        mAssistant.writeXml(serializer);

        Assistant assistant = new Assistant();
        // onCreate is not invoked, so settings won't be initialised, unless we do it here.
        assistant.mSettings = mAssistant.mSettings;
        assistant.readXml(new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())));

        assertEquals(ci1, assistant.getImpressions(key1));
        assertEquals(ci2, assistant.getImpressions(key2));
        assertEquals(ci3, assistant.getImpressions(key3));
    }

    @Test
    public void testSettingsProviderUpdate() {
        // Set up channels
        String key = mAssistant.getKey("pkg1", 1, "channel1");
        ChannelImpressions ci = new ChannelImpressions();
        for (int i = 0; i < 3; i++) {
            ci.incrementViews();
            if (i % 2 == 0) {
                ci.incrementDismissals();
            }
        }

        mAssistant.insertImpressions(key, ci);

        // With default values, the blocking helper shouldn't be triggered.
        assertEquals(false, ci.shouldTriggerBlock());

        // Update settings values.
        mAssistant.mSettings.mDismissToViewRatioLimit = 0f;
        mAssistant.mSettings.mStreakLimit = 0;

        // Notify for the settings values we updated.
        mAssistant.mSettings.mOnUpdateRunnable.run();

        // With the new threshold, the blocking helper should be triggered.
        assertEquals(true, ci.shouldTriggerBlock());
    }

    @Test
    public void testTrimLiveNotifications() {
        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C1, "no", null);
        mAssistant.setFakeRanking(generateRanking(sbn, P1C1));

        mAssistant.onNotificationPosted(sbn, mock(RankingMap.class));

        assertTrue(mAssistant.mLiveNotifications.containsKey(sbn.getKey()));

        mAssistant.onNotificationRemoved(
                sbn, mock(RankingMap.class), new NotificationStats(), 0);

        assertFalse(mAssistant.mLiveNotifications.containsKey(sbn.getKey()));
    }

    @Test
    public void testAssistantNeverIncreasesImportanceWhenSuggestingSilent() throws Exception {
        StatusBarNotification sbn = generateSbn(PKG1, UID1, P1C3, "min notif!", null);
        Adjustment adjust = mAssistant.createEnqueuedNotificationAdjustment(new NotificationEntry(
                mPackageManager, sbn, P1C3, mSmsHelper), new ArrayList<>(), new ArrayList<>());
        assertEquals(IMPORTANCE_MIN, adjust.getSignals().getInt(Adjustment.KEY_IMPORTANCE));
    }
}
