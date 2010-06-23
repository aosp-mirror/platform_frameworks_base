/*
 * Copyright (C) 2010 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "AACEncoder"
#include <utils/Log.h>

#include "AACEncoder.h"
#include "voAAC.h"
#include "cmnMemory.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

namespace android {

AACEncoder::AACEncoder(const sp<MediaSource> &source, const sp<MetaData> &meta)
    : mSource(source),
      mMeta(meta),
      mStarted(false),
      mBufferGroup(NULL),
      mInputBuffer(NULL),
      mEncoderHandle(NULL),
      mApiHandle(NULL),
      mMemOperator(NULL) {
}

status_t AACEncoder::initCheck() {
    CHECK(mApiHandle == NULL && mEncoderHandle == NULL);
    CHECK(mMeta->findInt32(kKeySampleRate, &mSampleRate));
    CHECK(mMeta->findInt32(kKeyChannelCount, &mChannels));
    CHECK(mMeta->findInt32(kKeyBitRate, &mBitRate));

    mApiHandle = new VO_AUDIO_CODECAPI;
    CHECK(mApiHandle);

    if (VO_ERR_NONE != voGetAACEncAPI(mApiHandle)) {
        LOGE("Failed to get api handle");
        return UNKNOWN_ERROR;
    }

    mMemOperator = new VO_MEM_OPERATOR;
    CHECK(mMemOperator != NULL);
    mMemOperator->Alloc = cmnMemAlloc;
    mMemOperator->Copy = cmnMemCopy;
    mMemOperator->Free = cmnMemFree;
    mMemOperator->Set = cmnMemSet;
    mMemOperator->Check = cmnMemCheck;

    VO_CODEC_INIT_USERDATA userData;
    memset(&userData, 0, sizeof(userData));
    userData.memflag = VO_IMF_USERMEMOPERATOR;
    userData.memData = (VO_PTR) mMemOperator;
    if (VO_ERR_NONE != mApiHandle->Init(&mEncoderHandle, VO_AUDIO_CodingAAC, &userData)) {
        LOGE("Failed to init AAC encoder");
        return UNKNOWN_ERROR;
    }
    if (OK != setAudioSpecificConfigData()) {
        LOGE("Failed to configure AAC encoder");
        return UNKNOWN_ERROR;
    }

    // Configure AAC encoder$
    AACENC_PARAM params;
    memset(&params, 0, sizeof(params));
    params.sampleRate = mSampleRate;
    params.bitRate = mBitRate;
    params.nChannels = mChannels;
    params.adtsUsed = 0;  // For MP4 file, don't use adts format$
    if (VO_ERR_NONE != mApiHandle->SetParam(mEncoderHandle, VO_PID_AAC_ENCPARAM,  &params)) {
        LOGE("Failed to set AAC encoder parameters");
        return UNKNOWN_ERROR;
    }

    return OK;
}

static status_t getSampleRateTableIndex(int32_t sampleRate, int32_t &index) {
    static const int32_t kSampleRateTable[] = {
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000
    };
    const int32_t tableSize = sizeof(kSampleRateTable) / sizeof(kSampleRateTable[0]);
    for (int32_t i = 0; i < tableSize; ++i) {
        if (sampleRate == kSampleRateTable[i]) {
            index = i;
            return OK;
        }
    }

    LOGE("Sampling rate %d bps is not supported", sampleRate);
    return UNKNOWN_ERROR;
}

status_t AACEncoder::setAudioSpecificConfigData() {
    LOGV("setAudioSpecificConfigData: %d hz, %d bps, and %d channels",
         mSampleRate, mBitRate, mChannels);

    int32_t index;
    CHECK_EQ(OK, getSampleRateTableIndex(mSampleRate, index));
    if (mChannels > 2 || mChannels <= 0) {
        LOGE("Unsupported number of channels(%d)", mChannels);
        return UNKNOWN_ERROR;
    }

    // OMX_AUDIO_AACObjectLC
    mAudioSpecificConfigData[0] = ((0x02 << 3) | (index >> 1));
    mAudioSpecificConfigData[1] = ((index & 0x01) << 7) | (mChannels << 3);
    return OK;
}

AACEncoder::~AACEncoder() {
    if (mStarted) {
        stop();
    }
}

status_t AACEncoder::start(MetaData *params) {
    CHECK(!mStarted);

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(new MediaBuffer(2048));

    CHECK_EQ(OK, initCheck());

    mFrameCount = 0;
    mSource->start(params);

    mStarted = true;

    return OK;
}

status_t AACEncoder::stop() {
    CHECK(mStarted);

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    delete mBufferGroup;
    mBufferGroup = NULL;

    mSource->stop();

    if (mEncoderHandle) {
        CHECK_EQ(VO_ERR_NONE, mApiHandle->Uninit(mEncoderHandle));
        mEncoderHandle = NULL;
    }
    delete mApiHandle;
    mApiHandle = NULL;

    mStarted = false;

    return OK;
}

sp<MetaData> AACEncoder::getFormat() {
    sp<MetaData> srcFormat = mSource->getFormat();

    mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC);

    int64_t durationUs;
    if (srcFormat->findInt64(kKeyDuration, &durationUs)) {
        mMeta->setInt64(kKeyDuration, durationUs);
    }

    mMeta->setCString(kKeyDecoderComponent, "AACEncoder");

    return mMeta;
}

status_t AACEncoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    status_t err;

    *out = NULL;

    int64_t seekTimeUs;
    CHECK(options == NULL || !options->getSeekTo(&seekTimeUs));

    MediaBuffer *buffer;
    CHECK_EQ(mBufferGroup->acquire_buffer(&buffer), OK);
    uint8_t *outPtr = (uint8_t *)buffer->data();

    if (mFrameCount == 0) {
        memcpy(outPtr, mAudioSpecificConfigData, 2);
        buffer->set_range(0, 2);
        buffer->meta_data()->setInt32(kKeyIsCodecConfig, true);
        *out = buffer;
        mInputBuffer = NULL;
        ++mFrameCount;
        return OK;
    } else if (mFrameCount == 1) {
        buffer->meta_data()->setInt32(kKeyIsCodecConfig, false);
    }

    // XXX: We assume that the input buffer contains at least
    // (actually, exactly) 1024 PCM samples. This needs to be fixed.
    if (mInputBuffer == NULL) {
        if (mSource->read(&mInputBuffer, options) != OK) {
            LOGE("failed to read from input audio source");
            return UNKNOWN_ERROR;
        }
        if (mInputBuffer->range_length() == 0) {
            mInputBuffer->release();
            mInputBuffer = NULL;
            return ERROR_END_OF_STREAM;
        }
        VO_CODECBUFFER inputData;
        memset(&inputData, 0, sizeof(inputData));
        inputData.Buffer = (unsigned char*) mInputBuffer->data();
        inputData.Length = mInputBuffer->range_length();
        CHECK(VO_ERR_NONE == mApiHandle->SetInputData(mEncoderHandle,&inputData));
    }

    CHECK(mInputBuffer != NULL);

    VO_CODECBUFFER outputData;
    memset(&outputData, 0, sizeof(outputData));
    VO_AUDIO_OUTPUTINFO outputInfo;
    memset(&outputInfo, 0, sizeof(outputInfo));

    VO_U32 ret = VO_ERR_NONE;
    int32_t outputLength = 0;
    outputData.Buffer = outPtr;
    outputData.Length = buffer->size();
    ret = mApiHandle->GetOutputData(mEncoderHandle, &outputData, &outputInfo);
    if (ret == VO_ERR_NONE || ret == VO_ERR_INPUT_BUFFER_SMALL) {
        outputLength += outputData.Length;
        if (ret == VO_ERR_INPUT_BUFFER_SMALL) {  // All done
            mInputBuffer->release();
            mInputBuffer = NULL;
        }
    } else {
        LOGE("failed to encode the input data 0x%lx", ret);
    }

    buffer->set_range(0, outputLength);

    // Each output frame compresses 1024 input PCM samples.
    int64_t timestampUs = ((mFrameCount - 1) * 1000000LL * 1024) / mSampleRate;
    ++mFrameCount;
    buffer->meta_data()->setInt64(kKeyTime, timestampUs);

    *out = buffer;
    return OK;
}

}  // namespace android
