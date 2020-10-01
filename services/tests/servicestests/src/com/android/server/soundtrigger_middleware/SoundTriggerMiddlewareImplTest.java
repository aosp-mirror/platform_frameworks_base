/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.audio.common.V2_0.AudioConfig;
import android.hardware.audio.common.V2_0.Uuid;
import android.hardware.soundtrigger.V2_3.OptionalModelParameterRange;
import android.media.audio.common.AudioChannelMask;
import android.media.audio.common.AudioFormat;
import android.media.soundtrigger_middleware.AudioCapabilities;
import android.media.soundtrigger_middleware.ConfidenceLevel;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameter;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.Phrase;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseRecognitionExtra;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.RecognitionMode;
import android.media.soundtrigger_middleware.RecognitionStatus;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundModelType;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.media.soundtrigger_middleware.SoundTriggerModuleProperties;
import android.os.HidlMemoryUtil;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.Pair;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;

@RunWith(Parameterized.class)
public class SoundTriggerMiddlewareImplTest {
    private static final String TAG = "SoundTriggerMiddlewareImplTest";

    // We run the test once for every version of the underlying driver.
    @Parameterized.Parameters
    public static Object[] data() {
        return new Object[]{
                mock(android.hardware.soundtrigger.V2_0.ISoundTriggerHw.class),
                mock(android.hardware.soundtrigger.V2_1.ISoundTriggerHw.class),
                mock(android.hardware.soundtrigger.V2_2.ISoundTriggerHw.class),
                mock(android.hardware.soundtrigger.V2_3.ISoundTriggerHw.class),
        };
    }

    @Mock
    @Parameterized.Parameter
    public android.hardware.soundtrigger.V2_0.ISoundTriggerHw mHalDriver;

    @Mock
    private SoundTriggerMiddlewareImpl.AudioSessionProvider mAudioSessionProvider = mock(
            SoundTriggerMiddlewareImpl.AudioSessionProvider.class);

    private SoundTriggerMiddlewareImpl mService;

    private static ISoundTriggerCallback createCallbackMock() {
        return mock(ISoundTriggerCallback.Stub.class, Mockito.CALLS_REAL_METHODS);
    }

    private static SoundModel createGenericSoundModel() {
        return createSoundModel(SoundModelType.GENERIC);
    }

    private static FileDescriptor byteArrayToFileDescriptor(byte[] data) {
        try {
            SharedMemory shmem = SharedMemory.create("", data.length);
            ByteBuffer buffer = shmem.mapReadWrite();
            buffer.put(data);
            return shmem.getFileDescriptor();
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }

    private static SoundModel createSoundModel(int type) {
        SoundModel model = new SoundModel();
        model.type = type;
        model.uuid = "12345678-2345-3456-4567-abcdef987654";
        model.vendorUuid = "87654321-5432-6543-7654-456789fedcba";
        byte[] data = new byte[]{91, 92, 93, 94, 95};
        model.data = byteArrayToFileDescriptor(data);
        model.dataSize = data.length;
        return model;
    }

    private static PhraseSoundModel createPhraseSoundModel() {
        PhraseSoundModel model = new PhraseSoundModel();
        model.common = createSoundModel(SoundModelType.KEYPHRASE);
        model.phrases = new Phrase[1];
        model.phrases[0] = new Phrase();
        model.phrases[0].id = 123;
        model.phrases[0].users = new int[]{5, 6, 7};
        model.phrases[0].locale = "locale";
        model.phrases[0].text = "text";
        model.phrases[0].recognitionModes =
                RecognitionMode.USER_AUTHENTICATION | RecognitionMode.USER_IDENTIFICATION;
        return model;
    }

    private static android.hardware.soundtrigger.V2_0.ISoundTriggerHw.Properties createDefaultProperties(
            boolean supportConcurrentCapture) {
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.Properties properties =
                new android.hardware.soundtrigger.V2_0.ISoundTriggerHw.Properties();
        properties.implementor = "implementor";
        properties.description = "description";
        properties.version = 123;
        properties.uuid = new Uuid();
        properties.uuid.timeLow = 1;
        properties.uuid.timeMid = 2;
        properties.uuid.versionAndTimeHigh = 3;
        properties.uuid.variantAndClockSeqHigh = 4;
        properties.uuid.node = new byte[]{5, 6, 7, 8, 9, 10};

        properties.maxSoundModels = 456;
        properties.maxKeyPhrases = 567;
        properties.maxUsers = 678;
        properties.recognitionModes =
                android.hardware.soundtrigger.V2_0.RecognitionMode.VOICE_TRIGGER
                | android.hardware.soundtrigger.V2_0.RecognitionMode.USER_IDENTIFICATION
                | android.hardware.soundtrigger.V2_0.RecognitionMode.USER_AUTHENTICATION
                | android.hardware.soundtrigger.V2_0.RecognitionMode.GENERIC_TRIGGER;
        properties.captureTransition = true;
        properties.maxBufferMs = 321;
        properties.concurrentCapture = supportConcurrentCapture;
        properties.triggerInEvent = true;
        properties.powerConsumptionMw = 432;
        return properties;
    }

    private static android.hardware.soundtrigger.V2_3.Properties createDefaultProperties_2_3(
            boolean supportConcurrentCapture) {
        android.hardware.soundtrigger.V2_3.Properties properties =
                new android.hardware.soundtrigger.V2_3.Properties();
        properties.base = createDefaultProperties(supportConcurrentCapture);
        properties.supportedModelArch = "supportedModelArch";
        properties.audioCapabilities =
                android.hardware.soundtrigger.V2_3.AudioCapabilities.ECHO_CANCELLATION
                        | android.hardware.soundtrigger.V2_3.AudioCapabilities.NOISE_SUPPRESSION;
        return properties;
    }

    private void validateDefaultProperties(SoundTriggerModuleProperties properties,
            boolean supportConcurrentCapture) {
        assertEquals("implementor", properties.implementor);
        assertEquals("description", properties.description);
        assertEquals(123, properties.version);
        assertEquals("00000001-0002-0003-0004-05060708090a", properties.uuid);
        assertEquals(456, properties.maxSoundModels);
        assertEquals(567, properties.maxKeyPhrases);
        assertEquals(678, properties.maxUsers);
        assertEquals(RecognitionMode.GENERIC_TRIGGER
                | RecognitionMode.USER_AUTHENTICATION
                | RecognitionMode.USER_IDENTIFICATION
                | RecognitionMode.VOICE_TRIGGER, properties.recognitionModes);
        assertTrue(properties.captureTransition);
        assertEquals(321, properties.maxBufferMs);
        assertEquals(supportConcurrentCapture, properties.concurrentCapture);
        assertTrue(properties.triggerInEvent);
        assertEquals(432, properties.powerConsumptionMw);

        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            assertEquals("supportedModelArch", properties.supportedModelArch);
            assertEquals(AudioCapabilities.ECHO_CANCELLATION | AudioCapabilities.NOISE_SUPPRESSION,
                    properties.audioCapabilities);
        } else {
            assertEquals("", properties.supportedModelArch);
            assertEquals(0, properties.audioCapabilities);
        }
    }

