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
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFormat;
import android.media.ISpatializerCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper class to manage Spatializer related functionality
 */
public class SpatializerHelper {

    private static final String TAG = "AS.NotificationHelper";

    //---------------------------------------------------------------
    // audio device compatibility / enabled

    private final ArrayList<AudioDeviceAttributes> mCompatibleAudioDevices = new ArrayList<>(0);
    private final ArrayList<AudioDeviceAttributes> mEnabledAudioDevices = new ArrayList<>(0);

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
        // by default, adding a compatible device enables it
        if (!mEnabledAudioDevices.contains(ada)) {
            mEnabledAudioDevices.add(ada);
        }
    }

    synchronized void removeCompatibleAudioDevice(@NonNull AudioDeviceAttributes ada) {
        mCompatibleAudioDevices.remove(ada);
        mEnabledAudioDevices.remove(ada);
    }

    synchronized void setEnabledForDevice(boolean enabled, @NonNull AudioDeviceAttributes ada) {
        if (enabled) {
            if (!mEnabledAudioDevices.contains(ada)) {
                mEnabledAudioDevices.add(ada);
            }
        } else {
            mEnabledAudioDevices.remove(ada);
        }
    }

    //------------------------------------------------------
    // enabled state

    // global state of feature
    boolean mFeatureEnabled = false;

    synchronized void setEnabled(boolean enabled) {
        final boolean oldState = mFeatureEnabled;
        mFeatureEnabled = enabled;
        if (oldState != enabled) {
            dispatchState();
        }
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

    private synchronized void dispatchState() {
        // TODO check enabled state based on available devices
        // (current implementation takes state as-is and dispatches it to listeners
        final int nbCallbacks = mStateCallbacks.beginBroadcast();
        for (int i = 0; i < nbCallbacks; i++) {
            try {
                mStateCallbacks.getBroadcastItem(i)
                        .dispatchSpatializerStateChanged(mFeatureEnabled);
            } catch (RemoteException e) {
                Log.e(TAG, "Error in dispatchSpatializerStateChanged", e);
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
