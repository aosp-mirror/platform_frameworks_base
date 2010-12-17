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

//#define LOG_NDEBUG 0
#define LOG_TAG "A2dpAudioInterface"
#include <utils/Log.h>
#include <utils/String8.h>

#include "A2dpAudioInterface.h"
#include "audio/liba2dp.h"
#include <hardware_legacy/power.h>

namespace android {

static const char *sA2dpWakeLock = "A2dpOutputStream";
#define MAX_WRITE_RETRIES  5

// ----------------------------------------------------------------------------

//AudioHardwareInterface* A2dpAudioInterface::createA2dpInterface()
//{
//    AudioHardwareInterface* hw = 0;
//
//    hw = AudioHardwareInterface::create();
//    LOGD("new A2dpAudioInterface(hw: %p)", hw);
//    hw = new A2dpAudioInterface(hw);
//    return hw;
//}

A2dpAudioInterface::A2dpAudioInterface(AudioHardwareInterface* hw) :
    mOutput(0), mHardwareInterface(hw), mBluetoothEnabled(true), mSuspended(false)
{
}

A2dpAudioInterface::~A2dpAudioInterface()
{
    closeOutputStream((AudioStreamOut *)mOutput);
    delete mHardwareInterface;
}

status_t A2dpAudioInterface::initCheck()
{
    if (mHardwareInterface == 0) return NO_INIT;
    return mHardwareInterface->initCheck();
}

AudioStreamOut* A2dpAudioInterface::openOutputStream(
        uint32_t devices, int *format, uint32_t *channels, uint32_t *sampleRate, status_t *status)
{
    if (!AudioSystem::isA2dpDevice((AudioSystem::audio_devices)devices)) {
        LOGV("A2dpAudioInterface::openOutputStream() open HW device: %x", devices);
        return mHardwareInterface->openOutputStream(devices, format, channels, sampleRate, status);
    }

    status_t err = 0;

    // only one output stream allowed
    if (mOutput) {
        if (status)
            *status = -1;
        return NULL;
    }

    // create new output stream
    A2dpAudioStreamOut* out = new A2dpAudioStreamOut();
    if ((err = out->set(devices, format, channels, sampleRate)) == NO_ERROR) {
        mOutput = out;
        mOutput->setBluetoothEnabled(mBluetoothEnabled);
        mOutput->setSuspended(mSuspended);
    } else {
        delete out;
    }

    if (status)
        *status = err;
    return mOutput;
}

void A2dpAudioInterface::closeOutputStream(AudioStreamOut* out) {
    if (mOutput == 0 || mOutput != out) {
        mHardwareInterface->closeOutputStream(out);
    }
    else {
        delete mOutput;
        mOutput = 0;
    }
}


AudioStreamIn* A2dpAudioInterface::openInputStream(
        uint32_t devices, int *format, uint32_t *channels, uint32_t *sampleRate, status_t *status,
        AudioSystem::audio_in_acoustics acoustics)
{
    return mHardwareInterface->openInputStream(devices, format, channels, sampleRate, status, acoustics);
}

void A2dpAudioInterface::closeInputStream(AudioStreamIn* in)
{
    return mHardwareInterface->closeInputStream(in);
}

status_t A2dpAudioInterface::setMode(int mode)
{
    return mHardwareInterface->setMode(mode);
}

status_t A2dpAudioInterface::setMicMute(bool state)
{
    return mHardwareInterface->setMicMute(state);
}

status_t A2dpAudioInterface::getMicMute(bool* state)
{
    return mHardwareInterface->getMicMute(state);
}

status_t A2dpAudioInterface::setParameters(const String8& keyValuePairs)
{
    AudioParameter param = AudioParameter(keyValuePairs);
    String8 value;
    String8 key;
    status_t status = NO_ERROR;

    LOGV("setParameters() %s", keyValuePairs.string());

    key = "bluetooth_enabled";
    if (param.get(key, value) == NO_ERROR) {
        mBluetoothEnabled = (value == "true");
        if (mOutput) {
            mOutput->setBluetoothEnabled(mBluetoothEnabled);
        }
        param.remove(key);
    }
    key = String8("A2dpSuspended");
    if (param.get(key, value) == NO_ERROR) {
        mSuspended = (value == "true");
        if (mOutput) {
            mOutput->setSuspended(mSuspended);
        }
        param.remove(key);
    }

    if (param.size()) {
        status_t hwStatus = mHardwareInterface->setParameters(param.toString());
        if (status == NO_ERROR) {
            status = hwStatus;
        }
    }

    return status;
}

String8 A2dpAudioInterface::getParameters(const String8& keys)
{
    AudioParameter param = AudioParameter(keys);
    AudioParameter a2dpParam = AudioParameter();
    String8 value;
    String8 key;

    key = "bluetooth_enabled";
    if (param.get(key, value) == NO_ERROR) {
        value = mBluetoothEnabled ? "true" : "false";
        a2dpParam.add(key, value);
        param.remove(key);
    }
    key = "A2dpSuspended";
    if (param.get(key, value) == NO_ERROR) {
        value = mSuspended ? "true" : "false";
        a2dpParam.add(key, value);
        param.remove(key);
    }

    String8 keyValuePairs  = a2dpParam.toString();

    if (param.size()) {
        if (keyValuePairs != "") {
            keyValuePairs += ";";
        }
        keyValuePairs += mHardwareInterface->getParameters(param.toString());
    }

    LOGV("getParameters() %s", keyValuePairs.string());
    return keyValuePairs;
}

size_t A2dpAudioInterface::getInputBufferSize(uint32_t sampleRate, int format, int channelCount)
{
    return mHardwareInterface->getInputBufferSize(sampleRate, format, channelCount);
}

status_t A2dpAudioInterface::setVoiceVolume(float v)
{
    return mHardwareInterface->setVoiceVolume(v);
}

status_t A2dpAudioInterface::setMasterVolume(float v)
{
    return mHardwareInterface->setMasterVolume(v);
}

status_t A2dpAudioInterface::dump(int fd, const Vector<String16>& args)
{
    return mHardwareInterface->dumpState(fd, args);
}

// ----------------------------------------------------------------------------

A2dpAudioInterface::A2dpAudioStreamOut::A2dpAudioStreamOut() :
    mFd(-1), mStandby(true), mStartCount(0), mRetryCount(0), mData(NULL),
    // assume BT enabled to start, this is safe because its only the
    // enabled->disabled transition we are worried about
    mBluetoothEnabled(true), mDevice(0), mClosing(false), mSuspended(false)
{
    // use any address by default
    strcpy(mA2dpAddress, "00:00:00:00:00:00");
    init();
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::set(
        uint32_t device, int *pFormat, uint32_t *pChannels, uint32_t *pRate)
{
    int lFormat = pFormat ? *pFormat : 0;
    uint32_t lChannels = pChannels ? *pChannels : 0;
    uint32_t lRate = pRate ? *pRate : 0;

    LOGD("A2dpAudioStreamOut::set %x, %d, %d, %d\n", device, lFormat, lChannels, lRate);

    // fix up defaults
    if (lFormat == 0) lFormat = format();
    if (lChannels == 0) lChannels = channels();
    if (lRate == 0) lRate = sampleRate();

    // check values
    if ((lFormat != format()) ||
            (lChannels != channels()) ||
            (lRate != sampleRate())){
        if (pFormat) *pFormat = format();
        if (pChannels) *pChannels = channels();
        if (pRate) *pRate = sampleRate();
        return BAD_VALUE;
    }

    if (pFormat) *pFormat = lFormat;
    if (pChannels) *pChannels = lChannels;
    if (pRate) *pRate = lRate;

    mDevice = device;
    mBufferDurationUs = ((bufferSize() * 1000 )/ frameSize() / sampleRate()) * 1000;
    return NO_ERROR;
}

A2dpAudioInterface::A2dpAudioStreamOut::~A2dpAudioStreamOut()
{
    LOGV("A2dpAudioStreamOut destructor");
    close();
    LOGV("A2dpAudioStreamOut destructor returning from close()");
}

ssize_t A2dpAudioInterface::A2dpAudioStreamOut::write(const void* buffer, size_t bytes)
{
    status_t status = -1;
    {
        Mutex::Autolock lock(mLock);

        size_t remaining = bytes;

        if (!mBluetoothEnabled || mClosing || mSuspended) {
            LOGV("A2dpAudioStreamOut::write(), but bluetooth disabled \
                   mBluetoothEnabled %d, mClosing %d, mSuspended %d",
                    mBluetoothEnabled, mClosing, mSuspended);
            goto Error;
        }

        if (mStandby) {
            acquire_wake_lock (PARTIAL_WAKE_LOCK, sA2dpWakeLock);
            mStandby = false;
            mLastWriteTime = systemTime();
        }

        status = init();
        if (status < 0)
            goto Error;

        int retries = MAX_WRITE_RETRIES;
        while (remaining > 0 && retries) {
            status = a2dp_write(mData, buffer, remaining);
            if (status < 0) {
                LOGE("a2dp_write failed err: %d\n", status);
                goto Error;
            }
            if (status == 0) {
                retries--;
            }
            remaining -= status;
            buffer = (char *)buffer + status;
        }

        // if A2DP sink runs abnormally fast, sleep a little so that audioflinger mixer thread
        // does no spin and starve other threads.
        // NOTE: It is likely that the A2DP headset is being disconnected
        nsecs_t now = systemTime();
        if ((uint32_t)ns2us(now - mLastWriteTime) < (mBufferDurationUs >> 2)) {
            LOGV("A2DP sink runs too fast");
            usleep(mBufferDurationUs - (uint32_t)ns2us(now - mLastWriteTime));
        }
        mLastWriteTime = now;
        return bytes;

    }
Error:

    standby();

    // Simulate audio output timing in case of error
    usleep(mBufferDurationUs);

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
    Mutex::Autolock lock(mLock);
    return standby_l();
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::standby_l()
{
    int result = NO_ERROR;

    if (!mStandby) {
        LOGV_IF(mClosing || !mBluetoothEnabled, "Standby skip stop: closing %d enabled %d",
                mClosing, mBluetoothEnabled);
        if (!mClosing && mBluetoothEnabled) {
            result = a2dp_stop(mData);
        }
        release_wake_lock(sA2dpWakeLock);
        mStandby = true;
    }

    return result;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::setParameters(const String8& keyValuePairs)
{
    AudioParameter param = AudioParameter(keyValuePairs);
    String8 value;
    String8 key = String8("a2dp_sink_address");
    status_t status = NO_ERROR;
    int device;
    LOGV("A2dpAudioStreamOut::setParameters() %s", keyValuePairs.string());

    if (param.get(key, value) == NO_ERROR) {
        if (value.length() != strlen("00:00:00:00:00:00")) {
            status = BAD_VALUE;
        } else {
            setAddress(value.string());
        }
        param.remove(key);
    }
    key = String8("closing");
    if (param.get(key, value) == NO_ERROR) {
        mClosing = (value == "true");
        if (mClosing) {
            standby();
        }
        param.remove(key);
    }
    key = AudioParameter::keyRouting;
    if (param.getInt(key, device) == NO_ERROR) {
        if (AudioSystem::isA2dpDevice((AudioSystem::audio_devices)device)) {
            mDevice = device;
            status = NO_ERROR;
        } else {
            status = BAD_VALUE;
        }
        param.remove(key);
    }

    if (param.size()) {
        status = BAD_VALUE;
    }
    return status;
}

String8 A2dpAudioInterface::A2dpAudioStreamOut::getParameters(const String8& keys)
{
    AudioParameter param = AudioParameter(keys);
    String8 value;
    String8 key = String8("a2dp_sink_address");

    if (param.get(key, value) == NO_ERROR) {
        value = mA2dpAddress;
        param.add(key, value);
    }
    key = AudioParameter::keyRouting;
    if (param.get(key, value) == NO_ERROR) {
        param.addInt(key, (int)mDevice);
    }

    LOGV("A2dpAudioStreamOut::getParameters() %s", param.toString().string());
    return param.toString();
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

status_t A2dpAudioInterface::A2dpAudioStreamOut::setSuspended(bool onOff)
{
    LOGV("setSuspended %d", onOff);
    mSuspended = onOff;
    standby();
    return NO_ERROR;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::close()
{
    Mutex::Autolock lock(mLock);
    LOGV("A2dpAudioStreamOut::close() calling close_l()");
    return close_l();
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::close_l()
{
    standby_l();
    if (mData) {
        LOGV("A2dpAudioStreamOut::close_l() calling a2dp_cleanup(mData)");
        a2dp_cleanup(mData);
        mData = NULL;
    }
    return NO_ERROR;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::dump(int fd, const Vector<String16>& args)
{
    return NO_ERROR;
}

status_t A2dpAudioInterface::A2dpAudioStreamOut::getRenderPosition(uint32_t *driverFrames)
{
    //TODO: enable when supported by driver
    return INVALID_OPERATION;
}

}; // namespace android
