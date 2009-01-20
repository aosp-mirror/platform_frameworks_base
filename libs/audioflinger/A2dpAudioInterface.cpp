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

#include <math.h>

#define LOG_NDEBUG 0
#define LOG_TAG "A2dpAudioInterface"
#include <utils/Log.h>
#include <utils/String8.h>

#include "A2dpAudioInterface.h"
#include "audio/liba2dp.h"


namespace android {

// ----------------------------------------------------------------------------

A2dpAudioInterface::A2dpAudioInterface() :
    mOutput(0)
{
}

A2dpAudioInterface::~A2dpAudioInterface()
{
    delete mOutput;
}

status_t A2dpAudioInterface::initCheck()
{
    return 0;
}

AudioStreamOut* A2dpAudioInterface::openOutputStream(
        int format, int channelCount, uint32_t sampleRate, status_t *status)
{
    LOGD("A2dpAudioInterface::openOutputStream %d, %d, %d\n", format, channelCount, sampleRate);
    Mutex::Autolock lock(mLock);
    status_t err = 0;

    // only one output stream allowed
    if (mOutput) {
        if (status)
            *status = -1;
        return NULL;
    }

    // create new output stream
    A2dpAudioStreamOut* out = new A2dpAudioStreamOut();
    if ((err = out->set(format, channelCount, sampleRate)) == NO_ERROR) {
        mOutput = out;
    } else {
        delete out;
    }
    
    if (status)
        *status = err;
    return mOutput;
}

AudioStreamIn* A2dpAudioInterface::openInputStream(
        int format, int channelCount, uint32_t sampleRate, status_t *status)
{
    if (status)
        *status = -1;
    return NULL;
}

status_t A2dpAudioInterface::setMicMute(bool state)
{
    return 0;
}

status_t A2dpAudioInterface::getMicMute(bool* state)
{
    return 0;
}

status_t A2dpAudioInterface::setParameter(const char *key, const char *value)
{
    LOGD("setParameter %s,%s\n", key, value);
    
    if (!key || !value)
        return -EINVAL;
    
    if (strcmp(key, "a2dp_sink_address") == 0) {        
        return mOutput->setAddress(value);
    }

    return 0;
}

status_t A2dpAudioInterface::setVoiceVolume(float v)
{
    return 0;
}

status_t A2dpAudioInterface::setMasterVolume(float v)
{
    return 0;
}

status_t A2dpAudioInterface::doRouting()
{
    return 0;
}

status_t A2dpAudioInterface::dump(int fd, const Vector<String16>& args)
{
    return 0;
}

// ----------------------------------------------------------------------------

A2dpAudioInterface::A2dpAudioStreamOut::A2dpAudioStreamOut() :
    mFd(-1), mStandby(true), mStartCount(0), mRetryCount(0), mData(NULL),
    mInitialized(false)
{
    // use any address by default
    strncpy(mA2dpAddress, "00:00:00:00:00:00", sizeof(mA2dpAddress));
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::set(
        int format, int channels, uint32_t rate)
{
    LOGD("A2dpAudioStreamOut::set %d, %d, %d\n", format, channels, rate);

    // fix up defaults
    if (format == 0) format = AudioSystem::PCM_16_BIT;
    if (channels == 0) channels = channelCount();
    if (rate == 0) rate = sampleRate();

    // check values
    if ((format != AudioSystem::PCM_16_BIT) ||
            (channels != channelCount()) ||
            (rate != sampleRate()))
        return BAD_VALUE;

    return NO_ERROR;
}

A2dpAudioInterface::A2dpAudioStreamOut::~A2dpAudioStreamOut()
{
    if (mData)
        a2dp_cleanup(mData);
}

ssize_t A2dpAudioInterface::A2dpAudioStreamOut::write(const void* buffer, size_t bytes)
{    
    status_t status = NO_INIT;
    size_t remaining = bytes;

    if (!mInitialized) {
        status = a2dp_init(mA2dpAddress, 44100, 2, &mData);
        if (status < 0) {
            LOGE("a2dp_init failed err: %d\n", status);
            goto Error;
        }
        mInitialized = true;
    }
    
    while (remaining > 0) {
        status = a2dp_write(mData, buffer, remaining);
        if (status <= 0) {
            LOGE("a2dp_write failed err: %d\n", status);
            goto Error;
        }
        remaining -= status;
        buffer = ((char *)buffer) + status;
    }

    mStandby = false;
    
    return bytes;

Error:   
    // Simulate audio output timing in case of error
    usleep(bytes * 1000000 / frameSize() / sampleRate());

    return status;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::standby()
{
    int result = 0;

    if (!mStandby) {
        result = a2dp_stop(mData);
        if (result == 0)
            mStandby = true;
    }

    return result;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::setAddress(const char* address)
{
    if (strlen(address) < sizeof(mA2dpAddress))
        return -EINVAL;

    if (strcmp(address, mA2dpAddress)) {
        strcpy(mA2dpAddress, address);
        
        if (mInitialized) {
            a2dp_cleanup(mData);
            mData = NULL;
            mInitialized = false;
        }
    }
    
    return NO_ERROR;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::dump(int fd, const Vector<String16>& args)
{
    return NO_ERROR;
}


}; // namespace android
