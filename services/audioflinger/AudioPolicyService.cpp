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

#define LOG_TAG "AudioPolicyService"
//#define LOG_NDEBUG 0

#undef __STRICT_ANSI__
#define __STDINT_LIMITS
#define __STDC_LIMIT_MACROS
#include <stdint.h>

#include <sys/time.h>
#include <binder/IServiceManager.h>
#include <utils/Log.h>
#include <cutils/properties.h>
#include <binder/IPCThreadState.h>
#include <utils/String16.h>
#include <utils/threads.h>
#include "AudioPolicyService.h"
#include <cutils/properties.h>
#include <dlfcn.h>
#include <hardware_legacy/power.h>

#include <hardware/hardware.h>
#include <system/audio.h>
#include <hardware/audio_policy.h>
#include <hardware/audio_policy_hal.h>

namespace android {

static const char *kDeadlockedString = "AudioPolicyService may be deadlocked\n";
static const char *kCmdDeadlockedString = "AudioPolicyService command thread may be deadlocked\n";

static const int kDumpLockRetries = 50;
static const int kDumpLockSleep = 20000;

static bool checkPermission() {
#ifndef HAVE_ANDROID_OS
    return true;
#endif
    if (getpid() == IPCThreadState::self()->getCallingPid()) return true;
    bool ok = checkCallingPermission(String16("android.permission.MODIFY_AUDIO_SETTINGS"));
    if (!ok) LOGE("Request requires android.permission.MODIFY_AUDIO_SETTINGS");
    return ok;
}

namespace {
    extern struct audio_policy_service_ops aps_ops;
};

// ----------------------------------------------------------------------------

AudioPolicyService::AudioPolicyService()
    : BnAudioPolicyService() , mpAudioPolicyDev(NULL) , mpAudioPolicy(NULL)
{
    char value[PROPERTY_VALUE_MAX];
    const struct hw_module_t *module;
    int forced_val;
    int rc;

    Mutex::Autolock _l(mLock);

    // start tone playback thread
    mTonePlaybackThread = new AudioCommandThread(String8(""));
    // start audio commands thread
    mAudioCommandThread = new AudioCommandThread(String8("ApmCommandThread"));

    /* instantiate the audio policy manager */
    rc = hw_get_module(AUDIO_POLICY_HARDWARE_MODULE_ID, &module);
    if (rc)
        return;

    rc = audio_policy_dev_open(module, &mpAudioPolicyDev);
    LOGE_IF(rc, "couldn't open audio policy device (%s)", strerror(-rc));
    if (rc)
        return;

    rc = mpAudioPolicyDev->create_audio_policy(mpAudioPolicyDev, &aps_ops, this,
                                               &mpAudioPolicy);
    LOGE_IF(rc, "couldn't create audio policy (%s)", strerror(-rc));
    if (rc)
        return;

    rc = mpAudioPolicy->init_check(mpAudioPolicy);
    LOGE_IF(rc, "couldn't init_check the audio policy (%s)", strerror(-rc));
    if (rc)
        return;

    property_get("ro.camera.sound.forced", value, "0");
    forced_val = strtol(value, NULL, 0);
    mpAudioPolicy->set_can_mute_enforced_audible(mpAudioPolicy, !forced_val);

    LOGI("Loaded audio policy from %s (%s)", module->name, module->id);
}

AudioPolicyService::~AudioPolicyService()
{
    mTonePlaybackThread->exit();
    mTonePlaybackThread.clear();
    mAudioCommandThread->exit();
    mAudioCommandThread.clear();

    if (mpAudioPolicy && mpAudioPolicyDev)
        mpAudioPolicyDev->destroy_audio_policy(mpAudioPolicyDev, mpAudioPolicy);
    if (mpAudioPolicyDev)
        audio_policy_dev_close(mpAudioPolicyDev);
}

status_t AudioPolicyService::setDeviceConnectionState(audio_devices_t device,
                                                  audio_policy_dev_state_t state,
                                                  const char *device_address)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (!audio_is_output_device(device) && !audio_is_input_device(device)) {
        return BAD_VALUE;
    }
    if (state != AUDIO_POLICY_DEVICE_STATE_AVAILABLE &&
            state != AUDIO_POLICY_DEVICE_STATE_UNAVAILABLE) {
        return BAD_VALUE;
    }

