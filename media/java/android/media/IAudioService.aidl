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

/**
 * {@hide}
 */
interface IAudioService {
    
    void adjustVolume(int direction, int flags);

    void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags);
    
    void adjustStreamVolume(int streamType, int direction, int flags);
    
    void setStreamVolume(int streamType, int index, int flags);
    
   	void setStreamSolo(int streamType, boolean state, IBinder cb);
   	
   	void setStreamMute(int streamType, boolean state, IBinder cb);
    
    int getStreamVolume(int streamType);
    
    int getStreamMaxVolume(int streamType);
    
    void setRingerMode(int ringerMode);
    
    int getRingerMode();

    void setVibrateSetting(int vibrateType, int vibrateSetting);
    
    int getVibrateSetting(int vibrateType);
    
    boolean shouldVibrate(int vibrateType);
    
    void setMicrophoneMute(boolean on);

    boolean isMicrophoneMute();

    void setMode(int mode);

    int getMode();

    void setRouting(int mode, int routes, int mask);

    int getRouting(int mode);

    boolean isMusicActive();

    void setParameter(String key, String value);

    oneway void playSoundEffect(int effectType);
  
    oneway void playSoundEffectVolume(int effectType, float volume);

    boolean loadSoundEffects();
  
    oneway void unloadSoundEffects();

    oneway void reloadAudioSettings();
}
