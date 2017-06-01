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

import static android.app.Notification.GROUP_ALERT_ALL;
import static android.app.Notification.GROUP_ALERT_CHILDREN;
import static android.app.Notification.GROUP_ALERT_SUMMARY;
import static android.app.NotificationManager.IMPORTANCE_HIGH;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import com.android.server.lights.Light;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BuzzBeepBlinkTest extends NotificationTestCase {

    @Mock AudioManager mAudioManager;
    @Mock Vibrator mVibrator;
    @Mock android.media.IRingtonePlayer mRingtonePlayer;
    @Mock Light mLight;
    @Mock Handler mHandler;

    private NotificationManagerService mService;
    private String mPkg = "com.android.server.notification";
    private int mId = 1001;
    private int mOtherId = 1002;
    private String mTag = null;
    private int mUid = 1000;
    private int mPid = 2000;
    private android.os.UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());

    private VibrateRepeatMatcher mVibrateOnceMatcher = new VibrateRepeatMatcher(-1);
    private VibrateRepeatMatcher mVibrateLoopMatcher = new VibrateRepeatMatcher(0);

    private static final long[] CUSTOM_VIBRATION = new long[] {
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400,
            300, 400, 300, 400, 300, 400, 300, 400, 300, 400, 300, 400 };
    private static final Uri CUSTOM_SOUND = Settings.System.DEFAULT_ALARM_ALERT_URI;
    private static final AudioAttributes CUSTOM_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build();
    private static final int CUSTOM_LIGHT_COLOR = Color.BLACK;
    private static final int CUSTOM_LIGHT_ON = 10000;
    private static final int CUSTOM_LIGHT_OFF = 10000;
    private static final long[] FALLBACK_VIBRATION_PATTERN = new long[] {100, 100, 100};
    private static final VibrationEffect FALLBACK_VIBRATION =
            VibrationEffect.createWaveform(FALLBACK_VIBRATION_PATTERN, -1);
    private static final int MAX_VIBRATION_DELAY = 1000;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mAudioManager.isAudioFocusExclusive()).thenReturn(false);
        when(mAudioManager.getRingtonePlayer()).thenReturn(mRingtonePlayer);
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(10);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);

        mService = new NotificationManagerService(getContext());
        mService.setAudioManager(mAudioManager);
        mService.setVibrator(mVibrator);
        mService.setSystemReady(true);
        mService.setHandler(mHandler);
        mService.setLights(mLight);
        mService.setScreenOn(false);
        mService.setFallbackVibrationPattern(FALLBACK_VIBRATION_PATTERN);
    }

    //
    // Convenience functions for creating notification records
    //

    private NotificationRecord getNoisyOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
                true /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBeepyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBeepyOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
                false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getQuietOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBeepyNotification() {
        return getNotificationRecord(mId, true /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getInsistentBuzzyNotification() {
        return getNotificationRecord(mId, true /* insistent */, false /* once */,
                false /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getBuzzyBeepyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, true /* buzzy*/, false /* lights */);
    }

    private NotificationRecord getLightsNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, true /* buzzy*/, true /* lights */);
    }

    private NotificationRecord getCustomLightsNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, true /* buzzy*/, true /* lights */,
                true /* defaultVibration */, true /* defaultSound */, false /* defaultLights */,
                null, Notification.GROUP_ALERT_ALL);
    }

    private NotificationRecord getNotificationRecord(int id, boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights) {
        return getNotificationRecord(id, insistent, once, noisy, buzzy, lights, true, true, true,
                null, Notification.GROUP_ALERT_ALL);
    }

    private NotificationRecord getBeepyNotificationRecord(String groupKey, int groupAlertBehavior) {
        return getNotificationRecord(mId, false, false, true, false, false, true, true, true,
                groupKey, groupAlertBehavior);
    }

    private NotificationRecord getNotificationRecord(int id, boolean insistent, boolean once,
            boolean noisy, boolean buzzy, boolean lights, boolean defaultVibration,
            boolean defaultSound, boolean defaultLights, String groupKey, int groupAlertBehavior) {
        NotificationChannel channel =
                new NotificationChannel("test", "test", IMPORTANCE_HIGH);
        final Builder builder = new Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .setOnlyAlertOnce(once);

        int defaults = 0;
        if (noisy) {
            if (defaultSound) {
                defaults |= Notification.DEFAULT_SOUND;
                channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI,
                        Notification.AUDIO_ATTRIBUTES_DEFAULT);
            } else {
                builder.setSound(CUSTOM_SOUND);
                channel.setSound(CUSTOM_SOUND, CUSTOM_ATTRIBUTES);
            }
        } else {
            channel.setSound(null, null);
        }
        if (buzzy) {
            if (defaultVibration) {
                defaults |= Notification.DEFAULT_VIBRATE;
            } else {
                builder.setVibrate(CUSTOM_VIBRATION);
                channel.setVibrationPattern(CUSTOM_VIBRATION);
            }
            channel.enableVibration(true);
        }
        if (lights) {
            if (defaultLights) {
                defaults |= Notification.DEFAULT_LIGHTS;
            } else {
                builder.setLights(CUSTOM_LIGHT_COLOR, CUSTOM_LIGHT_ON, CUSTOM_LIGHT_OFF);
            }
            channel.enableLights(true);
        }
        builder.setDefaults(defaults);

        builder.setGroup(groupKey);
        builder.setGroupAlertBehavior(groupAlertBehavior);

        Notification n = builder.build();
        if (insistent) {
            n.flags |= Notification.FLAG_INSISTENT;
        }

        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, id, mTag, mUid,
                mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        mService.addNotification(r);
        return r;
    }

    //
    // Convenience functions for interacting with mocks
    //

    private void verifyNeverBeep() throws RemoteException {
        verify(mRingtonePlayer, never()).playAsync((Uri) anyObject(), (UserHandle) anyObject(),
                anyBoolean(), (AudioAttributes) anyObject());
    }

    private void verifyBeep() throws RemoteException {
        verify(mRingtonePlayer, times(1)).playAsync((Uri) anyObject(), (UserHandle) anyObject(),
                eq(true), (AudioAttributes) anyObject());
    }

    private void verifyBeepLooped() throws RemoteException {
        verify(mRingtonePlayer, times(1)).playAsync((Uri) anyObject(), (UserHandle) anyObject(),
                eq(false), (AudioAttributes) anyObject());
    }

    private void verifyCustomBeep() throws RemoteException {
        verify(mRingtonePlayer, times(1)).playAsync(eq(CUSTOM_SOUND), (UserHandle) anyObject(),
                eq(false), (AudioAttributes) anyObject());
    }

    private void verifyNeverStopAudio() throws RemoteException {
        verify(mRingtonePlayer, never()).stopAsync();
    }

    private void verifyStopAudio() throws RemoteException {
        verify(mRingtonePlayer, times(1)).stopAsync();
    }

    private void verifyNeverVibrate() {
        verify(mVibrator, never()).vibrate(anyInt(), anyString(), (VibrationEffect) anyObject(),
                (AudioAttributes) anyObject());
    }

    private void verifyVibrate() {
        verify(mVibrator, times(1)).vibrate(anyInt(), anyString(), argThat(mVibrateOnceMatcher),
                (AudioAttributes) anyObject());
    }

    private void verifyVibrateLooped() {
        verify(mVibrator, times(1)).vibrate(anyInt(), anyString(), argThat(mVibrateLoopMatcher),
                (AudioAttributes) anyObject());
    }

    private void verifyDelayedVibrateLooped() {
        verify(mVibrator, timeout(MAX_VIBRATION_DELAY).times(1)).vibrate(anyInt(), anyString(),
                argThat(mVibrateLoopMatcher), (AudioAttributes) anyObject());
    }

    private void verifyStopVibrate() {
        verify(mVibrator, times(1)).cancel();
    }

    private void verifyNeverStopVibrate() throws RemoteException {
        verify(mVibrator, never()).cancel();
    }

    private void verifyLights() {
        verify(mLight, times(1)).setFlashing(anyInt(), anyInt(), anyInt(), anyInt());
    }

    private void verifyCustomLights() {
        verify(mLight, times(1)).setFlashing(
                eq(CUSTOM_LIGHT_COLOR), anyInt(), eq(CUSTOM_LIGHT_ON), eq(CUSTOM_LIGHT_OFF));
    }

    //
    // Tests
    //

    @Test
    public void testLights() throws Exception {
        NotificationRecord r = getLightsNotification();
        r.setImportance(NotificationManager.IMPORTANCE_DEFAULT, "for testing");

        mService.buzzBeepBlinkLocked(r);

        verifyLights();
    }

    @Test
    public void testBeep() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyBeepLooped();
        verifyNeverVibrate();
    }

    @Test
    public void testBeepInsistently() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyBeep();
    }

    @Test
    public void testNoInterruptionForMin() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setImportance(NotificationManager.IMPORTANCE_MIN, "foo");

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyNeverVibrate();
    }

    @Test
    public void testNoInterruptionForIntercepted() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setIntercepted(true);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyNeverVibrate();
    }

    @Test
    public void testBeepTwice() throws Exception {
        NotificationRecord r = getBeepyNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        // update should beep
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);
        verifyBeepLooped();
    }

    @Test
    public void testHonorAlertOnlyOnceForBeep() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getBeepyOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        // update should not beep
        mService.buzzBeepBlinkLocked(s);
        verifyNeverBeep();
    }

    @Test
    public void testNoisyUpdateDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.buzzBeepBlinkLocked(r);
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);

        verifyNeverStopAudio();
    }

    @Test
    public void testNoisyOnceUpdateDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getBeepyOnceNotification();
        s.isUpdate = true;

        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(s);

        verifyNeverStopAudio();
    }

    @Test
    public void testQuietUpdateDoesNotCancelAudioFromOther() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;
        NotificationRecord other = getNoisyOtherNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(other); // this takes the audio stream
        Mockito.reset(mRingtonePlayer);

        // should not stop noise, since we no longer own it
        mService.buzzBeepBlinkLocked(s); // this no longer owns the stream
        verifyNeverStopAudio();
    }

    @Test
    public void testQuietInterloperDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord other = getQuietOtherNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        // should not stop noise, since it does not own it
        mService.buzzBeepBlinkLocked(other);
        verifyNeverStopAudio();
    }

    @Test
    public void testQuietUpdateCancelsAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        // quiet update should stop making noise
        mService.buzzBeepBlinkLocked(s);
        verifyStopAudio();
    }

    @Test
    public void testQuietOnceUpdateCancelsAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mRingtonePlayer);

        // stop making noise - this is a weird corner case, but quiet should override once
        mService.buzzBeepBlinkLocked(s);
        verifyStopAudio();
    }

    @Test
    public void testNoDemoteSoundToVibrateIfVibrateGiven() throws Exception {
        NotificationRecord r = getBuzzyBeepyNotification();
        assertTrue(r.getSound() != null);

        // the phone is quiet
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);

        mService.buzzBeepBlinkLocked(r);

        VibrationEffect effect = VibrationEffect.createWaveform(r.getVibration(), -1);

        verify(mVibrator, timeout(MAX_VIBRATION_DELAY).times(1)).vibrate(anyInt(), anyString(),
                eq(effect), (AudioAttributes) anyObject());
    }

    @Test
    public void testDemoteSoundToVibrate() throws Exception {
        NotificationRecord r = getBeepyNotification();
        assertTrue(r.getSound() != null);
        assertNull(r.getVibration());

        // the phone is quiet
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);

        mService.buzzBeepBlinkLocked(r);

        verify(mVibrator, timeout(MAX_VIBRATION_DELAY).times(1)).vibrate(anyInt(), anyString(),
                eq(FALLBACK_VIBRATION), (AudioAttributes) anyObject());
        verify(mRingtonePlayer, never()).playAsync
                (anyObject(), anyObject(), anyBoolean(), anyObject());
    }

    @Test
    public void testDemoteInsistentSoundToVibrate() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();
        assertTrue(r.getSound() != null);
        assertNull(r.getVibration());

        // the phone is quiet
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);

        mService.buzzBeepBlinkLocked(r);

        verifyDelayedVibrateLooped();
    }

    @Test
    public void testVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyVibrate();
    }

    @Test
    public void testInsistentVibrate() throws Exception {
        NotificationRecord r = getInsistentBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);
        verifyVibrateLooped();
    }

    @Test
    public void testVibrateTwice() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mVibrator);

        // update should vibrate
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);
        verifyVibrate();
    }

    @Test
    public void testGroupAlertSummarySilenceChild() throws Exception {
        NotificationRecord child = getBeepyNotificationRecord("a", GROUP_ALERT_SUMMARY);

        mService.buzzBeepBlinkLocked(child);

        verifyNeverBeep();
    }

    @Test
    public void testGroupAlertSummaryNoSilenceSummary() throws Exception {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_SUMMARY);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mService.buzzBeepBlinkLocked(summary);

        verifyBeepLooped();
    }

    @Test
    public void testGroupAlertSummaryNoSilenceNonGroupChild() throws Exception {
        NotificationRecord nonGroup = getBeepyNotificationRecord(null, GROUP_ALERT_SUMMARY);

        mService.buzzBeepBlinkLocked(nonGroup);

        verifyBeepLooped();
    }

    @Test
    public void testGroupAlertChildSilenceSummary() throws Exception {
        NotificationRecord summary = getBeepyNotificationRecord("a", GROUP_ALERT_CHILDREN);
        summary.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;

        mService.buzzBeepBlinkLocked(summary);

        verifyNeverBeep();
    }

    @Test
    public void testGroupAlertChildNoSilenceChild() throws Exception {
        NotificationRecord child = getBeepyNotificationRecord("a", GROUP_ALERT_CHILDREN);

        mService.buzzBeepBlinkLocked(child);

        verifyBeepLooped();
    }

    @Test
    public void testGroupAlertChildNoSilenceNonGroupSummary() throws Exception {
        NotificationRecord nonGroup = getBeepyNotificationRecord(null, GROUP_ALERT_CHILDREN);

        mService.buzzBeepBlinkLocked(nonGroup);

        verifyBeepLooped();
    }

    @Test
    public void testGroupAlertAllNoSilenceGroup() throws Exception {
        NotificationRecord group = getBeepyNotificationRecord("a", GROUP_ALERT_ALL);

        mService.buzzBeepBlinkLocked(group);

        verifyBeepLooped();
    }

    @Test
    public void testHonorAlertOnlyOnceForBuzz() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getBuzzyOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mVibrator);

        // update should not beep
        mService.buzzBeepBlinkLocked(s);
        verifyNeverVibrate();
    }

    @Test
    public void testNoisyUpdateDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);

        verifyNeverStopVibrate();
    }

    @Test
    public void testNoisyOnceUpdateDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getBuzzyOnceNotification();
        s.isUpdate = true;

        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(s);

        verifyNeverStopVibrate();
    }

    @Test
    public void testQuietUpdateDoesNotCancelVibrateFromOther() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;
        NotificationRecord other = getNoisyOtherNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(other); // this takes the vibrate stream
        Mockito.reset(mVibrator);

        // should not stop vibrate, since we no longer own it
        mService.buzzBeepBlinkLocked(s); // this no longer owns the stream
        verifyNeverStopVibrate();
    }

    @Test
    public void testQuietInterloperDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord other = getQuietOtherNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mVibrator);

        // should not stop noise, since it does not own it
        mService.buzzBeepBlinkLocked(other);
        verifyNeverStopVibrate();
    }

    @Test
    public void testQuietUpdateCancelsVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        verifyVibrate();

        // quiet update should stop making noise
        mService.buzzBeepBlinkLocked(s);
        verifyStopVibrate();
    }

    @Test
    public void testQuietOnceUpdateCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        verifyVibrate();

        // stop making noise - this is a weird corner case, but quiet should override once
        mService.buzzBeepBlinkLocked(s);
        verifyStopVibrate();
    }

    @Test
    public void testQuietUpdateCancelsDemotedVibrate() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getQuietNotification();

        // the phone is quiet
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);

        mService.buzzBeepBlinkLocked(r);

        // quiet update should stop making noise
        mService.buzzBeepBlinkLocked(s);
        verifyStopVibrate();
    }

    @Test
    public void testEmptyUriSoundTreatedAsNoSound() throws Exception {
        NotificationChannel channel = new NotificationChannel("test", "test", IMPORTANCE_HIGH);
        channel.setSound(Uri.EMPTY, null);
        final Notification n = new Builder(getContext(), "test")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).build();

        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 0, mTag, mUid,
                mPid, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, channel);
        mService.addNotification(r);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
    }

    static class VibrateRepeatMatcher implements ArgumentMatcher<VibrationEffect> {
        private final int mRepeatIndex;

        VibrateRepeatMatcher(int repeatIndex) {
            mRepeatIndex = repeatIndex;
        }

        @Override
        public boolean matches(VibrationEffect actual) {
            if (actual instanceof VibrationEffect.Waveform &&
                    ((VibrationEffect.Waveform) actual).getRepeatIndex() == mRepeatIndex) {
                return true;
            }
            // All non-waveform effects are essentially one shots.
            return mRepeatIndex == -1;
        }

        @Override
        public String toString() {
            return "repeatIndex=" + mRepeatIndex;
        }
    }
}
