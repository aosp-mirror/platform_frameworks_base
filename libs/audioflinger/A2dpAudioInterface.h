/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef A2DP_AUDIO_HARDWARE_H
#define A2DP_AUDIO_HARDWARE_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/threads.h>

#include <hardware/AudioHardwareBase.h>


namespace android {

class A2dpAudioInterface : public AudioHardwareBase
{
    class A2dpAudioStreamOut;

public:
                        A2dpAudioInterface();
    virtual             ~A2dpAudioInterface();
    virtual status_t    initCheck();
    virtual status_t    standby();

    virtual status_t    setVoiceVolume(float volume);
    virtual status_t    setMasterVolume(float volume);

    // mic mute
    virtual status_t    setMicMute(bool state);
    virtual status_t    getMicMute(bool* state);

    // Temporary interface, do not use
    // TODO: Replace with a more generic key:value get/set mechanism
    virtual status_t    setParameter(const char *key, const char *value);

    // create I/O streams
    virtual AudioStreamOut* openOutputStream(
                                int format=0,
                                int channelCount=0,
                                uint32_t sampleRate=0,
                                status_t *status=0);

    virtual AudioStreamIn* openInputStream(
                                int format,
                                int channelCount,
                                uint32_t sampleRate,
                                status_t *status);

protected:
    virtual status_t    doRouting();
    virtual status_t    dump(int fd, const Vector<String16>& args);

private:
    class A2dpAudioStreamOut : public AudioStreamOut {
    public:
                            A2dpAudioStreamOut();
        virtual             ~A2dpAudioStreamOut();
                status_t    set(int format,
                                int channelCount,
                                uint32_t sampleRate);
        virtual uint32_t    sampleRate() const { return 44100; }
        // must be 32-bit aligned - driver only seems to like 4800
        virtual size_t      bufferSize() const { return 5120; }
        virtual int         channelCount() const { return 2; }
        virtual int         format() const { return AudioSystem::PCM_16_BIT; }
        virtual uint32_t    latency() const { return 0; }
        virtual status_t    setVolume(float volume) { return INVALID_OPERATION; }
        virtual ssize_t     write(const void* buffer, size_t bytes);
                status_t    standby();
        virtual status_t    dump(int fd, const Vector<String16>& args);

    private:
                int         mFd;
                int         mStartCount;
                int         mRetryCount;
                void*       mData;
                bool        mInitialized;

#define kBufferSize 50000
                char                    mBuffer[kBufferSize];
                int                     mBufferRemaining;
    };

    Mutex                   mLock;
    A2dpAudioStreamOut*     mOutput;
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // A2DP_AUDIO_HARDWARE_H