    LOGV("setDeviceConnectionState() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->set_device_connection_state(mpAudioPolicy, device,
                                                      state, device_address);
}

audio_policy_dev_state_t AudioPolicyService::getDeviceConnectionState(
                                                              audio_devices_t device,
                                                              const char *device_address)
{
    if (mpAudioPolicy == NULL) {
        return AUDIO_POLICY_DEVICE_STATE_UNAVAILABLE;
    }
    if (!checkPermission()) {
        return AUDIO_POLICY_DEVICE_STATE_UNAVAILABLE;
    }
    return mpAudioPolicy->get_device_connection_state(mpAudioPolicy, device,
                                                      device_address);
}

status_t AudioPolicyService::setPhoneState(int state)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (state < 0 || state >= AUDIO_MODE_CNT) {
        return BAD_VALUE;
    }

    LOGV("setPhoneState() tid %d", gettid());

    // TODO: check if it is more appropriate to do it in platform specific policy manager
    AudioSystem::setMode(state);

    Mutex::Autolock _l(mLock);
    mpAudioPolicy->set_phone_state(mpAudioPolicy, state);
    return NO_ERROR;
}

status_t AudioPolicyService::setRingerMode(uint32_t mode, uint32_t mask)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }

    mpAudioPolicy->set_ringer_mode(mpAudioPolicy, mode, mask);
    return NO_ERROR;
}

status_t AudioPolicyService::setForceUse(audio_policy_force_use_t usage,
                                         audio_policy_forced_cfg_t config)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (usage < 0 || usage >= AUDIO_POLICY_FORCE_USE_CNT) {
        return BAD_VALUE;
    }
    if (config < 0 || config >= AUDIO_POLICY_FORCE_CFG_CNT) {
        return BAD_VALUE;
    }
    LOGV("setForceUse() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    mpAudioPolicy->set_force_use(mpAudioPolicy, usage, config);
    return NO_ERROR;
}

audio_policy_forced_cfg_t AudioPolicyService::getForceUse(audio_policy_force_use_t usage)
{
    if (mpAudioPolicy == NULL) {
        return AUDIO_POLICY_FORCE_NONE;
    }
    if (!checkPermission()) {
        return AUDIO_POLICY_FORCE_NONE;
    }
    if (usage < 0 || usage >= AUDIO_POLICY_FORCE_USE_CNT) {
        return AUDIO_POLICY_FORCE_NONE;
    }
    return mpAudioPolicy->get_force_use(mpAudioPolicy, usage);
}

audio_io_handle_t AudioPolicyService::getOutput(audio_stream_type_t stream,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    audio_policy_output_flags_t flags)
{
    if (mpAudioPolicy == NULL) {
        return 0;
    }
    LOGV("getOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->get_output(mpAudioPolicy, stream, samplingRate, format, channels, flags);
}

status_t AudioPolicyService::startOutput(audio_io_handle_t output,
                                         audio_stream_type_t stream,
                                         int session)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    LOGV("startOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->start_output(mpAudioPolicy, output, stream, session);
}

status_t AudioPolicyService::stopOutput(audio_io_handle_t output,
                                        audio_stream_type_t stream,
                                        int session)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    LOGV("stopOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->stop_output(mpAudioPolicy, output, stream, session);
}

void AudioPolicyService::releaseOutput(audio_io_handle_t output)
{
    if (mpAudioPolicy == NULL) {
        return;
    }
    LOGV("releaseOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    mpAudioPolicy->release_output(mpAudioPolicy, output);
}

audio_io_handle_t AudioPolicyService::getInput(int inputSource,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    audio_in_acoustics_t acoustics)
{
    if (mpAudioPolicy == NULL) {
        return 0;
    }
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->get_input(mpAudioPolicy, inputSource, samplingRate, format, channels, acoustics);
}

status_t AudioPolicyService::startInput(audio_io_handle_t input)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->start_input(mpAudioPolicy, input);
}

status_t AudioPolicyService::stopInput(audio_io_handle_t input)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->stop_input(mpAudioPolicy, input);
}

