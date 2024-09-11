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

package com.android.server.broadcastradio;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.os.IBinder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;

/**
 * Tests for {@link android.hardware.radio.IRadioService} with HIDL HAL implementation
 */
@RunWith(MockitoJUnitRunner.class)
public final class IRadioServiceHidlImplTest {

    private static final int HAL1_MODULE_ID = 0;
    private static final int[] ENABLE_TYPES = new int[]{Announcement.TYPE_TRAFFIC};

    private IRadioServiceHidlImpl mHidlImpl;

    @Mock
    private BroadcastRadioService mServiceMock;
    @Mock
    private com.android.server.broadcastradio.hal1.BroadcastRadioService mHal1Mock;
    @Mock
    private com.android.server.broadcastradio.hal2.BroadcastRadioService mHal2Mock;
    @Mock
    private RadioManager.ModuleProperties mHal1ModuleMock;
    @Mock
    private RadioManager.ModuleProperties mHal2ModuleMock;
    @Mock
    private RadioManager.BandConfig mBandConfigMock;
    @Mock
    private ITunerCallback mTunerCallbackMock;
    @Mock
    private IAnnouncementListener mListenerMock;
    @Mock
    private ICloseHandle mICloseHandle;
    @Mock
    private ITuner mHal1TunerMock;
    @Mock
    private ITuner mHal2TunerMock;

    @Before
    public void setup() throws Exception {
        doNothing().when(mServiceMock).enforcePolicyAccess();
        when(mHal1Mock.loadModules()).thenReturn(Arrays.asList(mHal1ModuleMock));
        when(mHal1Mock.openTuner(anyInt(), any(), anyBoolean(), any())).thenReturn(mHal1TunerMock);

        when(mHal2Mock.listModules()).thenReturn(Arrays.asList(mHal2ModuleMock));
        doAnswer(invocation -> {
            int moduleId = (int) invocation.getArguments()[0];
            return moduleId != HAL1_MODULE_ID;
        }).when(mHal2Mock).hasModule(anyInt());
        when(mHal2Mock.openSession(anyInt(), any(), anyBoolean(), any()))
                .thenReturn(mHal2TunerMock);
        when(mHal2Mock.addAnnouncementListener(any(), any())).thenReturn(mICloseHandle);

        mHidlImpl = new IRadioServiceHidlImpl(mServiceMock, mHal1Mock, mHal2Mock);
    }

    @Test
    public void loadModules_forHidlImpl() {
        assertWithMessage("Modules loaded in HIDL HAL")
                .that(mHidlImpl.listModules())
                .containsExactly(mHal1ModuleMock, mHal2ModuleMock);
    }

    @Test
    public void openTuner_withHal1ModuleId_forHidlImpl() throws Exception {
        ITuner tuner = mHidlImpl.openTuner(HAL1_MODULE_ID, mBandConfigMock,
                /* withAudio= */ true, mTunerCallbackMock);

        assertWithMessage("Tuner opened in HAL 1")
                .that(tuner).isEqualTo(mHal1TunerMock);
    }

    @Test
    public void openTuner_withHal2ModuleId_forHidlImpl() throws Exception {
        ITuner tuner = mHidlImpl.openTuner(HAL1_MODULE_ID + 1, mBandConfigMock,
                /* withAudio= */ true, mTunerCallbackMock);

        assertWithMessage("Tuner opened in HAL 2")
                .that(tuner).isEqualTo(mHal2TunerMock);
    }

    @Test
    public void openTuner_withNullCallbackForHidlImpl_fails() throws Exception {
        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mHidlImpl.openTuner(/* moduleId= */ 0, mBandConfigMock,
                        /* withAudio= */ true, /* callback= */ null));

        assertWithMessage("Exception for opening tuner with null callback")
                .that(thrown).hasMessageThat().contains("Callback must not be null");
    }

    @Test
    public void addAnnouncementListener_forHidlImpl() {
        when(mHal2Mock.hasAnyModules()).thenReturn(true);

        ICloseHandle closeHandle = mHidlImpl.addAnnouncementListener(ENABLE_TYPES, mListenerMock);

        verify(mHal2Mock).addAnnouncementListener(ENABLE_TYPES, mListenerMock);
        assertWithMessage("Close handle of announcement listener for HAL 2")
                .that(closeHandle).isEqualTo(mICloseHandle);
    }

    @Test
    public void addAnnouncementListener_withoutAnyModules() throws Exception {
        when(mHal2Mock.hasAnyModules()).thenReturn(false);
        IBinder binderMock = mock(IBinder.class);
        when(mListenerMock.asBinder()).thenReturn(binderMock);

        mHidlImpl.addAnnouncementListener(ENABLE_TYPES, mListenerMock);

        verify(mHal2Mock, never()).addAnnouncementListener(ENABLE_TYPES, mListenerMock);
        verify(binderMock).linkToDeath(any(), anyInt());
    }
}
