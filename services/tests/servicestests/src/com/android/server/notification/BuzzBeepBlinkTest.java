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


import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.Vibrator;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.StatusBarNotification;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BuzzBeepBlinkTest extends AndroidTestCase {

    @Mock AudioManager mAudioManager;
    @Mock Vibrator mVibrator;
    @Mock android.media.IRingtonePlayer mRingtonePlayer;
    @Mock Handler mHandler;

    private NotificationManagerService mService;
    private String mPkg = "com.android.server.notification";
    private int mId = 1001;
    private int mOtherId = 1002;
    private String mTag = null;
    private int mUid = 1000;
    private int mPid = 2000;
    private int mScore = 10;
    private android.os.UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());

    @Override
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
        mService.setSystemNotificationSound("beep!");
    }

    //
    // Convenience functions for creating notification records
    //

    private NotificationRecord getNoisyOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
                true /* noisy */, true /* buzzy*/);
    }

    private NotificationRecord getBeepyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/);
    }

    private NotificationRecord getBeepyOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                true /* noisy */, false /* buzzy*/);
    }

    private NotificationRecord getQuietNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                false /* noisy */, false /* buzzy*/);
    }

    private NotificationRecord getQuietOtherNotification() {
        return getNotificationRecord(mOtherId, false /* insistent */, false /* once */,
                false /* noisy */, false /* buzzy*/);
    }

    private NotificationRecord getQuietOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, false /* buzzy*/);
    }

    private NotificationRecord getInsistentBeepyNotification() {
        return getNotificationRecord(mId, true /* insistent */, false /* once */,
                true /* noisy */, false /* buzzy*/);
    }

    private NotificationRecord getBuzzyNotification() {
        return getNotificationRecord(mId, false /* insistent */, false /* once */,
                false /* noisy */, true /* buzzy*/);
    }

    private NotificationRecord getBuzzyOnceNotification() {
        return getNotificationRecord(mId, false /* insistent */, true /* once */,
                false /* noisy */, true /* buzzy*/);
    }

    private NotificationRecord getInsistentBuzzyNotification() {
        return getNotificationRecord(mId, true /* insistent */, false /* once */,
                false /* noisy */, true /* buzzy*/);
    }

    private NotificationRecord getNotificationRecord(int id, boolean insistent, boolean once,
            boolean noisy, boolean buzzy) {
        final Builder builder = new Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .setOnlyAlertOnce(once);

        int defaults = 0;
        if (noisy) {
            defaults |= Notification.DEFAULT_SOUND;
        }
        if (buzzy) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }
        builder.setDefaults(defaults);

        Notification n = builder.build();
        if (insistent) {
            n.flags |= Notification.FLAG_INSISTENT;
        }
        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, id, mTag, mUid, mPid,
                mScore, n, mUser, System.currentTimeMillis());
        return new NotificationRecord(getContext(), sbn);
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

    private void verifyNeverStopAudio() throws RemoteException {
        verify(mRingtonePlayer, never()).stopAsync();
    }

    private void verifyStopAudio() throws RemoteException {
        verify(mRingtonePlayer, times(1)).stopAsync();
    }

    private void verifyNeverVibrate() {
        verify(mVibrator, never()).vibrate(anyInt(), anyString(), (long[]) anyObject(),
                anyInt(), (AudioAttributes) anyObject());
    }

    private void verifyVibrate() {
        verify(mVibrator, times(1)).vibrate(anyInt(), anyString(), (long[]) anyObject(),
                eq(-1), (AudioAttributes) anyObject());
    }

    private void verifyVibrateLooped() {
        verify(mVibrator, times(1)).vibrate(anyInt(), anyString(), (long[]) anyObject(),
                eq(0), (AudioAttributes) anyObject());
    }

    private void verifyStopVibrate() {
        verify(mVibrator, times(1)).cancel();
    }

    private void verifyNeverStopVibrate() throws RemoteException {
        verify(mVibrator, never()).cancel();
    }

    @SmallTest
    public void testBeep() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyBeepLooped();
        verifyNeverVibrate();
    }

    //
    // Tests
    //

    @SmallTest
    public void testBeepInsistently() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyBeep();
    }

    @SmallTest
    public void testNoInterruptionForMin() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setImportance(Ranking.IMPORTANCE_MIN, "foo");

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyNeverVibrate();
    }

    @SmallTest
    public void testNoInterruptionForIntercepted() throws Exception {
        NotificationRecord r = getBeepyNotification();
        r.setIntercepted(true);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyNeverVibrate();
    }

    @SmallTest
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

    @SmallTest
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

    @SmallTest
    public void testNoisyUpdateDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();

        mService.buzzBeepBlinkLocked(r);
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);

        verifyNeverStopAudio();
    }

    @SmallTest
    public void testNoisyOnceUpdateDoesNotCancelAudio() throws Exception {
        NotificationRecord r = getBeepyNotification();
        NotificationRecord s = getBeepyOnceNotification();
        s.isUpdate = true;

        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(s);

        verifyNeverStopAudio();
    }

    @SmallTest
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

    @SmallTest
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

    @SmallTest
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

    @SmallTest
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

    @SmallTest
    public void testDemoteSoundToVibrate() throws Exception {
        NotificationRecord r = getBeepyNotification();

        // the phone is quiet
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyVibrate();
    }

    @SmallTest
    public void testDemotInsistenteSoundToVibrate() throws Exception {
        NotificationRecord r = getInsistentBeepyNotification();

        // the phone is quiet
        when(mAudioManager.getStreamVolume(anyInt())).thenReturn(0);
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);

        mService.buzzBeepBlinkLocked(r);

        verifyVibrateLooped();
    }

    @SmallTest
    public void testVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);

        verifyNeverBeep();
        verifyVibrate();
    }

    @SmallTest
    public void testInsistenteVibrate() throws Exception {
        NotificationRecord r = getInsistentBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);
        verifyVibrateLooped();
    }

    @SmallTest
    public void testVibratTwice() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mVibrator);

        // update should vibrate
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);
        verifyVibrate();
    }

    @SmallTest
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

    @SmallTest
    public void testNoisyUpdateDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();

        mService.buzzBeepBlinkLocked(r);
        r.isUpdate = true;
        mService.buzzBeepBlinkLocked(r);

        verifyNeverStopVibrate();
    }

    @SmallTest
    public void testNoisyOnceUpdateDoesNotCancelVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getBuzzyOnceNotification();
        s.isUpdate = true;

        mService.buzzBeepBlinkLocked(r);
        mService.buzzBeepBlinkLocked(s);

        verifyNeverStopVibrate();
    }

    @SmallTest
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

    @SmallTest
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

    @SmallTest
    public void testQuietUpdateCancelsVibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);

        // quiet update should stop making noise
        mService.buzzBeepBlinkLocked(s);
        verifyStopVibrate();
    }

    @SmallTest
    public void testQuietOnceUpdateCancelsvibrate() throws Exception {
        NotificationRecord r = getBuzzyNotification();
        NotificationRecord s = getQuietOnceNotification();
        s.isUpdate = true;

        // set up internal state
        mService.buzzBeepBlinkLocked(r);
        Mockito.reset(mVibrator);

        // stop making noise - this is a weird corner case, but quiet should override once
        mService.buzzBeepBlinkLocked(s);
        verifyStopVibrate();
    }

    @SmallTest
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
}
