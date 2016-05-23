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

import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.media.AudioAttributes;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRoutesInfo;
import android.media.IAudioFocusDispatcher;
import android.media.IAudioRoutesObserver;
import android.media.IRecordingConfigDispatcher;
import android.media.IRingtonePlayer;
import android.media.IVolumeController;
import android.media.Rating;
import android.media.VolumePolicy;
import android.media.audiopolicy.AudioPolicyConfig;
import android.media.audiopolicy.IAudioPolicyCallback;
import android.net.Uri;
import android.view.KeyEvent;

/**
 * {@hide}
 */
interface IAudioService {

    oneway void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags,
            String callingPackage, String caller);

    void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage);

    void setStreamVolume(int streamType, int index, int flags, String callingPackage);

    boolean isStreamMute(int streamType);

    void forceRemoteSubmixFullVolume(boolean startForcing, IBinder cb);

    boolean isMasterMute();

    void setMasterMute(boolean mute, int flags, String callingPackage, int userId);

    int getStreamVolume(int streamType);

    int getStreamMinVolume(int streamType);

    int getStreamMaxVolume(int streamType);

    int getLastAudibleStreamVolume(int streamType);

    void setMicrophoneMute(boolean on, String callingPackage, int userId);

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

    oneway void avrcpSupportsAbsoluteVolume(String address, boolean support);

    void setSpeakerphoneOn(boolean on);

    boolean isSpeakerphoneOn();

    void setBluetoothScoOn(boolean on);

    boolean isBluetoothScoOn();

    void setBluetoothA2dpOn(boolean on);

    boolean isBluetoothA2dpOn();

    int requestAudioFocus(in AudioAttributes aa, int durationHint, IBinder cb,
            IAudioFocusDispatcher fd, String clientId, String callingPackageName, int flags,
            IAudioPolicyCallback pcb);

    int abandonAudioFocus(IAudioFocusDispatcher fd, String clientId, in AudioAttributes aa);

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

    int setBluetoothA2dpDeviceConnectionState(in BluetoothDevice device, int state, int profile);

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
            in IAudioPolicyCallback pcb, boolean hasFocusListener);

    oneway void unregisterAudioPolicyAsync(in IAudioPolicyCallback pcb);

    int setFocusPropertiesForPolicy(int duckingBehavior, in IAudioPolicyCallback pcb);

    void setVolumePolicy(in VolumePolicy policy);

    void registerRecordingCallback(in IRecordingConfigDispatcher rcdb);

    oneway void unregisterRecordingCallback(in IRecordingConfigDispatcher rcdb);

    List<AudioRecordingConfiguration> getActiveRecordingConfigurations();
}
