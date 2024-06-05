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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.volume;

import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.Process;
import android.testing.TestableLooper;
import android.view.accessibility.AccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.util.RingerModeLiveData;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.concurrency.FakeThreadFactory;
import com.android.systemui.util.concurrency.ThreadFactory;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
@SmallTest
@TestableLooper.RunWithLooper
public class VolumeDialogControllerImplTest extends SysuiTestCase {

    TestableVolumeDialogControllerImpl mVolumeController;
    VolumeDialogControllerImpl.C mCallback;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private RingerModeTracker mRingerModeTracker;
    @Mock
    private RingerModeLiveData mRingerModeLiveData;
    @Mock
    private RingerModeLiveData mRingerModeInternalLiveData;
    private final FakeThreadFactory mThreadFactory = new FakeThreadFactory(
            new FakeExecutor(new FakeSystemClock()));
    @Mock
    private AudioManager mAudioManager;
    @Mock
    private NotificationManager mNotificationManager;
    @Mock
    private VibratorHelper mVibrator;
    @Mock
    private IAudioService mIAudioService;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private WakefulnessLifecycle mWakefullnessLifcycle;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private ActivityManager mActivityManager;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private DumpManager mDumpManager;


    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mRingerModeTracker.getRingerMode()).thenReturn(mRingerModeLiveData);
        when(mRingerModeTracker.getRingerModeInternal()).thenReturn(mRingerModeInternalLiveData);
        // Initial non-set value
        when(mRingerModeLiveData.getValue()).thenReturn(-1);
        when(mRingerModeInternalLiveData.getValue()).thenReturn(-1);
        when(mUserTracker.getUserId()).thenReturn(ActivityManager.getCurrentUser());
        when(mUserTracker.getUserContext()).thenReturn(mContext);
        // Enable group volume adjustments
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_volumeAdjustmentForRemoteGroupSessions,
                true);

        mCallback = mock(VolumeDialogControllerImpl.C.class);
        mThreadFactory.setLooper(TestableLooper.get(this).getLooper());
        mVolumeController = new TestableVolumeDialogControllerImpl(mContext,
                mBroadcastDispatcher, mRingerModeTracker, mThreadFactory, mAudioManager,
                mNotificationManager, mVibrator, mIAudioService, mAccessibilityManager,
                mPackageManager, mWakefullnessLifcycle, mKeyguardManager,
                mActivityManager, mUserTracker, mDumpManager, mCallback);
        mVolumeController.setEnableDialogs(true, true);
    }

    @Test
    public void testRegisteredWithDispatcher() {
        verify(mBroadcastDispatcher).registerReceiverWithHandler(any(BroadcastReceiver.class),
                any(IntentFilter.class),
                any(Handler.class)); // VolumeDialogControllerImpl does not call with user
    }

    @Test
    public void testVolumeChangeW_deviceNotInteractiveAOD() {
        mVolumeController.setDeviceInteractive(false);
        when(mWakefullnessLifcycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        mVolumeController.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        verify(mCallback, never()).onShowRequested(Events.SHOW_REASON_VOLUME_CHANGED, false,
                LOCK_TASK_MODE_NONE);
    }

    @Test
    public void testVolumeChangeW_deviceInteractive() {
        mVolumeController.setDeviceInteractive(true);
        when(mWakefullnessLifcycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        mVolumeController.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        verify(mCallback, times(1)).onShowRequested(Events.SHOW_REASON_VOLUME_CHANGED, false,
                LOCK_TASK_MODE_NONE);
    }

    @Test
    public void testVolumeChangeW_deviceInteractive_StartedSleeping() {
        mVolumeController.setDeviceInteractive(true);
        when(mWakefullnessLifcycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        mVolumeController.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        mVolumeController.setDeviceInteractive(false);
        when(mWakefullnessLifcycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP);
        mVolumeController.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        verify(mCallback, times(1)).onShowRequested(Events.SHOW_REASON_VOLUME_CHANGED, false,
                LOCK_TASK_MODE_NONE);
    }

    @Test
    public void testVolumeChangeW_deviceOutFromBLEHeadset_doStateChanged() {
        mVolumeController.setDeviceInteractive(false);
        when(mWakefullnessLifcycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        when(mAudioManager.getDevicesForStream(AudioManager.STREAM_VOICE_CALL)).thenReturn(
                AudioManager.DEVICE_OUT_BLE_HEADSET);

        mVolumeController.onVolumeChangedW(
                AudioManager.STREAM_VOICE_CALL, AudioManager.FLAG_SHOW_UI);

        verify(mCallback, times(1)).onStateChanged(any());
    }

    @Test
    public void testVolumeChangeW_deviceOutFromA2DP_doStateChanged() {
        mVolumeController.setDeviceInteractive(false);
        when(mWakefullnessLifcycle.getWakefulness()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        when(mAudioManager.getDevicesForStream(AudioManager.STREAM_VOICE_CALL)).thenReturn(
                AudioManager.DEVICE_OUT_BLUETOOTH_A2DP);

        mVolumeController.onVolumeChangedW(
                AudioManager.STREAM_VOICE_CALL, AudioManager.FLAG_SHOW_UI);

        verify(mCallback, never()).onStateChanged(any());
    }

    @Test
    public void testOnRemoteVolumeChanged_newStream_noNullPointer() {
        MediaSession.Token token = new MediaSession.Token(Process.myUid(), null);
        mVolumeController.mMediaSessionsCallbacksW.onRemoteVolumeChanged(token, 0);
    }

    @Test
    public void testOnRemoteRemove_newStream_noNullPointer() {
        MediaSession.Token token = new MediaSession.Token(Process.myUid(), null);
        mVolumeController.mMediaSessionsCallbacksW.onRemoteRemoved(token);
    }

    @Test
    public void testRingerModeLiveDataObserving() {
        verify(mRingerModeLiveData).observeForever(any());
        verify(mRingerModeInternalLiveData).observeForever(any());
    }

    @Test
    public void testAddCallbackWithUserTracker() {
        verify(mUserTracker).addCallback(any(UserTracker.Callback.class), any(Executor.class));
    }

    static class TestableVolumeDialogControllerImpl extends VolumeDialogControllerImpl {
        private final WakefulnessLifecycle.Observer mWakefullessLifecycleObserver;

        TestableVolumeDialogControllerImpl(
                Context context,
                BroadcastDispatcher broadcastDispatcher,
                RingerModeTracker ringerModeTracker,
                ThreadFactory theadFactory,
                AudioManager audioManager,
                NotificationManager notificationManager,
                VibratorHelper optionalVibrator,
                IAudioService iAudioService,
                AccessibilityManager accessibilityManager,
                PackageManager packageManager,
                WakefulnessLifecycle wakefulnessLifecycle,
                KeyguardManager keyguardManager,
                ActivityManager activityManager,
                UserTracker userTracker,
                DumpManager dumpManager,
                C callback) {
            super(context, broadcastDispatcher, ringerModeTracker, theadFactory, audioManager,
                    notificationManager, optionalVibrator, iAudioService, accessibilityManager,
                    packageManager, wakefulnessLifecycle, keyguardManager,
                    activityManager, userTracker, dumpManager);
            mCallbacks = callback;

            ArgumentCaptor<WakefulnessLifecycle.Observer> observerCaptor =
                    ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
            verify(wakefulnessLifecycle).addObserver(observerCaptor.capture());
            mWakefullessLifecycleObserver = observerCaptor.getValue();
        }

        public void setDeviceInteractive(boolean interactive) {
            if (interactive) {
                mWakefullessLifecycleObserver.onStartedWakingUp();
            } else {
                mWakefullessLifecycleObserver.onFinishedGoingToSleep();
            }
        }
    }

//    static class TestableVolumeDialogControllerImpl extends VolumeDialogControllerImpl {
//        TestableVolumeDialogControllerImpl(Context context, C callback,
//                BroadcastDispatcher broadcastDispatcher, RingerModeTracker ringerModeTracker,
//                ThreadFactory threadFactory) {
//            super(
//                    context, broadcastDispatcher,
//                    s == null ? Optional.empty() : Optional.of(() -> s), ringerModeTracker);
//            mCallbacks = callback;
//        }
//    }

}
