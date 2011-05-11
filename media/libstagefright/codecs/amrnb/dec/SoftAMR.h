/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef SOFT_AMR_H_

#define SOFT_AMR_H_

#include "SimpleSoftOMXComponent.h"

namespace android {

struct SoftAMR : public SimpleSoftOMXComponent {
    SoftAMR(const char *name,
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData,
            OMX_COMPONENTTYPE **component);

protected:
    virtual ~SoftAMR();

    virtual OMX_ERRORTYPE internalGetParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE internalSetParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual void onQueueFilled(OMX_U32 portIndex);
    virtual void onPortFlushCompleted(OMX_U32 portIndex);
    virtual void onPortEnableCompleted(OMX_U32 portIndex, bool enabled);

private:
    enum {
        kNumBuffers             = 4,
        kSampleRateNB           = 8000,
        kSampleRateWB           = 16000,
        kNumSamplesPerFrameNB   = 160,
        kNumSamplesPerFrameWB   = 320,
    };

    enum {
        MODE_NARROW,
        MODE_WIDE

    } mMode;

    void *mState;
    void *mDecoderBuf;
    int16_t *mDecoderCookie;

    size_t mInputBufferCount;
    int64_t mAnchorTimeUs;
    int64_t mNumSamplesOutput;

    bool mSignalledError;

    enum {
        NONE,
        AWAITING_DISABLED,
        AWAITING_ENABLED
    } mOutputPortSettingsChange;

    int16_t mInputSampleBuffer[477];

    void initPorts();
    status_t initDecoder();
    bool isConfigured() const;

    DISALLOW_EVIL_CONSTRUCTORS(SoftAMR);
};

}  // namespace android

#endif  // SOFT_AMR_H_

