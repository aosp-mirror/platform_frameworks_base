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
import android.media.BluetoothProfileConnectionInfo;
import android.media.IAudioDeviceVolumeDispatcher;
import android.media.IAudioFocusDispatcher;
import android.media.IAudioModeDispatcher;
import android.media.IAudioRoutesObserver;
import android.media.IAudioServerStateDispatcher;
import android.media.ICapturePresetDevicesRoleDispatcher;
import android.media.ICommunicationDeviceDispatcher;
import android.media.IDeviceVolumeBehaviorDispatcher;
import android.media.IMuteAwaitConnectionCallback;
import android.media.IPlaybackConfigDispatcher;
import android.media.IRecordingConfigDispatcher;
import android.media.IRingtonePlayer;
import android.media.IStrategyPreferredDevicesDispatcher;
import android.media.ISpatializerCallback;
import android.media.ISpatializerHeadTrackerAvailableCallback;
import android.media.ISpatializerHeadTrackingModeCallback;
import android.media.ISpatializerHeadToSoundStagePoseCallback;
import android.media.ISpatializerOutputCallback;
import android.media.IVolumeController;
import android.media.PlayerBase;
import android.media.VolumeInfo;
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
    void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage);

    void adjustStreamVolumeWithAttribution(int streamType, int direction, int flags,
            in String callingPackage, in String attributionTag);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setStreamVolume(int streamType, int index, int flags, String callingPackage);

    void setStreamVolumeWithAttribution(int streamType, int index, int flags,
            in String callingPackage, in String attributionTag);

    oneway void handleVolumeKey(in KeyEvent event, boolean isOnTv,
            String callingPackage, String caller);

    boolean isStreamMute(int streamType);

    void forceRemoteSubmixFullVolume(boolean startForcing, IBinder cb);

    boolean isMasterMute();

    void setMasterMute(boolean mute, int flags, String callingPackage, int userId,
            in String attributionTag);

    @UnsupportedAppUsage
    int getStreamVolume(int streamType);

    int getStreamMinVolume(int streamType);

    @UnsupportedAppUsage
    int getStreamMaxVolume(int streamType);

    List<AudioVolumeGroup> getAudioVolumeGroups();

    void setVolumeIndexForAttributes(in AudioAttributes aa, int index, int flags,
            String callingPackage, in String attributionTag);

    int getVolumeIndexForAttributes(in AudioAttributes aa);

    int getMaxVolumeIndexForAttributes(in AudioAttributes aa);

    int getMinVolumeIndexForAttributes(in AudioAttributes aa);

    int getLastAudibleStreamVolume(int streamType);

    void setSupportedSystemUsages(in int[] systemUsages);

    int[] getSupportedSystemUsages();

    List<AudioProductStrategy> getAudioProductStrategies();

    boolean isMicrophoneMuted();

    boolean isUltrasoundSupported();

    void setMicrophoneMute(boolean on, String callingPackage, int userId, in String attributionTag);

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

    oneway void playSoundEffect(int effectType, int userId);

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

    void setSpeakerphoneOn(IBinder cb, boolean on);

    boolean isSpeakerphoneOn();

    void setBluetoothScoOn(boolean on);

    boolean isBluetoothScoOn();

    void setBluetoothA2dpOn(boolean on);

    boolean isBluetoothA2dpOn();

    int requestAudioFocus(in AudioAttributes aa, int durationHint, IBinder cb,
            IAudioFocusDispatcher fd, in String clientId, in String callingPackageName,
            in String attributionTag, int flags, IAudioPolicyCallback pcb, int sdk);

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

    void setWiredDeviceConnectionState(in AudioDeviceAttributes aa, int state, String caller);

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

    int getFocusRampTimeMs(in int focusGain, in AudioAttributes attr);

    int dispatchFocusChange(in AudioFocusInfo afi, in int focusChange,
            in IAudioPolicyCallback pcb);

    oneway void playerHasOpPlayAudio(in int piid, in boolean hasOpPlayAudio);

    void handleBluetoothActiveDeviceChanged(in BluetoothDevice newDevice,
            in BluetoothDevice previousDevice, in BluetoothProfileConnectionInfo info);

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

    List<AudioDeviceAttributes> getDevicesForAttributesUnprotected(in AudioAttributes attributes);

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

    int getDeviceMaskForStream(in int streamType);

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
            int flags, int uid, int sdk);

    int abandonAudioFocusForTest(in IAudioFocusDispatcher fd, in String clientId,
            in AudioAttributes aa, in String callingPackageName);

    long getFadeOutDurationOnFocusLossMillis(in AudioAttributes aa);

    void registerModeDispatcher(IAudioModeDispatcher dispatcher);

    oneway void unregisterModeDispatcher(IAudioModeDispatcher dispatcher);

    int getSpatializerImmersiveAudioLevel();

    boolean isSpatializerEnabled();

    boolean isSpatializerAvailable();

    boolean isSpatializerAvailableForDevice(in AudioDeviceAttributes device);

    boolean hasHeadTracker(in AudioDeviceAttributes device);

    void setHeadTrackerEnabled(boolean enabled, in AudioDeviceAttributes device);

    boolean isHeadTrackerEnabled(in AudioDeviceAttributes device);

    boolean isHeadTrackerAvailable();

    void registerSpatializerHeadTrackerAvailableCallback(
            in ISpatializerHeadTrackerAvailableCallback cb, boolean register);

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

    void setSpatializerParameter(int key, in byte[] value);

    void getSpatializerParameter(int key, inout byte[] value);

    int getSpatializerOutput();

    void registerSpatializerOutputCallback(in ISpatializerOutputCallback cb);

    void unregisterSpatializerOutputCallback(in ISpatializerOutputCallback cb);

    boolean isVolumeFixed();

    VolumeInfo getDefaultVolumeInfo();

    boolean isPstnCallAudioInterceptable();

    oneway void muteAwaitConnection(in int[] usagesToMute, in AudioDeviceAttributes dev,
            long timeOutMs);

    oneway void cancelMuteAwaitConnection(in AudioDeviceAttributes dev);

    AudioDeviceAttributes getMutingExpectedDevice();

    void registerMuteAwaitConnectionDispatcher(in IMuteAwaitConnectionCallback cb,
            boolean register);

    void setTestDeviceConnectionState(in AudioDeviceAttributes device, boolean connected);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf={android.Manifest.permission.MODIFY_AUDIO_ROUTING,android.Manifest.permission.QUERY_AUDIO_STATE})")
    void registerDeviceVolumeBehaviorDispatcher(boolean register,
            in IDeviceVolumeBehaviorDispatcher dispatcher);

    List<AudioFocusInfo> getFocusStack();

    boolean sendFocusLoss(in AudioFocusInfo focusLoser, in IAudioPolicyCallback apcb);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    void addAssistantServicesUids(in int[] assistantUID);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    void removeAssistantServicesUids(in int[] assistantUID);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    void setActiveAssistantServiceUids(in int[] activeUids);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    int[] getAssistantServicesUids();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    int[] getActiveAssistantServiceUids();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    void registerDeviceVolumeDispatcherForAbsoluteVolume(boolean register,
            in IAudioDeviceVolumeDispatcher cb,
            in String packageName,
            in AudioDeviceAttributes device, in List<VolumeInfo> volumes,
            boolean handlesvolumeAdjustment);

    String getHalVersion();
}
