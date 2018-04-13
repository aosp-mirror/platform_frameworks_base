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

import static android.app.NotificationChannel.USER_LOCKED_IMPORTANCE;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking
        .USER_SENTIMENT_POSITIVE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.StatusBarNotification;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;


import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Objects;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationRecordTest extends UiServiceTestCase {

    private final Context mMockContext = Mockito.mock(Context.class);
    @Mock PackageManager mPm;

    private final String pkg = "com.android.server.notification";
    private final int uid = 9583;
    private final String pkg2 = "pkg2";
    private final int uid2 = 1111111;
    private final int id1 = 1;
    private final int id2 = 2;
    private final String tag1 = "tag1";
    private final String tag2 = "tag2";
    private final String channelId = "channel";
    NotificationChannel channel =
            new NotificationChannel(channelId, "test", NotificationManager.IMPORTANCE_DEFAULT);
    private final String channelIdLong =
            "give_a_developer_a_string_argument_and_who_knows_what_they_will_pass_in_there";
    final String groupId = "group";
    final String groupIdOverride = "other_group";
    private final String groupIdLong =
            "0|com.foo.bar|g:content://com.foo.bar.ui/account%3A-0000000/account/";
    NotificationChannel channelLongId =
            new NotificationChannel(channelIdLong, "long", NotificationManager.IMPORTANCE_DEFAULT);
    NotificationChannel defaultChannel =
            new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "test",
                    NotificationManager.IMPORTANCE_UNSPECIFIED);
    private android.os.UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());
    final ApplicationInfo legacy = new ApplicationInfo();
    final ApplicationInfo upgrade = new ApplicationInfo();

    private static final long[] CUSTOM_VIBRATION = new long[] {
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400 };
    private static final long[] CUSTOM_CHANNEL_VIBRATION = new long[] {300, 400, 300, 400 };
    private static final Uri CUSTOM_SOUND = Settings.System.DEFAULT_ALARM_ALERT_URI;
    private static final AudioAttributes CUSTOM_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build();
    private static final NotificationRecord.Light CUSTOM_LIGHT =
            new NotificationRecord.Light(1, 2, 3);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mMockContext.getResources()).thenReturn(getContext().getResources());
        when(mMockContext.getPackageManager()).thenReturn(mPm);

        legacy.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        upgrade.targetSdkVersion = Build.VERSION_CODES.O;
        try {
            when(mPm.getApplicationInfoAsUser(eq(pkg), anyInt(), anyInt())).thenReturn(legacy);
            when(mPm.getApplicationInfoAsUser(eq(pkg2), anyInt(), anyInt())).thenReturn(upgrade);
        } catch (PackageManager.NameNotFoundException e) {}
    }

    private StatusBarNotification getNotification(boolean preO, boolean noisy, boolean defaultSound,
            boolean buzzy, boolean defaultVibration, boolean lights, boolean defaultLights,
            String group) {
        when(mMockContext.getApplicationInfo()).thenReturn(preO ? legacy : upgrade);
        final Builder builder = new Builder(mMockContext)
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH);

        int defaults = 0;
        if (noisy) {
            if (defaultSound) {
                defaults |= Notification.DEFAULT_SOUND;
            } else {
                builder.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
                channel.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
            }
        } else {
            channel.setSound(null, null);
            builder.setSound(null, null);
        }
        if (buzzy) {
            if (defaultVibration) {
                defaults |= Notification.DEFAULT_VIBRATE;
            } else {
                builder.setVibrate(CUSTOM_VIBRATION);
                channel.setVibrationPattern(CUSTOM_CHANNEL_VIBRATION);
            }
        }
        if (lights) {
            if (defaultLights) {
                defaults |= Notification.DEFAULT_LIGHTS;
            } else {
                builder.setLights(CUSTOM_LIGHT.color, CUSTOM_LIGHT.onMs, CUSTOM_LIGHT.offMs);
                channel.setLightColor(Color.BLUE);
            }
            channel.enableLights(true);
        } else {
            channel.enableLights(false);
        }

        builder.setDefaults(defaults);
        if (!preO) {
            builder.setChannelId(channelId);
        }

        if(group != null) {
            builder.setGroup(group);
        }

        Notification n = builder.build();
        if (preO) {
            return new StatusBarNotification(pkg, pkg, id1, tag1, uid, uid, n,
                    mUser, null, uid);
        } else {
            return new StatusBarNotification(pkg2, pkg2, id2, tag2, uid2, uid2, n,
                    mUser, null, uid2);
        }
    }

    //
    // Tests
    //

    @Test
    public void testSound_default_preUpgradeUsesNotification() throws Exception {
        defaultChannel.setSound(null, null);
        // pre upgrade, default sound.
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, record.getSound());
        assertEquals(Notification.AUDIO_ATTRIBUTES_DEFAULT, record.getAudioAttributes());
    }

    @Test
    public void testSound_custom_preUpgradeUsesNotification() throws Exception {
        defaultChannel.setSound(null, null);
        // pre upgrade, custom sound.
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                false /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(CUSTOM_SOUND, record.getSound());
        assertEquals(CUSTOM_ATTRIBUTES, record.getAudioAttributes());
    }

    @Test
    public void testSound_default_userLocked_preUpgrade() throws Exception {
        defaultChannel.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
        defaultChannel.lockFields(NotificationChannel.USER_LOCKED_SOUND);
        // pre upgrade, default sound.
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(CUSTOM_SOUND, record.getSound());
        assertEquals(CUSTOM_ATTRIBUTES, record.getAudioAttributes());
    }

    @Test
    public void testSound_noSound_preUpgrade() throws Exception {
        // pre upgrade, default sound.
        StatusBarNotification sbn = getNotification(true /*preO */, false /* noisy */,
                false /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(null, record.getSound());
        assertEquals(Notification.AUDIO_ATTRIBUTES_DEFAULT, record.getAudioAttributes());
    }

    @Test
    public void testSound_default_upgradeUsesChannel() throws Exception {
        channel.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
        // post upgrade, default sound.
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(CUSTOM_SOUND, record.getSound());
        assertEquals(CUSTOM_ATTRIBUTES, record.getAudioAttributes());
    }

    @Test
    public void testVibration_default_preUpgradeUsesNotification() throws Exception {
        defaultChannel.enableVibration(false);
        // pre upgrade, default vibration.
        StatusBarNotification sbn = getNotification(true /*preO */, false /* noisy */,
                false /* defaultSound */, true /* buzzy */, true /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertNotNull(record.getVibration());
    }

    @Test
    public void testVibration_custom_preUpgradeUsesNotification() throws Exception {
        defaultChannel.enableVibration(false);
        // pre upgrade, custom vibration.
        StatusBarNotification sbn = getNotification(true /*preO */, false /* noisy */,
                false /* defaultSound */, true /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(CUSTOM_VIBRATION, record.getVibration());
    }

    @Test
    public void testVibration_custom_userLocked_preUpgrade() throws Exception {
        defaultChannel.enableVibration(true);
        defaultChannel.lockFields(NotificationChannel.USER_LOCKED_VIBRATION);
        // pre upgrade, custom vibration.
        StatusBarNotification sbn = getNotification(true /*preO */, false /* noisy */,
                false /* defaultSound */, true /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertTrue(!Objects.equals(CUSTOM_VIBRATION, record.getVibration()));
    }

    @Test
    public void testVibration_custom_upgradeUsesChannel() throws Exception {
        channel.enableVibration(true);
        // post upgrade, custom vibration.
        StatusBarNotification sbn = getNotification(false /*preO */, false /* noisy */,
                false /* defaultSound */, true /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(CUSTOM_CHANNEL_VIBRATION, record.getVibration());
    }

    @Test
    public void testImportance_preUpgrade() throws Exception {
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, record.getImportance());
    }

    @Test
    public void testImportance_locked_preUpgrade() throws Exception {
        defaultChannel.setImportance(NotificationManager.IMPORTANCE_LOW);
        defaultChannel.lockFields(USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(NotificationManager.IMPORTANCE_LOW, record.getImportance());
    }

    @Test
    public void testImportance_locked_unspecified_preUpgrade() throws Exception {
        defaultChannel.setImportance(NotificationManager.IMPORTANCE_UNSPECIFIED);
        defaultChannel.lockFields(USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, record.getImportance());
    }

    @Test
    public void testImportance_upgrade() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, record.getImportance());
    }

    @Test
    public void testLights_preUpgrade_noLight() throws Exception {
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertNull(record.getLight());
    }


    @Test
    public void testLights_preUpgrade() throws Exception {
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                true /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(CUSTOM_LIGHT, record.getLight());
    }

    @Test
    public void testLights_locked_preUpgrade() throws Exception {
        defaultChannel.enableLights(true);
        defaultChannel.lockFields(NotificationChannel.USER_LOCKED_LIGHTS);
        StatusBarNotification sbn = getNotification(true /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                true /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertFalse(CUSTOM_LIGHT.equals(record.getLight()));
    }

    @Test
    public void testLights_upgrade_defaultLights() throws Exception {
        int defaultLightColor = mMockContext.getResources().getColor(
                com.android.internal.R.color.config_defaultNotificationColor);
        int defaultLightOn = mMockContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOn);
        int defaultLightOff = mMockContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOff);

        NotificationRecord.Light expected = new NotificationRecord.Light(
                defaultLightColor, defaultLightOn, defaultLightOff);
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                true /* lights */, true /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(expected, record.getLight());
    }

    @Test
    public void testLights_upgrade() throws Exception {
        int defaultLightOn = mMockContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOn);
        int defaultLightOff = mMockContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOff);

        NotificationRecord.Light expected = new NotificationRecord.Light(
                Color.BLUE, defaultLightOn, defaultLightOff);
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                true /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(expected, record.getLight());
    }

    @Test
    public void testLights_upgrade_noLight() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertNull(record.getLight());
    }

    @Test
    public void testLogmakerShortChannel() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        final LogMaker logMaker = record.getLogMaker();
        assertEquals(channelId,
                (String) logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID));
        assertEquals(channel.getImportance(),
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE));
    }

    @Test
    public void testLogmakerLongChannel() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
        true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
        false /* lights */, false /*defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channelLongId);
        final String loggedId = (String)
            record.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID);
        assertEquals(channelIdLong.substring(0,10), loggedId.substring(0, 10));
    }

    @Test
    public void testLogmakerNoGroup() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /*defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertNull(record.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));
    }

    @Test
    public void testLogmakerShortGroup() throws Exception {
        StatusBarNotification sbn = getNotification(false /*reO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(groupId,
                record.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));
    }

    @Test
    public void testLogmakerLongGroup() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupIdLong /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        final String loggedId = (String)
                record.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID);
        assertEquals(groupIdLong.substring(0,10), loggedId.substring(0, 10));
    }

    @Test
    public void testLogmakerOverrideGroup() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(groupId,
                record.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));
        record.setOverrideGroupKey(groupIdOverride);
        assertEquals(groupIdOverride,
                record.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));
        record.setOverrideGroupKey(null);
        assertEquals(groupId,
                record.getLogMaker().getTaggedData(MetricsEvent.FIELD_NOTIFICATION_GROUP_ID));
    }

    @Test
    public void testNotificationStats() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse(record.getStats().hasSeen());
        assertFalse(record.isSeen());
        assertFalse(record.getStats().hasDirectReplied());
        assertFalse(record.getStats().hasExpanded());
        assertFalse(record.getStats().hasInteracted());
        assertFalse(record.getStats().hasViewedSettings());
        assertFalse(record.getStats().hasSnoozed());

        record.setSeen();
        assertTrue(record.getStats().hasSeen());
        assertTrue(record.isSeen());
        assertFalse(record.getStats().hasDirectReplied());
        assertFalse(record.getStats().hasExpanded());
        assertFalse(record.getStats().hasInteracted());
        assertFalse(record.getStats().hasViewedSettings());
        assertFalse(record.getStats().hasSnoozed());

        record.recordViewedSettings();
        assertFalse(record.getStats().hasDirectReplied());
        assertFalse(record.getStats().hasExpanded());
        assertTrue(record.getStats().hasViewedSettings());
        assertFalse(record.getStats().hasSnoozed());

        record.recordSnoozed();
        assertFalse(record.getStats().hasDirectReplied());
        assertFalse(record.getStats().hasExpanded());
        assertTrue(record.getStats().hasSnoozed());

        record.recordExpanded();
        assertFalse(record.getStats().hasDirectReplied());
        assertTrue(record.getStats().hasExpanded());

        record.recordDirectReplied();
        assertTrue(record.getStats().hasDirectReplied());
    }

    @Test
    public void testUserSentiment() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertEquals(USER_SENTIMENT_NEUTRAL, record.getUserSentiment());

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT, USER_SENTIMENT_NEGATIVE);
        record.addAdjustment(new Adjustment(pkg, record.getKey(), signals, null, sbn.getUserId()));

        record.applyAdjustments();

        assertEquals(USER_SENTIMENT_NEGATIVE, record.getUserSentiment());
    }

    @Test
    public void testUserSentiment_appImportanceUpdatesSentiment() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(USER_SENTIMENT_NEUTRAL, record.getUserSentiment());

        record.setIsAppImportanceLocked(true);
        assertEquals(USER_SENTIMENT_POSITIVE, record.getUserSentiment());
    }

    @Test
    public void testUserSentiment_appImportanceBlocksNegativeSentimentUpdate() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setIsAppImportanceLocked(true);

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT, USER_SENTIMENT_NEGATIVE);
        record.addAdjustment(new Adjustment(pkg, record.getKey(), signals, null, sbn.getUserId()));
        record.applyAdjustments();

        assertEquals(USER_SENTIMENT_POSITIVE, record.getUserSentiment());
    }

    @Test
    public void testUserSentiment_userLocked() throws Exception {
        channel.lockFields(USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertEquals(USER_SENTIMENT_POSITIVE, record.getUserSentiment());

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT, USER_SENTIMENT_NEGATIVE);
        record.addAdjustment(new Adjustment(pkg, record.getKey(), signals, null, sbn.getUserId()));

        record.applyAdjustments();

        assertEquals(USER_SENTIMENT_POSITIVE, record.getUserSentiment());
    }

    @Test
    public void testAppImportance_returnsCorrectly() throws Exception {
        StatusBarNotification sbn = getNotification(false /*preO */, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.setIsAppImportanceLocked(true);
        assertEquals(true, record.getIsAppImportanceLocked());

        record.setIsAppImportanceLocked(false);
        assertEquals(false, record.getIsAppImportanceLocked());
    }
}
