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

package com.android.server.companion.virtual.audio;

import static android.media.AudioAttributes.FLAG_SECURE;
import static android.media.AudioPlaybackConfiguration.PLAYER_STATE_STARTED;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.PlayerBase;
import android.os.Parcel;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.companion.virtual.GenericWindowPolicyController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualAudioControllerTest {
    private static final int APP1_UID = 100;
    private static final int APP2_UID = 200;

    private Context mContext;
    private VirtualAudioController mVirtualAudioController;
    private GenericWindowPolicyController mGenericWindowPolicyController;
    @Mock
    private IAudioRoutingCallback mRoutingCallback;
    @Mock
    private IAudioConfigChangedCallback mConfigChangedCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = Mockito.spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        mVirtualAudioController = new VirtualAudioController(mContext,
                AttributionSource.myAttributionSource());
        mGenericWindowPolicyController =
                new GenericWindowPolicyController(
                        FLAG_SECURE,
                        SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                        AttributionSource.myAttributionSource(),
                        /* allowedUsers= */ new ArraySet<>(),
                        /* activityLaunchAllowedByDefault= */ true,
                        /* activityPolicyExemptions= */ new ArraySet<>(),
                        /* activityPolicyPackageExemptions= */ new ArraySet<>(),
                        /* crossTaskNavigationAllowedByDefault= */ true,
                        /* crossTaskNavigationExemptions= */ new ArraySet<>(),
                        /* activityListener= */ null,
                        /* displayCategories= */ new ArraySet<>(),
                        /* showTasksInHostDeviceRecents= */ true,
                        /* customHomeComponent= */ null);
    }


    @FlakyTest(bugId = 265155135)
    @Test
    public void startListening_receivesCallback() throws RemoteException {
        ArraySet<Integer> runningUids = new ArraySet<>();
        runningUids.add(APP1_UID);
        int[] appUids = new int[]{APP1_UID};

        mVirtualAudioController.startListening(
                mGenericWindowPolicyController, mRoutingCallback, mConfigChangedCallback);

        mGenericWindowPolicyController.onRunningAppsChanged(runningUids);
        verify(mRoutingCallback).onAppsNeedingAudioRoutingChanged(appUids);
    }

    @Test
    public void stopListening_removesCallback() throws RemoteException {
        ArraySet<Integer> runningUids = new ArraySet<>();
        runningUids.add(APP1_UID);
        int[] appUids = new int[]{APP1_UID};
        mVirtualAudioController.startListening(
                mGenericWindowPolicyController, mRoutingCallback, mConfigChangedCallback);

        mVirtualAudioController.stopListening();

        mGenericWindowPolicyController.onRunningAppsChanged(runningUids);
        verify(mRoutingCallback, never()).onAppsNeedingAudioRoutingChanged(appUids);
    }

    @Test
    public void onRunningAppsChanged_notifiesAudioRoutingModified() throws RemoteException {
        mVirtualAudioController.startListening(
                mGenericWindowPolicyController, mRoutingCallback, mConfigChangedCallback);

        ArraySet<Integer> runningUids = new ArraySet<>();
        runningUids.add(APP1_UID);
        mVirtualAudioController.onRunningAppsChanged(runningUids);

        int[] appUids = new int[]{APP1_UID};
        verify(mRoutingCallback).onAppsNeedingAudioRoutingChanged(appUids);
    }

    @Test
    public void onRunningAppsChanged_audioIsPlaying_doesNothing() throws RemoteException {
        mVirtualAudioController.startListening(
                mGenericWindowPolicyController, mRoutingCallback, mConfigChangedCallback);
        mVirtualAudioController.addPlayingAppsForTesting(APP2_UID);

        ArraySet<Integer> runningUids = new ArraySet<>();
        runningUids.add(APP1_UID);
        mVirtualAudioController.onRunningAppsChanged(runningUids);

        int[] appUids = new int[]{APP1_UID};
        verify(mRoutingCallback, never()).onAppsNeedingAudioRoutingChanged(appUids);
    }

    @Test
    public void onRunningAppsChanged_lastPlayingAppRemoved_delaysReroutingAudio() {
        ArraySet<Integer> runningUids = new ArraySet<>();
        runningUids.add(APP1_UID);
        runningUids.add(APP2_UID);
        mVirtualAudioController.onRunningAppsChanged(runningUids);
        mVirtualAudioController.addPlayingAppsForTesting(APP2_UID);

        ArraySet<Integer> appUids = new ArraySet<>();
        appUids.add(APP1_UID);
        mVirtualAudioController.onRunningAppsChanged(appUids);

        assertThat(mVirtualAudioController.hasPendingRunnable()).isTrue();
    }

    @Test
    public void onPlaybackConfigChanged_sendsCallback() throws RemoteException {
        mVirtualAudioController.startListening(
                mGenericWindowPolicyController, mRoutingCallback, mConfigChangedCallback);
        ArraySet<Integer> runningUids = new ArraySet<>();
        runningUids.add(APP1_UID);
        mVirtualAudioController.onRunningAppsChanged(runningUids);
        List<AudioPlaybackConfiguration> configs = createPlaybackConfigurations(runningUids);

        mVirtualAudioController.onPlaybackConfigChanged(configs);

        verify(mConfigChangedCallback).onPlaybackConfigChanged(configs);
    }

    @Test
    public void onRecordingConfigChanged_sendsCallback() throws RemoteException {
        mVirtualAudioController.startListening(
                mGenericWindowPolicyController, mRoutingCallback, mConfigChangedCallback);
        ArraySet<Integer> runningUids = new ArraySet<>();
        runningUids.add(APP1_UID);
        mVirtualAudioController.onRunningAppsChanged(runningUids);
        List<AudioRecordingConfiguration> configs = createRecordingConfigurations(runningUids);

        mVirtualAudioController.onRecordingConfigChanged(configs);

        verify(mConfigChangedCallback).onRecordingConfigChanged(configs);
    }

    private List<AudioPlaybackConfiguration> createPlaybackConfigurations(
            ArraySet<Integer> appUids) {
        List<AudioPlaybackConfiguration> configs = new ArrayList<>();
        for (int appUid : appUids) {
            PlayerBase.PlayerIdCard playerIdCard =
                    PlayerBase.PlayerIdCard.CREATOR.createFromParcel(Parcel.obtain());
            AudioPlaybackConfiguration audioPlaybackConfiguration =
                    new AudioPlaybackConfiguration(
                            playerIdCard, /* piid= */ 1000, appUid, /* pid= */ 1000);
            audioPlaybackConfiguration.handleStateEvent(PLAYER_STATE_STARTED, /* deviceId= */1);
            configs.add(audioPlaybackConfiguration);
        }
        return configs;
    }

    private List<AudioRecordingConfiguration> createRecordingConfigurations(
            ArraySet<Integer> appUids) {
        List<AudioRecordingConfiguration> configs = new ArrayList<>();
        for (int appUid : appUids) {
            AudioRecordingConfiguration audioRecordingConfiguration =
                    new AudioRecordingConfiguration(
                            /* uid= */ appUid,
                            /* session= */ 1000,
                            MediaRecorder.AudioSource.MIC,
                            /* clientFormat= */ null,
                            /* devFormat= */ null,
                            /* patchHandle= */ 1000,
                            "com.android.example");
            configs.add(audioRecordingConfiguration);
        }
        return configs;
    }
}
