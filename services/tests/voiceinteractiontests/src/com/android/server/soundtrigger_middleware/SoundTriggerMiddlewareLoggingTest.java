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

import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.ServiceEvent;
import static com.android.server.soundtrigger_middleware.SoundTriggerMiddlewareLogging.SessionEvent;
import static com.android.internal.util.LatencyTracker.ACTION_SHOW_VOICE_INTERACTION;

import static com.google.common.truth.Truth.assertThat;

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
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.os.BatteryStatsInternal;
import android.os.Process;
import android.os.RemoteException;

import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.FakeLatencyTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@RunWith(JUnit4.class)
public class SoundTriggerMiddlewareLoggingTest {
    private static final ServiceEvent.Type SERVICE_TYPE = ServiceEvent.Type.ATTACH;
    private static final SessionEvent.Type SESSION_TYPE = SessionEvent.Type.LOAD_MODEL;

    private FakeLatencyTracker mLatencyTracker;
    @Mock
    private BatteryStatsInternal mBatteryStatsInternal;
    @Mock
    private ISoundTriggerMiddlewareInternal mDelegateMiddleware;
    @Mock
    private ISoundTriggerCallback mISoundTriggerCallback;
    @Mock
    private ISoundTriggerModule mSoundTriggerModule;
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
    @FlakyTest(bugId = 275113847)
    public void testSetUpAndTearDown() {
    }

