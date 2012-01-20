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
#define LOG_TAG "AMRWBEncoder"
#include <utils/Log.h>

#include "AMRWBEncoder.h"
#include "voAMRWB.h"
#include "cmnMemory.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

namespace android {

static const int32_t kNumSamplesPerFrame = 320;
static const int32_t kBitsPerSample = 16;
static const int32_t kInputBufferSize = (kBitsPerSample / 8) * kNumSamplesPerFrame;
static const int32_t kSampleRate = 16000;
static const int32_t kNumChannels = 1;

AMRWBEncoder::AMRWBEncoder(const sp<MediaSource> &source, const sp<MetaData> &meta)
    : mSource(source),
      mMeta(meta),
      mStarted(false),
      mBufferGroup(NULL),
      mInputBuffer(NULL),
      mEncoderHandle(NULL),
      mApiHandle(NULL),
      mMemOperator(NULL),
      mAnchorTimeUs(0),
      mNumFramesOutput(0),
      mNumInputSamples(0) {
}

static VOAMRWBMODE pickModeFromBitRate(int32_t bps) {
    CHECK(bps >= 0);
    if (bps <= 6600) {
        return VOAMRWB_MD66;
    } else if (bps <= 8850) {
        return VOAMRWB_MD885;
    } else if (bps <= 12650) {
        return VOAMRWB_MD1265;
    } else if (bps <= 14250) {
        return VOAMRWB_MD1425;
    } else if (bps <= 15850) {
        return VOAMRWB_MD1585;
    } else if (bps <= 18250) {
        return VOAMRWB_MD1825;
    } else if (bps <= 19850) {
        return VOAMRWB_MD1985;
    } else if (bps <= 23050) {
        return VOAMRWB_MD2305;
    }
    return VOAMRWB_MD2385;
}

status_t AMRWBEncoder::initCheck() {
    CHECK(mApiHandle == NULL && mEncoderHandle == NULL);
    CHECK(mMeta->findInt32(kKeyBitRate, &mBitRate));

    mApiHandle = new VO_AUDIO_CODECAPI;
    CHECK(mApiHandle);

    if (VO_ERR_NONE != voGetAMRWBEncAPI(mApiHandle)) {
        ALOGE("Failed to get api handle");
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
    if (VO_ERR_NONE != mApiHandle->Init(&mEncoderHandle, VO_AUDIO_CodingAMRWB, &userData)) {
        ALOGE("Failed to init AMRWB encoder");
        return UNKNOWN_ERROR;
    }

    // Configure AMRWB encoder$
    VOAMRWBMODE mode = pickModeFromBitRate(mBitRate);
    if (VO_ERR_NONE != mApiHandle->SetParam(mEncoderHandle, VO_PID_AMRWB_MODE,  &mode)) {
        ALOGE("Failed to set AMRWB encoder mode to %d", mode);
        return UNKNOWN_ERROR;
    }

    VOAMRWBFRAMETYPE type = VOAMRWB_RFC3267;
    if (VO_ERR_NONE != mApiHandle->SetParam(mEncoderHandle, VO_PID_AMRWB_FRAMETYPE, &type)) {
        ALOGE("Failed to set AMRWB encoder frame type to %d", type);
        return UNKNOWN_ERROR;
    }

    return OK;
}

AMRWBEncoder::~AMRWBEncoder() {
    if (mStarted) {
        stop();
    }
}

status_t AMRWBEncoder::start(MetaData *params) {
    if (mStarted) {
        ALOGW("Call start() when encoder already started");
        return OK;
    }

    mBufferGroup = new MediaBufferGroup;

    // The largest buffer size is header + 477 bits
    mBufferGroup->add_buffer(new MediaBuffer(1024));

    CHECK_EQ(OK, initCheck());

    mNumFramesOutput = 0;

    status_t err = mSource->start(params);
    if (err != OK) {
        ALOGE("AudioSource is not available");
        return err;
    }
    mStarted = true;

    return OK;
}

status_t AMRWBEncoder::stop() {
    if (!mStarted) {
        ALOGW("Call stop() when encoder has not started");
        return OK;
    }

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    delete mBufferGroup;
    mBufferGroup = NULL;


    CHECK_EQ(VO_ERR_NONE, mApiHandle->Uninit(mEncoderHandle));
    mEncoderHandle = NULL;

    delete mApiHandle;
    mApiHandle = NULL;

    delete mMemOperator;
    mMemOperator;

    mStarted = false;

    mSource->stop();
    return OK;
}

sp<MetaData> AMRWBEncoder::getFormat() {
    sp<MetaData> srcFormat = mSource->getFormat();

    mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AMR_WB);

    int64_t durationUs;
    if (srcFormat->findInt64(kKeyDuration, &durationUs)) {
        mMeta->setInt64(kKeyDuration, durationUs);
    }

    mMeta->setCString(kKeyDecoderComponent, "AMRWBEncoder");

    return mMeta;
}

status_t AMRWBEncoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    status_t err;

    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    CHECK(options == NULL || !options->getSeekTo(&seekTimeUs, &mode));
    bool readFromSource = false;
    int64_t wallClockTimeUs = -1;

