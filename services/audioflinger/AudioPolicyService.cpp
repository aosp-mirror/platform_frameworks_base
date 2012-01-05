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
#include <hardware_legacy/power.h>
#include <media/AudioEffect.h>
#include <media/EffectsFactoryApi.h>

#include <hardware/hardware.h>
#include <system/audio.h>
#include <system/audio_policy.h>
#include <hardware/audio_policy.h>
#include <audio_effects/audio_effects_conf.h>

namespace android {

static const char *kDeadlockedString = "AudioPolicyService may be deadlocked\n";
static const char *kCmdDeadlockedString = "AudioPolicyService command thread may be deadlocked\n";

static const int kDumpLockRetries = 50;
static const int kDumpLockSleep = 20000;

static bool checkPermission() {
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

    ALOGI("Loaded audio policy from %s (%s)", module->name, module->id);

    // load audio pre processing modules
    if (access(AUDIO_EFFECT_VENDOR_CONFIG_FILE, R_OK) == 0) {
        loadPreProcessorConfig(AUDIO_EFFECT_VENDOR_CONFIG_FILE);
    } else if (access(AUDIO_EFFECT_DEFAULT_CONFIG_FILE, R_OK) == 0) {
        loadPreProcessorConfig(AUDIO_EFFECT_DEFAULT_CONFIG_FILE);
    }
}

AudioPolicyService::~AudioPolicyService()
{
    mTonePlaybackThread->exit();
    mTonePlaybackThread.clear();
    mAudioCommandThread->exit();
    mAudioCommandThread.clear();


    // release audio pre processing resources
    for (size_t i = 0; i < mInputSources.size(); i++) {
        InputSourceDesc *source = mInputSources.valueAt(i);
        Vector <EffectDesc *> effects = source->mEffects;
        for (size_t j = 0; j < effects.size(); j++) {
            delete effects[j]->mName;
            Vector <effect_param_t *> params = effects[j]->mParams;
            for (size_t k = 0; k < params.size(); k++) {
                delete params[k];
            }
            params.clear();
            delete effects[j];
        }
        effects.clear();
        delete source;
    }
    mInputSources.clear();

    for (size_t i = 0; i < mInputs.size(); i++) {
        mInputs.valueAt(i)->mEffects.clear();
        delete mInputs.valueAt(i);
    }
    mInputs.clear();

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

    ALOGV("setDeviceConnectionState() tid %d", gettid());
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

    ALOGV("setPhoneState() tid %d", gettid());

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
    ALOGV("setForceUse() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    mpAudioPolicy->set_force_use(mpAudioPolicy, usage, config);
    return NO_ERROR;
}

audio_policy_forced_cfg_t AudioPolicyService::getForceUse(audio_policy_force_use_t usage)
{
    if (mpAudioPolicy == NULL) {
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
    ALOGV("getOutput() tid %d", gettid());
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
    ALOGV("startOutput() tid %d", gettid());
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
    ALOGV("stopOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->stop_output(mpAudioPolicy, output, stream, session);
}

void AudioPolicyService::releaseOutput(audio_io_handle_t output)
{
    if (mpAudioPolicy == NULL) {
        return;
    }
    ALOGV("releaseOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    mpAudioPolicy->release_output(mpAudioPolicy, output);
}

audio_io_handle_t AudioPolicyService::getInput(int inputSource,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    audio_in_acoustics_t acoustics,
                                    int audioSession)
{
    if (mpAudioPolicy == NULL) {
        return 0;
    }
    Mutex::Autolock _l(mLock);
    audio_io_handle_t input = mpAudioPolicy->get_input(mpAudioPolicy, inputSource, samplingRate,
                                                       format, channels, acoustics);

    if (input == 0) {
        return input;
    }
    // create audio pre processors according to input source
    ssize_t index = mInputSources.indexOfKey((audio_source_t)inputSource);
    if (index < 0) {
        return input;
    }
    ssize_t idx = mInputs.indexOfKey(input);
    InputDesc *inputDesc;
    if (idx < 0) {
        inputDesc = new InputDesc();
        inputDesc->mSessionId = audioSession;
        mInputs.add(input, inputDesc);
    } else {
        inputDesc = mInputs.valueAt(idx);
    }

    Vector <EffectDesc *> effects = mInputSources.valueAt(index)->mEffects;
    for (size_t i = 0; i < effects.size(); i++) {
        EffectDesc *effect = effects[i];
        sp<AudioEffect> fx = new AudioEffect(NULL, &effect->mUuid, -1, 0, 0, audioSession, input);
        status_t status = fx->initCheck();
        if (status != NO_ERROR && status != ALREADY_EXISTS) {
            ALOGW("Failed to create Fx %s on input %d", effect->mName, input);
            // fx goes out of scope and strong ref on AudioEffect is released
            continue;
        }
        for (size_t j = 0; j < effect->mParams.size(); j++) {
            fx->setParameter(effect->mParams[j]);
        }
        inputDesc->mEffects.add(fx);
    }
    setPreProcessorEnabled(inputDesc, true);
    return input;
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

    ssize_t index = mInputs.indexOfKey(input);
    if (index < 0) {
        return;
    }
    InputDesc *inputDesc = mInputs.valueAt(index);
    setPreProcessorEnabled(inputDesc, false);
    inputDesc->mEffects.clear();
    delete inputDesc;
    mInputs.removeItemsAt(index);
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
                                audio_io_handle_t io,
                                uint32_t strategy,
                                int session,
                                int id)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    return mpAudioPolicy->register_effect(mpAudioPolicy, desc, io, strategy, session, id);
}

status_t AudioPolicyService::unregisterEffect(int id)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    return mpAudioPolicy->unregister_effect(mpAudioPolicy, id);
}

status_t AudioPolicyService::setEffectEnabled(int id, bool enabled)
{
    if (mpAudioPolicy == NULL) {
        return NO_INIT;
    }
    return mpAudioPolicy->set_effect_enabled(mpAudioPolicy, id, enabled);
}

bool AudioPolicyService::isStreamActive(int stream, uint32_t inPastMs) const
{
    if (mpAudioPolicy == NULL) {
        return 0;
    }
    Mutex::Autolock _l(mLock);
    return mpAudioPolicy->is_stream_active(mpAudioPolicy, stream, inPastMs);
}

status_t AudioPolicyService::queryDefaultPreProcessing(int audioSession,
                                                       effect_descriptor_t *descriptors,
                                                       uint32_t *count)
{

    if (mpAudioPolicy == NULL) {
        *count = 0;
        return NO_INIT;
    }
    Mutex::Autolock _l(mLock);
    status_t status = NO_ERROR;

    size_t index;
    for (index = 0; index < mInputs.size(); index++) {
        if (mInputs.valueAt(index)->mSessionId == audioSession) {
            break;
        }
    }
    if (index == mInputs.size()) {
        *count = 0;
        return BAD_VALUE;
    }
    Vector< sp<AudioEffect> > effects = mInputs.valueAt(index)->mEffects;

    for (size_t i = 0; i < effects.size(); i++) {
        effect_descriptor_t desc = effects[i]->descriptor();
        if (i < *count) {
            memcpy(descriptors + i, &desc, sizeof(effect_descriptor_t));
        }
    }
    if (effects.size() > *count) {
        status = NO_MEMORY;
    }
    *count = effects.size();
    return status;
}

void AudioPolicyService::binderDied(const wp<IBinder>& who) {
    ALOGW("binderDied() %p, tid %d, calling tid %d", who.unsafe_get(), gettid(),
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

void AudioPolicyService::setPreProcessorEnabled(InputDesc *inputDesc, bool enabled)
{
    Vector<sp<AudioEffect> > fxVector = inputDesc->mEffects;
    for (size_t i = 0; i < fxVector.size(); i++) {
        sp<AudioEffect> fx = fxVector.itemAt(i);
        fx->setEnabled(enabled);
    }
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
                    ALOGV("AudioCommandThread() processing start tone %d on stream %d",
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
                    ALOGV("AudioCommandThread() processing stop tone");
                    if (mpToneGenerator != NULL) {
                        mpToneGenerator->stopTone();
                        delete mpToneGenerator;
                        mpToneGenerator = NULL;
                    }
                    mLock.lock();
                    }break;
                case SET_VOLUME: {
                    VolumeData *data = (VolumeData *)command->mParam;
                    ALOGV("AudioCommandThread() processing set volume stream %d, \
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
                     ALOGV("AudioCommandThread() processing set parameters string %s, io %d",
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
                    ALOGV("AudioCommandThread() processing set voice volume volume %f",
                            data->mVolume);
                    command->mStatus = AudioSystem::setVoiceVolume(data->mVolume);
                    if (command->mWaitStatus) {
                        command->mCond.signal();
                        mWaitWorkCV.wait(mLock);
                    }
                    delete data;
                    }break;
                default:
                    ALOGW("AudioCommandThread() unknown command %d", command->mCommand);
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
        ALOGV("AudioCommandThread() going to sleep");
        mWaitWorkCV.waitRelative(mLock, waitTime);
        ALOGV("AudioCommandThread() waking up");
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
    ALOGV("AudioCommandThread() adding tone start type %d, stream %d", type, stream);
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
    ALOGV("AudioCommandThread() adding tone stop");
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
    ALOGV("AudioCommandThread() adding set volume stream %d, volume %f, output %d",
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
    ALOGV("AudioCommandThread() adding set parameter string %s, io %d ,delay %d",
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
    ALOGV("AudioCommandThread() adding set voice volume volume %f", volume);
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
            ALOGV("Comparing parameter command %s to new command %s",
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
                      ALOGV("Filtering out parameter %s", key2.string());
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
            ALOGV("Filtering out volume command on output %d for stream %d",
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
                ALOGV("suppressing command: %d", mAudioCommands[k]->mCommand);
                mAudioCommands.removeAt(k);
                break;
            }
        }
    }
    removedCommands.clear();

    // insert command at the right place according to its time stamp
    ALOGV("inserting command: %d at index %d, num commands %d",
            command->mCommand, (int)i+1, mAudioCommands.size());
    mAudioCommands.insertAt(command, i + 1);
}

void AudioPolicyService::AudioCommandThread::exit()
{
    ALOGV("AudioCommandThread::exit");
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

// ----------------------------------------------------------------------------
// Audio pre-processing configuration
// ----------------------------------------------------------------------------

const char *AudioPolicyService::kInputSourceNames[AUDIO_SOURCE_CNT -1] = {
    MIC_SRC_TAG,
    VOICE_UL_SRC_TAG,
    VOICE_DL_SRC_TAG,
    VOICE_CALL_SRC_TAG,
    CAMCORDER_SRC_TAG,
    VOICE_REC_SRC_TAG,
    VOICE_COMM_SRC_TAG
};

// returns the audio_source_t enum corresponding to the input source name or
// AUDIO_SOURCE_CNT is no match found
audio_source_t AudioPolicyService::inputSourceNameToEnum(const char *name)
{
    int i;
    for (i = AUDIO_SOURCE_MIC; i < AUDIO_SOURCE_CNT; i++) {
        if (strcmp(name, kInputSourceNames[i - AUDIO_SOURCE_MIC]) == 0) {
            ALOGV("inputSourceNameToEnum found source %s %d", name, i);
            break;
        }
    }
    return (audio_source_t)i;
}

size_t AudioPolicyService::growParamSize(char *param,
                                         size_t size,
                                         size_t *curSize,
                                         size_t *totSize)
{
    // *curSize is at least sizeof(effect_param_t) + 2 * sizeof(int)
    size_t pos = ((*curSize - 1 ) / size + 1) * size;

    if (pos + size > *totSize) {
        while (pos + size > *totSize) {
            *totSize += ((*totSize + 7) / 8) * 4;
        }
        param = (char *)realloc(param, *totSize);
    }
    *curSize = pos + size;
    return pos;
}

size_t AudioPolicyService::readParamValue(cnode *node,
                                          char *param,
                                          size_t *curSize,
                                          size_t *totSize)
{
    if (strncmp(node->name, SHORT_TAG, sizeof(SHORT_TAG) + 1) == 0) {
        size_t pos = growParamSize(param, sizeof(short), curSize, totSize);
        *(short *)((char *)param + pos) = (short)atoi(node->value);
        ALOGV("readParamValue() reading short %d", *(short *)((char *)param + pos));
        return sizeof(short);
    } else if (strncmp(node->name, INT_TAG, sizeof(INT_TAG) + 1) == 0) {
        size_t pos = growParamSize(param, sizeof(int), curSize, totSize);
        *(int *)((char *)param + pos) = atoi(node->value);
        ALOGV("readParamValue() reading int %d", *(int *)((char *)param + pos));
        return sizeof(int);
    } else if (strncmp(node->name, FLOAT_TAG, sizeof(FLOAT_TAG) + 1) == 0) {
        size_t pos = growParamSize(param, sizeof(float), curSize, totSize);
        *(float *)((char *)param + pos) = (float)atof(node->value);
        ALOGV("readParamValue() reading float %f",*(float *)((char *)param + pos));
        return sizeof(float);
    } else if (strncmp(node->name, BOOL_TAG, sizeof(BOOL_TAG) + 1) == 0) {
        size_t pos = growParamSize(param, sizeof(bool), curSize, totSize);
        if (strncmp(node->value, "false", strlen("false") + 1) == 0) {
            *(bool *)((char *)param + pos) = false;
        } else {
            *(bool *)((char *)param + pos) = true;
        }
        ALOGV("readParamValue() reading bool %s",*(bool *)((char *)param + pos) ? "true" : "false");
        return sizeof(bool);
    } else if (strncmp(node->name, STRING_TAG, sizeof(STRING_TAG) + 1) == 0) {
        size_t len = strnlen(node->value, EFFECT_STRING_LEN_MAX);
        if (*curSize + len + 1 > *totSize) {
            *totSize = *curSize + len + 1;
            param = (char *)realloc(param, *totSize);
        }
        strncpy(param + *curSize, node->value, len);
        *curSize += len;
        param[*curSize] = '\0';
        ALOGV("readParamValue() reading string %s", param + *curSize - len);
        return len;
    }
    ALOGW("readParamValue() unknown param type %s", node->name);
    return 0;
}

effect_param_t *AudioPolicyService::loadEffectParameter(cnode *root)
{
    cnode *param;
    cnode *value;
    size_t curSize = sizeof(effect_param_t);
    size_t totSize = sizeof(effect_param_t) + 2 * sizeof(int);
    effect_param_t *fx_param = (effect_param_t *)malloc(totSize);

    param = config_find(root, PARAM_TAG);
    value = config_find(root, VALUE_TAG);
    if (param == NULL && value == NULL) {
        // try to parse simple parameter form {int int}
        param = root->first_child;
        if (param) {
            // Note: that a pair of random strings is read as 0 0
            int *ptr = (int *)fx_param->data;
            int *ptr2 = (int *)((char *)param + sizeof(effect_param_t));
            ALOGW("loadEffectParameter() ptr %p ptr2 %p", ptr, ptr2);
            *ptr++ = atoi(param->name);
            *ptr = atoi(param->value);
            fx_param->psize = sizeof(int);
            fx_param->vsize = sizeof(int);
            return fx_param;
        }
    }
    if (param == NULL || value == NULL) {
        ALOGW("loadEffectParameter() invalid parameter description %s", root->name);
        goto error;
    }

    fx_param->psize = 0;
    param = param->first_child;
    while (param) {
        ALOGV("loadEffectParameter() reading param of type %s", param->name);
        size_t size = readParamValue(param, (char *)fx_param, &curSize, &totSize);
        if (size == 0) {
            goto error;
        }
        fx_param->psize += size;
        param = param->next;
    }

    // align start of value field on 32 bit boundary
    curSize = ((curSize - 1 ) / sizeof(int) + 1) * sizeof(int);

    fx_param->vsize = 0;
    value = value->first_child;
    while (value) {
        ALOGV("loadEffectParameter() reading value of type %s", value->name);
        size_t size = readParamValue(value, (char *)fx_param, &curSize, &totSize);
        if (size == 0) {
            goto error;
        }
        fx_param->vsize += size;
        value = value->next;
    }

    return fx_param;

error:
    delete fx_param;
    return NULL;
}

void AudioPolicyService::loadEffectParameters(cnode *root, Vector <effect_param_t *>& params)
{
    cnode *node = root->first_child;
    while (node) {
        ALOGV("loadEffectParameters() loading param %s", node->name);
        effect_param_t *param = loadEffectParameter(node);
        if (param == NULL) {
            node = node->next;
            continue;
        }
        params.add(param);
        node = node->next;
    }
}

AudioPolicyService::InputSourceDesc *AudioPolicyService::loadInputSource(
                                                            cnode *root,
                                                            const Vector <EffectDesc *>& effects)
{
    cnode *node = root->first_child;
    if (node == NULL) {
        ALOGW("loadInputSource() empty element %s", root->name);
        return NULL;
    }
    InputSourceDesc *source = new InputSourceDesc();
    while (node) {
        size_t i;
        for (i = 0; i < effects.size(); i++) {
            if (strncmp(effects[i]->mName, node->name, EFFECT_STRING_LEN_MAX) == 0) {
                ALOGV("loadInputSource() found effect %s in list", node->name);
                break;
            }
        }
        if (i == effects.size()) {
            ALOGV("loadInputSource() effect %s not in list", node->name);
            node = node->next;
            continue;
        }
        EffectDesc *effect = new EffectDesc(*effects[i]);
        loadEffectParameters(node, effect->mParams);
        ALOGV("loadInputSource() adding effect %s uuid %08x", effect->mName, effect->mUuid.timeLow);
        source->mEffects.add(effect);
        node = node->next;
    }
    if (source->mEffects.size() == 0) {
        ALOGW("loadInputSource() no valid effects found in source %s", root->name);
        delete source;
        return NULL;
    }
    return source;
}

status_t AudioPolicyService::loadInputSources(cnode *root, const Vector <EffectDesc *>& effects)
{
    cnode *node = config_find(root, PREPROCESSING_TAG);
    if (node == NULL) {
        return -ENOENT;
    }
    node = node->first_child;
    while (node) {
        audio_source_t source = inputSourceNameToEnum(node->name);
        if (source == AUDIO_SOURCE_CNT) {
            ALOGW("loadInputSources() invalid input source %s", node->name);
            node = node->next;
            continue;
        }
        ALOGV("loadInputSources() loading input source %s", node->name);
        InputSourceDesc *desc = loadInputSource(node, effects);
        if (desc == NULL) {
            node = node->next;
            continue;
        }
        mInputSources.add(source, desc);
        node = node->next;
    }
    return NO_ERROR;
}

AudioPolicyService::EffectDesc *AudioPolicyService::loadEffect(cnode *root)
{
    cnode *node = config_find(root, UUID_TAG);
    if (node == NULL) {
        return NULL;
    }
    effect_uuid_t uuid;
    if (AudioEffect::stringToGuid(node->value, &uuid) != NO_ERROR) {
        ALOGW("loadEffect() invalid uuid %s", node->value);
        return NULL;
    }
    EffectDesc *effect = new EffectDesc();
    effect->mName = strdup(root->name);
    memcpy(&effect->mUuid, &uuid, sizeof(effect_uuid_t));

    return effect;
}

status_t AudioPolicyService::loadEffects(cnode *root, Vector <EffectDesc *>& effects)
{
    cnode *node = config_find(root, EFFECTS_TAG);
    if (node == NULL) {
        return -ENOENT;
    }
    node = node->first_child;
    while (node) {
        ALOGV("loadEffects() loading effect %s", node->name);
        EffectDesc *effect = loadEffect(node);
        if (effect == NULL) {
            node = node->next;
            continue;
        }
        effects.add(effect);
        node = node->next;
    }
    return NO_ERROR;
}

status_t AudioPolicyService::loadPreProcessorConfig(const char *path)
{
    cnode *root;
    char *data;

    data = (char *)load_file(path, NULL);
    if (data == NULL) {
        return -ENODEV;
    }
    root = config_node("", "");
    config_load(root, data);

    Vector <EffectDesc *> effects;
    loadEffects(root, effects);
    loadInputSources(root, effects);

    config_free(root);
    free(root);
    free(data);

    return NO_ERROR;
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
        ALOGW("%s: could not get AudioFlinger", __func__);
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
        ALOGW("%s: could not get AudioFlinger", __func__);
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
        ALOGW("%s: could not get AudioFlinger", __func__);
        return PERMISSION_DENIED;
    }

    return af->suspendOutput(output);
}

static int aps_restore_output(void *service, audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == NULL) {
        ALOGW("%s: could not get AudioFlinger", __func__);
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
        ALOGW("%s: could not get AudioFlinger", __func__);
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
