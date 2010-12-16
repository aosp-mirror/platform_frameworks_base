/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

#define LOG_TAG "AudioSystem"
//#define LOG_NDEBUG 0

#include <utils/Log.h>
#include <binder/IServiceManager.h>
#include <media/AudioSystem.h>
#include <media/IAudioPolicyService.h>
#include <math.h>

// ----------------------------------------------------------------------------
// the sim build doesn't have gettid

#ifndef HAVE_GETTID
# define gettid getpid
#endif

// ----------------------------------------------------------------------------

namespace android {

// client singleton for AudioFlinger binder interface
Mutex AudioSystem::gLock;
sp<IAudioFlinger> AudioSystem::gAudioFlinger;
sp<AudioSystem::AudioFlingerClient> AudioSystem::gAudioFlingerClient;
audio_error_callback AudioSystem::gAudioErrorCallback = NULL;
// Cached values
DefaultKeyedVector<int, audio_io_handle_t> AudioSystem::gStreamOutputMap(0);
DefaultKeyedVector<audio_io_handle_t, AudioSystem::OutputDescriptor *> AudioSystem::gOutputs(0);

// Cached values for recording queries
uint32_t AudioSystem::gPrevInSamplingRate = 16000;
int AudioSystem::gPrevInFormat = AudioSystem::PCM_16_BIT;
int AudioSystem::gPrevInChannelCount = 1;
size_t AudioSystem::gInBuffSize = 0;


// establish binder interface to AudioFlinger service
const sp<IAudioFlinger>& AudioSystem::get_audio_flinger()
{
    Mutex::Autolock _l(gLock);
    if (gAudioFlinger.get() == 0) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            binder = sm->getService(String16("media.audio_flinger"));
            if (binder != 0)
                break;
            LOGW("AudioFlinger not published, waiting...");
            usleep(500000); // 0.5 s
        } while(true);
        if (gAudioFlingerClient == NULL) {
            gAudioFlingerClient = new AudioFlingerClient();
        } else {
            if (gAudioErrorCallback) {
                gAudioErrorCallback(NO_ERROR);
            }
         }
        binder->linkToDeath(gAudioFlingerClient);
        gAudioFlinger = interface_cast<IAudioFlinger>(binder);
        gAudioFlinger->registerClient(gAudioFlingerClient);
    }
    LOGE_IF(gAudioFlinger==0, "no AudioFlinger!?");

    return gAudioFlinger;
}

status_t AudioSystem::muteMicrophone(bool state) {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    return af->setMicMute(state);
}

status_t AudioSystem::isMicrophoneMuted(bool* state) {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *state = af->getMicMute();
    return NO_ERROR;
}

status_t AudioSystem::setMasterVolume(float value)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    af->setMasterVolume(value);
    return NO_ERROR;
}

status_t AudioSystem::setMasterMute(bool mute)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    af->setMasterMute(mute);
    return NO_ERROR;
}

status_t AudioSystem::getMasterVolume(float* volume)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *volume = af->masterVolume();
    return NO_ERROR;
}

status_t AudioSystem::getMasterMute(bool* mute)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *mute = af->masterMute();
    return NO_ERROR;
}