    @Test
    @FlakyTest(bugId = 275113847)
    public void testOnPhraseRecognitionStartsLatencyTrackerWithSuccessfulPhraseIdTrigger()
            throws RemoteException {
        ArgumentCaptor<ISoundTriggerCallback> soundTriggerCallbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerCallback.class);
        mSoundTriggerMiddlewareLogging.attach(0, mISoundTriggerCallback);
        verify(mDelegateMiddleware).attach(anyInt(), soundTriggerCallbackCaptor.capture());

        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.SUCCESS, Optional.of(100) /* keyphraseId */);

        assertThat(mLatencyTracker.getActiveActionStartTime(
                ACTION_SHOW_VOICE_INTERACTION)).isGreaterThan(-1);
    }

    @Test
    @FlakyTest(bugId = 275113847)
    public void testOnPhraseRecognitionRestartsActiveSession() throws RemoteException {
        ArgumentCaptor<ISoundTriggerCallback> soundTriggerCallbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerCallback.class);
        mSoundTriggerMiddlewareLogging.attach(0, mISoundTriggerCallback);
        verify(mDelegateMiddleware).attach(anyInt(), soundTriggerCallbackCaptor.capture());

        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.SUCCESS, Optional.of(100) /* keyphraseId */);
        long firstTriggerSessionStartTime = mLatencyTracker.getActiveActionStartTime(
                ACTION_SHOW_VOICE_INTERACTION);
        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.SUCCESS, Optional.of(100) /* keyphraseId */);
        assertThat(mLatencyTracker.getActiveActionStartTime(
                ACTION_SHOW_VOICE_INTERACTION)).isGreaterThan(-1);
        assertThat(mLatencyTracker.getActiveActionStartTime(
                ACTION_SHOW_VOICE_INTERACTION)).isNotEqualTo(firstTriggerSessionStartTime);
    }

    @Test
    @FlakyTest(bugId = 275113847)
    public void testOnPhraseRecognitionNeverStartsLatencyTrackerWithNonSuccessEvent()
            throws RemoteException {
        ArgumentCaptor<ISoundTriggerCallback> soundTriggerCallbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerCallback.class);
        mSoundTriggerMiddlewareLogging.attach(0, mISoundTriggerCallback);
        verify(mDelegateMiddleware).attach(anyInt(), soundTriggerCallbackCaptor.capture());

        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.ABORTED, Optional.of(100) /* keyphraseId */);

        assertThat(
                mLatencyTracker.getActiveActionStartTime(ACTION_SHOW_VOICE_INTERACTION)).isEqualTo(
                -1);
    }

    @Test
    @FlakyTest(bugId = 275113847)
    public void testOnPhraseRecognitionNeverStartsLatencyTrackerWithNoKeyphraseId()
            throws RemoteException {
        ArgumentCaptor<ISoundTriggerCallback> soundTriggerCallbackCaptor = ArgumentCaptor.forClass(
                ISoundTriggerCallback.class);
        mSoundTriggerMiddlewareLogging.attach(0, mISoundTriggerCallback);
        verify(mDelegateMiddleware).attach(anyInt(), soundTriggerCallbackCaptor.capture());

        triggerPhraseRecognitionEvent(soundTriggerCallbackCaptor.getValue(),
                RecognitionStatus.SUCCESS, Optional.empty() /* keyphraseId */);

        assertThat(
                mLatencyTracker.getActiveActionStartTime(ACTION_SHOW_VOICE_INTERACTION)).isEqualTo(
                -1);
    }

    private void triggerPhraseRecognitionEvent(ISoundTriggerCallback callback,
            @RecognitionStatus int triggerEventStatus, Optional<Integer> optionalKeyphraseId)
            throws RemoteException {
        // trigger a phrase recognition to start a latency tracker session
        PhraseRecognitionEvent successEventWithKeyphraseId = new PhraseRecognitionEvent();
        successEventWithKeyphraseId.common = new RecognitionEvent();
        successEventWithKeyphraseId.common.status = triggerEventStatus;
        if (optionalKeyphraseId.isPresent()) {
            PhraseRecognitionExtra recognitionExtra = new PhraseRecognitionExtra();
            recognitionExtra.id = optionalKeyphraseId.get();
            successEventWithKeyphraseId.phraseExtras =
                    new PhraseRecognitionExtra[]{recognitionExtra};
        }
        callback.onPhraseRecognition(0 /* modelHandle */, successEventWithKeyphraseId,
                0 /* captureSession */);
    }

    @Test
    public void serviceEventException_getStringContainsInfo() {
        String packageName = "com.android.test";
        Exception exception = new Exception("test");
        Object param1 = new Object();
        Object param2 = new Object();
        final var event = ServiceEvent.createForException(
                SERVICE_TYPE, packageName, exception, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SERVICE_TYPE.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(exception.toString());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void serviceEventExceptionNoArgs_getStringContainsInfo() {
        String packageName = "com.android.test";
        Exception exception = new Exception("test");
        final var event = ServiceEvent.createForException(
                SERVICE_TYPE, packageName, exception);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SERVICE_TYPE.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(exception.toString());
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void serviceEventReturn_getStringContainsInfo() {
        String packageName = "com.android.test";
        Object param1 = new Object();
        Object param2 = new Object();
        Object retValue = new Object();
        final var event = ServiceEvent.createForReturn(
                SERVICE_TYPE, packageName, retValue, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SERVICE_TYPE.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(retValue.toString());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void serviceEventReturnNoArgs_getStringContainsInfo() {
        String packageName = "com.android.test";
        Object retValue = new Object();
        final var event = ServiceEvent.createForReturn(
                SERVICE_TYPE, packageName, retValue);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SERVICE_TYPE.name());
        assertThat(stringRep).contains(packageName);
        assertThat(stringRep).contains(retValue.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventException_getStringContainsInfo() {
        Object param1 = new Object();
        Object param2 = new Object();
        Exception exception = new Exception("test");
        final var event = SessionEvent.createForException(
                SESSION_TYPE, exception, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(exception.toString());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void sessionEventExceptionNoArgs_getStringContainsInfo() {
        Exception exception = new Exception("test");
        final var event = SessionEvent.createForException(
                SESSION_TYPE, exception);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(exception.toString());
        assertThat(stringRep).ignoringCase().contains("error");
    }

    @Test
    public void sessionEventReturn_getStringContainsInfo() {
        Object param1 = new Object();
        Object param2 = new Object();
        Object retValue = new Object();
        final var event = SessionEvent.createForReturn(
                SESSION_TYPE, retValue, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(retValue.toString());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventReturnNoArgs_getStringContainsInfo() {
        Object retValue = new Object();
        final var event = SessionEvent.createForReturn(
                SESSION_TYPE, retValue);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(retValue.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventVoid_getStringContainsInfo() {
        Object param1 = new Object();
        Object param2 = new Object();
        final var event = SessionEvent.createForVoid(
                SESSION_TYPE, param1, param2);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).contains(param1.toString());
        assertThat(stringRep).contains(param2.toString());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }

    @Test
    public void sessionEventVoidNoArgs_getStringContainsInfo() {
        final var event = SessionEvent.createForVoid(
                SESSION_TYPE);
        final var stringRep = event.eventToString();
        assertThat(stringRep).contains(SESSION_TYPE.name());
        assertThat(stringRep).ignoringCase().doesNotContain("error");
    }
}