void AudioPolicyService::releaseInput(audio_io_handle_t input)
{
    if (mpAudioPolicy == NULL) {
        return;
    }
    Mutex::Autolock _l(mLock);
    mpAudioPolicy->release_input(mpAudioPolicy, input);
}

status_t AudioPolicyService::initStreamVolume(audio_stream_type_t stream,
                                            int indexMin,
                                            int indexMax)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (stream < 0 || stream >= AUDIO_STREAM_CNT) {
        return BAD_VALUE;
    }
    mpAudioPolicy->init_stream_volume(mpAudioPolicy, stream, indexMin, indexMax);
    return NO_ERROR;
}

status_t AudioPolicyService::setStreamVolumeIndex(audio_stream_type_t stream, int index)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (stream < 0 || stream >= AUDIO_STREAM_CNT) {
        return BAD_VALUE;
    }

    return mpAudioPolicy->set_stream_volume_index(mpAudioPolicy, stream, index);
}

status_t AudioPolicyService::getStreamVolumeIndex(audio_stream_type_t stream, int *index)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (stream < 0 || stream >= AUDIO_STREAM_CNT) {
        return BAD_VALUE;
    }
    return mpAudioPolicy->get_stream_volume_index(mpAudioPolicy, stream, index);
}

uint32_t AudioPolicyService::getStrategyForStream(audio_stream_type_t stream)
{
    if (mpAudioPolicy == NULL) {
        return 0;
    }
    return mpAudioPolicy->get_strategy_for_stream(mpAudioPolicy, stream);
}

uint32_t AudioPolicyService::getDevicesForStream(audio_stream_type_t stream)
{
    if (mpAudioPolicy == NULL) {
        return 0;
    }
    return mpAudioPolicy->get_devices_for_stream(mpAudioPolicy, stream);
}

audio_io_handle_t AudioPolicyService::getOutputForEffect(effect_descriptor_t *desc)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->get_output_for_effect(mpAudioPolicy, desc);
}

status_t AudioPolicyService::registerEffect(effect_descriptor_t *desc,
                                audio_io_handle_t output,
                                uint32_t strategy,
                                int session,
                                int id)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    return mpAudioPolicy->register_effect(mpAudioPolicy, desc, output, strategy, session, id);
}

status_t AudioPolicyService::unregisterEffect(int id)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    return mpAudioPolicy->unregister_effect(mpAudioPolicy, id);
}

bool AudioPolicyService::isStreamActive(int stream, uint32_t inPastMs) const
{
    if (mpAudioPolicy == NULL) {
        return 0;
    }
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->is_stream_active(mpAudioPolicy, stream, inPastMs);
}

void AudioPolicyService::binderDied(const wp<IBinder>& who) {
    LOGW("binderDied() %p, tid %d, calling tid %d", who.unsafe_get(), gettid(),
            IPCThreadState::self()->getCallingPid());
}

static bool tryLock(Mutex& mutex)
{
    bool locked = false;
    for (int i = 0; i < kDumpLockRetries; ++i) {
        if (mutex.tryLock() == NO_ERROR) {
            locked = true;
            break;
        }
        usleep(kDumpLockSleep);
    }
    return locked;
}

status_t AudioPolicyService::dumpInternals(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    snprintf(buffer, SIZE, "PolicyManager Interface: %p\n", mpAudioPolicy);
    result.append(buffer);
    snprintf(buffer, SIZE, "Command Thread: %p\n", mAudioCommandThread.get());
    result.append(buffer);
    snprintf(buffer, SIZE, "Tones Thread: %p\n", mTonePlaybackThread.get());
    result.append(buffer);

    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioPolicyService::dump(int fd, const Vector<String16>& args)
{
    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        dumpPermissionDenial(fd);
    } else {
        bool locked = tryLock(mLock);
        if (!locked) {
            String8 result(kDeadlockedString);
            write(fd, result.string(), result.size());
        }

        dumpInternals(fd);
        if (mAudioCommandThread != NULL) {
            mAudioCommandThread->dump(fd);
        }
        if (mTonePlaybackThread != NULL) {
            mTonePlaybackThread->dump(fd);
        }

        if (mpAudioPolicy) {
            mpAudioPolicy->dump(mpAudioPolicy, fd);
        }

        if (locked) mLock.unlock();
    }
    return NO_ERROR;
}

