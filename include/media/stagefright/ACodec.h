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

#ifndef A_CODEC_H_

#define A_CODEC_H_

#include <stdint.h>
#include <android/native_window.h>
#include <media/IOMX.h>
#include <media/stagefright/foundation/AHierarchicalStateMachine.h>

namespace android {

struct ABuffer;
struct MemoryDealer;

struct ACodec : public AHierarchicalStateMachine {
    enum {
        kWhatFillThisBuffer      = 'fill',
        kWhatDrainThisBuffer     = 'drai',
        kWhatEOS                 = 'eos ',
        kWhatShutdownCompleted   = 'scom',
        kWhatFlushCompleted      = 'fcom',
        kWhatOutputFormatChanged = 'outC',
        kWhatError               = 'erro',
    };

    ACodec();

    void setNotificationMessage(const sp<AMessage> &msg);
    void initiateSetup(const sp<AMessage> &msg);
    void signalFlush();
    void signalResume();
    void initiateShutdown();

protected:
    virtual ~ACodec();

private:
    struct BaseState;
    struct UninitializedState;
    struct LoadedToIdleState;
    struct IdleToExecutingState;
    struct ExecutingState;
    struct OutputPortSettingsChangedState;
    struct ExecutingToIdleState;
    struct IdleToLoadedState;
    struct FlushingState;

    enum {
        kWhatSetup                   = 'setu',
        kWhatOMXMessage              = 'omx ',
        kWhatInputBufferFilled       = 'inpF',
        kWhatOutputBufferDrained     = 'outD',
        kWhatShutdown                = 'shut',
        kWhatFlush                   = 'flus',
        kWhatResume                  = 'resm',
        kWhatDrainDeferredMessages   = 'drai',
    };

    enum {
        kPortIndexInput  = 0,
        kPortIndexOutput = 1
    };

    struct BufferInfo {
        enum Status {
            OWNED_BY_US,
            OWNED_BY_COMPONENT,
            OWNED_BY_UPSTREAM,
            OWNED_BY_DOWNSTREAM,
            OWNED_BY_NATIVE_WINDOW,
        };

        IOMX::buffer_id mBufferID;
        Status mStatus;

        sp<ABuffer> mData;
        sp<GraphicBuffer> mGraphicBuffer;
    };

    sp<AMessage> mNotify;

    sp<UninitializedState> mUninitializedState;
    sp<LoadedToIdleState> mLoadedToIdleState;
    sp<IdleToExecutingState> mIdleToExecutingState;
    sp<ExecutingState> mExecutingState;
    sp<OutputPortSettingsChangedState> mOutputPortSettingsChangedState;
    sp<ExecutingToIdleState> mExecutingToIdleState;
    sp<IdleToLoadedState> mIdleToLoadedState;
    sp<FlushingState> mFlushingState;

    AString mComponentName;
    sp<IOMX> mOMX;
    IOMX::node_id mNode;
    sp<MemoryDealer> mDealer[2];

    sp<ANativeWindow> mNativeWindow;

    Vector<BufferInfo> mBuffers[2];
    bool mPortEOS[2];

    List<sp<AMessage> > mDeferredQueue;

    bool mSentFormat;

    status_t allocateBuffersOnPort(OMX_U32 portIndex);
    status_t freeBuffersOnPort(OMX_U32 portIndex);
    status_t freeBuffer(OMX_U32 portIndex, size_t i);

    status_t allocateOutputBuffersFromNativeWindow();
    status_t cancelBufferToNativeWindow(BufferInfo *info);
    status_t freeOutputBuffersNotOwnedByComponent();
    BufferInfo *dequeueBufferFromNativeWindow();

    BufferInfo *findBufferByID(
            uint32_t portIndex, IOMX::buffer_id bufferID,
            ssize_t *index = NULL);

    void setComponentRole(bool isEncoder, const char *mime);
    void configureCodec(const char *mime, const sp<AMessage> &msg);

    status_t setVideoPortFormatType(
            OMX_U32 portIndex,
            OMX_VIDEO_CODINGTYPE compressionFormat,
            OMX_COLOR_FORMATTYPE colorFormat);

    status_t setSupportedOutputFormat();

    status_t setupVideoDecoder(
            const char *mime, int32_t width, int32_t height);

    status_t setVideoFormatOnPort(
            OMX_U32 portIndex,
            int32_t width, int32_t height,
            OMX_VIDEO_CODINGTYPE compressionFormat);

    status_t setupAACDecoder(int32_t numChannels, int32_t sampleRate);
    status_t setMinBufferSize(OMX_U32 portIndex, size_t size);

    status_t initNativeWindow();

    // Returns true iff all buffers on the given port have status OWNED_BY_US.
    bool allYourBuffersAreBelongToUs(OMX_U32 portIndex);

    bool allYourBuffersAreBelongToUs();

    void deferMessage(const sp<AMessage> &msg);
    void processDeferredMessages();

    void sendFormatChange();

    void signalError(OMX_ERRORTYPE error = OMX_ErrorUndefined);

    DISALLOW_EVIL_CONSTRUCTORS(ACodec);
};

}  // namespace android

#endif  // A_CODEC_H_
