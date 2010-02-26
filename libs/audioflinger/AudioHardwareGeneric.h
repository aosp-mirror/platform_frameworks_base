/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef ANDROID_AUDIO_HARDWARE_GENERIC_H
#define ANDROID_AUDIO_HARDWARE_GENERIC_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/threads.h>

#include <hardware_legacy/AudioHardwareBase.h>

namespace android {

// ----------------------------------------------------------------------------

class AudioHardwareGeneric;

class AudioStreamOutGeneric : public AudioStreamOut {
public:
                        AudioStreamOutGeneric() : mAudioHardware(0), mFd(-1) {}
    virtual             ~AudioStreamOutGeneric();

    virtual status_t    set(
            AudioHardwareGeneric *hw,
            int mFd,
            uint32_t devices,
            int *pFormat,
            uint32_t *pChannels,
            uint32_t *pRate);

    virtual uint32_t    sampleRate() const { return 44100; }
    virtual size_t      bufferSize() const { return 4096; }
    virtual uint32_t    channels() const { return AudioSystem::CHANNEL_OUT_STEREO; }
    virtual int         format() const { return AudioSystem::PCM_16_BIT; }
    virtual uint32_t    latency() const { return 20; }
    virtual status_t    setVolume(float left, float right) { return INVALID_OPERATION; }
    virtual ssize_t     write(const void* buffer, size_t bytes);
    virtual status_t    standby();
    virtual status_t    dump(int fd, const Vector<String16>& args);
    virtual status_t    setParameters(const String8& keyValuePairs);
    virtual String8     getParameters(const String8& keys);
    virtual status_t    getRenderPosition(uint32_t *dspFrames);

private:
    AudioHardwareGeneric *mAudioHardware;
    Mutex   mLock;
    int     mFd;
    uint32_t mDevice;
};

class AudioStreamInGeneric : public AudioStreamIn {
public:
                        AudioStreamInGeneric() : mAudioHardware(0), mFd(-1) {}
    virtual             ~AudioStreamInGeneric();

    virtual status_t    set(
            AudioHardwareGeneric *hw,
            int mFd,
            uint32_t devices,
            int *pFormat,
            uint32_t *pChannels,
            uint32_t *pRate,
            AudioSystem::audio_in_acoustics acoustics);

    virtual uint32_t    sampleRate() const { return 8000; }
    virtual size_t      bufferSize() const { return 320; }
    virtual uint32_t    channels() const { return AudioSystem::CHANNEL_IN_MONO; }
    virtual int         format() const { return AudioSystem::PCM_16_BIT; }
    virtual status_t    setGain(float gain) { return INVALID_OPERATION; }
    virtual ssize_t     read(void* buffer, ssize_t bytes);
    virtual status_t    dump(int fd, const Vector<String16>& args);
    virtual status_t    standby() { return NO_ERROR; }
    virtual status_t    setParameters(const String8& keyValuePairs);
    virtual String8     getParameters(const String8& keys);
    virtual unsigned int  getInputFramesLost() const { return 0; }

private:
    AudioHardwareGeneric *mAudioHardware;
    Mutex   mLock;
    int     mFd;
    uint32_t mDevice;
};


class AudioHardwareGeneric : public AudioHardwareBase
{
public:
                        AudioHardwareGeneric();
    virtual             ~AudioHardwareGeneric();
    virtual status_t    initCheck();
    virtual status_t    setVoiceVolume(float volume);
    virtual status_t    setMasterVolume(float volume);

    // mic mute
    virtual status_t    setMicMute(bool state);
    virtual status_t    getMicMute(bool* state);

    // create I/O streams
    virtual AudioStreamOut* openOutputStream(
            uint32_t devices,
            int *format=0,
            uint32_t *channels=0,
            uint32_t *sampleRate=0,
            status_t *status=0);
    virtual    void        closeOutputStream(AudioStreamOut* out);

    virtual AudioStreamIn* openInputStream(
            uint32_t devices,
            int *format,
            uint32_t *channels,
            uint32_t *sampleRate,
            status_t *status,
            AudioSystem::audio_in_acoustics acoustics);
    virtual    void        closeInputStream(AudioStreamIn* in);

            void            closeOutputStream(AudioStreamOutGeneric* out);
            void            closeInputStream(AudioStreamInGeneric* in);
protected:
    virtual status_t        dump(int fd, const Vector<String16>& args);

private:
    status_t                dumpInternals(int fd, const Vector<String16>& args);

    Mutex                   mLock;
    AudioStreamOutGeneric   *mOutput;
    AudioStreamInGeneric    *mInput;
    int                     mFd;
    bool                    mMicMute;
};

// ----------------------------------------------------------------------------

}; // namespace android

#endif // ANDROID_AUDIO_HARDWARE_GENERIC_H
