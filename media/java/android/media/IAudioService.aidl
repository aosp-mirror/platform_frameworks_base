/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.media;

import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFormat;
import android.media.AudioFocusInfo;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRoutesInfo;
import android.media.IAudioFocusDispatcher;
import android.media.IAudioModeDispatcher;
import android.media.IAudioRoutesObserver;
import android.media.IAudioServerStateDispatcher;
import android.media.ICapturePresetDevicesRoleDispatcher;
import android.media.ICommunicationDeviceDispatcher;
import android.media.IPlaybackConfigDispatcher;
import android.media.IRecordingConfigDispatcher;
import android.media.IRingtonePlayer;
import android.media.IStrategyPreferredDevicesDispatcher;
import android.media.ISpatializerCallback;
import android.media.ISpatializerHeadTrackingModeCallback;
import android.media.ISpatializerHeadToSoundStagePoseCallback;
import android.media.IVolumeController;
import android.media.IVolumeController;
import android.media.PlayerBase;
import android.media.VolumePolicy;
import android.media.audiopolicy.AudioPolicyConfig;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.media.projection.IMediaProjection;
import android.net.Uri;
import android.os.UserHandle;
import android.view.KeyEvent;

/**
 * {@hide}
 */
interface IAudioService {
    // C++ and Java methods below.

    // WARNING: When methods are inserted or deleted in this section, the transaction IDs in
    // frameworks/native/include/audiomanager/IAudioManager.h must be updated to match the order
    // in this file.
    //
    // When a method's argument list is changed, BpAudioManager's corresponding serialization code
    // (if any) in frameworks/native/services/audiomanager/IAudioManager.cpp must be updated.

    int trackPlayer(in PlayerBase.PlayerIdCard pic);

    oneway void playerAttributes(in int piid, in AudioAttributes attr);

    oneway void playerEvent(in int piid, in int event, in int deviceId);

    oneway void releasePlayer(in int piid);

    int trackRecorder(in IBinder recorder);

    oneway void recorderEvent(in int riid, in int event);

    oneway void releaseRecorder(in int riid);

    oneway void playerSessionId(in int piid, in int sessionId);

    // Java-only methods below.

