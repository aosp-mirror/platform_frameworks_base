/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Handler;
import android.os.Looper;

/**
 * AudioRouting defines an interface for controlling routing and routing notifications in
 * AudioTrack and AudioRecord objects.
 */
public interface AudioRouting {
    /**
     * Specifies an audio device (via an {@link AudioDeviceInfo} object) to route
     * the output/input to/from.
     * @param deviceInfo The {@link AudioDeviceInfo} specifying the audio sink or source.
     *  If deviceInfo is null, default routing is restored.
     * @return true if succesful, false if the specified {@link AudioDeviceInfo} is non-null and
     * does not correspond to a valid audio device.
     */
    public boolean setPreferredDevice(AudioDeviceInfo deviceInfo);

    /**
     * Returns the selected output/input specified by {@link #setPreferredDevice}. Note that this
     * is not guaranteed to correspond to the actual device being used for playback/recording.
     */
    public AudioDeviceInfo getPreferredDevice();

    /**
     * Returns an {@link AudioDeviceInfo} identifying the current routing of this
     * AudioTrack/AudioRecord.
     * Note: The query is only valid if the AudioTrack/AudioRecord is currently playing.
     * If it is not, <code>getRoutedDevice()</code> will return null.
     */
    public AudioDeviceInfo getRoutedDevice();

    /**
     * Adds an {@link AudioRouting.OnRoutingChangedListener} to receive notifications of routing
     * changes on this AudioTrack/AudioRecord.
     * @param listener The {@link AudioRouting.OnRoutingChangedListener} interface to receive
     * notifications of rerouting events.
     * @param handler  Specifies the {@link Handler} object for the thread on which to execute
     * the callback. If <code>null</code>, the {@link Handler} associated with the main
     * {@link Looper} will be used.
     */
    public void addOnRoutingChangedListener(OnRoutingChangedListener listener,
            Handler handler);

    /**
     * Removes an {@link AudioRouting.OnRoutingChangedListener} which has been previously added
     * to receive rerouting notifications.
     * @param listener The previously added {@link AudioRouting.OnRoutingChangedListener} interface
     * to remove.
     */
    public void removeOnRoutingChangedListener(OnRoutingChangedListener listener);

    /**
     * Defines the interface by which applications can receive notifications of routing
     * changes for the associated {@link AudioRouting}.
     */
    public interface OnRoutingChangedListener {
        public void onRoutingChanged(AudioRouting router);
    }
}
