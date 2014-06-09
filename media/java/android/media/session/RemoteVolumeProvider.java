/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.media.session;

import android.os.RemoteException;
import android.util.Log;

/**
 * Handles requests to adjust or set the volume on a session. This is also used
 * to push volume updates back to the session after a request has been handled.
 * You can set a volume provider on a session by calling
 * {@link MediaSession#setPlaybackToRemote}.
 */
public abstract class RemoteVolumeProvider {
    private static final String TAG = "RemoteVolumeProvider";

    /**
     * The volume is fixed and can not be modified. Requests to change volume
     * should be ignored.
     */
    public static final int VOLUME_CONTROL_FIXED = 0;

    /**
     * The volume control uses relative adjustment via
     * {@link #onAdjustVolumeBy(int)}. Attempts to set the volume to a specific
     * value should be ignored.
     */
    public static final int VOLUME_CONTROL_RELATIVE = 1;

    /**
     * The volume control uses an absolute value. It may be adjusted using
     * {@link #onAdjustVolumeBy(int)} or set directly using
     * {@link #onSetVolumeTo(int)}.
     */
    public static final int VOLUME_CONTROL_ABSOLUTE = 2;

    private final int mControlType;
    private final int mMaxVolume;

    private MediaSession mSession;

    /**
     * Create a new volume provider for handling volume events. You must specify
     * the type of volume control and the maximum volume that can be used.
     *
     * @param volumeControl The method for controlling volume that is used by
     *            this provider.
     * @param maxVolume The maximum allowed volume.
     */
    public RemoteVolumeProvider(int volumeControl, int maxVolume) {
        mControlType = volumeControl;
        mMaxVolume = maxVolume;
    }

    /**
     * Get the current volume of the remote playback.
     *
     * @return The current volume.
     */
    public abstract int onGetCurrentVolume();

    /**
     * Get the volume control type that this volume provider uses.
     *
     * @return The volume control type for this volume provider
     */
    public final int getVolumeControl() {
        return mControlType;
    }

    /**
     * Get the maximum volume this provider allows.
     *
     * @return The max allowed volume.
     */
    public final int getMaxVolume() {
        return mMaxVolume;
    }

    /**
     * Notify the system that the remote playback's volume has been changed.
     */
    public final void notifyVolumeChanged() {
        mSession.notifyRemoteVolumeChanged(this);
    }

    /**
     * Override to handle requests to set the volume of the current output.
     *
     * @param volume The volume to set the output to.
     */
    public void onSetVolumeTo(int volume) {
    }

    /**
     * Override to handle requests to adjust the volume of the current
     * output.
     *
     * @param delta The amount to change the volume
     */
    public void onAdjustVolumeBy(int delta) {
    }

    /**
     * @hide
     */
    void setSession(MediaSession session) {
        mSession = session;
    }
}