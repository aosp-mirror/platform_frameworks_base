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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.media.soundtrigger_middleware.Status;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.RemoteException;
import android.system.OsConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;

@RunWith(Parameterized.class)
public class SoundHw2CompatTest {
    @Parameterized.Parameter
    public android.hardware.soundtrigger.V2_0.ISoundTriggerHw mHalDriver;
    private Runnable mRebootRunnable = mock(Runnable.class);
    private ISoundTriggerHw2 mCanonical;

    // We run the test once for every version of the underlying driver.
    @Parameterized.Parameters
    public static Object[] data() {
        return new Object[]{
                mock(android.hardware.soundtrigger.V2_0.ISoundTriggerHw.class),
                mock(android.hardware.soundtrigger.V2_1.ISoundTriggerHw.class),
                mock(android.hardware.soundtrigger.V2_2.ISoundTriggerHw.class),
                mock(android.hardware.soundtrigger.V2_3.ISoundTriggerHw.class),
                mock(android.hardware.soundtrigger.V2_4.ISoundTriggerHw.class),
        };
    }

    @Before
    public void setUp() throws Exception {
        clearInvocations(mHalDriver);
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
                        && mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw
                        || descriptor.equals("android.hardware.soundtrigger@2.4::ISoundTriggerHw")
                        && mHalDriver instanceof android.hardware.soundtrigger.V2_4.ISoundTriggerHw) {
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
        mCanonical = new SoundTriggerHw2Compat(mHalDriver, mRebootRunnable);
        // This method can be called any number of times.
        verify(mHalDriver, atLeast(0)).asBinder();
    }

    @After
    public void tearDown() {
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
        android.hardware.soundtrigger.V2_3.Properties halProperties =
                TestUtil.createDefaultProperties_2_3(true);

        doAnswer(invocation -> {
            ((android.hardware.soundtrigger.V2_0.ISoundTriggerHw.getPropertiesCallback)
                    invocation.getArgument(
                            0)).onValues(0,
                    halProperties.base);
            return null;
        }).when(mHalDriver).getProperties(any());

        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                    (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;
            doAnswer(invocation -> {
                ((android.hardware.soundtrigger.V2_3.ISoundTriggerHw.getProperties_2_3Callback)
                        invocation.getArgument(
                                0)).onValues(0,
                        halProperties);
                return null;
            }).when(driver).getProperties_2_3(any());
        }

        android.hardware.soundtrigger.V2_3.Properties properties = mCanonical.getProperties();

        if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver =
                    (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;
            verify(driver).getProperties_2_3(any());
            assertEquals(halProperties, properties);
        } else {
            verify(mHalDriver).getProperties(any());
            assertEquals(halProperties.base, properties.base);
            assertEquals(0, properties.audioCapabilities);
            assertEquals("", properties.supportedModelArch);
        }
    }

