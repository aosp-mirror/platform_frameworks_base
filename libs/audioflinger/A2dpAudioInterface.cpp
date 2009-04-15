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
        int format, int channelCount, uint32_t sampleRate, status_t *status,
        AudioSystem::audio_in_acoustics acoustics)
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
    if (strcmp(key, "bluetooth_enabled") == 0) {
        mOutput->setBluetoothEnabled(strcmp(value, "true") == 0);
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
    // assume BT enabled to start, this is safe because its only the
    // enabled->disabled transition we are worried about
    mBluetoothEnabled(true)
{
    // use any address by default
    strcpy(mA2dpAddress, "00:00:00:00:00:00");
    init();
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
    close();
}

ssize_t A2dpAudioInterface::A2dpAudioStreamOut::write(const void* buffer, size_t bytes)
{
    Mutex::Autolock lock(mLock);

    size_t remaining = bytes;
    status_t status = -1;

    if (!mBluetoothEnabled) {
        LOGW("A2dpAudioStreamOut::write(), but bluetooth disabled");
        goto Error;
    }

    status = init();
    if (status < 0)
        goto Error;

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

status_t A2dpAudioInterface::A2dpAudioStreamOut::init()
{
    if (!mData) {
        status_t status = a2dp_init(44100, 2, &mData);
        if (status < 0) {
            LOGE("a2dp_init failed err: %d\n", status);
            mData = NULL;
            return status;
        }
        a2dp_set_sink(mData, mA2dpAddress);
    }

    return 0;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::standby()
{
    int result = 0;

    Mutex::Autolock lock(mLock);

    if (!mStandby) {
        result = a2dp_stop(mData);
        if (result == 0)
            mStandby = true;
    }

    return result;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::setAddress(const char* address)
{
    Mutex::Autolock lock(mLock);

    if (strlen(address) != strlen("00:00:00:00:00:00"))
        return -EINVAL;

    strcpy(mA2dpAddress, address);
    if (mData)
        a2dp_set_sink(mData, mA2dpAddress);

    return NO_ERROR;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::setBluetoothEnabled(bool enabled)
{
    LOGD("setBluetoothEnabled %d", enabled);

    Mutex::Autolock lock(mLock);

    mBluetoothEnabled = enabled;
    if (!enabled) {
        return close_l();
    }
    return NO_ERROR;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::close()
{
    Mutex::Autolock lock(mLock);
    return close_l();
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::close_l()
{
    if (mData) {
        a2dp_cleanup(mData);
        mData = NULL;
    }
    return NO_ERROR;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::dump(int fd, const Vector<String16>& args)
{
    return NO_ERROR;
}


}; // namespace android
