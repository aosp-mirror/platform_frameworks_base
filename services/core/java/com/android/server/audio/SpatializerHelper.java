/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFormat;
import android.media.ISpatializerCallback;
import android.media.Spatializer;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class to manage Spatializer related functionality
 */
public class SpatializerHelper {

    private static final String TAG = "AS.SpatializerHelper";

    //---------------------------------------------------------------
    // audio device compatibility / enabled

    private final ArrayList<AudioDeviceAttributes> mCompatibleAudioDevices = new ArrayList<>(0);

    /**
     * @return a shallow copy of the list of compatible audio devices
     */
    synchronized @NonNull List<AudioDeviceAttributes> getCompatibleAudioDevices() {
        return (List<AudioDeviceAttributes>) mCompatibleAudioDevices.clone();
    }

    synchronized void addCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        if (!mCompatibleAudioDevices.contains(ada)) {
            mCompatibleAudioDevices.add(ada);
        }
    }

    synchronized void removeCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        mCompatibleAudioDevices.remove(ada);
    }

    //------------------------------------------------------
    // enabled state

    // global state of feature
    boolean mFeatureEnabled = false;
    // initialized state, checked after each audio_server start
    boolean mInitialized = false;

    synchronized boolean isEnabled() {
        return mFeatureEnabled;
    }

    synchronized boolean isAvailable() {
        if (!mInitialized) {
            return false;
        }
        // TODO check device compatibility
        // ...
        return true;
    }

    synchronized void setEnabled(boolean enabled) {
        final boolean oldState = mFeatureEnabled;
        mFeatureEnabled = enabled;
        if (oldState != enabled) {
            dispatchEnabledState();
        }
    }

    public int getImmersiveAudioLevel() {
        // TODO replace placeholder code with actual effect discovery
        return Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE;
    }

    final RemoteCallbackList<ISpatializerCallback> mStateCallbacks =
            new RemoteCallbackList<ISpatializerCallback>();

    synchronized void registerStateCallback(
            @NonNull ISpatializerCallback callback) {
        mStateCallbacks.register(callback);
    }

    synchronized void unregisterStateCallback(
            @NonNull ISpatializerCallback callback) {
        mStateCallbacks.unregister(callback);
    }

    private synchronized void dispatchEnabledState() {
        final int nbCallbacks = mStateCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mStateCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerEnabledChanged(mFeatureEnabled);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerEnabledChanged", e);
            }
        }
        mStateCallbacks.finishBroadcast();
    }

    //------------------------------------------------------
    // virtualization capabilities
    synchronized boolean canBeSpatialized(
            @NonNull AudioAttributes attributes, @NonNull AudioFormat format) {
        // TODO hook up to spatializer effect for query
        return false;
    }
}