    private void testLoadGenericModel_2_0() throws Exception {
        final int handle = 29;
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel>
                modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback>
                callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.loadSoundModelCallback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(mHalDriver).loadSoundModel(any(), any(), anyInt(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        assertEquals(handle, mCanonical.loadSoundModel(TestUtil.createGenericSoundModel_2_1(),
                canonicalCallback));

        verify(mHalDriver).loadSoundModel(modelCaptor.capture(), callbackCaptor.capture(), anyInt(),
                any());

        TestUtil.validateGenericSoundModel_2_0(modelCaptor.getValue());
        validateCallback_2_0(callbackCaptor.getValue(), canonicalCallback);
    }

    private void testLoadGenericModel_2_1() throws Exception {
        final android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver_2_1 =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;

        final int handle = 29;
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel>
                modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback>
                callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.loadSoundModel_2_1Callback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(driver_2_1).loadSoundModel_2_1(any(), any(), anyInt(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        assertEquals(handle, mCanonical.loadSoundModel(TestUtil.createGenericSoundModel_2_1(),
                canonicalCallback));

        verify(driver_2_1).loadSoundModel_2_1(modelCaptor.capture(), callbackCaptor.capture(),
                anyInt(),
                any());

        TestUtil.validateGenericSoundModel_2_1(modelCaptor.getValue());
        validateCallback_2_1(callbackCaptor.getValue(), canonicalCallback);
    }

    private void testLoadGenericModel_2_4() throws Exception {
        final android.hardware.soundtrigger.V2_4.ISoundTriggerHw driver_2_4 =
                (android.hardware.soundtrigger.V2_4.ISoundTriggerHw) mHalDriver;

        final int handle = 29;
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel>
                modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_4.ISoundTriggerHwCallback>
                callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_4.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_4.ISoundTriggerHw.loadSoundModel_2_4Callback
                    resultCallback = invocation.getArgument(2);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(driver_2_4).loadSoundModel_2_4(any(), any(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        assertEquals(handle, mCanonical.loadSoundModel(TestUtil.createGenericSoundModel_2_1(),
                canonicalCallback));

        verify(driver_2_4).loadSoundModel_2_4(modelCaptor.capture(), callbackCaptor.capture(),
                any());

        TestUtil.validateGenericSoundModel_2_1(modelCaptor.getValue());
        validateCallback_2_4(callbackCaptor.getValue(), canonicalCallback);
    }

    @Test
    public void testLoadGenericModel() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_4.ISoundTriggerHw) {
            testLoadGenericModel_2_4();
        } else if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            testLoadGenericModel_2_1();
        } else {
            testLoadGenericModel_2_0();
        }
    }

    private void testLoadGenericModelBusy_2_4() throws Exception {
        final android.hardware.soundtrigger.V2_4.ISoundTriggerHw driver_2_4 =
                (android.hardware.soundtrigger.V2_4.ISoundTriggerHw) mHalDriver;

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_4.ISoundTriggerHw.loadSoundModel_2_4Callback
                    resultCallback = invocation.getArgument(2);

            // This is the return of this method.
            resultCallback.onValues(-OsConstants.EBUSY, 0);
            return null;
        }).when(driver_2_4).loadSoundModel_2_4(any(), any(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        try {
            mCanonical.loadSoundModel(TestUtil.createGenericSoundModel_2_1(),
                    canonicalCallback);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.RESOURCE_CONTENTION, e.errorCode);
        }
        verify(driver_2_4).loadSoundModel_2_4(any(), any(), any());
    }

    @Test
    public void testLoadGenericModelBusy() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_4.ISoundTriggerHw) {
            testLoadGenericModelBusy_2_4();
        }
    }

