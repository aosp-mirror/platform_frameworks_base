/*
 * Copyright (C) 2016 The Android Open Source Project
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
 *
 */

// cribbed from samples/native-audio

#include "audioplay.h"

#define CHATTY ALOGD
#define LOG_TAG "audioplay"

#include <string.h>

#include <utils/Log.h>

// for native audio
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

namespace audioplay {
namespace {

// engine interfaces
static SLObjectItf engineObject = NULL;
static SLEngineItf engineEngine;

// output mix interfaces
static SLObjectItf outputMixObject = NULL;

// buffer queue player interfaces
static SLObjectItf bqPlayerObject = NULL;
static SLPlayItf bqPlayerPlay;
static SLAndroidSimpleBufferQueueItf bqPlayerBufferQueue;
static SLMuteSoloItf bqPlayerMuteSolo;
static SLVolumeItf bqPlayerVolume;

// pointer and size of the next player buffer to enqueue, and number of remaining buffers
static const uint8_t* nextBuffer;
static unsigned nextSize;

static const uint32_t ID_RIFF = 0x46464952;
static const uint32_t ID_WAVE = 0x45564157;
static const uint32_t ID_FMT  = 0x20746d66;
static const uint32_t ID_DATA = 0x61746164;

struct RiffWaveHeader {
    uint32_t riff_id;
    uint32_t riff_sz;
    uint32_t wave_id;
};

struct ChunkHeader {
    uint32_t id;
    uint32_t sz;
};

struct ChunkFormat {
    uint16_t audio_format;
    uint16_t num_channels;
    uint32_t sample_rate;
    uint32_t byte_rate;
    uint16_t block_align;
    uint16_t bits_per_sample;
};

// this callback handler is called every time a buffer finishes playing
void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void *context) {
    (void)bq;
    (void)context;
    audioplay::setPlaying(false);
}

bool hasPlayer() {
    return (engineObject != NULL && bqPlayerObject != NULL);
}

// create the engine and output mix objects
bool createEngine() {
    SLresult result;

    // create engine
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("slCreateEngine failed with result %d", result);
        return false;
    }
    (void)result;

    // realize the engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl engine Realize failed with result %d", result);
        return false;
    }
    (void)result;

    // get the engine interface, which is needed in order to create other objects
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl engine GetInterface failed with result %d", result);
        return false;
    }
    (void)result;

    // create output mix
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, NULL, NULL);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl engine CreateOutputMix failed with result %d", result);
        return false;
    }
    (void)result;

    // realize the output mix
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl outputMix Realize failed with result %d", result);
        return false;
    }
    (void)result;

    return true;
}

// create buffer queue audio player
bool createBufferQueueAudioPlayer(const ChunkFormat* chunkFormat) {
    SLresult result;

    // configure audio source
    SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 1};

    SLDataFormat_PCM format_pcm = {
        SL_DATAFORMAT_PCM,
        chunkFormat->num_channels,
        chunkFormat->sample_rate * 1000,  // convert to milliHz
        chunkFormat->bits_per_sample,
        16,
        SL_SPEAKER_FRONT_CENTER,
        SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSource audioSrc = {&loc_bufq, &format_pcm};

    // configure audio sink
    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
    SLDataSink audioSnk = {&loc_outmix, NULL};

    // create audio player
    const SLInterfaceID ids[3] = {SL_IID_BUFFERQUEUE, SL_IID_VOLUME, SL_IID_ANDROIDCONFIGURATION};
    const SLboolean req[3] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    result = (*engineEngine)->CreateAudioPlayer(engineEngine, &bqPlayerObject, &audioSrc, &audioSnk,
            3, ids, req);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl CreateAudioPlayer failed with result %d", result);
        return false;
    }
    (void)result;

    // Use the System stream for boot sound playback.
    SLAndroidConfigurationItf playerConfig;
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject,
        SL_IID_ANDROIDCONFIGURATION, &playerConfig);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("config GetInterface failed with result %d", result);
        return false;
    }
    SLint32 streamType = SL_ANDROID_STREAM_SYSTEM;
    result = (*playerConfig)->SetConfiguration(playerConfig,
        SL_ANDROID_KEY_STREAM_TYPE, &streamType, sizeof(SLint32));
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("SetConfiguration failed with result %d", result);
        return false;
    }
    // use normal performance mode as low latency is not needed. This is not mandatory so
    // do not bail if we fail
    SLuint32 performanceMode = SL_ANDROID_PERFORMANCE_NONE;
    result = (*playerConfig)->SetConfiguration(
           playerConfig, SL_ANDROID_KEY_PERFORMANCE_MODE, &performanceMode, sizeof(SLuint32));
    ALOGW_IF(result != SL_RESULT_SUCCESS,
            "could not set performance mode on player, error %d", result);
    (void)result;

    // realize the player
    result = (*bqPlayerObject)->Realize(bqPlayerObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl player Realize failed with result %d", result);
        return false;
    }
    (void)result;

    // get the play interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAY, &bqPlayerPlay);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl player GetInterface failed with result %d", result);
        return false;
    }
    (void)result;

    // get the buffer queue interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_BUFFERQUEUE,
            &bqPlayerBufferQueue);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl playberBufferQueue GetInterface failed with result %d", result);
        return false;
    }
    (void)result;

    // register callback on the buffer queue
    result = (*bqPlayerBufferQueue)->RegisterCallback(bqPlayerBufferQueue, bqPlayerCallback, NULL);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl bqPlayerBufferQueue RegisterCallback failed with result %d", result);
        return false;
    }
    (void)result;

    // get the volume interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_VOLUME, &bqPlayerVolume);
    if (result != SL_RESULT_SUCCESS) {
        ALOGE("sl volume GetInterface failed with result %d", result);
        return false;
    }
    (void)result;

    // set the player's state to playing
    audioplay::setPlaying(true);
    CHATTY("Created buffer queue player: %p", bqPlayerBufferQueue);
    return true;
}

