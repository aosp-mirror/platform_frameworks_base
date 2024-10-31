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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.RadioManager;
import android.os.ParcelableException;
import android.os.RemoteException;

import com.android.server.broadcastradio.RadioServiceUserController;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Tests for AIDL HAL RadioModule.
 */
@RunWith(MockitoJUnitRunner.class)
public final class RadioModuleTest {

    private static final int TEST_ENABLED_TYPE = Announcement.TYPE_EVENT;
    private static final RadioManager.ModuleProperties TEST_MODULE_PROPERTIES =
            AidlTestUtils.makeDefaultModuleProperties();

    @Rule
    public final Expect mExpect = Expect.create();

    // Mocks
    @Mock
    private IBroadcastRadio mBroadcastRadioMock;
    @Mock
    private IAnnouncementListener mListenerMock;
    @Mock
    private android.hardware.broadcastradio.ICloseHandle mHalCloseHandleMock;
    @Mock
    private RadioServiceUserController mUserControllerMock;

    // RadioModule under test
    private RadioModule mRadioModule;
    private android.hardware.broadcastradio.IAnnouncementListener mHalListener;

    @Before
    public void setup() throws RemoteException {
        mRadioModule = new RadioModule(mBroadcastRadioMock, TEST_MODULE_PROPERTIES,
                mUserControllerMock);

        // TODO(b/241118988): test non-null image for getImage method
        when(mBroadcastRadioMock.getImage(anyInt())).thenReturn(null);
        doAnswer(invocation -> {
            mHalListener = (android.hardware.broadcastradio.IAnnouncementListener) invocation
                    .getArguments()[0];
            return null;
        }).when(mBroadcastRadioMock).registerAnnouncementListener(any(), any());
    }

    @Test
    public void getService() {
        mExpect.withMessage("Service of radio module")
                .that(mRadioModule.getService()).isEqualTo(mBroadcastRadioMock);
    }

    @Test
    public void getProperties() {
        mExpect.withMessage("Module properties of radio module")
                .that(mRadioModule.getProperties()).isEqualTo(TEST_MODULE_PROPERTIES);
    }

    @Test
    public void getImage_withValidIdFromRadioModule() {
        int imageId = 1;

        Bitmap imageTest = mRadioModule.getImage(imageId);

        mExpect.withMessage("Image from radio module").that(imageTest).isNull();
    }

    @Test
    public void getImage_withInvalidIdFromRadioModule_throwsIllegalArgumentException() {
        int invalidImageId = IBroadcastRadio.INVALID_IMAGE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mRadioModule.getImage(invalidImageId);
        });

        mExpect.withMessage("Exception for getting image with invalid ID")
                .that(thrown).hasMessageThat().contains("Image ID is missing");
    }

    @Test
    public void addAnnouncementListener_listenerRegistered() throws Exception {
        mRadioModule.addAnnouncementListener(mListenerMock, new int[]{TEST_ENABLED_TYPE});

        verify(mBroadcastRadioMock)
                .registerAnnouncementListener(any(), eq(new byte[]{TEST_ENABLED_TYPE}));
    }

    @Test
    public void addAnnouncementListener_whenHalThrowsRemoteException() throws Exception {
        doThrow(new RuntimeException("HAL service died")).when(mBroadcastRadioMock)
                .registerAnnouncementListener(any(), any());

        ParcelableException thrown = assertThrows(ParcelableException.class, () ->
                mRadioModule.addAnnouncementListener(mListenerMock, new int[]{TEST_ENABLED_TYPE}));

        mExpect.withMessage("Exception for adding announcement listener when HAL service died")
                .that(thrown).hasMessageThat().contains("unknown error");
    }

    @Test
    public void onListUpdate_forAnnouncementListener() throws Exception {
        android.hardware.broadcastradio.Announcement halAnnouncement =
                AidlTestUtils.makeAnnouncement(TEST_ENABLED_TYPE, /* selectorFreq= */ 96300);
        mRadioModule.addAnnouncementListener(mListenerMock, new int[]{TEST_ENABLED_TYPE});

        mHalListener.onListUpdated(
                new android.hardware.broadcastradio.Announcement[]{halAnnouncement});

        verify(mListenerMock).onListUpdated(any());
    }

    @Test
    public void close_forCloseHandle() throws Exception {
        when(mBroadcastRadioMock.registerAnnouncementListener(any(), any()))
                .thenReturn(mHalCloseHandleMock);
        ICloseHandle closeHandle =
                mRadioModule.addAnnouncementListener(mListenerMock, new int[]{TEST_ENABLED_TYPE});

        closeHandle.close();

        verify(mHalCloseHandleMock).close();
    }
}