    private void testLoadPhraseModel_2_0() throws Exception {
        final int handle = 29;
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel>
                modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback>
                callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.loadPhraseSoundModelCallback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(mHalDriver).loadPhraseSoundModel(any(), any(), anyInt(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        assertEquals(handle, mCanonical.loadPhraseSoundModel(TestUtil.createPhraseSoundModel_2_1(),
                canonicalCallback));

        verify(mHalDriver).loadPhraseSoundModel(modelCaptor.capture(), callbackCaptor.capture(),
                anyInt(),
                any());

        TestUtil.validatePhraseSoundModel_2_0(modelCaptor.getValue());
        validateCallback_2_0(callbackCaptor.getValue(), canonicalCallback);
    }

    private void testLoadPhraseModel_2_1() throws Exception {
        final android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver_2_1 =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;

        final int handle = 29;
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel>
                modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback>
                callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.loadPhraseSoundModel_2_1Callback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(driver_2_1).loadPhraseSoundModel_2_1(any(), any(), anyInt(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        assertEquals(handle, mCanonical.loadPhraseSoundModel(TestUtil.createPhraseSoundModel_2_1(),
                canonicalCallback));

        verify(driver_2_1).loadPhraseSoundModel_2_1(modelCaptor.capture(), callbackCaptor.capture(),
                anyInt(),
                any());

        TestUtil.validatePhraseSoundModel_2_1(modelCaptor.getValue());
        validateCallback_2_1(callbackCaptor.getValue(), canonicalCallback);
    }

    private void testLoadPhraseModel_2_4() throws Exception {
        final android.hardware.soundtrigger.V2_4.ISoundTriggerHw driver_2_4 =
                (android.hardware.soundtrigger.V2_4.ISoundTriggerHw) mHalDriver;

        final int handle = 29;
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel>
                modelCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_4.ISoundTriggerHwCallback>
                callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_4.ISoundTriggerHwCallback.class);

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_4.ISoundTriggerHw.loadPhraseSoundModel_2_4Callback
                    resultCallback = invocation.getArgument(2);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(driver_2_4).loadPhraseSoundModel_2_4(any(), any(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        assertEquals(handle, mCanonical.loadPhraseSoundModel(TestUtil.createPhraseSoundModel_2_1(),
                canonicalCallback));

        verify(driver_2_4).loadPhraseSoundModel_2_4(modelCaptor.capture(), callbackCaptor.capture(),
                any());

        TestUtil.validatePhraseSoundModel_2_1(modelCaptor.getValue());
        validateCallback_2_4(callbackCaptor.getValue(), canonicalCallback);
    }

    @Test
    public void testLoadPhraseModel() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_4.ISoundTriggerHw) {
            testLoadPhraseModel_2_4();
        } else if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            testLoadPhraseModel_2_1();
        } else {
            testLoadPhraseModel_2_0();
        }
    }

    private void testLoadPhraseModelBusy_2_4() throws Exception {
        final android.hardware.soundtrigger.V2_4.ISoundTriggerHw driver_2_4 =
                (android.hardware.soundtrigger.V2_4.ISoundTriggerHw) mHalDriver;

        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_4.ISoundTriggerHw.loadPhraseSoundModel_2_4Callback
                    resultCallback = invocation.getArgument(2);

            // This is the return of this method.
            resultCallback.onValues(-OsConstants.EBUSY, 0);
            return null;
        }).when(driver_2_4).loadPhraseSoundModel_2_4(any(), any(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        try {
            mCanonical.loadPhraseSoundModel(TestUtil.createPhraseSoundModel_2_1(),
                    canonicalCallback);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.RESOURCE_CONTENTION, e.errorCode);
        }
        verify(driver_2_4).loadPhraseSoundModel_2_4(any(), any(), any());
    }

    @Test
    public void testLoadPhraseModelBusy() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_4.ISoundTriggerHw) {
            testLoadPhraseModelBusy_2_4();
        }
    }

    @Test
    public void testUnloadModel() throws Exception {
        mCanonical.unloadSoundModel(14);
        verify(mHalDriver).unloadSoundModel(14);
    }

    private void testStartRecognition_2_0() throws Exception {
        final int handle = 65;

        // First load (this registers the callback).
        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.loadSoundModelCallback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(mHalDriver).loadSoundModel(any(), any(), anyInt(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        assertEquals(handle, mCanonical.loadSoundModel(TestUtil.createGenericSoundModel_2_1(),
                canonicalCallback));
        verify(mHalDriver).loadSoundModel(any(), any(), anyInt(), any());

        // Then start.
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback>
                callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.class);

        when(mHalDriver.startRecognition(eq(handle), any(), any(), anyInt())).thenReturn(0);

        android.hardware.soundtrigger.V2_3.RecognitionConfig config =
                TestUtil.createRecognitionConfig_2_3(203, 204);
        mCanonical.startRecognition(handle, config);
        verify(mHalDriver).startRecognition(eq(handle), configCaptor.capture(),
                callbackCaptor.capture(), anyInt());

        TestUtil.validateRecognitionConfig_2_0(configCaptor.getValue(), 203, 204);
        validateCallback_2_0(callbackCaptor.getValue(), canonicalCallback);
    }

    private void testStartRecognition_2_1() throws Exception {
        final android.hardware.soundtrigger.V2_1.ISoundTriggerHw driver_2_1 =
                (android.hardware.soundtrigger.V2_1.ISoundTriggerHw) mHalDriver;

        final int handle = 68;

        // First load (this registers the callback).
        doAnswer(invocation -> {
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.loadSoundModel_2_1Callback
                    resultCallback = invocation.getArgument(3);

            // This is the return of this method.
            resultCallback.onValues(0, handle);
            return null;
        }).when(driver_2_1).loadSoundModel_2_1(any(), any(), anyInt(), any());

        ISoundTriggerHw2.ModelCallback canonicalCallback = mock(
                ISoundTriggerHw2.ModelCallback.class);
        assertEquals(handle, mCanonical.loadSoundModel(TestUtil.createGenericSoundModel_2_1(),
                canonicalCallback));
        verify(driver_2_1).loadSoundModel_2_1(any(), any(), anyInt(), any());

        // Then start.
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig.class);
        ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback>
                callbackCaptor =
                ArgumentCaptor.forClass(
                        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.class);

        when(driver_2_1.startRecognition_2_1(eq(handle), any(), any(), anyInt())).thenReturn(0);

        android.hardware.soundtrigger.V2_3.RecognitionConfig config =
                TestUtil.createRecognitionConfig_2_3(505, 506);
        mCanonical.startRecognition(handle, config);
        verify(driver_2_1).startRecognition_2_1(eq(handle), configCaptor.capture(),
                callbackCaptor.capture(),
                anyInt());

        TestUtil.validateRecognitionConfig_2_1(configCaptor.getValue(), 505, 506);
        validateCallback_2_1(callbackCaptor.getValue(), canonicalCallback);
    }

    private void testStartRecognition_2_3() throws Exception {
        final android.hardware.soundtrigger.V2_3.ISoundTriggerHw driver_2_3 =
                (android.hardware.soundtrigger.V2_3.ISoundTriggerHw) mHalDriver;

        final int handle = 68;
        ArgumentCaptor<android.hardware.soundtrigger.V2_3.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_3.RecognitionConfig.class);

        when(driver_2_3.startRecognition_2_3(eq(handle), any())).thenReturn(0);

        android.hardware.soundtrigger.V2_3.RecognitionConfig config =
                TestUtil.createRecognitionConfig_2_3(808, 909);
        mCanonical.startRecognition(handle, config);
        verify(driver_2_3).startRecognition_2_3(eq(handle), configCaptor.capture());
        TestUtil.validateRecognitionConfig_2_3(configCaptor.getValue(), 808, 909);
    }

    private void testStartRecognition_2_4() throws Exception {
        final android.hardware.soundtrigger.V2_4.ISoundTriggerHw driver_2_4 =
                (android.hardware.soundtrigger.V2_4.ISoundTriggerHw) mHalDriver;

        final int handle = 68;
        ArgumentCaptor<android.hardware.soundtrigger.V2_3.RecognitionConfig>
                configCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_3.RecognitionConfig.class);

        when(driver_2_4.startRecognition_2_4(eq(handle), any())).thenReturn(0);

        android.hardware.soundtrigger.V2_3.RecognitionConfig config =
                TestUtil.createRecognitionConfig_2_3(21, 22);
        mCanonical.startRecognition(handle, config);
        verify(driver_2_4).startRecognition_2_4(eq(handle), configCaptor.capture());
        TestUtil.validateRecognitionConfig_2_3(configCaptor.getValue(), 21, 22);
    }

    @Test
    public void testStartRecognition() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_4.ISoundTriggerHw) {
            testStartRecognition_2_4();
        } else if (mHalDriver instanceof android.hardware.soundtrigger.V2_3.ISoundTriggerHw) {
            testStartRecognition_2_3();
        } else if (mHalDriver instanceof android.hardware.soundtrigger.V2_1.ISoundTriggerHw) {
            testStartRecognition_2_1();
        } else {
            testStartRecognition_2_0();
        }
    }

    private void testStartRecognitionBusy_2_4() throws Exception {
        final android.hardware.soundtrigger.V2_4.ISoundTriggerHw driver_2_4 =
                (android.hardware.soundtrigger.V2_4.ISoundTriggerHw) mHalDriver;

        final int handle = 68;
        when(driver_2_4.startRecognition_2_4(eq(handle), any())).thenReturn(-OsConstants.EBUSY);

        android.hardware.soundtrigger.V2_3.RecognitionConfig config =
                TestUtil.createRecognitionConfig_2_3(34, 35);
        try {
            mCanonical.startRecognition(handle, config);
            fail("Expected an exception");
        } catch (RecoverableException e) {
            assertEquals(Status.RESOURCE_CONTENTION, e.errorCode);
        }
        verify(driver_2_4).startRecognition_2_4(eq(handle), any());
    }

    @Test
    public void testStartRecognitionBusy() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_4.ISoundTriggerHw) {
            testStartRecognitionBusy_2_4();
        }
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
            mCanonical.getModelState(14);
            verify(driver_2_2).getModelState(14);
        } else {
            try {
                mCanonical.getModelState(14);
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
            android.hardware.soundtrigger.V2_3.ISoundTriggerHw.getParameterCallback
                    resultCallback = invocation.getArgument(2);

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

        android.hardware.soundtrigger.V2_3.ModelParameterRange range = mCanonical.queryParameter(11,
                12);
        assertNotNull(range);
        assertEquals(34, range.start);
        assertEquals(45, range.end);
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

            android.hardware.soundtrigger.V2_3.ModelParameterRange range =
                    mCanonical.queryParameter(11, 12);
            assertNull(range);
            verify(driver_2_3).queryParameter(eq(11), eq(12), any());
        } else {
            android.hardware.soundtrigger.V2_3.ModelParameterRange range =
                    mCanonical.queryParameter(11, 12);
            assertNull(range);
        }
    }

    private void testGlobalCallback_2_0() {
        ISoundTriggerHw2.GlobalCallback canonicalCallback = mock(
                ISoundTriggerHw2.GlobalCallback.class);
        mCanonical.registerCallback(canonicalCallback);
        // We just care that it doesn't throw.
    }

    private void testGlobalCallback_2_4() throws Exception {
        android.hardware.soundtrigger.V2_4.ISoundTriggerHw driver_2_4 =
                (android.hardware.soundtrigger.V2_4.ISoundTriggerHw) mHalDriver;

        ISoundTriggerHw2.GlobalCallback canonicalCallback = mock(
                ISoundTriggerHw2.GlobalCallback.class);
        mCanonical.registerCallback(canonicalCallback);

        ArgumentCaptor<android.hardware.soundtrigger.V2_4.ISoundTriggerHwGlobalCallback>
                callbackCaptor = ArgumentCaptor.forClass(
                android.hardware.soundtrigger.V2_4.ISoundTriggerHwGlobalCallback.class);
        verify(driver_2_4).registerGlobalCallback(callbackCaptor.capture());
        validateGlobalCallback_2_4(callbackCaptor.getValue(), canonicalCallback);
    }

    @Test
    public void testGlobalCallback() throws Exception {
        if (mHalDriver instanceof android.hardware.soundtrigger.V2_4.ISoundTriggerHw) {
            testGlobalCallback_2_4();
        } else {
            testGlobalCallback_2_0();
        }
    }

    @Test
    public void testLinkToDeath() throws Exception {
        IHwBinder.DeathRecipient canonicalRecipient = mock(IHwBinder.DeathRecipient.class);
        mCanonical.linkToDeath(canonicalRecipient, 19);

        ArgumentCaptor<IHwBinder.DeathRecipient> recipientCaptor = ArgumentCaptor.forClass(
                IHwBinder.DeathRecipient.class);
        ArgumentCaptor<Long> cookieCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mHalDriver).linkToDeath(recipientCaptor.capture(), cookieCaptor.capture());

        recipientCaptor.getValue().serviceDied(cookieCaptor.getValue());
        verify(canonicalRecipient).serviceDied(19);

        mCanonical.unlinkToDeath(canonicalRecipient);
        verify(mHalDriver).unlinkToDeath(recipientCaptor.getValue());
    }

    @Test
    public void testInterfaceDescriptor() throws Exception {
        when(mHalDriver.interfaceDescriptor()).thenReturn("ABCD");
        assertEquals("ABCD", mCanonical.interfaceDescriptor());
        verify(mHalDriver).interfaceDescriptor();
    }

    private static void validateGlobalCallback_2_4(
            android.hardware.soundtrigger.V2_4.ISoundTriggerHwGlobalCallback hwCallback,
            ISoundTriggerHw2.GlobalCallback canonicalCallback) throws Exception {
        hwCallback.tryAgain();
        verify(canonicalCallback).tryAgain();
    }

    private static void validateCallback_2_0(
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback hwCallback,
            ISoundTriggerHw2.ModelCallback canonicalCallback) throws Exception {
        {
            final int handle = 85;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.ABORT;
            ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent>
                    eventCaptor = ArgumentCaptor.forClass(
                    android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent.class);

            hwCallback.recognitionCallback(TestUtil.createRecognitionEvent_2_0(handle, status, 555),
                    99);
            verify(canonicalCallback).recognitionCallback(eventCaptor.capture());
            TestUtil.validateRecognitionEvent_2_1(eventCaptor.getValue(), handle, status, 555);
        }

        {
            final int handle = 92;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS;
            ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent>
                    eventCaptor = ArgumentCaptor.forClass(
                    android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent.class);

            hwCallback.phraseRecognitionCallback(
                    TestUtil.createPhraseRecognitionEvent_2_0(handle, status, 666), 99);
            verify(canonicalCallback).phraseRecognitionCallback(eventCaptor.capture());
            TestUtil.validatePhraseRecognitionEvent_2_1(eventCaptor.getValue(), handle, status,
                    666);
        }
    }

    private void validateCallback_2_1(
            android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback hwCallback,
            ISoundTriggerHw2.ModelCallback canonicalCallback) throws Exception {
        {
            final int handle = 85;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.ABORT;
            ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent>
                    eventCaptor = ArgumentCaptor.forClass(
                    android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent.class);

            hwCallback.recognitionCallback_2_1(
                    TestUtil.createRecognitionEvent_2_1(handle, status, 777),
                    99);
            verify(canonicalCallback).recognitionCallback(eventCaptor.capture());
            TestUtil.validateRecognitionEvent_2_1(eventCaptor.getValue(), handle, status, 777);
        }

        {
            final int handle = 92;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS;
            ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent>
                    eventCaptor = ArgumentCaptor.forClass(
                    android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent.class);

            hwCallback.phraseRecognitionCallback_2_1(
                    TestUtil.createPhraseRecognitionEvent_2_1(handle, status, 888), 99);
            verify(canonicalCallback).phraseRecognitionCallback(eventCaptor.capture());
            TestUtil.validatePhraseRecognitionEvent_2_1(eventCaptor.getValue(), handle, status,
                    888);
        }
    }

    private void validateCallback_2_4(
            android.hardware.soundtrigger.V2_4.ISoundTriggerHwCallback hwCallback,
            ISoundTriggerHw2.ModelCallback canonicalCallback) throws Exception {
        {
            final int handle = 85;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.ABORT;
            ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent>
                    eventCaptor = ArgumentCaptor.forClass(
                    android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent.class);

            hwCallback.recognitionCallback_2_1(
                    TestUtil.createRecognitionEvent_2_1(handle, status, 444),
                    99);
            verify(canonicalCallback).recognitionCallback(eventCaptor.capture());
            TestUtil.validateRecognitionEvent_2_1(eventCaptor.getValue(), handle, status, 444);
        }

        {
            final int handle = 92;
            final int status =
                    android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionStatus.SUCCESS;
            ArgumentCaptor<android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent>
                    eventCaptor = ArgumentCaptor.forClass(
                    android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent.class);

            hwCallback.phraseRecognitionCallback_2_1(
                    TestUtil.createPhraseRecognitionEvent_2_1(handle, status, 555), 99);
            verify(canonicalCallback).phraseRecognitionCallback(eventCaptor.capture());
            TestUtil.validatePhraseRecognitionEvent_2_1(eventCaptor.getValue(), handle, status,
                    555);
        }

        {
            final int handle = 23;
            hwCallback.modelUnloaded(handle);
            verify(canonicalCallback).modelUnloaded(handle);
        }
    }
}