    private void verifyNotGetProperties() throws RemoteException {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            verify((android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver,
                    never()).getProperties(any());
        }
    }

    private static android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionEvent createRecognitionEvent_2_0(
            int hwHandle,
            int status) {
        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionEvent halEvent =
                new android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionEvent();
        halEvent.status = status;
        halEvent.type = SoundModelType.GENERIC;
        halEvent.model = hwHandle;
        halEvent.captureAvailable = true;
        // This field is ignored.
        halEvent.captureSession = 123;
        halEvent.captureDelayMs = 234;
        halEvent.capturePreambleMs = 345;
        halEvent.triggerInData = true;
        halEvent.audioConfig = new AudioConfig();
        halEvent.audioConfig.sampleRateHz = 456;
        halEvent.audioConfig.channelMask = AudioChannelMask.IN_LEFT;
        halEvent.audioConfig.format = AudioFormat.MP3;
        // hwEvent.audioConfig.offloadInfo is irrelevant.
        halEvent.data.add((byte) 31);
        halEvent.data.add((byte) 32);
        halEvent.data.add((byte) 33);
        return halEvent;
    }

    private static android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent createRecognitionEvent_2_1(
            int hwHandle,
            int status) {
        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent halEvent =
                new android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent();
        halEvent.header = createRecognitionEvent_2_0(hwHandle, status);
        halEvent.header.data.clear();
        halEvent.data = HidlMemoryUtil.byteArrayToHidlMemory(new byte[]{31, 32, 33});
        return halEvent;
    }

    private static void validateRecognitionEvent(RecognitionEvent event, int status) {
        assertEquals(status, event.status);
        assertEquals(SoundModelType.GENERIC, event.type);
        assertTrue(event.captureAvailable);
        assertEquals(101, event.captureSession);
        assertEquals(234, event.captureDelayMs);
        assertEquals(345, event.capturePreambleMs);
        assertTrue(event.triggerInData);
        assertEquals(456, event.audioConfig.sampleRateHz);
        assertEquals(AudioChannelMask.IN_LEFT, event.audioConfig.channelMask);
        assertEquals(AudioFormat.MP3, event.audioConfig.format);
    }

    private static android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.PhraseRecognitionEvent createPhraseRecognitionEvent_2_0(
            int hwHandle, int status) {
        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.PhraseRecognitionEvent halEvent =
                new android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.PhraseRecognitionEvent();
        halEvent.common = createRecognitionEvent_2_0(hwHandle, status);

        android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra halExtra =
                new android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra();
        halExtra.id = 123;
        halExtra.confidenceLevel = 52;
        halExtra.recognitionModes = android.hardware.soundtrigger.V2_0.RecognitionMode.VOICE_TRIGGER
                | android.hardware.soundtrigger.V2_0.RecognitionMode.GENERIC_TRIGGER;
        android.hardware.soundtrigger.V2_0.ConfidenceLevel halLevel =
                new android.hardware.soundtrigger.V2_0.ConfidenceLevel();
        halLevel.userId = 31;
        halLevel.levelPercent = 43;
        halExtra.levels.add(halLevel);
        halEvent.phraseExtras.add(halExtra);
        return halEvent;
    }

    private static android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent createPhraseRecognitionEvent_2_1(
            int hwHandle, int status) {
        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent halEvent =
                new android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent();
        halEvent.common = createRecognitionEvent_2_1(hwHandle, status);

        android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra halExtra =
                new android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra();
        halExtra.id = 123;
        halExtra.confidenceLevel = 52;
        halExtra.recognitionModes = android.hardware.soundtrigger.V2_0.RecognitionMode.VOICE_TRIGGER
                | android.hardware.soundtrigger.V2_0.RecognitionMode.GENERIC_TRIGGER;
        android.hardware.soundtrigger.V2_0.ConfidenceLevel halLevel =
                new android.hardware.soundtrigger.V2_0.ConfidenceLevel();
        halLevel.userId = 31;
        halLevel.levelPercent = 43;
        halExtra.levels.add(halLevel);
        halEvent.phraseExtras.add(halExtra);
        return halEvent;
    }

