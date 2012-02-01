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

#ifndef SOFT_AMRNB_ENCODER_H_

#define SOFT_AMRNB_ENCODER_H_

#include "SimpleSoftOMXComponent.h"

namespace android {

struct SoftAMRNBEncoder : public SimpleSoftOMXComponent {
    SoftAMRNBEncoder(
            const char *name,
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData,
            OMX_COMPONENTTYPE **component);

protected:
    virtual ~SoftAMRNBEncoder();

    virtual OMX_ERRORTYPE internalGetParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE internalSetParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual void onQueueFilled(OMX_U32 portIndex);

private:
    enum {
        kNumBuffers             = 4,
        kNumSamplesPerFrame     = 160,
    };

    void *mEncState;
    void *mSidState;

    OMX_U32 mBitRate;
    int mMode;

    size_t mInputSize;
    int16_t mInputFrame[kNumSamplesPerFrame];
    int64_t mInputTimeUs;

    bool mSawInputEOS;
    bool mSignalledError;

    void initPorts();
    status_t initEncoder();

    status_t setAudioParams();

    DISALLOW_EVIL_CONSTRUCTORS(SoftAMRNBEncoder);
};

}  // namespace android

#endif  // SOFT_AMRNB_ENCODER_H_