status_t AudioSystem::setStreamVolume(int stream, float value, int output)
{
    if (uint32_t(stream) >= NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    af->setStreamVolume(stream, value, output);
    return NO_ERROR;
}

status_t AudioSystem::setStreamMute(int stream, bool mute)
{
    if (uint32_t(stream) >= NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    af->setStreamMute(stream, mute);
    return NO_ERROR;
}

status_t AudioSystem::getStreamVolume(int stream, float* volume, int output)
{
    if (uint32_t(stream) >= NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *volume = af->streamVolume(stream, output);
    return NO_ERROR;
}

status_t AudioSystem::getStreamMute(int stream, bool* mute)
{
    if (uint32_t(stream) >= NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *mute = af->streamMute(stream);
    return NO_ERROR;
}

status_t AudioSystem::setMode(int mode)
{
    if (mode >= NUM_MODES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    return af->setMode(mode);
}


status_t AudioSystem::isStreamActive(int stream, bool* state) {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *state = af->isStreamActive(stream);
    return NO_ERROR;
}


status_t AudioSystem::setParameters(audio_io_handle_t ioHandle, const String8& keyValuePairs) {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    return af->setParameters(ioHandle, keyValuePairs);
}

String8 AudioSystem::getParameters(audio_io_handle_t ioHandle, const String8& keys) {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    String8 result = String8("");
    if (af == 0) return result;

    result = af->getParameters(ioHandle, keys);
    return result;
}

// convert volume steps to natural log scale

// change this value to change volume scaling
static const float dBPerStep = 0.5f;
// shouldn't need to touch these
static const float dBConvert = -dBPerStep * 2.302585093f / 20.0f;
static const float dBConvertInverse = 1.0f / dBConvert;

float AudioSystem::linearToLog(int volume)
{
    // float v = volume ? exp(float(100 - volume) * dBConvert) : 0;
    // LOGD("linearToLog(%d)=%f", volume, v);
    // return v;
    return volume ? exp(float(100 - volume) * dBConvert) : 0;
}

int AudioSystem::logToLinear(float volume)
{
    // int v = volume ? 100 - int(dBConvertInverse * log(volume) + 0.5) : 0;
    // LOGD("logTolinear(%d)=%f", v, volume);
    // return v;
    return volume ? 100 - int(dBConvertInverse * log(volume) + 0.5) : 0;
}

status_t AudioSystem::getOutputSamplingRate(int* samplingRate, int streamType)
{
    OutputDescriptor *outputDesc;
    audio_io_handle_t output;

    if (streamType == DEFAULT) {
        streamType = MUSIC;
    }

    output = getOutput((stream_type)streamType);
    if (output == 0) {
        return PERMISSION_DENIED;
    }

    gLock.lock();
    outputDesc = AudioSystem::gOutputs.valueFor(output);
    if (outputDesc == 0) {
        LOGV("getOutputSamplingRate() no output descriptor for output %d in gOutputs", output);
        gLock.unlock();
        const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
        if (af == 0) return PERMISSION_DENIED;
        *samplingRate = af->sampleRate(output);
    } else {
        LOGV("getOutputSamplingRate() reading from output desc");
        *samplingRate = outputDesc->samplingRate;
        gLock.unlock();
    }

    LOGV("getOutputSamplingRate() streamType %d, output %d, sampling rate %d", streamType, output, *samplingRate);

    return NO_ERROR;
}

status_t AudioSystem::getOutputFrameCount(int* frameCount, int streamType)
{
    OutputDescriptor *outputDesc;
    audio_io_handle_t output;

    if (streamType == DEFAULT) {
        streamType = MUSIC;
    }

    output = getOutput((stream_type)streamType);
    if (output == 0) {
        return PERMISSION_DENIED;
    }

    gLock.lock();
    outputDesc = AudioSystem::gOutputs.valueFor(output);
    if (outputDesc == 0) {
        gLock.unlock();
        const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
        if (af == 0) return PERMISSION_DENIED;
        *frameCount = af->frameCount(output);
    } else {
        *frameCount = outputDesc->frameCount;
        gLock.unlock();
    }

    LOGV("getOutputFrameCount() streamType %d, output %d, frameCount %d", streamType, output, *frameCount);

    return NO_ERROR;
}

status_t AudioSystem::getOutputLatency(uint32_t* latency, int streamType)
{
    OutputDescriptor *outputDesc;
    audio_io_handle_t output;

    if (streamType == DEFAULT) {
        streamType = MUSIC;
    }

    output = getOutput((stream_type)streamType);
    if (output == 0) {
        return PERMISSION_DENIED;
    }

    gLock.lock();
    outputDesc = AudioSystem::gOutputs.valueFor(output);
    if (outputDesc == 0) {
        gLock.unlock();
        const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
        if (af == 0) return PERMISSION_DENIED;
        *latency = af->latency(output);
    } else {
        *latency = outputDesc->latency;
        gLock.unlock();
    }

    LOGV("getOutputLatency() streamType %d, output %d, latency %d", streamType, output, *latency);

    return NO_ERROR;
}

status_t AudioSystem::getInputBufferSize(uint32_t sampleRate, int format, int channelCount,
    size_t* buffSize)
{
    // Do we have a stale gInBufferSize or are we requesting the input buffer size for new values
    if ((gInBuffSize == 0) || (sampleRate != gPrevInSamplingRate) || (format != gPrevInFormat)
        || (channelCount != gPrevInChannelCount)) {
        // save the request params
        gPrevInSamplingRate = sampleRate;
        gPrevInFormat = format;
        gPrevInChannelCount = channelCount;

        gInBuffSize = 0;
        const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
        if (af == 0) {
            return PERMISSION_DENIED;
        }
        gInBuffSize = af->getInputBufferSize(sampleRate, format, channelCount);
    }
    *buffSize = gInBuffSize;

    return NO_ERROR;
}

status_t AudioSystem::setVoiceVolume(float value)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    return af->setVoiceVolume(value);
}

status_t AudioSystem::getRenderPosition(uint32_t *halFrames, uint32_t *dspFrames, int stream)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;

    if (stream == DEFAULT) {
        stream = MUSIC;
    }

    return af->getRenderPosition(halFrames, dspFrames, getOutput((stream_type)stream));
}

unsigned int AudioSystem::getInputFramesLost(audio_io_handle_t ioHandle) {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    unsigned int result = 0;
    if (af == 0) return result;
    if (ioHandle == 0) return result;

    result = af->getInputFramesLost(ioHandle);
    return result;
}

int AudioSystem::newAudioSessionId() {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return 0;
    return af->newAudioSessionId();
}

// ---------------------------------------------------------------------------

void AudioSystem::AudioFlingerClient::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock _l(AudioSystem::gLock);

    AudioSystem::gAudioFlinger.clear();
    // clear output handles and stream to output map caches
    AudioSystem::gStreamOutputMap.clear();
    AudioSystem::gOutputs.clear();

    if (gAudioErrorCallback) {
        gAudioErrorCallback(DEAD_OBJECT);
    }
    LOGW("AudioFlinger server died!");
}

void AudioSystem::AudioFlingerClient::ioConfigChanged(int event, int ioHandle, void *param2) {
    LOGV("ioConfigChanged() event %d", event);
    OutputDescriptor *desc;
    uint32_t stream;

    if (ioHandle == 0) return;

    Mutex::Autolock _l(AudioSystem::gLock);

    switch (event) {
    case STREAM_CONFIG_CHANGED:
        if (param2 == 0) break;
        stream = *(uint32_t *)param2;
        LOGV("ioConfigChanged() STREAM_CONFIG_CHANGED stream %d, output %d", stream, ioHandle);
        if (gStreamOutputMap.indexOfKey(stream) >= 0) {
            gStreamOutputMap.replaceValueFor(stream, ioHandle);
        }
        break;
    case OUTPUT_OPENED: {
        if (gOutputs.indexOfKey(ioHandle) >= 0) {
            LOGV("ioConfigChanged() opening already existing output! %d", ioHandle);
            break;
        }
        if (param2 == 0) break;
        desc = (OutputDescriptor *)param2;

        OutputDescriptor *outputDesc =  new OutputDescriptor(*desc);
        gOutputs.add(ioHandle, outputDesc);
        LOGV("ioConfigChanged() new output samplingRate %d, format %d channels %d frameCount %d latency %d",
                outputDesc->samplingRate, outputDesc->format, outputDesc->channels, outputDesc->frameCount, outputDesc->latency);
        } break;
    case OUTPUT_CLOSED: {
        if (gOutputs.indexOfKey(ioHandle) < 0) {
            LOGW("ioConfigChanged() closing unknow output! %d", ioHandle);
            break;
        }
        LOGV("ioConfigChanged() output %d closed", ioHandle);

        gOutputs.removeItem(ioHandle);
        for (int i = gStreamOutputMap.size() - 1; i >= 0 ; i--) {
            if (gStreamOutputMap.valueAt(i) == ioHandle) {
                gStreamOutputMap.removeItemsAt(i);
            }
        }
        } break;

    case OUTPUT_CONFIG_CHANGED: {
        int index = gOutputs.indexOfKey(ioHandle);
        if (index < 0) {
            LOGW("ioConfigChanged() modifying unknow output! %d", ioHandle);
            break;
        }
        if (param2 == 0) break;
        desc = (OutputDescriptor *)param2;

        LOGV("ioConfigChanged() new config for output %d samplingRate %d, format %d channels %d frameCount %d latency %d",
                ioHandle, desc->samplingRate, desc->format,
                desc->channels, desc->frameCount, desc->latency);
        OutputDescriptor *outputDesc = gOutputs.valueAt(index);
        delete outputDesc;
        outputDesc =  new OutputDescriptor(*desc);
        gOutputs.replaceValueFor(ioHandle, outputDesc);
    } break;
    case INPUT_OPENED:
    case INPUT_CLOSED:
    case INPUT_CONFIG_CHANGED:
        break;

    }
}

void AudioSystem::setErrorCallback(audio_error_callback cb) {
    Mutex::Autolock _l(gLock);
    gAudioErrorCallback = cb;
}

bool AudioSystem::routedToA2dpOutput(int streamType) {
    switch(streamType) {
    case MUSIC:
    case VOICE_CALL:
    case BLUETOOTH_SCO:
    case SYSTEM:
        return true;
    default:
        return false;
    }
}


// client singleton for AudioPolicyService binder interface
sp<IAudioPolicyService> AudioSystem::gAudioPolicyService;
sp<AudioSystem::AudioPolicyServiceClient> AudioSystem::gAudioPolicyServiceClient;


// establish binder interface to AudioFlinger service
const sp<IAudioPolicyService>& AudioSystem::get_audio_policy_service()
{
    gLock.lock();
    if (gAudioPolicyService.get() == 0) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            binder = sm->getService(String16("media.audio_policy"));
            if (binder != 0)
                break;
            LOGW("AudioPolicyService not published, waiting...");
            usleep(500000); // 0.5 s
        } while(true);
        if (gAudioPolicyServiceClient == NULL) {
            gAudioPolicyServiceClient = new AudioPolicyServiceClient();
        }
        binder->linkToDeath(gAudioPolicyServiceClient);
        gAudioPolicyService = interface_cast<IAudioPolicyService>(binder);
        gLock.unlock();
    } else {
        gLock.unlock();
    }
    return gAudioPolicyService;
}

