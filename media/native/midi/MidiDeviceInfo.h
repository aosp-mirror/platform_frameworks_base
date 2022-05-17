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

#ifndef ANDROID_MEDIA_MIDI_DEVICE_INFO_H
#define ANDROID_MEDIA_MIDI_DEVICE_INFO_H

#include <binder/Parcelable.h>
#include <binder/PersistableBundle.h>
#include <utils/String16.h>
#include <utils/Vector.h>

namespace android {
namespace media {
namespace midi {

class MidiDeviceInfo : public Parcelable {
public:
    MidiDeviceInfo() = default;
    virtual ~MidiDeviceInfo() = default;
    MidiDeviceInfo(const MidiDeviceInfo& midiDeviceInfo) = default;

    status_t writeToParcel(Parcel* parcel) const override;
    status_t readFromParcel(const Parcel* parcel) override;

    int getType() const { return mType; }
    int getUid() const { return mId; }
    bool isPrivate() const { return mIsPrivate; }
    int getDefaultProtocol() const { return mDefaultProtocol; }
    const Vector<String16>& getInputPortNames() const { return mInputPortNames; }
    const Vector<String16>&  getOutputPortNames() const { return mOutputPortNames; }
    String16 getProperty(const char* propertyName);

    // The constants need to be kept in sync with MidiDeviceInfo.java
    enum {
        TYPE_USB = 1,
        TYPE_VIRTUAL = 2,
        TYPE_BLUETOOTH = 3,
    };

    enum {
        PROTOCOL_UMP_USE_MIDI_CI = 0,
        PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS = 1,
        PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS_AND_JRTS = 2,
        PROTOCOL_UMP_MIDI_1_0_UP_TO_128_BITS = 3,
        PROTOCOL_UMP_MIDI_1_0_UP_TO_128_BITS_AND_JRTS = 4,
        PROTOCOL_UMP_MIDI_2_0 = 17,
        PROTOCOL_UMP_MIDI_2_0_AND_JRTS = 18,
        PROTOCOL_UNKNOWN = -1,
    };

    static const char* const PROPERTY_NAME;
    static const char* const PROPERTY_MANUFACTURER;
    static const char* const PROPERTY_PRODUCT;
    static const char* const PROPERTY_VERSION;
    static const char* const PROPERTY_SERIAL_NUMBER;
    static const char* const PROPERTY_ALSA_CARD;
    static const char* const PROPERTY_ALSA_DEVICE;

    friend bool operator==(const MidiDeviceInfo& lhs, const MidiDeviceInfo& rhs);
    friend bool operator!=(const MidiDeviceInfo& lhs, const MidiDeviceInfo& rhs) {
        return !(lhs == rhs);
    }

private:
    status_t readStringVector(
            const Parcel* parcel, Vector<String16> *vectorPtr, size_t defaultLength);
    status_t writeStringVector(Parcel* parcel, const Vector<String16>& vector) const;

    int32_t mType;
    int32_t mId;
    Vector<String16> mInputPortNames;
    Vector<String16> mOutputPortNames;
    os::PersistableBundle mProperties;
    bool mIsPrivate;
    int32_t mDefaultProtocol;
};

}  // namespace midi
}  // namespace media
}  // namespace android

#endif  // ANDROID_MEDIA_MIDI_DEVICE_INFO_H
