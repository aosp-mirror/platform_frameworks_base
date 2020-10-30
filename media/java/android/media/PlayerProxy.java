/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.VolumeShaper;
import android.os.RemoteException;
import android.util.Log;

import java.lang.IllegalArgumentException;
import java.util.Objects;

/**
 * Class to remotely control a player.
 * @hide
 */
@SystemApi
public class PlayerProxy {

    private final static String TAG = "PlayerProxy";
    private final static boolean DEBUG = false;

    private final AudioPlaybackConfiguration mConf; // never null

    /**
     * @hide
     * Constructor. Proxy for this player associated with this AudioPlaybackConfiguration
     * @param conf the configuration being proxied.
     */
    PlayerProxy(@NonNull AudioPlaybackConfiguration apc) {
        if (apc == null) {
            throw new IllegalArgumentException("Illegal null AudioPlaybackConfiguration");
        }
        mConf = apc;
    };

    //=====================================================================
    // Methods matching the IPlayer interface
    /**
     * @hide
     */
    @SystemApi
    public void start() {
        try {
            mConf.getIPlayer().start();
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for start operation, player already released?", e);
        }
    }

    /**
     * @hide
     */
    @SystemApi
    public void pause() {
        try {
            mConf.getIPlayer().pause();
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for pause operation, player already released?", e);
        }
    }

    /**
     * @hide
     */
    @SystemApi
    public void stop() {
        try {
            mConf.getIPlayer().stop();
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for stop operation, player already released?", e);
        }
    }

    /**
     * @hide
     * @param vol
     */
    @SystemApi
    public void setVolume(float vol) {
        try {
            mConf.getIPlayer().setVolume(vol);
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for setVolume operation, player already released?", e);
        }
    }

    /**
     * @hide
     * @param pan
     */
    @SystemApi
    public void setPan(float pan) {
        try {
            mConf.getIPlayer().setPan(pan);
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for setPan operation, player already released?", e);
        }
    }

    /**
     * @hide
     * @param delayMs
     */
    @SystemApi
    public void setStartDelayMs(int delayMs) {
        try {
            mConf.getIPlayer().setStartDelayMs(delayMs);
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for setStartDelayMs operation, player already released?",
                    e);
        }
    }

    /**
     * @hide
     * @param configuration
     * @param operation
     * @return volume shaper id or error
     */
    public void applyVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation) {
        try {
            mConf.getIPlayer().applyVolumeShaper(configuration.toParcelable(),
                    operation.toParcelable());
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for applyVolumeShaper operation,"
                    + " player already released?", e);
        }
    }
}