status_t AudioPolicyService::dumpPermissionDenial(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "Permission Denial: "
            "can't dump AudioPolicyService from pid=%d, uid=%d\n",
            IPCThreadState::self()->getCallingPid(),
            IPCThreadState::self()->getCallingUid());
    result.append(buffer);
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

status_t AudioPolicyService::onTransact(
        uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    return BnAudioPolicyService::onTransact(code, data, reply, flags);
}


// -----------  AudioPolicyService::AudioCommandThread implementation ----------

AudioPolicyService::AudioCommandThread::AudioCommandThread(String8 name)
    : Thread(false), mName(name)
{
    mpToneGenerator = NULL;
}


AudioPolicyService::AudioCommandThread::~AudioCommandThread()
{
    if (mName != "" && !mAudioCommands.isEmpty()) {
        release_wake_lock(mName.string());
    }
    mAudioCommands.clear();
    if (mpToneGenerator != NULL) delete mpToneGenerator;
}

void AudioPolicyService::AudioCommandThread::onFirstRef()
{
    if (mName != "") {
        run(mName.string(), ANDROID_PRIORITY_AUDIO);
    } else {
        run("AudioCommandThread", ANDROID_PRIORITY_AUDIO);
    }
}

bool AudioPolicyService::AudioCommandThread::threadLoop()
{
    nsecs_t waitTime = INT64_MAX;

    mLock.lock();
    while (!exitPending())
    {
        while(!mAudioCommands.isEmpty()) {
            nsecs_t curTime = systemTime();
            // commands are sorted by increasing time stamp: execute them from index 0 and up
            if (mAudioCommands[0]->mTime <= curTime) {
                AudioCommand *command = mAudioCommands[0];
                mAudioCommands.removeAt(0);
                mLastCommand = *command;

                switch (command->mCommand) {
                case START_TONE: {
                    mLock.unlock();
                    ToneData *data = (ToneData *)command->mParam;
                    LOGV("AudioCommandThread() processing start tone %d on stream %d",
                            data->mType, data->mStream);
                    if (mpToneGenerator != NULL)
                        delete mpToneGenerator;
                    mpToneGenerator = new ToneGenerator(data->mStream, 1.0);
                    mpToneGenerator->startTone(data->mType);
                    delete data;
                    mLock.lock();
                    }break;
                case STOP_TONE: {
                    mLock.unlock();
                    LOGV("AudioCommandThread() processing stop tone");
                    if (mpToneGenerator != NULL) {
                        mpToneGenerator->stopTone();
                        delete mpToneGenerator;
                        mpToneGenerator = NULL;
                    }
                    mLock.lock();
                    }break;
                case SET_VOLUME: {
                    VolumeData *data = (VolumeData *)command->mParam;
                    LOGV("AudioCommandThread() processing set volume stream %d, \
                            volume %f, output %d", data->mStream, data->mVolume, data->mIO);
                    command->mStatus = AudioSystem::setStreamVolume(data->mStream,
                                                                    data->mVolume,
                                                                    data->mIO);
                    if (command->mWaitStatus) {
                        command->mCond.signal();
                        mWaitWorkCV.wait(mLock);
                    }
                    delete data;
                    }break;
                case SET_PARAMETERS: {
                     ParametersData *data = (ParametersData *)command->mParam;
                     LOGV("AudioCommandThread() processing set parameters string %s, io %d",
                             data->mKeyValuePairs.string(), data->mIO);
                     command->mStatus = AudioSystem::setParameters(data->mIO, data->mKeyValuePairs);
                     if (command->mWaitStatus) {
                         command->mCond.signal();
                         mWaitWorkCV.wait(mLock);
                     }
                     delete data;
                     }break;
                case SET_VOICE_VOLUME: {
                    VoiceVolumeData *data = (VoiceVolumeData *)command->mParam;
                    LOGV("AudioCommandThread() processing set voice volume volume %f",
                            data->mVolume);
                    command->mStatus = AudioSystem::setVoiceVolume(data->mVolume);
                    if (command->mWaitStatus) {
                        command->mCond.signal();
                        mWaitWorkCV.wait(mLock);
                    }
                    delete data;
                    }break;
                default:
                    LOGW("AudioCommandThread() unknown command %d", command->mCommand);
                }
                delete command;
                waitTime = INT64_MAX;
            } else {
                waitTime = mAudioCommands[0]->mTime - curTime;
                break;
            }
        }
        // release delayed commands wake lock
        if (mName != "" && mAudioCommands.isEmpty()) {
            release_wake_lock(mName.string());
        }
        LOGV("AudioCommandThread() going to sleep");
        mWaitWorkCV.waitRelative(mLock, waitTime);
        LOGV("AudioCommandThread() waking up");
    }
    mLock.unlock();
    return false;
}

