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
import android.media.Rating;
import android.net.Uri;
import android.view.KeyEvent;

/**
 * {@hide}
 */
interface IAudioService {

    int verifyX509CertChain(int chainsize, in byte[] chain, String host, String authtype);

    void adjustVolume(int direction, int flags, String callingPackage);

    boolean isLocalOrRemoteMusicActive();

    oneway void adjustLocalOrRemoteStreamVolume(int streamType, int direction,
            String callingPackage);

    void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags,
            String callingPackage);

    void adjustStreamVolume(int streamType, int direction, int flags, String callingPackage);

    void adjustMasterVolume(int direction, int flags, String callingPackage);

    void setStreamVolume(int streamType, int index, int flags, String callingPackage);

    oneway void setRemoteStreamVolume(int index);

    void setMasterVolume(int index, int flags, String callingPackage);

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

    oneway void avrcpSupportsAbsoluteVolume(String address, boolean support);

    void setSpeakerphoneOn(boolean on);

    boolean isSpeakerphoneOn();

    void setBluetoothScoOn(boolean on);

    boolean isBluetoothScoOn();

    void setBluetoothA2dpOn(boolean on);

    boolean isBluetoothA2dpOn();

    int requestAudioFocus(int mainStreamType, int durationHint, IBinder cb,
            IAudioFocusDispatcher fd, String clientId, String callingPackageName);

    int abandonAudioFocus(IAudioFocusDispatcher fd, String clientId);

    void unregisterAudioFocusClient(String clientId);

    int getCurrentAudioFocus();

    oneway void dispatchMediaKeyEvent(in KeyEvent keyEvent);
    void dispatchMediaKeyEventUnderWakelock(in KeyEvent keyEvent);

           void registerMediaButtonIntent(in PendingIntent pi, in ComponentName c, IBinder token);
    oneway void unregisterMediaButtonIntent(in PendingIntent pi);

    oneway void registerMediaButtonEventReceiverForCalls(in ComponentName c);
    oneway void unregisterMediaButtonEventReceiverForCalls();

    /**
     * Register an IRemoteControlDisplay.
     * Success of registration is subject to a check on
     *   the android.Manifest.permission.MEDIA_CONTENT_CONTROL permission.
     * Notify all IRemoteControlClient of the new display and cause the RemoteControlClient
     * at the top of the stack to update the new display with its information.
     * @param rcd the IRemoteControlDisplay to register. No effect if null.
     * @param w the maximum width of the expected bitmap. Negative or zero values indicate this
     *   display doesn't need to receive artwork.
     * @param h the maximum height of the expected bitmap. Negative or zero values indicate this
     *   display doesn't need to receive artwork.
     */
    boolean registerRemoteControlDisplay(in IRemoteControlDisplay rcd, int w, int h);

    /**
     * Like registerRemoteControlDisplay, but with success being subject to a check on
     *   the android.Manifest.permission.MEDIA_CONTENT_CONTROL permission, and if it fails,
     *   success is subject to listenerComp being one of the ENABLED_NOTIFICATION_LISTENERS
     *   components.
     */
    boolean registerRemoteController(in IRemoteControlDisplay rcd, int w, int h,
            in ComponentName listenerComp);

    /**
     * Unregister an IRemoteControlDisplay.
     * No effect if the IRemoteControlDisplay hasn't been successfully registered.
     * @param rcd the IRemoteControlDisplay to unregister. No effect if null.
     */
    oneway void unregisterRemoteControlDisplay(in IRemoteControlDisplay rcd);
    /**
     * Update the size of the artwork used by an IRemoteControlDisplay.
     * @param rcd the IRemoteControlDisplay with the new artwork size requirement
     * @param w the maximum width of the expected bitmap. Negative or zero values indicate this
     *   display doesn't need to receive artwork.
     * @param h the maximum height of the expected bitmap. Negative or zero values indicate this
     *   display doesn't need to receive artwork.
     */
    oneway void remoteControlDisplayUsesBitmapSize(in IRemoteControlDisplay rcd, int w, int h);
    /**
     * Controls whether a remote control display needs periodic checks of the RemoteControlClient
     * playback position to verify that the estimated position has not drifted from the actual
     * position. By default the check is not performed.
     * The IRemoteControlDisplay must have been previously registered for this to have any effect.
     * @param rcd the IRemoteControlDisplay for which the anti-drift mechanism will be enabled
     *     or disabled. Not null.
     * @param wantsSync if true, RemoteControlClient instances which expose their playback position
     *     to the framework will regularly compare the estimated playback position with the actual
     *     position, and will update the IRemoteControlDisplay implementation whenever a drift is
     *     detected.
     */
    oneway void remoteControlDisplayWantsPlaybackPositionSync(in IRemoteControlDisplay rcd,
            boolean wantsSync);
    /**
     * Request the user of a RemoteControlClient to seek to the given playback position.
     * @param generationId the RemoteControlClient generation counter for which this request is
     *         issued. Requests for an older generation than current one will be ignored.
     * @param timeMs the time in ms to seek to, must be positive.
     */
     void setRemoteControlClientPlaybackPosition(int generationId, long timeMs);
     /**
      * Notify the user of a RemoteControlClient that it should update its metadata with the
      * new value for the given key.
      * @param generationId the RemoteControlClient generation counter for which this request is
      *         issued. Requests for an older generation than current one will be ignored.
      * @param key the metadata key for which a new value exists
      * @param value the new metadata value
      */
     void updateRemoteControlClientMetadata(int generationId, int key, in Rating value);

    /**
     * Do not use directly, use instead
     *     {@link android.media.AudioManager#registerRemoteControlClient(RemoteControlClient)}
     */
    int registerRemoteControlClient(in PendingIntent mediaIntent,
            in IRemoteControlClient rcClient, in String callingPackageName);
    /**
     * Do not use directly, use instead
     *     {@link android.media.AudioManager#unregisterRemoteControlClient(RemoteControlClient)}
     */
    oneway void unregisterRemoteControlClient(in PendingIntent mediaIntent,
            in IRemoteControlClient rcClient);

    oneway void setPlaybackInfoForRcc(int rccId, int what, int value);
    void setPlaybackStateForRcc(int rccId, int state, long timeMs, float speed);
           int  getRemoteStreamMaxVolume();
           int  getRemoteStreamVolume();
    oneway void registerRemoteVolumeObserverForRcc(int rccId, in IRemoteVolumeObserver rvo);

    void startBluetoothSco(IBinder cb, int targetSdkVersion);
    void stopBluetoothSco(IBinder cb);

    void forceVolumeControlStream(int streamType, IBinder cb);

    void setRingtonePlayer(IRingtonePlayer player);
    IRingtonePlayer getRingtonePlayer();
    int getMasterStreamType();

    void setWiredDeviceConnectionState(int device, int state, String name);
    int setBluetoothA2dpDeviceConnectionState(in BluetoothDevice device, int state);

    AudioRoutesInfo startWatchingRoutes(in IAudioRoutesObserver observer);

    boolean isCameraSoundForced();

}
