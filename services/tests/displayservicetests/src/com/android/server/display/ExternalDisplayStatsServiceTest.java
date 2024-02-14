/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.display;

import static android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
import static android.media.AudioDeviceInfo.TYPE_HDMI;
import static android.view.Display.TYPE_EXTERNAL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.media.AudioDeviceInfo;
import android.media.AudioManager.AudioPlaybackCallback;
import android.media.AudioPlaybackConfiguration;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.testutils.TestHandler;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;


/**
 * Tests for {@link ExternalDisplayStatsService}
 * Run: atest ExternalDisplayStatsServiceTest
 */
@SmallTest
@RunWith(TestParameterInjector.class)
public class ExternalDisplayStatsServiceTest {
    private static final int EXTERNAL_DISPLAY_ID = 2;

    private TestHandler mHandler;
    private ExternalDisplayStatsService mExternalDisplayStatsService;
    private List<AudioPlaybackConfiguration> mAudioPlaybackConfigsPhoneActive;
    private List<AudioPlaybackConfiguration> mAudioPlaybackConfigsHdmiActive;
    @Nullable
    private AudioPlaybackCallback mAudioPlaybackCallback;
    @Nullable
    private BroadcastReceiver mInteractivityReceiver;

    @Mock
    private ExternalDisplayStatsService.Injector mMockedInjector;
    @Mock
    private LogicalDisplay mMockedLogicalDisplay;
    @Mock
    private DisplayInfo mMockedDisplayInfo;

