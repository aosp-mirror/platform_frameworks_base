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
     * @throws IllegalStateException
     */
    @SystemApi
    public void start() throws IllegalStateException {
        try {
            mConf.getIPlayer().start();
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for start operation, player already released?", e);
        }
    }

    /**
     * @hide
     * @throws IllegalStateException
     */
    @SystemApi
    public void pause() throws IllegalStateException {
        try {
            mConf.getIPlayer().pause();
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for pause operation, player already released?", e);
        }
    }

    /**
     * @hide
     * @throws IllegalStateException
     */
    @SystemApi
    public void stop() throws IllegalStateException {
        try {
            mConf.getIPlayer().stop();
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for stop operation, player already released?", e);
        }
    }

    /**
     * @hide
     * @throws IllegalStateException
     */
    @SystemApi
    public void setVolume(float vol) throws IllegalStateException {
        try {
            mConf.getIPlayer().setVolume(vol);
        } catch (NullPointerException|RemoteException e) {
            throw new IllegalStateException(
                    "No player to proxy for setVolume operation, player already released?", e);
        }
    }

}
