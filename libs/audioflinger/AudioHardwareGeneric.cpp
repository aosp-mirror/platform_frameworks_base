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

#include <stdint.h>
#include <sys/types.h>

#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sched.h>
#include <fcntl.h>
#include <sys/ioctl.h>

#define LOG_TAG "AudioHardware"
#include <utils/Log.h>
#include <utils/String8.h>

#include "AudioHardwareGeneric.h"
#include <media/AudioRecord.h>

namespace android {

// ----------------------------------------------------------------------------

static char const * const kAudioDeviceName = "/dev/eac";

// ----------------------------------------------------------------------------

AudioHardwareGeneric::AudioHardwareGeneric()
    : mOutput(0), mInput(0),  mFd(-1), mMicMute(false)
{
    mFd = ::open(kAudioDeviceName, O_RDWR);
}

AudioHardwareGeneric::~AudioHardwareGeneric()
{
    if (mFd >= 0) ::close(mFd);
    closeOutputStream((AudioStreamOut *)mOutput);
    closeInputStream((AudioStreamIn *)mInput);
}

status_t AudioHardwareGeneric::initCheck()
{
    if (mFd >= 0) {
        if (::access(kAudioDeviceName, O_RDWR) == NO_ERROR)
            return NO_ERROR;
    }
    return NO_INIT;
}

AudioStreamOut* AudioHardwareGeneric::openOutputStream(
        uint32_t devices, int *format, uint32_t *channels, uint32_t *sampleRate, status_t *status)
{
    AutoMutex lock(mLock);

    // only one output stream allowed
    if (mOutput) {
        if (status) {
            *status = INVALID_OPERATION;
        }
        return 0;
    }

    // create new output stream
    AudioStreamOutGeneric* out = new AudioStreamOutGeneric();
    status_t lStatus = out->set(this, mFd, devices, format, channels, sampleRate);
    if (status) {
        *status = lStatus;
    }
    if (lStatus == NO_ERROR) {
        mOutput = out;
    } else {
        delete out;
    }
    return mOutput;
}

void AudioHardwareGeneric::closeOutputStream(AudioStreamOut* out) {
    if (mOutput && out == mOutput) {
        delete mOutput;
        mOutput = 0;
    }
}

AudioStreamIn* AudioHardwareGeneric::openInputStream(
        uint32_t devices, int *format, uint32_t *channels, uint32_t *sampleRate,
        status_t *status, AudioSystem::audio_in_acoustics acoustics)
{
    // check for valid input source
    if (!AudioSystem::isInputDevice((AudioSystem::audio_devices)devices)) {
        return 0;
    }

    AutoMutex lock(mLock);

    // only one input stream allowed
    if (mInput) {
        if (status) {
            *status = INVALID_OPERATION;
        }
        return 0;
    }

    // create new output stream
    AudioStreamInGeneric* in = new AudioStreamInGeneric();
    status_t lStatus = in->set(this, mFd, devices, format, channels, sampleRate, acoustics);
    if (status) {
        *status = lStatus;
    }
    if (lStatus == NO_ERROR) {
        mInput = in;
    } else {
        delete in;
    }
    return mInput;
}

void AudioHardwareGeneric::closeInputStream(AudioStreamIn* in) {
    if (mInput && in == mInput) {
        delete mInput;
        mInput = 0;
    }
}

status_t AudioHardwareGeneric::setVoiceVolume(float v)
{
    // Implement: set voice volume
    return NO_ERROR;
}

status_t AudioHardwareGeneric::setMasterVolume(float v)
{
    // Implement: set master volume
    // return error - software mixer will handle it
    return INVALID_OPERATION;
}

status_t AudioHardwareGeneric::setMicMute(bool state)
{
    mMicMute = state;
    return NO_ERROR;
}

status_t AudioHardwareGeneric::getMicMute(bool* state)
{
    *state = mMicMute;
    return NO_ERROR;
}

status_t AudioHardwareGeneric::dumpInternals(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    result.append("AudioHardwareGeneric::dumpInternals\n");
    snprintf(buffer, SIZE, "\tmFd: %d mMicMute: %s\n",  mFd, mMicMute? "true": "false");
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioHardwareGeneric::dump(int fd, const Vector<String16>& args)
{
    dumpInternals(fd, args);
    if (mInput) {
        mInput->dump(fd, args);
    }
    if (mOutput) {
        mOutput->dump(fd, args);
    }
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

status_t AudioStreamOutGeneric::set(
        AudioHardwareGeneric *hw,
        int fd,
        uint32_t devices,
        int *pFormat,
        uint32_t *pChannels,
        uint32_t *pRate)
{
    int lFormat = pFormat ? *pFormat : 0;
    uint32_t lChannels = pChannels ? *pChannels : 0;
    uint32_t lRate = pRate ? *pRate : 0;

    // fix up defaults
    if (lFormat == 0) lFormat = format();
    if (lChannels == 0) lChannels = channels();
    if (lRate == 0) lRate = sampleRate();

    // check values
    if ((lFormat != format()) ||
            (lChannels != channels()) ||
            (lRate != sampleRate())) {
        if (pFormat) *pFormat = format();
        if (pChannels) *pChannels = channels();
        if (pRate) *pRate = sampleRate();
        return BAD_VALUE;
    }

    if (pFormat) *pFormat = lFormat;
    if (pChannels) *pChannels = lChannels;
    if (pRate) *pRate = lRate;

    mAudioHardware = hw;
    mFd = fd;
    mDevice = devices;
    return NO_ERROR;
}

AudioStreamOutGeneric::~AudioStreamOutGeneric()
{
}

ssize_t AudioStreamOutGeneric::write(const void* buffer, size_t bytes)
{
    Mutex::Autolock _l(mLock);
    return ssize_t(::write(mFd, buffer, bytes));
}

status_t AudioStreamOutGeneric::standby()
{
    // Implement: audio hardware to standby mode
    return NO_ERROR;
}

status_t AudioStreamOutGeneric::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "AudioStreamOutGeneric::dump\n");
    result.append(buffer);
    snprintf(buffer, SIZE, "\tsample rate: %d\n", sampleRate());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tbuffer size: %d\n", bufferSize());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tchannels: %d\n", channels());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tformat: %d\n", format());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tdevice: %d\n", mDevice);
    result.append(buffer);
    snprintf(buffer, SIZE, "\tmAudioHardware: %p\n", mAudioHardware);
    result.append(buffer);
    snprintf(buffer, SIZE, "\tmFd: %d\n", mFd);
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioStreamOutGeneric::setParameters(const String8& keyValuePairs)
{
    AudioParameter param = AudioParameter(keyValuePairs);
    String8 key = String8(AudioParameter::keyRouting);
    status_t status = NO_ERROR;
    int device;
    LOGV("setParameters() %s", keyValuePairs.string());

    if (param.getInt(key, device) == NO_ERROR) {
        mDevice = device;
        param.remove(key);
    }

    if (param.size()) {
        status = BAD_VALUE;
    }
    return status;
}

