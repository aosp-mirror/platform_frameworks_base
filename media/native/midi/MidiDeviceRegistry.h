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

#ifndef ANDROID_MEDIA_MIDI_DEVICE_REGISTRY_H_
#define ANDROID_MEDIA_MIDI_DEVICE_REGISTRY_H_

#include <map>
#include <mutex>

#include <binder/IBinder.h>
#include <utils/Errors.h>
#include <utils/Singleton.h>

#include "android/media/midi/BpMidiDeviceServer.h"
#include "midi.h"

namespace android {
namespace media {
namespace midi {

/*
 * Maintains a thread-safe, (singleton) list of MIDI devices with associated Binder interfaces,
 * which are exposed to the Native API via (Java) MidiDevice.mirrorToNative() &
 * MidiDevice.removeFromNative().
 * (Called via MidiDeviceManager::addDevice() MidiManager::removeDevice()).
 */
class MidiDeviceRegistry : public Singleton<MidiDeviceRegistry> {
  public:
    /* Add a MIDI Device to the registry.
     *
     * server       The Binder interface to the MIDI device server.
     * deviceUId    The unique ID of the device obtained from
     *              the Java API via MidiDeviceInfo.getId().
     */
    status_t addDevice(sp<BpMidiDeviceServer> server, int32_t deviceId);

    /* Remove the device (and associated server) from the Device registry.
     *
     * deviceUid    The ID of the device which was used in the call to addDevice().
     */
    status_t removeDevice(int32_t deviceId);

    /* Gets a device token associated with the device ID. This is used by the
     * native API to identify/access the device.
     * Multiple calls without releasing the token will return the same value.
     *
     * deviceUid    The ID of the device.
     * deviceTokenPtr Receives the device (native) token associated with the device ID.
     * returns: OK on success, error code otherwise.
     */
    status_t obtainDeviceToken(int32_t deviceId, AMIDI_Device *deviceTokenPtr);

    /*
     * Releases the native API device token associated with a MIDI device.
     *
     * deviceToken The device (native) token associated with the device ID.
     */
    status_t releaseDevice(AMIDI_Device deviceToken);

    /*
     * Gets the Device server binder interface associated with the device token.
     *
     * deviceToken The device (native) token associated with the device ID.
     */
    status_t getDeviceByToken(AMIDI_Device deviceToken, sp<BpMidiDeviceServer> *devicePtr);

  private:
    friend class Singleton<MidiDeviceRegistry>;
    MidiDeviceRegistry();

    // Access Mutex
    std::mutex                              mMapsLock;

    // maps device IDs to servers
    std::map<int32_t, sp<BpMidiDeviceServer>>   mServers;

    // maps device tokens to device ID
    std::map<AMIDI_Device, int32_t>         mTokenToUid;

    // maps device IDs to device tokens
    std::map<int32_t, AMIDI_Device>         mUidToToken;

    // Value of next device token to dole out.
    AMIDI_Device                            mNextDeviceToken;
};

} // namespace midi
} // namespace media
} // namespace android

#endif // ANDROID_MEDIA_MIDI_DEVICE_REGISTRY_H_
