/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.Phrase;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionMode;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger.SoundModelType;
import android.media.soundtrigger.Status;
import android.os.IBinder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.InOrder;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public final class SoundTriggerDuplicateModelHandlerTest {
    // Component under test
    private SoundTriggerDuplicateModelHandler mComponent;

    private static final String DUPLICATE_UUID = "abcddead-beef-0123-3210-0123456789ab";
    private static final String DIFFERENT_UUID = "0000dead-beef-0123-3210-0123456789ab";

    @Mock private ISoundTriggerHal mUnderlying;
    @Mock private ISoundTriggerHal.GlobalCallback mGlobalCallback;
    @Mock private ISoundTriggerHal.ModelCallback mModelCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mComponent = new SoundTriggerDuplicateModelHandler(mUnderlying);
        doNothing().when(mUnderlying).registerCallback(any());
        mComponent.registerCallback(mGlobalCallback);
        verify(mUnderlying).registerCallback(eq(mGlobalCallback));
    }

    @Test
    public void loadSoundModel_throwsResourceContention_whenDuplicateUuid() {
        final var soundModel = createSoundModelOne();
        final var soundModelSameUuid = createSoundModelTwo();
        // First sound model load should complete successfully
        mComponent.loadSoundModel(soundModel, mModelCallback);
        verify(mUnderlying).loadSoundModel(eq(soundModel), eq(mModelCallback));
        assertEquals(
                assertThrows(
                                RecoverableException.class,
                                () -> mComponent.loadSoundModel(soundModelSameUuid, mModelCallback))
                        .errorCode,
                Status.RESOURCE_CONTENTION);
        // Model has not been unloaded, so we don't get a callback
        verify(mGlobalCallback, never()).onResourcesAvailable();
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    @Test
    public void loadSoundModel_doesNotThrowResourceContention_whenDifferentUuid() {
        final var soundModel = createSoundModelOne();
        // Make all other fields the same
        final var soundModelDifferentUuid = createSoundModelOne();
        soundModelDifferentUuid.uuid = DIFFERENT_UUID;
        InOrder inOrder = Mockito.inOrder(mUnderlying);
        // First sound model load should complete successfully
        mComponent.loadSoundModel(soundModel, mModelCallback);
        inOrder.verify(mUnderlying).loadSoundModel(eq(soundModel), eq(mModelCallback));
        mComponent.loadSoundModel(soundModelDifferentUuid, mModelCallback);
        inOrder.verify(mUnderlying).loadSoundModel(eq(soundModelDifferentUuid), eq(mModelCallback));
        // No contention, so we don't get a callback
        verify(mGlobalCallback, never()).onResourcesAvailable();
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    @Test
    public void loadSoundModel_doesNotThrow_afterDuplicateUuidHasBeenUnloaded() {
        final var soundModel = createSoundModelOne();
        // First sound model load should complete successfully
        int handle = mComponent.loadSoundModel(soundModel, mModelCallback);
        verify(mUnderlying).loadSoundModel(eq(soundModel), eq(mModelCallback));
        // Unload model should complete successfully
        mComponent.unloadSoundModel(handle);
        verify(mUnderlying).unloadSoundModel(eq(handle));
        // Since the model with the same UUID was unloaded, the subsequent load model
        // should succeed.
        mComponent.loadSoundModel(soundModel, mModelCallback);
        verify(mUnderlying, times(2)).loadSoundModel(eq(soundModel), eq(mModelCallback));
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    @Test
    public void unloadSoundModel_triggersResourceCallback_afterDuplicateUuidRejected() {
        final var soundModel = createSoundModelOne();
        final var soundModelSameUuid = createSoundModelTwo();
        // First sound model load should complete successfully
        int handle = mComponent.loadSoundModel(soundModel, mModelCallback);
        verify(mUnderlying).loadSoundModel(eq(soundModel), eq(mModelCallback));
        assertEquals(
                assertThrows(
                                RecoverableException.class,
                                () -> mComponent.loadSoundModel(soundModelSameUuid, mModelCallback))
                        .errorCode,
                Status.RESOURCE_CONTENTION);
        mComponent.unloadSoundModel(handle);
        verify(mUnderlying).unloadSoundModel(eq(handle));
        verify(mGlobalCallback).onResourcesAvailable();
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    // Next tests are same as above, but for phrase sound model.
    @Test
    public void loadPhraseSoundModel_throwsResourceContention_whenDuplicateUuid() {
        final var soundModel = createPhraseSoundModelOne();
        final var soundModelSameUuid = createPhraseSoundModelTwo();
        // First sound model load should complete successfully
        mComponent.loadPhraseSoundModel(soundModel, mModelCallback);
        verify(mUnderlying).loadPhraseSoundModel(eq(soundModel), eq(mModelCallback));
        assertEquals(
                assertThrows(
                                RecoverableException.class,
                                () ->
                                        mComponent.loadPhraseSoundModel(
                                                soundModelSameUuid, mModelCallback))
                        .errorCode,
                Status.RESOURCE_CONTENTION);
        // Model has not been unloaded, so we don't get a callback
        verify(mGlobalCallback, never()).onResourcesAvailable();
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    @Test
    public void loadPhraseSoundModel_doesNotThrowResourceContention_whenDifferentUuid() {
        final var soundModel = createPhraseSoundModelOne();
        // Make all other fields the same
        final var soundModelDifferentUuid = createPhraseSoundModelOne();
        soundModelDifferentUuid.common.uuid = DIFFERENT_UUID;
        InOrder inOrder = Mockito.inOrder(mUnderlying);
        // First sound model load should complete successfully
        mComponent.loadPhraseSoundModel(soundModel, mModelCallback);
        inOrder.verify(mUnderlying).loadPhraseSoundModel(eq(soundModel), eq(mModelCallback));
        mComponent.loadPhraseSoundModel(soundModelDifferentUuid, mModelCallback);
        inOrder.verify(mUnderlying).loadPhraseSoundModel(eq(soundModelDifferentUuid),
                eq(mModelCallback));
        // No contention, so we don't get a callback
        verify(mGlobalCallback, never()).onResourcesAvailable();
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    @Test
    public void loadPhraseSoundModel_doesNotThrow_afterDuplicateUuidHasBeenUnloaded() {
        final var soundModel = createPhraseSoundModelOne();
        // First sound model load should complete successfully
        int handle = mComponent.loadPhraseSoundModel(soundModel, mModelCallback);
        verify(mUnderlying).loadPhraseSoundModel(eq(soundModel), eq(mModelCallback));
        // Unload model should complete successfully
        mComponent.unloadSoundModel(handle);
        verify(mUnderlying).unloadSoundModel(eq(handle));
        // Since the model with the same UUID was unloaded, the subsequent load model
        // should succeed.
        mComponent.loadPhraseSoundModel(soundModel, mModelCallback);
        verify(mUnderlying, times(2)).loadPhraseSoundModel(eq(soundModel), eq(mModelCallback));
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    @Test
    public void unloadSoundModel_triggersResourceCallback_afterDuplicateUuidRejectedPhrase() {
        final var soundModel = createPhraseSoundModelOne();
        final var soundModelSameUuid = createPhraseSoundModelTwo();
        // First sound model load should complete successfully
        int handle = mComponent.loadPhraseSoundModel(soundModel, mModelCallback);
        verify(mUnderlying).loadPhraseSoundModel(eq(soundModel), eq(mModelCallback));
        assertEquals(
                assertThrows(
                                RecoverableException.class,
                                () ->
                                        mComponent.loadPhraseSoundModel(
                                                soundModelSameUuid, mModelCallback))
                        .errorCode,
                Status.RESOURCE_CONTENTION);
        mComponent.unloadSoundModel(handle);
        verify(mUnderlying).unloadSoundModel(eq(handle));
        verify(mGlobalCallback).onResourcesAvailable();
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    @Test
    public void testDelegation() {
        // Test that the rest of the interface delegates its calls to the underlying object
        // appropriately.
        // This test method does not test load/unloadSoundModel
        var properties = new Properties();
        InOrder inOrder = Mockito.inOrder(mUnderlying);
        doReturn(properties).when(mUnderlying).getProperties();
        assertEquals(mComponent.getProperties(), properties);
        inOrder.verify(mUnderlying).getProperties();
        var mockGlobalCallback = mock(ISoundTriggerHal.GlobalCallback.class);
        mComponent.registerCallback(mockGlobalCallback);
        inOrder.verify(mUnderlying).registerCallback(eq(mockGlobalCallback));
        int modelId = 5;
        int deviceHandle = 2;
        int ioHandle = 3;
        var config = mock(RecognitionConfig.class);
        mComponent.startRecognition(modelId, deviceHandle, ioHandle, config);
        inOrder.verify(mUnderlying)
                .startRecognition(eq(modelId), eq(deviceHandle), eq(ioHandle), eq(config));

        mComponent.stopRecognition(modelId);
        inOrder.verify(mUnderlying).stopRecognition(eq(modelId));
        mComponent.forceRecognitionEvent(modelId);
        inOrder.verify(mUnderlying).forceRecognitionEvent(eq(modelId));
        int param = 10;
        int value = 50;
        var modelParamRange = new ModelParameterRange();
        doReturn(modelParamRange).when(mUnderlying).queryParameter(anyInt(), anyInt());
        assertEquals(mComponent.queryParameter(param, value), modelParamRange);
        inOrder.verify(mUnderlying).queryParameter(param, value);
        doReturn(value).when(mUnderlying).getModelParameter(anyInt(), anyInt());
        assertEquals(mComponent.getModelParameter(modelId, param), value);
        inOrder.verify(mUnderlying).getModelParameter(eq(modelId), eq(param));
        mComponent.setModelParameter(modelId, param, value);
        inOrder.verify(mUnderlying).setModelParameter(eq(modelId), eq(param), eq(value));
        var recipient = mock(IBinder.DeathRecipient.class);
        mComponent.linkToDeath(recipient);
        inOrder.verify(mUnderlying).linkToDeath(eq(recipient));
        mComponent.unlinkToDeath(recipient);
        inOrder.verify(mUnderlying).unlinkToDeath(eq(recipient));
        mComponent.flushCallbacks();
        inOrder.verify(mUnderlying).flushCallbacks();
        var token = mock(IBinder.class);
        mComponent.clientAttached(token);
        inOrder.verify(mUnderlying).clientAttached(eq(token));
        mComponent.clientDetached(token);
        inOrder.verify(mUnderlying).clientDetached(eq(token));
        mComponent.reboot();
        inOrder.verify(mUnderlying).reboot();
        mComponent.detach();
        inOrder.verify(mUnderlying).detach();
        verifyNoMoreInteractions(mUnderlying);
        verifyNoMoreInteractions(mGlobalCallback);
    }

    private static SoundModel createSoundModelOne() {
        SoundModel model = new SoundModel();
        model.type = SoundModelType.GENERIC;
        model.uuid = DUPLICATE_UUID;
        model.vendorUuid = "87654321-5432-6543-7654-456789fedcba";
        byte[] data = new byte[] {91, 92, 93, 94, 95};
        model.data = TestUtil.byteArrayToParcelFileDescriptor(data);
        model.dataSize = data.length;
        return model;
    }

    // Different except for the same UUID
    private static SoundModel createSoundModelTwo() {
        SoundModel model = new SoundModel();
        model.type = SoundModelType.GENERIC;
        model.uuid = DUPLICATE_UUID;
        model.vendorUuid = "12345678-9876-5432-1012-345678901234";
        byte[] data = new byte[] {19, 18, 17, 16};
        model.data = TestUtil.byteArrayToParcelFileDescriptor(data);
        model.dataSize = data.length;
        return model;
    }

    private static PhraseSoundModel createPhraseSoundModelOne() {
        PhraseSoundModel model = new PhraseSoundModel();
        model.common = createSoundModelOne();
        model.common.type = SoundModelType.KEYPHRASE;
        model.phrases = new Phrase[1];
        model.phrases[0] = new Phrase();
        model.phrases[0].id = 123;
        model.phrases[0].users = new int[] {5, 6, 7};
        model.phrases[0].locale = "locale";
        model.phrases[0].text = "text";
        model.phrases[0].recognitionModes =
                RecognitionMode.USER_AUTHENTICATION | RecognitionMode.USER_IDENTIFICATION;
        return model;
    }

    private static PhraseSoundModel createPhraseSoundModelTwo() {
        PhraseSoundModel model = new PhraseSoundModel();
        model.common = createSoundModelTwo();
        model.common.type = SoundModelType.KEYPHRASE;
        model.phrases = new Phrase[1];
        model.phrases[0] = new Phrase();
        model.phrases[0].id = 321;
        model.phrases[0].users = new int[] {4, 3, 2, 1};
        model.phrases[0].locale = "differentLocale";
        model.phrases[0].text = "differentText";
        model.phrases[0].recognitionModes = 0;
        return model;
    }
}
