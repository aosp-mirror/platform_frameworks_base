/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef MEDIA_CODEC_H_

#define MEDIA_CODEC_H_

#include <gui/ISurfaceTexture.h>
#include <media/stagefright/foundation/AHandler.h>
#include <utils/Vector.h>

namespace android {

struct ABuffer;
struct ACodec;
struct AMessage;
struct ICrypto;
struct SoftwareRenderer;
struct SurfaceTextureClient;

struct MediaCodec : public AHandler {
    enum ConfigureFlags {
        CONFIGURE_FLAG_ENCODE   = 1,
        CONFIGURE_FLAG_SECURE   = 2,
    };

    enum BufferFlags {
        BUFFER_FLAG_SYNCFRAME   = 1,
        BUFFER_FLAG_CODECCONFIG = 2,
        BUFFER_FLAG_EOS         = 4,
        BUFFER_FLAG_ENCRYPTED   = 8,
    };

    static sp<MediaCodec> CreateByType(
            const sp<ALooper> &looper, const char *mime, bool encoder);

    static sp<MediaCodec> CreateByComponentName(
            const sp<ALooper> &looper, const char *name);

    status_t configure(
            const sp<AMessage> &format,
            const sp<SurfaceTextureClient> &nativeWindow,
            uint32_t flags);

    status_t start();

    // Returns to a state in which the component remains allocated but
    // unconfigured.
    status_t stop();

    // Client MUST call release before releasing final reference to this
    // object.
    status_t release();

    status_t flush();

    status_t queueInputBuffer(
            size_t index,
            size_t offset,
            size_t size,
            int64_t presentationTimeUs,
            uint32_t flags);

    status_t dequeueInputBuffer(size_t *index, int64_t timeoutUs = 0ll);

    status_t dequeueOutputBuffer(
            size_t *index,
            size_t *offset,
            size_t *size,
            int64_t *presentationTimeUs,
            uint32_t *flags,
            int64_t timeoutUs = 0ll);

    status_t renderOutputBufferAndRelease(size_t index);
    status_t releaseOutputBuffer(size_t index);

    status_t getOutputFormat(sp<AMessage> *format) const;

    status_t getInputBuffers(Vector<sp<ABuffer> > *buffers) const;
    status_t getOutputBuffers(Vector<sp<ABuffer> > *buffers) const;

protected:
    virtual ~MediaCodec();
    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    enum State {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        CONFIGURING,
        CONFIGURED,
        STARTING,
        STARTED,
        FLUSHING,
        STOPPING,
        RELEASING,
    };

    enum {
        kPortIndexInput         = 0,
        kPortIndexOutput        = 1,
    };

    enum {
        kWhatInit                       = 'init',
        kWhatConfigure                  = 'conf',
        kWhatStart                      = 'strt',
        kWhatStop                       = 'stop',
        kWhatRelease                    = 'rele',
        kWhatDequeueInputBuffer         = 'deqI',
        kWhatQueueInputBuffer           = 'queI',
        kWhatDequeueOutputBuffer        = 'deqO',
        kWhatReleaseOutputBuffer        = 'relO',
        kWhatGetBuffers                 = 'getB',
        kWhatFlush                      = 'flus',
        kWhatGetOutputFormat            = 'getO',
        kWhatDequeueInputTimedOut       = 'dITO',
        kWhatDequeueOutputTimedOut      = 'dOTO',
        kWhatCodecNotify                = 'codc',
    };

    enum {
        kFlagIsSoftwareCodec            = 1,
        kFlagOutputFormatChanged        = 2,
        kFlagOutputBuffersChanged       = 4,
        kFlagStickyError                = 8,
        kFlagDequeueInputPending        = 16,
        kFlagDequeueOutputPending       = 32,
        kFlagIsSecure                   = 64,
    };

    struct BufferInfo {
        void *mBufferID;
        sp<ABuffer> mData;
        sp<ABuffer> mEncryptedData;
        sp<AMessage> mNotify;
        bool mOwnedByClient;
    };

    State mState;
    sp<ALooper> mLooper;
    sp<ALooper> mCodecLooper;
    sp<ACodec> mCodec;
    uint32_t mReplyID;
    uint32_t mFlags;
    sp<SurfaceTextureClient> mNativeWindow;
    SoftwareRenderer *mSoftRenderer;
    sp<AMessage> mOutputFormat;

    List<size_t> mAvailPortBuffers[2];
    Vector<BufferInfo> mPortBuffers[2];

    int32_t mDequeueInputTimeoutGeneration;
    uint32_t mDequeueInputReplyID;

    int32_t mDequeueOutputTimeoutGeneration;
    uint32_t mDequeueOutputReplyID;

    sp<ICrypto> mCrypto;

    MediaCodec(const sp<ALooper> &looper);

    static status_t PostAndAwaitResponse(
            const sp<AMessage> &msg, sp<AMessage> *response);

    status_t init(const char *name, bool nameIsType, bool encoder);

    void setState(State newState);
    void returnBuffersToCodec();
    void returnBuffersToCodecOnPort(int32_t portIndex);
    size_t updateBuffers(int32_t portIndex, const sp<AMessage> &msg);
    status_t onQueueInputBuffer(const sp<AMessage> &msg);
    status_t onReleaseOutputBuffer(const sp<AMessage> &msg);
    ssize_t dequeuePortBuffer(int32_t portIndex);

    bool handleDequeueInputBuffer(uint32_t replyID, bool newRequest = false);
    bool handleDequeueOutputBuffer(uint32_t replyID, bool newRequest = false);
    void cancelPendingDequeueOperations();

    DISALLOW_EVIL_CONSTRUCTORS(MediaCodec);
};

}  // namespace android

#endif  // MEDIA_CODEC_H_
