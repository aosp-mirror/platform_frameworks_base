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
#include <hardware_legacy/AudioPolicyManagerBase.h>
#include <cutils/properties.h>
#include <dlfcn.h>
#include <hardware_legacy/power.h>

// ----------------------------------------------------------------------------
// the sim build doesn't have gettid

#ifndef HAVE_GETTID
# define gettid getpid
#endif

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

// ----------------------------------------------------------------------------

AudioPolicyService::AudioPolicyService()
    : BnAudioPolicyService() , mpPolicyManager(NULL)
{
    char value[PROPERTY_VALUE_MAX];

    // start tone playback thread
    mTonePlaybackThread = new AudioCommandThread(String8(""));
    // start audio commands thread
    mAudioCommandThread = new AudioCommandThread(String8("ApmCommandThread"));

#if (defined GENERIC_AUDIO) || (defined AUDIO_POLICY_TEST)
    mpPolicyManager = new AudioPolicyManagerBase(this);
    LOGV("build for GENERIC_AUDIO - using generic audio policy");
#else
    // if running in emulation - use the emulator driver
    if (property_get("ro.kernel.qemu", value, 0)) {
        LOGV("Running in emulation - using generic audio policy");
        mpPolicyManager = new AudioPolicyManagerBase(this);
    }
    else {
        LOGV("Using hardware specific audio policy");
        mpPolicyManager = createAudioPolicyManager(this);
    }
#endif

    // load properties
    property_get("ro.camera.sound.forced", value, "0");
    mpPolicyManager->setSystemProperty("ro.camera.sound.forced", value);
}

AudioPolicyService::~AudioPolicyService()
{
    mTonePlaybackThread->exit();
    mTonePlaybackThread.clear();
    mAudioCommandThread->exit();
    mAudioCommandThread.clear();

    if (mpPolicyManager) {
        delete mpPolicyManager;
    }
}


status_t AudioPolicyService::setDeviceConnectionState(AudioSystem::audio_devices device,
                                                  AudioSystem::device_connection_state state,
                                                  const char *device_address)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (!AudioSystem::isOutputDevice(device) && !AudioSystem::isInputDevice(device)) {
        return BAD_VALUE;
    }
    if (state != AudioSystem::DEVICE_STATE_AVAILABLE && state != AudioSystem::DEVICE_STATE_UNAVAILABLE) {
        return BAD_VALUE;
    }

    LOGV("setDeviceConnectionState() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpPolicyManager->setDeviceConnectionState(device, state, device_address);
}

AudioSystem::device_connection_state AudioPolicyService::getDeviceConnectionState(AudioSystem::audio_devices device,
                                                  const char *device_address)
{
    if (mpPolicyManager == NULL) {
        return AudioSystem::DEVICE_STATE_UNAVAILABLE;
    }
    if (!checkPermission()) {
        return AudioSystem::DEVICE_STATE_UNAVAILABLE;
    }
    return mpPolicyManager->getDeviceConnectionState(device, device_address);
}

status_t AudioPolicyService::setPhoneState(int state)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (state < 0 || state >= AudioSystem::NUM_MODES) {
        return BAD_VALUE;
    }

    LOGV("setPhoneState() tid %d", gettid());

    // TODO: check if it is more appropriate to do it in platform specific policy manager
    AudioSystem::setMode(state);

    Mutex::Autolock _l(mLock);
    mpPolicyManager->setPhoneState(state);
    return NO_ERROR;
}

status_t AudioPolicyService::setRingerMode(uint32_t mode, uint32_t mask)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }

    mpPolicyManager->setRingerMode(mode, mask);
    return NO_ERROR;
}

status_t AudioPolicyService::setForceUse(AudioSystem::force_use usage, AudioSystem::forced_config config)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (usage < 0 || usage >= AudioSystem::NUM_FORCE_USE) {
        return BAD_VALUE;
    }
    if (config < 0 || config >= AudioSystem::NUM_FORCE_CONFIG) {
        return BAD_VALUE;
    }
    LOGV("setForceUse() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    mpPolicyManager->setForceUse(usage, config);
    return NO_ERROR;
}

AudioSystem::forced_config AudioPolicyService::getForceUse(AudioSystem::force_use usage)
{
    if (mpPolicyManager == NULL) {
        return AudioSystem::FORCE_NONE;
    }
    if (!checkPermission()) {
        return AudioSystem::FORCE_NONE;
    }
    if (usage < 0 || usage >= AudioSystem::NUM_FORCE_USE) {
        return AudioSystem::FORCE_NONE;
    }
    return mpPolicyManager->getForceUse(usage);
}

