/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distriZenbuted on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertEquals;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.VolumePolicy;
import android.media.AudioSystem;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.UiServiceTestCase;
import android.util.Slog;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ZenModeHelperTest extends UiServiceTestCase {

    @Mock ConditionProviders mConditionProviders;
    @Mock NotificationManager mNotificationManager;
    @Mock private Resources mResources;
    private TestableLooper mTestableLooper;
    private ZenModeHelper mZenModeHelperSpy;
    private Context mContext;
    private ContentResolver mContentResolver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mContext = spy(getContext());
        mContentResolver = mContext.getContentResolver();
        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(R.string.zen_mode_default_every_night_name)).thenReturn("night");
        when(mResources.getString(R.string.zen_mode_default_events_name)).thenReturn("events");
        when(mContext.getSystemService(NotificationManager.class)).thenReturn(mNotificationManager);

        mZenModeHelperSpy = spy(new ZenModeHelper(mContext, mTestableLooper.getLooper(),
                mConditionProviders));
    }

    private ByteArrayOutputStream writeXmlAndPurge(boolean forBackup, Integer version)
            throws Exception {
        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mZenModeHelperSpy.writeXml(serializer, forBackup, version);
        serializer.endDocument();
        serializer.flush();
        mZenModeHelperSpy.setConfig(new ZenModeConfig(), "writing xml");
        return baos;
    }

    @Test
    public void testZenOff_NoMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_OFF;
        assertTrue(mZenModeHelperSpy.mConfig.allowAlarms);
        mZenModeHelperSpy.applyRestrictions();

        doNothing().when(mZenModeHelperSpy).applyRestrictions(anyBoolean(), anyInt());
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ALARM);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_MEDIA);
    }

    @Test
    public void testZenOn_AllowAlarmsMedia_NoAlarmMediaMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        assertTrue(mZenModeHelperSpy.mConfig.allowAlarms);
        assertTrue(mZenModeHelperSpy.mConfig.allowMedia);
        mZenModeHelperSpy.applyRestrictions();
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ALARM);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_MEDIA);
    }

    @Test
    public void testZenOn_DisallowAlarmsMedia_AlarmMediaMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMedia);
        assertFalse(mZenModeHelperSpy.mConfig.allowSystem);
        mZenModeHelperSpy.applyRestrictions();
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_ALARM);

        // Media is a catch-all that includes games
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_MEDIA);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_GAME);
    }

    @Test
    public void testTotalSilence() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;
        mZenModeHelperSpy.applyRestrictions();

        // Total silence will silence alarms, media and system noises (but not vibrations)
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_ALARM);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_MEDIA);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_GAME);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.OP_PLAY_AUDIO);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.OP_VIBRATE);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_UNKNOWN);
    }

    @Test
    public void testAlarmsOnly_alarmMediaMuteNotApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMedia);
        mZenModeHelperSpy.applyRestrictions();

        // Alarms only mode will not silence alarms
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ALARM);

        // Alarms only mode will not silence media
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_MEDIA);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_GAME);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_UNKNOWN);

        // Alarms only will silence system noises (but not vibrations)
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.OP_PLAY_AUDIO);
    }

    @Test
    public void testAlarmsOnly_callsMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConfig.allowCalls = true;
        assertTrue(mZenModeHelperSpy.mConfig.allowCalls);
        mZenModeHelperSpy.applyRestrictions();

        // Alarms only mode will silence calls despite priority-mode config
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
    }

    @Test
    public void testAlarmsOnly_allZenConfigToggledCannotBypass_alarmMuteNotApplied() {
        // Only audio attributes with SUPPRESIBLE_NEVER can bypass
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = false;
        mZenModeHelperSpy.mConfig.allowCalls = false;
        mZenModeHelperSpy.mConfig.allowMessages = false;
        mZenModeHelperSpy.mConfig.allowEvents = false;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= false;
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMedia);
        assertFalse(mZenModeHelperSpy.mConfig.allowReminders);
        assertFalse(mZenModeHelperSpy.mConfig.allowCalls);
        assertFalse(mZenModeHelperSpy.mConfig.allowMessages);
        assertFalse(mZenModeHelperSpy.mConfig.allowEvents);
        assertFalse(mZenModeHelperSpy.mConfig.allowRepeatCallers);
        mZenModeHelperSpy.applyRestrictions();

        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false,
                AudioAttributes.USAGE_ALARM);
    }

    @Test
    public void testZenAllCannotBypass() {
        // Only audio attributes with SUPPRESIBLE_NEVER can bypass
        // with special case USAGE_ASSISTANCE_SONIFICATION
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = false;
        mZenModeHelperSpy.mConfig.allowCalls = false;
        mZenModeHelperSpy.mConfig.allowMessages = false;
        mZenModeHelperSpy.mConfig.allowEvents = false;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= false;
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMedia);
        assertFalse(mZenModeHelperSpy.mConfig.allowReminders);
        assertFalse(mZenModeHelperSpy.mConfig.allowCalls);
        assertFalse(mZenModeHelperSpy.mConfig.allowMessages);
        assertFalse(mZenModeHelperSpy.mConfig.allowEvents);
        assertFalse(mZenModeHelperSpy.mConfig.allowRepeatCallers);
        mZenModeHelperSpy.applyRestrictions();

        for (int usage : AudioAttributes.SDK_USAGES) {
            if (usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {
                // only mute audio, not vibrations
                verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, usage,
                        AppOpsManager.OP_PLAY_AUDIO);
                verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, usage,
                        AppOpsManager.OP_VIBRATE);
            } else {
                boolean shouldMute = AudioAttributes.SUPPRESSIBLE_USAGES.get(usage)
                        != AudioAttributes.SUPPRESSIBLE_NEVER;
                verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(shouldMute, usage);
            }
        }
    }

    @Test
    public void testZenUpgradeNotification() {
        // shows zen upgrade notification if stored settings says to shows, boot is completed
        // and we're setting zen mode on
        Settings.Global.putInt(mContentResolver, Settings.Global.SHOW_ZEN_UPGRADE_NOTIFICATION, 1);
        mZenModeHelperSpy.mIsBootComplete = true;
        mZenModeHelperSpy.setZenModeSetting(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mZenModeHelperSpy, times(1)).createZenUpgradeNotification();
        verify(mNotificationManager, times(1)).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
        assertEquals(0, Settings.Global.getInt(mContentResolver,
                Settings.Global.SHOW_ZEN_UPGRADE_NOTIFICATION, -1));
    }

    @Test
    public void testNoZenUpgradeNotification() {
        // doesn't show upgrade notification if stored settings says don't show
        Settings.Global.putInt(mContentResolver, Settings.Global.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
        mZenModeHelperSpy.mIsBootComplete = true;
        mZenModeHelperSpy.setZenModeSetting(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mZenModeHelperSpy, never()).createZenUpgradeNotification();
        verify(mNotificationManager, never()).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
    }

    @Test
    public void testZenSetInternalRinger_AllPriorityNotificationSoundsMuted() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        // Set zen to priority-only with all notification sounds muted (so ringer will be muted)
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowReminders = false;
        mZenModeHelperSpy.mConfig.allowCalls = false;
        mZenModeHelperSpy.mConfig.allowMessages = false;
        mZenModeHelperSpy.mConfig.allowEvents = false;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= false;

        // 2. apply priority only zen - verify ringer is unchanged
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelperSpy.TAG);

        // 3. apply zen off - verify zen is set to previous ringer (normal)
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testRingerAffectedStreamsTotalSilence() {
        // in total silence:
        // ringtone, notification, system, alarm, streams, music are affected by ringer mode
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelperSpy.new RingerModeDelegate();
        int ringerModeAffectedStreams = ringerModeDelegate.getRingerModeAffectedStreams(0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) != 0);
    }

    @Test
    public void testRingerAffectedStreamsPriorityOnly() {
        // in priority only mode:
        // ringtone, notification and system streams are affected by ringer mode
        // UNLESS ringer is muted due to all the other priority only dnd sounds being muted
        mZenModeHelperSpy.mConfig.allowAlarms = true;
        mZenModeHelperSpy.mConfig.allowReminders = true;
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        ZenModeHelper.RingerModeDelegate ringerModeDelegateRingerMuted =
                mZenModeHelperSpy.new RingerModeDelegate();

        int ringerModeAffectedStreams =
                ringerModeDelegateRingerMuted.getRingerModeAffectedStreams(0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) == 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) == 0);

        // special case: if ringer is muted (since all notification sounds cannot bypass)
        // then system stream is not affected by ringer mode
        mZenModeHelperSpy.mConfig.allowReminders = false;
        mZenModeHelperSpy.mConfig.allowCalls = false;
        mZenModeHelperSpy.mConfig.allowMessages = false;
        mZenModeHelperSpy.mConfig.allowEvents = false;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= false;
        ZenModeHelper.RingerModeDelegate ringerModeDelegateRingerNotMuted =
                mZenModeHelperSpy.new RingerModeDelegate();

        int ringerMutedRingerModeAffectedStreams =
                ringerModeDelegateRingerNotMuted.getRingerModeAffectedStreams(0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM))
                == 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) == 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) == 0);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_StartNormal() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify ringer is normal
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);

        // 3.  apply zen off - verify ringer remains normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_StartSilent() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_SILENT));

        // 1. Current ringer is silent
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify ringer is silent
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelperSpy.TAG);

        // 3. apply zen-off - verify ringer is still silent
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_RingerChanges() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        // Set zen to priority-only with all notification sounds muted (so ringer will be muted)
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify zen will still be normal
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);

        // 3. change ringer from normal to silent, verify previous ringer set to new ringer (silent)
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelperSpy.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_SILENT, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // 4.  apply zen off - verify ringer still silenced
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testSilentRingerSavedInZenOff_startsZenOff() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.mConfig = null; // will evaluate config to zen mode off
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelperSpy.evaluateZenMode("test", true);
        }
        verify(mZenModeHelperSpy, never()).applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testSilentRingerSavedOnZenOff_startsZenOn() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;

        // previously set silent ringer
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelperSpy.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_SILENT, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig = null; // will evaluate config to zen mode off
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelperSpy.evaluateZenMode("test", true);
        }
        verify(mZenModeHelperSpy, times(1)).applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testVibrateRingerSavedOnZenOff_startsZenOn() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;

        // previously set silent ringer
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelperSpy.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_VIBRATE, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig = null; // will evaluate config to zen mode off
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelperSpy.evaluateZenMode("test", true);
        }
        verify(mZenModeHelperSpy, times(1)).applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testParcelConfig() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = true;
        mZenModeHelperSpy.mConfig.allowCalls = true;
        mZenModeHelperSpy.mConfig.allowMessages = true;
        mZenModeHelperSpy.mConfig.allowEvents = true;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= true;
        mZenModeHelperSpy.mConfig.allowWhenScreenOff = true;
        mZenModeHelperSpy.mConfig.allowWhenScreenOn = true;
        mZenModeHelperSpy.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelperSpy.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelperSpy.mConfig.manualRule.component = new ComponentName("a", "a");
        mZenModeHelperSpy.mConfig.manualRule.enabled = true;
        mZenModeHelperSpy.mConfig.manualRule.snoozing = true;

        ZenModeConfig actual = mZenModeHelperSpy.mConfig.copy();

        assertEquals(mZenModeHelperSpy.mConfig, actual);
    }

    @Test
    public void testWriteXml() throws Exception {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = true;
        mZenModeHelperSpy.mConfig.allowCalls = true;
        mZenModeHelperSpy.mConfig.allowMessages = true;
        mZenModeHelperSpy.mConfig.allowEvents = true;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= true;
        mZenModeHelperSpy.mConfig.allowWhenScreenOff = true;
        mZenModeHelperSpy.mConfig.allowWhenScreenOn = true;
        mZenModeHelperSpy.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelperSpy.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelperSpy.mConfig.manualRule.zenMode =
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.manualRule.component = new ComponentName("a", "a");
        mZenModeHelperSpy.mConfig.manualRule.enabled = true;
        mZenModeHelperSpy.mConfig.manualRule.snoozing = true;

        ZenModeConfig expected = mZenModeHelperSpy.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurge(false, null);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false);

        assertEquals(expected, mZenModeHelperSpy.mConfig);
    }

    @Test
    public void testReadXml() throws Exception {
        setupZenConfig();

        // automatic zen rule is enabled on upgrade so rules should not be overriden by default
        ArrayMap<String, ZenModeConfig.ZenRule> enabledAutoRule = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo weeknights = new ScheduleInfo();
        customRule.enabled = true;
        customRule.name = "Custom Rule";
        customRule.zenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(weeknights);
        enabledAutoRule.put("customRule", customRule);
        mZenModeHelperSpy.mConfig.automaticRules = enabledAutoRule;

        ZenModeConfig expected = mZenModeHelperSpy.mConfig.copy();

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(false, 5);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false);

        assertTrue(mZenModeHelperSpy.mConfig.automaticRules.containsKey("customRule"));
        setupZenConfigMaintained();
    }

    @Test
    public void testReadXmlResetDefaultRules() throws Exception {
        setupZenConfig();

        // no enabled automatic zen rule, so rules should be overriden by default rules
        mZenModeHelperSpy.mConfig.automaticRules = new ArrayMap<>();

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(false, 5);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelperSpy.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }

        setupZenConfigMaintained();
    }


    @Test
    public void testReadXmlAllDisabledRulesResetDefaultRules() throws Exception {
        setupZenConfig();

        // all automatic zen rules are diabled on upgrade so rules should be overriden by default
        // rules
        ArrayMap<String, ZenModeConfig.ZenRule> enabledAutoRule = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo weeknights = new ScheduleInfo();
        customRule.enabled = false;
        customRule.name = "Custom Rule";
        customRule.zenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(weeknights);
        enabledAutoRule.put("customRule", customRule);
        mZenModeHelperSpy.mConfig.automaticRules = enabledAutoRule;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(false, 5);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelperSpy.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }
        assertFalse(rules.containsKey("customRule"));

        setupZenConfigMaintained();
    }

    @Test
    public void testPolicyReadsSuppressedEffects() {
        mZenModeHelperSpy.mConfig.allowWhenScreenOff = true;
        mZenModeHelperSpy.mConfig.allowWhenScreenOn = true;
        mZenModeHelperSpy.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;

        NotificationManager.Policy policy = mZenModeHelperSpy.getNotificationPolicy();
        assertEquals(SUPPRESSED_EFFECT_BADGE, policy.suppressedVisualEffects);
    }

    private void setupZenConfig() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = true;
        mZenModeHelperSpy.mConfig.allowCalls = true;
        mZenModeHelperSpy.mConfig.allowMessages = true;
        mZenModeHelperSpy.mConfig.allowEvents = true;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= true;
        mZenModeHelperSpy.mConfig.allowWhenScreenOff = true;
        mZenModeHelperSpy.mConfig.allowWhenScreenOn = true;
        mZenModeHelperSpy.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelperSpy.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelperSpy.mConfig.manualRule.zenMode =
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.manualRule.component = new ComponentName("a", "a");
        mZenModeHelperSpy.mConfig.manualRule.enabled = true;
        mZenModeHelperSpy.mConfig.manualRule.snoozing = true;
    }

    private void setupZenConfigMaintained() {
        // config is still the same as when it was setup (setupZenConfig)
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMedia);
        assertFalse(mZenModeHelperSpy.mConfig.allowSystem);
        assertTrue(mZenModeHelperSpy.mConfig.allowReminders);
        assertTrue(mZenModeHelperSpy.mConfig.allowCalls);
        assertTrue(mZenModeHelperSpy.mConfig.allowMessages);
        assertTrue(mZenModeHelperSpy.mConfig.allowEvents);
        assertTrue(mZenModeHelperSpy.mConfig.allowRepeatCallers);
        assertTrue(mZenModeHelperSpy.mConfig.allowWhenScreenOff);
        assertTrue(mZenModeHelperSpy.mConfig.allowWhenScreenOn);
        assertEquals(SUPPRESSED_EFFECT_BADGE, mZenModeHelperSpy.mConfig.suppressedVisualEffects);
    }
}
