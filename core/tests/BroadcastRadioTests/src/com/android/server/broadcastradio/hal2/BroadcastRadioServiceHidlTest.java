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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.broadcastradio.V2_0.IBroadcastRadio;
import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.IBinder;
import android.os.IHwBinder.DeathRecipient;
import android.os.RemoteException;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.broadcastradio.ExtendedRadioMockitoTestCase;
import com.android.server.broadcastradio.RadioServiceUserController;

import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;

public final class BroadcastRadioServiceHidlTest extends ExtendedRadioMockitoTestCase {

    private static final int FM_RADIO_MODULE_ID = 0;
    private static final int DAB_RADIO_MODULE_ID = 1;
    private static final ArrayList<String> SERVICE_LIST =
            new ArrayList<>(Arrays.asList("FmService", "DabService"));
    private static final int[] TEST_ENABLED_TYPES = new int[]{Announcement.TYPE_TRAFFIC};

    private BroadcastRadioService mBroadcastRadioService;
    private DeathRecipient mFmDeathRecipient;

    @Mock
    private IServiceManager mServiceManagerMock;
    @Mock
    private RadioManager.ModuleProperties mFmModuleMock;
    @Mock
    private RadioManager.ModuleProperties mDabModuleMock;
    @Mock
    private RadioModule mFmRadioModuleMock;
    @Mock
    private RadioModule mDabRadioModuleMock;
    @Mock
    private IBroadcastRadio mFmHalServiceMock;
    @Mock
    private IBroadcastRadio mDabHalServiceMock;
    @Mock
    private TunerSession mFmTunerSessionMock;
    @Mock
    private ITunerCallback mTunerCallbackMock;
    @Mock
    private ICloseHandle mFmCloseHandleMock;
    @Mock
    private ICloseHandle mDabCloseHandleMock;
    @Mock
    private IAnnouncementListener mAnnouncementListenerMock;
    @Mock
    private IBinder mBinderMock;
    @Mock
    private RadioServiceUserController mUserControllerMock;

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(RadioModule.class);
    }

    @Test
    public void listModules_withMultipleServiceNames() throws Exception {
        createBroadcastRadioService();

        assertWithMessage("Radio modules in HIDL broadcast radio HAL client")
                .that(mBroadcastRadioService.listModules())
                .containsExactly(mFmModuleMock, mDabModuleMock);
    }

    @Test
    public void hasModules_withIdFoundInModules() throws Exception {
        createBroadcastRadioService();

        assertWithMessage("DAB radio module in HIDL broadcast radio HAL client")
                .that(mBroadcastRadioService.hasModule(FM_RADIO_MODULE_ID)).isTrue();
    }

    @Test
    public void hasModules_withIdNotFoundInModules() throws Exception {
        createBroadcastRadioService();

        assertWithMessage("Radio module of id not found in HIDL broadcast radio HAL client")
                .that(mBroadcastRadioService.hasModule(DAB_RADIO_MODULE_ID + 1)).isFalse();
    }

    @Test
    public void hasAnyModules_withModulesExist() throws Exception {
        createBroadcastRadioService();

        assertWithMessage("Any radio module in HIDL broadcast radio HAL client")
                .that(mBroadcastRadioService.hasAnyModules()).isTrue();
    }

    @Test
    public void openSession_withIdFound() throws Exception {
        createBroadcastRadioService();

        ITuner session = mBroadcastRadioService.openSession(FM_RADIO_MODULE_ID,
                /* legacyConfig= */ null, /* withAudio= */ true, mTunerCallbackMock);

        assertWithMessage("Session opened in FM radio module")
                .that(session).isEqualTo(mFmTunerSessionMock);
    }

    @Test
    public void openSession_withIdNotFound() throws Exception {
        createBroadcastRadioService();
        int moduleIdInvalid = DAB_RADIO_MODULE_ID + 1;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            mBroadcastRadioService.openSession(moduleIdInvalid, /* legacyConfig= */ null,
                    /* withAudio= */ true, mTunerCallbackMock);
        });

        assertWithMessage("Exception for opening session with module id %s", moduleIdInvalid)
                .that(thrown).hasMessageThat().contains("Invalid module ID");
    }

    @Test
    public void openSession_forNonCurrentUser_throwsException() throws Exception {
        createBroadcastRadioService();
        when(mUserControllerMock.isCurrentOrSystemUser()).thenReturn(false);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mBroadcastRadioService.openSession(FM_RADIO_MODULE_ID,
                        /* legacyConfig= */ null, /* withAudio= */ true, mTunerCallbackMock));

        assertWithMessage("Exception for opening session by non-current user")
                .that(thrown).hasMessageThat().contains("Cannot open session for non-current user");
    }

    @Test
    public void openSession_withoutAudio_fails() throws Exception {
        createBroadcastRadioService();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> mBroadcastRadioService.openSession(FM_RADIO_MODULE_ID,
                        /* legacyConfig= */ null, /* withAudio= */ false, mTunerCallbackMock));

        assertWithMessage("Exception for opening session without audio")
                .that(thrown).hasMessageThat().contains("not supported");
    }

    @Test
    public void addAnnouncementListener_addsOnAllRadioModules() throws Exception {
        createBroadcastRadioService();
        when(mAnnouncementListenerMock.asBinder()).thenReturn(mBinderMock);
        when(mFmRadioModuleMock.addAnnouncementListener(any(), any()))
                .thenReturn(mFmCloseHandleMock);
        when(mDabRadioModuleMock.addAnnouncementListener(any(), any()))
                .thenReturn(mDabCloseHandleMock);

        mBroadcastRadioService.addAnnouncementListener(TEST_ENABLED_TYPES,
                mAnnouncementListenerMock);

        verify(mFmRadioModuleMock).addAnnouncementListener(any(), any());
        verify(mDabRadioModuleMock).addAnnouncementListener(any(), any());
    }

    @Test
    public void binderDied_forDeathRecipient() throws Exception {
        createBroadcastRadioService();

        mFmDeathRecipient.serviceDied(FM_RADIO_MODULE_ID);

        verify(mFmRadioModuleMock).closeSessions(eq(RadioTuner.ERROR_HARDWARE_FAILURE));
        assertWithMessage("FM radio module after FM broadcast radio HAL service died")
                .that(mBroadcastRadioService.hasModule(FM_RADIO_MODULE_ID)).isFalse();
    }

    private void createBroadcastRadioService() throws RemoteException {
        when(mUserControllerMock.isCurrentOrSystemUser()).thenReturn(true);

        mockServiceManager();
        mBroadcastRadioService = new BroadcastRadioService(/* nextModuleId= */ FM_RADIO_MODULE_ID,
                mServiceManagerMock, mUserControllerMock);
    }

    private void mockServiceManager() throws RemoteException {
        doAnswer(invocation -> {
            mFmDeathRecipient = (DeathRecipient) invocation.getArguments()[0];
            return null;
        }).when(mFmHalServiceMock).linkToDeath(any(), eq((long) FM_RADIO_MODULE_ID));

        when(mServiceManagerMock.registerForNotifications(anyString(), anyString(),
                any(IServiceNotification.class))).thenAnswer(invocation -> {
                    IServiceNotification serviceCallback =
                            (IServiceNotification) invocation.getArguments()[2];
                    for (int index = 0; index < SERVICE_LIST.size(); index++) {
                        serviceCallback.onRegistration(IBroadcastRadio.kInterfaceName,
                                SERVICE_LIST.get(index), /* b= */ false);
                    }
                    return true;
                }).thenReturn(true);

        doReturn(mFmRadioModuleMock).when(() -> RadioModule.tryLoadingModule(
                eq(FM_RADIO_MODULE_ID), anyString(), any()));
        doReturn(mDabRadioModuleMock).when(() -> RadioModule.tryLoadingModule(
                eq(DAB_RADIO_MODULE_ID), anyString(), any()));

        when(mFmRadioModuleMock.getProperties()).thenReturn(mFmModuleMock);
        when(mDabRadioModuleMock.getProperties()).thenReturn(mDabModuleMock);

        when(mFmRadioModuleMock.getService()).thenReturn(mFmHalServiceMock);
        when(mDabRadioModuleMock.getService()).thenReturn(mDabHalServiceMock);

        when(mFmRadioModuleMock.openSession(mTunerCallbackMock))
                .thenReturn(mFmTunerSessionMock);
    }
}