status_t AudioSystem::setDeviceConnectionState(audio_devices device,
                                                  device_connection_state state,
                                                  const char *device_address)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;

    return aps->setDeviceConnectionState(device, state, device_address);
}

AudioSystem::device_connection_state AudioSystem::getDeviceConnectionState(audio_devices device,
                                                  const char *device_address)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return DEVICE_STATE_UNAVAILABLE;

    return aps->getDeviceConnectionState(device, device_address);
}

status_t AudioSystem::setPhoneState(int state)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;

    return aps->setPhoneState(state);
}

status_t AudioSystem::setRingerMode(uint32_t mode, uint32_t mask)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->setRingerMode(mode, mask);
}

status_t AudioSystem::setForceUse(force_use usage, forced_config config)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->setForceUse(usage, config);
}

AudioSystem::forced_config AudioSystem::getForceUse(force_use usage)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return FORCE_NONE;
    return aps->getForceUse(usage);
}


audio_io_handle_t AudioSystem::getOutput(stream_type stream,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    output_flags flags)
{
    audio_io_handle_t output = 0;
    // Do not use stream to output map cache if the direct output
    // flag is set or if we are likely to use a direct output
    // (e.g voice call stream @ 8kHz could use BT SCO device and be routed to
    // a direct output on some platforms).
    // TODO: the output cache and stream to output mapping implementation needs to
    // be reworked for proper operation with direct outputs. This code is too specific
    // to the first use case we want to cover (Voice Recognition and Voice Dialer over
    // Bluetooth SCO
    if ((flags & AudioSystem::OUTPUT_FLAG_DIRECT) == 0 &&
        ((stream != AudioSystem::VOICE_CALL && stream != AudioSystem::BLUETOOTH_SCO) ||
         channels != AudioSystem::CHANNEL_OUT_MONO ||
         (samplingRate != 8000 && samplingRate != 16000))) {
        Mutex::Autolock _l(gLock);
        output = AudioSystem::gStreamOutputMap.valueFor(stream);
        LOGV_IF((output != 0), "getOutput() read %d from cache for stream %d", output, stream);
    }
    if (output == 0) {
        const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
        if (aps == 0) return 0;
        output = aps->getOutput(stream, samplingRate, format, channels, flags);
        if ((flags & AudioSystem::OUTPUT_FLAG_DIRECT) == 0) {
            Mutex::Autolock _l(gLock);
            AudioSystem::gStreamOutputMap.add(stream, output);
        }
    }
    return output;
}