audio_io_handle_t AudioPolicyService::getOutput(AudioSystem::stream_type stream,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    AudioSystem::output_flags flags)
{
    if (mpPolicyManager == NULL) {
        return 0;
    }
    LOGV("getOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpPolicyManager->getOutput(stream, samplingRate, format, channels, flags);
}

status_t AudioPolicyService::startOutput(audio_io_handle_t output, AudioSystem::stream_type stream)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    LOGV("startOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpPolicyManager->startOutput(output, stream);
}

status_t AudioPolicyService::stopOutput(audio_io_handle_t output, AudioSystem::stream_type stream)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    LOGV("stopOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    return mpPolicyManager->stopOutput(output, stream);
}

void AudioPolicyService::releaseOutput(audio_io_handle_t output)
{
    if (mpPolicyManager == NULL) {
        return;
    }
    LOGV("releaseOutput() tid %d", gettid());
    Mutex::Autolock _l(mLock);
    mpPolicyManager->releaseOutput(output);
}

audio_io_handle_t AudioPolicyService::getInput(int inputSource,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    AudioSystem::audio_in_acoustics acoustics)
{
    if (mpPolicyManager == NULL) {
        return 0;
    }
    Mutex::Autolock _l(mLock);
    return mpPolicyManager->getInput(inputSource, samplingRate, format, channels, acoustics);
}

status_t AudioPolicyService::startInput(audio_io_handle_t input)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    Mutex::Autolock _l(mLock);
    return mpPolicyManager->startInput(input);
}

status_t AudioPolicyService::stopInput(audio_io_handle_t input)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    Mutex::Autolock _l(mLock);
    return mpPolicyManager->stopInput(input);
}

void AudioPolicyService::releaseInput(audio_io_handle_t input)
{
    if (mpPolicyManager == NULL) {
        return;
    }
    Mutex::Autolock _l(mLock);
    mpPolicyManager->releaseInput(input);
}

status_t AudioPolicyService::initStreamVolume(AudioSystem::stream_type stream,
                                            int indexMin,
                                            int indexMax)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (stream < 0 || stream >= AudioSystem::NUM_STREAM_TYPES) {
        return BAD_VALUE;
    }
    mpPolicyManager->initStreamVolume(stream, indexMin, indexMax);
    return NO_ERROR;
}

status_t AudioPolicyService::setStreamVolumeIndex(AudioSystem::stream_type stream, int index)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (stream < 0 || stream >= AudioSystem::NUM_STREAM_TYPES) {
        return BAD_VALUE;
    }

    return mpPolicyManager->setStreamVolumeIndex(stream, index);
}

status_t AudioPolicyService::getStreamVolumeIndex(AudioSystem::stream_type stream, int *index)
{
    if (mpPolicyManager == NULL) {
        return NO_INIT;
    }
    if (!checkPermission()) {
        return PERMISSION_DENIED;
    }
    if (stream < 0 || stream >= AudioSystem::NUM_STREAM_TYPES) {
        return BAD_VALUE;
    }
    return mpPolicyManager->getStreamVolumeIndex(stream, index);
}

void AudioPolicyService::binderDied(const wp<IBinder>& who) {
    LOGW("binderDied() %p, tid %d, calling tid %d", who.unsafe_get(), gettid(), IPCThreadState::self()->getCallingPid());
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

    snprintf(buffer, SIZE, "PolicyManager Interface: %p\n", mpPolicyManager);
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

        if (mpPolicyManager) {
            mpPolicyManager->dump(fd);
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


// ----------------------------------------------------------------------------
void AudioPolicyService::instantiate() {
    defaultServiceManager()->addService(
            String16("media.audio_policy"), new AudioPolicyService());
}


// ----------------------------------------------------------------------------
// AudioPolicyClientInterface implementation
// ----------------------------------------------------------------------------


audio_io_handle_t AudioPolicyService::openOutput(uint32_t *pDevices,
                                uint32_t *pSamplingRate,
                                uint32_t *pFormat,
                                uint32_t *pChannels,
                                uint32_t *pLatencyMs,
                                AudioSystem::output_flags flags)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == 0) {
        LOGW("openOutput() could not get AudioFlinger");
        return 0;
    }

    return af->openOutput(pDevices, pSamplingRate, (uint32_t *)pFormat, pChannels, pLatencyMs, flags);
}

audio_io_handle_t AudioPolicyService::openDuplicateOutput(audio_io_handle_t output1, audio_io_handle_t output2)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == 0) {
        LOGW("openDuplicateOutput() could not get AudioFlinger");
        return 0;
    }
    return af->openDuplicateOutput(output1, output2);
}

status_t AudioPolicyService::closeOutput(audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;

    return af->closeOutput(output);
}


status_t AudioPolicyService::suspendOutput(audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == 0) {
        LOGW("suspendOutput() could not get AudioFlinger");
        return PERMISSION_DENIED;
    }

    return af->suspendOutput(output);
}

status_t AudioPolicyService::restoreOutput(audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == 0) {
        LOGW("restoreOutput() could not get AudioFlinger");
        return PERMISSION_DENIED;
    }

    return af->restoreOutput(output);
}

