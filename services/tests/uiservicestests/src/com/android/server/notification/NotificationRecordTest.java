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
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.StatusBarNotification;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.UiServiceTestCase;
import com.android.server.uri.UriGrantsManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationRecordTest extends UiServiceTestCase {

    private final Context mMockContext = mock(Context.class);
    @Mock private PackageManager mPm;

    private final String pkg = PKG_N_MR1;
    private final int uid = 9583;
    private final int id1 = 1;
    private final String tag1 = "tag1";
    private final String channelId = "channel";
    private NotificationChannel channel =
            new NotificationChannel(channelId, "test", NotificationManager.IMPORTANCE_DEFAULT);
    private final String groupId = "group";
    private NotificationChannel defaultChannel =
            new NotificationChannel(NotificationChannel.DEFAULT_CHANNEL_ID, "test",
                    NotificationManager.IMPORTANCE_UNSPECIFIED);
    private android.os.UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());

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
        when(mMockContext.getApplicationInfo()).thenReturn(new ApplicationInfo());
    }

    private StatusBarNotification getNotification(String pkg, boolean noisy, boolean defaultSound,
            boolean buzzy, boolean defaultVibration, boolean lights, boolean defaultLights,
            String group) {
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
        builder.setChannelId(channelId);

        if(group != null) {
            builder.setGroup(group);
        }

        Notification n = builder.build();
        return new StatusBarNotification(pkg, pkg, id1, tag1, uid, uid, n, mUser, null, uid);
    }

    //
    // Tests
    //

    @Test
    public void testSound_default_preUpgradeUsesNotification() {
        defaultChannel.setSound(null, null);
        // pre upgrade, default sound.
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, record.getSound());
        assertEquals(Notification.AUDIO_ATTRIBUTES_DEFAULT, record.getAudioAttributes());
    }

    @Test
    public void testSound_custom_preUpgradeUsesNotification() {
        defaultChannel.setSound(null, null);
        // pre upgrade, custom sound.
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                false /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(CUSTOM_SOUND, record.getSound());
        assertEquals(CUSTOM_ATTRIBUTES, record.getAudioAttributes());
    }

    @Test
    public void testSound_default_userLocked_preUpgrade() {
        defaultChannel.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
        defaultChannel.lockFields(NotificationChannel.USER_LOCKED_SOUND);
        // pre upgrade, default sound.
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(CUSTOM_SOUND, record.getSound());
        assertEquals(CUSTOM_ATTRIBUTES, record.getAudioAttributes());
    }

    @Test
    public void testSound_noSound_preUpgrade() {
        // pre upgrade, default sound.
        StatusBarNotification sbn = getNotification(PKG_N_MR1, false /* noisy */,
                false /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertNull(record.getSound());
        assertEquals(Notification.AUDIO_ATTRIBUTES_DEFAULT, record.getAudioAttributes());
    }

    @Test
    public void testSound_default_upgradeUsesChannel() {
        channel.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
        // post upgrade, default sound.
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(CUSTOM_SOUND, record.getSound());
        assertEquals(CUSTOM_ATTRIBUTES, record.getAudioAttributes());
    }

    @Test
    public void testVibration_default_preUpgradeUsesNotification() {
        defaultChannel.enableVibration(false);
        // pre upgrade, default vibration.
        StatusBarNotification sbn = getNotification(PKG_N_MR1, false /* noisy */,
                false /* defaultSound */, true /* buzzy */, true /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertNotNull(record.getVibration());
    }

    @Test
    public void testVibration_custom_preUpgradeUsesNotification() {
        defaultChannel.enableVibration(false);
        // pre upgrade, custom vibration.
        StatusBarNotification sbn = getNotification(PKG_N_MR1, false /* noisy */,
                false /* defaultSound */, true /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(CUSTOM_VIBRATION, record.getVibration());
    }

    @Test
    public void testVibration_custom_userLocked_preUpgrade() {
        defaultChannel.enableVibration(true);
        defaultChannel.lockFields(NotificationChannel.USER_LOCKED_VIBRATION);
        // pre upgrade, custom vibration.
        StatusBarNotification sbn = getNotification(PKG_N_MR1, false /* noisy */,
                false /* defaultSound */, true /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertTrue(!Arrays.equals(CUSTOM_VIBRATION, record.getVibration()));
    }

    @Test
    public void testVibration_custom_upgradeUsesChannel() {
        channel.enableVibration(true);
        // post upgrade, custom vibration.
        StatusBarNotification sbn = getNotification(PKG_O, false /* noisy */,
                false /* defaultSound */, true /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(CUSTOM_CHANNEL_VIBRATION, record.getVibration());
    }

    @Test
    public void testImportance_preUpgrade() {
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, record.getImportance());
    }

    @Test
    public void testImportance_locked_preUpgrade() {
        defaultChannel.setImportance(IMPORTANCE_LOW);
        defaultChannel.lockFields(USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(IMPORTANCE_LOW, record.getImportance());
    }

    @Test
    public void testImportance_locked_unspecified_preUpgrade() {
        defaultChannel.setImportance(NotificationManager.IMPORTANCE_UNSPECIFIED);
        defaultChannel.lockFields(USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(NotificationManager.IMPORTANCE_HIGH, record.getImportance());
    }

    @Test
    public void testImportance_upgrade() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, record.getImportance());
    }

    @Test
    public void testLights_preUpgrade_noLight() {
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertNull(record.getLight());
    }


    @Test
    public void testLights_preUpgrade() {
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                true /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertEquals(CUSTOM_LIGHT, record.getLight());
    }

    @Test
    public void testLights_locked_preUpgrade() {
        defaultChannel.enableLights(true);
        defaultChannel.lockFields(NotificationChannel.USER_LOCKED_LIGHTS);
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                true /* lights */, false /* defaultLights */, null /* group */);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertNotEquals(CUSTOM_LIGHT, record.getLight());
    }

    @Test
    public void testLights_upgrade_defaultLights() {
        int defaultLightColor = mMockContext.getResources().getColor(
                com.android.internal.R.color.config_defaultNotificationColor);
        int defaultLightOn = mMockContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOn);
        int defaultLightOff = mMockContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOff);

        NotificationRecord.Light expected = new NotificationRecord.Light(
                defaultLightColor, defaultLightOn, defaultLightOff);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                true /* lights */, true /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(expected, record.getLight());
    }

    @Test
    public void testLights_upgrade() {
        int defaultLightOn = mMockContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOn);
        int defaultLightOff = mMockContext.getResources().getInteger(
                com.android.internal.R.integer.config_defaultNotificationLedOff);

        NotificationRecord.Light expected = new NotificationRecord.Light(
                Color.BLUE, defaultLightOn, defaultLightOff);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                true /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(expected, record.getLight());
    }

    @Test
    public void testLights_upgrade_noLight() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);
        assertNull(record.getLight());
    }

    @Test
    public void testLogMaker() {
        long timestamp = 1000L;
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        final LogMaker logMaker = record.getLogMaker(timestamp);

        assertNull(logMaker.getTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX));
        assertEquals(channelId,
                (String) logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_ID));
        assertEquals(channel.getImportance(),
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE));
        assertEquals(record.getLifespanMs(timestamp),
                (int) logMaker.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_CREATE_MILLIS));
        assertEquals(record.getFreshnessMs(timestamp),
                (int) logMaker.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_UPDATE_MILLIS));
        assertEquals(record.getExposureMs(timestamp),
                (int) logMaker.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS));
        assertEquals(record.getInterruptionMs(timestamp),
                (int) logMaker.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_INTERRUPTION_MILLIS));
    }

    @Test
    public void testNotificationStats() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
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
    public void testUserSentiment() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
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
    public void testUserSentiment_appImportanceUpdatesSentiment() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(USER_SENTIMENT_NEUTRAL, record.getUserSentiment());

        record.setIsAppImportanceLocked(true);
        assertEquals(USER_SENTIMENT_POSITIVE, record.getUserSentiment());
    }

    @Test
    public void testUserSentiment_appImportanceBlocksNegativeSentimentUpdate() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
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
    public void testUserSentiment_userLocked() {
        channel.lockFields(USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
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
    public void testAppImportance_returnsCorrectly() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.setIsAppImportanceLocked(true);
        assertTrue(record.getIsAppImportanceLocked());

        record.setIsAppImportanceLocked(false);
        assertFalse(record.getIsAppImportanceLocked());
    }

    @Test
    public void testIsInterruptive_textChanged_notSeen() {
        StatusBarNotification sbn = getNotification(PKG_O, false /* noisy */,
                false /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse(record.isInterruptive());

        record.setTextChanged(true);
        assertFalse(record.isInterruptive());
    }

    @Test
    public void testIsInterruptive_textChanged_seen() {
        StatusBarNotification sbn = getNotification(PKG_O, false /* noisy */,
                false /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse(record.isInterruptive());

        record.setTextChanged(true);
        record.setSeen();
        assertTrue(record.isInterruptive());
    }

    @Test
    public void testIsInterruptive_textNotChanged_seen() {
        StatusBarNotification sbn = getNotification(PKG_O, false /* noisy */,
                false /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse(record.isInterruptive());

        record.setTextChanged(false);
        record.setSeen();
        assertFalse(record.isInterruptive());
    }

    @Test
    public void testCalculateGrantableUris_PappProvided() {
        IActivityManager am = mock(IActivityManager.class);
        UriGrantsManagerInternal ugm = mock(UriGrantsManagerInternal.class);
        when(ugm.checkGrantUriPermission(anyInt(), eq(null), any(Uri.class),
                anyInt(), anyInt())).thenThrow(new SecurityException());

        Notification n = mock(Notification.class);
        when(n.getChannelId()).thenReturn(channel.getId());
        StatusBarNotification sbn =
                new StatusBarNotification(PKG_P, PKG_P, id1, tag1, uid, uid, n, mUser, null, uid);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.mAm = am;
        record.mUgmInternal = ugm;

        try {
            record.calculateGrantableUris();
            fail("App provided uri for p targeting app should throw exception");
        } catch (SecurityException e) {
            // expected
        }
    }

    @Test
    public void testCalculateGrantableUris_PuserOverridden() {
        IActivityManager am = mock(IActivityManager.class);
        UriGrantsManagerInternal ugm = mock(UriGrantsManagerInternal.class);
        when(ugm.checkGrantUriPermission(anyInt(), eq(null), any(Uri.class),
                anyInt(), anyInt())).thenThrow(new SecurityException());

        channel.lockFields(NotificationChannel.USER_LOCKED_SOUND);
        Notification n = mock(Notification.class);
        when(n.getChannelId()).thenReturn(channel.getId());
        StatusBarNotification sbn =
                new StatusBarNotification(PKG_P, PKG_P, id1, tag1, uid, uid, n, mUser, null, uid);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.mAm = am;

        record.calculateGrantableUris();
    }

    @Test
    public void testCalculateGrantableUris_prePappProvided() {
        IActivityManager am = mock(IActivityManager.class);
        UriGrantsManagerInternal ugm = mock(UriGrantsManagerInternal.class);
        when(ugm.checkGrantUriPermission(anyInt(), eq(null), any(Uri.class),
                anyInt(), anyInt())).thenThrow(new SecurityException());

        Notification n = mock(Notification.class);
        when(n.getChannelId()).thenReturn(channel.getId());
        StatusBarNotification sbn =
                new StatusBarNotification(PKG_O, PKG_O, id1, tag1, uid, uid, n, mUser, null, uid);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.mAm = am;

        record.calculateGrantableUris();
        // should not throw
    }

    @Test
    public void testSmartActions() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertNull(record.getSystemGeneratedSmartActions());

        ArrayList<Notification.Action> smartActions = new ArrayList<>();
        smartActions.add(new Notification.Action.Builder(
                Icon.createWithResource(getContext(), R.drawable.btn_default),
                "text", null).build());
        record.setSystemGeneratedSmartActions(smartActions);
        assertEquals(smartActions, record.getSystemGeneratedSmartActions());
    }

    @Test
    public void testUpdateNotificationChannel() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertEquals(channel.getImportance(), record.getImportance());

        record.updateNotificationChannel(
                new NotificationChannel(channelId, "", channel.getImportance() - 1));

        assertEquals(channel.getImportance() - 1, record.getImportance());
    }

    @Test
    public void testCalculateImportance_systemImportance() {
        channel.setImportance(IMPORTANCE_HIGH);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.setSystemImportance(IMPORTANCE_LOW);
        assertEquals(IMPORTANCE_LOW, record.getImportance());

        record = new NotificationRecord(mMockContext, sbn, channel);
        channel.lockFields(USER_LOCKED_IMPORTANCE);

        record.setSystemImportance(IMPORTANCE_LOW);
        assertEquals(IMPORTANCE_LOW, record.getImportance());
    }

    @Test
    public void testCalculateImportance_asstImportance() {
        channel.setImportance(IMPORTANCE_HIGH);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.setAssistantImportance(IMPORTANCE_LOW);
        record.calculateImportance();
        assertEquals(IMPORTANCE_LOW, record.getImportance());

        // assistant ignored if user expressed preference
        record = new NotificationRecord(mMockContext, sbn, channel);
        channel.lockFields(USER_LOCKED_IMPORTANCE);

        record.setAssistantImportance(IMPORTANCE_LOW);
        record.calculateImportance();
        assertEquals(channel.getImportance(), record.getImportance());
    }

    @Test
    public void testCalculateImportance_asstImportanceChannelUpdate() {
        channel.setImportance(IMPORTANCE_HIGH);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.setAssistantImportance(IMPORTANCE_LOW);
        record.calculateImportance();
        assertEquals(IMPORTANCE_LOW, record.getImportance());

        record.updateNotificationChannel(
                new NotificationChannel(channelId, "", IMPORTANCE_DEFAULT));

        assertEquals(IMPORTANCE_LOW, record.getImportance());
    }

    @Test
    public void testSetContactAffinity() {
        channel.setImportance(IMPORTANCE_LOW);
        channel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.setContactAffinity(1.0f);

        assertEquals(1.0f, record.getContactAffinity());
        assertEquals(IMPORTANCE_LOW, record.getImportance());
    }

    @Test
    public void testSetDidNotAudiblyAlert() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.setAudiblyAlerted(false);

        assertEquals(-1, record.getLastAudiblyAlertedMs());
    }

    @Test
    public void testSetAudiblyAlerted() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.setAudiblyAlerted(true);

        assertNotEquals(-1, record.getLastAudiblyAlertedMs());
    }

    @Test
    public void testIsNewEnoughForAlerting_new() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertTrue(record.isNewEnoughForAlerting(record.mUpdateTimeMs));
    }

    @Test
    public void testIsNewEnoughForAlerting_old() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse(record.isNewEnoughForAlerting(record.mUpdateTimeMs + (1000 * 60 * 60)));
    }

    @Test
    public void testIgnoreImportanceAdjustmentsForOemLockedChannels() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setImportanceLockedByOEM(true);

        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertEquals(IMPORTANCE_DEFAULT, record.getImportance());

        Bundle bundle = new Bundle();
        bundle.putInt(Adjustment.KEY_IMPORTANCE, IMPORTANCE_LOW);
        Adjustment adjustment = new Adjustment(
                PKG_O, record.getKey(), bundle, "", record.getUserId());

        record.addAdjustment(adjustment);
        record.applyAdjustments();
        record.calculateImportance();

        assertEquals(IMPORTANCE_DEFAULT, record.getImportance());
    }

    @Test
    public void testApplyImportanceAdjustmentsForNonOemLockedChannels() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);
        channel.setImportanceLockedByOEM(false);

        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertEquals(IMPORTANCE_DEFAULT, record.getImportance());

        Bundle bundle = new Bundle();
        bundle.putInt(Adjustment.KEY_IMPORTANCE, IMPORTANCE_LOW);
        Adjustment adjustment = new Adjustment(
                PKG_O, record.getKey(), bundle, "", record.getUserId());

        record.addAdjustment(adjustment);
        record.applyAdjustments();
        record.calculateImportance();

        assertEquals(IMPORTANCE_LOW, record.getImportance());
    }
}
