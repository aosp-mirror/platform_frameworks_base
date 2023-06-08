/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import static com.android.internal.util.LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.app.ActivityThread;
import android.media.permission.Identity;
import android.media.permission.IdentityContext;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseRecognitionExtra;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.PhraseRecognitionEventSys;
import android.os.BatteryStatsInternal;
import android.os.Process;
import android.os.RemoteException;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.FakeLatencyTracker;
import com.android.modules.utils.testing.TestableDeviceConfig.TestableDeviceConfigRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class SoundTriggerMiddlewareLoggingLatencyTest {

    @Rule
    public TestableDeviceConfigRule mDeviceConfigRule = new TestableDeviceConfigRule();

    private FakeLatencyTracker mLatencyTracker;
    @Mock
    private BatteryStatsInternal mBatteryStatsInternal;
    @Mock
    private ISoundTriggerMiddlewareInternal mDelegateMiddleware;
    @Mock
    private ISoundTriggerCallback mISoundTriggerCallback;
    private SoundTriggerMiddlewareLogging mSoundTriggerMiddlewareLogging;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.WRITE_DEVICE_CONFIG,
                        Manifest.permission.READ_DEVICE_CONFIG);

        Identity identity = new Identity();
        identity.uid = Process.myUid();
        identity.pid = Process.myPid();
        identity.packageName = ActivityThread.currentOpPackageName();
        IdentityContext.create(identity);

        mLatencyTracker = FakeLatencyTracker.create();
        mLatencyTracker.forceEnabled(ACTION_SHOW_VOICE_INTERACTION, -1);
        mSoundTriggerMiddlewareLogging = new SoundTriggerMiddlewareLogging(mLatencyTracker,
                () -> mBatteryStatsInternal,
                mDelegateMiddleware);
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testSetUpAndTearDown() {
    }

    @Test
    public void testOnPhraseRecognitionStartsLatencyTrackerWithSuccessfulPhraseIdTrigger()
            throws RemoteException {
        ArgumentCaptor<ISoundTriggerCallback> soundTriggerCallbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerCallback.class);
        mSoundTriggerMiddlewareLogging.attach(0, mISoundTriggerCallback, false);
        verify(mDelegateMiddleware).attach(anyInt(), soundTriggerCallbackCaptor.capture(),
                anyBoolean());

        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.SUCCESS, 100 /* keyphraseId */);

        assertThat(mLatencyTracker.getActiveActionStartTime(
                ACTION_SHOW_VOICE_INTERACTION)).isGreaterThan(-1);
    }

    @Test
    public void testOnPhraseRecognitionRestartsActiveSession() throws RemoteException {
        ArgumentCaptor<ISoundTriggerCallback> soundTriggerCallbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerCallback.class);
        mSoundTriggerMiddlewareLogging.attach(0, mISoundTriggerCallback, false);
        verify(mDelegateMiddleware).attach(anyInt(), soundTriggerCallbackCaptor.capture(),
                anyBoolean());

        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.SUCCESS, 100 /* keyphraseId */);
        long firstTriggerSessionStartTime = mLatencyTracker.getActiveActionStartTime(
                ACTION_SHOW_VOICE_INTERACTION);
        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.SUCCESS, 100 /* keyphraseId */);
        assertThat(mLatencyTracker.getActiveActionStartTime(
                ACTION_SHOW_VOICE_INTERACTION)).isGreaterThan(-1);
        assertThat(mLatencyTracker.getActiveActionStartTime(
                ACTION_SHOW_VOICE_INTERACTION)).isNotEqualTo(firstTriggerSessionStartTime);
    }

    @Test
    public void testOnPhraseRecognitionNeverStartsLatencyTrackerWithNonSuccessEvent()
            throws RemoteException {
        ArgumentCaptor<ISoundTriggerCallback> soundTriggerCallbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerCallback.class);
        mSoundTriggerMiddlewareLogging.attach(0, mISoundTriggerCallback, false);
        verify(mDelegateMiddleware).attach(anyInt(), soundTriggerCallbackCaptor.capture(),
                anyBoolean());

        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.ABORTED, 100 /* keyphraseId */);

        assertThat(
                mLatencyTracker.getActiveActionStartTime(ACTION_SHOW_VOICE_INTERACTION)).isEqualTo(
                -1);
    }

    @Test
    public void testOnPhraseRecognitionNeverStartsLatencyTrackerWithNoKeyphraseId()
            throws RemoteException {
        ArgumentCaptor<ISoundTriggerCallback> soundTriggerCallbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerCallback.class);
        mSoundTriggerMiddlewareLogging.attach(0, mISoundTriggerCallback, false);
        verify(mDelegateMiddleware).attach(anyInt(), soundTriggerCallbackCaptor.capture(),
                anyBoolean());

        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.SUCCESS);

        assertThat(
                mLatencyTracker.getActiveActionStartTime(ACTION_SHOW_VOICE_INTERACTION)).isEqualTo(
                -1);
    }

    private void triggerPhraseRecognitionEvent(ISoundTriggerCallback callback,
            @RecognitionStatus int triggerEventStatus) throws RemoteException {
        triggerPhraseRecognitionEvent(callback, triggerEventStatus, -1 /* keyphraseId */);
    }

    private void triggerPhraseRecognitionEvent(ISoundTriggerCallback callback,
            @RecognitionStatus int triggerEventStatus, int keyphraseId) throws RemoteException {
        // trigger a phrase recognition to start a latency tracker session
        PhraseRecognitionEvent successEventWithKeyphraseId = new PhraseRecognitionEvent();
        successEventWithKeyphraseId.common = new RecognitionEvent();
        successEventWithKeyphraseId.common.status = triggerEventStatus;
        if (keyphraseId > 0) {
            PhraseRecognitionExtra recognitionExtra = new PhraseRecognitionExtra();
            recognitionExtra.id = keyphraseId;
            successEventWithKeyphraseId.phraseExtras =
                    new PhraseRecognitionExtra[]{recognitionExtra};
        }
        PhraseRecognitionEventSys phraseRecognitionEventSys = new PhraseRecognitionEventSys();
        phraseRecognitionEventSys.phraseRecognitionEvent = successEventWithKeyphraseId;
        phraseRecognitionEventSys.halEventReceivedMillis = 12345;
        callback.onPhraseRecognition(0 /* modelHandle */, phraseRecognitionEventSys,
                0 /* captureSession */);
    }

}