status_t AudioSystem::startOutput(audio_io_handle_t output,
                                  AudioSystem::stream_type stream,
                                  int session)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->startOutput(output, stream, session);
}

status_t AudioSystem::stopOutput(audio_io_handle_t output,
                                 AudioSystem::stream_type stream,
                                 int session)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->stopOutput(output, stream, session);
}

void AudioSystem::releaseOutput(audio_io_handle_t output)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return;
    aps->releaseOutput(output);
}

audio_io_handle_t AudioSystem::getInput(int inputSource,
                                    uint32_t samplingRate,
                                    uint32_t format,
                                    uint32_t channels,
                                    audio_in_acoustics acoustics)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return 0;
    return aps->getInput(inputSource, samplingRate, format, channels, acoustics);
}

status_t AudioSystem::startInput(audio_io_handle_t input)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->startInput(input);
}

status_t AudioSystem::stopInput(audio_io_handle_t input)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->stopInput(input);
}

void AudioSystem::releaseInput(audio_io_handle_t input)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return;
    aps->releaseInput(input);
}

status_t AudioSystem::initStreamVolume(stream_type stream,
                                    int indexMin,
                                    int indexMax)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->initStreamVolume(stream, indexMin, indexMax);
}

status_t AudioSystem::setStreamVolumeIndex(stream_type stream, int index)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->setStreamVolumeIndex(stream, index);
}

