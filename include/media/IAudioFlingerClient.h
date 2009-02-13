/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_IAUDIOFLINGERCLIENT_H
#define ANDROID_IAUDIOFLINGERCLIENT_H


#include <utils/RefBase.h>
#include <utils/IInterface.h>


namespace android {

// ----------------------------------------------------------------------------

class IAudioFlingerClient : public IInterface
{
public:
    DECLARE_META_INTERFACE(AudioFlingerClient);

    // Notifies a change of audio output from/to hardware to/from A2DP.
    virtual void a2dpEnabledChanged(bool enabled) = 0;

};


// ----------------------------------------------------------------------------

class BnAudioFlingerClient : public BnInterface<IAudioFlingerClient>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_IAUDIOFLINGERCLIENT_H
