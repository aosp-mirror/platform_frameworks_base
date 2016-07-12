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

#include <assert.h>
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
    assert(bq == bqPlayerBufferQueue);
    assert(NULL == context);
    audioplay::setPlaying(false);
}

bool hasPlayer() {
    return (engineObject != NULL && bqPlayerObject != NULL);
}

// create the engine and output mix objects
void createEngine() {
    SLresult result;

    // create engine
    result = slCreateEngine(&engineObject, 0, NULL, 0, NULL, NULL);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the engine interface, which is needed in order to create other objects
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // create output mix, with environmental reverb specified as a non-required interface
    const SLInterfaceID ids[1] = {SL_IID_ENVIRONMENTALREVERB};
    const SLboolean req[1] = {SL_BOOLEAN_FALSE};
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 1, ids, req);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the output mix
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;
}

// create buffer queue audio player
void createBufferQueueAudioPlayer(const ChunkFormat* chunkFormat) {
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
    const SLInterfaceID ids[2] = {SL_IID_BUFFERQUEUE, SL_IID_VOLUME};
    const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};
    result = (*engineEngine)->CreateAudioPlayer(engineEngine, &bqPlayerObject, &audioSrc, &audioSnk,
            2, ids, req);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // realize the player
    result = (*bqPlayerObject)->Realize(bqPlayerObject, SL_BOOLEAN_FALSE);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the play interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAY, &bqPlayerPlay);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // get the buffer queue interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_BUFFERQUEUE,
            &bqPlayerBufferQueue);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // register callback on the buffer queue
    result = (*bqPlayerBufferQueue)->RegisterCallback(bqPlayerBufferQueue, bqPlayerCallback, NULL);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

#if 0   // mute/solo is not supported for sources that are known to be mono, as this is
    // get the mute/solo interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_MUTESOLO, &bqPlayerMuteSolo);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;
#endif

    // get the volume interface
    result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_VOLUME, &bqPlayerVolume);
    assert(SL_RESULT_SUCCESS == result);
    (void)result;

    // set the player's state to playing
    audioplay::setPlaying(true);
    CHATTY("Created buffer queue player: %p", bqPlayerBufferQueue);
}

} // namespace

void create() {
    createEngine();
}

bool playClip(const uint8_t* buf, int size) {
    // Parse the WAV header
    nextBuffer = buf;
    nextSize = size;
    const RiffWaveHeader* wavHeader = (const RiffWaveHeader*)buf;
    if (nextSize < sizeof(*wavHeader) || (wavHeader->riff_id != ID_RIFF) ||
        (wavHeader->wave_id != ID_WAVE)) {
        ALOGE("Error: audio file is not a riff/wave file\n");
        return false;
    }
    nextBuffer += sizeof(*wavHeader);
    nextSize -= sizeof(*wavHeader);

    const ChunkFormat* chunkFormat = nullptr;
    while (true) {
        const ChunkHeader* chunkHeader = (const ChunkHeader*)nextBuffer;
        if (nextSize < sizeof(*chunkHeader)) {
            ALOGE("EOF reading chunk headers");
            return false;
        }

        nextBuffer += sizeof(*chunkHeader);
        nextSize -= sizeof(*chunkHeader);

        bool endLoop = false;
        switch (chunkHeader->id) {
            case ID_FMT:
                chunkFormat = (const ChunkFormat*)nextBuffer;
                nextBuffer += chunkHeader->sz;
                nextSize -= chunkHeader->sz;
                break;
            case ID_DATA:
                /* Stop looking for chunks */
                endLoop = true;
                break;
            default:
                /* Unknown chunk, skip bytes */
                nextBuffer += chunkHeader->sz;
                nextSize -= chunkHeader->sz;
        }
        if (endLoop) {
            break;
        }
    }

    if (!chunkFormat) {
        ALOGE("format not found in WAV file");
        return false;
    }

    // If this is the first clip, create the buffer based on this WAV's header.
    // We assume all future clips with be in the same format.
    if (bqPlayerBufferQueue == nullptr) {
        createBufferQueueAudioPlayer(chunkFormat);
    }

    assert(bqPlayerBufferQueue != nullptr);
    assert(buf != nullptr);

    if (!hasPlayer()) {
        ALOGD("cannot play clip %p without a player", buf);
        return false;
    }

    CHATTY("playClip on player %p: buf=%p size=%d", bqPlayerBufferQueue, buf, size);

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
        assert(SL_RESULT_SUCCESS == result);
        (void)result;
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