String8 AudioStreamOutGeneric::getParameters(const String8& keys)
{
    AudioParameter param = AudioParameter(keys);
    String8 value;
    String8 key = String8(AudioParameter::keyRouting);

    if (param.get(key, value) == NO_ERROR) {
        param.addInt(key, (int)mDevice);
    }

    LOGV("getParameters() %s", param.toString().string());
    return param.toString();
}

status_t AudioStreamOutGeneric::getRenderPosition(uint32_t *dspFrames)
{
    return INVALID_OPERATION;
}

// ----------------------------------------------------------------------------

// record functions
status_t AudioStreamInGeneric::set(
        AudioHardwareGeneric *hw,
        int fd,
        uint32_t devices,
        int *pFormat,
        uint32_t *pChannels,
        uint32_t *pRate,
        AudioSystem::audio_in_acoustics acoustics)
{
    if (pFormat == 0 || pChannels == 0 || pRate == 0) return BAD_VALUE;
    LOGV("AudioStreamInGeneric::set(%p, %d, %d, %d, %u)", hw, fd, *pFormat, *pChannels, *pRate);
    // check values
    if ((*pFormat != format()) ||
        (*pChannels != channels()) ||
        (*pRate != sampleRate())) {
        LOGE("Error opening input channel");
        *pFormat = format();
        *pChannels = channels();
        *pRate = sampleRate();
        return BAD_VALUE;
    }

    mAudioHardware = hw;
    mFd = fd;
    mDevice = devices;
    return NO_ERROR;
}

AudioStreamInGeneric::~AudioStreamInGeneric()
{
}

ssize_t AudioStreamInGeneric::read(void* buffer, ssize_t bytes)
{
    AutoMutex lock(mLock);
    if (mFd < 0) {
        LOGE("Attempt to read from unopened device");
        return NO_INIT;
    }
    return ::read(mFd, buffer, bytes);
}

status_t AudioStreamInGeneric::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "AudioStreamInGeneric::dump\n");
    result.append(buffer);
    snprintf(buffer, SIZE, "\tsample rate: %d\n", sampleRate());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tbuffer size: %d\n", bufferSize());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tchannels: %d\n", channels());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tformat: %d\n", format());
    result.append(buffer);
    snprintf(buffer, SIZE, "\tdevice: %d\n", mDevice);
    result.append(buffer);
    snprintf(buffer, SIZE, "\tmAudioHardware: %p\n", mAudioHardware);
    result.append(buffer);
    snprintf(buffer, SIZE, "\tmFd: %d\n", mFd);
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioStreamInGeneric::setParameters(const String8& keyValuePairs)
{
    AudioParameter param = AudioParameter(keyValuePairs);
    String8 key = String8(AudioParameter::keyRouting);
    status_t status = NO_ERROR;
    int device;
    LOGV("setParameters() %s", keyValuePairs.string());

    if (param.getInt(key, device) == NO_ERROR) {
        mDevice = device;
        param.remove(key);
    }

    if (param.size()) {
        status = BAD_VALUE;
    }
    return status;
}

String8 AudioStreamInGeneric::getParameters(const String8& keys)
{
    AudioParameter param = AudioParameter(keys);
    String8 value;
    String8 key = String8(AudioParameter::keyRouting);

    if (param.get(key, value) == NO_ERROR) {
        param.addInt(key, (int)mDevice);
    }

    LOGV("getParameters() %s", param.toString().string());
    return param.toString();
}

// ----------------------------------------------------------------------------

}; // namespace android
