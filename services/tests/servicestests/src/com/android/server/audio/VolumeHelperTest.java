/*
 * Copyright 2024 The Android Open Source Project
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

import static android.media.AudioManager.ADJUST_LOWER;
import static android.media.AudioManager.ADJUST_MUTE;
import static android.media.AudioManager.ADJUST_RAISE;
import static android.media.AudioManager.DEVICE_OUT_BLE_SPEAKER;
import static android.media.AudioManager.DEVICE_OUT_BLUETOOTH_SCO;
import static android.media.AudioManager.DEVICE_OUT_SPEAKER;
import static android.media.AudioManager.DEVICE_OUT_USB_DEVICE;
import static android.media.AudioManager.DEVICE_VOLUME_BEHAVIOR_UNSET;
import static android.media.AudioManager.FLAG_ALLOW_RINGER_MODES;
import static android.media.AudioManager.FLAG_BLUETOOTH_ABS_VOLUME;
import static android.media.AudioManager.RINGER_MODE_NORMAL;
import static android.media.AudioManager.RINGER_MODE_VIBRATE;
import static android.media.AudioManager.STREAM_ACCESSIBILITY;
import static android.media.AudioManager.STREAM_ALARM;
import static android.media.AudioManager.STREAM_BLUETOOTH_SCO;
import static android.media.AudioManager.STREAM_MUSIC;
import static android.media.AudioManager.STREAM_NOTIFICATION;
import static android.media.AudioManager.STREAM_RING;
import static android.media.AudioManager.STREAM_SYSTEM;
import static android.media.AudioManager.STREAM_VOICE_CALL;
import static android.media.audio.Flags.autoPublicVolumeApiHardening;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.KEYCODE_VOLUME_UP;

import static com.android.media.audio.Flags.FLAG_DISABLE_PRESCALE_ABSOLUTE_VOLUME;
import static com.android.media.audio.Flags.FLAG_ABS_VOLUME_INDEX_FIX;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.IDeviceVolumeBehaviorDispatcher;
import android.media.VolumeInfo;
import android.media.audiopolicy.AudioVolumeGroup;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.SparseIntArray;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@Presubmit
public class VolumeHelperTest {
    private static final AudioDeviceAttributes DEVICE_SPEAKER_OUT = new AudioDeviceAttributes(
            AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "");

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private MyAudioService mAudioService;

    private AudioManager mAm;

    private Context mContext;

    private AudioSystemAdapter mSpyAudioSystem;
    private SettingsAdapter mSettingsAdapter;
    @Spy
    private NoOpSystemServerAdapter mSpySystemServer;
    @Mock
    private AppOpsManager mMockAppOpsManager;
    @Mock
    private PermissionEnforcer mMockPermissionEnforcer;
    @Mock
    private AudioServerPermissionProvider mMockPermissionProvider;
    @Mock
    private AudioVolumeGroupHelperBase mAudioVolumeGroupHelper;

    @Mock
    private AudioPolicyFacade mMockAudioPolicy;

    private AudioVolumeGroup mAudioMusicVolumeGroup;

    private TestLooper mTestLooper;

    public static final int[] BASIC_VOLUME_BEHAVIORS = {
            AudioManager.DEVICE_VOLUME_BEHAVIOR_VARIABLE,
            AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL,
            AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED
    };

    private static class MyAudioService extends AudioService {
        private final SparseIntArray mStreamDevice = new SparseIntArray();

        MyAudioService(Context context, AudioSystemAdapter audioSystem,
                SystemServerAdapter systemServer, SettingsAdapter settings,
                AudioVolumeGroupHelperBase audioVolumeGroupHelper, AudioPolicyFacade audioPolicy,
                @Nullable Looper looper, AppOpsManager appOps,
                @NonNull PermissionEnforcer enforcer,
                AudioServerPermissionProvider permissionProvider) {
            super(context, audioSystem, systemServer, settings, audioVolumeGroupHelper,
                    audioPolicy, looper, appOps, enforcer, permissionProvider);
        }

        public void setDeviceForStream(int stream, int device) {
            mStreamDevice.put(stream, device);
        }

        @Override
        public int getDeviceForStream(int stream) {
            if (mStreamDevice.indexOfKey(stream) < 0) {
                return DEVICE_OUT_SPEAKER;
            }
            return mStreamDevice.get(stream);
        }
    }

    private static class TestDeviceVolumeBehaviorDispatcherStub
            extends IDeviceVolumeBehaviorDispatcher.Stub {

        private AudioDeviceAttributes mDevice;
        private int mVolumeBehavior;
        private int mTimesCalled;

        @Override
        public void dispatchDeviceVolumeBehaviorChanged(@NonNull AudioDeviceAttributes device,
                @AudioManager.DeviceVolumeBehavior int volumeBehavior) {
            mDevice = device;
            mVolumeBehavior = volumeBehavior;
            mTimesCalled++;
        }

        public void reset() {
            mTimesCalled = 0;
            mVolumeBehavior = DEVICE_VOLUME_BEHAVIOR_UNSET;
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mTestLooper = new TestLooper();

        mSpyAudioSystem = spy(new NoOpAudioSystemAdapter());
        mSettingsAdapter = new NoOpSettingsAdapter();

        mAudioMusicVolumeGroup = getStreamTypeVolumeGroup(STREAM_MUSIC);
        if (mAudioMusicVolumeGroup != null) {
            when(mAudioVolumeGroupHelper.getAudioVolumeGroups()).thenReturn(
                    List.of(mAudioMusicVolumeGroup));
        }

        mAm = mContext.getSystemService(AudioManager.class);

        mAudioService = new MyAudioService(mContext, mSpyAudioSystem, mSpySystemServer,
                mSettingsAdapter, mAudioVolumeGroupHelper, mMockAudioPolicy,
                mTestLooper.getLooper(), mMockAppOpsManager, mMockPermissionEnforcer,
                mMockPermissionProvider);

        mTestLooper.dispatchAll();
        prepareAudioServiceState();
        mTestLooper.dispatchAll();

        reset(mSpyAudioSystem);

        final boolean useFixedVolume = mContext.getResources().getBoolean(
                Resources.getSystem().getIdentifier("config_useFixedVolume", "bool", "android"));
        final PackageManager packageManager = mContext.getPackageManager();
        final boolean isTelevision = packageManager != null
                && (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION));
        final boolean isSingleVolume = mContext.getResources().getBoolean(
                Resources.getSystem().getIdentifier("config_single_volume", "bool", "android"));
        final boolean automotiveHardened = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE) && autoPublicVolumeApiHardening();
        assumeFalse("Skipping test for fixed, TV, single volume and auto devices",
                useFixedVolume || isTelevision || isSingleVolume || automotiveHardened);

        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED,
                        Manifest.permission.MODIFY_AUDIO_ROUTING,
                        Manifest.permission.MODIFY_PHONE_STATE,
                        android.Manifest.permission.STATUS_BAR_SERVICE);
    }

    private void prepareAudioServiceState() throws Exception {
        int[] usedStreamTypes =
                {STREAM_MUSIC, STREAM_NOTIFICATION, STREAM_RING, STREAM_ALARM, STREAM_SYSTEM,
                        STREAM_VOICE_CALL, STREAM_ACCESSIBILITY};
        for (int streamType : usedStreamTypes) {
            final int streamVolume = (mAm.getStreamMinVolume(streamType) + mAm.getStreamMaxVolume(
                    streamType)) / 2;

            mAudioService.setStreamVolume(streamType, streamVolume, /*flags=*/0,
                    mContext.getOpPackageName());
        }

        mAudioService.setRingerModeInternal(RINGER_MODE_NORMAL, mContext.getOpPackageName());
        mAudioService.setRingerModeExternal(RINGER_MODE_NORMAL, mContext.getOpPackageName());
    }

    private AudioVolumeGroup getStreamTypeVolumeGroup(int streamType) {
        // get the volume group from the AudioManager to pass permission checks
        // when requesting from real service
        final List<AudioVolumeGroup> audioVolumeGroups = AudioManager.getAudioVolumeGroups();
        for (AudioVolumeGroup vg : audioVolumeGroups) {
            for (int stream : vg.getLegacyStreamTypes()) {
                if (stream == streamType) {
                    return vg;
                }
            }
        }

        return null;
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    // --------------- Volume Stream APIs ---------------
    @Test
    public void setStreamVolume_callsASSetStreamVolumeIndex() throws Exception {
        int newIndex = circularNoMinMaxIncrementVolume(STREAM_MUSIC);

        mAudioService.setDeviceForStream(STREAM_MUSIC, DEVICE_OUT_USB_DEVICE);
        mAudioService.setStreamVolume(STREAM_MUSIC, newIndex, /*flags=*/0,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem).setStreamVolumeIndexAS(
                eq(STREAM_MUSIC), eq(newIndex), eq(DEVICE_OUT_USB_DEVICE));
    }

    @Test
    public void setStreamRingVolume0_setsRingerModeVibrate() throws Exception {
        mAudioService.setStreamVolume(STREAM_RING, 0, /*flags=*/0,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        assertEquals(RINGER_MODE_VIBRATE, mAudioService.getRingerModeExternal());
    }

    @Test
    public void adjustStreamVolume_callsASSetStreamVolumeIndex() throws Exception {
        mAudioService.setDeviceForStream(STREAM_MUSIC, DEVICE_OUT_USB_DEVICE);
        mAudioService.adjustStreamVolume(STREAM_MUSIC, ADJUST_LOWER, /*flags=*/0,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                eq(STREAM_MUSIC), anyInt(), eq(DEVICE_OUT_USB_DEVICE));
    }

    @Test
    public void handleVolumeKey_callsASSetStreamVolumeIndex() throws Exception {
        final KeyEvent keyEvent = new KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP);

        mAudioService.setDeviceForStream(STREAM_MUSIC, DEVICE_OUT_USB_DEVICE);
        mAudioService.handleVolumeKey(keyEvent, /*isOnTv=*/false, mContext.getOpPackageName(),
                "adjustSuggestedStreamVolume_callsAudioSystemSetStreamVolumeIndex");
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem).setStreamVolumeIndexAS(
                eq(STREAM_MUSIC), anyInt(), eq(DEVICE_OUT_USB_DEVICE));
    }

    // --------------- Volume Group APIs ---------------

    @Test
    public void setVolumeGroupVolumeIndex_callsASSetVolumeIndexForAttributes() throws Exception {
        assumeNotNull(mAudioMusicVolumeGroup);

        mAudioService.setDeviceForStream(STREAM_MUSIC, DEVICE_OUT_USB_DEVICE);
        mAudioService.setVolumeGroupVolumeIndex(mAudioMusicVolumeGroup.getId(),
                circularNoMinMaxIncrementVolume(STREAM_MUSIC), /*flags=*/0,
                mContext.getOpPackageName(),  /*attributionTag*/null);
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem).setVolumeIndexForAttributes(
                any(), anyInt(), eq(DEVICE_OUT_USB_DEVICE));
    }

    @Test
    public void adjustVolumeGroupVolume_callsASSetVolumeIndexForAttributes() throws Exception {
        assumeNotNull(mAudioMusicVolumeGroup);

        mAudioService.setDeviceForStream(STREAM_MUSIC, DEVICE_OUT_USB_DEVICE);
        mAudioService.adjustVolumeGroupVolume(mAudioMusicVolumeGroup.getId(),
                ADJUST_LOWER, /*flags=*/0, mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem).setVolumeIndexForAttributes(
                any(), anyInt(), eq(DEVICE_OUT_USB_DEVICE));
    }

    @Test
    public void check_getVolumeGroupVolumeIndex() throws Exception {
        assumeNotNull(mAudioMusicVolumeGroup);

        int newIndex = circularNoMinMaxIncrementVolume(STREAM_MUSIC);

        mAudioService.setVolumeGroupVolumeIndex(mAudioMusicVolumeGroup.getId(),
                newIndex, /*flags=*/0, mContext.getOpPackageName(),  /*attributionTag*/null);
        mTestLooper.dispatchAll();

        assertEquals(mAudioService.getVolumeGroupVolumeIndex(mAudioMusicVolumeGroup.getId()),
                newIndex);
        assertEquals(mAudioService.getStreamVolume(STREAM_MUSIC),
                newIndex);
    }

    @Test
    public void check_getVolumeGroupMaxVolumeIndex() throws Exception {
        assumeNotNull(mAudioMusicVolumeGroup);

        assertEquals(mAudioService.getVolumeGroupMaxVolumeIndex(mAudioMusicVolumeGroup.getId()),
                mAudioService.getStreamMaxVolume(STREAM_MUSIC));
    }

    @Test
    public void check_getVolumeGroupMinVolumeIndex() throws Exception {
        assumeNotNull(mAudioMusicVolumeGroup);

        assertEquals(mAudioService.getVolumeGroupMinVolumeIndex(mAudioMusicVolumeGroup.getId()),
                mAudioService.getStreamMinVolume(STREAM_MUSIC));
    }

    @Test
    public void check_getLastAudibleVolumeForVolumeGroup() throws Exception {
        assumeNotNull(mAudioMusicVolumeGroup);

        assertEquals(
                mAudioService.getLastAudibleVolumeForVolumeGroup(mAudioMusicVolumeGroup.getId()),
                mAudioService.getLastAudibleStreamVolume(STREAM_MUSIC));
    }

    @Test
    public void check_isVolumeGroupMuted() throws Exception {
        assumeNotNull(mAudioMusicVolumeGroup);

        assertEquals(mAudioService.isVolumeGroupMuted(mAudioMusicVolumeGroup.getId()),
                mAudioService.isStreamMute(STREAM_MUSIC));
    }

    // ------------------------- Mute Tests ------------------------

    @Test
    public void check_setMasterMute() {
        mAudioService.setMasterMute(true, /*flags=*/0, mContext.getOpPackageName(),
                mContext.getUserId(), /*attributionTag*/"");

        assertTrue(mAudioService.isMasterMute());
    }

    @Test
    public void check_isStreamAffectedByMute() {
        assertFalse(mAudioService.isStreamAffectedByMute(STREAM_VOICE_CALL));
    }

    // --------------------- Volume Flag Check --------------------

    @Test
    public void flagAbsVolume_onBtDevice_changesVolume() throws Exception {
        mAudioService.setDeviceForStream(STREAM_NOTIFICATION, DEVICE_OUT_BLE_SPEAKER);

        int newIndex = circularNoMinMaxIncrementVolume(STREAM_NOTIFICATION);
        mAudioService.setStreamVolume(STREAM_NOTIFICATION, newIndex, FLAG_BLUETOOTH_ABS_VOLUME,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();
        verify(mSpyAudioSystem).setStreamVolumeIndexAS(
                eq(STREAM_NOTIFICATION), anyInt(), eq(DEVICE_OUT_BLE_SPEAKER));

        reset(mSpyAudioSystem);
        mAudioService.adjustStreamVolume(STREAM_NOTIFICATION, ADJUST_LOWER,
                FLAG_BLUETOOTH_ABS_VOLUME, mContext.getOpPackageName());
        mTestLooper.dispatchAll();
        verify(mSpyAudioSystem).setStreamVolumeIndexAS(
                eq(STREAM_NOTIFICATION), anyInt(), eq(DEVICE_OUT_BLE_SPEAKER));
    }

    @Test
    public void flagAbsVolume_onNonBtDevice_noVolumeChange() throws Exception {
        mAudioService.setDeviceForStream(STREAM_NOTIFICATION, DEVICE_OUT_SPEAKER);

        int newIndex = circularNoMinMaxIncrementVolume(STREAM_NOTIFICATION);
        mAudioService.setStreamVolume(STREAM_NOTIFICATION, newIndex, FLAG_BLUETOOTH_ABS_VOLUME,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();
        verify(mSpyAudioSystem, times(0)).setStreamVolumeIndexAS(
                eq(STREAM_NOTIFICATION), eq(newIndex), eq(DEVICE_OUT_BLE_SPEAKER));

        mAudioService.adjustStreamVolume(STREAM_NOTIFICATION, ADJUST_LOWER,
                FLAG_BLUETOOTH_ABS_VOLUME, mContext.getOpPackageName());
        mTestLooper.dispatchAll();
        verify(mSpyAudioSystem, times(0)).setStreamVolumeIndexAS(
                eq(STREAM_NOTIFICATION), anyInt(), eq(DEVICE_OUT_BLE_SPEAKER));
    }

    @Test
    public void flagAllowRingerModes_onSystemStreams_changesMode() throws Exception {
        mAudioService.setStreamVolume(STREAM_SYSTEM,
                mAudioService.getStreamMinVolume(STREAM_SYSTEM), /*flags=*/0,
                mContext.getOpPackageName());
        mAudioService.setRingerModeInternal(RINGER_MODE_VIBRATE, mContext.getOpPackageName());

        mAudioService.adjustStreamVolume(STREAM_SYSTEM, ADJUST_RAISE, FLAG_ALLOW_RINGER_MODES,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        assertNotEquals(mAudioService.getRingerModeInternal(), RINGER_MODE_VIBRATE);
    }

    @Test
    public void flagAllowRingerModesAbsent_onNonSystemStreams_noModeChange() throws Exception {
        mAudioService.setStreamVolume(STREAM_MUSIC,
                mAudioService.getStreamMinVolume(STREAM_MUSIC), /*flags=*/0,
                mContext.getOpPackageName());
        mAudioService.setRingerModeInternal(RINGER_MODE_VIBRATE, mContext.getOpPackageName());

        mAudioService.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE, /*flags=*/0,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        assertEquals(mAudioService.getRingerModeInternal(), RINGER_MODE_VIBRATE);
    }

    // --------------------- Permission tests ---------------------

    @Test
    public void appOpsIgnore_noVolumeChange() throws Exception {
        reset(mMockAppOpsManager);
        when(mMockAppOpsManager.noteOp(anyInt(), anyInt(), anyString(), isNull(), isNull()))
                .thenReturn(AppOpsManager.MODE_IGNORED);

        mAudioService.adjustStreamVolume(STREAM_MUSIC, ADJUST_LOWER, /*flags=*/0,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem, times(0)).setStreamVolumeIndexAS(
                eq(STREAM_MUSIC), anyInt(), anyInt());
    }

    @Test
    public void modifyPhoneStateAbsent_noMuteVoiceCallScoAllowed() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();

        mAudioService.setDeviceForStream(STREAM_VOICE_CALL, DEVICE_OUT_USB_DEVICE);
        mAudioService.adjustStreamVolume(STREAM_VOICE_CALL, ADJUST_MUTE, /*flags=*/0,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem, times(0)).setStreamVolumeIndexAS(
                eq(STREAM_VOICE_CALL), anyInt(), eq(DEVICE_OUT_USB_DEVICE));

        mAudioService.setDeviceForStream(STREAM_BLUETOOTH_SCO, DEVICE_OUT_BLUETOOTH_SCO);
        mAudioService.adjustStreamVolume(STREAM_BLUETOOTH_SCO, ADJUST_MUTE, /*flags=*/0,
                mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem, times(0)).setStreamVolumeIndexAS(
                eq(STREAM_BLUETOOTH_SCO), anyInt(), eq(DEVICE_OUT_USB_DEVICE));
    }

    // ----------------- AudioDeviceVolumeManager -----------------
    @Test
    public void setDeviceVolume_checkIndex() {
        final int minIndex = mAm.getStreamMinVolume(STREAM_MUSIC);
        final int maxIndex = mAm.getStreamMaxVolume(STREAM_MUSIC);
        final int midIndex = (minIndex + maxIndex) / 2;
        final VolumeInfo volMedia = new VolumeInfo.Builder(STREAM_MUSIC)
                .setMinVolumeIndex(minIndex)
                .setMaxVolumeIndex(maxIndex)
                .build();
        final VolumeInfo volMin = new VolumeInfo.Builder(volMedia).setVolumeIndex(minIndex).build();
        final VolumeInfo volMid = new VolumeInfo.Builder(volMedia).setVolumeIndex(midIndex).build();
        final AudioDeviceAttributes usbDevice = new AudioDeviceAttributes(
                /*native type*/ AudioSystem.DEVICE_OUT_USB_DEVICE, /*address*/ "bla");

        mAudioService.setDeviceVolume(volMin, usbDevice, mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        assertEquals(mAudioService.getDeviceVolume(volMin, usbDevice,
                mContext.getOpPackageName()), volMin);
        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                STREAM_MUSIC, minIndex, AudioSystem.DEVICE_OUT_USB_DEVICE);

        mAudioService.setDeviceVolume(volMid, usbDevice, mContext.getOpPackageName());
        mTestLooper.dispatchAll();
        assertEquals(mAudioService.getDeviceVolume(volMid, usbDevice,
                mContext.getOpPackageName()), volMid);
        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                STREAM_MUSIC, midIndex, AudioSystem.DEVICE_OUT_USB_DEVICE);
    }

    @Test
    @RequiresFlagsDisabled({FLAG_DISABLE_PRESCALE_ABSOLUTE_VOLUME, FLAG_ABS_VOLUME_INDEX_FIX})
    public void configurablePreScaleAbsoluteVolume_checkIndex() throws Exception {
        final int minIndex = mAm.getStreamMinVolume(STREAM_MUSIC);
        final int maxIndex = mAm.getStreamMaxVolume(STREAM_MUSIC);
        final VolumeInfo volMedia = new VolumeInfo.Builder(STREAM_MUSIC)
                .setMinVolumeIndex(minIndex)
                .setMaxVolumeIndex(maxIndex)
                .build();
        final AudioDeviceAttributes bleDevice = new AudioDeviceAttributes(
                /*native type*/ AudioSystem.DEVICE_OUT_BLE_HEADSET, /*address*/ "fake_ble");
        final int maxPreScaleIndex = 3;
        final float[] preScale = new float[maxPreScaleIndex];
        preScale[0] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index1,
                1, 1);
        preScale[1] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index2,
                1, 1);
        preScale[2] = mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_prescaleAbsoluteVolume_index3,
                1, 1);

        for (int i = 0; i < maxPreScaleIndex; i++) {
            final int targetIndex = (int) (preScale[i] * maxIndex);
            final VolumeInfo volCur = new VolumeInfo.Builder(volMedia)
                    .setVolumeIndex(i + 1).build();
            // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set (index:1~3)
            mAudioService.setDeviceVolume(volCur, bleDevice, mContext.getOpPackageName());
            mTestLooper.dispatchAll();

            assertEquals(
                    mAudioService.getDeviceVolume(volCur, bleDevice, mContext.getOpPackageName()),
                    volCur);
            // Stream volume changes
            verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                    STREAM_MUSIC, targetIndex,
                    AudioSystem.DEVICE_OUT_BLE_HEADSET);
        }

        // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set (index:4)
        final VolumeInfo volIndex4 = new VolumeInfo.Builder(volMedia)
                .setVolumeIndex(4).build();
        mAudioService.setDeviceVolume(volIndex4, bleDevice, mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        assertEquals(
                mAudioService.getDeviceVolume(volIndex4, bleDevice, mContext.getOpPackageName()),
                volIndex4);
        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                STREAM_MUSIC, maxIndex,
                AudioSystem.DEVICE_OUT_BLE_HEADSET);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_DISABLE_PRESCALE_ABSOLUTE_VOLUME)
    @RequiresFlagsDisabled(FLAG_ABS_VOLUME_INDEX_FIX)
    public void disablePreScaleAbsoluteVolume_checkIndex() throws Exception {
        final int minIndex = mAm.getStreamMinVolume(STREAM_MUSIC);
        final int maxIndex = mAm.getStreamMaxVolume(STREAM_MUSIC);
        final VolumeInfo volMedia = new VolumeInfo.Builder(STREAM_MUSIC)
                .setMinVolumeIndex(minIndex)
                .setMaxVolumeIndex(maxIndex)
                .build();
        final AudioDeviceAttributes bleDevice = new AudioDeviceAttributes(
                /*native type*/ AudioSystem.DEVICE_OUT_BLE_HEADSET, /*address*/ "bla");
        final int maxPreScaleIndex = 3;

        for (int i = 0; i < maxPreScaleIndex; i++) {
            final VolumeInfo volCur = new VolumeInfo.Builder(volMedia)
                    .setVolumeIndex(i + 1).build();
            // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set (index:1~3)
            mAudioService.setDeviceVolume(volCur, bleDevice, mContext.getOpPackageName());
            mTestLooper.dispatchAll();

            // Stream volume changes
            verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                    STREAM_MUSIC, maxIndex,
                    AudioSystem.DEVICE_OUT_BLE_HEADSET);
        }

        // Adjust stream volume with FLAG_ABSOLUTE_VOLUME set (index:4)
        final VolumeInfo volIndex4 = new VolumeInfo.Builder(volMedia)
                .setVolumeIndex(4).build();
        mAudioService.setDeviceVolume(volIndex4, bleDevice, mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        verify(mSpyAudioSystem, atLeast(1)).setStreamVolumeIndexAS(
                STREAM_MUSIC, maxIndex,
                AudioSystem.DEVICE_OUT_BLE_HEADSET);
    }

    // ---------------- DeviceVolumeBehaviorTest ----------------
    @Test
    public void setDeviceVolumeBehavior_changesDeviceVolumeBehavior() {
        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        for (int behavior : BASIC_VOLUME_BEHAVIORS) {
            mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT, behavior,
                    mContext.getOpPackageName());
            mTestLooper.dispatchAll();

            int actualBehavior = mAudioService.getDeviceVolumeBehavior(DEVICE_SPEAKER_OUT);

            assertWithMessage("Expected volume behavior to be " + behavior
                    + " but was instead " + actualBehavior)
                    .that(actualBehavior).isEqualTo(behavior);
        }
    }

    @Test
    public void setToNewBehavior_triggersDeviceVolumeBehaviorDispatcher() {
        TestDeviceVolumeBehaviorDispatcherStub dispatcher =
                new TestDeviceVolumeBehaviorDispatcherStub();
        mAudioService.registerDeviceVolumeBehaviorDispatcher(true, dispatcher);

        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        for (int behavior : BASIC_VOLUME_BEHAVIORS) {
            dispatcher.reset();
            mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT, behavior,
                    mContext.getOpPackageName());
            mTestLooper.dispatchAll();

            assertThat(dispatcher.mTimesCalled).isEqualTo(1);
            assertThat(dispatcher.mDevice).isEqualTo(DEVICE_SPEAKER_OUT);
            assertWithMessage("Expected dispatched volume behavior to be " + behavior
                    + " but was instead " + dispatcher.mVolumeBehavior)
                    .that(dispatcher.mVolumeBehavior).isEqualTo(behavior);
        }
    }

    @Test
    public void setToSameBehavior_doesNotTriggerDeviceVolumeBehaviorDispatcher() {
        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        TestDeviceVolumeBehaviorDispatcherStub dispatcher =
                new TestDeviceVolumeBehaviorDispatcherStub();
        mAudioService.registerDeviceVolumeBehaviorDispatcher(true, dispatcher);

        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, mContext.getOpPackageName());
        mTestLooper.dispatchAll();
        assertThat(dispatcher.mTimesCalled).isEqualTo(0);
    }

    @Test
    public void unregisterDeviceVolumeBehaviorDispatcher_noLongerTriggered() {
        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, mContext.getOpPackageName());
        mTestLooper.dispatchAll();

        TestDeviceVolumeBehaviorDispatcherStub dispatcher =
                new TestDeviceVolumeBehaviorDispatcherStub();
        mAudioService.registerDeviceVolumeBehaviorDispatcher(true, dispatcher);
        mAudioService.registerDeviceVolumeBehaviorDispatcher(false, dispatcher);

        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL, mContext.getOpPackageName());
        mTestLooper.dispatchAll();
        assertThat(dispatcher.mTimesCalled).isEqualTo(0);
    }

    @Test
    public void setDeviceVolumeBehavior_checkIsVolumeFixed() throws Exception {
        when(mSpyAudioSystem.getDevicesForAttributes(any(), anyBoolean())).thenReturn(
                new ArrayList<>(List.of(DEVICE_SPEAKER_OUT)));

        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, mContext.getOpPackageName());

        assertTrue(mAudioService.isVolumeFixed());
    }

    private int circularNoMinMaxIncrementVolume(int streamType) throws Exception {
        final int streamMinVolume = mAm.getStreamMinVolume(streamType) + 1;
        final int streamMaxVolume = mAm.getStreamMaxVolume(streamType) - 1;

        int streamVolume = mAudioService.getStreamVolume(streamType);
        if (streamVolume + 1 > streamMaxVolume) {
            return streamMinVolume;
        }
        return streamVolume + 1;
    }
}
