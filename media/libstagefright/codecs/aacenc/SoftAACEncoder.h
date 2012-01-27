/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef SOFT_AAC_ENCODER_H_

#define SOFT_AAC_ENCODER_H_

#include "SimpleSoftOMXComponent.h"

struct VO_AUDIO_CODECAPI;
struct VO_MEM_OPERATOR;

namespace android {

struct SoftAACEncoder : public SimpleSoftOMXComponent {
    SoftAACEncoder(
            const char *name,
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData,
            OMX_COMPONENTTYPE **component);

protected:
    virtual ~SoftAACEncoder();

    virtual OMX_ERRORTYPE internalGetParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE internalSetParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual void onQueueFilled(OMX_U32 portIndex);

private:
    enum {
        kNumBuffers             = 4,
        kNumSamplesPerFrame     = 1024,
    };

    void *mEncoderHandle;
    VO_AUDIO_CODECAPI *mApiHandle;
    VO_MEM_OPERATOR  *mMemOperator;

    OMX_U32 mNumChannels;
    OMX_U32 mSampleRate;
    OMX_U32 mBitRate;

    bool mSentCodecSpecificData;
    size_t mInputSize;
    int16_t *mInputFrame;
    int64_t mInputTimeUs;

    bool mSawInputEOS;

    uint8_t mAudioSpecificConfigData[2];

    bool mSignalledError;

    void initPorts();
    status_t initEncoder();

    status_t setAudioSpecificConfigData();
    status_t setAudioParams();

    DISALLOW_EVIL_CONSTRUCTORS(SoftAACEncoder);
};

}  // namespace android

#endif  // SOFT_AAC_ENCODER_H_