status_t AudioSystem::getStreamVolumeIndex(stream_type stream, int *index)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->getStreamVolumeIndex(stream, index);
}

uint32_t AudioSystem::getStrategyForStream(AudioSystem::stream_type stream)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return 0;
    return aps->getStrategyForStream(stream);
}

audio_io_handle_t AudioSystem::getOutputForEffect(effect_descriptor_t *desc)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->getOutputForEffect(desc);
}

status_t AudioSystem::registerEffect(effect_descriptor_t *desc,
                                audio_io_handle_t output,
                                uint32_t strategy,
                                int session,
                                int id)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->registerEffect(desc, output, strategy, session, id);
}

status_t AudioSystem::unregisterEffect(int id)
{
    const sp<IAudioPolicyService>& aps = AudioSystem::get_audio_policy_service();
    if (aps == 0) return PERMISSION_DENIED;
    return aps->unregisterEffect(id);
}

// ---------------------------------------------------------------------------

void AudioSystem::AudioPolicyServiceClient::binderDied(const wp<IBinder>& who) {
    Mutex::Autolock _l(AudioSystem::gLock);
    AudioSystem::gAudioPolicyService.clear();

    LOGW("AudioPolicyService server died!");
}

// ---------------------------------------------------------------------------


// use emulated popcount optimization
// http://www.df.lth.se/~john_e/gems/gem002d.html
uint32_t AudioSystem::popCount(uint32_t u)
{
    u = ((u&0x55555555) + ((u>>1)&0x55555555));
    u = ((u&0x33333333) + ((u>>2)&0x33333333));
    u = ((u&0x0f0f0f0f) + ((u>>4)&0x0f0f0f0f));
    u = ((u&0x00ff00ff) + ((u>>8)&0x00ff00ff));
    u = ( u&0x0000ffff) + (u>>16);
    return u;
}