    private static void validatePhraseRecognitionEvent(PhraseRecognitionEvent event, int status) {
        validateRecognitionEvent(event.common, status);

        assertEquals(1, event.phraseExtras.length);
        assertEquals(123, event.phraseExtras[0].id);
        assertEquals(52, event.phraseExtras[0].confidenceLevel);
        assertEquals(RecognitionMode.VOICE_TRIGGER | RecognitionMode.GENERIC_TRIGGER,
                event.phraseExtras[0].recognitionModes);
        assertEquals(1, event.phraseExtras[0].levels.length);
        assertEquals(31, event.phraseExtras[0].levels[0].userId);
        assertEquals(43, event.phraseExtras[0].levels[0].levelPercent);
    }

    private void initService(boolean supportConcurrentCapture) throws RemoteException {
        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.Properties properties =
                    createDefaultProperties(
                            supportConcurrentCapture);
            ((android.hardware.soundtrigger.V2_0.ISoundTriggerHw.getPropertiesCallback) invocation.getArgument(
                    0)).onValues(0,
                    properties);
            return null;
        }).when(mHalDriver).getProperties(any());

        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                    (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;
            doAnswer(invocation -> {
                android.hardware.soundtrigger.V2_3.Properties properties =
                        createDefaultProperties_2_3(
                                supportConcurrentCapture);
                ((android.hardware.soundtrigger.V2_3.ISoundTriggerHw.getProperties_2_3Callback)
                        invocation.getArgument(
                        0)).onValues(0,
                        properties);
                return null;
            }).when(driver).getProperties_2_3(any());
        }

        mService = new SoundTriggerMiddlewareImpl(() -> {
            return mHalDriver;
        }, mAudioSessionProvider);
    }

    private Pair<Integer, SoundTriggerHwCallback> loadGenericModel_2_0(ISoundTriggerModule module,
            int hwHandle) throws RemoteException {
        SoundModel model = createGenericSoundModel();
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel> modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.class);
        ArgumentCaptor<Integer> cookieCaptor = ArgumentCaptor.forClass(Integer.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback callback =
                    invocation.getArgument(1);
            int callbackCookie = invocation.getArgument(2);
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.loadSoundModelCallback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, hwHandle);

            // This is the async mCallback that comes after.
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.ModelEvent modelEvent =
                    new android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.ModelEvent();
            modelEvent.status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.SoundModelStatus.UPDATED;
            modelEvent.model = hwHandle;
            callback.soundModelCallback(modelEvent, callbackCookie);
            return null;
        }).when(mHalDriver).loadSoundModel(modelCaptor.capture(), callbackCaptor.capture(),
                cookieCaptor.capture(), any());

        when(mAudioSessionProvider.acquireSession()).thenReturn(
                new SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession(101, 102, 103));

        int handle = module.loadModel(model);
        verify(mHalDriver).loadSoundModel(any(), any(), anyInt(), any());
        verify(mAudioSessionProvider).acquireSession();

        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel hidlModel =
                modelCaptor.getValue();
        assertEquals(android.hardware.soundtrigger.V2_0.SoundModelType.GENERIC,
                hidlModel.type);
        assertEquals(model.uuid, ConversionUtil.hidl2aidlUuid(hidlModel.uuid));
        assertEquals(model.vendorUuid, ConversionUtil.hidl2aidlUuid(hidlModel.vendorUuid));
        assertArrayEquals(new Byte[]{91, 92, 93, 94, 95}, hidlModel.data.toArray());

        return new Pair<>(handle,
                new SoundTriggerHwCallback(callbackCaptor.getValue(), cookieCaptor.getValue()));
    }

    private Pair<Integer, SoundTriggerHwCallback> loadGenericModel_2_1(ISoundTriggerModule module,
            int hwHandle) throws RemoteException {
        android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;
        SoundModel model = createGenericSoundModel();
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel> modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.class);
        ArgumentCaptor<Integer> cookieCaptor = ArgumentCaptor.forClass(Integer.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback callback =
                    invocation.getArgument(1);
            int callbackCookie = invocation.getArgument(2);
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.loadSoundModel_2_1Callback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, hwHandle);

            // This is the async mCallback that comes after.
            android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.ModelEvent modelEvent =
                    new android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.ModelEvent();
            modelEvent.header.status =
                    android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.SoundModelStatus.UPDATED;
            modelEvent.header.model = hwHandle;
            callback.soundModelCallback_2_1(modelEvent, callbackCookie);
            return null;
        }).when(driver).loadSoundModel_2_1(modelCaptor.capture(), callbackCaptor.capture(),
                cookieCaptor.capture(), any());

        when(mAudioSessionProvider.acquireSession()).thenReturn(
                new SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession(101, 102, 103));

        int handle = module.loadModel(model);
        verify(driver).loadSoundModel_2_1(any(), any(), anyInt(), any());
        verify(mAudioSessionProvider).acquireSession();

        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel hidlModel =
                modelCaptor.getValue();
        assertEquals(android.hardware.soundtrigger.V2_0.SoundModelType.GENERIC,
                hidlModel.header.type);
        assertEquals(model.uuid, ConversionUtil.hidl2aidlUuid(hidlModel.header.uuid));
        assertEquals(model.vendorUuid, ConversionUtil.hidl2aidlUuid(hidlModel.header.vendorUuid));
        assertArrayEquals(new byte[]{91, 92, 93, 94, 95},
                HidlMemoryUtil.hidlMemoryToByteArray(hidlModel.data));

        return new Pair<>(handle,
                new SoundTriggerHwCallback(callbackCaptor.getValue(), cookieCaptor.getValue()));
    }

    private Pair<Integer, SoundTriggerHwCallback> loadGenericModel(ISoundTriggerModule module,
            int hwHandle) throws RemoteException {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            return loadGenericModel_2_1(module, hwHandle);
        } else {
            return loadGenericModel_2_0(module, hwHandle);
        }
    }

    private Pair<Integer, SoundTriggerHwCallback> loadPhraseModel_2_0(ISoundTriggerModule module,
            int hwHandle) throws RemoteException {
        PhraseSoundModel model = createPhraseSoundModel();
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel>
                modelCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.class);
        ArgumentCaptor<Integer> cookieCaptor = ArgumentCaptor.forClass(Integer.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback callback =
                    invocation.getArgument(
                            1);
            int callbackCookie = invocation.getArgument(2);
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.loadPhraseSoundModelCallback
                    resultCallback =
                    invocation.getArgument(
                            3);

            // This is the return of this method.
            resultCallback.onValues(0, hwHandle);

            // This is the async mCallback that comes after.
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.ModelEvent modelEvent =
                    new android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.ModelEvent();
            modelEvent.status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.SoundModelStatus.UPDATED;
            modelEvent.model = hwHandle;
            callback.soundModelCallback(modelEvent, callbackCookie);
            return null;
        }).when(mHalDriver).loadPhraseSoundModel(modelCaptor.capture(), callbackCaptor.capture(),
                cookieCaptor.capture(), any());

        when(mAudioSessionProvider.acquireSession()).thenReturn(
                new SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession(101, 102, 103));

        int handle = module.loadPhraseModel(model);
        verify(mHalDriver).loadPhraseSoundModel(any(), any(), anyInt(), any());
        verify(mAudioSessionProvider).acquireSession();

        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel hidlModel =
                modelCaptor.getValue();

        // Validate common part.
        assertEquals(android.hardware.soundtrigger.V2_0.SoundModelType.KEYPHRASE,
                hidlModel.common.type);
        assertEquals(model.common.uuid, ConversionUtil.hidl2aidlUuid(hidlModel.common.uuid));
        assertEquals(model.common.vendorUuid,
                ConversionUtil.hidl2aidlUuid(hidlModel.common.vendorUuid));
        assertArrayEquals(new Byte[]{91, 92, 93, 94, 95}, hidlModel.common.data.toArray());

        // Validate phrase part.
        assertEquals(1, hidlModel.phrases.size());
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.Phrase hidlPhrase =
                hidlModel.phrases.get(0);
        assertEquals(123, hidlPhrase.id);
        assertArrayEquals(new Integer[]{5, 6, 7}, hidlPhrase.users.toArray());
        assertEquals("locale", hidlPhrase.locale);
        assertEquals("text", hidlPhrase.text);
        assertEquals(android.hardware.soundtrigger.V2_0.RecognitionMode.USER_AUTHENTICATION
                        | android.hardware.soundtrigger.V2_0.RecognitionMode.USER_IDENTIFICATION,
                hidlPhrase.recognitionModes);

        return new Pair<>(handle,
                new SoundTriggerHwCallback(callbackCaptor.getValue(), cookieCaptor.getValue()));
    }

    private Pair<Integer, SoundTriggerHwCallback> loadPhraseModel_2_1(ISoundTriggerModule module,
            int hwHandle) throws RemoteException {
        android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;

        PhraseSoundModel model = createPhraseSoundModel();
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel>
                modelCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.class);
        ArgumentCaptor<Integer> cookieCaptor = ArgumentCaptor.forClass(Integer.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback callback =
                    invocation.getArgument(
                            1);
            int callbackCookie = invocation.getArgument(2);
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.loadPhraseSoundModel_2_1Callback
                    resultCallback =
                    invocation.getArgument(
                            3);

            // This is the return of this method.
            resultCallback.onValues(0, hwHandle);

            // This is the async mCallback that comes after.
            android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.ModelEvent modelEvent =
                    new android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.ModelEvent();
            modelEvent.header.status =
                    android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.SoundModelStatus.UPDATED;
            modelEvent.header.model = hwHandle;
            callback.soundModelCallback_2_1(modelEvent, callbackCookie);
            return null;
        }).when(driver).loadPhraseSoundModel_2_1(modelCaptor.capture(), callbackCaptor.capture(),
                cookieCaptor.capture(), any());

        when(mAudioSessionProvider.acquireSession()).thenReturn(
                new SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession(101, 102, 103));

        int handle = module.loadPhraseModel(model);
        verify(driver).loadPhraseSoundModel_2_1(any(), any(), anyInt(), any());
        verify(mAudioSessionProvider).acquireSession();

        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel hidlModel =
                modelCaptor.getValue();

        // Validate common part.
        assertEquals(android.hardware.soundtrigger.V2_0.SoundModelType.KEYPHRASE,
                hidlModel.common.header.type);
        assertEquals(model.common.uuid, ConversionUtil.hidl2aidlUuid(hidlModel.common.header.uuid));
        assertEquals(model.common.vendorUuid,
                ConversionUtil.hidl2aidlUuid(hidlModel.common.header.vendorUuid));
        assertArrayEquals(new byte[]{91, 92, 93, 94, 95},
                HidlMemoryUtil.hidlMemoryToByteArray(hidlModel.common.data));

        // Validate phrase part.
        assertEquals(1, hidlModel.phrases.size());
        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.Phrase hidlPhrase =
                hidlModel.phrases.get(0);
        assertEquals(123, hidlPhrase.id);
        assertArrayEquals(new Integer[]{5, 6, 7}, hidlPhrase.users.toArray());
        assertEquals("locale", hidlPhrase.locale);
        assertEquals("text", hidlPhrase.text);
        assertEquals(android.hardware.soundtrigger.V2_0.RecognitionMode.USER_AUTHENTICATION
                        | android.hardware.soundtrigger.V2_0.RecognitionMode.USER_IDENTIFICATION,
                hidlPhrase.recognitionModes);

        return new Pair<>(handle,
                new SoundTriggerHwCallback(callbackCaptor.getValue(), cookieCaptor.getValue()));
    }

    private Pair<Integer, SoundTriggerHwCallback> loadPhraseModel(
            ISoundTriggerModule module, int hwHandle) throws RemoteException {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            return loadPhraseModel_2_1(module, hwHandle);
        } else {
            return loadPhraseModel_2_0(module, hwHandle);
        }
    }

    private void unloadModel(ISoundTriggerModule module, int handle, int hwHandle)
            throws RemoteException {
        module.unloadModel(handle);
        verify(mHalDriver).unloadSoundModel(hwHandle);
        verify(mAudioSessionProvider).releaseSession(101);
    }

    private void startRecognition_2_0(ISoundTriggerModule module, int handle,
            int hwHandle) throws RemoteException {
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig.class);

        when(mHalDriver.startRecognition(eq(hwHandle), configCaptor.capture(), any(), anyInt()))
                .thenReturn(0);

        RecognitionConfig config = createRecognitionConfig();

        module.startRecognition(handle, config);
        verify(mHalDriver).startRecognition(eq(hwHandle), any(), any(), anyInt());

        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig halConfig =
                configCaptor.getValue();
        assertTrue(halConfig.captureRequested);
        assertEquals(102, halConfig.captureHandle);
        assertEquals(103, halConfig.captureDevice);
        assertEquals(1, halConfig.phrases.size());
        android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra halPhraseExtra =
                halConfig.phrases.get(0);
        assertEquals(123, halPhraseExtra.id);
        assertEquals(4, halPhraseExtra.confidenceLevel);
        assertEquals(5, halPhraseExtra.recognitionModes);
        assertEquals(1, halPhraseExtra.levels.size());
        android.hardware.soundtrigger.V2_0.ConfidenceLevel halLevel = halPhraseExtra.levels.get(0);
        assertEquals(234, halLevel.userId);
        assertEquals(34, halLevel.levelPercent);
        assertArrayEquals(new Byte[]{5, 4, 3, 2, 1}, halConfig.data.toArray());
    }

    private void startRecognition_2_1(ISoundTriggerModule module, int handle,
            int hwHandle) throws RemoteException {
        android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;

        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig.class);

        when(driver.startRecognition_2_1(eq(hwHandle), configCaptor.capture(), any(), anyInt()))
                .thenReturn(0);

        RecognitionConfig config = createRecognitionConfig();

        module.startRecognition(handle, config);
        verify(driver).startRecognition_2_1(eq(hwHandle), any(), any(), anyInt());

        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig halConfig =
                configCaptor.getValue();
        assertTrue(halConfig.header.captureRequested);
        assertEquals(102, halConfig.header.captureHandle);
        assertEquals(103, halConfig.header.captureDevice);
        assertEquals(1, halConfig.header.phrases.size());
        android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra halPhraseExtra =
                halConfig.header.phrases.get(0);
        assertEquals(123, halPhraseExtra.id);
        assertEquals(4, halPhraseExtra.confidenceLevel);
        assertEquals(5, halPhraseExtra.recognitionModes);
        assertEquals(1, halPhraseExtra.levels.size());
        android.hardware.soundtrigger.V2_0.ConfidenceLevel halLevel = halPhraseExtra.levels.get(0);
        assertEquals(234, halLevel.userId);
        assertEquals(34, halLevel.levelPercent);
        assertArrayEquals(new byte[]{5, 4, 3, 2, 1},
                HidlMemoryUtil.hidlMemoryToByteArray(halConfig.data));
    }

    private void startRecognition_2_3(ISoundTriggerModule module, int handle,
            int hwHandle) throws RemoteException {
        android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        ArgumentCaptor<android.hardware.soundtrigger.V2_3.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_3.RecognitionConfig.class);

        when(driver.startRecognition_2_3(eq(hwHandle), configCaptor.capture())).thenReturn(0);

        RecognitionConfig config = createRecognitionConfig();

        module.startRecognition(handle, config);
        verify(driver).startRecognition_2_3(eq(hwHandle), any());

        android.hardware.soundtrigger.V2_3.RecognitionConfig halConfigExtended =
                configCaptor.getValue();
        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig halConfig_2_1 =
                halConfigExtended.base;

        assertTrue(halConfig_2_1.header.captureRequested);
        assertEquals(102, halConfig_2_1.header.captureHandle);
        assertEquals(103, halConfig_2_1.header.captureDevice);
        assertEquals(1, halConfig_2_1.header.phrases.size());
        android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra halPhraseExtra =
                halConfig_2_1.header.phrases.get(0);
        assertEquals(123, halPhraseExtra.id);
        assertEquals(4, halPhraseExtra.confidenceLevel);
        assertEquals(5, halPhraseExtra.recognitionModes);
        assertEquals(1, halPhraseExtra.levels.size());
        android.hardware.soundtrigger.V2_0.ConfidenceLevel halLevel = halPhraseExtra.levels.get(0);
        assertEquals(234, halLevel.userId);
        assertEquals(34, halLevel.levelPercent);
        assertArrayEquals(new byte[]{5, 4, 3, 2, 1},
                HidlMemoryUtil.hidlMemoryToByteArray(halConfig_2_1.data));
        assertEquals(AudioCapabilities.ECHO_CANCELLATION
                | AudioCapabilities.NOISE_SUPPRESSION, halConfigExtended.audioCapabilities);
    }

    private void startRecognition(ISoundTriggerModule module, int handle,
            int hwHandle) throws RemoteException {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            startRecognition_2_3(module, handle, hwHandle);
        } else if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            startRecognition_2_1(module, handle, hwHandle);
        } else {
            startRecognition_2_0(module, handle, hwHandle);
        }
    }

    private RecognitionConfig createRecognitionConfig() {
        RecognitionConfig config = new RecognitionConfig();
        config.captureRequested = true;
        config.phraseRecognitionExtras = new PhraseRecognitionExtra[]{new PhraseRecognitionExtra()};
        config.phraseRecognitionExtras[0].id = 123;
        config.phraseRecognitionExtras[0].confidenceLevel = 4;
        config.phraseRecognitionExtras[0].recognitionModes = 5;
        config.phraseRecognitionExtras[0].levels = new ConfidenceLevel[]{new ConfidenceLevel()};
        config.phraseRecognitionExtras[0].levels[0].userId = 234;
        config.phraseRecognitionExtras[0].levels[0].levelPercent = 34;
        config.data = new byte[]{5, 4, 3, 2, 1};
        config.audioCapabilities = AudioCapabilities.ECHO_CANCELLATION
                | AudioCapabilities.NOISE_SUPPRESSION;
        return config;
    }

    private void stopRecognition(ISoundTriggerModule module, int handle, int hwHandle)
            throws RemoteException {
        when(mHalDriver.stopRecognition(hwHandle)).thenReturn(0);
        module.stopRecognition(handle);
        verify(mHalDriver).stopRecognition(hwHandle);
    }

    private void verifyNotStartRecognition() throws RemoteException {
        verify(mHalDriver, never()).startRecognition(anyInt(), any(), any(), anyInt());
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            verify((android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver,
                    never()).startRecognition_2_1(anyInt(), any(), any(), anyInt());
        } else if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            verify((android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver,
                    never()).startRecognition_2_3(anyInt(), any());
        }
    }


    @Before
    public void setUp() throws Exception {
        clearInvocations(mHalDriver);
        clearInvocations(mAudioSessionProvider);

        // This binder is associated with the mock, so it can be cast to either version of the
        // HAL interface.
        final IHwBinder binder = new IHwBinder() {
            @Override
            public void transact(int code, HwParcel request, HwParcel reply, int flags)
                    throws RemoteException {
                // This is a little hacky, but a very easy way to gracefully reject a request for
                // an unsupported interface (after queryLocalInterface() returns null, the client
                // will attempt a remote transaction to obtain the interface. RemoteException will
                // cause it to give up).
                throw new RemoteException();
            }

            @Override
            public IHwInterface queryLocalInterface(String descriptor) {
                if (descriptor.equals("android.hardware.soundtrigger@2.0::ISoundTriggerHw")
                        || descriptor.equals("android.hardware.soundtrigger@2.1::ISoundTriggerHw")
                        && mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw
                        || descriptor.equals("android.hardware.soundtrigger@2.2::ISoundTriggerHw")
                        && mHalDriver instanceof android.hardware.soundtrigger.V2_2.ISoundTriggerHw
                        || descriptor.equals("android.hardware.soundtrigger@2.3::ISoundTriggerHw")
                        && mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
                    return mHalDriver;
                }
                return null;
            }

            @Override
            public boolean linkToDeath(DeathRecipient recipient, long cookie) {
                return true;
            }

            @Override
            public boolean unlinkToDeath(DeathRecipient recipient) {
                return true;
            }
        };

        when(mHalDriver.asBinder()).thenReturn(binder);
    }

    @Test
    public void testSetUpAndTearDown() {
    }

    @Test
    public void testListModules() throws Exception {
        initService(true);
        // Note: input and output properties are NOT the same type, even though they are in any way
        // equivalent. One is a type that's exposed by the HAL and one is a type that's exposed by
        // the service. The service actually performs a (trivial) conversion between the two.
        SoundTriggerModuleDescriptor[] allDescriptors = mService.listModules();
        assertEquals(1, allDescriptors.length);

        SoundTriggerModuleProperties properties = allDescriptors[0].properties;

        validateDefaultProperties(properties, true);
        verifyNotGetProperties();
    }

    @Test
    public void testAttachDetach() throws Exception {
        // Normal attachment / detachment.
        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        verify(callback).onRecognitionAvailabilityChange(true);
        assertNotNull(module);
        module.detach();
    }

    @Test
    public void testAttachDetachNotAvailable() throws Exception {
        // Attachment / detachment during external capture, with a module not supporting concurrent
        // capture.
        initService(false);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        verify(callback).onRecognitionAvailabilityChange(false);
        assertNotNull(module);
        module.detach();
    }

    @Test
    public void testAttachDetachAvailable() throws Exception {
        // Attachment / detachment during external capture, with a module supporting concurrent
        // capture.
        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        verify(callback).onRecognitionAvailabilityChange(true);
        assertNotNull(module);
        module.detach();
    }

    @Test
    public void testLoadUnloadModel() throws Exception {
        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        final int hwHandle = 7;
        int handle = loadGenericModel(module, hwHandle).first;
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testLoadUnloadPhraseModel() throws Exception {
        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        final int hwHandle = 73;
        int handle = loadPhraseModel(module, hwHandle).first;
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testStartStopRecognition() throws Exception {
        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 7;
        int handle = loadGenericModel(module, hwHandle).first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testStartStopPhraseRecognition() throws Exception {
        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 67;
        int handle = loadPhraseModel(module, hwHandle).first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testRecognition() throws Exception {
        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 7;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadGenericModel(module, hwHandle);
        int handle = modelHandles.first;
        SoundTriggerHwCallback hwCallback = modelHandles.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Signal a capture from the driver.
        hwCallback.sendRecognitionEvent(hwHandle,
                android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        validateRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.SUCCESS);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testPhraseRecognition() throws Exception {
        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 7;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadPhraseModel(module, hwHandle);
        int handle = modelHandles.first;
        SoundTriggerHwCallback hwCallback = modelHandles.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Signal a capture from the driver.
        hwCallback.sendPhraseRecognitionEvent(hwHandle,
                android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        validatePhraseRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.SUCCESS);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForceRecognition() throws Exception {
        if (!(mHalDriver instanceof android.hardware.soundtrigger.V2_2.ISoundTriggerHw)) {
            return;
        }

        android.hardware.soundtrigger.V2_2.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_2.ISoundTriggerHw) mHalDriver;

        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 17;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadGenericModel(module, hwHandle);
        int handle = modelHandles.first;
        SoundTriggerHwCallback hwCallback = modelHandles.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Force a trigger.
        module.forceRecognitionEvent(handle);
        verify(driver).getModelState(hwHandle);

        // Signal a capture from the driver.
        // '3' means 'forced', there's no constant for that in the HAL.
        hwCallback.sendRecognitionEvent(hwHandle, 3);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        validateRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.FORCED);

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForcePhraseRecognition() throws Exception {
        if (!(mHalDriver instanceof android.hardware.soundtrigger.V2_2.ISoundTriggerHw)) {
            return;
        }

        android.hardware.soundtrigger.V2_2.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_2.ISoundTriggerHw) mHalDriver;

        initService(true);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 17;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadPhraseModel(module, hwHandle);
        int handle = modelHandles.first;
        SoundTriggerHwCallback hwCallback = modelHandles.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Force a trigger.
        module.forceRecognitionEvent(handle);
        verify(driver).getModelState(hwHandle);

        // Signal a capture from the driver.
        // '3' means 'forced', there's no constant for that in the HAL.
        hwCallback.sendPhraseRecognitionEvent(hwHandle, 3);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        validatePhraseRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.FORCED);

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testAbortRecognition() throws Exception {
        // Make sure the HAL doesn't support concurrent capture.
        initService(false);
        mService.setCaptureState(false);

        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        verify(callback).onRecognitionAvailabilityChange(true);

        // Load the model.
        final int hwHandle = 11;
        int handle = loadGenericModel(module, hwHandle).first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Abort.
        mService.setCaptureState(true);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().status);

        // Make sure we are notified of the lost availability.
        verify(callback).onRecognitionAvailabilityChange(false);

        // Attempt to start a new recognition - should get an abort event immediately, without
        // involving the HAL.
        clearInvocations(callback);
        clearInvocations(mHalDriver);
        module.startRecognition(handle, createRecognitionConfig());
        verify(callback).onRecognition(eq(handle), eventCaptor.capture());
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().status);
        verifyNotStartRecognition();

        // Now enable it and make sure we are notified.
        mService.setCaptureState(false);
        verify(callback).onRecognitionAvailabilityChange(true);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testAbortPhraseRecognition() throws Exception {
        // Make sure the HAL doesn't support concurrent capture.
        initService(false);
        mService.setCaptureState(false);

        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        verify(callback).onRecognitionAvailabilityChange(true);

        // Load the model.
        final int hwHandle = 11;
        int handle = loadPhraseModel(module, hwHandle).first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Abort.
        mService.setCaptureState(true);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().common.status);

        // Make sure we are notified of the lost availability.
        verify(callback).onRecognitionAvailabilityChange(false);

        // Attempt to start a new recognition - should get an abort event immediately, without
        // involving the HAL.
        clearInvocations(callback);
        clearInvocations(mHalDriver);
        module.startRecognition(handle, createRecognitionConfig());
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture());
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().common.status);
        verifyNotStartRecognition();

        // Now enable it and make sure we are notified.
        mService.setCaptureState(false);
        verify(callback).onRecognitionAvailabilityChange(true);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testNotAbortRecognitionConcurrent() throws Exception {
        // Make sure the HAL supports concurrent capture.
        initService(true);

        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        verify(callback).onRecognitionAvailabilityChange(true);
        clearInvocations(callback);

        // Load the model.
        final int hwHandle = 13;
        int handle = loadGenericModel(module, hwHandle).first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Signal concurrent capture. Shouldn't abort.
        mService.setCaptureState(true);
        verify(callback, never()).onRecognition(anyInt(), any());
        verify(callback, never()).onRecognitionAvailabilityChange(anyBoolean());

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Initiating a new one should work fine.
        clearInvocations(mHalDriver);
        startRecognition(module, handle, hwHandle);
        verify(callback, never()).onRecognition(anyInt(), any());
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        module.unloadModel(handle);
        module.detach();
    }

    @Test
    public void testNotAbortPhraseRecognitionConcurrent() throws Exception {
        // Make sure the HAL supports concurrent capture.
        initService(true);

        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        verify(callback).onRecognitionAvailabilityChange(true);
        clearInvocations(callback);

        // Load the model.
        final int hwHandle = 13;
        int handle = loadPhraseModel(module, hwHandle).first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Signal concurrent capture. Shouldn't abort.
        mService.setCaptureState(true);
        verify(callback, never()).onPhraseRecognition(anyInt(), any());
        verify(callback, never()).onRecognitionAvailabilityChange(anyBoolean());

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Initiating a new one should work fine.
        clearInvocations(mHalDriver);
        startRecognition(module, handle, hwHandle);
        verify(callback, never()).onRecognition(anyInt(), any());
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        module.unloadModel(handle);
        module.detach();
    }

    @Test
    public void testParameterSupported() throws Exception {
        if (!(mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw)) {
            return;
        }

        android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        initService(false);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 12;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        doAnswer((Answer<Void>) invocation -> {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw.queryParameterCallback
                    resultCallback = invocation.getArgument(2);
            android.hardware.soundtrigger.V2_3.ModelParameterRange range =
                    new android.hardware.soundtrigger.V2_3.ModelParameterRange();
            range.start = 23;
            range.end = 45;
            OptionalModelParameterRange optionalRange = new OptionalModelParameterRange();
            optionalRange.range(range);
            resultCallback.onValues(0, optionalRange);
            return null;
        }).when(driver).queryParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR), any());

        ModelParameterRange range = module.queryModelParameterSupport(modelHandle,
                ModelParameter.THRESHOLD_FACTOR);

        verify(driver).queryParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR), any());

        assertEquals(23, range.minInclusive);
        assertEquals(45, range.maxInclusive);
    }

    @Test
    public void testParameterNotSupportedOld() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            return;
        }

        initService(false);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 13;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        ModelParameterRange range = module.queryModelParameterSupport(modelHandle,
                ModelParameter.THRESHOLD_FACTOR);

        assertNull(range);
    }

    @Test
    public void testParameterNotSupported() throws Exception {
        if (!(mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw)) {
            return;
        }

        android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        initService(false);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 13;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw.queryParameterCallback
                    resultCallback = invocation.getArgument(2);
            // This is the return of this method.
            resultCallback.onValues(0, new OptionalModelParameterRange());
            return null;
        }).when(driver).queryParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR), any());

        ModelParameterRange range = module.queryModelParameterSupport(modelHandle,
                ModelParameter.THRESHOLD_FACTOR);

        verify(driver).queryParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR), any());

        assertNull(range);
    }

    @Test
    public void testGetParameter() throws Exception {
        if (!(mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw)) {
            return;
        }

        android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        initService(false);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 14;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw.getParameterCallback
                    resultCallback = invocation.getArgument(2);
            // This is the return of this method.
            resultCallback.onValues(0, 234);
            return null;
        }).when(driver).getParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR), any());

        int value = module.getModelParameter(modelHandle, ModelParameter.THRESHOLD_FACTOR);

        verify(driver).getParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR), any());

        assertEquals(234, value);
    }

    @Test
    public void testSetParameter() throws Exception {
        if (!(mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw)) {
            return;
        }

        android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        initService(false);
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 17;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        when(driver.setParameter(hwHandle,
                android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR,
                456)).thenReturn(0);

        module.setModelParameter(modelHandle, ModelParameter.THRESHOLD_FACTOR, 456);

        verify(driver).setParameter(hwHandle,
                android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR, 456);
    }

    private static class SoundTriggerHwCallback {
        private final android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback mCallback;
        private final int mCookie;

        SoundTriggerHwCallback(android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback callback,
                int cookie) {
            mCallback = callback;
            mCookie = cookie;
        }

        private void sendRecognitionEvent(int hwHandle, int status) throws RemoteException {
            if (mCallback instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback) {
                ((android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback) mCallback).recognitionCallback_2_1(
                        createRecognitionEvent_2_1(hwHandle, status), mCookie);
            } else {
                mCallback.recognitionCallback(createRecognitionEvent_2_0(hwHandle, status),
                        mCookie);
            }
        }

        private void sendPhraseRecognitionEvent(int hwHandle, int status) throws RemoteException {
            if (mCallback instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback) {
                ((android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback) mCallback).phraseRecognitionCallback_2_1(
                        createPhraseRecognitionEvent_2_1(hwHandle, status), mCookie);
            } else {
                mCallback.phraseRecognitionCallback(
                        createPhraseRecognitionEvent_2_0(hwHandle, status), mCookie);
            }
        }
    }
}
