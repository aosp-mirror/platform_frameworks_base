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

package com.android.server.broadcastradio.aidl;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.radio.RadioManager;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Tests for AIDL HAL RadioModule.
 */
@RunWith(MockitoJUnitRunner.class)
public final class RadioModuleTest {

    // Mocks
    @Mock
    private IBroadcastRadio mBroadcastRadioMock;

    private final Object mLock = new Object();
    // RadioModule under test
    private RadioModule mRadioModule;

    @Before
    public void setup() throws RemoteException {
        mRadioModule = new RadioModule(mBroadcastRadioMock, new RadioManager.ModuleProperties(
                /* id= */ 0, /* serviceName= */ "", /* classId= */ 0, /* implementor= */ "",
                /* product= */ "", /* version= */ "", /* serial= */ "", /* numTuners= */ 0,
                /* numAudioSources= */ 0, /* isInitializationRequired= */ false,
                /* isCaptureSupported= */ false, /* bands= */ null, /* isBgScanSupported= */ false,
                /* supportedProgramTypes= */ new int[]{},
                /* supportedIdentifierTypes */ new int[]{},
                /* dabFrequencyTable= */ null, /* vendorInfo= */ null), mLock);

        // TODO(b/241118988): test non-null image for getImage method
        when(mBroadcastRadioMock.getImage(anyInt())).thenReturn(null);
    }

    @Test
    public void getService() {
        assertWithMessage("Service of radio module")
                .that(mRadioModule.getService()).isEqualTo(mBroadcastRadioMock);
    }

    @Test
    public void setInternalHalCallback_callbackSetInHal() throws RemoteException {
        mRadioModule.setInternalHalCallback();

        verify(mBroadcastRadioMock).setTunerCallback(any());
    }

    @Test
    public void getImage_withValidIdFromRadioModule() {
        int imageId = 1;

        Bitmap imageTest = mRadioModule.getImage(imageId);

        assertWithMessage("Image got from radio module").that(imageTest).isNull();
    }

    @Test
    public void getImage_withInvalidIdFromRadioModule_throwsIllegalArgumentException() {
        int invalidImageId = IBroadcastRadio.INVALID_IMAGE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mRadioModule.getImage(invalidImageId);
        });

        assertWithMessage("Exception for getting image with invalid ID")
                .that(thrown).hasMessageThat().contains("Image ID is missing");
    }
}
