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
        int format, int channelCount, uint32_t sampleRate, status_t *status)
{
    AudioStreamOutStub* out = new AudioStreamOutStub();
    status_t lStatus = out->set(format, channelCount, sampleRate);
    if (status) {
        *status = lStatus;
    }
    if (lStatus == NO_ERROR)
        return out;
    delete out;
    return 0;
}

AudioStreamIn* AudioHardwareStub::openInputStream(
        int format, int channelCount, uint32_t sampleRate,
        status_t *status, AudioSystem::audio_in_acoustics acoustics)
{
    AudioStreamInStub* in = new AudioStreamInStub();
    status_t lStatus = in->set(format, channelCount, sampleRate, acoustics);
    if (status) {
        *status = lStatus;
    }
    if (lStatus == NO_ERROR)
        return in;
    delete in;
    return 0;
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

status_t AudioStreamOutStub::set(int format, int channels, uint32_t rate)
{
    // fix up defaults
    if (format == 0) format = AudioSystem::PCM_16_BIT;
    if (channels == 0) channels = channelCount();
    if (rate == 0) rate = sampleRate();

    if ((format == AudioSystem::PCM_16_BIT) &&
            (channels == channelCount()) &&
            (rate == sampleRate()))
        return NO_ERROR;
    return BAD_VALUE;
}

ssize_t AudioStreamOutStub::write(const void* buffer, size_t bytes)
{
    // fake timing for audio output
    usleep(bytes * 1000000 / sizeof(int16_t) / channelCount() / sampleRate());
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
    snprintf(buffer, SIZE, "\tchannel count: %d\n", channelCount());
    snprintf(buffer, SIZE, "\tformat: %d\n", format());
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

status_t AudioStreamInStub::set(int format, int channels, uint32_t rate,
				AudioSystem::audio_in_acoustics acoustics)
{
    if ((format == AudioSystem::PCM_16_BIT) &&
            (channels == channelCount()) &&
            (rate == sampleRate()))
        return NO_ERROR;
    return BAD_VALUE;
}

ssize_t AudioStreamInStub::read(void* buffer, ssize_t bytes)
{
    // fake timing for audio input
    usleep(bytes * 1000000 / sizeof(int16_t) / channelCount() / sampleRate());
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
    snprintf(buffer, SIZE, "\tchannel count: %d\n", channelCount());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tformat: %d\n", format());
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

}; // namespace android
