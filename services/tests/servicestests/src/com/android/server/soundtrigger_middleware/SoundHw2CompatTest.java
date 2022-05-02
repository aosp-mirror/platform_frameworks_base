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
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.Status;
import android.os.HwParcel;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.RemoteException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(Parameterized.class)
public class SoundHw2CompatTest {
    @Parameterized.Parameter public String mVersion;

    private final Runnable mRebootRunnable = mock(Runnable.class);
    private ISoundTriggerHal mCanonical;
    private android.hardware.soundtrigger.V2_0.ISoundTriggerHw mHalDriver;

    // We run the test once for every version of the underlying driver.
    @Parameterized.Parameters
    public static Object[] data() {
        return new String[]{"V2_0", "V2_1", "V2_2", "V2_3"};
    }

    @Before
    public void setUp() throws Exception {
        mHalDriver = (android.hardware.soundtrigger.V2_0.ISoundTriggerHw) mock(Class.forName(
                String.format("android.hardware.soundtrigger.%s.ISoundTriggerHw", mVersion)));

        clearInvocations(mRebootRunnable);

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
                try {
                    return mHalDriver.linkToDeath(recipient, cookie);
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }
            }

            @Override
            public boolean unlinkToDeath(DeathRecipient recipient) {
                try {
                    return mHalDriver.unlinkToDeath(recipient);
                } catch (RemoteException e) {
                    throw e.rethrowAsRuntimeException();
                }
            }
        };
        when(mHalDriver.asBinder()).thenReturn(binder);

        android.hardware.soundtrigger.V2_3.Properties halProperties =
                TestUtil.createDefaultProperties_2_3();
        doAnswer(invocation -> {
            ((android.hardware.soundtrigger.V2_0.ISoundTriggerHw.getPropertiesCallback) invocation.getArgument(
                    0)).onValues(0, halProperties.base);
            return null;
        }).when(mHalDriver).getProperties(any());

        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                    (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;
            doAnswer(invocation -> {
                ((android.hardware.soundtrigger.V2_3.ISoundTriggerHw.getProperties_2_3Callback) invocation.getArgument(
                        0)).onValues(0, halProperties);
                return null;
            }).when(driver).getProperties_2_3(any());
        }

        mCanonical = SoundTriggerHw2Compat.create(mHalDriver, mRebootRunnable, null);

        // During initialization any method can be called, but after we're starting to enforce that
        // no additional methods are called.
        clearInvocations(mHalDriver);
    }

    @After
    public void tearDown() {
        mCanonical.detach();
        verifyNoMoreInteractions(mHalDriver);
        verifyNoMoreInteractions(mRebootRunnable);
    }

    @Test
    public void testSetUpAndTearDown() {
    }

    @Test
    public void testReboot() {
        mCanonical.reboot();
        verify(mRebootRunnable).run();
    }

    @Test
    public void testGetProperties() throws Exception {
        Properties properties = mCanonical.getProperties();

        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                    (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;
            // It is OK for the SUT to cache the properties, so the underlying method doesn't
            // need to be called every single time.
            verify(driver, atMost(1)).getProperties_2_3(any());
            TestUtil.validateDefaultProperties(properties);
        } else {
            // It is OK for the SUT to cache the properties, so the underlying method doesn't
            // need to be called every single time.
            verify(mHalDriver, atMost(1)).getProperties(any());
            TestUtil.validateDefaultProperties(properties, 0, "");
        }
    }

    private int loadGenericModel_2_0(ISoundTriggerHal.ModelCallback canonicalCallback)
            throws Exception {
        final int handle = 29;
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel> modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.loadSoundModelCallback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(mHalDriver).loadSoundModel(any(), any(), anyInt(), any());

        assertEquals(handle,
                mCanonical.loadSoundModel(TestUtil.createGenericSoundModel(), canonicalCallback));

        verify(mHalDriver).loadSoundModel(modelCaptor.capture(), callbackCaptor.capture(), anyInt(),
                any());

        TestUtil.validateGenericSoundModel_2_0(modelCaptor.getValue());
        validateCallback_2_0(callbackCaptor.getValue(), canonicalCallback);
        return handle;
    }

    private int loadGenericModel_2_1(ISoundTriggerHal.ModelCallback canonicalCallback)
            throws Exception {
        final android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver_2_1 =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;

        final int handle = 29;
        AtomicReference<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel> model =
                new AtomicReference<>();
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            // We need to dup the model, as it gets invalidated after the call returns.
            model.set(TestUtil.dupModel_2_1(invocation.getArgument(0)));
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.loadSoundModel_2_1Callback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(driver_2_1).loadSoundModel_2_1(any(), any(), anyInt(), any());

        assertEquals(handle,
                mCanonical.loadSoundModel(TestUtil.createGenericSoundModel(), canonicalCallback));

        verify(driver_2_1).loadSoundModel_2_1(any(), callbackCaptor.capture(), anyInt(), any());

        TestUtil.validateGenericSoundModel_2_1(model.get());
        validateCallback_2_1(callbackCaptor.getValue(), canonicalCallback);
        return handle;
    }

    private int loadGenericModel(ISoundTriggerHal.ModelCallback canonicalCallback)
            throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            return loadGenericModel_2_1(canonicalCallback);
        } else {
            return loadGenericModel_2_0(canonicalCallback);
        }
    }

    @Test
    public void testLoadGenericModel() throws Exception {
        ISoundTriggerHal.ModelCallback canonicalCallback = mock(
                ISoundTriggerHal.ModelCallback.class);
        loadGenericModel(canonicalCallback);
    }

    @Test
    public void testMaxModels() throws Exception {
        // Register global callback.
        ISoundTriggerHal.GlobalCallback globalCallback = mock(
                ISoundTriggerHal.GlobalCallback.class);
        mCanonical.registerCallback(globalCallback);

        ISoundTriggerHal.ModelCallback canonicalCallback = mock(
                ISoundTriggerHal.ModelCallback.class);
        final int maxModels = TestUtil.createDefaultProperties_2_0().maxSoundModels;
        int[] modelHandles = new int[maxModels];

        // Load as many models as we're allowed.
        for (int i = 0; i < maxModels; ++i) {
            modelHandles[i] = loadGenericModel(canonicalCallback);
            verifyNoMoreInteractions(mHalDriver);
            clearInvocations(mHalDriver);
        }

        // Now try to load an additional one and expect failure without invoking the underlying
        // driver.
        try {
            mCanonical.loadPhraseSoundModel(TestUtil.createPhraseSoundModel(), canonicalCallback);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.RESOURCE_CONTENTION, e.errorCode);
        }

        // Unload a single model and expect a onResourcesAvailable().
        mCanonical.unloadSoundModel(modelHandles[0]);
        verify(mHalDriver).unloadSoundModel(modelHandles[0]);

        mCanonical.flushCallbacks();
        verify(globalCallback).onResourcesAvailable();
    }

    private void loadPhraseModel_2_0(ISoundTriggerHal.ModelCallback canonicalCallback)
            throws Exception {
        final int handle = 29;
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel>
                modelCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.loadPhraseSoundModelCallback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(mHalDriver).loadPhraseSoundModel(any(), any(), anyInt(), any());

        assertEquals(handle, mCanonical.loadPhraseSoundModel(TestUtil.createPhraseSoundModel(),
                canonicalCallback));

        verify(mHalDriver).loadPhraseSoundModel(modelCaptor.capture(), callbackCaptor.capture(),
                anyInt(), any());

        TestUtil.validatePhraseSoundModel_2_0(modelCaptor.getValue());
        validateCallback_2_0(callbackCaptor.getValue(), canonicalCallback);
    }

    private void loadPhraseModel_2_1(ISoundTriggerHal.ModelCallback canonicalCallback)
            throws Exception {
        final android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver_2_1 =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;

        final int handle = 29;
        AtomicReference<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel> model =
                new AtomicReference<>();
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            // We need to dup the model, as it gets invalidated after the call returns.
            model.set(TestUtil.dupPhraseModel_2_1(invocation.getArgument(0)));

            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.loadPhraseSoundModel_2_1Callback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(driver_2_1).loadPhraseSoundModel_2_1(any(), any(), anyInt(), any());

        assertEquals(handle, mCanonical.loadPhraseSoundModel(TestUtil.createPhraseSoundModel(),
                canonicalCallback));

        verify(driver_2_1).loadPhraseSoundModel_2_1(any(), callbackCaptor.capture(), anyInt(),
                any());

        TestUtil.validatePhraseSoundModel_2_1(model.get());
        validateCallback_2_1(callbackCaptor.getValue(), canonicalCallback);
    }

    public void loadPhraseModel(ISoundTriggerHal.ModelCallback canonicalCallback) throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            loadPhraseModel_2_1(canonicalCallback);
        } else {
            loadPhraseModel_2_0(canonicalCallback);
        }
    }

    @Test
    public void testLoadPhraseModel() throws Exception {
        ISoundTriggerHal.ModelCallback canonicalCallback = mock(
                ISoundTriggerHal.ModelCallback.class);
        loadPhraseModel(canonicalCallback);
    }

    @Test
    public void testUnloadModel() throws Exception {
        mCanonical.unloadSoundModel(14);
        verify(mHalDriver).unloadSoundModel(14);
    }

    private void startRecognition_2_0(int handle, ISoundTriggerHal.ModelCallback canonicalCallback)
            throws Exception {
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.class);

        when(mHalDriver.startRecognition(eq(handle), any(), any(), anyInt())).thenReturn(0);

        RecognitionConfig config = TestUtil.createRecognitionConfig();
        mCanonical.startRecognition(handle, 203, 204, config);
        verify(mHalDriver).startRecognition(eq(handle), configCaptor.capture(),
                callbackCaptor.capture(), anyInt());

        TestUtil.validateRecognitionConfig_2_0(configCaptor.getValue(), 203, 204);
        validateCallback_2_0(callbackCaptor.getValue(), canonicalCallback);
    }

    private void startRecognition_2_1(int handle, ISoundTriggerHal.ModelCallback canonicalCallback)
            throws Exception {
        final android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver_2_1 =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;

        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback> callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.class);

        when(driver_2_1.startRecognition_2_1(eq(handle), any(), any(), anyInt())).thenReturn(0);

        RecognitionConfig config = TestUtil.createRecognitionConfig();
        mCanonical.startRecognition(handle, 505, 506, config);
        verify(driver_2_1).startRecognition_2_1(eq(handle), configCaptor.capture(),
                callbackCaptor.capture(), anyInt());

        TestUtil.validateRecognitionConfig_2_1(configCaptor.getValue(), 505, 506);
        validateCallback_2_1(callbackCaptor.getValue(), canonicalCallback);
    }

    private void startRecognition_2_3(int handle) throws Exception {
        final android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver_2_3 =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;
        ArgumentCaptor<android.hardware.soundtrigger.V2_3.RecognitionConfig> configCaptor =
                ArgumentCaptor.forClass(android.hardware.soundtrigger.V2_3.RecognitionConfig.class);

        when(driver_2_3.startRecognition_2_3(eq(handle), any())).thenReturn(0);

        RecognitionConfig config = TestUtil.createRecognitionConfig();
        mCanonical.startRecognition(handle, 808, 909, config);
        verify(driver_2_3).startRecognition_2_3(eq(handle), configCaptor.capture());
        TestUtil.validateRecognitionConfig_2_3(configCaptor.getValue(), 808, 909);
    }

    private void startRecognition(int handle, ISoundTriggerHal.ModelCallback canonicalCallback)
            throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            startRecognition_2_3(handle);
        } else if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            startRecognition_2_1(handle, canonicalCallback);
        } else {
            startRecognition_2_0(handle, canonicalCallback);
        }
    }

    @Test
    public void testStartRecognition() throws Exception {
        // First load.
        ISoundTriggerHal.ModelCallback canonicalCallback = mock(
                ISoundTriggerHal.ModelCallback.class);
        final int handle = loadGenericModel(canonicalCallback);

        // Then start.
        startRecognition(handle, canonicalCallback);
    }

    @Test
    public void testStopRecognition() throws Exception {
        mCanonical.stopRecognition(17);
        verify(mHalDriver).stopRecognition(17);
    }

    @Test
    public void testForceRecognition() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_2.ISoundTriggerHw) {
            android.hardware.soundtrigger.V2_2.ISoundTriggerHw driver_2_2 =
                    (android.hardware.soundtrigger.V2_2.ISoundTriggerHw) mHalDriver;
            mCanonical.forceRecognitionEvent(14);
            verify(driver_2_2).getModelState(14);
        } else {
            try {
                mCanonical.forceRecognitionEvent(14);
                fail("Expected an exception");
            } catch (RecoverableException e) {
                assertEquals(Status.OPERATION_NOT_SUPPORTED, e.errorCode);
            }
        }
    }

    @Test
    public void testGetParameter() throws Exception {
        assumeTrue(mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw);

        android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver_2_3 =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw.getParameterCallback resultCallback =
                    invocation.getArgument(2);

            // This is the return of this method.
            resultCallback.onValues(0, 99);
            return null;
        }).when(driver_2_3).getParameter(eq(21), eq(47), any());

        assertEquals(99, mCanonical.getModelParameter(21, 47));
        verify(driver_2_3).getParameter(eq(21), eq(47), any());
    }

    @Test
    public void testSetParameter() throws Exception {
        assumeTrue(mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw);

        android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver_2_3 =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        mCanonical.setModelParameter(212, 247, 80);
        verify(driver_2_3).setParameter(212, 247, 80);
    }

    @Test
    public void testQueryParameterSupported() throws Exception {
        assumeTrue(mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw);

        android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver_2_3 =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw.queryParameterCallback
                    resultCallback = invocation.getArgument(2);

            // This is the return of this method.
            android.hardware.soundtrigger.V2_3.ModelParameterRange range =
                    new android.hardware.soundtrigger.V2_3.ModelParameterRange();
            range.start = 34;
            range.end = 45;
            android.hardware.soundtrigger.V2_3.OptionalModelParameterRange optionalRange =
                    new android.hardware.soundtrigger.V2_3.OptionalModelParameterRange();
            optionalRange.range(range);
            resultCallback.onValues(0, optionalRange);
            return null;
        }).when(driver_2_3).queryParameter(eq(11), eq(12), any());

        ModelParameterRange range = mCanonical.queryParameter(11, 12);
        assertNotNull(range);
        assertEquals(34, range.minInclusive);
        assertEquals(45, range.maxInclusive);
        verify(driver_2_3).queryParameter(eq(11), eq(12), any());
    }

    @Test
    public void testQueryParameterNotSupported() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver_2_3 =
                    (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

            doAnswer(invocation -> {
                android.hardware.soundtrigger.V2_3.ISoundTriggerHw.queryParameterCallback
                        resultCallback = invocation.getArgument(2);

                // This is the return of this method.
                android.hardware.soundtrigger.V2_3.OptionalModelParameterRange optionalRange =
                        new android.hardware.soundtrigger.V2_3.OptionalModelParameterRange();
                resultCallback.onValues(0, optionalRange);
                return null;
            }).when(driver_2_3).queryParameter(eq(11), eq(12), any());

            ModelParameterRange range = mCanonical.queryParameter(11, 12);
            assertNull(range);
            verify(driver_2_3).queryParameter(eq(11), eq(12), any());
        } else {
            ModelParameterRange range = mCanonical.queryParameter(11, 12);
            assertNull(range);
        }
    }

    private void testGlobalCallback_2_0() {
        ISoundTriggerHal.GlobalCallback canonicalCallback = mock(
                ISoundTriggerHal.GlobalCallback.class);
        mCanonical.registerCallback(canonicalCallback);
        // We just care that it doesn't throw.
    }

    @Test
    public void testGlobalCallback() {
        testGlobalCallback_2_0();
    }

    @Test
    public void testLinkToDeath() throws Exception {
        IBinder.DeathRecipient canonicalRecipient = mock(IBinder.DeathRecipient.class);
        when(mHalDriver.linkToDeath(any(), anyLong())).thenReturn(true);
        mCanonical.linkToDeath(canonicalRecipient);

        ArgumentCaptor<IHwBinder.DeathRecipient> recipientCaptor = ArgumentCaptor.forClass(
                IHwBinder.DeathRecipient.class);
        ArgumentCaptor<Long> cookieCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mHalDriver).linkToDeath(recipientCaptor.capture(), cookieCaptor.capture());

        recipientCaptor.getValue().serviceDied(cookieCaptor.getValue());
        mCanonical.flushCallbacks();
        verify(canonicalRecipient).binderDied();

        mCanonical.unlinkToDeath(canonicalRecipient);
        verify(mHalDriver).unlinkToDeath(recipientCaptor.getValue());
    }

    @Test
    public void testInterfaceDescriptor() throws Exception {
        when(mHalDriver.interfaceDescriptor()).thenReturn("ABCD");
        assertEquals("ABCD", mCanonical.interfaceDescriptor());
        verify(mHalDriver).interfaceDescriptor();
    }

    private void validateCallback_2_0(
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback hwCallback,
            ISoundTriggerHal.ModelCallback canonicalCallback) throws Exception {
        {
            final int handle = 85;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.ABORT;
            ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                    RecognitionEvent.class);

            hwCallback.recognitionCallback(TestUtil.createRecognitionEvent_2_0(handle, status), 99);
            mCanonical.flushCallbacks();
            verify(canonicalCallback).recognitionCallback(eq(handle), eventCaptor.capture());
            TestUtil.validateRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.ABORTED,
                    false);
        }

        {
            final int handle = 92;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS;
            ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                    PhraseRecognitionEvent.class);

            hwCallback.phraseRecognitionCallback(
                    TestUtil.createPhraseRecognitionEvent_2_0(handle, status), 99);
            mCanonical.flushCallbacks();
            verify(canonicalCallback).phraseRecognitionCallback(eq(handle), eventCaptor.capture());
            TestUtil.validatePhraseRecognitionEvent(eventCaptor.getValue(),
                    RecognitionStatus.SUCCESS, false);
        }
        verifyNoMoreInteractions(canonicalCallback);
        clearInvocations(canonicalCallback);
    }

    private void validateCallback_2_1(
            android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback hwCallback,
            ISoundTriggerHal.ModelCallback canonicalCallback) throws Exception {
        {
            final int handle = 85;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.ABORT;
            ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                    RecognitionEvent.class);

            hwCallback.recognitionCallback_2_1(TestUtil.createRecognitionEvent_2_1(handle, status),
                    99);
            mCanonical.flushCallbacks();
            verify(canonicalCallback).recognitionCallback(eq(handle), eventCaptor.capture());
            TestUtil.validateRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.ABORTED,
                    false);
        }

        {
            final int handle = 87;
            final int status = 3; // FORCED;
            ArgumentCaptor<RecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                    RecognitionEvent.class);

            hwCallback.recognitionCallback_2_1(TestUtil.createRecognitionEvent_2_1(handle, status),
                    99);
            mCanonical.flushCallbacks();
            verify(canonicalCallback).recognitionCallback(eq(handle), eventCaptor.capture());
            TestUtil.validateRecognitionEvent(eventCaptor.getValue(), RecognitionStatus.FORCED,
                    true);
        }

        {
            final int handle = 92;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS;
            ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                    PhraseRecognitionEvent.class);

            hwCallback.phraseRecognitionCallback_2_1(
                    TestUtil.createPhraseRecognitionEvent_2_1(handle, status), 99);
            mCanonical.flushCallbacks();
            verify(canonicalCallback).phraseRecognitionCallback(eq(handle), eventCaptor.capture());
            TestUtil.validatePhraseRecognitionEvent(eventCaptor.getValue(),
                    RecognitionStatus.SUCCESS, false);
        }

        {
            final int handle = 102;
            final int status = 3; // FORCED;
            ArgumentCaptor<PhraseRecognitionEvent> eventCaptor = ArgumentCaptor.forClass(
                    PhraseRecognitionEvent.class);

            hwCallback.phraseRecognitionCallback_2_1(
                    TestUtil.createPhraseRecognitionEvent_2_1(handle, status), 99);
            mCanonical.flushCallbacks();
            verify(canonicalCallback).phraseRecognitionCallback(eq(handle), eventCaptor.capture());
            TestUtil.validatePhraseRecognitionEvent(eventCaptor.getValue(),
                    RecognitionStatus.FORCED, true);
        }
        verifyNoMoreInteractions(canonicalCallback);
        clearInvocations(canonicalCallback);
    }
}