status_t AudioPolicyService::AudioCommandThread::dump(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    snprintf(buffer, SIZE, "AudioCommandThread %p Dump\n", this);
    result.append(buffer);
    write(fd, result.string(), result.size());

    bool locked = tryLock(mLock);
    if (!locked) {
        String8 result2(kCmdDeadlockedString);
        write(fd, result2.string(), result2.size());
    }

    snprintf(buffer, SIZE, "- Commands:\n");
    result = String8(buffer);
    result.append("   Command Time        Wait pParam\n");
    for (int i = 0; i < (int)mAudioCommands.size(); i++) {
        mAudioCommands[i]->dump(buffer, SIZE);
        result.append(buffer);
    }
    result.append("  Last Command\n");
    mLastCommand.dump(buffer, SIZE);
    result.append(buffer);

    write(fd, result.string(), result.size());

    if (locked) mLock.unlock();

    return NO_ERROR;
}

void AudioPolicyService::AudioCommandThread::startToneCommand(int type, int stream)
{
    AudioCommand *command = new AudioCommand();
    command->mCommand = START_TONE;
    ToneData *data = new ToneData();
    data->mType = type;
    data->mStream = stream;
    command->mParam = (void *)data;
    command->mWaitStatus = false;
    Mutex::Autolock _l(mLock);
    insertCommand_l(command);
    LOGV("AudioCommandThread() adding tone start type %d, stream %d", type, stream);
    mWaitWorkCV.signal();
}

void AudioPolicyService::AudioCommandThread::stopToneCommand()
{
    AudioCommand *command = new AudioCommand();
    command->mCommand = STOP_TONE;
    command->mParam = NULL;
    command->mWaitStatus = false;
    Mutex::Autolock _l(mLock);
    insertCommand_l(command);
    LOGV("AudioCommandThread() adding tone stop");
    mWaitWorkCV.signal();
}

status_t AudioPolicyService::AudioCommandThread::volumeCommand(int stream,
                                                               float volume,
                                                               int output,
                                                               int delayMs)
{
    status_t status = NO_ERROR;

    AudioCommand *command = new AudioCommand();
    command->mCommand = SET_VOLUME;
    VolumeData *data = new VolumeData();
    data->mStream = stream;
    data->mVolume = volume;
    data->mIO = output;
    command->mParam = data;
    if (delayMs == 0) {
        command->mWaitStatus = true;
    } else {
        command->mWaitStatus = false;
    }
    Mutex::Autolock _l(mLock);
    insertCommand_l(command, delayMs);
    LOGV("AudioCommandThread() adding set volume stream %d, volume %f, output %d",
            stream, volume, output);
    mWaitWorkCV.signal();
    if (command->mWaitStatus) {
        command->mCond.wait(mLock);
        status =  command->mStatus;
        mWaitWorkCV.signal();
    }
    return status;
}

