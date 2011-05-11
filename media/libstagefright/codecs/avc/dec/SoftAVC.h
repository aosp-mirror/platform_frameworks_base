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

#ifndef SOFT_AVC_H_

#define SOFT_AVC_H_

#include "SimpleSoftOMXComponent.h"

struct tagAVCHandle;

namespace android {

struct SoftAVC : public SimpleSoftOMXComponent {
    SoftAVC(const char *name,
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData,
            OMX_COMPONENTTYPE **component);

protected:
    virtual ~SoftAVC();

    virtual OMX_ERRORTYPE internalGetParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE internalSetParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual OMX_ERRORTYPE getConfig(OMX_INDEXTYPE index, OMX_PTR params);

    virtual void onQueueFilled(OMX_U32 portIndex);
    virtual void onPortFlushCompleted(OMX_U32 portIndex);
    virtual void onPortEnableCompleted(OMX_U32 portIndex, bool enabled);

private:
    enum {
        kNumInputBuffers  = 4,
        kNumOutputBuffers = 18,
    };

    enum EOSStatus {
        INPUT_DATA_AVAILABLE,
        INPUT_EOS_SEEN,
        OUTPUT_FRAMES_FLUSHED,
    };

    tagAVCHandle *mHandle;

    size_t mInputBufferCount;

    int32_t mWidth, mHeight;
    int32_t mCropLeft, mCropTop, mCropRight, mCropBottom;

    bool mSPSSeen, mPPSSeen;

    int64_t mCurrentTimeUs;

    EOSStatus mEOSStatus;

    enum {
        NONE,
        AWAITING_DISABLED,
        AWAITING_ENABLED
    } mOutputPortSettingsChange;

    void initPorts();
    status_t initDecoder();

    status_t decodeFragment(
            const uint8_t *fragPtr, size_t fragSize,
            bool *releaseFrames,
            OMX_BUFFERHEADERTYPE **outHeader);

    void updatePortDefinitions();
    bool drainOutputBuffer(OMX_BUFFERHEADERTYPE **outHeader);

    static int32_t ActivateSPSWrapper(
            void *userData, unsigned int sizeInMbs, unsigned int numBuffers);

    static int32_t BindFrameWrapper(
            void *userData, int32_t index, uint8_t **yuv);

    static void UnbindFrame(void *userData, int32_t index);

    int32_t activateSPS(
            unsigned int sizeInMbs, unsigned int numBuffers);

    int32_t bindFrame(int32_t index, uint8_t **yuv);

    DISALLOW_EVIL_CONSTRUCTORS(SoftAVC);
};

}  // namespace android

#endif  // SOFT_AVC_H_

