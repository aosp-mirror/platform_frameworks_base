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

package android.companion.virtual;

import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.IVirtualDeviceListener;
import android.companion.virtual.IVirtualDeviceSoundEffectListener;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceParams;
import android.content.AttributionSource;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;

/**
 * Interface for communication between VirtualDeviceManager and VirtualDeviceManagerService.
 *
 * @hide
 */
interface IVirtualDeviceManager {

    /**
     * Creates a virtual device that can be used to create virtual displays and stream contents.
     *
     * @param token The binder token created by the caller of this API.
     * @param packageName The package name of the caller. Implementation of this method must verify
     *   that this belongs to the calling UID.
     * @param associationId The association ID as returned by {@link AssociationInfo#getId()} from
     *   CDM. Virtual devices must have a corresponding association with CDM in order to be created.
     * @param params The parameters for creating this virtual device. See {@link
     *   VirtualDeviceManager.VirtualDeviceParams}.
     * @param activityListener The listener to listen for activity changes in a virtual device.
     * @param soundEffectListener The listener to listen for sound effect playback requests.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    IVirtualDevice createVirtualDevice(
            in IBinder token, in AttributionSource attributionSource, int associationId,
            in VirtualDeviceParams params, in IVirtualDeviceActivityListener activityListener,
            in IVirtualDeviceSoundEffectListener soundEffectListener);

    /**
     * Returns the details of all available virtual devices.
     */
    List<VirtualDevice> getVirtualDevices();

    /**
     * Returns the details of the virtual device with the given ID, if any.
     */
    VirtualDevice getVirtualDevice(int deviceId);

    /**
     * Registers a virtual device listener to receive notifications for virtual device events.
     */
    void registerVirtualDeviceListener(in IVirtualDeviceListener listener);

    /**
     * Unregisters a previously registered virtual device listener.
     */
    void unregisterVirtualDeviceListener(in IVirtualDeviceListener listener);

    /**
     * Returns the ID of the device which owns the display with the given ID.
     */
    int getDeviceIdForDisplayId(int displayId);

    /**
     * Returns the display name corresponding to the given persistent device ID, if any.
     */
    CharSequence getDisplayNameForPersistentDeviceId(in String persistentDeviceId);

    /**
     * Checks whether the passed {@code deviceId} is a valid virtual device ID or not.
     * {@link VirtualDeviceManager#DEVICE_ID_DEFAULT} is not valid as it is the ID of the default
     * device which is not a virtual device. {@code deviceId} must correspond to a virtual device
     * created by {@link VirtualDeviceManager#createVirtualDevice(int, VirtualDeviceParams)}.
    */
   boolean isValidVirtualDeviceId(int deviceId);

    /**
     * Returns the device policy for the given virtual device and policy type.
     */
    int getDevicePolicy(int deviceId, int policyType);

    /**
     * Creates a virtual display owned by a particular virtual device.
     *
     * @param virtualDisplayConfig The configuration used in creating the display
     * @param callback A callback that receives display lifecycle events
     * @param virtualDevice The device that will own this display
     * @param packageName The package name of the calling app
     */
    int createVirtualDisplay(in VirtualDisplayConfig virtualDisplayConfig,
            in IVirtualDisplayCallback callback, in IVirtualDevice virtualDevice,
            String packageName);

    /**
     * Returns device-specific session id for playback, or AUDIO_SESSION_ID_GENERATE
     * if there's none.
     */
    int getAudioPlaybackSessionId(int deviceId);

    /**
     * Returns device-specific session id for recording, or AUDIO_SESSION_ID_GENERATE
     * if there's none.
     */
    int getAudioRecordingSessionId(int deviceId);

    /**
     * Triggers sound effect playback on virtual device.
     *
     * @param deviceId id of the virtual device.
     * @param sound effect type corresponding to
     *   {@code android.media.AudioManager.SystemSoundEffect}
     */
    void playSoundEffect(int deviceId, int effectType);

    /**
     * Returns whether the given display is an auto-mirror display owned by a virtual
     * device.
     */
    boolean isVirtualDeviceOwnedMirrorDisplay(int displayId);

    /**
     * Returns all current persistent device IDs, including the ones for which no virtual device
     * exists, as long as one may have existed or can be created.
     */
    List<String> getAllPersistentDeviceIds();
}
