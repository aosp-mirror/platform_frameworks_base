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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ServiceManager;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.broadcastradio.aidl.BroadcastRadioServiceImpl;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.List;

/**
 * Tests for {@link android.hardware.radio.IRadioService} with AIDL HAL implementation
 */
public final class IRadioServiceAidlImplTest extends ExtendedRadioMockitoTestCase {

    private static final int[] ENABLE_TYPES = new int[]{Announcement.TYPE_TRAFFIC};
    private static final String AM_FM_SERVICE_NAME =
            "android.hardware.broadcastradio.IBroadcastRadio/amfm";
    private static final String DAB_SERVICE_NAME =
            "android.hardware.broadcastradio.IBroadcastRadio/dab";
    private static final int TARGET_SDK_VERSION = Build.VERSION_CODES.CUR_DEVELOPMENT;

    private IRadioServiceAidlImpl mAidlImpl;

    @Mock
    private BroadcastRadioService mServiceMock;
    @Mock
    private IBinder mServiceBinderMock;
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

        when(mHalMock.listModules()).thenReturn(List.of(mModuleMock));
        when(mHalMock.openSession(anyInt(), any(), anyBoolean(), any(), eq(TARGET_SDK_VERSION)))
                .thenReturn(mTunerMock);
        when(mHalMock.addAnnouncementListener(any(), any())).thenReturn(mICloseHandle);

        mAidlImpl = new IRadioServiceAidlImpl(mServiceMock, mHalMock);
    }

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(ServiceManager.class);
    }

    @Test
    public void getServicesNames_forAidlImpl() {
        doReturn(null).when(() -> ServiceManager.waitForDeclaredService(
                AM_FM_SERVICE_NAME));
        doReturn(mServiceBinderMock).when(() -> ServiceManager.waitForDeclaredService(
                DAB_SERVICE_NAME));

        assertWithMessage("Names of services available")
                .that(IRadioServiceAidlImpl.getServicesNames()).containsExactly(DAB_SERVICE_NAME);
    }

    @Test
    public void loadModules_forAidlImpl() {
        assertWithMessage("Modules loaded in AIDL HAL")
                .that(mAidlImpl.listModules()).containsExactly(mModuleMock);
    }

    @Test
    public void openTuner_forAidlImpl() throws Exception {
        ITuner tuner = mAidlImpl.openTuner(/* moduleId= */ 0, mBandConfigMock,
                /* withAudio= */ true, mTunerCallbackMock, TARGET_SDK_VERSION);

        assertWithMessage("Tuner opened in AIDL HAL")
                .that(tuner).isEqualTo(mTunerMock);
    }

    @Test
    public void openTuner_withNullCallbackForAidlImpl_fails() throws Exception {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mAidlImpl.openTuner(/* moduleId= */ 0, mBandConfigMock,
                        /* withAudio= */ true, /* callback= */ null, TARGET_SDK_VERSION));

        assertWithMessage("Exception for opening tuner with null callback")
                .that(thrown).hasMessageThat().contains("Callback must not be null");
    }

    @Test
    public void addAnnouncementListener_forAidlImpl() {
        ICloseHandle closeHandle = mAidlImpl.addAnnouncementListener(ENABLE_TYPES, mListenerMock);

        verify(mHalMock).addAnnouncementListener(ENABLE_TYPES, mListenerMock);
        assertWithMessage("Close handle of announcement listener for HAL 2")
                .that(closeHandle).isEqualTo(mICloseHandle);
    }
}