audio_io_handle_t AudioPolicyService::openInput(uint32_t *pDevices,
                                uint32_t *pSamplingRate,
                                uint32_t *pFormat,
                                uint32_t *pChannels,
                                uint32_t acoustics)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == 0) {
        LOGW("openInput() could not get AudioFlinger");
        return 0;
    }

    return af->openInput(pDevices, pSamplingRate, (uint32_t *)pFormat, pChannels, acoustics);
}

status_t AudioPolicyService::closeInput(audio_io_handle_t input)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;

    return af->closeInput(input);
}

status_t AudioPolicyService::setStreamVolume(AudioSystem::stream_type stream, float volume, audio_io_handle_t output, int delayMs)
{
    return mAudioCommandThread->volumeCommand((int)stream, volume, (int)output, delayMs);
}

status_t AudioPolicyService::setStreamOutput(AudioSystem::stream_type stream, audio_io_handle_t output)
{
    sp<IAudioFlinger> af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;

    return af->setStreamOutput(stream, output);
}


void AudioPolicyService::setParameters(audio_io_handle_t ioHandle, const String8& keyValuePairs, int delayMs)
{
    mAudioCommandThread->parametersCommand((int)ioHandle, keyValuePairs, delayMs);
}

String8 AudioPolicyService::getParameters(audio_io_handle_t ioHandle, const String8& keys)
{
    String8 result = AudioSystem::getParameters(ioHandle, keys);
    return result;
}

status_t AudioPolicyService::startTone(ToneGenerator::tone_type tone, AudioSystem::stream_type stream)
{
    mTonePlaybackThread->startToneCommand(tone, stream);
    return NO_ERROR;
}

status_t AudioPolicyService::stopTone()
{
    mTonePlaybackThread->stopToneCommand();
    return NO_ERROR;
}

status_t AudioPolicyService::setVoiceVolume(float volume, int delayMs)
{
    return mAudioCommandThread->voiceVolumeCommand(volume, delayMs);
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
                    LOGV("AudioCommandThread() processing set volume stream %d, volume %f, output %d", data->mStream, data->mVolume, data->mIO);
                    command->mStatus = AudioSystem::setStreamVolume(data->mStream, data->mVolume, data->mIO);
                    if (command->mWaitStatus) {
                        command->mCond.signal();
                        mWaitWorkCV.wait(mLock);
                    }
                    delete data;
                    }break;
                case SET_PARAMETERS: {
                     ParametersData *data = (ParametersData *)command->mParam;
                     LOGV("AudioCommandThread() processing set parameters string %s, io %d", data->mKeyValuePairs.string(), data->mIO);
                     command->mStatus = AudioSystem::setParameters(data->mIO, data->mKeyValuePairs);
                     if (command->mWaitStatus) {
                         command->mCond.signal();
                         mWaitWorkCV.wait(mLock);
                     }
                     delete data;
                     }break;
                case SET_VOICE_VOLUME: {
                    VoiceVolumeData *data = (VoiceVolumeData *)command->mParam;
                    LOGV("AudioCommandThread() processing set voice volume volume %f", data->mVolume);
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

status_t AudioPolicyService::AudioCommandThread::volumeCommand(int stream, float volume, int output, int delayMs)
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
    LOGV("AudioCommandThread() adding set volume stream %d, volume %f, output %d", stream, volume, output);
    mWaitWorkCV.signal();
    if (command->mWaitStatus) {
        command->mCond.wait(mLock);
        status =  command->mStatus;
        mWaitWorkCV.signal();
    }
    return status;
}

status_t AudioPolicyService::AudioCommandThread::parametersCommand(int ioHandle, const String8& keyValuePairs, int delayMs)
{
    status_t status = NO_ERROR;

    AudioCommand *command = new AudioCommand();
    command->mCommand = SET_PARAMETERS;
    ParametersData *data = new ParametersData();
    data->mIO = ioHandle;
    data->mKeyValuePairs = keyValuePairs;
    command->mParam = data;
    if (delayMs == 0) {
        command->mWaitStatus = true;
    } else {
        command->mWaitStatus = false;
    }
    Mutex::Autolock _l(mLock);
    insertCommand_l(command, delayMs);
    LOGV("AudioCommandThread() adding set parameter string %s, io %d ,delay %d", keyValuePairs.string(), ioHandle, delayMs);
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
            LOGV("Comparing parameter command %s to new command %s", data2->mKeyValuePairs.string(), data->mKeyValuePairs.string());
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
            LOGV("Filtering out volume command on output %d for stream %d", data->mIO, data->mStream);
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
    LOGV("inserting command: %d at index %d, num commands %d", command->mCommand, (int)i+1, mAudioCommands.size());
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

}; // namespace android
