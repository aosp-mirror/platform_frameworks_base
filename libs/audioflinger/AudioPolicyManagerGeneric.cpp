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

#define LOG_TAG "AudioPolicyManagerGeneric"
//#define LOG_NDEBUG 0
#include <utils/Log.h>
#include "AudioPolicyManagerGeneric.h"
#include <media/mediarecorder.h>

namespace android {


// ----------------------------------------------------------------------------
// AudioPolicyInterface implementation
// ----------------------------------------------------------------------------


status_t AudioPolicyManagerGeneric::setDeviceConnectionState(AudioSystem::audio_devices device,
                                                  AudioSystem::device_connection_state state,
                                                  const char *device_address)
{

    LOGV("setDeviceConnectionState() device: %x, state %d, address %s", device, state, device_address);

    // connect/disconnect only 1 device at a time
    if (AudioSystem::popCount(device) != 1) return BAD_VALUE;

    if (strlen(device_address) >= MAX_DEVICE_ADDRESS_LEN) {
        LOGE("setDeviceConnectionState() invalid address: %s", device_address);
        return BAD_VALUE;
    }

    // handle output devices
    if (AudioSystem::isOutputDevice(device)) {
        switch (state)
        {
        // handle output device connection
        case AudioSystem::DEVICE_STATE_AVAILABLE:
            if (mAvailableOutputDevices & device) {
                LOGW("setDeviceConnectionState() device already connected: %x", device);
                return INVALID_OPERATION;
            }
            LOGV("setDeviceConnectionState() connecting device %x", device);

            // register new device as available
            mAvailableOutputDevices |= device;
            break;
        // handle output device disconnection
        case AudioSystem::DEVICE_STATE_UNAVAILABLE:
            if (!(mAvailableOutputDevices & device)) {
                LOGW("setDeviceConnectionState() device not connected: %x", device);
                return INVALID_OPERATION;
            }
            LOGV("setDeviceConnectionState() disconnecting device %x", device);
            // remove device from available output devices
            mAvailableOutputDevices &= ~device;
            break;

        default:
            LOGE("setDeviceConnectionState() invalid state: %x", state);
            return BAD_VALUE;
        }
        return NO_ERROR;
    }
    // handle input devices
    if (AudioSystem::isInputDevice(device)) {
        switch (state)
        {
        // handle input device connection
        case AudioSystem::DEVICE_STATE_AVAILABLE:
            if (mAvailableInputDevices & device) {
                LOGW("setDeviceConnectionState() device already connected: %d", device);
                return INVALID_OPERATION;
            }
            mAvailableInputDevices |= device;
            break;

        // handle input device disconnection
        case AudioSystem::DEVICE_STATE_UNAVAILABLE:
            if (!(mAvailableInputDevices & device)) {
                LOGW("setDeviceConnectionState() device not connected: %d", device);
                return INVALID_OPERATION;
            }
            mAvailableInputDevices &= ~device;
            break;

        default:
            LOGE("setDeviceConnectionState() invalid state: %x", state);
            return BAD_VALUE;
        }
        return NO_ERROR;
    }

    LOGW("setDeviceConnectionState() invalid device: %x", device);
    return BAD_VALUE;
}

AudioSystem::device_connection_state AudioPolicyManagerGeneric::getDeviceConnectionState(AudioSystem::audio_devices device,
                                                  const char *device_address)
{
    AudioSystem::device_connection_state state = AudioSystem::DEVICE_STATE_UNAVAILABLE;
    String8 address = String8(device_address);
    if (AudioSystem::isOutputDevice(device)) {
        if (device & mAvailableOutputDevices) {
            state = AudioSystem::DEVICE_STATE_AVAILABLE;
        }
    } else if (AudioSystem::isInputDevice(device)) {
        if (device & mAvailableInputDevices) {
            state = AudioSystem::DEVICE_STATE_AVAILABLE;
        }
    }

    return state;
}

void AudioPolicyManagerGeneric::setPhoneState(int state)
{
    LOGV("setPhoneState() state %d", state);
    uint32_t newDevice = 0;
    if (state < 0 || state >= AudioSystem::NUM_MODES) {
        LOGW("setPhoneState() invalid state %d", state);
        return;
    }

    if (state == mPhoneState ) {
        LOGW("setPhoneState() setting same state %d", state);
        return;
    }
    // store previous phone state for management of sonification strategy below
    int oldState = mPhoneState;
    mPhoneState = state;

    // if leaving or entering in call state, handle special case of active streams
    // pertaining to sonification strategy see handleIncallSonification()
    if (state == AudioSystem::MODE_IN_CALL ||
        oldState == AudioSystem::MODE_IN_CALL) {
        bool starting = (state == AudioSystem::MODE_IN_CALL) ? true : false;
        LOGV("setPhoneState() in call state management: new state is %d", state);
        for (int stream = 0; stream < AudioSystem::NUM_STREAM_TYPES; stream++) {
            handleIncallSonification(stream, starting);
        }
    }
}

void AudioPolicyManagerGeneric::setRingerMode(uint32_t mode, uint32_t mask)
{
    LOGV("setRingerMode() mode %x, mask %x", mode, mask);

    mRingerMode = mode;
}

void AudioPolicyManagerGeneric::setForceUse(AudioSystem::force_use usage, AudioSystem::forced_config config)
{
    LOGV("setForceUse) usage %d, config %d, mPhoneState %d", usage, config, mPhoneState);
    mForceUse[usage] = config;
}

AudioSystem::forced_config AudioPolicyManagerGeneric::getForceUse(AudioSystem::force_use usage)
{
    return mForceUse[usage];
}

void AudioPolicyManagerGeneric::setSystemProperty(const char* property, const char* value)
{
    LOGV("setSystemProperty() property %s, value %s", property, value);
    if (strcmp(property, "ro.camera.sound.forced") == 0) {
        if (atoi(value)) {
            LOGV("ENFORCED_AUDIBLE cannot be muted");
            mStreams[AudioSystem::ENFORCED_AUDIBLE].mCanBeMuted = false;
        } else {
            LOGV("ENFORCED_AUDIBLE can be muted");
            mStreams[AudioSystem::ENFORCED_AUDIBLE].mCanBeMuted = true;
        }
    }
}

audio_io_handle_t AudioPolicyManagerGeneric::getOutput(AudioSystem::stream_type stream,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    AudioSystem::output_flags flags)
{
    LOGV("getOutput() stream %d, samplingRate %d, format %d, channels %x, flags %x", stream, samplingRate, format, channels, flags);

#ifdef AUDIO_POLICY_TEST
    if (mCurOutput != 0) {
        LOGV("getOutput() test output mCurOutput %d, samplingRate %d, format %d, channels %x, mDirectOutput %d",
                mCurOutput, mTestSamplingRate, mTestFormat, mTestChannels, mDirectOutput);

        if (mTestOutputs[mCurOutput] == 0) {
            LOGV("getOutput() opening test output");
            AudioOutputDescriptor *outputDesc = new AudioOutputDescriptor();
            outputDesc->mDevice = mTestDevice;
            outputDesc->mSamplingRate = mTestSamplingRate;
            outputDesc->mFormat = mTestFormat;
            outputDesc->mChannels = mTestChannels;
            outputDesc->mLatency = mTestLatencyMs;
            outputDesc->mFlags = (AudioSystem::output_flags)(mDirectOutput ? AudioSystem::OUTPUT_FLAG_DIRECT : 0);
            outputDesc->mRefCount[stream] = 0;
            mTestOutputs[mCurOutput] = mpClientInterface->openOutput(&outputDesc->mDevice,
                                            &outputDesc->mSamplingRate,
                                            &outputDesc->mFormat,
                                            &outputDesc->mChannels,
                                            &outputDesc->mLatency,
                                            outputDesc->mFlags);
            if (mTestOutputs[mCurOutput]) {
                AudioParameter outputCmd = AudioParameter();
                outputCmd.addInt(String8("set_id"),mCurOutput);
                mpClientInterface->setParameters(mTestOutputs[mCurOutput],outputCmd.toString());
                mOutputs.add(mTestOutputs[mCurOutput], outputDesc);
            }
        }
        return mTestOutputs[mCurOutput];
    }
#endif //AUDIO_POLICY_TEST
    if ((flags & AudioSystem::OUTPUT_FLAG_DIRECT) ||
        (format != 0 && !AudioSystem::isLinearPCM(format)) ||
        (channels != 0 && channels != AudioSystem::CHANNEL_OUT_MONO && channels != AudioSystem::CHANNEL_OUT_STEREO)) {
        return 0;
    }

    return mHardwareOutput;
}

status_t AudioPolicyManagerGeneric::startOutput(audio_io_handle_t output, AudioSystem::stream_type stream)
{
    LOGV("startOutput() output %d, stream %d", output, stream);
    ssize_t index = mOutputs.indexOfKey(output);
    if (index < 0) {
        LOGW("startOutput() unknow output %d", output);
        return BAD_VALUE;
    }

    AudioOutputDescriptor *outputDesc = mOutputs.valueAt(index);

    // handle special case for sonification while in call
    if (mPhoneState == AudioSystem::MODE_IN_CALL) {
        handleIncallSonification(stream, true);
    }

    // incremenent usage count for this stream on the requested output:
    outputDesc->changeRefCount(stream, 1);
    return NO_ERROR;
}

status_t AudioPolicyManagerGeneric::stopOutput(audio_io_handle_t output, AudioSystem::stream_type stream)
{
    LOGV("stopOutput() output %d, stream %d", output, stream);
    ssize_t index = mOutputs.indexOfKey(output);
    if (index < 0) {
        LOGW("stopOutput() unknow output %d", output);
        return BAD_VALUE;
    }

    AudioOutputDescriptor *outputDesc = mOutputs.valueAt(index);

    // handle special case for sonification while in call
    if (mPhoneState == AudioSystem::MODE_IN_CALL) {
        handleIncallSonification(stream, false);
    }

    if (outputDesc->isUsedByStream(stream)) {
        // decrement usage count of this stream on the output
        outputDesc->changeRefCount(stream, -1);
        return NO_ERROR;
    } else {
        LOGW("stopOutput() refcount is already 0 for output %d", output);
        return INVALID_OPERATION;
    }
}

void AudioPolicyManagerGeneric::releaseOutput(audio_io_handle_t output)
{
    LOGV("releaseOutput() %d", output);
    ssize_t index = mOutputs.indexOfKey(output);
    if (index < 0) {
        LOGW("releaseOutput() releasing unknown output %d", output);
        return;
    }

#ifdef AUDIO_POLICY_TEST
    int testIndex = testOutputIndex(output);
    if (testIndex != 0) {
        AudioOutputDescriptor *outputDesc = mOutputs.valueAt(index);
        if (outputDesc->refCount() == 0) {
            mpClientInterface->closeOutput(output);
            delete mOutputs.valueAt(index);
            mOutputs.removeItem(output);
            mTestOutputs[testIndex] = 0;
        }
    }
#endif //AUDIO_POLICY_TEST
}

audio_io_handle_t AudioPolicyManagerGeneric::getInput(int inputSource,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    AudioSystem::audio_in_acoustics acoustics)
{
    audio_io_handle_t input = 0;
    uint32_t device;

    LOGV("getInput() inputSource %d, samplingRate %d, format %d, channels %x, acoustics %x", inputSource, samplingRate, format, channels, acoustics);

    AudioInputDescriptor *inputDesc = new AudioInputDescriptor();
    inputDesc->mDevice = AudioSystem::DEVICE_IN_BUILTIN_MIC;
    inputDesc->mSamplingRate = samplingRate;
    inputDesc->mFormat = format;
    inputDesc->mChannels = channels;
    inputDesc->mAcoustics = acoustics;
    inputDesc->mRefCount = 0;
    input = mpClientInterface->openInput(&inputDesc->mDevice,
                                    &inputDesc->mSamplingRate,
                                    &inputDesc->mFormat,
                                    &inputDesc->mChannels,
                                    inputDesc->mAcoustics);

    // only accept input with the exact requested set of parameters
    if ((samplingRate != inputDesc->mSamplingRate) ||
        (format != inputDesc->mFormat) ||
        (channels != inputDesc->mChannels)) {
        LOGV("getOutput() failed opening input: samplingRate %d, format %d, channels %d",
                samplingRate, format, channels);
        mpClientInterface->closeInput(input);
        delete inputDesc;
        return 0;
    }
    mInputs.add(input, inputDesc);
    return input;
}

status_t AudioPolicyManagerGeneric::startInput(audio_io_handle_t input)
{
    LOGV("startInput() input %d", input);
    ssize_t index = mInputs.indexOfKey(input);
    if (index < 0) {
        LOGW("startInput() unknow input %d", input);
        return BAD_VALUE;
    }
    AudioInputDescriptor *inputDesc = mInputs.valueAt(index);

#ifdef AUDIO_POLICY_TEST
    if (mTestInput == 0)
#endif //AUDIO_POLICY_TEST
    {
        // refuse 2 active AudioRecord clients at the same time
        for (size_t i = 0; i < mInputs.size(); i++) {
            if (mInputs.valueAt(i)->mRefCount > 0) {
                LOGW("startInput() input %d, other input %d already started", input, mInputs.keyAt(i));
                return INVALID_OPERATION;
            }
        }
    }

    inputDesc->mRefCount = 1;
    return NO_ERROR;
}

status_t AudioPolicyManagerGeneric::stopInput(audio_io_handle_t input)
{
    LOGV("stopInput() input %d", input);
    ssize_t index = mInputs.indexOfKey(input);
    if (index < 0) {
        LOGW("stopInput() unknow input %d", input);
        return BAD_VALUE;
    }
    AudioInputDescriptor *inputDesc = mInputs.valueAt(index);

    if (inputDesc->mRefCount == 0) {
        LOGW("stopInput() input %d already stopped", input);
        return INVALID_OPERATION;
    } else {
        inputDesc->mRefCount = 0;
        return NO_ERROR;
    }
}

void AudioPolicyManagerGeneric::releaseInput(audio_io_handle_t input)
{
    LOGV("releaseInput() %d", input);
    ssize_t index = mInputs.indexOfKey(input);
    if (index < 0) {
        LOGW("releaseInput() releasing unknown input %d", input);
        return;
    }
    mpClientInterface->closeInput(input);
    delete mInputs.valueAt(index);
    mInputs.removeItem(input);
}



void AudioPolicyManagerGeneric::initStreamVolume(AudioSystem::stream_type stream,
                                            int indexMin,
                                            int indexMax)
{
    LOGV("initStreamVolume() stream %d, min %d, max %d", stream , indexMin, indexMax);
    mStreams[stream].mIndexMin = indexMin;
    mStreams[stream].mIndexMax = indexMax;
}

status_t AudioPolicyManagerGeneric::setStreamVolumeIndex(AudioSystem::stream_type stream, int index)
{

    if ((index < mStreams[stream].mIndexMin) || (index > mStreams[stream].mIndexMax)) {
        return BAD_VALUE;
    }

    LOGV("setStreamVolumeIndex() stream %d, index %d", stream, index);
    mStreams[stream].mIndexCur = index;

    // do not change actual stream volume if the stream is muted
    if (mStreams[stream].mMuteCount != 0) {
        return NO_ERROR;
    }

    // Do not changed in call volume if bluetooth is connected and vice versa
    if ((stream == AudioSystem::VOICE_CALL && mForceUse[AudioSystem::FOR_COMMUNICATION] == AudioSystem::FORCE_BT_SCO) ||
        (stream == AudioSystem::BLUETOOTH_SCO && mForceUse[AudioSystem::FOR_COMMUNICATION] != AudioSystem::FORCE_BT_SCO)) {
        LOGV("setStreamVolumeIndex() cannot set stream %d volume with force use = %d for comm",
             stream, mForceUse[AudioSystem::FOR_COMMUNICATION]);
        return INVALID_OPERATION;
    }

    // compute and apply stream volume on all outputs according to connected device
    for (size_t i = 0; i < mOutputs.size(); i++) {
        AudioOutputDescriptor *outputDesc = mOutputs.valueAt(i);
        uint32_t device = outputDesc->device();

        float volume = computeVolume((int)stream, index, device);

        LOGV("setStreamVolume() for output %d stream %d, volume %f", mOutputs.keyAt(i), stream, volume);
        mpClientInterface->setStreamVolume(stream, volume, mOutputs.keyAt(i));
    }
    return NO_ERROR;
}

status_t AudioPolicyManagerGeneric::getStreamVolumeIndex(AudioSystem::stream_type stream, int *index)
{
    if (index == 0) {
        return BAD_VALUE;
    }
    LOGV("getStreamVolumeIndex() stream %d", stream);
    *index =  mStreams[stream].mIndexCur;
    return NO_ERROR;
}

status_t AudioPolicyManagerGeneric::dump(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    snprintf(buffer, SIZE, "\nAudioPolicyManager Dump: %p\n", this);
    result.append(buffer);
    snprintf(buffer, SIZE, " Hardware Output: %d\n", mHardwareOutput);
    result.append(buffer);
    snprintf(buffer, SIZE, " Output devices: %08x\n", mAvailableOutputDevices);
    result.append(buffer);
    snprintf(buffer, SIZE, " Input devices: %08x\n", mAvailableInputDevices);
    result.append(buffer);
    snprintf(buffer, SIZE, " Phone state: %d\n", mPhoneState);
    result.append(buffer);
    snprintf(buffer, SIZE, " Ringer mode: %d\n", mRingerMode);
    result.append(buffer);
    snprintf(buffer, SIZE, " Force use for communications %d\n", mForceUse[AudioSystem::FOR_COMMUNICATION]);
    result.append(buffer);
    snprintf(buffer, SIZE, " Force use for media %d\n", mForceUse[AudioSystem::FOR_MEDIA]);
    result.append(buffer);
    snprintf(buffer, SIZE, " Force use for record %d\n", mForceUse[AudioSystem::FOR_RECORD]);
    result.append(buffer);
    write(fd, result.string(), result.size());

    snprintf(buffer, SIZE, "\nOutputs dump:\n");
    write(fd, buffer, strlen(buffer));
    for (size_t i = 0; i < mOutputs.size(); i++) {
        snprintf(buffer, SIZE, "- Output %d dump:\n", mOutputs.keyAt(i));
        write(fd, buffer, strlen(buffer));
        mOutputs.valueAt(i)->dump(fd);
    }

    snprintf(buffer, SIZE, "\nInputs dump:\n");
    write(fd, buffer, strlen(buffer));
    for (size_t i = 0; i < mInputs.size(); i++) {
        snprintf(buffer, SIZE, "- Input %d dump:\n", mInputs.keyAt(i));
        write(fd, buffer, strlen(buffer));
        mInputs.valueAt(i)->dump(fd);
    }

    snprintf(buffer, SIZE, "\nStreams dump:\n");
    write(fd, buffer, strlen(buffer));
    snprintf(buffer, SIZE, " Stream  Index Min  Index Max  Index Cur  Mute Count  Can be muted\n");
    write(fd, buffer, strlen(buffer));
    for (size_t i = 0; i < AudioSystem::NUM_STREAM_TYPES; i++) {
        snprintf(buffer, SIZE, " %02d", i);
        mStreams[i].dump(buffer + 3, SIZE);
        write(fd, buffer, strlen(buffer));
    }

    return NO_ERROR;
}

// ----------------------------------------------------------------------------
// AudioPolicyManagerGeneric
// ----------------------------------------------------------------------------

// ---  class factory

AudioPolicyManagerGeneric::AudioPolicyManagerGeneric(AudioPolicyClientInterface *clientInterface)
    :
#ifdef AUDIO_POLICY_TEST
    Thread(false),
#endif //AUDIO_POLICY_TEST
    mPhoneState(AudioSystem::MODE_NORMAL), mRingerMode(0)
{
    mpClientInterface = clientInterface;

    for (int i = 0; i < AudioSystem::NUM_FORCE_USE; i++) {
        mForceUse[i] = AudioSystem::FORCE_NONE;
    }

    // devices available by default are speaker, ear piece and microphone
    mAvailableOutputDevices = AudioSystem::DEVICE_OUT_SPEAKER;
    mAvailableInputDevices = AudioSystem::DEVICE_IN_BUILTIN_MIC;

    // open hardware output
    AudioOutputDescriptor *outputDesc = new AudioOutputDescriptor();
    outputDesc->mDevice = (uint32_t)AudioSystem::DEVICE_OUT_SPEAKER;
    mHardwareOutput = mpClientInterface->openOutput(&outputDesc->mDevice,
                                    &outputDesc->mSamplingRate,
                                    &outputDesc->mFormat,
                                    &outputDesc->mChannels,
                                    &outputDesc->mLatency,
                                    outputDesc->mFlags);

    if (mHardwareOutput == 0) {
        LOGE("Failed to initialize hardware output stream, samplingRate: %d, format %d, channels %d",
                outputDesc->mSamplingRate, outputDesc->mFormat, outputDesc->mChannels);
    } else {
        mOutputs.add(mHardwareOutput, outputDesc);
    }

#ifdef AUDIO_POLICY_TEST
    AudioParameter outputCmd = AudioParameter();
    outputCmd.addInt(String8("set_id"), 0);
    mpClientInterface->setParameters(mHardwareOutput, outputCmd.toString());

    mTestDevice = AudioSystem::DEVICE_OUT_SPEAKER;
    mTestSamplingRate = 44100;
    mTestFormat = AudioSystem::PCM_16_BIT;
    mTestChannels =  AudioSystem::CHANNEL_OUT_STEREO;
    mTestLatencyMs = 0;
    mCurOutput = 0;
    mDirectOutput = false;
    for (int i = 0; i < NUM_TEST_OUTPUTS; i++) {
        mTestOutputs[i] = 0;
    }

    const size_t SIZE = 256;
    char buffer[SIZE];
    snprintf(buffer, SIZE, "AudioPolicyManagerTest");
    run(buffer, ANDROID_PRIORITY_AUDIO);
#endif //AUDIO_POLICY_TEST
}

AudioPolicyManagerGeneric::~AudioPolicyManagerGeneric()
{
#ifdef AUDIO_POLICY_TEST
    exit();
#endif //AUDIO_POLICY_TEST

   for (size_t i = 0; i < mOutputs.size(); i++) {
        mpClientInterface->closeOutput(mOutputs.keyAt(i));
        delete mOutputs.valueAt(i);
   }
   mOutputs.clear();
   for (size_t i = 0; i < mInputs.size(); i++) {
        mpClientInterface->closeInput(mInputs.keyAt(i));
        delete mInputs.valueAt(i);
   }
   mInputs.clear();
}

#ifdef AUDIO_POLICY_TEST
bool AudioPolicyManagerGeneric::threadLoop()
{
    LOGV("entering threadLoop()");
    while (!exitPending())
    {
        String8 command;
        int valueInt;
        String8 value;

        Mutex::Autolock _l(mLock);
        mWaitWorkCV.waitRelative(mLock, milliseconds(50));

        command = mpClientInterface->getParameters(0, String8("test_cmd_policy"));
        AudioParameter param = AudioParameter(command);

        if (param.getInt(String8("test_cmd_policy"), valueInt) == NO_ERROR &&
            valueInt != 0) {
            LOGV("Test command %s received", command.string());
            String8 target;
            if (param.get(String8("target"), target) != NO_ERROR) {
                target = "Manager";
            }
            if (param.getInt(String8("test_cmd_policy_output"), valueInt) == NO_ERROR) {
                param.remove(String8("test_cmd_policy_output"));
                mCurOutput = valueInt;
            }
            if (param.get(String8("test_cmd_policy_direct"), value) == NO_ERROR) {
                param.remove(String8("test_cmd_policy_direct"));
                if (value == "false") {
                    mDirectOutput = false;
                } else if (value == "true") {
                    mDirectOutput = true;
                }
            }
            if (param.getInt(String8("test_cmd_policy_input"), valueInt) == NO_ERROR) {
                param.remove(String8("test_cmd_policy_input"));
                mTestInput = valueInt;
            }

            if (param.get(String8("test_cmd_policy_format"), value) == NO_ERROR) {
                param.remove(String8("test_cmd_policy_format"));
                int format = AudioSystem::INVALID_FORMAT;
                if (value == "PCM 16 bits") {
                    format = AudioSystem::PCM_16_BIT;
                } else if (value == "PCM 8 bits") {
                    format = AudioSystem::PCM_8_BIT;
                } else if (value == "Compressed MP3") {
                    format = AudioSystem::MP3;
                }
                if (format != AudioSystem::INVALID_FORMAT) {
                    if (target == "Manager") {
                        mTestFormat = format;
                    } else if (mTestOutputs[mCurOutput] != 0) {
                        AudioParameter outputParam = AudioParameter();
                        outputParam.addInt(String8("format"), format);
                        mpClientInterface->setParameters(mTestOutputs[mCurOutput], outputParam.toString());
                    }
                }
            }
            if (param.get(String8("test_cmd_policy_channels"), value) == NO_ERROR) {
                param.remove(String8("test_cmd_policy_channels"));
                int channels = 0;

                if (value == "Channels Stereo") {
                    channels =  AudioSystem::CHANNEL_OUT_STEREO;
                } else if (value == "Channels Mono") {
                    channels =  AudioSystem::CHANNEL_OUT_MONO;
                }
                if (channels != 0) {
                    if (target == "Manager") {
                        mTestChannels = channels;
                    } else if (mTestOutputs[mCurOutput] != 0) {
                        AudioParameter outputParam = AudioParameter();
                        outputParam.addInt(String8("channels"), channels);
                        mpClientInterface->setParameters(mTestOutputs[mCurOutput], outputParam.toString());
                    }
                }
            }
            if (param.getInt(String8("test_cmd_policy_sampleRate"), valueInt) == NO_ERROR) {
                param.remove(String8("test_cmd_policy_sampleRate"));
                if (valueInt >= 0 && valueInt <= 96000) {
                    int samplingRate = valueInt;
                    if (target == "Manager") {
                        mTestSamplingRate = samplingRate;
                    } else if (mTestOutputs[mCurOutput] != 0) {
                        AudioParameter outputParam = AudioParameter();
                        outputParam.addInt(String8("sampling_rate"), samplingRate);
                        mpClientInterface->setParameters(mTestOutputs[mCurOutput], outputParam.toString());
                    }
                }
            }

            if (param.get(String8("test_cmd_policy_reopen"), value) == NO_ERROR) {
                param.remove(String8("test_cmd_policy_reopen"));

                mpClientInterface->closeOutput(mHardwareOutput);
                delete mOutputs.valueFor(mHardwareOutput);
                mOutputs.removeItem(mHardwareOutput);

                AudioOutputDescriptor *outputDesc = new AudioOutputDescriptor();
                outputDesc->mDevice = (uint32_t)AudioSystem::DEVICE_OUT_SPEAKER;
                mHardwareOutput = mpClientInterface->openOutput(&outputDesc->mDevice,
                                                &outputDesc->mSamplingRate,
                                                &outputDesc->mFormat,
                                                &outputDesc->mChannels,
                                                &outputDesc->mLatency,
                                                outputDesc->mFlags);
                if (mHardwareOutput == 0) {
                    LOGE("Failed to reopen hardware output stream, samplingRate: %d, format %d, channels %d",
                            outputDesc->mSamplingRate, outputDesc->mFormat, outputDesc->mChannels);
                } else {
                    AudioParameter outputCmd = AudioParameter();
                    outputCmd.addInt(String8("set_id"), 0);
                    mpClientInterface->setParameters(mHardwareOutput, outputCmd.toString());
                    mOutputs.add(mHardwareOutput, outputDesc);
                }
            }


            mpClientInterface->setParameters(0, String8("test_cmd_policy="));
        }
    }
    return false;
}

void AudioPolicyManagerGeneric::exit()
{
    {
        AutoMutex _l(mLock);
        requestExit();
        mWaitWorkCV.signal();
    }
    requestExitAndWait();
}

int AudioPolicyManagerGeneric::testOutputIndex(audio_io_handle_t output)
{
    for (int i = 0; i < NUM_TEST_OUTPUTS; i++) {
        if (output == mTestOutputs[i]) return i;
    }
    return 0;
}
#endif //AUDIO_POLICY_TEST

// ---

AudioPolicyManagerGeneric::routing_strategy AudioPolicyManagerGeneric::getStrategy(AudioSystem::stream_type stream)
{
    // stream to strategy mapping
    switch (stream) {
    case AudioSystem::VOICE_CALL:
    case AudioSystem::BLUETOOTH_SCO:
        return STRATEGY_PHONE;
    case AudioSystem::RING:
    case AudioSystem::NOTIFICATION:
    case AudioSystem::ALARM:
    case AudioSystem::ENFORCED_AUDIBLE:
        return STRATEGY_SONIFICATION;
    case AudioSystem::DTMF:
        return STRATEGY_DTMF;
    default:
        LOGE("unknown stream type");
    case AudioSystem::SYSTEM:
        // NOTE: SYSTEM stream uses MEDIA strategy because muting music and switching outputs
        // while key clicks are played produces a poor result
    case AudioSystem::TTS:
    case AudioSystem::MUSIC:
        return STRATEGY_MEDIA;
    }
}


float AudioPolicyManagerGeneric::computeVolume(int stream, int index, uint32_t device)
{
    float volume = 1.0;

    StreamDescriptor &streamDesc = mStreams[stream];

    // Force max volume if stream cannot be muted
    if (!streamDesc.mCanBeMuted) index = streamDesc.mIndexMax;

    int volInt = (100 * (index - streamDesc.mIndexMin)) / (streamDesc.mIndexMax - streamDesc.mIndexMin);
    volume = AudioSystem::linearToLog(volInt);

    return volume;
}

void AudioPolicyManagerGeneric::setStreamMute(int stream, bool on, audio_io_handle_t output)
{
    LOGV("setStreamMute() stream %d, mute %d, output %d", stream, on, output);

    StreamDescriptor &streamDesc = mStreams[stream];

    if (on) {
        if (streamDesc.mMuteCount++ == 0) {
            if (streamDesc.mCanBeMuted) {
                mpClientInterface->setStreamVolume((AudioSystem::stream_type)stream, 0, output);
            }
        }
    } else {
        if (streamDesc.mMuteCount == 0) {
            LOGW("setStreamMute() unmuting non muted stream!");
            return;
        }
        if (--streamDesc.mMuteCount == 0) {
            uint32_t device = mOutputs.valueFor(output)->mDevice;
            float volume = computeVolume(stream, streamDesc.mIndexCur, device);
            mpClientInterface->setStreamVolume((AudioSystem::stream_type)stream, volume, output);
        }
    }
}

void AudioPolicyManagerGeneric::handleIncallSonification(int stream, bool starting)
{
    // if the stream pertains to sonification strategy and we are in call we must
    // mute the stream if it is low visibility. If it is high visibility, we must play a tone
    // in the device used for phone strategy and play the tone if the selected device does not
    // interfere with the device used for phone strategy
    if (getStrategy((AudioSystem::stream_type)stream) == STRATEGY_SONIFICATION) {
        AudioOutputDescriptor *outputDesc = mOutputs.valueFor(mHardwareOutput);
        LOGV("handleIncallSonification() stream %d starting %d device %x", stream, starting, outputDesc->mDevice);
        if (outputDesc->isUsedByStream((AudioSystem::stream_type)stream)) {
            if (AudioSystem::isLowVisibility((AudioSystem::stream_type)stream)) {
                LOGV("handleIncallSonification() low visibility");
                setStreamMute(stream, starting, mHardwareOutput);
            } else {
                if (starting) {
                    mpClientInterface->startTone(ToneGenerator::TONE_SUP_CALL_WAITING, AudioSystem::VOICE_CALL);
                } else {
                    mpClientInterface->stopTone();
                }
            }
        }
    }
}


// --- AudioOutputDescriptor class implementation

AudioPolicyManagerGeneric::AudioOutputDescriptor::AudioOutputDescriptor()
    : mSamplingRate(0), mFormat(0), mChannels(0), mLatency(0),
    mFlags((AudioSystem::output_flags)0), mDevice(0)
{
    // clear usage count for all stream types
    for (int i = 0; i < AudioSystem::NUM_STREAM_TYPES; i++) {
        mRefCount[i] = 0;
    }
}

uint32_t AudioPolicyManagerGeneric::AudioOutputDescriptor::device()
{
    return mDevice;
}

void AudioPolicyManagerGeneric::AudioOutputDescriptor::changeRefCount(AudioSystem::stream_type stream, int delta)
{
    if ((delta + (int)mRefCount[stream]) < 0) {
        LOGW("changeRefCount() invalid delta %d for stream %d, refCount %d", delta, stream, mRefCount[stream]);
        mRefCount[stream] = 0;
        return;
    }
    mRefCount[stream] += delta;
    LOGV("changeRefCount() stream %d, count %d", stream, mRefCount[stream]);
}

uint32_t AudioPolicyManagerGeneric::AudioOutputDescriptor::refCount()
{
    uint32_t refcount = 0;
    for (int i = 0; i < (int)AudioSystem::NUM_STREAM_TYPES; i++) {
        refcount += mRefCount[i];
    }
    return refcount;
}

status_t AudioPolicyManagerGeneric::AudioOutputDescriptor::dump(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    snprintf(buffer, SIZE, " Sampling rate: %d\n", mSamplingRate);
    result.append(buffer);
    snprintf(buffer, SIZE, " Format: %d\n", mFormat);
    result.append(buffer);
    snprintf(buffer, SIZE, " Channels: %08x\n", mChannels);
    result.append(buffer);
    snprintf(buffer, SIZE, " Latency: %d\n", mLatency);
    result.append(buffer);
    snprintf(buffer, SIZE, " Flags %08x\n", mFlags);
    result.append(buffer);
    snprintf(buffer, SIZE, " Devices %08x\n", mDevice);
    result.append(buffer);
    snprintf(buffer, SIZE, " Stream refCount\n");
    result.append(buffer);
    for (int i = 0; i < AudioSystem::NUM_STREAM_TYPES; i++) {
        snprintf(buffer, SIZE, " %02d     %d\n", i, mRefCount[i]);
        result.append(buffer);
    }
    write(fd, result.string(), result.size());

    return NO_ERROR;
}

// --- AudioInputDescriptor class implementation

AudioPolicyManagerGeneric::AudioInputDescriptor::AudioInputDescriptor()
    : mSamplingRate(0), mFormat(0), mChannels(0),
     mAcoustics((AudioSystem::audio_in_acoustics)0), mDevice(0), mRefCount(0)
{
}

status_t AudioPolicyManagerGeneric::AudioInputDescriptor::dump(int fd)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    snprintf(buffer, SIZE, " Sampling rate: %d\n", mSamplingRate);
    result.append(buffer);
    snprintf(buffer, SIZE, " Format: %d\n", mFormat);
    result.append(buffer);
    snprintf(buffer, SIZE, " Channels: %08x\n", mChannels);
    result.append(buffer);
    snprintf(buffer, SIZE, " Acoustics %08x\n", mAcoustics);
    result.append(buffer);
    snprintf(buffer, SIZE, " Devices %08x\n", mDevice);
    result.append(buffer);
    snprintf(buffer, SIZE, " Ref Count %d\n", mRefCount);
    result.append(buffer);
    write(fd, result.string(), result.size());

    return NO_ERROR;
}

// --- StreamDescriptor class implementation

void AudioPolicyManagerGeneric::StreamDescriptor::dump(char* buffer, size_t size)
{
    snprintf(buffer, size, "      %02d         %02d         %02d         %02d          %d\n",
            mIndexMin,
            mIndexMax,
            mIndexCur,
            mMuteCount,
            mCanBeMuted);
}

}; // namespace android
