/*
 * Copyright 2022 The Android Open Source Project
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
package com.android.server.audio;

import static android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE;
import static android.media.AudioManager.GET_DEVICES_OUTPUTS;
import static android.media.AudioPlaybackConfiguration.PLAYER_UPDATE_DEVICE_ID;
import static android.media.LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_4;
import static android.media.LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_D;
import static android.media.MediaFormat.KEY_AAC_DRC_EFFECT_TYPE;
import static android.media.MediaFormat.KEY_AAC_DRC_HEAVY_COMPRESSION;
import static android.media.MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL;
import static android.os.Process.myPid;

import static com.android.server.audio.LoudnessCodecHelper.SPL_RANGE_LARGE;
import static com.android.server.audio.LoudnessCodecHelper.SPL_RANGE_MEDIUM;
import static com.android.server.audio.LoudnessCodecHelper.SPL_RANGE_SMALL;
import static com.android.server.audio.LoudnessCodecHelper.SPL_RANGE_UNKNOWN;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.ILoudnessCodecUpdatesDispatcher;
import android.media.LoudnessCodecInfo;
import android.media.PlayerBase;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.audio.LoudnessCodecHelper.DeviceSplRange;
import com.android.server.audio.LoudnessCodecHelper.LoudnessCodecInputProperties;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@Presubmit
public class LoudnessCodecHelperTest {
    private static final String TAG = "LoudnessCodecHelperTest";

    private static final int TEST_USAGE = AudioAttributes.USAGE_MEDIA;

    private static final int TEST_CONTENT = AudioAttributes.CONTENT_TYPE_MUSIC;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    private LoudnessCodecHelper mLoudnessHelper;

    @Mock
    private AudioService mAudioService;
    @Mock
    private ILoudnessCodecUpdatesDispatcher.Default mDispatcher;

    private final int mInitialApcPiid = 1;

    private int mSessionId;

    @Before
    public void setUp() throws Exception {
        mLoudnessHelper = new LoudnessCodecHelper(mAudioService);
        mSessionId = 1;

        when(mAudioService.getActivePlaybackConfigurations()).thenReturn(
                getApcListForApcWithPiidSid(mInitialApcPiid, mSessionId, 0));

        when(mDispatcher.asBinder()).thenReturn(Mockito.mock(IBinder.class));
    }

    @Test
    public void registerDispatcher_sendsUpdateOnAddCodec() throws Exception {
        mLoudnessHelper.registerLoudnessCodecUpdatesDispatcher(mDispatcher);

        mLoudnessHelper.startLoudnessCodecUpdates(mSessionId);
        mLoudnessHelper.addLoudnessCodecInfo(mSessionId, /*mediaCodecHash=*/222,
                getLoudnessInfo(/*isDownmixing=*/true, CODEC_METADATA_TYPE_MPEG_D));

        verify(mDispatcher).dispatchLoudnessCodecParameterChange(eq(mSessionId), any());
    }

    @Test
    public void unregisterDispatcher_noUpdateOnAdd() throws Exception {
        mLoudnessHelper.registerLoudnessCodecUpdatesDispatcher(mDispatcher);
        mLoudnessHelper.unregisterLoudnessCodecUpdatesDispatcher(mDispatcher);

        mLoudnessHelper.startLoudnessCodecUpdates(mSessionId);
        mLoudnessHelper.addLoudnessCodecInfo(mSessionId, /*mediaCodecHash=*/222,
                getLoudnessInfo(/*isDownmixing=*/true, CODEC_METADATA_TYPE_MPEG_D));

        verify(mDispatcher, times(0)).dispatchLoudnessCodecParameterChange(eq(mSessionId),
                any());
    }

    @Test
    public void addCodecInfoForDifferentId_noUpdateSent() throws Exception {
        final int newSessionId = mSessionId + 1;
        mLoudnessHelper.registerLoudnessCodecUpdatesDispatcher(mDispatcher);

        mLoudnessHelper.startLoudnessCodecUpdates(mSessionId);
        mLoudnessHelper.addLoudnessCodecInfo(newSessionId, /*mediaCodecHash=*/222,
                getLoudnessInfo(/*isDownmixing=*/true, CODEC_METADATA_TYPE_MPEG_D));

        verify(mDispatcher, times(0)).dispatchLoudnessCodecParameterChange(eq(mSessionId),
                any());
    }

    @Test
    public void updateCodecParameters_noStartedPiids_noDispatch() throws Exception {
        mLoudnessHelper.registerLoudnessCodecUpdatesDispatcher(mDispatcher);
        mLoudnessHelper.addLoudnessCodecInfo(mSessionId, /*mediaCodecHash=*/222,
                getLoudnessInfo(/*isDownmixing=*/true, CODEC_METADATA_TYPE_MPEG_D));

        mLoudnessHelper.updateCodecParameters(
                getApcListForApcWithPiidSid(mInitialApcPiid, mSessionId, 1));

        // no dispatch since mSessionId was not started
        verify(mDispatcher, times(0)).dispatchLoudnessCodecParameterChange(eq(mSessionId),
                any());
    }

    @Test
    public void updateCodecParameters_dispatchUpdates() throws Exception {
        final LoudnessCodecInfo info = getLoudnessInfo(/*isDownmixing=*/true,
                CODEC_METADATA_TYPE_MPEG_4);
        mLoudnessHelper.registerLoudnessCodecUpdatesDispatcher(mDispatcher);

        mLoudnessHelper.startLoudnessCodecUpdates(mSessionId);
        mLoudnessHelper.addLoudnessCodecInfo(mSessionId, /*mediaCodecHash=*/222, info);

        mLoudnessHelper.updateCodecParameters(
                getApcListForApcWithPiidSid(mInitialApcPiid, mSessionId, 1));

        // second dispatch since player configurations were updated
        verify(mDispatcher, times(2)).dispatchLoudnessCodecParameterChange(eq(mSessionId),
                any());
    }

    @Test
    public void updateCodecParameters_removedCodecInfo_noDispatch() throws Exception {
        final LoudnessCodecInfo info = getLoudnessInfo(/*isDownmixing=*/true,
                CODEC_METADATA_TYPE_MPEG_4);
        mLoudnessHelper.registerLoudnessCodecUpdatesDispatcher(mDispatcher);

        mLoudnessHelper.startLoudnessCodecUpdates(mSessionId);
        mLoudnessHelper.addLoudnessCodecInfo(mSessionId, /*mediaCodecHash=*/222, info);
        mLoudnessHelper.removeLoudnessCodecInfo(mSessionId, info);

        mLoudnessHelper.updateCodecParameters(
                getApcListForApcWithPiidSid(mInitialApcPiid, mSessionId, 1));

        // no second dispatch since codec info was removed for updates
        verify(mDispatcher, times(1)).dispatchLoudnessCodecParameterChange(eq(mSessionId),
                any());
    }

    @Test
    public void updateCodecParameters_stoppedPiids_noDispatch() throws Exception {
        final LoudnessCodecInfo info = getLoudnessInfo(/*isDownmixing=*/true,
                CODEC_METADATA_TYPE_MPEG_4);
        mLoudnessHelper.registerLoudnessCodecUpdatesDispatcher(mDispatcher);

        mLoudnessHelper.startLoudnessCodecUpdates(mSessionId);
        mLoudnessHelper.stopLoudnessCodecUpdates(mSessionId);

        mLoudnessHelper.updateCodecParameters(
                getApcListForApcWithPiidSid(mInitialApcPiid, mSessionId, 1));

        // no second dispatch since piid was removed for updates
        verify(mDispatcher, times(0)).dispatchLoudnessCodecParameterChange(eq(mSessionId),
                any());
    }

    @Test
    public void checkParcelableBundle_forMpeg4CodecInputProperties() {
        PersistableBundle loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_4, /*isDownmixing*/true,
                SPL_RANGE_SMALL).createLoudnessParameters();
        assertEquals(64, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(1, loudnessParameters.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_4, /*isDownmixing*/false,
                SPL_RANGE_SMALL).createLoudnessParameters();
        assertEquals(64, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(1, loudnessParameters.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_4, /*isDownmixing*/true,
                SPL_RANGE_MEDIUM).createLoudnessParameters();
        assertEquals(96, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(1, loudnessParameters.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_4, /*isDownmixing*/false,
                SPL_RANGE_MEDIUM).createLoudnessParameters();
        assertEquals(96, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(0, loudnessParameters.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_4, /*isDownmixing*/true,
                SPL_RANGE_LARGE).createLoudnessParameters();
        assertEquals(124, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(0, loudnessParameters.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_4, /*isDownmixing*/false,
                SPL_RANGE_LARGE).createLoudnessParameters();
        assertEquals(124, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(0, loudnessParameters.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_4, /*isDownmixing*/true,
                SPL_RANGE_UNKNOWN).createLoudnessParameters();
        assertEquals(96, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(1, loudnessParameters.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_4, /*isDownmixing*/false,
                SPL_RANGE_UNKNOWN).createLoudnessParameters();
        assertEquals(96, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(0, loudnessParameters.getInt(KEY_AAC_DRC_HEAVY_COMPRESSION));
    }

    @Test
    public void checkParcelableBundle_forMpegDCodecInputProperties() {
        PersistableBundle loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_D, /*isDownmixing*/true,
                SPL_RANGE_SMALL).createLoudnessParameters();
        assertEquals(64, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(3, loudnessParameters.getInt(KEY_AAC_DRC_EFFECT_TYPE));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_D, /*isDownmixing*/false,
                SPL_RANGE_SMALL).createLoudnessParameters();
        assertEquals(64, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(3, loudnessParameters.getInt(KEY_AAC_DRC_EFFECT_TYPE));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_D, /*isDownmixing*/true,
                SPL_RANGE_MEDIUM).createLoudnessParameters();
        assertEquals(96, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(6, loudnessParameters.getInt(KEY_AAC_DRC_EFFECT_TYPE));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_D, /*isDownmixing*/false,
                SPL_RANGE_MEDIUM).createLoudnessParameters();
        assertEquals(96, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(6, loudnessParameters.getInt(KEY_AAC_DRC_EFFECT_TYPE));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_D, /*isDownmixing*/true,
                SPL_RANGE_LARGE).createLoudnessParameters();
        assertEquals(124, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(6, loudnessParameters.getInt(KEY_AAC_DRC_EFFECT_TYPE));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_D, /*isDownmixing*/false,
                SPL_RANGE_LARGE).createLoudnessParameters();
        assertEquals(124, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(6, loudnessParameters.getInt(KEY_AAC_DRC_EFFECT_TYPE));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_D, /*isDownmixing*/true,
                SPL_RANGE_UNKNOWN).createLoudnessParameters();
        assertEquals(96, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(6, loudnessParameters.getInt(KEY_AAC_DRC_EFFECT_TYPE));

        loudnessParameters = createInputProperties(
                CODEC_METADATA_TYPE_MPEG_D, /*isDownmixing*/false,
                SPL_RANGE_UNKNOWN).createLoudnessParameters();
        assertEquals(96, loudnessParameters.getInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL));
        assertEquals(6, loudnessParameters.getInt(KEY_AAC_DRC_EFFECT_TYPE));
    }

    private List<AudioPlaybackConfiguration> getApcListForApcWithPiidSid(int piid, int sessionId,
            int devIdx) {
        final ArrayList<AudioPlaybackConfiguration> apcList = new ArrayList<>();

        AudioDeviceInfo[] devicesStatic = AudioManager.getDevicesStatic(GET_DEVICES_OUTPUTS);
        assumeTrue(devIdx < devicesStatic.length);
        Log.d(TAG, "Out devices number " + devicesStatic.length + ". Picking index " + devIdx);
        int deviceId = devicesStatic[devIdx].getId();

        PlayerBase.PlayerIdCard idCard = Mockito.mock(PlayerBase.PlayerIdCard.class);
        AudioPlaybackConfiguration apc =
                new AudioPlaybackConfiguration(idCard, piid, /*uid=*/1, /*pid=*/myPid());
        apc.handleStateEvent(PLAYER_UPDATE_DEVICE_ID, deviceId);
        apc.handleSessionIdEvent(sessionId);
        apc.handleAudioAttributesEvent(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)
                .build());

        apcList.add(apc);

        return apcList;
    }

    private static LoudnessCodecInputProperties createInputProperties(
            int metadataType, boolean isDownmixing, @DeviceSplRange int splRange) {
        return new LoudnessCodecInputProperties.Builder().setMetadataType(
                metadataType).setIsDownmixing(isDownmixing).setDeviceSplRange(splRange).build();
    }

    private static LoudnessCodecInfo getLoudnessInfo(boolean isDownmixing, int metadataType) {
        LoudnessCodecInfo info = new LoudnessCodecInfo();
        info.isDownmixing = isDownmixing;
        info.metadataType = metadataType;

        return info;
    }
}