status_t AudioPolicyService::AudioCommandThread::parametersCommand(int ioHandle,
                                                                   const char *keyValuePairs,
                                                                   int delayMs)
{
    status_t status = NO_ERROR;

    AudioCommand *command = new AudioCommand();
    command->mCommand = SET_PARAMETERS;
    ParametersData *data = new ParametersData();
    data->mIO = ioHandle;
    data->mKeyValuePairs = String8(keyValuePairs);
    command->mParam = data;
    if (delayMs == 0) {
        command->mWaitStatus = true;
    } else {
        command->mWaitStatus = false;
    }
    Mutex::Autolock _l(mLock);
    insertCommand_l(command, delayMs);
    LOGV("AudioCommandThread() adding set parameter string %s, io %d ,delay %d",
            keyValuePairs, ioHandle, delayMs);
    mWaitWorkCV.signal();
    if (command->mWaitStatus) {
        command->mCond.wait(mLock);
        status =  command->mStatus;
        mWaitWorkCV.signal();
    }
    return status;
}

status_t AudioPolicyService::AudioCommandThread::voiceVolumeCommand(float volume, int delayMs)
{
    status_t status = NO_ERROR;

    AudioCommand *command = new AudioCommand();
    command->mCommand = SET_VOICE_VOLUME;
    VoiceVolumeData *data = new VoiceVolumeData();
    data->mVolume = volume;
    command->mParam = data;
    if (delayMs == 0) {
        command->mWaitStatus = true;
    } else {
        command->mWaitStatus = false;
    }
    Mutex::Autolock _l(mLock);
    insertCommand_l(command, delayMs);
    LOGV("AudioCommandThread() adding set voice volume volume %f", volume);
    mWaitWorkCV.signal();
    if (command->mWaitStatus) {
        command->mCond.wait(mLock);
        status =  command->mStatus;
        mWaitWorkCV.signal();
    }
    return status;
}

// insertCommand_l() must be called with mLock held
void AudioPolicyService::AudioCommandThread::insertCommand_l(AudioCommand *command, int delayMs)
{
    ssize_t i;
    Vector <AudioCommand *> removedCommands;

    command->mTime = systemTime() + milliseconds(delayMs);

    // acquire wake lock to make sure delayed commands are processed
    if (mName != "" && mAudioCommands.isEmpty()) {
        acquire_wake_lock(PARTIAL_WAKE_LOCK, mName.string());
    }

    // check same pending commands with later time stamps and eliminate them
    for (i = mAudioCommands.size()-1; i >= 0; i--) {
        AudioCommand *command2 = mAudioCommands[i];
        // commands are sorted by increasing time stamp: no need to scan the rest of mAudioCommands
        if (command2->mTime <= command->mTime) break;
        if (command2->mCommand != command->mCommand) continue;

        switch (command->mCommand) {
        case SET_PARAMETERS: {
            ParametersData *data = (ParametersData *)command->mParam;
            ParametersData *data2 = (ParametersData *)command2->mParam;
            if (data->mIO != data2->mIO) break;
            LOGV("Comparing parameter command %s to new command %s",
                    data2->mKeyValuePairs.string(), data->mKeyValuePairs.string());
            AudioParameter param = AudioParameter(data->mKeyValuePairs);
            AudioParameter param2 = AudioParameter(data2->mKeyValuePairs);
            for (size_t j = 0; j < param.size(); j++) {
               String8 key;
               String8 value;
               param.getAt(j, key, value);
               for (size_t k = 0; k < param2.size(); k++) {
                  String8 key2;
                  String8 value2;
                  param2.getAt(k, key2, value2);
                  if (key2 == key) {
                      param2.remove(key2);
                      LOGV("Filtering out parameter %s", key2.string());
                      break;
                  }
               }
            }
            // if all keys have been filtered out, remove the command.
            // otherwise, update the key value pairs
            if (param2.size() == 0) {
                removedCommands.add(command2);
            } else {
                data2->mKeyValuePairs = param2.toString();
            }
        } break;

        case SET_VOLUME: {
            VolumeData *data = (VolumeData *)command->mParam;
            VolumeData *data2 = (VolumeData *)command2->mParam;
            if (data->mIO != data2->mIO) break;
            if (data->mStream != data2->mStream) break;
            LOGV("Filtering out volume command on output %d for stream %d",
                    data->mIO, data->mStream);
            removedCommands.add(command2);
        } break;
        case START_TONE:
        case STOP_TONE:
        default:
            break;
        }
    }

    // remove filtered commands
    for (size_t j = 0; j < removedCommands.size(); j++) {
        // removed commands always have time stamps greater than current command
        for (size_t k = i + 1; k < mAudioCommands.size(); k++) {
            if (mAudioCommands[k] == removedCommands[j]) {
                LOGV("suppressing command: %d", mAudioCommands[k]->mCommand);
                mAudioCommands.removeAt(k);
                break;
            }
        }
    }
    removedCommands.clear();

    // insert command at the right place according to its time stamp
    LOGV("inserting command: %d at index %d, num commands %d",
            command->mCommand, (int)i+1, mAudioCommands.size());
    mAudioCommands.insertAt(command, i + 1);
}