bool AudioSystem::isOutputDevice(audio_devices device)
{
    if ((popCount(device) == 1 ) &&
        ((device & ~AudioSystem::DEVICE_OUT_ALL) == 0)) {
        return true;
    } else {
        return false;
    }
}

bool AudioSystem::isInputDevice(audio_devices device)
{
    if ((popCount(device) == 1 ) &&
        ((device & ~AudioSystem::DEVICE_IN_ALL) == 0)) {
        return true;
    } else {
        return false;
    }
}

bool AudioSystem::isA2dpDevice(audio_devices device)
{
    if ((popCount(device) == 1 ) &&
        (device & (AudioSystem::DEVICE_OUT_BLUETOOTH_A2DP |
                   AudioSystem::DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES |
                   AudioSystem::DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER))) {
        return true;
    } else {
        return false;
    }
}

bool AudioSystem::isBluetoothScoDevice(audio_devices device)
{
    if ((popCount(device) == 1 ) &&
        (device & (AudioSystem::DEVICE_OUT_BLUETOOTH_SCO |
                   AudioSystem::DEVICE_OUT_BLUETOOTH_SCO_HEADSET |
                   AudioSystem::DEVICE_OUT_BLUETOOTH_SCO_CARKIT |
                   AudioSystem::DEVICE_IN_BLUETOOTH_SCO_HEADSET))) {
        return true;
    } else {
        return false;
    }
}

bool AudioSystem::isLowVisibility(stream_type stream)
{
    if (stream == AudioSystem::SYSTEM ||
        stream == AudioSystem::NOTIFICATION ||
        stream == AudioSystem::RING) {
        return true;
    } else {
        return false;
    }
}

bool AudioSystem::isInputChannel(uint32_t channel)
{
    if ((channel & ~AudioSystem::CHANNEL_IN_ALL) == 0) {
        return true;
    } else {
        return false;
    }
}

bool AudioSystem::isOutputChannel(uint32_t channel)
{
    if ((channel & ~AudioSystem::CHANNEL_OUT_ALL) == 0) {
        return true;
    } else {
        return false;
    }
}

bool AudioSystem::isValidFormat(uint32_t format)
{
    switch (format & MAIN_FORMAT_MASK) {
    case         PCM:
    case         MP3:
    case         AMR_NB:
    case         AMR_WB:
    case         AAC:
    case         HE_AAC_V1:
    case         HE_AAC_V2:
    case         VORBIS:
        return true;
    default:
        return false;
    }
}

bool AudioSystem::isLinearPCM(uint32_t format)
{
    switch (format) {
    case         PCM_16_BIT:
    case         PCM_8_BIT:
        return true;
    default:
        return false;
    }
}

//------------------------- AudioParameter class implementation ---------------

