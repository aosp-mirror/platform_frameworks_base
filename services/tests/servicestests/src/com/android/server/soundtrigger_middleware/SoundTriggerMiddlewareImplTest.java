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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback;
import android.media.soundtrigger_middleware.ISoundTriggerCallback;
import android.media.soundtrigger_middleware.ISoundTriggerModule;
import android.media.soundtrigger_middleware.ModelParameter;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.RecognitionStatus;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.media.soundtrigger_middleware.SoundTriggerModuleProperties;
import android.media.soundtrigger_middleware.Status;
import android.os.RemoteException;
import android.util.Pair;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class SoundTriggerMiddlewareImplTest {
    @Mock
    public ISoundTriggerHw2 mHalDriver = mock(ISoundTriggerHw2.class);

    @Mock
    private final SoundTriggerMiddlewareImpl.AudioSessionProvider mAudioSessionProvider = mock(
            SoundTriggerMiddlewareImpl.AudioSessionProvider.class);

    private SoundTriggerMiddlewareImpl mService;

    private static ISoundTriggerCallback createCallbackMock() {
        return mock(ISoundTriggerCallback.Stub.class, Mockito.CALLS_REAL_METHODS);
    }

    private Pair<Integer, SoundTriggerHwCallback> loadGenericModel(ISoundTriggerModule module,
            int hwHandle) throws RemoteException {
        SoundModel model = TestUtil.createGenericSoundModel();
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel> modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel.class);
        ArgumentCaptor<ISoundTriggerHw2.ModelCallback> callbackCaptor =
                ArgumentCaptor.forClass(ISoundTriggerHw2.ModelCallback.class);

        when(mHalDriver.loadSoundModel(any(), any())).thenReturn(
                hwHandle);
        when(mAudioSessionProvider.acquireSession()).thenReturn(
                new SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession(101, 102, 103));

        int handle = module.loadModel(model);
        verify(mHalDriver).loadSoundModel(modelCaptor.capture(), callbackCaptor.capture());
        verify(mAudioSessionProvider).acquireSession();
        TestUtil.validateGenericSoundModel_2_1(modelCaptor.getValue());
        return new Pair<>(handle, new SoundTriggerHwCallback(callbackCaptor.getValue()));
    }

    private Pair<Integer, SoundTriggerHwCallback> loadPhraseModel(
            ISoundTriggerModule module, int hwHandle) throws RemoteException {
        PhraseSoundModel model = TestUtil.createPhraseSoundModel();
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel>
                modelCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel.class);
        ArgumentCaptor<ISoundTriggerHw2.ModelCallback> callbackCaptor =
                ArgumentCaptor.forClass(ISoundTriggerHw2.ModelCallback.class);

        when(mHalDriver.loadPhraseSoundModel(any(), any())).thenReturn(hwHandle);
        when(mAudioSessionProvider.acquireSession()).thenReturn(
                new SoundTriggerMiddlewareImpl.AudioSessionProvider.AudioSession(101, 102, 103));

        int handle = module.loadPhraseModel(model);
        verify(mHalDriver).loadPhraseSoundModel(modelCaptor.capture(), callbackCaptor.capture());
        verify(mAudioSessionProvider).acquireSession();
        TestUtil.validatePhraseSoundModel_2_1(modelCaptor.getValue());
        return new Pair<>(handle, new SoundTriggerHwCallback(callbackCaptor.getValue()));
    }

    private void unloadModel(ISoundTriggerModule module, int handle, int hwHandle)
            throws RemoteException {
        module.unloadModel(handle);
        verify(mHalDriver).unloadSoundModel(hwHandle);
        verify(mAudioSessionProvider).releaseSession(101);
    }

    private void startRecognition(ISoundTriggerModule module, int handle,
            int hwHandle) throws RemoteException {
        ArgumentCaptor<android.hardware.soundtrigger.V2_3.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_3.RecognitionConfig.class);

        RecognitionConfig config = TestUtil.createRecognitionConfig();

        module.startRecognition(handle, config);
        verify(mHalDriver).startRecognition(eq(hwHandle), configCaptor.capture());
        TestUtil.validateRecognitionConfig_2_3(configCaptor.getValue(), 102, 103);
    }

    private void stopRecognition(ISoundTriggerModule module, int handle, int hwHandle)
            throws RemoteException {
        module.stopRecognition(handle);
        verify(mHalDriver).stopRecognition(hwHandle);
    }

    @Before
    public void setUp() throws Exception {
        clearInvocations(mHalDriver);
        clearInvocations(mAudioSessionProvider);
        when(mHalDriver.getProperties()).thenReturn(
                TestUtil.createDefaultProperties_2_3(false));
        mService = new SoundTriggerMiddlewareImpl(() -> mHalDriver, mAudioSessionProvider);
    }

    @After
    public void tearDown() {
        verify(mHalDriver, never()).reboot();
    }

    @Test
    public void testSetUpAndTearDown() {
    }

    @Test
    public void testListModules() {
        // Note: input and output properties are NOT the same type, even though they are in any way
        // equivalent. One is a type that's exposed by the HAL and one is a type that's exposed by
        // the service. The service actually performs a (trivial) conversion between the two.
        SoundTriggerModuleDescriptor[] allDescriptors = mService.listModules();
        assertEquals(1, allDescriptors.length);

        SoundTriggerModuleProperties properties = allDescriptors[0].properties;

        TestUtil.validateDefaultProperties(properties, false);
    }

    @Test
    public void testAttachDetach() throws Exception {
        // Normal attachment / detachment.
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        assertNotNull(module);
        module.detach();
    }

    @Test
    public void testLoadUnloadModel() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        final int hwHandle = 7;
        int handle = loadGenericModel(module, hwHandle).first;
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testLoadPreemptModel() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        final int hwHandle = 7;
        Pair<Integer, SoundTriggerHwCallback> loadResult = loadGenericModel(module, hwHandle);

        int handle = loadResult.first;
        SoundTriggerHwCallback hwCallback = loadResult.second;

        // Signal preemption.
        hwCallback.sendUnloadEvent(hwHandle);

        verify(callback).onModelUnloaded(handle);

        module.detach();
    }

    @Test
    public void testLoadUnloadPhraseModel() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        final int hwHandle = 73;
        int handle = loadPhraseModel(module, hwHandle).first;
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testStartStopRecognition() throws Exception {
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
    public void testStartRecognitionBusy() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 7;
        int handle = loadGenericModel(module, hwHandle).first;

        // Start the model.
        doThrow(new RecoverableException(Status.RESOURCE_CONTENTION)).when(
                mHalDriver).startRecognition(eq(7), any());

        try {
            RecognitionConfig config = TestUtil.createRecognitionConfig();
            module.startRecognition(handle, config);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.RESOURCE_CONTENTION, e.errorCode);
        }

        verify(mHalDriver).startRecognition(eq(7), any());
    }

    @Test
    public void testStartStopPhraseRecognition() throws Exception {
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
                android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS,
                101);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        TestUtil.validateRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.SUCCESS, 101);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testPhraseRecognition() throws Exception {
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
                android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS,
                101);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        TestUtil.validatePhraseRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.SUCCESS,
                101);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForceRecognition() throws Exception {
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
        verify(mHalDriver).getModelState(hwHandle);

        // Signal a capture from the driver.
        // '3' means 'forced', there's no constant for that in the HAL.
        hwCallback.sendRecognitionEvent(hwHandle, 3, 101);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        TestUtil.validateRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.FORCED, 101);

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForceRecognitionNotSupported() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 17;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadGenericModel(module, hwHandle);
        int handle = modelHandles.first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Force a trigger.
        doThrow(new RecoverableException(Status.OPERATION_NOT_SUPPORTED)).when(
                mHalDriver).getModelState(hwHandle);
        try {
            module.forceRecognitionEvent(handle);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.OPERATION_NOT_SUPPORTED, e.errorCode);
        }

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForcePhraseRecognition() throws Exception {
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
        verify(mHalDriver).getModelState(hwHandle);

        // Signal a capture from the driver.
        // '3' means 'forced', there's no constant for that in the HAL.
        hwCallback.sendPhraseRecognitionEvent(hwHandle, 3, 101);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        TestUtil.validatePhraseRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.FORCED,
                101);

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testForcePhraseRecognitionNotSupported() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 17;
        Pair<Integer, SoundTriggerHwCallback> modelHandles = loadPhraseModel(module, hwHandle);
        int handle = modelHandles.first;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Force a trigger.
        doThrow(new RecoverableException(Status.OPERATION_NOT_SUPPORTED)).when(
                mHalDriver).getModelState(hwHandle);
        try {
            module.forceRecognitionEvent(handle);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.OPERATION_NOT_SUPPORTED, e.errorCode);
        }

        // Stop the recognition.
        stopRecognition(module, handle, hwHandle);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testAbortRecognition() throws Exception {
        // Make sure the HAL doesn't support concurrent capture.
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 11;
        Pair<Integer, SoundTriggerHwCallback> loadResult = loadGenericModel(module, hwHandle);
        int handle = loadResult.first;
        SoundTriggerHwCallback hwCallback = loadResult.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Abort.
        hwCallback.sendRecognitionEvent(hwHandle, ISoundTriggerHwCallback.RecognitionStatus.ABORT,
                99);

        ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                RecognitionEvent.class);
        verify(callback).onRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().status);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testAbortPhraseRecognition() throws Exception {
        // Make sure the HAL doesn't support concurrent capture.
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);

        // Load the model.
        final int hwHandle = 11;
        Pair<Integer, SoundTriggerHwCallback> loadResult = loadPhraseModel(module, hwHandle);
        int handle = loadResult.first;
        SoundTriggerHwCallback hwCallback = loadResult.second;

        // Initiate a recognition.
        startRecognition(module, handle, hwHandle);

        // Abort.
        hwCallback.sendPhraseRecognitionEvent(hwHandle,
                ISoundTriggerHwCallback.RecognitionStatus.ABORT, 333);

        ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                PhraseRecognitionEvent.class);
        verify(callback).onPhraseRecognition(eq(handle), eventCaptor.capture());

        // Validate the event.
        assertEquals(RecognitionStatus.ABORTED, eventCaptor.getValue().common.status);

        // Unload the model.
        unloadModel(module, handle, hwHandle);
        module.detach();
    }

    @Test
    public void testParameterSupported() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 12;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        android.hardware.soundtrigger.V2_3.ModelParameterRange halRange =
                new android.hardware.soundtrigger.V2_3.ModelParameterRange();
        halRange.start = 23;
        halRange.end = 45;

        when(mHalDriver.queryParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR))).thenReturn(
                halRange);

        ModelParameterRange range = module.queryModelParameterSupport(modelHandle,
                ModelParameter.THRESHOLD_FACTOR);

        verify(mHalDriver).queryParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR));

        assertEquals(23, range.minInclusive);
        assertEquals(45, range.maxInclusive);
    }

    @Test
    public void testParameterNotSupported() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 13;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        when(mHalDriver.queryParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR))).thenReturn(
                null);

        ModelParameterRange range = module.queryModelParameterSupport(modelHandle,
                ModelParameter.THRESHOLD_FACTOR);

        verify(mHalDriver).queryParameter(eq(hwHandle),
                eq(android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR));

        assertNull(range);
    }

    @Test
    public void testGetParameter() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 14;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        when(mHalDriver.getModelParameter(hwHandle,
                android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR)).thenReturn(
                234);

        int value = module.getModelParameter(modelHandle, ModelParameter.THRESHOLD_FACTOR);

        verify(mHalDriver).getModelParameter(hwHandle,
                android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR);

        assertEquals(234, value);
    }

    @Test
    public void testSetParameter() throws Exception {
        ISoundTriggerCallback callback = createCallbackMock();
        ISoundTriggerModule module = mService.attach(0, callback);
        final int hwHandle = 17;
        int modelHandle = loadGenericModel(module, hwHandle).first;

        module.setModelParameter(modelHandle, ModelParameter.THRESHOLD_FACTOR, 456);

        verify(mHalDriver).setModelParameter(hwHandle,
                android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR, 456);
    }

    private static class SoundTriggerHwCallback {
        private final ISoundTriggerHw2.ModelCallback mCallback;

        SoundTriggerHwCallback(ISoundTriggerHw2.ModelCallback callback) {
            mCallback = callback;
        }

        private void sendRecognitionEvent(int hwHandle, int status, int captureSession) {
            mCallback.recognitionCallback(
                    TestUtil.createRecognitionEvent_2_1(hwHandle, status, captureSession));
        }

        private void sendPhraseRecognitionEvent(int hwHandle, int status, int captureSession) {
            mCallback.phraseRecognitionCallback(
                    TestUtil.createPhraseRecognitionEvent_2_1(hwHandle, status, captureSession));
        }

        private void sendUnloadEvent(int hwHandle) {
            mCallback.modelUnloaded(hwHandle);
        }
    }
}
