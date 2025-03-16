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

package com.android.server.location.contexthub;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.location.ContextHubInfo;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@Presubmit
public class ContextHubServiceTest {
    private static final int CONTEXT_HUB_ID = 3;
    private static final String CONTEXT_HUB_STRING = "Context Hub Info Test";

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Mock private IContextHubWrapper mMockContextHubWrapper;
    @Mock private ContextHubInfo mMockContextHubInfo;
    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setUp() throws RemoteException {
        Pair<List<ContextHubInfo>, List<String>> hubInfo =
                new Pair<>(Arrays.asList(mMockContextHubInfo), Arrays.asList(""));
        when(mMockContextHubInfo.getId()).thenReturn(CONTEXT_HUB_ID);
        when(mMockContextHubInfo.toString()).thenReturn(CONTEXT_HUB_STRING);
        when(mMockContextHubWrapper.getContextHubs()).thenReturn(hubInfo);

        when(mMockContextHubWrapper.supportsLocationSettingNotifications()).thenReturn(true);
        when(mMockContextHubWrapper.supportsWifiSettingNotifications()).thenReturn(true);
        when(mMockContextHubWrapper.supportsAirplaneModeSettingNotifications()).thenReturn(true);
        when(mMockContextHubWrapper.supportsMicrophoneSettingNotifications()).thenReturn(true);
        when(mMockContextHubWrapper.supportsBtSettingNotifications()).thenReturn(true);
    }

    @Test
    public void testDump_emptyPreloadedNanoappList() {
        when(mMockContextHubWrapper.getPreloadedNanoappIds(anyInt())).thenReturn(null);
        StringWriter stringWriter = new StringWriter();

        ContextHubService service = new ContextHubService(mContext, mMockContextHubWrapper);
        service.dump(
                new FileDescriptor(), new PrintWriter(stringWriter), /* args= */ new String[0]);

        assertThat(stringWriter.toString()).isNotEmpty();
    }

    // TODO (b/254290317): These existing tests are to setup the testing infra for the ContextHub
    //                     service and verify the constructor correctly registers a context hub.
    //                     We need to augment these tests to cover the full behavior of the
    //                     ContextHub service

    @Test
    public void testConstructorRegistersContextHub() throws RemoteException {
        ContextHubService service = new ContextHubService(mContext, mMockContextHubWrapper);
        assertThat(service.getContextHubInfo(CONTEXT_HUB_ID)).isEqualTo(mMockContextHubInfo);
    }

    @Test
    public void testConstructorRegistersNotifications() {
        new ContextHubService(mContext, mMockContextHubWrapper);
        verify(mMockContextHubWrapper).onAirplaneModeSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper).onWifiSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper).onWifiScanningSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper).onWifiMainSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper).onAirplaneModeSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper).onMicrophoneSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper).onBtScanningSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper).onBtMainSettingChanged(anyBoolean());
    }

    @Test
    public void testConstructorRegistersNotificationsAndHandlesSettings() {
        when(mMockContextHubWrapper.supportsLocationSettingNotifications())
                .thenReturn(false);
        when(mMockContextHubWrapper.supportsWifiSettingNotifications()).thenReturn(false);
        when(mMockContextHubWrapper.supportsAirplaneModeSettingNotifications())
                .thenReturn(false);
        when(mMockContextHubWrapper.supportsMicrophoneSettingNotifications())
                .thenReturn(false);
        when(mMockContextHubWrapper.supportsBtSettingNotifications()).thenReturn(false);

        new ContextHubService(mContext, mMockContextHubWrapper);
        verify(mMockContextHubWrapper, never()).onAirplaneModeSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper, never()).onWifiSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper, never()).onWifiScanningSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper, never()).onWifiMainSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper, never()).onAirplaneModeSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper, never()).onMicrophoneSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper, never()).onBtScanningSettingChanged(anyBoolean());
        verify(mMockContextHubWrapper, never()).onBtMainSettingChanged(anyBoolean());
    }
}