    /** Setup tests. */
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mHandler = new TestHandler(/*callback=*/ null);
        when(mMockedInjector.getHandler()).thenReturn(mHandler);
        when(mMockedInjector.isExtendedDisplayEnabled()).thenReturn(false);
        when(mMockedLogicalDisplay.getDisplayInfoLocked()).thenReturn(mMockedDisplayInfo);
        when(mMockedLogicalDisplay.getDisplayIdLocked()).thenReturn(EXTERNAL_DISPLAY_ID);
        when(mMockedInjector.isInteractive(eq(EXTERNAL_DISPLAY_ID))).thenReturn(true);
        mMockedDisplayInfo.type = TYPE_EXTERNAL;
        doAnswer(invocation -> {
            mAudioPlaybackCallback = invocation.getArgument(0);
            return null; // void method, so return null
        }).when(mMockedInjector).registerAudioPlaybackCallback(any());
        doAnswer(invocation -> {
            mInteractivityReceiver = invocation.getArgument(0);
            return null; // void method, so return null
        }).when(mMockedInjector).registerInteractivityReceiver(any(), any());
        mAudioPlaybackConfigsPhoneActive = createAudioConfigs(/*isPhoneActive=*/ true);
        mAudioPlaybackConfigsHdmiActive = createAudioConfigs(/*isPhoneActive=*/ false);
        mExternalDisplayStatsService = new ExternalDisplayStatsService(mMockedInjector);
    }

    @Test
    public void testOnHotplugConnectionError() {
        mExternalDisplayStatsService.onHotplugConnectionError();
        verify(mMockedInjector).writeLog(
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__ERROR_HOTPLUG_CONNECTION,
                /*numberOfDisplays=*/ 0,
                /*isExternalDisplayUsedForAudio=*/ false);
    }

    @Test
    public void testOnDisplayPortLinkTrainingFailure() {
        mExternalDisplayStatsService.onDisplayPortLinkTrainingFailure();
        verify(mMockedInjector).writeLog(
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog
                        .EXTERNAL_DISPLAY_STATE_CHANGED__STATE__ERROR_DISPLAYPORT_LINK_FAILED,
                /*numberOfDisplays=*/ 0,
                /*isExternalDisplayUsedForAudio=*/ false);
    }

    @Test
    public void testOnCableNotCapableDisplayPort() {
        mExternalDisplayStatsService.onCableNotCapableDisplayPort();
        verify(mMockedInjector).writeLog(
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog
                        .EXTERNAL_DISPLAY_STATE_CHANGED__STATE__ERROR_CABLE_NOT_CAPABLE_DISPLAYPORT,
                /*numberOfDisplays=*/ 0,
                /*isExternalDisplayUsedForAudio=*/ false);
    }

    @Test
    public void testDisplayConnected() {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        verify(mMockedInjector).registerInteractivityReceiver(any(), any());
        verify(mMockedInjector).registerAudioPlaybackCallback(any());
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__CONNECTED,
                /*numberOfDisplays=*/ 1,
                /*isExternalDisplayUsedForAudio=*/ false);
    }

    @Test
    public void testDisplayInteractivityChanges(
            @TestParameter final boolean isExternalDisplayUsedForAudio) {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        assertThat(mInteractivityReceiver).isNotNull();

        initAudioPlayback(isExternalDisplayUsedForAudio);
        clearInvocations(mMockedInjector);

        // Default is 'interactive', so no log should be written.
        mInteractivityReceiver.onReceive(null, null);
        assertThat(mExternalDisplayStatsService.isInteractiveExternalDisplays()).isTrue();
        verify(mMockedInjector, never()).writeLog(anyInt(), anyInt(), anyInt(), anyBoolean());

        // Change to non-interactive should produce log
        when(mMockedInjector.isInteractive(eq(EXTERNAL_DISPLAY_ID))).thenReturn(false);
        mInteractivityReceiver.onReceive(null, null);
        assertThat(mExternalDisplayStatsService.isInteractiveExternalDisplays()).isFalse();
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__KEYGUARD,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
        clearInvocations(mMockedInjector);

        // Change back to interactive should produce log
        when(mMockedInjector.isInteractive(eq(EXTERNAL_DISPLAY_ID))).thenReturn(true);
        mInteractivityReceiver.onReceive(null, null);
        assertThat(mExternalDisplayStatsService.isInteractiveExternalDisplays()).isTrue();
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__CONNECTED,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
    }

    @Test
    public void testAudioPlaybackChanges() {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        assertThat(mAudioPlaybackCallback).isNotNull();

        mAudioPlaybackCallback.onPlaybackConfigChanged(mAudioPlaybackConfigsPhoneActive);
        mHandler.flush();
        assertThat(mExternalDisplayStatsService.isExternalDisplayUsedForAudio()).isFalse();

        mAudioPlaybackCallback.onPlaybackConfigChanged(mAudioPlaybackConfigsHdmiActive);
        mHandler.flush();
        assertThat(mExternalDisplayStatsService.isExternalDisplayUsedForAudio()).isTrue();
    }
    @Test
    public void testOnDisplayAddedMirroring(
            @TestParameter final boolean isExternalDisplayUsedForAudio) {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        initAudioPlayback(isExternalDisplayUsedForAudio);
        clearInvocations(mMockedInjector);

        mExternalDisplayStatsService.onDisplayAdded(EXTERNAL_DISPLAY_ID);
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__MIRRORING,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
    }

    @Test
    public void testOnDisplayAddedExtended(
            @TestParameter final boolean isExternalDisplayUsedForAudio) {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        initAudioPlayback(isExternalDisplayUsedForAudio);
        clearInvocations(mMockedInjector);

        when(mMockedInjector.isExtendedDisplayEnabled()).thenReturn(true);
        mExternalDisplayStatsService.onDisplayAdded(EXTERNAL_DISPLAY_ID);
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__EXTENDED,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
    }

    @Test
    public void testOnDisplayDisabled(
            @TestParameter final boolean isExternalDisplayUsedForAudio) {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        initAudioPlayback(isExternalDisplayUsedForAudio);
        mExternalDisplayStatsService.onDisplayAdded(EXTERNAL_DISPLAY_ID);
        clearInvocations(mMockedInjector);

        mExternalDisplayStatsService.onDisplayDisabled(EXTERNAL_DISPLAY_ID);
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__DISABLED,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
    }

    @Test
    public void testOnDisplayDisconnected(
            @TestParameter final boolean isExternalDisplayUsedForAudio) {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        initAudioPlayback(isExternalDisplayUsedForAudio);
        clearInvocations(mMockedInjector);

        mExternalDisplayStatsService.onDisplayDisconnected(EXTERNAL_DISPLAY_ID);
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__DISCONNECTED,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
        mHandler.flush();
        assertThat(mAudioPlaybackCallback).isNotNull();
        assertThat(mInteractivityReceiver).isNotNull();
        verify(mMockedInjector).unregisterAudioPlaybackCallback(eq(mAudioPlaybackCallback));
        verify(mMockedInjector).unregisterInteractivityReceiver(eq(mInteractivityReceiver));
    }

    @Test
    public void testOnPresentationWindowAddedWhileMirroring(
            @TestParameter final boolean isExternalDisplayUsedForAudio) {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        initAudioPlayback(isExternalDisplayUsedForAudio);
        mExternalDisplayStatsService.onDisplayAdded(EXTERNAL_DISPLAY_ID);
        clearInvocations(mMockedInjector);

        mExternalDisplayStatsService.onPresentationWindowAdded(EXTERNAL_DISPLAY_ID);
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog
                        .EXTERNAL_DISPLAY_STATE_CHANGED__STATE__PRESENTATION_WHILE_MIRRORING,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
    }

    @Test
    public void testOnPresentationWindowAddedWhileExtended(
            @TestParameter final boolean isExternalDisplayUsedForAudio) {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        when(mMockedInjector.isExtendedDisplayEnabled()).thenReturn(true);
        mHandler.flush();
        initAudioPlayback(isExternalDisplayUsedForAudio);
        clearInvocations(mMockedInjector);

        mExternalDisplayStatsService.onPresentationWindowAdded(EXTERNAL_DISPLAY_ID);
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog
                        .EXTERNAL_DISPLAY_STATE_CHANGED__STATE__PRESENTATION_WHILE_EXTENDED,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
    }

    @Test
    public void testOnPresentationWindowRemoved(
            @TestParameter final boolean isExternalDisplayUsedForAudio) {
        mExternalDisplayStatsService.onDisplayConnected(mMockedLogicalDisplay);
        mHandler.flush();
        initAudioPlayback(isExternalDisplayUsedForAudio);
        clearInvocations(mMockedInjector);

        mExternalDisplayStatsService.onPresentationWindowRemoved(EXTERNAL_DISPLAY_ID);
        verify(mMockedInjector).writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                FrameworkStatsLog
                        .EXTERNAL_DISPLAY_STATE_CHANGED__STATE__PRESENTATION_ENDED,
                /*numberOfDisplays=*/ 1,
                isExternalDisplayUsedForAudio);
    }

    private void initAudioPlayback(boolean isExternalDisplayUsedForAudio) {
        assertThat(mAudioPlaybackCallback).isNotNull();
        mAudioPlaybackCallback.onPlaybackConfigChanged(
                isExternalDisplayUsedForAudio ? mAudioPlaybackConfigsHdmiActive
                        : mAudioPlaybackConfigsPhoneActive);
        mHandler.flush();
    }

    private List<AudioPlaybackConfiguration> createAudioConfigs(boolean isPhoneActive) {
        var mockedConfigHdmi = mock(AudioPlaybackConfiguration.class);
        var mockedInfoHdmi = mock(AudioDeviceInfo.class);
        when(mockedInfoHdmi.isSink()).thenReturn(true);
        when(mockedInfoHdmi.getType()).thenReturn(TYPE_HDMI);
        when(mockedConfigHdmi.getAudioDeviceInfo()).thenReturn(mockedInfoHdmi);
        when(mockedConfigHdmi.isActive()).thenReturn(!isPhoneActive);

        var mockedInfoPhone = mock(AudioDeviceInfo.class);
        var mockedConfigPhone = mock(AudioPlaybackConfiguration.class);
        when(mockedInfoPhone.isSink()).thenReturn(true);
        when(mockedInfoPhone.getType()).thenReturn(TYPE_BUILTIN_SPEAKER);
        when(mockedConfigPhone.getAudioDeviceInfo()).thenReturn(mockedInfoPhone);
        when(mockedConfigPhone.isActive()).thenReturn(isPhoneActive);
        return List.of(mockedConfigHdmi, mockedConfigPhone);
    }
}
