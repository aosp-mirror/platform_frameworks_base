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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;

import com.android.server.broadcastradio.aidl.BroadcastRadioServiceImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;

/**
 * Tests for {@link android.hardware.radio.IRadioService} with AIDL HAL implementation
 */
@RunWith(MockitoJUnitRunner.class)
public final class IRadioServiceAidlImplTest {

    private static final int[] ENABLE_TYPES = new int[]{Announcement.TYPE_TRAFFIC};

    private IRadioServiceAidlImpl mAidlImpl;

    @Mock
    private BroadcastRadioService mServiceMock;
    @Mock
    private BroadcastRadioServiceImpl mHalMock;
    @Mock
    private RadioManager.ModuleProperties mModuleMock;
    @Mock
    private RadioManager.BandConfig mBandConfigMock;
    @Mock
    private ITunerCallback mTunerCallbackMock;
    @Mock
    private IAnnouncementListener mListenerMock;
    @Mock
    private ICloseHandle mICloseHandle;
    @Mock
    private ITuner mTunerMock;

    @Before
    public void setUp() throws Exception {
        doNothing().when(mServiceMock).enforcePolicyAccess();

        when(mHalMock.listModules()).thenReturn(Arrays.asList(mModuleMock));
        when(mHalMock.openSession(anyInt(), any(), anyBoolean(), any()))
                .thenReturn(mTunerMock);
        when(mHalMock.addAnnouncementListener(any(), any())).thenReturn(mICloseHandle);

        mAidlImpl = new IRadioServiceAidlImpl(mServiceMock, mHalMock);
    }

    @Test
    public void loadModules_forAidlImpl() {
        assertWithMessage("Modules loaded in AIDL HAL")
                .that(mAidlImpl.listModules())
                .containsExactly(mModuleMock);
    }

    @Test
    public void openTuner_forAidlImpl() throws Exception {
        ITuner tuner = mAidlImpl.openTuner(/* moduleId= */ 0, mBandConfigMock,
                /* withAudio= */ true, mTunerCallbackMock);

        assertWithMessage("Tuner opened in AIDL HAL")
                .that(tuner).isEqualTo(mTunerMock);
    }

    @Test
    public void addAnnouncementListener_forAidlImpl() {
        ICloseHandle closeHandle = mAidlImpl.addAnnouncementListener(ENABLE_TYPES, mListenerMock);

        verify(mHalMock).addAnnouncementListener(ENABLE_TYPES, mListenerMock);
        assertWithMessage("Close handle of announcement listener for HAL 2")
                .that(closeHandle).isEqualTo(mICloseHandle);
    }

}
