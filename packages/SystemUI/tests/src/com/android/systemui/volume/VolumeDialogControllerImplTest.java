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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.Process;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.util.RingerModeLiveData;
import com.android.systemui.util.RingerModeTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@TestableLooper.RunWithLooper
public class VolumeDialogControllerImplTest extends SysuiTestCase {

    TestableVolumeDialogControllerImpl mVolumeController;
    VolumeDialogControllerImpl.C mCallback;
    StatusBar mStatusBar;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private RingerModeTracker mRingerModeTracker;
    @Mock
    private RingerModeLiveData mRingerModeLiveData;
    @Mock
    private RingerModeLiveData mRingerModeInternalLiveData;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mRingerModeTracker.getRingerMode()).thenReturn(mRingerModeLiveData);
        when(mRingerModeTracker.getRingerModeInternal()).thenReturn(mRingerModeInternalLiveData);
        // Initial non-set value
        when(mRingerModeLiveData.getValue()).thenReturn(-1);
        when(mRingerModeInternalLiveData.getValue()).thenReturn(-1);

        mCallback = mock(VolumeDialogControllerImpl.C.class);
        mStatusBar = mock(StatusBar.class);
        mVolumeController = new TestableVolumeDialogControllerImpl(mContext, mCallback, mStatusBar,
                mBroadcastDispatcher, mRingerModeTracker);
        mVolumeController.setEnableDialogs(true, true);
    }

    @After
    public void tearDown() {
        mVolumeController.destroy();
    }

    @Test
    public void testRegisteredWithDispatcher() {
        verify(mBroadcastDispatcher).registerReceiverWithHandler(any(BroadcastReceiver.class),
                any(IntentFilter.class),
                any(Handler.class)); // VolumeDialogControllerImpl does not call with user
    }

    @Test
    public void testVolumeChangeW_deviceNotInteractiveAOD() {
        when(mStatusBar.isDeviceInteractive()).thenReturn(false);
        when(mStatusBar.getWakefulnessState()).thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        mVolumeController.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        verify(mCallback, never()).onShowRequested(Events.SHOW_REASON_VOLUME_CHANGED);
    }

    @Test
    public void testVolumeChangeW_deviceInteractive() {
        when(mStatusBar.isDeviceInteractive()).thenReturn(true);
        when(mStatusBar.getWakefulnessState()).thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        mVolumeController.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        verify(mCallback, times(1)).onShowRequested(Events.SHOW_REASON_VOLUME_CHANGED);
    }

    @Test
    public void testVolumeChangeW_deviceInteractive_StartedSleeping() {
        when(mStatusBar.isDeviceInteractive()).thenReturn(true);
        when(mStatusBar.getWakefulnessState()).thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE);
        mVolumeController.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        when(mStatusBar.isDeviceInteractive()).thenReturn(false);
        when(mStatusBar.getWakefulnessState()).thenReturn(
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP);
        mVolumeController.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        verify(mCallback, times(1)).onShowRequested(Events.SHOW_REASON_VOLUME_CHANGED);
    }

    @Test
    public void testVolumeChangeW_nullStatusBar() {
        VolumeDialogControllerImpl.C callback = mock(VolumeDialogControllerImpl.C.class);
        TestableVolumeDialogControllerImpl
                nullStatusBarTestableDialog =
                new TestableVolumeDialogControllerImpl(
                        mContext, callback, null, mBroadcastDispatcher, mRingerModeTracker);
        nullStatusBarTestableDialog.setEnableDialogs(true, true);
        nullStatusBarTestableDialog.onVolumeChangedW(0, AudioManager.FLAG_SHOW_UI);
        verify(callback, times(1)).onShowRequested(Events.SHOW_REASON_VOLUME_CHANGED);
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
    public void testRingerModeOnDestroy_observersRemoved() {
        mVolumeController.destroy();

        verify(mRingerModeLiveData).removeObserver(any());
        verify(mRingerModeInternalLiveData).removeObserver(any());
    }

    static class TestableVolumeDialogControllerImpl extends VolumeDialogControllerImpl {
        TestableVolumeDialogControllerImpl(Context context, C callback, StatusBar s,
                BroadcastDispatcher broadcastDispatcher, RingerModeTracker ringerModeTracker) {
            super(
                    context, broadcastDispatcher,
                    s == null ? Optional.empty() : Optional.of(() -> s), ringerModeTracker);
            mCallbacks = callback;
        }
    }

}
