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

#include <android/native_window.h>
#include <media/IOMX.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <utils/threads.h>

namespace android {

class MemoryDealer;
struct OMXCodecObserver;
struct CodecProfileLevel;

struct OMXCodec : public MediaSource,
                  public MediaBufferObserver {
    enum CreationFlags {
        kPreferSoftwareCodecs    = 1,
        kIgnoreCodecSpecificData = 2,

        // The client wants to access the output buffer's video
        // data for example for thumbnail extraction.
        kClientNeedsFramebuffer  = 4,

        // Request for software or hardware codecs. If request
        // can not be fullfilled, Create() returns NULL.
        kSoftwareCodecsOnly      = 8,
        kHardwareCodecsOnly      = 16,

        // Store meta data in video buffers
        kStoreMetaDataInVideoBuffers = 32,

        // Only submit one input buffer at one time.
        kOnlySubmitOneInputBufferAtOneTime = 64,

        // Enable GRALLOC_USAGE_PROTECTED for output buffers from native window
        kEnableGrallocUsageProtected = 128,
    };
    static sp<MediaSource> Create(
            const sp<IOMX> &omx,
            const sp<MetaData> &meta, bool createEncoder,
            const sp<MediaSource> &source,
            const char *matchComponentName = NULL,
            uint32_t flags = 0,
            const sp<ANativeWindow> &nativeWindow = NULL);

    static void setComponentRole(
            const sp<IOMX> &omx, IOMX::node_id node, bool isEncoder,
            const char *mime);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    virtual status_t pause();

    // from MediaBufferObserver
    virtual void signalBufferReturned(MediaBuffer *buffer);

protected:
    virtual ~OMXCodec();

private:

    // Make sure mLock is accessible to OMXCodecObserver
    friend class OMXCodecObserver;

    // Call this with mLock hold
    void on_message(const omx_message &msg);

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
        kNeedsFlushBeforeDisable              = 1,
        kWantsNALFragments                    = 2,
        kRequiresLoadedToIdleAfterAllocation  = 4,
        kRequiresAllocateBufferOnInputPorts   = 8,
        kRequiresFlushCompleteEmulation       = 16,
        kRequiresAllocateBufferOnOutputPorts  = 32,
        kRequiresFlushBeforeShutdown          = 64,
        kDefersOutputBufferAllocation         = 128,
        kDecoderLiesAboutNumberOfChannels     = 256,
        kInputBufferSizesAreBogus             = 512,
        kSupportsMultipleFramesPerInputBuffer = 1024,
        kAvoidMemcopyInputRecordingFrames     = 2048,
        kRequiresLargerEncoderOutputBuffer    = 4096,
        kOutputBuffersAreUnreadable           = 8192,
    };

    enum BufferStatus {
        OWNED_BY_US,
        OWNED_BY_COMPONENT,
        OWNED_BY_NATIVE_WINDOW,
        OWNED_BY_CLIENT,
    };

    struct BufferInfo {
        IOMX::buffer_id mBuffer;
        BufferStatus mStatus;
        sp<IMemory> mMem;
        size_t mSize;
        void *mData;
        MediaBuffer *mMediaBuffer;
    };

    struct CodecSpecificData {
        size_t mSize;
        uint8_t mData[1];
    };

    sp<IOMX> mOMX;
    bool mOMXLivesLocally;
    IOMX::node_id mNode;
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
    status_t mFinalStatus;
    bool mNoMoreOutputData;
    bool mOutputPortSettingsHaveChanged;
    int64_t mSeekTimeUs;
    ReadOptions::SeekMode mSeekMode;
    int64_t mTargetTimeUs;
    bool mOutputPortSettingsChangedPending;

    MediaBuffer *mLeftOverBuffer;

    Mutex mLock;
    Condition mAsyncCompletion;

    bool mPaused;

    sp<ANativeWindow> mNativeWindow;

    // The index in each of the mPortBuffers arrays of the buffer that will be
    // submitted to OMX next.  This only applies when using buffers from a
    // native window.
    size_t mNextNativeBufferIndex[2];

    // A list of indices into mPortStatus[kPortIndexOutput] filled with data.
    List<size_t> mFilledBuffers;
    Condition mBufferFilled;

    bool mIsMetaDataStoredInVideoBuffers;
    bool mOnlySubmitOneBufferAtOneTime;
    bool mEnableGrallocUsageProtected;

    OMXCodec(const sp<IOMX> &omx, IOMX::node_id node, uint32_t quirks,
             bool isEncoder, const char *mime, const char *componentName,
             const sp<MediaSource> &source,
             const sp<ANativeWindow> &nativeWindow);

    void addCodecSpecificData(const void *data, size_t size);
    void clearCodecSpecificData();

    void setComponentRole();

    void setAMRFormat(bool isWAMR, int32_t bitRate);
    void setAACFormat(int32_t numChannels, int32_t sampleRate, int32_t bitRate);

    status_t setVideoPortFormatType(
            OMX_U32 portIndex,
            OMX_VIDEO_CODINGTYPE compressionFormat,
            OMX_COLOR_FORMATTYPE colorFormat);

    void setVideoInputFormat(
            const char *mime, const sp<MetaData>& meta);

    status_t setupBitRate(int32_t bitRate);
    status_t setupErrorCorrectionParameters();
    status_t setupH263EncoderParameters(const sp<MetaData>& meta);
    status_t setupMPEG4EncoderParameters(const sp<MetaData>& meta);
    status_t setupAVCEncoderParameters(const sp<MetaData>& meta);
    status_t findTargetColorFormat(
            const sp<MetaData>& meta, OMX_COLOR_FORMATTYPE *colorFormat);

    status_t isColorFormatSupported(
            OMX_COLOR_FORMATTYPE colorFormat, int portIndex);

    // If profile/level is set in the meta data, its value in the meta
    // data will be used; otherwise, the default value will be used.
    status_t getVideoProfileLevel(const sp<MetaData>& meta,
            const CodecProfileLevel& defaultProfileLevel,
            CodecProfileLevel& profileLevel);

    status_t setVideoOutputFormat(
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
    status_t allocateOutputBuffersFromNativeWindow();

    status_t queueBufferToNativeWindow(BufferInfo *info);
    status_t cancelBufferToNativeWindow(BufferInfo *info);
    BufferInfo* dequeueBufferFromNativeWindow();

    status_t freeBuffersOnPort(
            OMX_U32 portIndex, bool onlyThoseWeOwn = false);

    status_t freeBuffer(OMX_U32 portIndex, size_t bufIndex);

    bool drainInputBuffer(IOMX::buffer_id buffer);
    void fillOutputBuffer(IOMX::buffer_id buffer);
    bool drainInputBuffer(BufferInfo *info);
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
    status_t initNativeWindow();

    void dumpPortStatus(OMX_U32 portIndex);

    status_t configureCodec(const sp<MetaData> &meta, uint32_t flags);

    static uint32_t getComponentQuirks(
            const char *componentName, bool isEncoder);

    static void findMatchingCodecs(
            const char *mime,
            bool createEncoder, const char *matchComponentName,
            uint32_t flags,
            Vector<String8> *matchingCodecs);

    void restorePatchedDataPointer(BufferInfo *info);

    status_t applyRotation();

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
    Vector<OMX_U32> mColorFormats;
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
