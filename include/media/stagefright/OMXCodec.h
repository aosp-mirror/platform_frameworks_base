/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef OMX_CODEC_H_

#define OMX_CODEC_H_

#include <media/IOMX.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <utils/threads.h>

namespace android {

class MemoryDealer;
struct OMXCodecObserver;

struct OMXCodec : public MediaSource,
                  public MediaBufferObserver {
    static sp<OMXCodec> Create(
            const sp<IOMX> &omx,
            const sp<MetaData> &meta, bool createEncoder,
            const sp<MediaSource> &source);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    void on_message(const omx_message &msg);

    // from MediaBufferObserver
    virtual void signalBufferReturned(MediaBuffer *buffer);

protected:
    virtual ~OMXCodec();

private:
    enum State {
        DEAD,
        LOADED,
        LOADED_TO_IDLE,
        IDLE_TO_EXECUTING,
        EXECUTING,
        EXECUTING_TO_IDLE,
        IDLE_TO_LOADED,
        RECONFIGURING,
        ERROR
    };

    enum {
        kPortIndexInput  = 0,
        kPortIndexOutput = 1
    };

    enum PortStatus {
        ENABLED,
        DISABLING,
        DISABLED,
        ENABLING,
        SHUTTING_DOWN,
    };

    enum Quirks {
        kNeedsFlushBeforeDisable             = 1,
        kWantsNALFragments                   = 2,
        kRequiresLoadedToIdleAfterAllocation = 4,
        kRequiresAllocateBufferOnInputPorts  = 8,
    };

    struct BufferInfo {
        IOMX::buffer_id mBuffer;
        bool mOwnedByComponent;
        sp<IMemory> mMem;
        MediaBuffer *mMediaBuffer;
    };

    struct CodecSpecificData {
        size_t mSize;
        uint8_t mData[1];
    };

    sp<IOMX> mOMX;
    IOMX::node_id mNode;
    sp<OMXCodecObserver> mObserver;
    uint32_t mQuirks;
    bool mIsEncoder;
    char *mMIME;
    char *mComponentName;
    sp<MetaData> mOutputFormat;
    sp<MediaSource> mSource;
    Vector<CodecSpecificData *> mCodecSpecificData;
    size_t mCodecSpecificDataIndex;

    sp<MemoryDealer> mDealer;

    State mState;
    Vector<BufferInfo> mPortBuffers[2];
    PortStatus mPortStatus[2];
    bool mSignalledEOS;
    bool mNoMoreOutputData;
    int64_t mSeekTimeUs;

    Mutex mLock;
    Condition mAsyncCompletion;

    // A list of indices into mPortStatus[kPortIndexOutput] filled with data.
    List<size_t> mFilledBuffers;
    Condition mBufferFilled;

    OMXCodec(const sp<IOMX> &omx, IOMX::node_id node, uint32_t quirks,
             bool isEncoder, const char *mime, const char *componentName,
             const sp<MediaSource> &source);

    void addCodecSpecificData(const void *data, size_t size);
    void clearCodecSpecificData();

    void setAMRFormat();
    void setAACFormat();

    status_t setVideoPortFormatType(
            OMX_U32 portIndex,
            OMX_VIDEO_CODINGTYPE compressionFormat,
            OMX_COLOR_FORMATTYPE colorFormat);

    void setVideoInputFormat(
            const char *mime, OMX_U32 width, OMX_U32 height);

    void setVideoOutputFormat(
            const char *mime, OMX_U32 width, OMX_U32 height);

    void setImageOutputFormat(
            OMX_COLOR_FORMATTYPE format, OMX_U32 width, OMX_U32 height);

    status_t allocateBuffers();
    status_t allocateBuffersOnPort(OMX_U32 portIndex);

    status_t freeBuffersOnPort(
            OMX_U32 portIndex, bool onlyThoseWeOwn = false);

    void drainInputBuffer(IOMX::buffer_id buffer);
    void fillOutputBuffer(IOMX::buffer_id buffer);
    void drainInputBuffer(BufferInfo *info);
    void fillOutputBuffer(BufferInfo *info);

    void drainInputBuffers();
    void fillOutputBuffers();

    void flushPortAsync(OMX_U32 portIndex);
    void disablePortAsync(OMX_U32 portIndex);
    void enablePortAsync(OMX_U32 portIndex);

    static size_t countBuffersWeOwn(const Vector<BufferInfo> &buffers);
    static bool isIntermediateState(State state);

    void onEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);
    void onCmdComplete(OMX_COMMANDTYPE cmd, OMX_U32 data);
    void onStateChange(OMX_STATETYPE newState);
    void onPortSettingsChanged(OMX_U32 portIndex);

    void setState(State newState);

    status_t init();
    void initOutputFormat(const sp<MetaData> &inputFormat);

    void dumpPortStatus(OMX_U32 portIndex);

    OMXCodec(const OMXCodec &);
    OMXCodec &operator=(const OMXCodec &);
};

}  // namespace android

#endif  // OMX_CODEC_H_
