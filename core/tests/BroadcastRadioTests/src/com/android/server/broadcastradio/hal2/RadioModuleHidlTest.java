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

package com.android.server.broadcastradio.hal2;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.hardware.broadcastradio.V2_0.Constants;
import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.RadioManager;
import android.os.RemoteException;

import com.android.server.broadcastradio.RadioServiceUserController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Tests for HIDL HAL RadioModule.
 */
@RunWith(MockitoJUnitRunner.class)
public final class RadioModuleHidlTest {

    private static final int TEST_ENABLED_TYPE = Announcement.TYPE_EVENT;
    private static final RadioManager.ModuleProperties TEST_MODULE_PROPERTIES =
            TestUtils.makeDefaultModuleProperties();

    @Mock
    private IBroadcastRadio mBroadcastRadioMock;
    @Mock
    private IAnnouncementListener mListenerMock;
    @Mock
    private android.hardware.broadcastradio.V2_0.ICloseHandle mHalCloseHandleMock;
    @Mock
    private RadioServiceUserController mUserControllerMock;

    private RadioModule mRadioModule;
    private android.hardware.broadcastradio.V2_0.IAnnouncementListener mHalListener;

    @Before
    public void setup() throws RemoteException {
        mRadioModule = new RadioModule(mBroadcastRadioMock, TEST_MODULE_PROPERTIES,
                mUserControllerMock);

        when(mBroadcastRadioMock.getImage(anyInt())).thenReturn(new ArrayList<Byte>(0));

        doAnswer(invocation -> {
            mHalListener = (android.hardware.broadcastradio.V2_0.IAnnouncementListener) invocation
                    .getArguments()[1];
            IBroadcastRadio.registerAnnouncementListenerCallback cb =
                    (IBroadcastRadio.registerAnnouncementListenerCallback)
                            invocation.getArguments()[2];
            cb.onValues(Result.OK, mHalCloseHandleMock);
            return null;
        }).when(mBroadcastRadioMock).registerAnnouncementListener(any(), any(), any());
    }

    @Test
    public void getService() {
        assertWithMessage("Service of radio module")
                .that(mRadioModule.getService()).isEqualTo(mBroadcastRadioMock);
    }

    @Test
    public void getProperties() {
        assertWithMessage("Module properties of radio module")
                .that(mRadioModule.getProperties()).isEqualTo(TEST_MODULE_PROPERTIES);
    }

    @Test
    public void getImage_withValidIdFromRadioModule() {
        int imageId = 1;

        Bitmap imageTest = mRadioModule.getImage(imageId);

        assertWithMessage("Image from radio module").that(imageTest).isNull();
    }

    @Test
    public void getImage_withInvalidIdFromRadioModule_throwsIllegalArgumentException() {
        int invalidImageId = Constants.INVALID_IMAGE;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mRadioModule.getImage(invalidImageId);
        });

        assertWithMessage("Exception for getting image with invalid ID")
                .that(thrown).hasMessageThat().contains("Image ID is missing");
    }

    @Test
    public void addAnnouncementListener_listenerRegistered() throws Exception {
        ArrayList<Byte> enabledListExpected = new ArrayList<Byte>(Arrays.asList(
                (byte) TEST_ENABLED_TYPE));
        mRadioModule.addAnnouncementListener(new int[]{TEST_ENABLED_TYPE}, mListenerMock);

        verify(mBroadcastRadioMock)
                .registerAnnouncementListener(eq(enabledListExpected), any(), any());
    }

    @Test
    public void onListUpdate_forAnnouncementListener() throws Exception {
        android.hardware.broadcastradio.V2_0.Announcement halAnnouncement =
                TestUtils.makeAnnouncement(TEST_ENABLED_TYPE, /* selectorFreq= */ 96300);
        mRadioModule.addAnnouncementListener(new int[]{TEST_ENABLED_TYPE}, mListenerMock);

        mHalListener.onListUpdated(
                new ArrayList<android.hardware.broadcastradio.V2_0.Announcement>(
                        Arrays.asList(halAnnouncement)));

        verify(mListenerMock).onListUpdated(any());
    }

    @Test
    public void close_forCloseHandle() throws Exception {
        ICloseHandle closeHandle =
                mRadioModule.addAnnouncementListener(new int[]{TEST_ENABLED_TYPE}, mListenerMock);

        closeHandle.close();

        verify(mHalCloseHandleMock).close();
    }
}
