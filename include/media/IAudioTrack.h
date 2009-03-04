/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_IAUDIOTRACK_H
#define ANDROID_IAUDIOTRACK_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/RefBase.h>
#include <utils/Errors.h>
#include <utils/IInterface.h>
#include <utils/IMemory.h>


namespace android {

// ----------------------------------------------------------------------------

class IAudioTrack : public IInterface
{
public: 
    DECLARE_META_INTERFACE(AudioTrack);

    /* After it's created the track is not active. Call start() to
     * make it active. If set, the callback will start being called.
     */
    virtual status_t    start() = 0;

    /* Stop a track. If set, the callback will cease being called and
     * obtainBuffer will return an error. Buffers that are already released 
     * will be processed, unless flush() is called.
     */
    virtual void        stop() = 0;

    /* flush a stopped track. All pending buffers are discarded.
     * This function has no effect if the track is not stoped.
     */
    virtual void        flush() = 0;

    /* mute or unmutes this track.
     * While mutted, the callback, if set, is still called.
     */
    virtual void        mute(bool) = 0;
    
    /* Pause a track. If set, the callback will cease being called and
     * obtainBuffer will return an error. Buffers that are already released 
     * will be processed, unless flush() is called.
     */
    virtual void        pause() = 0;

    /* get this tracks control block */
    virtual sp<IMemory> getCblk() const = 0;    
};

// ----------------------------------------------------------------------------

class BnAudioTrack : public BnInterface<IAudioTrack>
{
public:
    virtual status_t    onTransact( uint32_t code,
                                    const Parcel& data,
                                    Parcel* reply,
                                    uint32_t flags = 0);
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_IAUDIOTRACK_H
