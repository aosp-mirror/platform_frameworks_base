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

#ifndef SOFT_MP3_H_

#define SOFT_MP3_H_

#include "SimpleSoftOMXComponent.h"

struct tPVMP3DecoderExternal;

namespace android {

struct SoftMP3 : public SimpleSoftOMXComponent {
    SoftMP3(const char *name,
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData,
            OMX_COMPONENTTYPE **component);

protected:
    virtual ~SoftMP3();

    virtual OMX_ERRORTYPE internalGetParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE internalSetParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual void onQueueFilled(OMX_U32 portIndex);
    virtual void onPortFlushCompleted(OMX_U32 portIndex);
    virtual void onPortEnableCompleted(OMX_U32 portIndex, bool enabled);

private:
    enum {
        kNumBuffers = 4,
        kOutputBufferSize = 4608 * 2,
        kPVMP3DecoderDelay = 529 // frames
    };

    tPVMP3DecoderExternal *mConfig;
    void *mDecoderBuf;
    int64_t mAnchorTimeUs;
    int64_t mNumFramesOutput;

    int32_t mNumChannels;
    int32_t mSamplingRate;

    bool mIsFirst;
    bool mSignalledError;

    enum {
        NONE,
        AWAITING_DISABLED,
        AWAITING_ENABLED
    } mOutputPortSettingsChange;

    void initPorts();
    void initDecoder();

    DISALLOW_EVIL_CONSTRUCTORS(SoftMP3);
};

}  // namespace android

#endif  // SOFT_MP3_H_


