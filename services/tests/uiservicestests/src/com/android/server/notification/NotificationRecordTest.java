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
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.media.AudioAttributes.USAGE_ALARM;
import static android.service.notification.Adjustment.KEY_IMPORTANCE;
import static android.service.notification.Adjustment.KEY_NOT_CONVERSATION;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_ALERTING;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_CONVERSATIONS;
import static android.service.notification.NotificationListenerService.FLAG_FILTER_TYPE_SILENT;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Flags;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.media.Utils;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.widget.RemoteViews;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.LocalServices;
import com.android.server.UiServiceTestCase;
import com.android.server.uri.UriGrantsManagerInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationRecordTest extends UiServiceTestCase {

    private final Context mMockContext = mock(Context.class);
    @Mock private PackageManager mPm;
    @Mock private ContentResolver mContentResolver;
    @Mock private Vibrator mVibrator;

    private final String mPkg = PKG_O;
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

    private static final long[] CUSTOM_NOTIFICATION_VIBRATION = new long[] {
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400 };
    private static final long[] CUSTOM_CHANNEL_VIBRATION_PATTERN = new long[] {300, 400, 300, 400 };
    private static final VibrationEffect CUSTOM_CHANNEL_VIBRATION_EFFECT =
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
    private static final Uri CUSTOM_SOUND = Settings.System.DEFAULT_ALARM_ALERT_URI;
    private static final AudioAttributes CUSTOM_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build();
    private static final NotificationRecord.Light CUSTOM_LIGHT =
            new NotificationRecord.Light(1, 2, 3);

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mMockContext.getSystemService(eq(Vibrator.class))).thenReturn(mVibrator);
        when(mVibrator.areVibrationFeaturesSupported(any())).thenReturn(true);
        when(mVibrator.getInfo()).thenReturn(VibratorInfo.EMPTY_VIBRATOR_INFO);
        final Resources res = mContext.getResources();
        when(mMockContext.getResources()).thenReturn(res);
        when(mMockContext.getPackageManager()).thenReturn(mPm);
        when(mMockContext.getContentResolver()).thenReturn(mContentResolver);
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.targetSdkVersion = Build.VERSION_CODES.O;
        when(mMockContext.getApplicationInfo()).thenReturn(appInfo);
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
                builder.setVibrate(CUSTOM_NOTIFICATION_VIBRATION);
                channel.setVibrationPattern(CUSTOM_CHANNEL_VIBRATION_PATTERN);
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

    private StatusBarNotification getStyledNotification(boolean customContent, boolean customBig,
            boolean customHeadsUp, Notification.Style style) {
        final Builder builder = new Builder(mMockContext)
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        if (style != null) {
            builder.setStyle(style);
        }
        if (customContent) {
            builder.setCustomContentView(mock(RemoteViews.class));
        }
        if (customBig) {
            builder.setCustomBigContentView(mock(RemoteViews.class));
        }
        if (customHeadsUp) {
            builder.setCustomHeadsUpContentView(mock(RemoteViews.class));
        }

        Notification n = builder.build();
        return new StatusBarNotification(mPkg, mPkg, id1, tag1, uid, uid, n, mUser, null, uid);
    }

    private StatusBarNotification getNotification(
            long[] channelVibrationPattern,
            VibrationEffect channelVibrationEffect,
            boolean insistent) {
        if (channelVibrationPattern != null) {
            channel.setVibrationPattern(channelVibrationPattern);
        } else if (channelVibrationEffect != null) {
            channel.setVibrationEffect(channelVibrationEffect);
        }

        final Builder builder = new Builder(mMockContext)
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .setVibrate(CUSTOM_NOTIFICATION_VIBRATION)
                .setFlag(Notification.FLAG_INSISTENT, insistent);

        Notification n = builder.build();
        return new StatusBarNotification(mPkg, mPkg, id1, tag1, uid, uid, n, mUser, null, uid);
    }

    private StatusBarNotification getNotification(
            VibrationEffect channelVibrationEffect, boolean insistent) {
        return getNotification(
                /* channelVibrationPattern= */ null, channelVibrationEffect, insistent);
    }

    private StatusBarNotification getNotification(
            long[] channelVibrationPattern, boolean insistent) {
        return getNotification(
                channelVibrationPattern, /* channelVibrationEffect= */ null, insistent);
    }

    private StatusBarNotification getMessagingStyleNotification() {
        return getMessagingStyleNotification(mPkg);
    }

    private StatusBarNotification getMessagingStyleNotification(String pkg) {
        final Builder builder = new Builder(mMockContext)
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon);

        Person person = new Person.Builder().setName("Bob").build();
        builder.setStyle(new Notification.MessagingStyle(person));

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
        assertEquals(VibratorHelper.createWaveformVibration(
                CUSTOM_NOTIFICATION_VIBRATION, /* insistent= */ false), record.getVibration());
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
        assertNotEquals(VibratorHelper.createWaveformVibration(
                CUSTOM_NOTIFICATION_VIBRATION, /* insistent= */ false), record.getVibration());
    }

    @Test
    public void testVibration_customPattern_nonInsistent_usesCustomPattern() {
        channel.enableVibration(true);
        StatusBarNotification sbn = getNotification(
                CUSTOM_CHANNEL_VIBRATION_PATTERN, /* insistent= */ false);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(VibratorHelper.createWaveformVibration(
                CUSTOM_CHANNEL_VIBRATION_PATTERN, /* insistent= */ false), record.getVibration());
    }

    @Test
    public void testVibration_customPattern_insistent_createsInsistentEffect() {
        channel.enableVibration(true);
        StatusBarNotification sbn = getNotification(
                CUSTOM_CHANNEL_VIBRATION_PATTERN, /* insistent= */ true);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(VibratorHelper.createWaveformVibration(
                CUSTOM_CHANNEL_VIBRATION_PATTERN, /* insistent= */ true), record.getVibration());
    }

    @Test
    public void testVibration_customEffect_flagNotEnabled_usesDefaultEffect() {
        mSetFlagsRule.disableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        channel.enableVibration(true);
        StatusBarNotification sbn = getNotification(
                CUSTOM_CHANNEL_VIBRATION_EFFECT, /* insistent= */ false);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        VibrationEffect effect = record.getVibration();
        assertNotEquals(effect, CUSTOM_CHANNEL_VIBRATION_EFFECT);
        assertNotNull(effect);
    }

    @Test
    public void testVibration_customEffect_effectNotSupported_usesDefaultEffect() {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        when(mVibrator.areVibrationFeaturesSupported(any())).thenReturn(false);
        StatusBarNotification sbn = getNotification(
                CUSTOM_CHANNEL_VIBRATION_EFFECT, /* insistent= */ false);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        VibrationEffect effect = record.getVibration();
        assertNotEquals(effect, CUSTOM_CHANNEL_VIBRATION_EFFECT);
        assertNotNull(effect);
    }

    @Test
    public void testVibration_customNonRepeatingEffect_nonInsistent_usesCustomEffect() {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        StatusBarNotification sbn = getNotification(
                CUSTOM_CHANNEL_VIBRATION_EFFECT, /* insistent= */ false);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(CUSTOM_CHANNEL_VIBRATION_EFFECT, record.getVibration());
    }

    @Test
    public void testVibration_customNonRepeatingEffect_insistent_createsInsistentEffect() {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        StatusBarNotification sbn = getNotification(
                CUSTOM_CHANNEL_VIBRATION_EFFECT, /* insistent= */ true);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        VibrationEffect repeatingEffect =
                CUSTOM_CHANNEL_VIBRATION_EFFECT
                        .applyRepeatingIndefinitely(true, /* loopDelayMs= */ 0);
        assertEquals(repeatingEffect, record.getVibration());
    }

    @Test
    public void testVibration_customRepeatingEffect_nonInsistent_createsNonRepeatingCustomEffect() {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        VibrationEffect repeatingEffect =
                CUSTOM_CHANNEL_VIBRATION_EFFECT
                        .applyRepeatingIndefinitely(true, /* loopDelayMs= */ 0);
        StatusBarNotification sbn = getNotification(repeatingEffect, /* insistent= */ false);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(CUSTOM_CHANNEL_VIBRATION_EFFECT, record.getVibration());
    }

    @Test
    public void testVibration_customRepeatingEffect_insistent_usesCustomEffect() {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        VibrationEffect repeatingEffect =
                CUSTOM_CHANNEL_VIBRATION_EFFECT
                        .applyRepeatingIndefinitely(true, /* loopDelayMs= */ 0);
        StatusBarNotification sbn = getNotification(repeatingEffect, /* insistent= */ true);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(repeatingEffect, record.getVibration());
    }

    @Test
    public void testVibration_noCustomVibration_vibrationEnabled_usesDefaultVibration() {
        channel.enableVibration(true);
        StatusBarNotification sbn = getNotification(
                /* channelVibrationPattern= */ null,
                /* channelVibrationEffect= */ null,
                /* insistent= */ false);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertNotNull(record.getVibration());
    }

    @Test
    public void testVibration_noCustomVibration_vibrationNotEnabled_usesNoVibration() {
        channel.enableVibration(false);
        StatusBarNotification sbn = getNotification(
                /* channelVibrationPattern= */ null,
                /* channelVibrationEffect= */ null,
                /* insistent= */ false);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertNull(record.getVibration());
    }

    @Test
    public void testVibration_customVibration_vibrationNotEnabled_usesNoVibration() {
        mSetFlagsRule.enableFlags(Flags.FLAG_NOTIFICATION_CHANNEL_VIBRATION_EFFECT_API);
        StatusBarNotification sbn = getNotification(
                CUSTOM_CHANNEL_VIBRATION_PATTERN, /* insistent= */ false);
        channel.enableVibration(false);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertNull(record.getVibration());
    }

    @Test
    @EnableFlags(com.android.server.notification.Flags.FLAG_NOTIFICATION_VIBRATION_IN_SOUND_URI)
    public void testVibration_customVibrationForSound_withoutVibrationUri() {
        // prepare testing data
        Uri backupDefaultUri = RingtoneManager.getActualDefaultRingtoneUri(mMockContext,
                RingtoneManager.TYPE_NOTIFICATION);
        RingtoneManager.setActualDefaultRingtoneUri(mMockContext, RingtoneManager.TYPE_NOTIFICATION,
                Settings.System.DEFAULT_NOTIFICATION_URI);
        defaultChannel.enableVibration(true);
        defaultChannel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, CUSTOM_ATTRIBUTES);
        StatusBarNotification sbn = getNotification(
                /* channelVibrationPattern= */ null,
                /* channelVibrationEffect= */ null,
                /* insistent= */ false);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);

        try {
            assertEquals(
                    new VibratorHelper(mMockContext).createDefaultVibration(false),
                    record.getVibration());
        } finally {
            // restore the data
            RingtoneManager.setActualDefaultRingtoneUri(mMockContext,
                    RingtoneManager.TYPE_NOTIFICATION,
                    backupDefaultUri);
        }
    }

    @Test
    @EnableFlags(com.android.server.notification.Flags.FLAG_NOTIFICATION_VIBRATION_IN_SOUND_URI)
    public void testVibration_customVibrationForSound_withVibrationUri() throws IOException {
        defaultChannel.enableVibration(true);
        VibrationInfo vibration = getTestingVibration(mVibrator);
        Uri uriWithVibration = getVibrationUriAppended(
                Settings.System.DEFAULT_NOTIFICATION_URI, vibration.mUri);
        defaultChannel.setSound(uriWithVibration, CUSTOM_ATTRIBUTES);
        StatusBarNotification sbn = getNotification(
                /* channelVibrationPattern= */ null,
                /* channelVibrationEffect= */ null,
                /* insistent= */ false);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);

        assertEquals(vibration.mVibrationEffect, record.getVibration());
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
        // If no importance calculation has been run, no explanation is available.
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_EXPLANATION));
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL));
        assertNull(logMaker.getTaggedData(
                MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL_EXPLANATION));
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_ASST));
    }

    @Test
    public void testLogMakerImportanceApp() {
        long timestamp = 1000L;
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.calculateImportance();  // This importance calculation will yield 'app'
        final LogMaker logMaker = record.getLogMaker(timestamp);
        assertEquals(MetricsEvent.IMPORTANCE_EXPLANATION_APP,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_EXPLANATION));
        assertEquals(channel.getImportance(),
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE));
        // The additional information is only populated if the initial importance is overridden.
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL));
        assertNull(logMaker.getTaggedData(
                MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL_EXPLANATION));
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_ASST));
    }

    @Test
    public void testLogMakerImportanceAsst() {
        long timestamp = 1000L;
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        Bundle signals = new Bundle();
        signals.putInt(KEY_IMPORTANCE, IMPORTANCE_LOW);
        record.addAdjustment(new Adjustment(PKG_O, KEY_IMPORTANCE, signals, "", uid));
        record.applyAdjustments();
        record.calculateImportance();  // This importance calculation will yield 'asst'
        final LogMaker logMaker = record.getLogMaker(timestamp);
        assertEquals(MetricsEvent.IMPORTANCE_EXPLANATION_ASST,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_EXPLANATION));
        // Therefore this is the assistant-set importance
        assertEquals(IMPORTANCE_LOW,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE));
        // Initial importance is populated so we know what it was, since it didn't get used.
        assertEquals(channel.getImportance(),
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL));
        assertEquals(MetricsEvent.IMPORTANCE_EXPLANATION_APP,
                logMaker.getTaggedData(
                        MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL_EXPLANATION));
        // This field is populated whenever mImportanceExplanationCode is.
        assertEquals(IMPORTANCE_LOW,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_ASST));
    }

    @Test
    public void testLogMakerImportanceSystem() {
        long timestamp = 1000L;
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setSystemImportance(IMPORTANCE_HIGH);
        record.calculateImportance();  // This importance calculation will yield 'system'
        final LogMaker logMaker = record.getLogMaker(timestamp);
        assertEquals(MetricsEvent.IMPORTANCE_EXPLANATION_SYSTEM,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_EXPLANATION));
        // Therefore this is the system-set importance
        assertEquals(IMPORTANCE_HIGH,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE));
        // Initial importance is populated so we know what it was, since it didn't get used.
        assertEquals(channel.getImportance(),
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL));
        assertEquals(MetricsEvent.IMPORTANCE_EXPLANATION_APP,
                logMaker.getTaggedData(
                        MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL_EXPLANATION));
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_ASST));
    }

    @Test
    public void testLogMakerImportanceUser() {
        long timestamp = 1000L;
        channel.lockFields(channel.USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.calculateImportance();  // This importance calculation will yield 'user'
        final LogMaker logMaker = record.getLogMaker(timestamp);
        assertEquals(MetricsEvent.IMPORTANCE_EXPLANATION_USER,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_EXPLANATION));
        // Therefore this is the user-set importance
        assertEquals(channel.getImportance(),
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE));
        // The additional information is only populated if the initial importance is overridden.
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL));
        assertNull(logMaker.getTaggedData(
                MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL_EXPLANATION));
        assertNull(logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_ASST));
    }

    @Test
    public void testLogMakerImportanceMulti() {
        long timestamp = 1000L;
        channel.lockFields(channel.USER_LOCKED_IMPORTANCE);
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        // Add all 3 ways of overriding the app-set importance of the notification
        Bundle signals = new Bundle();
        signals.putInt(KEY_IMPORTANCE, IMPORTANCE_LOW);
        record.addAdjustment(new Adjustment(PKG_O, KEY_IMPORTANCE, signals, "", uid));
        record.applyAdjustments();
        record.setSystemImportance(IMPORTANCE_HIGH);
        record.calculateImportance();  // This importance calculation will yield 'system'
        final LogMaker logMaker = record.getLogMaker(timestamp);
        assertEquals(MetricsEvent.IMPORTANCE_EXPLANATION_SYSTEM,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_EXPLANATION));
        // Therefore this is the system-set importance
        assertEquals(IMPORTANCE_HIGH,
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_CHANNEL_IMPORTANCE));
        // Initial importance is populated so we know what it was, since it didn't get used.
        assertEquals(channel.getImportance(),
                logMaker.getTaggedData(MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL));
        assertEquals(MetricsEvent.IMPORTANCE_EXPLANATION_USER, logMaker.getTaggedData(
                MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_INITIAL_EXPLANATION));
        // Assistant importance is populated so we know what it was, since it didn't get used.
        assertEquals(IMPORTANCE_LOW, logMaker.getTaggedData(
                MetricsEvent.FIELD_NOTIFICATION_IMPORTANCE_ASST));
    }

    @Test
    public void testNotificationStats() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LIFETIME_EXTENSION_REFACTOR);
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

        record.recordSmartReplied();
        assertThat(record.getStats().hasSmartReplied()).isTrue();
    }

    @Test
    public void testDirectRepliedAddsLifetimeExtensionFlag() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LIFETIME_EXTENSION_REFACTOR);

        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.recordDirectReplied();
        assertThat(record.getSbn().getNotification().flags
                & Notification.FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY).isGreaterThan(0);
    }

    @Test
    public void testSmartRepliedAddsLifetimeExtensionFlag() {
        mSetFlagsRule.enableFlags(Flags.FLAG_LIFETIME_EXTENSION_REFACTOR);

        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.recordSmartReplied();
        assertThat(record.getSbn().getNotification().flags
                & Notification.FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY).isGreaterThan(0);
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
        record.addAdjustment(new Adjustment(mPkg, record.getKey(), signals, null, sbn.getUserId()));

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
        record.addAdjustment(new Adjustment(mPkg, record.getKey(), signals, null, sbn.getUserId()));
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
        record.addAdjustment(new Adjustment(mPkg, record.getKey(), signals, null, sbn.getUserId()));

        record.applyAdjustments();

        assertEquals(USER_SENTIMENT_POSITIVE, record.getUserSentiment());
    }

    @Test
    public void testProposedImportance() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertEquals(IMPORTANCE_UNSPECIFIED, record.getProposedImportance());

        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_IMPORTANCE_PROPOSAL, IMPORTANCE_DEFAULT);
        record.addAdjustment(new Adjustment(mPkg, record.getKey(), signals, null, sbn.getUserId()));

        record.applyAdjustments();

        assertEquals(IMPORTANCE_DEFAULT, record.getProposedImportance());
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
    public void testSensitiveContent() {
        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse(record.hasSensitiveContent());

        Bundle signals = new Bundle();
        signals.putBoolean(Adjustment.KEY_SENSITIVE_CONTENT, true);
        record.addAdjustment(new Adjustment(mPkg, record.getKey(), signals, null, sbn.getUserId()));

        record.applyAdjustments();

        assertTrue(record.hasSensitiveContent());
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
        UriGrantsManagerInternal ugm = mock(UriGrantsManagerInternal.class);
        when(ugm.checkGrantUriPermission(anyInt(), eq(null), any(Uri.class),
                anyInt(), anyInt())).thenThrow(new SecurityException());

        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, ugm);

        channel.setSound(null, null);
        Notification n = new Notification.Builder(mContext, channel.getId())
                .setSmallIcon(Icon.createWithContentUri(Uri.parse("content://something")))
                .build();
        StatusBarNotification sbn =
                new StatusBarNotification(PKG_P, PKG_P, id1, tag1, uid, uid, n, mUser, null, uid);

        assertThrows("App provided uri for p targeting app should throw exception",
                SecurityException.class,
                () -> new NotificationRecord(mMockContext, sbn, channel));
    }

    @Test
    public void testCalculateGrantableUris_PappProvided_invalidSound() {
        UriGrantsManagerInternal ugm = mock(UriGrantsManagerInternal.class);
        when(ugm.checkGrantUriPermission(anyInt(), eq(null), any(Uri.class),
                anyInt(), anyInt())).thenThrow(new SecurityException());

        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, ugm);

        channel.setSound(Uri.parse("content://something"), mock(AudioAttributes.class));

        Notification n = mock(Notification.class);
        when(n.getChannelId()).thenReturn(channel.getId());
        StatusBarNotification sbn =
                new StatusBarNotification(PKG_P, PKG_P, id1, tag1, uid, uid, n, mUser, null, uid);

        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, record.getSound());
    }

    @Test
    public void testCalculateGrantableUris_PuserOverridden() {
        UriGrantsManagerInternal ugm = mock(UriGrantsManagerInternal.class);
        when(ugm.checkGrantUriPermission(anyInt(), eq(null), any(Uri.class),
                anyInt(), anyInt())).thenThrow(new SecurityException());

        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, ugm);

        channel.lockFields(NotificationChannel.USER_LOCKED_SOUND);
        Notification n = mock(Notification.class);
        when(n.getChannelId()).thenReturn(channel.getId());
        StatusBarNotification sbn =
                new StatusBarNotification(PKG_P, PKG_P, id1, tag1, uid, uid, n, mUser, null, uid);

        new NotificationRecord(mMockContext, sbn, channel); // should not throw
    }

    @Test
    public void testCalculateGrantableUris_prePappProvided() {
        UriGrantsManagerInternal ugm = mock(UriGrantsManagerInternal.class);
        when(ugm.checkGrantUriPermission(anyInt(), eq(null), any(Uri.class),
                anyInt(), anyInt())).thenThrow(new SecurityException());

        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, ugm);

        Notification n = mock(Notification.class);
        when(n.getChannelId()).thenReturn(channel.getId());
        StatusBarNotification sbn =
                new StatusBarNotification(PKG_O, PKG_O, id1, tag1, uid, uid, n, mUser, null, uid);

        new NotificationRecord(mMockContext, sbn, channel); // should not throw
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
        assertEquals(FLAG_FILTER_TYPE_SILENT, record.getNotificationType());

        record.updateNotificationChannel(
                new NotificationChannel(channelId, "", IMPORTANCE_DEFAULT));

        assertEquals(IMPORTANCE_LOW, record.getImportance());
        assertEquals(FLAG_FILTER_TYPE_SILENT, record.getNotificationType());
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
    public void testIgnoreImportanceAdjustmentsForFixedRecords() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);

        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setImportanceFixed(true);

        assertEquals(IMPORTANCE_DEFAULT, record.getImportance());

        Bundle bundle = new Bundle();
        bundle.putInt(KEY_IMPORTANCE, IMPORTANCE_LOW);
        Adjustment adjustment = new Adjustment(
                PKG_O, record.getKey(), bundle, "", record.getUserId());

        record.addAdjustment(adjustment);
        record.applyAdjustments();
        record.calculateImportance();

        assertEquals(IMPORTANCE_DEFAULT, record.getImportance());
    }

    @Test
    public void testApplyImportanceAdjustments() {
        NotificationChannel channel = new NotificationChannel("a", "a", IMPORTANCE_DEFAULT);

        StatusBarNotification sbn = getNotification(PKG_O, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, groupId /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertEquals(IMPORTANCE_DEFAULT, record.getImportance());

        Bundle bundle = new Bundle();
        bundle.putInt(KEY_IMPORTANCE, IMPORTANCE_LOW);
        Adjustment adjustment = new Adjustment(
                PKG_O, record.getKey(), bundle, "", record.getUserId());

        record.addAdjustment(adjustment);
        record.applyAdjustments();
        record.calculateImportance();

        assertEquals(IMPORTANCE_LOW, record.getImportance());
    }

    @Test
    public void testHasUndecoratedRemoteViews_NoRemoteViews() {
        StatusBarNotification sbn = getStyledNotification(false, false, false, null);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse("false positive detection", record.hasUndecoratedRemoteView());
    }

    @Test
    public void testHasUndecoratedRemoteViews_NoRemoteViewsWithStyle() {
        StatusBarNotification sbn = getStyledNotification(false, false, false,
                new Notification.BigPictureStyle());
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse("false positive detection", record.hasUndecoratedRemoteView());
    }

    @Test
    public void testHasUndecoratedRemoteViews_UndecoratedContent() {
        StatusBarNotification sbn = getStyledNotification(true, false, false, null);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertTrue("false negative detection", record.hasUndecoratedRemoteView());
    }


    @Test
    public void testHasUndecoratedRemoteViews_UndecoratedBig() {
        StatusBarNotification sbn = getStyledNotification(false, true, false, null);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertTrue("false negative detection", record.hasUndecoratedRemoteView());
    }


    @Test
    public void testHasUndecoratedRemoteViews_UndecoratedHeadsup() {
        StatusBarNotification sbn = getStyledNotification(false, false, true, null);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertTrue("false negative detection", record.hasUndecoratedRemoteView());
    }

    @Test
    public void testHasUndecoratedRemoteViews_DecoratedRemoteViews() {
        StatusBarNotification sbn = getStyledNotification(true, true, true,
                new Notification.DecoratedCustomViewStyle());
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse("false positive detection", record.hasUndecoratedRemoteView());
    }

    @Test
    public void testHasUndecoratedRemoteViews_DecoratedMediaRemoteViews() {
        StatusBarNotification sbn = getStyledNotification(true, true, true,
                new Notification.DecoratedMediaCustomViewStyle());
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertFalse("false positive detection", record.hasUndecoratedRemoteView());
    }

    @Test
    public void testHasUndecoratedRemoteViews_UndecoratedWrongStyle() {
        StatusBarNotification sbn = getStyledNotification(true, true, true,
                new Notification.BigPictureStyle());
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        assertTrue("false negative detection", record.hasUndecoratedRemoteView());
    }

    @Test
    public void testIsConversation() {
        StatusBarNotification sbn = getMessagingStyleNotification();
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setShortcutInfo(mock(ShortcutInfo.class));

        assertTrue(record.isConversation());
        assertEquals(FLAG_FILTER_TYPE_CONVERSATIONS, record.getNotificationType());
    }

    @Test
    public void testIsConversation_shortcutHasOneBot_targetsR() {
        StatusBarNotification sbn = getMessagingStyleNotification(PKG_R);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        ShortcutInfo shortcutMock = mock(ShortcutInfo.class);
        when(shortcutMock.getPersons()).thenReturn(new Person[]{
                new Person.Builder().setName("Bot").setBot(true).build()
        });
        record.setShortcutInfo(shortcutMock);

        assertFalse(record.isConversation());
    }

    @Test
    public void testIsConversation_shortcutHasOnePerson_targetsR() {
        StatusBarNotification sbn = getMessagingStyleNotification(PKG_R);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        ShortcutInfo shortcutMock = mock(ShortcutInfo.class);
        when(shortcutMock.getPersons()).thenReturn(new Person[]{
                new Person.Builder().setName("Person").setBot(false).build()
        });
        record.setShortcutInfo(shortcutMock);

        assertTrue(record.isConversation());
        assertEquals(FLAG_FILTER_TYPE_CONVERSATIONS, record.getNotificationType());
    }

    @Test
    public void testIsConversation_shortcutHasOneBotOnePerson_targetsR() {
        StatusBarNotification sbn = getMessagingStyleNotification(PKG_R);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        ShortcutInfo shortcutMock = mock(ShortcutInfo.class);
        when(shortcutMock.getPersons()).thenReturn(new Person[]{
                new Person.Builder().setName("Bot").setBot(true).build(),
                new Person.Builder().setName("Person").setBot(false).build()
        });
        record.setShortcutInfo(shortcutMock);

        assertTrue(record.isConversation());
        assertEquals(FLAG_FILTER_TYPE_CONVERSATIONS, record.getNotificationType());
    }

    @Test
    public void testIsConversation_noShortcut() {
        StatusBarNotification sbn = getMessagingStyleNotification();
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setShortcutInfo(null);

        assertTrue(record.isConversation());
        assertEquals(FLAG_FILTER_TYPE_CONVERSATIONS, record.getNotificationType());
    }

    @Test
    public void testIsConversation_noShortcut_appHasPreviousSentFullConversation() {
        StatusBarNotification sbn = getMessagingStyleNotification();
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setShortcutInfo(null);
        record.setHasSentValidMsg(true);

        assertFalse(record.isConversation());
        assertEquals(FLAG_FILTER_TYPE_ALERTING, record.getNotificationType());
    }

    @Test
    public void testIsConversation_noShortcut_userDemotedApp() {
        StatusBarNotification sbn = getMessagingStyleNotification();
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setShortcutInfo(null);
        record.userDemotedAppFromConvoSpace(true);

        assertFalse(record.isConversation());
    }

    @Test
    public void testIsConversation_noShortcut_targetsR() {
        StatusBarNotification sbn = getMessagingStyleNotification(PKG_R);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setShortcutInfo(null);

        assertFalse(record.isConversation());
    }

    @Test
    public void testIsConversation_channelDemoted() {
        StatusBarNotification sbn = getMessagingStyleNotification();
        channel.setDemoted(true);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setShortcutInfo(mock(ShortcutInfo.class));

        assertFalse(record.isConversation());
    }

    @Test
    public void testIsConversation_withAdjustmentOverride() {
        StatusBarNotification sbn = getMessagingStyleNotification();
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);
        record.setShortcutInfo(mock(ShortcutInfo.class));

        Bundle bundle = new Bundle();
        bundle.putBoolean(KEY_NOT_CONVERSATION, true);
        Adjustment adjustment = new Adjustment(
                PKG_O, record.getKey(), bundle, "", record.getUser().getIdentifier());

        record.addAdjustment(adjustment);
        record.applyAdjustments();

        assertFalse(record.isConversation());
    }

    @Test
    public void isConversation_pkgAllowed_isMsgType() {
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        sbn.getNotification().category = Notification.CATEGORY_MESSAGE;
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);

        record.setPkgAllowedAsConvo(true);

        assertTrue(record.isConversation());
    }

    @Test
    public void isConversation_pkgAllowed_isMNotsgType() {
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        sbn.getNotification().category = Notification.CATEGORY_ALARM;
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);

        record.setPkgAllowedAsConvo(true);

        assertFalse(record.isConversation());
    }

    @Test
    public void isConversation_pkgNotAllowed_isMsgType() {
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        sbn.getNotification().category = Notification.CATEGORY_MESSAGE;
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);

        record.setPkgAllowedAsConvo(false);

        assertFalse(record.isConversation());
    }

    @Test
    public void isConversation_pkgAllowed_isMsgType_targetsR() {
        StatusBarNotification sbn = getNotification(PKG_R, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        sbn.getNotification().category = Notification.CATEGORY_MESSAGE;
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);

        record.setPkgAllowedAsConvo(true);

        assertFalse(record.isConversation());
    }

    @Test
    public void mergePhoneNumbers_nulls() {
        // make sure nothing dies if we just don't have any phone numbers
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);

        // by default, no phone numbers
        assertNull(record.getPhoneNumbers());

        // nothing happens if we attempt to merge phone numbers but there aren't any
        record.mergePhoneNumbers(null);
        assertNull(record.getPhoneNumbers());
    }

    @Test
    public void mergePhoneNumbers_addNumbers() {
        StatusBarNotification sbn = getNotification(PKG_N_MR1, true /* noisy */,
                true /* defaultSound */, false /* buzzy */, false /* defaultBuzz */,
                false /* lights */, false /* defaultLights */, null /* group */);
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, defaultChannel);

        // by default, no phone numbers
        assertNull(record.getPhoneNumbers());

        // make sure it behaves properly when we merge in some real content
        record.mergePhoneNumbers(new ArraySet<>(
                new String[]{"16175551212", "16175552121"}));
        assertTrue(record.getPhoneNumbers().contains("16175551212"));
        assertTrue(record.getPhoneNumbers().contains("16175552121"));
        assertFalse(record.getPhoneNumbers().contains("16175553434"));

        // now merge in a new number, make sure old ones are still there and the new one
        // is also there
        record.mergePhoneNumbers(new ArraySet<>(new String[]{"16175553434"}));
        assertTrue(record.getPhoneNumbers().contains("16175551212"));
        assertTrue(record.getPhoneNumbers().contains("16175552121"));
        assertTrue(record.getPhoneNumbers().contains("16175553434"));
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_AUDIO_ATTRIBUTES_ALARM)
    public void updateChannel_nullAudioAttributes() {
        StatusBarNotification sbn = getStyledNotification(true, true, true,
                new Notification.DecoratedCustomViewStyle());
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        record.updateNotificationChannel(new NotificationChannel("new", "new", 3));

        assertThat(record.getAudioAttributes()).isNotNull();
    }

    @Test
    @EnableFlags(Flags.FLAG_RESTRICT_AUDIO_ATTRIBUTES_ALARM)
    public void updateChannel_nonNullAudioAttributes() {
        StatusBarNotification sbn = getStyledNotification(true, true, true,
                new Notification.DecoratedCustomViewStyle());
        NotificationRecord record = new NotificationRecord(mMockContext, sbn, channel);

        NotificationChannel update = new NotificationChannel("new", "new", 3);
        update.setSound(Uri.EMPTY,
                new AudioAttributes.Builder().setUsage(USAGE_ALARM).build());
        record.updateNotificationChannel(update);

        assertThat(record.getAudioAttributes().getUsage()).isEqualTo(USAGE_ALARM);
    }

    static class VibrationInfo {
        public VibrationEffect mVibrationEffect;
        public Uri mUri;
        VibrationInfo(VibrationEffect vibrationEffect, Uri uri) {
            mVibrationEffect = vibrationEffect;
            mUri = uri;
        }
    }

    private static VibrationInfo getTestingVibration(Vibrator vibrator) throws IOException {
        File tempVibrationFile = File.createTempFile("test_vibration_file", ".xml");
        FileWriter writer = new FileWriter(tempVibrationFile);
        writer.write("<vibration-effect>\n"
                + "    <waveform-effect>\n"
                + "        <!-- PRIMING -->\n"
                + "        <waveform-entry durationMs=\"0\" amplitude=\"0\"/>\n"
                + "        <waveform-entry durationMs=\"12\" amplitude=\"255\"/>\n"
                + "        <waveform-entry durationMs=\"250\" amplitude=\"0\"/>\n"
                + "        <waveform-entry durationMs=\"12\" amplitude=\"255\"/>\n"
                + "        <waveform-entry durationMs=\"500\" amplitude=\"0\"/>\n"
                + "    </waveform-effect>\n"
                + "</vibration-effect>"); // Your test XML content
        writer.close();
        Uri vibrationUri = Uri.parse(tempVibrationFile.toURI().toString());

        VibrationEffect vibrationEffect = Utils.parseVibrationEffect(vibrator, vibrationUri);
        return new VibrationInfo(vibrationEffect, vibrationUri);
    }

    private static Uri getVibrationUriAppended(Uri audioUri, Uri vibrationUri) {
        Uri.Builder builder = audioUri.buildUpon();
        builder.appendQueryParameter(Utils.VIBRATION_URI_PARAM, vibrationUri.toString());
        return builder.build();
    }
}
