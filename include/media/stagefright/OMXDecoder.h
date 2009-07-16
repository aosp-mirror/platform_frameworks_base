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

#ifndef OMX_DECODER_H_

#define OMX_DECODER_H_

#include <binder/MemoryDealer.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/OMXClient.h>
#include <utils/KeyedVector.h>
#include <utils/List.h>
#include <utils/threads.h>

namespace android {

class OMXMediaBuffer;

class OMXDecoder : public MediaSource,
                   public OMXObserver,
                   public MediaBufferObserver {
public:
    static OMXDecoder *Create(
            OMXClient *client, const sp<MetaData> &data);

    static OMXDecoder *CreateEncoder(
            OMXClient *client, const sp<MetaData> &data);

    virtual ~OMXDecoder();

    // Caller retains ownership of "source".
    void setSource(MediaSource *source);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    void addCodecSpecificData(const void *data, size_t size);

    // from OMXObserver
    virtual void onOMXMessage(const omx_message &msg);

    // from MediaBufferObserver
    virtual void signalBufferReturned(MediaBuffer *buffer);

private:
    enum {
        kPortIndexInput  = 0,
        kPortIndexOutput = 1
    };

    enum PortStatus {
        kPortStatusActive   = 0,
        kPortStatusDisabled = 1,
        kPortStatusShutdown = 2,
        kPortStatusFlushing = 3
    };

    OMXClient *mClient;
    sp<IOMX> mOMX;
    IOMX::node_id mNode;
    char *mComponentName;
    bool mIsMP3;

    MediaSource *mSource;
    sp<MetaData> mOutputFormat;

    Mutex mLock;
    Condition mOutputBufferAvailable;

    List<MediaBuffer *> mOutputBuffers;

    struct CodecSpecificData {
        void *data;
        size_t size;
    };

    List<CodecSpecificData> mCodecSpecificData;
    List<CodecSpecificData>::iterator mCodecSpecificDataIterator;

    volatile OMX_STATETYPE mState;
    OMX_U32 mPortStatusMask;
    bool mShutdownInitiated;

    typedef List<IOMX::buffer_id> BufferList;
    Vector<BufferList> mBuffers;

    KeyedVector<IOMX::buffer_id, sp<IMemory> > mBufferMap;
    KeyedVector<IOMX::buffer_id, OMXMediaBuffer *> mMediaBufferMap;

    sp<MemoryDealer> mDealer;

    bool mSeeking;
    int64_t mSeekTimeUs;

    bool mStarted;
    status_t mErrorCondition;
    bool mReachedEndOfInput;

    OMXDecoder(OMXClient *client, IOMX::node_id node,
               const char *mime, const char *codec);

    void setPortStatus(OMX_U32 port_index, PortStatus status);
    PortStatus getPortStatus(OMX_U32 port_index) const;

    void allocateBuffers(OMX_U32 port_index);

    void setAMRFormat();
    void setAACFormat();
    void setVideoOutputFormat(OMX_U32 width, OMX_U32 height);
    void setup();
    void dumpPortDefinition(OMX_U32 port_index);

    void onStart();
    void onEvent(OMX_EVENTTYPE event, OMX_U32 data1, OMX_U32 data2);
    void onEventCmdComplete(OMX_COMMANDTYPE type, OMX_U32 data);
    void onEventPortSettingsChanged(OMX_U32 port_index);
    void onStateChanged(OMX_STATETYPE to);
    void onEmptyBufferDone(IOMX::buffer_id buffer);
    void onFillBufferDone(const omx_message &msg);

    void onRealEmptyBufferDone(IOMX::buffer_id buffer);
    void onRealFillBufferDone(const omx_message &msg);

    void initiateShutdown();

    void freeInputBuffer(IOMX::buffer_id buffer);
    void freeOutputBuffer(IOMX::buffer_id buffer);

    void postStart();
    void postEmptyBufferDone(IOMX::buffer_id buffer);
    void postInitialFillBuffer(IOMX::buffer_id buffer);

    OMXDecoder(const OMXDecoder &);
    OMXDecoder &operator=(const OMXDecoder &);
};

}  // namespace android

#endif  // OMX_DECODER_H_
