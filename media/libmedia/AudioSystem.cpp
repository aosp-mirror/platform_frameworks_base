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
#include <utils/Log.h>
#include <utils/IServiceManager.h>
#include <media/AudioSystem.h>
#include <media/AudioTrack.h>
#include <math.h>

namespace android {

// client singleton for AudioFlinger binder interface
Mutex AudioSystem::gLock;
sp<IAudioFlinger> AudioSystem::gAudioFlinger;
sp<AudioSystem::DeathNotifier> AudioSystem::gDeathNotifier;
audio_error_callback AudioSystem::gAudioErrorCallback = NULL;

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
        if (gDeathNotifier == NULL) {
            gDeathNotifier = new DeathNotifier();
        } else {
            if (gAudioErrorCallback) {
                gAudioErrorCallback(NO_ERROR);               
            }
         }
        binder->linkToDeath(gDeathNotifier);
        gAudioFlinger = interface_cast<IAudioFlinger>(binder);
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
    uint32_t mask = ROUTE_BLUETOOTH;
    uint32_t routes = state ? mask : ROUTE_EARPIECE;
    return setRouting(MODE_IN_CALL, routes, ROUTE_ALL);
}

status_t AudioSystem::isBluetoothScoOn(bool* state) {
    uint32_t routes = 0;
    status_t s = getRouting(MODE_IN_CALL, &routes);
    *state = !!(routes & ROUTE_BLUETOOTH);
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
    if (uint32_t(stream) >= AudioTrack::NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    af->setStreamVolume(stream, value);
    return NO_ERROR;
}

status_t AudioSystem::setStreamMute(int stream, bool mute)
{
    if (uint32_t(stream) >= AudioTrack::NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    af->setStreamMute(stream, mute);
    return NO_ERROR;
}

status_t AudioSystem::getStreamVolume(int stream, float* volume)
{
    if (uint32_t(stream) >= AudioTrack::NUM_STREAM_TYPES) return BAD_VALUE;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    *volume = af->streamVolume(stream);
    return NO_ERROR;
}

status_t AudioSystem::getStreamMute(int stream, bool* mute)
{
    if (uint32_t(stream) >= AudioTrack::NUM_STREAM_TYPES) return BAD_VALUE;
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

// ---------------------------------------------------------------------------

void AudioSystem::DeathNotifier::binderDied(const wp<IBinder>& who) {    
    Mutex::Autolock _l(AudioSystem::gLock);
    AudioSystem::gAudioFlinger.clear();
    if (gAudioErrorCallback) {
        gAudioErrorCallback(DEAD_OBJECT);
    }
    LOGW("AudioFlinger server died!");
}

void AudioSystem::setErrorCallback(audio_error_callback cb) {
    Mutex::Autolock _l(AudioSystem::gLock);
    gAudioErrorCallback = cb;
}

}; // namespace android

