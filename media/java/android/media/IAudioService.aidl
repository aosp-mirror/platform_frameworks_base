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
import android.content.AttributionSource;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFormat;
import android.media.AudioFocusInfo;
import android.media.AudioHalVersionInfo;
import android.media.AudioMixerAttributes;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRoutesInfo;
import android.media.BluetoothProfileConnectionInfo;
import android.media.FadeManagerConfiguration;
import android.media.IAudioDeviceVolumeDispatcher;
import android.media.IAudioFocusDispatcher;
import android.media.IAudioModeDispatcher;
import android.media.IAudioRoutesObserver;
import android.media.IAudioServerStateDispatcher;
import android.media.ICapturePresetDevicesRoleDispatcher;
import android.media.ICommunicationDeviceDispatcher;
import android.media.IDeviceVolumeBehaviorDispatcher;
import android.media.IDevicesForAttributesCallback;
import android.media.ILoudnessCodecUpdatesDispatcher;
import android.media.IMuteAwaitConnectionCallback;
import android.media.IPlaybackConfigDispatcher;
import android.media.IPreferredMixerAttributesDispatcher;
import android.media.IRecordingConfigDispatcher;
import android.media.IRingtonePlayer;
import android.media.IStrategyPreferredDevicesDispatcher;
import android.media.IStrategyNonDefaultDevicesDispatcher;
import android.media.ISpatializerCallback;
import android.media.ISpatializerHeadTrackerAvailableCallback;
import android.media.ISpatializerHeadTrackingModeCallback;
import android.media.ISpatializerHeadToSoundStagePoseCallback;
import android.media.ISpatializerOutputCallback;
import android.media.IStreamAliasingDispatcher;
import android.media.IVolumeController;
import android.media.LoudnessCodecInfo;
import android.media.PlayerBase;
import android.media.VolumeInfo;
import android.media.VolumePolicy;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicyConfig;
import android.media.audiopolicy.AudioProductStrategy;
import android.media.audiopolicy.AudioVolumeGroup;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.media.projection.IMediaProjection;
import android.net.Uri;
import android.os.PersistableBundle;
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

    oneway void playerEvent(in int piid, in int event, in int eventId);

    oneway void releasePlayer(in int piid);

    int trackRecorder(in IBinder recorder);

    oneway void recorderEvent(in int riid, in int event);

    oneway void releaseRecorder(in int riid);

    oneway void playerSessionId(in int piid, in int sessionId);

    oneway void portEvent(in int portId, in int event, in @nullable PersistableBundle extras);

    // Java-only methods below.
    void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage);

    void adjustStreamVolumeWithAttribution(int streamType, int direction, int flags,
            in String callingPackage, in String attributionTag);

    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setStreamVolume(int streamType, int index, int flags, String callingPackage);

    void setStreamVolumeWithAttribution(int streamType, int index, int flags,
            in String callingPackage, in String attributionTag);

    @EnforcePermission(anyOf = {"MODIFY_AUDIO_ROUTING", "MODIFY_AUDIO_SETTINGS_PRIVILEGED"})
    void setDeviceVolume(in VolumeInfo vi, in AudioDeviceAttributes ada,
            in String callingPackage);

    @EnforcePermission(anyOf = {"MODIFY_AUDIO_ROUTING", "MODIFY_AUDIO_SETTINGS_PRIVILEGED"})
    VolumeInfo getDeviceVolume(in VolumeInfo vi, in AudioDeviceAttributes ada,
            in String callingPackage);

    oneway void handleVolumeKey(in KeyEvent event, boolean isOnTv,
            String callingPackage, String caller);

    boolean isStreamMute(int streamType);

    void forceRemoteSubmixFullVolume(boolean startForcing, IBinder cb);

    boolean isMasterMute();

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    void setMasterMute(boolean mute, int flags, String callingPackage, int userId,
            in String attributionTag);

    @UnsupportedAppUsage
    int getStreamVolume(int streamType);

    int getStreamMinVolume(int streamType);

    @UnsupportedAppUsage
    int getStreamMaxVolume(int streamType);

    @EnforcePermission(anyOf={"MODIFY_AUDIO_SETTINGS_PRIVILEGED", "MODIFY_AUDIO_ROUTING"})
    List<AudioVolumeGroup> getAudioVolumeGroups();

    @EnforcePermission(anyOf={"MODIFY_AUDIO_SETTINGS_PRIVILEGED", "MODIFY_AUDIO_ROUTING"})
    void setVolumeGroupVolumeIndex(int groupId, int index, int flags, String callingPackage,
            in String attributionTag);

    @EnforcePermission(anyOf={"MODIFY_AUDIO_SETTINGS_PRIVILEGED", "MODIFY_AUDIO_ROUTING"})
    int getVolumeGroupVolumeIndex(int groupId);

    @EnforcePermission(anyOf={"MODIFY_AUDIO_SETTINGS_PRIVILEGED", "MODIFY_AUDIO_ROUTING"})
    int getVolumeGroupMaxVolumeIndex(int groupId);

    @EnforcePermission(anyOf={"MODIFY_AUDIO_SETTINGS_PRIVILEGED", "MODIFY_AUDIO_ROUTING"})
    int getVolumeGroupMinVolumeIndex(int groupId);

    @EnforcePermission("QUERY_AUDIO_STATE")
    int getLastAudibleVolumeForVolumeGroup(int groupId);

    boolean isVolumeGroupMuted(int groupId);

    void adjustVolumeGroupVolume(int groupId, int direction, int flags, String callingPackage);

    @EnforcePermission("QUERY_AUDIO_STATE")
    int getLastAudibleStreamVolume(int streamType);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    void setSupportedSystemUsages(in int[] systemUsages);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    int[] getSupportedSystemUsages();

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    List<AudioProductStrategy> getAudioProductStrategies();

    boolean isMicrophoneMuted();

    @EnforcePermission("ACCESS_ULTRASOUND")
    boolean isUltrasoundSupported();

    @EnforcePermission("CAPTURE_AUDIO_HOTWORD")
    boolean isHotwordStreamSupported(boolean lookbackAudio);

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

    @EnforcePermission("WRITE_SETTINGS")
    boolean setEncodedSurroundMode(int mode);

    int getEncodedSurroundMode(int targetSdkVersion);

    void setSpeakerphoneOn(IBinder cb, boolean on);

    boolean isSpeakerphoneOn();

    void setBluetoothScoOn(boolean on);

    @EnforcePermission("BLUETOOTH_STACK")
    void setA2dpSuspended(boolean on);

    @EnforcePermission("BLUETOOTH_STACK")
    void setLeAudioSuspended(boolean enable);

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

    @EnforcePermission("REMOTE_AUDIO_PLAYBACK")
    void setRingtonePlayer(IRingtonePlayer player);
    IRingtonePlayer getRingtonePlayer();
    int getUiSoundsStreamType();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    List getIndependentStreamTypes();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    int getStreamTypeAlias(int streamType);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    boolean isVolumeControlUsingVolumeGroups();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    void registerStreamAliasingDispatcher(IStreamAliasingDispatcher isad, boolean register);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    void setNotifAliasRingForTest(boolean alias);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    void setWiredDeviceConnectionState(in AudioDeviceAttributes aa, int state, String caller);

    @UnsupportedAppUsage
    AudioRoutesInfo startWatchingRoutes(in IAudioRoutesObserver observer);

    boolean isCameraSoundForced();

    void setVolumeController(in IVolumeController controller);

    @nullable IVolumeController getVolumeController();

    void notifyVolumeControllerVisible(in IVolumeController controller, boolean visible);

    boolean isStreamAffectedByRingerMode(int streamType);

    boolean isStreamAffectedByMute(int streamType);

    void disableSafeMediaVolume(String callingPackage);

    oneway void lowerVolumeToRs1(String callingPackage);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    float getOutputRs2UpperBound();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    oneway void setOutputRs2UpperBound(float rs2Value);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    float getCsd();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    oneway void setCsd(float csd);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    oneway void forceUseFrameworkMel(boolean useFrameworkMel);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    oneway void forceComputeCsdOnAllDevices(boolean computeCsdOnAllDevices);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    boolean isCsdEnabled();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    boolean isCsdAsAFeatureAvailable();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    boolean isCsdAsAFeatureEnabled();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    oneway void setCsdAsAFeatureEnabled(boolean csdToggleValue);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    oneway void setBluetoothAudioDeviceCategory_legacy(in String address, boolean isBle,
            int deviceCategory);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    int getBluetoothAudioDeviceCategory_legacy(in String address, boolean isBle);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    boolean setBluetoothAudioDeviceCategory(in String address, int deviceCategory);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    int getBluetoothAudioDeviceCategory(in String address);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    boolean isBluetoothAudioDeviceCategoryFixed(in String address);

    int setHdmiSystemAudioSupported(boolean on);

    boolean isHdmiSystemAudioSupported();

    String registerAudioPolicy(in AudioPolicyConfig policyConfig,
            in IAudioPolicyCallback pcb, boolean hasFocusListener, boolean isFocusPolicy,
            boolean isTestFocusPolicy,
            boolean isVolumeController, in IMediaProjection projection,
            in AttributionSource attributionSource);

    oneway void unregisterAudioPolicyAsync(in IAudioPolicyCallback pcb);

    List<AudioMix> getRegisteredPolicyMixes();

    void unregisterAudioPolicy(in IAudioPolicyCallback pcb);

    int addMixForPolicy(in AudioPolicyConfig policyConfig, in IAudioPolicyCallback pcb);

    int removeMixForPolicy(in AudioPolicyConfig policyConfig, in IAudioPolicyCallback pcb);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    int updateMixingRulesForPolicy(in AudioMix[] mixesToUpdate,
                                   in AudioMixingRule[] updatedMixingRules,
                                   in IAudioPolicyCallback pcb);

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

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)")
    int dispatchFocusChangeWithFade(in AudioFocusInfo afi,
            in int focusChange,
            in IAudioPolicyCallback pcb,
            in List<AudioFocusInfo> otherActiveAfis,
            in FadeManagerConfiguration transientFadeMgrConfig);

    oneway void playerHasOpPlayAudio(in int piid, in boolean hasOpPlayAudio);

    @EnforcePermission("BLUETOOTH_STACK")
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

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    int setPreferredDevicesForStrategy(in int strategy, in List<AudioDeviceAttributes> devices);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    int removePreferredDevicesForStrategy(in int strategy);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    List<AudioDeviceAttributes> getPreferredDevicesForStrategy(in int strategy);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    int setDeviceAsNonDefaultForStrategy(in int strategy, in AudioDeviceAttributes device);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    int removeDeviceAsNonDefaultForStrategy(in int strategy, in AudioDeviceAttributes device);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    List<AudioDeviceAttributes> getNonDefaultDevicesForStrategy(in int strategy);

    List<AudioDeviceAttributes> getDevicesForAttributes(in AudioAttributes attributes);

    List<AudioDeviceAttributes> getDevicesForAttributesUnprotected(in AudioAttributes attributes);

    void addOnDevicesForAttributesChangedListener(in AudioAttributes attributes,
            in IDevicesForAttributesCallback callback);

    oneway void removeOnDevicesForAttributesChangedListener(
            in IDevicesForAttributesCallback callback);

    int setAllowedCapturePolicy(in int capturePolicy);

    int getAllowedCapturePolicy();

    void registerStrategyPreferredDevicesDispatcher(IStrategyPreferredDevicesDispatcher dispatcher);

    oneway void unregisterStrategyPreferredDevicesDispatcher(
            IStrategyPreferredDevicesDispatcher dispatcher);

    void registerStrategyNonDefaultDevicesDispatcher(
            IStrategyNonDefaultDevicesDispatcher dispatcher);

    oneway void unregisterStrategyNonDefaultDevicesDispatcher(
            IStrategyNonDefaultDevicesDispatcher dispatcher);

    oneway void setRttEnabled(in boolean rttEnabled);

    @EnforcePermission(anyOf = {"MODIFY_AUDIO_ROUTING", "MODIFY_AUDIO_SETTINGS_PRIVILEGED"})
    void setDeviceVolumeBehavior(in AudioDeviceAttributes device,
             in int deviceVolumeBehavior, in String pkgName);

    @EnforcePermission(anyOf = {"MODIFY_AUDIO_ROUTING", "QUERY_AUDIO_STATE", "MODIFY_AUDIO_SETTINGS_PRIVILEGED"})
    int getDeviceVolumeBehavior(in AudioDeviceAttributes device);

    // WARNING: read warning at top of file, new methods that need to be used by native
    // code via IAudioManager.h need to be added to the top section.

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    oneway void setMultiAudioFocusEnabled(in boolean enabled);

    int setPreferredDevicesForCapturePreset(
            in int capturePreset, in List<AudioDeviceAttributes> devices);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    int clearPreferredDevicesForCapturePreset(in int capturePreset);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
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

    oneway void adjustVolume(int direction, int flags);

    oneway void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags);

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

    @EnforcePermission("QUERY_AUDIO_STATE")
    /* Returns a List<Integer> */
    @SuppressWarnings(value = {"untyped-collection"})
    List getFocusDuckedUidsForTest();

    @EnforcePermission("QUERY_AUDIO_STATE")
    long getFocusFadeOutDurationForTest();

    @EnforcePermission("QUERY_AUDIO_STATE")
    long getFocusUnmuteDelayAfterFadeOutForTest();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    boolean enterAudioFocusFreezeForTest(IBinder cb, in int[] uids);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    boolean exitAudioFocusFreezeForTest(IBinder cb);

    void registerModeDispatcher(IAudioModeDispatcher dispatcher);

    oneway void unregisterModeDispatcher(IAudioModeDispatcher dispatcher);

    int getSpatializerImmersiveAudioLevel();

    boolean isSpatializerEnabled();

    boolean isSpatializerAvailable();

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    boolean isSpatializerAvailableForDevice(in AudioDeviceAttributes device);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    boolean hasHeadTracker(in AudioDeviceAttributes device);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void setHeadTrackerEnabled(boolean enabled, in AudioDeviceAttributes device);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    boolean isHeadTrackerEnabled(in AudioDeviceAttributes device);

    boolean isHeadTrackerAvailable();

    void registerSpatializerHeadTrackerAvailableCallback(
            in ISpatializerHeadTrackerAvailableCallback cb, boolean register);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void setSpatializerEnabled(boolean enabled);

    boolean canBeSpatialized(in AudioAttributes aa, in AudioFormat af);

    void registerSpatializerCallback(in ISpatializerCallback cb);

    void unregisterSpatializerCallback(in ISpatializerCallback cb);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void registerSpatializerHeadTrackingCallback(in ISpatializerHeadTrackingModeCallback cb);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void unregisterSpatializerHeadTrackingCallback(in ISpatializerHeadTrackingModeCallback cb);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void registerHeadToSoundstagePoseCallback(in ISpatializerHeadToSoundStagePoseCallback cb);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void unregisterHeadToSoundstagePoseCallback(in ISpatializerHeadToSoundStagePoseCallback cb);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    List<AudioDeviceAttributes> getSpatializerCompatibleAudioDevices();

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void addSpatializerCompatibleAudioDevice(in AudioDeviceAttributes ada);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void removeSpatializerCompatibleAudioDevice(in AudioDeviceAttributes ada);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void setDesiredHeadTrackingMode(int mode);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    int getDesiredHeadTrackingMode();

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    int[] getSupportedHeadTrackingModes();

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    int getActualHeadTrackingMode();

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    oneway void setSpatializerGlobalTransform(in float[] transform);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    oneway void recenterHeadTracker();

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void setSpatializerParameter(int key, in byte[] value);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void getSpatializerParameter(int key, inout byte[] value);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    int getSpatializerOutput();

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void registerSpatializerOutputCallback(in ISpatializerOutputCallback cb);

    @EnforcePermission("MODIFY_DEFAULT_AUDIO_EFFECTS")
    void unregisterSpatializerOutputCallback(in ISpatializerOutputCallback cb);

    boolean isVolumeFixed();

    VolumeInfo getDefaultVolumeInfo();

    @EnforcePermission("CALL_AUDIO_INTERCEPTION")
    boolean isPstnCallAudioInterceptable();

    oneway void muteAwaitConnection(in int[] usagesToMute, in AudioDeviceAttributes dev,
            long timeOutMs);

    oneway void cancelMuteAwaitConnection(in AudioDeviceAttributes dev);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    AudioDeviceAttributes getMutingExpectedDevice();

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    void registerMuteAwaitConnectionDispatcher(in IMuteAwaitConnectionCallback cb,
            boolean register);

    void setTestDeviceConnectionState(in AudioDeviceAttributes device, boolean connected);

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf={android.Manifest.permission.MODIFY_AUDIO_ROUTING,android.Manifest.permission.QUERY_AUDIO_STATE})")
    void registerDeviceVolumeBehaviorDispatcher(boolean register,
            in IDeviceVolumeBehaviorDispatcher dispatcher);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    List<AudioFocusInfo> getFocusStack();

    boolean sendFocusLoss(in AudioFocusInfo focusLoser, in IAudioPolicyCallback apcb);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    void addAssistantServicesUids(in int[] assistantUID);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    void removeAssistantServicesUids(in int[] assistantUID);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    void setActiveAssistantServiceUids(in int[] activeUids);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    int[] getAssistantServicesUids();

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    int[] getActiveAssistantServiceUids();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(anyOf={android.Manifest.permission.MODIFY_AUDIO_ROUTING,android.Manifest.permission.BLUETOOTH_PRIVILEGED})")
    void registerDeviceVolumeDispatcherForAbsoluteVolume(boolean register,
            in IAudioDeviceVolumeDispatcher cb,
            in String packageName,
            in AudioDeviceAttributes device, in List<VolumeInfo> volumes,
            boolean handlesvolumeAdjustment,
            int volumeBehavior);

    AudioHalVersionInfo getHalVersion();

    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)")
    int setPreferredMixerAttributes(
            in AudioAttributes aa, int portId, in AudioMixerAttributes mixerAttributes);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)")
    int clearPreferredMixerAttributes(in AudioAttributes aa, int portId);
    void registerPreferredMixerAttributesDispatcher(
            IPreferredMixerAttributesDispatcher dispatcher);
    oneway void unregisterPreferredMixerAttributesDispatcher(
            IPreferredMixerAttributesDispatcher dispatcher);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    boolean supportsBluetoothVariableLatency();

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    void setBluetoothVariableLatencyEnabled(boolean enabled);

    @EnforcePermission("MODIFY_AUDIO_ROUTING")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING)")
    boolean isBluetoothVariableLatencyEnabled();

    void registerLoudnessCodecUpdatesDispatcher(in ILoudnessCodecUpdatesDispatcher dispatcher);

    void unregisterLoudnessCodecUpdatesDispatcher(in ILoudnessCodecUpdatesDispatcher dispatcher);

    oneway void startLoudnessCodecUpdates(int sessionId);

    oneway void stopLoudnessCodecUpdates(int sessionId);

    oneway void addLoudnessCodecInfo(int sessionId, int mediaCodecHash,
            in LoudnessCodecInfo codecInfo);

    oneway void removeLoudnessCodecInfo(int sessionId, in LoudnessCodecInfo codecInfo);

    PersistableBundle getLoudnessParams(in LoudnessCodecInfo codecInfo);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)")
    int setFadeManagerConfigurationForFocusLoss(in FadeManagerConfiguration fmcForFocusLoss);

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)")
    int clearFadeManagerConfigurationForFocusLoss();

    @EnforcePermission("MODIFY_AUDIO_SETTINGS_PRIVILEGED")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS_PRIVILEGED)")
    FadeManagerConfiguration getFadeManagerConfigurationForFocusLoss();

    @EnforcePermission("QUERY_AUDIO_STATE")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.QUERY_AUDIO_STATE)")
    boolean shouldNotificationSoundPlay(in AudioAttributes aa);
}
