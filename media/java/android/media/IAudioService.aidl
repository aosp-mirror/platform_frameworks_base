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
import android.media.AudioRoutesInfo;
import android.media.IAudioFocusDispatcher;
import android.media.IAudioRoutesObserver;
import android.media.IRemoteControlClient;
import android.media.IRemoteControlDisplay;
import android.media.IRemoteVolumeObserver;
import android.media.IRingtonePlayer;
import android.net.Uri;
import android.view.KeyEvent;

/**
 * {@hide}
 */
interface IAudioService {
    
    void adjustVolume(int direction, int flags);

    oneway void adjustLocalOrRemoteStreamVolume(int streamType, int direction);

    void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags);

    void adjustStreamVolume(int streamType, int direction, int flags);

    void adjustMasterVolume(int direction, int flags);

    void setStreamVolume(int streamType, int index, int flags);

    oneway void setRemoteStreamVolume(int index);

    void setMasterVolume(int index, int flags);
    
    void setStreamSolo(int streamType, boolean state, IBinder cb);
   	
    void setStreamMute(int streamType, boolean state, IBinder cb);

    boolean isStreamMute(int streamType);

    void setMasterMute(boolean state, int flags, IBinder cb);

    boolean isMasterMute();

    int getStreamVolume(int streamType);

    int getMasterVolume();

    int getStreamMaxVolume(int streamType);

    int getMasterMaxVolume();
    
    int getLastAudibleStreamVolume(int streamType);

    int getLastAudibleMasterVolume();

    void setRingerMode(int ringerMode);
    
    int getRingerMode();

    void setVibrateSetting(int vibrateType, int vibrateSetting);
    
    int getVibrateSetting(int vibrateType);
    
    boolean shouldVibrate(int vibrateType);

    void setMode(int mode, IBinder cb);

    int getMode();

    oneway void playSoundEffect(int effectType);
  
    oneway void playSoundEffectVolume(int effectType, float volume);

    boolean loadSoundEffects();
  
    oneway void unloadSoundEffects();

    oneway void reloadAudioSettings();

    void setSpeakerphoneOn(boolean on);

    boolean isSpeakerphoneOn();

    void setBluetoothScoOn(boolean on);

    boolean isBluetoothScoOn();

    void setBluetoothA2dpOn(boolean on);

    boolean isBluetoothA2dpOn();

    int requestAudioFocus(int mainStreamType, int durationHint, IBinder cb, IAudioFocusDispatcher l,
            String clientId, String callingPackageName);

    int abandonAudioFocus(IAudioFocusDispatcher l, String clientId);
    
    void unregisterAudioFocusClient(String clientId);

    oneway void dispatchMediaKeyEvent(in KeyEvent keyEvent);
    void dispatchMediaKeyEventUnderWakelock(in KeyEvent keyEvent);

    oneway void registerMediaButtonIntent(in PendingIntent pi, in ComponentName c);
    oneway void unregisterMediaButtonIntent(in PendingIntent pi,  in ComponentName c);

    oneway void registerMediaButtonEventReceiverForCalls(in ComponentName c);
    oneway void unregisterMediaButtonEventReceiverForCalls();

    int registerRemoteControlClient(in PendingIntent mediaIntent,
           in IRemoteControlClient rcClient, in String callingPackageName);
    oneway void unregisterRemoteControlClient(in PendingIntent mediaIntent,
           in IRemoteControlClient rcClient);

    oneway void   registerRemoteControlDisplay(in IRemoteControlDisplay rcd);
    oneway void unregisterRemoteControlDisplay(in IRemoteControlDisplay rcd);
    oneway void remoteControlDisplayUsesBitmapSize(in IRemoteControlDisplay rcd, int w, int h);

    oneway void setPlaybackInfoForRcc(int rccId, int what, int value);
           int  getRemoteStreamMaxVolume();
           int  getRemoteStreamVolume();
    oneway void registerRemoteVolumeObserverForRcc(int rccId, in IRemoteVolumeObserver rvo);

    void startBluetoothSco(IBinder cb);
    void stopBluetoothSco(IBinder cb);

    void forceVolumeControlStream(int streamType, IBinder cb);

    void setRingtonePlayer(IRingtonePlayer player);
    IRingtonePlayer getRingtonePlayer();
    int getMasterStreamType();

    void setWiredDeviceConnectionState(int device, int state, String name);
    int setBluetoothA2dpDeviceConnectionState(in BluetoothDevice device, int state);

    AudioRoutesInfo startWatchingRoutes(in IAudioRoutesObserver observer);
}