bool parseClipBuf(const uint8_t* clipBuf, int clipBufSize, const ChunkFormat** oChunkFormat,
                  const uint8_t** oSoundBuf, unsigned* oSoundBufSize) {
    *oSoundBuf = clipBuf;
    *oSoundBufSize = clipBufSize;
    *oChunkFormat = NULL;
    const RiffWaveHeader* wavHeader = (const RiffWaveHeader*)*oSoundBuf;
    if (*oSoundBufSize < sizeof(*wavHeader) || (wavHeader->riff_id != ID_RIFF) ||
        (wavHeader->wave_id != ID_WAVE)) {
        ALOGE("Error: audio file is not a riff/wave file\n");
        return false;
    }
    *oSoundBuf += sizeof(*wavHeader);
    *oSoundBufSize -= sizeof(*wavHeader);

    while (true) {
        const ChunkHeader* chunkHeader = (const ChunkHeader*)*oSoundBuf;
        if (*oSoundBufSize < sizeof(*chunkHeader)) {
            ALOGE("EOF reading chunk headers");
            return false;
        }

        *oSoundBuf += sizeof(*chunkHeader);
        *oSoundBufSize -= sizeof(*chunkHeader);

        bool endLoop = false;
        switch (chunkHeader->id) {
            case ID_FMT:
                *oChunkFormat = (const ChunkFormat*)*oSoundBuf;
                *oSoundBuf += chunkHeader->sz;
                *oSoundBufSize -= chunkHeader->sz;
                break;
            case ID_DATA:
                /* Stop looking for chunks */
                *oSoundBufSize = chunkHeader->sz;
                endLoop = true;
                break;
            default:
                /* Unknown chunk, skip bytes */
                *oSoundBuf += chunkHeader->sz;
                *oSoundBufSize -= chunkHeader->sz;
        }
        if (endLoop) {
            break;
        }
    }

    if (*oChunkFormat == NULL) {
        ALOGE("format not found in WAV file");
        return false;
    }
    return true;
}

} // namespace

bool create(const uint8_t* exampleClipBuf, int exampleClipBufSize) {
    if (!createEngine()) {
        return false;
    }

    // Parse the example clip.
    const ChunkFormat* chunkFormat;
    const uint8_t* soundBuf;
    unsigned soundBufSize;
    if (!parseClipBuf(exampleClipBuf, exampleClipBufSize, &chunkFormat, &soundBuf, &soundBufSize)) {
        return false;
    }

    // Initialize the BufferQueue based on this clip's format.
    if (!createBufferQueueAudioPlayer(chunkFormat)) {
        return false;
    }
    return true;
}

bool playClip(const uint8_t* buf, int size) {
    // Parse the WAV header
    const ChunkFormat* chunkFormat;
    if (!parseClipBuf(buf, size, &chunkFormat, &nextBuffer, &nextSize)) {
        return false;
    }

    if (!hasPlayer()) {
        ALOGD("cannot play clip %p without a player", buf);
        return false;
    }

    CHATTY("playClip on player %p: buf=%p size=%d nextSize %d",
           bqPlayerBufferQueue, buf, size, nextSize);

    if (nextSize > 0) {
        // here we only enqueue one buffer because it is a long clip,
        // but for streaming playback we would typically enqueue at least 2 buffers to start
        SLresult result;
        result = (*bqPlayerBufferQueue)->Enqueue(bqPlayerBufferQueue, nextBuffer, nextSize);
        if (SL_RESULT_SUCCESS != result) {
            return false;
        }
        audioplay::setPlaying(true);
    }

    return true;
}

// set the playing state for the buffer queue audio player
void setPlaying(bool isPlaying) {
    if (!hasPlayer()) return;

    SLresult result;

    if (NULL != bqPlayerPlay) {
        // set the player's state
        result = (*bqPlayerPlay)->SetPlayState(bqPlayerPlay,
            isPlaying ? SL_PLAYSTATE_PLAYING : SL_PLAYSTATE_STOPPED);
    }

}

void destroy() {
    // destroy buffer queue audio player object, and invalidate all associated interfaces
    if (bqPlayerObject != NULL) {
        CHATTY("destroying audio player");
        (*bqPlayerObject)->Destroy(bqPlayerObject);
        bqPlayerObject = NULL;
        bqPlayerPlay = NULL;
        bqPlayerBufferQueue = NULL;
        bqPlayerMuteSolo = NULL;
        bqPlayerVolume = NULL;
    }

    // destroy output mix object, and invalidate all associated interfaces
    if (outputMixObject != NULL) {
        (*outputMixObject)->Destroy(outputMixObject);
        outputMixObject = NULL;
    }

    // destroy engine object, and invalidate all associated interfaces
    if (engineObject != NULL) {
        CHATTY("destroying audio engine");
        (*engineObject)->Destroy(engineObject);
        engineObject = NULL;
        engineEngine = NULL;
    }
}

}  // namespace audioplay