    oneway void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags,
            String callingPackage, String caller);

    void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setStreamVolume(int streamType, int index, int flags, String callingPackage);

    oneway void handleVolumeKey(in KeyEvent event, boolean isOnTv,
            String callingPackage, String caller);

    boolean isStreamMute(int streamType);

    void forceRemoteSubmixFullVolume(boolean startForcing, IBinder cb);

    boolean isMasterMute();

    void setMasterMute(boolean mute, int flags, String callingPackage, int userId);

    @UnsupportedAppUsage
    int getStreamVolume(int streamType);

    int getStreamMinVolume(int streamType);

    @UnsupportedAppUsage
    int getStreamMaxVolume(int streamType);

    List<AudioVolumeGroup> getAudioVolumeGroups();

    void setVolumeIndexForAttributes(in AudioAttributes aa, int index, int flags, String callingPackage);

    int getVolumeIndexForAttributes(in AudioAttributes aa);

    int getMaxVolumeIndexForAttributes(in AudioAttributes aa);

    int getMinVolumeIndexForAttributes(in AudioAttributes aa);

    int getLastAudibleStreamVolume(int streamType);

    void setSupportedSystemUsages(in int[] systemUsages);

    int[] getSupportedSystemUsages();

    List<AudioProductStrategy> getAudioProductStrategies();

    boolean isMicrophoneMuted();

    void setMicrophoneMute(boolean on, String callingPackage, int userId);

    oneway void setMicrophoneMuteFromSwitch(boolean on);

    void setRingerModeExternal(int ringerMode, String caller);

    void setRingerModeInternal(int ringerMode, String caller);

    int getRingerModeExternal();

    int getRingerModeInternal();

    boolean isValidRingerMode(int ringerMode);

    void setVibrateSetting(int vibrateType, int vibrateSetting);

    int getVibrateSetting(int vibrateType);

    boolean shouldVibrate(int vibrateType);

    void setMode(int mode, IBinder cb, String callingPackage);

    int getMode();

    oneway void playSoundEffect(int effectType);

    oneway void playSoundEffectVolume(int effectType, float volume);

    boolean loadSoundEffects();

    oneway void unloadSoundEffects();

    oneway void reloadAudioSettings();

    Map getSurroundFormats();

    List getReportedSurroundFormats();

    boolean setSurroundFormatEnabled(int audioFormat, boolean enabled);

    boolean isSurroundFormatEnabled(int audioFormat);

    boolean setEncodedSurroundMode(int mode);

    int getEncodedSurroundMode(int targetSdkVersion);

    oneway void avrcpSupportsAbsoluteVolume(String address, boolean support);

    void setSpeakerphoneOn(IBinder cb, boolean on);

    boolean isSpeakerphoneOn();

    void setBluetoothScoOn(boolean on);

    boolean isBluetoothScoOn();

    void setBluetoothA2dpOn(boolean on);

    boolean isBluetoothA2dpOn();

    int requestAudioFocus(in AudioAttributes aa, int durationHint, IBinder cb,
            IAudioFocusDispatcher fd, String clientId, String callingPackageName, int flags,
            IAudioPolicyCallback pcb, int sdk);

    int abandonAudioFocus(IAudioFocusDispatcher fd, String clientId, in AudioAttributes aa,
            in String callingPackageName);

    void unregisterAudioFocusClient(String clientId);

    int getCurrentAudioFocus();

    void startBluetoothSco(IBinder cb, int targetSdkVersion);
    void startBluetoothScoVirtualCall(IBinder cb);
    void stopBluetoothSco(IBinder cb);

    void forceVolumeControlStream(int streamType, IBinder cb);

    void setRingtonePlayer(IRingtonePlayer player);
    IRingtonePlayer getRingtonePlayer();
    int getUiSoundsStreamType();

    void setWiredDeviceConnectionState(int type, int state, String address, String name,
            String caller);

    void handleBluetoothA2dpDeviceConfigChange(in BluetoothDevice device);

    @UnsupportedAppUsage
    AudioRoutesInfo startWatchingRoutes(in IAudioRoutesObserver observer);

    boolean isCameraSoundForced();

    void setVolumeController(in IVolumeController controller);

    void notifyVolumeControllerVisible(in IVolumeController controller, boolean visible);

    boolean isStreamAffectedByRingerMode(int streamType);

    boolean isStreamAffectedByMute(int streamType);

    void disableSafeMediaVolume(String callingPackage);

    int setHdmiSystemAudioSupported(boolean on);

    boolean isHdmiSystemAudioSupported();

    String registerAudioPolicy(in AudioPolicyConfig policyConfig,
            in IAudioPolicyCallback pcb, boolean hasFocusListener, boolean isFocusPolicy,
            boolean isTestFocusPolicy,
            boolean isVolumeController, in IMediaProjection projection);

    oneway void unregisterAudioPolicyAsync(in IAudioPolicyCallback pcb);

    void unregisterAudioPolicy(in IAudioPolicyCallback pcb);

    int addMixForPolicy(in AudioPolicyConfig policyConfig, in IAudioPolicyCallback pcb);

    int removeMixForPolicy(in AudioPolicyConfig policyConfig, in IAudioPolicyCallback pcb);

    int setFocusPropertiesForPolicy(int duckingBehavior, in IAudioPolicyCallback pcb);

    void setVolumePolicy(in VolumePolicy policy);

    boolean hasRegisteredDynamicPolicy();

    void registerRecordingCallback(in IRecordingConfigDispatcher rcdb);

    oneway void unregisterRecordingCallback(in IRecordingConfigDispatcher rcdb);

    List<AudioRecordingConfiguration> getActiveRecordingConfigurations();

    void registerPlaybackCallback(in IPlaybackConfigDispatcher pcdb);

    oneway void unregisterPlaybackCallback(in IPlaybackConfigDispatcher pcdb);

    List<AudioPlaybackConfiguration> getActivePlaybackConfigurations();

    void disableRingtoneSync(in int userId);

    int getFocusRampTimeMs(in int focusGain, in AudioAttributes attr);

    int dispatchFocusChange(in AudioFocusInfo afi, in int focusChange,
            in IAudioPolicyCallback pcb);

    oneway void playerHasOpPlayAudio(in int piid, in boolean hasOpPlayAudio);

    void setBluetoothHearingAidDeviceConnectionState(in BluetoothDevice device,
            int state, boolean suppressNoisyIntent, int musicDevice);

    void setBluetoothA2dpDeviceConnectionStateSuppressNoisyIntent(in BluetoothDevice device,
            int state, int profile, boolean suppressNoisyIntent, int a2dpVolume);

    oneway void setFocusRequestResultFromExtPolicy(in AudioFocusInfo afi, int requestResult,
            in IAudioPolicyCallback pcb);

    void registerAudioServerStateDispatcher(IAudioServerStateDispatcher asd);

    oneway void unregisterAudioServerStateDispatcher(IAudioServerStateDispatcher asd);

    boolean isAudioServerRunning();

    int setUidDeviceAffinity(in IAudioPolicyCallback pcb, in int uid, in int[] deviceTypes,
             in String[] deviceAddresses);

    int removeUidDeviceAffinity(in IAudioPolicyCallback pcb, in int uid);

    int setUserIdDeviceAffinity(in IAudioPolicyCallback pcb, in int userId, in int[] deviceTypes,
             in String[] deviceAddresses);
    int removeUserIdDeviceAffinity(in IAudioPolicyCallback pcb, in int userId);

    boolean hasHapticChannels(in Uri uri);

    boolean isCallScreeningModeSupported();

    int setPreferredDevicesForStrategy(in int strategy, in List<AudioDeviceAttributes> device);

    int removePreferredDevicesForStrategy(in int strategy);

    List<AudioDeviceAttributes> getPreferredDevicesForStrategy(in int strategy);

    List<AudioDeviceAttributes> getDevicesForAttributes(in AudioAttributes attributes);

    int setAllowedCapturePolicy(in int capturePolicy);

    int getAllowedCapturePolicy();

    void registerStrategyPreferredDevicesDispatcher(IStrategyPreferredDevicesDispatcher dispatcher);

    oneway void unregisterStrategyPreferredDevicesDispatcher(
            IStrategyPreferredDevicesDispatcher dispatcher);

    oneway void setRttEnabled(in boolean rttEnabled);

    void setDeviceVolumeBehavior(in AudioDeviceAttributes device,
             in int deviceVolumeBehavior, in String pkgName);

    int getDeviceVolumeBehavior(in AudioDeviceAttributes device);

    // WARNING: read warning at top of file, new methods that need to be used by native
    // code via IAudioManager.h need to be added to the top section.

    oneway void setMultiAudioFocusEnabled(in boolean enabled);

    int setPreferredDevicesForCapturePreset(
            in int capturePreset, in List<AudioDeviceAttributes> devices);

    int clearPreferredDevicesForCapturePreset(in int capturePreset);

    List<AudioDeviceAttributes> getPreferredDevicesForCapturePreset(in int capturePreset);

    void registerCapturePresetDevicesRoleDispatcher(ICapturePresetDevicesRoleDispatcher dispatcher);

    oneway void unregisterCapturePresetDevicesRoleDispatcher(
            ICapturePresetDevicesRoleDispatcher dispatcher);

    oneway void adjustStreamVolumeForUid(int streamType, int direction, int flags,
            in String packageName, int uid, int pid, in UserHandle userHandle,
            int targetSdkVersion);

    oneway void adjustSuggestedStreamVolumeForUid(int streamType, int direction, int flags,
            in String packageName, int uid, int pid, in UserHandle userHandle,
            int targetSdkVersion);

    oneway void setStreamVolumeForUid(int streamType, int direction, int flags,
            in String packageName, int uid, int pid, in UserHandle userHandle,
            int targetSdkVersion);

    boolean isMusicActive(in boolean remotely);

    int getDevicesForStream(in int streamType);

    int[] getAvailableCommunicationDeviceIds();

    boolean setCommunicationDevice(IBinder cb, int portId);

    int getCommunicationDevice();

    void registerCommunicationDeviceDispatcher(ICommunicationDeviceDispatcher dispatcher);

    oneway void unregisterCommunicationDeviceDispatcher(
            ICommunicationDeviceDispatcher dispatcher);

    boolean areNavigationRepeatSoundEffectsEnabled();

    oneway void setNavigationRepeatSoundEffectsEnabled(boolean enabled);

    boolean isHomeSoundEffectEnabled();

    oneway void setHomeSoundEffectEnabled(boolean enabled);

    boolean setAdditionalOutputDeviceDelay(in AudioDeviceAttributes device, long delayMillis);

    long getAdditionalOutputDeviceDelay(in AudioDeviceAttributes device);

    long getMaxAdditionalOutputDeviceDelay(in AudioDeviceAttributes device);

    int requestAudioFocusForTest(in AudioAttributes aa, int durationHint, IBinder cb,
            in IAudioFocusDispatcher fd, in String clientId, in String callingPackageName,
            int uid, int sdk);

    int abandonAudioFocusForTest(in IAudioFocusDispatcher fd, in String clientId,
            in AudioAttributes aa, in String callingPackageName);

    long getFadeOutDurationOnFocusLossMillis(in AudioAttributes aa);

    void registerModeDispatcher(IAudioModeDispatcher dispatcher);

    oneway void unregisterModeDispatcher(IAudioModeDispatcher dispatcher);

    int getSpatializerImmersiveAudioLevel();

    boolean isSpatializerEnabled();

    boolean isSpatializerAvailable();

    void setSpatializerEnabled(boolean enabled);

    boolean canBeSpatialized(in AudioAttributes aa, in AudioFormat af);

    void registerSpatializerCallback(in ISpatializerCallback cb);

    void unregisterSpatializerCallback(in ISpatializerCallback cb);

    void registerSpatializerHeadTrackingCallback(in ISpatializerHeadTrackingModeCallback cb);

    void unregisterSpatializerHeadTrackingCallback(in ISpatializerHeadTrackingModeCallback cb);

    void registerHeadToSoundstagePoseCallback(in ISpatializerHeadToSoundStagePoseCallback cb);

    void unregisterHeadToSoundstagePoseCallback(in ISpatializerHeadToSoundStagePoseCallback cb);

    List<AudioDeviceAttributes> getSpatializerCompatibleAudioDevices();

    void addSpatializerCompatibleAudioDevice(in AudioDeviceAttributes ada);

    void removeSpatializerCompatibleAudioDevice(in AudioDeviceAttributes ada);

    void setDesiredHeadTrackingMode(int mode);

    int getDesiredHeadTrackingMode();

    int[] getSupportedHeadTrackingModes();

    int getActualHeadTrackingMode();

    oneway void setSpatializerGlobalTransform(in float[] transform);

    oneway void recenterHeadTracker();
}