const char *AudioParameter::keyRouting = "routing";
const char *AudioParameter::keySamplingRate = "sampling_rate";
const char *AudioParameter::keyFormat = "format";
const char *AudioParameter::keyChannels = "channels";
const char *AudioParameter::keyFrameCount = "frame_count";

AudioParameter::AudioParameter(const String8& keyValuePairs)
{
    char *str = new char[keyValuePairs.length()+1];
    mKeyValuePairs = keyValuePairs;

    strcpy(str, keyValuePairs.string());
    char *pair = strtok(str, ";");
    while (pair != NULL) {
        if (strlen(pair) != 0) {
            size_t eqIdx = strcspn(pair, "=");
            String8 key = String8(pair, eqIdx);
            String8 value;
            if (eqIdx == strlen(pair)) {
                value = String8("");
            } else {
                value = String8(pair + eqIdx + 1);
            }
            if (mParameters.indexOfKey(key) < 0) {
                mParameters.add(key, value);
            } else {
                mParameters.replaceValueFor(key, value);
            }
        } else {
            LOGV("AudioParameter() cstor empty key value pair");
        }
        pair = strtok(NULL, ";");
    }

    delete[] str;
}

AudioParameter::~AudioParameter()
{
    mParameters.clear();
}

String8 AudioParameter::toString()
{
    String8 str = String8("");

    size_t size = mParameters.size();
    for (size_t i = 0; i < size; i++) {
        str += mParameters.keyAt(i);
        str += "=";
        str += mParameters.valueAt(i);
        if (i < (size - 1)) str += ";";
    }
    return str;
}

status_t AudioParameter::add(const String8& key, const String8& value)
{
    if (mParameters.indexOfKey(key) < 0) {
        mParameters.add(key, value);
        return NO_ERROR;
    } else {
        mParameters.replaceValueFor(key, value);
        return ALREADY_EXISTS;
    }
}

status_t AudioParameter::addInt(const String8& key, const int value)
{
    char str[12];
    if (snprintf(str, 12, "%d", value) > 0) {
        String8 str8 = String8(str);
        return add(key, str8);
    } else {
        return BAD_VALUE;
    }
}

status_t AudioParameter::addFloat(const String8& key, const float value)
{
    char str[23];
    if (snprintf(str, 23, "%.10f", value) > 0) {
        String8 str8 = String8(str);
        return add(key, str8);
    } else {
        return BAD_VALUE;
    }
}

status_t AudioParameter::remove(const String8& key)
{
    if (mParameters.indexOfKey(key) >= 0) {
        mParameters.removeItem(key);
        return NO_ERROR;
    } else {
        return BAD_VALUE;
    }
}

status_t AudioParameter::get(const String8& key, String8& value)
{
    if (mParameters.indexOfKey(key) >= 0) {
        value = mParameters.valueFor(key);
        return NO_ERROR;
    } else {
        return BAD_VALUE;
    }
}

status_t AudioParameter::getInt(const String8& key, int& value)
{
    String8 str8;
    status_t result = get(key, str8);
    value = 0;
    if (result == NO_ERROR) {
        int val;
        if (sscanf(str8.string(), "%d", &val) == 1) {
            value = val;
        } else {
            result = INVALID_OPERATION;
        }
    }
    return result;
}

status_t AudioParameter::getFloat(const String8& key, float& value)
{
    String8 str8;
    status_t result = get(key, str8);
    value = 0;
    if (result == NO_ERROR) {
        float val;
        if (sscanf(str8.string(), "%f", &val) == 1) {
            value = val;
        } else {
            result = INVALID_OPERATION;
        }
    }
    return result;
}

status_t AudioParameter::getAt(size_t index, String8& key, String8& value)
{
    if (mParameters.size() > index) {
        key = mParameters.keyAt(index);
        value = mParameters.valueAt(index);
        return NO_ERROR;
    } else {
        return BAD_VALUE;
    }
}
}; // namespace android

