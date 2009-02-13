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
#include <utils/IServiceManager.h>
#include <media/AudioSystem.h>
#include <math.h>

namespace android {

// client singleton for AudioFlinger binder interface
Mutex AudioSystem::gLock;
sp<IAudioFlinger> AudioSystem::gAudioFlinger;
sp<AudioSystem::AudioFlingerClient> AudioSystem::gAudioFlingerClient;
audio_error_callback AudioSystem::gAudioErrorCallback = NULL;
// Cached values
int AudioSystem::gOutSamplingRate[NUM_AUDIO_OUTPUT_TYPES];
int AudioSystem::gOutFrameCount[NUM_AUDIO_OUTPUT_TYPES];
uint32_t AudioSystem::gOutLatency[NUM_AUDIO_OUTPUT_TYPES];
bool AudioSystem::gA2dpEnabled;
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
        // Cache frequently accessed parameters 
        for (int output = 0; output < NUM_AUDIO_OUTPUT_TYPES; output++) {
            gOutFrameCount[output] = (int)gAudioFlinger->frameCount(output);
            gOutSamplingRate[output] = (int)gAudioFlinger->sampleRate(output);
            gOutLatency[output] = gAudioFlinger->latency(output);
        }
        gA2dpEnabled = gAudioFlinger->isA2dpEnabled();
    }
    LOGE_IF(gAudioFlinger==0, "no AudioFlinger!?");
    return gAudioFlinger;
}

// routing helper functions
status_t AudioSystem::speakerphone(bool state) {
    uint32_t routes = state ? ROUTE_SPEAKER : ROUTE_EARPIECE;
    return setRouting(MODE_IN_CALL, routes, ROUTE_ALL);
}

status_t AudioSystem::isSpeakerphoneOn(bool* state) {
    uint32_t routes = 0;
    status_t s = getRouting(MODE_IN_CALL, &routes);
    *state = !!(routes & ROUTE_SPEAKER);
    return s;
}

status_t AudioSystem::bluetoothSco(bool state) {
    uint32_t mask = ROUTE_BLUETOOTH_SCO;
    uint32_t routes = state ? mask : ROUTE_EARPIECE;
    return setRouting(MODE_IN_CALL, routes, ROUTE_ALL);
}

status_t AudioSystem::isBluetoothScoOn(bool* state) {
    uint32_t routes = 0;
    status_t s = getRouting(MODE_IN_CALL, &routes);
    *state = !!(routes & ROUTE_BLUETOOTH_SCO);
    return s;
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

status_t AudioSystem::setStreamVolume(int stream, float value)
{
    if (uint32_t(stream) >= NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    af->setStreamVolume(stream, value);
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

status_t AudioSystem::getStreamVolume(int stream, float* volume)
{
    if (uint32_t(stream) >= NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *volume = af->streamVolume(stream);
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

status_t AudioSystem::getMode(int* mode)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *mode = af->getMode();
    return NO_ERROR;
}

status_t AudioSystem::setRouting(int mode, uint32_t routes, uint32_t mask)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    return af->setRouting(mode, routes, mask);
}

status_t AudioSystem::getRouting(int mode, uint32_t* routes)
{
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    uint32_t r = af->getRouting(mode);
    *routes = r;
    return NO_ERROR;
}

status_t AudioSystem::isMusicActive(bool* state) {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *state = af->isMusicActive();
    return NO_ERROR;
}

// Temporary interface, do not use
// TODO: Replace with a more generic key:value get/set mechanism
status_t AudioSystem::setParameter(const char* key, const char* value) {
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    return af->setParameter(key, value);
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
    int output = getOutput(streamType);

    if (gOutSamplingRate[output] == 0) {
        const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
        if (af == 0) return PERMISSION_DENIED;
        // gOutSamplingRate is updated by get_audio_flinger()
    }
    LOGV("getOutputSamplingRate() streamType %d, output %d, sampling rate %d", streamType, output, gOutSamplingRate[output]);
    *samplingRate = gOutSamplingRate[output];
    
    return NO_ERROR;
}

status_t AudioSystem::getOutputFrameCount(int* frameCount, int streamType)
{
    int output = getOutput(streamType);

    if (gOutFrameCount[output] == 0) {
        const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
        if (af == 0) return PERMISSION_DENIED;
        // gOutFrameCount is updated by get_audio_flinger()
    }
    LOGV("getOutputFrameCount() streamType %d, output %d, frame count %d", streamType, output, gOutFrameCount[output]);

    *frameCount = gOutFrameCount[output];
    return NO_ERROR;
}

status_t AudioSystem::getOutputLatency(uint32_t* latency, int streamType)
{
    int output = getOutput(streamType);

    if (gOutLatency[output] == 0) {
        const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
        if (af == 0) return PERMISSION_DENIED;
        // gOutLatency is updated by get_audio_flinger()
    }
    LOGV("getOutputLatency() streamType %d, output %d, latency %d", streamType, output, gOutLatency[output]);

    *latency = gOutLatency[output];
    
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

// ---------------------------------------------------------------------------

void AudioSystem::AudioFlingerClient::binderDied(const wp<IBinder>& who) {   
    Mutex::Autolock _l(AudioSystem::gLock);
    AudioSystem::gAudioFlinger.clear();

    for (int output = 0; output < NUM_AUDIO_OUTPUT_TYPES; output++) {
        gOutFrameCount[output] = 0;
        gOutSamplingRate[output] = 0;
        gOutLatency[output] = 0;
    }
    AudioSystem::gInBuffSize = 0;

    if (gAudioErrorCallback) {
        gAudioErrorCallback(DEAD_OBJECT);
    }
    LOGW("AudioFlinger server died!");
}

void AudioSystem::AudioFlingerClient::a2dpEnabledChanged(bool enabled) {
    gA2dpEnabled = enabled;        
    LOGV("AudioFlinger A2DP enabled status changed! %d", enabled);
}

void AudioSystem::setErrorCallback(audio_error_callback cb) {
    Mutex::Autolock _l(AudioSystem::gLock);
    gAudioErrorCallback = cb;
}

int AudioSystem::getOutput(int streamType)
{  
    if (streamType == DEFAULT) {
        streamType = MUSIC;
    }
    if (gA2dpEnabled && routedToA2dpOutput(streamType)) {
        return AUDIO_OUTPUT_A2DP;
    } else {
        return AUDIO_OUTPUT_HARDWARE;
    }
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



}; // namespace android

