/* //device/servers/AudioFlinger/AudioHardwareStub.cpp
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

#include <stdint.h>
#include <sys/types.h>

#include <stdlib.h>
#include <unistd.h>
#include <utils/String8.h>

#include "AudioHardwareStub.h"
#include <media/AudioRecord.h>

namespace android {

// ----------------------------------------------------------------------------

AudioHardwareStub::AudioHardwareStub() : mMicMute(false)
{
}

AudioHardwareStub::~AudioHardwareStub()
{
}

status_t AudioHardwareStub::initCheck()
{
    return NO_ERROR;
}

AudioStreamOut* AudioHardwareStub::openOutputStream(
        uint32_t devices, int *format, uint32_t *channels, uint32_t *sampleRate, status_t *status)
{
    AudioStreamOutStub* out = new AudioStreamOutStub();
    status_t lStatus = out->set(format, channels, sampleRate);
    if (status) {
        *status = lStatus;
    }
    if (lStatus == NO_ERROR)
        return out;
    delete out;
    return 0;
}

void AudioHardwareStub::closeOutputStream(AudioStreamOut* out)
{
    delete out;
}

AudioStreamIn* AudioHardwareStub::openInputStream(
        uint32_t devices, int *format, uint32_t *channels, uint32_t *sampleRate,
        status_t *status, AudioSystem::audio_in_acoustics acoustics)
{
    // check for valid input source
    if (!AudioSystem::isInputDevice((AudioSystem::audio_devices)devices)) {
        return 0;
    }

    AudioStreamInStub* in = new AudioStreamInStub();
    status_t lStatus = in->set(format, channels, sampleRate, acoustics);
    if (status) {
        *status = lStatus;
    }
    if (lStatus == NO_ERROR)
        return in;
    delete in;
    return 0;
}

void AudioHardwareStub::closeInputStream(AudioStreamIn* in)
{
    delete in;
}

status_t AudioHardwareStub::setVoiceVolume(float volume)
{
    return NO_ERROR;
}

status_t AudioHardwareStub::setMasterVolume(float volume)
{
    return NO_ERROR;
}

status_t AudioHardwareStub::dumpInternals(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    result.append("AudioHardwareStub::dumpInternals\n");
    snprintf(buffer, SIZE, "\tmMicMute: %s\n", mMicMute? "true": "false");
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioHardwareStub::dump(int fd, const Vector<String16>& args)
{
    dumpInternals(fd, args);
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

status_t AudioStreamOutStub::set(int *pFormat, uint32_t *pChannels, uint32_t *pRate)
{
    if (pFormat) *pFormat = format();
    if (pChannels) *pChannels = channels();
    if (pRate) *pRate = sampleRate();

    return NO_ERROR;
}

ssize_t AudioStreamOutStub::write(const void* buffer, size_t bytes)
{
    // fake timing for audio output
    usleep(bytes * 1000000 / sizeof(int16_t) / AudioSystem::popCount(channels()) / sampleRate());
    return bytes;
}

status_t AudioStreamOutStub::standby()
{
    return NO_ERROR;
}

status_t AudioStreamOutStub::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "AudioStreamOutStub::dump\n");
    snprintf(buffer, SIZE, "\tsample rate: %d\n", sampleRate());
    snprintf(buffer, SIZE, "\tbuffer size: %d\n", bufferSize());
    snprintf(buffer, SIZE, "\tchannels: %d\n", channels());
    snprintf(buffer, SIZE, "\tformat: %d\n", format());
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

String8 AudioStreamOutStub::getParameters(const String8& keys)
{
    AudioParameter param = AudioParameter(keys);
    return param.toString();
}

status_t AudioStreamOutStub::getRenderPosition(uint32_t *dspFrames)
{
    return INVALID_OPERATION;
}

// ----------------------------------------------------------------------------

status_t AudioStreamInStub::set(int *pFormat, uint32_t *pChannels, uint32_t *pRate,
                AudioSystem::audio_in_acoustics acoustics)
{
    return NO_ERROR;
}

ssize_t AudioStreamInStub::read(void* buffer, ssize_t bytes)
{
    // fake timing for audio input
    usleep(bytes * 1000000 / sizeof(int16_t) / AudioSystem::popCount(channels()) / sampleRate());
    memset(buffer, 0, bytes);
    return bytes;
}

status_t AudioStreamInStub::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "AudioStreamInStub::dump\n");
    result.append(buffer);
    snprintf(buffer, SIZE, "\tsample rate: %d\n", sampleRate());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tbuffer size: %d\n", bufferSize());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tchannels: %d\n", channels());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tformat: %d\n", format());
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

String8 AudioStreamInStub::getParameters(const String8& keys)
{
    AudioParameter param = AudioParameter(keys);
    return param.toString();
}

// ----------------------------------------------------------------------------

}; // namespace android