void AudioPolicyService::AudioCommandThread::exit()
{
    LOGV("AudioCommandThread::exit");
    {
        AutoMutex _l(mLock);
        requestExit();
        mWaitWorkCV.signal();
    }
    requestExitAndWait();
}

void AudioPolicyService::AudioCommandThread::AudioCommand::dump(char* buffer, size_t size)
{
    snprintf(buffer, size, "   %02d      %06d.%03d  %01u    %p\n",
            mCommand,
            (int)ns2s(mTime),
            (int)ns2ms(mTime)%1000,
            mWaitStatus,
            mParam);
}

/******* helpers for the service_ops callbacks defined below *********/
void AudioPolicyService::setParameters(audio_io_handle_t ioHandle,
                                       const char *keyValuePairs,
                                       int delayMs)
{
    mAudioCommandThread->parametersCommand((int)ioHandle, keyValuePairs,
                                           delayMs);
}

int AudioPolicyService::setStreamVolume(audio_stream_type_t stream,
                                        float volume,
                                        audio_io_handle_t output,
                                        int delayMs)
{
    return (int)mAudioCommandThread->volumeCommand((int)stream, volume,
                                                   (int)output, delayMs);
}

int AudioPolicyService::startTone(audio_policy_tone_t tone,
                                  audio_stream_type_t stream)
{
    if (tone != AUDIO_POLICY_TONE_IN_CALL_NOTIFICATION)
        LOGE("startTone: illegal tone requested (%d)", tone);
    if (stream != AUDIO_STREAM_VOICE_CALL)
        LOGE("startTone: illegal stream (%d) requested for tone %d", stream,
             tone);
    mTonePlaybackThread->startToneCommand(ToneGenerator::TONE_SUP_CALL_WAITING,
                                          AUDIO_STREAM_VOICE_CALL);
    return 0;
}

int AudioPolicyService::stopTone()
{
    mTonePlaybackThread->stopToneCommand();
    return 0;
}

int AudioPolicyService::setVoiceVolume(float volume, int delayMs)
{
    return (int)mAudioCommandThread->voiceVolumeCommand(volume, delayMs);
}

/* implementation of the interface to the policy manager */
extern "C" {

static audio_io_handle_t aps_open_output(void *service,
                                             uint32_t *pDevices,
                                             uint32_t *pSamplingRate,
                                             uint32_t *pFormat,
                                             uint32_t *pChannels,
                                             uint32_t *pLatencyMs,
                                             audio_policy_output_flags_t flags)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL) {
        LOGW("%s: could not get AudioFlinger", __func__);
        return 0;
    }

    return af->openOutput(pDevices, pSamplingRate, pFormat, pChannels,
                          pLatencyMs, flags);
}