    while (mNumInputSamples < kNumSamplesPerFrame) {
        if (mInputBuffer == NULL) {
            err = mSource->read(&mInputBuffer, options);

            if (err != OK) {
                if (mNumInputSamples == 0) {
                    return ERROR_END_OF_STREAM;
                }
                memset(&mInputFrame[mNumInputSamples],
                       0,
                       sizeof(int16_t)
                            * (kNumSamplesPerFrame - mNumInputSamples));
                mNumInputSamples = 0;
                break;
            }

            size_t align = mInputBuffer->range_length() % sizeof(int16_t);
            CHECK_EQ(align, 0);

            int64_t timeUs;
            if (mInputBuffer->meta_data()->findInt64(kKeyDriftTime, &timeUs)) {
                wallClockTimeUs = timeUs;
            }
            if (mInputBuffer->meta_data()->findInt64(kKeyAnchorTime, &timeUs)) {
                mAnchorTimeUs = timeUs;
            }
            readFromSource = true;
        } else {
            readFromSource = false;
        }

        size_t copy =
            (kNumSamplesPerFrame - mNumInputSamples) * sizeof(int16_t);

        if (copy > mInputBuffer->range_length()) {
            copy = mInputBuffer->range_length();
        }

        memcpy(&mInputFrame[mNumInputSamples],
               (const uint8_t *)mInputBuffer->data()
                    + mInputBuffer->range_offset(),
               copy);

        mInputBuffer->set_range(
                mInputBuffer->range_offset() + copy,
                mInputBuffer->range_length() - copy);

        if (mInputBuffer->range_length() == 0) {
            mInputBuffer->release();
            mInputBuffer = NULL;
        }

        mNumInputSamples += copy / sizeof(int16_t);
        if (mNumInputSamples >= kNumSamplesPerFrame) {
            mNumInputSamples %= kNumSamplesPerFrame;
            break;  // Get a whole input frame 640 bytes
        }
    }

    VO_CODECBUFFER inputData;
    memset(&inputData, 0, sizeof(inputData));
    inputData.Buffer = (unsigned char*) mInputFrame;
    inputData.Length = kInputBufferSize;
    CHECK(VO_ERR_NONE == mApiHandle->SetInputData(mEncoderHandle,&inputData));

    MediaBuffer *buffer;
    CHECK_EQ(mBufferGroup->acquire_buffer(&buffer), OK);
    uint8_t *outPtr = (uint8_t *)buffer->data();

    VO_CODECBUFFER outputData;
    memset(&outputData, 0, sizeof(outputData));
    VO_AUDIO_OUTPUTINFO outputInfo;
    memset(&outputInfo, 0, sizeof(outputInfo));

    VO_U32 ret = VO_ERR_NONE;
    outputData.Buffer = outPtr;
    outputData.Length = buffer->size();
    ret = mApiHandle->GetOutputData(mEncoderHandle, &outputData, &outputInfo);
    CHECK(ret == VO_ERR_NONE || ret == VO_ERR_INPUT_BUFFER_SMALL);

    buffer->set_range(0, outputData.Length);
    ++mNumFramesOutput;

    int64_t mediaTimeUs = mNumFramesOutput * 20000LL;
    buffer->meta_data()->setInt64(kKeyTime, mAnchorTimeUs + mediaTimeUs);
    if (readFromSource && wallClockTimeUs != -1) {
        buffer->meta_data()->setInt64(kKeyDriftTime, mediaTimeUs - wallClockTimeUs);
    }

    *out = buffer;
    return OK;
}

}  // namespace android
