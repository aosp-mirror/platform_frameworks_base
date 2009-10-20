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
            const sp<MediaSource> &source,
            const char *matchComponentName = NULL);

    static void setComponentRole(
            const sp<IOMX> &omx, IOMX::node_id node, bool isEncoder,
            const char *mime);

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
        kRequiresFlushCompleteEmulation      = 16,
        kRequiresAllocateBufferOnOutputPorts = 32,
        kRequiresFlushBeforeShutdown         = 64,
        kOutputDimensionsAre16Aligned        = 128,
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

    sp<MemoryDealer> mDealer[2];

    State mState;
    Vector<BufferInfo> mPortBuffers[2];
    PortStatus mPortStatus[2];
    bool mInitialBufferSubmit;
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

    void setComponentRole();

    void setAMRFormat();
    void setAMRWBFormat();
    void setAACFormat(int32_t numChannels, int32_t sampleRate);

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

    void setJPEGInputFormat(
            OMX_U32 width, OMX_U32 height, OMX_U32 compressedSize);

    void setMinBufferSize(OMX_U32 portIndex, OMX_U32 size);

    void setRawAudioFormat(
            OMX_U32 portIndex, int32_t sampleRate, int32_t numChannels);

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

    // Returns true iff a flush was initiated and a completion event is
    // upcoming, false otherwise (A flush was not necessary as we own all
    // the buffers on that port).
    // This method will ONLY ever return false for a component with quirk
    // "kRequiresFlushCompleteEmulation".
    bool flushPortAsync(OMX_U32 portIndex);

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

struct CodecProfileLevel {
    OMX_U32 mProfile;
    OMX_U32 mLevel;
};

struct CodecCapabilities {
    String8 mComponentName;
    Vector<CodecProfileLevel> mProfileLevels;
};

// Return a vector of componentNames with supported profile/level pairs
// supporting the given mime type, if queryDecoders==true, returns components
// that decode content of the given type, otherwise returns components
// that encode content of the given type.
// profile and level indications only make sense for h.263, mpeg4 and avc
// video.
// The profile/level values correspond to
// OMX_VIDEO_H263PROFILETYPE, OMX_VIDEO_MPEG4PROFILETYPE,
// OMX_VIDEO_AVCPROFILETYPE, OMX_VIDEO_H263LEVELTYPE, OMX_VIDEO_MPEG4LEVELTYPE
// and OMX_VIDEO_AVCLEVELTYPE respectively.

status_t QueryCodecs(
        const sp<IOMX> &omx,
        const char *mimeType, bool queryDecoders,
        Vector<CodecCapabilities> *results);

}  // namespace android

#endif  // OMX_CODEC_H_