static audio_io_handle_t aps_open_dup_output(void *service,
                                                 audio_io_handle_t output1,
                                                 audio_io_handle_t output2)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL) {
        LOGW("%s: could not get AudioFlinger", __func__);
        return 0;
    }
    return af->openDuplicateOutput(output1, output2);
}

static int aps_close_output(void *service, audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL)
        return PERMISSION_DENIED;

    return af->closeOutput(output);
}

static int aps_suspend_output(void *service, audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL) {
        LOGW("%s: could not get AudioFlinger", __func__);
        return PERMISSION_DENIED;
    }

    return af->suspendOutput(output);
}

static int aps_restore_output(void *service, audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL) {
        LOGW("%s: could not get AudioFlinger", __func__);
        return PERMISSION_DENIED;
    }

    return af->restoreOutput(output);
}

static audio_io_handle_t aps_open_input(void *service,
                                            uint32_t *pDevices,
                                            uint32_t *pSamplingRate,
                                            uint32_t *pFormat,
                                            uint32_t *pChannels,
                                            uint32_t acoustics)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL) {
        LOGW("%s: could not get AudioFlinger", __func__);
        return 0;
    }

    return af->openInput(pDevices, pSamplingRate, pFormat, pChannels,
                         acoustics);
}

static int aps_close_input(void *service, audio_io_handle_t input)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL)
        return PERMISSION_DENIED;

    return af->closeInput(input);
}

static int aps_set_stream_output(void *service, audio_stream_type_t stream,
                                     audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL)
        return PERMISSION_DENIED;

    return af->setStreamOutput(stream, output);
}

static int aps_move_effects(void *service, int session,
                                audio_io_handle_t src_output,
                                audio_io_handle_t dst_output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL)
        return PERMISSION_DENIED;

    return af->moveEffects(session, (int)src_output, (int)dst_output);
}

static char * aps_get_parameters(void *service, audio_io_handle_t io_handle,
                                     const char *keys)
{
    String8 result = AudioSystem::getParameters(io_handle, String8(keys));
    return strdup(result.string());
}

static void aps_set_parameters(void *service, audio_io_handle_t io_handle,
                                   const char *kv_pairs, int delay_ms)
{
    AudioPolicyService *audioPolicyService = (AudioPolicyService *)service;

    audioPolicyService->setParameters(io_handle, kv_pairs, delay_ms);
}

static int aps_set_stream_volume(void *service, audio_stream_type_t stream,
                                     float volume, audio_io_handle_t output,
                                     int delay_ms)
{
    AudioPolicyService *audioPolicyService = (AudioPolicyService *)service;

    return audioPolicyService->setStreamVolume(stream, volume, output,
                                               delay_ms);
}

static int aps_start_tone(void *service, audio_policy_tone_t tone,
                              audio_stream_type_t stream)
{
    AudioPolicyService *audioPolicyService = (AudioPolicyService *)service;

    return audioPolicyService->startTone(tone, stream);
}

static int aps_stop_tone(void *service)
{
    AudioPolicyService *audioPolicyService = (AudioPolicyService *)service;

    return audioPolicyService->stopTone();
}

static int aps_set_voice_volume(void *service, float volume, int delay_ms)
{
    AudioPolicyService *audioPolicyService = (AudioPolicyService *)service;

    return audioPolicyService->setVoiceVolume(volume, delay_ms);
}

}; // extern "C"

namespace {
    struct audio_policy_service_ops aps_ops = {
        open_output           : aps_open_output,
        open_duplicate_output : aps_open_dup_output,
        close_output          : aps_close_output,
        suspend_output        : aps_suspend_output,
        restore_output        : aps_restore_output,
        open_input            : aps_open_input,
        close_input           : aps_close_input,
        set_stream_volume     : aps_set_stream_volume,
        set_stream_output     : aps_set_stream_output,
        set_parameters        : aps_set_parameters,
        get_parameters        : aps_get_parameters,
        start_tone            : aps_start_tone,
        stop_tone             : aps_stop_tone,
        set_voice_volume      : aps_set_voice_volume,
        move_effects          : aps_move_effects,
    };
}; // namespace <unnamed>

}; // namespace android
