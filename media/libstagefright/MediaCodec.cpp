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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaCodec"
#include <utils/Log.h>

#include <media/stagefright/MediaCodec.h>

#include "include/SoftwareRenderer.h"

#include <gui/SurfaceTextureClient.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/ACodec.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/NativeWindowWrapper.h>

namespace android {

// static
sp<MediaCodec> MediaCodec::CreateByType(
        const sp<ALooper> &looper, const char *mime, bool encoder) {
    sp<MediaCodec> codec = new MediaCodec(looper);
    if (codec->init(mime, true /* nameIsType */, encoder) != OK) {
        return NULL;
    }

    return codec;
}

// static
sp<MediaCodec> MediaCodec::CreateByComponentName(
        const sp<ALooper> &looper, const char *name) {
    sp<MediaCodec> codec = new MediaCodec(looper);
    if (codec->init(name, false /* nameIsType */, false /* encoder */) != OK) {
        return NULL;
    }

    return codec;
}

MediaCodec::MediaCodec(const sp<ALooper> &looper)
    : mState(UNINITIALIZED),
      mLooper(looper),
      mCodec(new ACodec),
      mFlags(0),
      mSoftRenderer(NULL),
      mDequeueInputTimeoutGeneration(0),
      mDequeueInputReplyID(0),
      mDequeueOutputTimeoutGeneration(0),
      mDequeueOutputReplyID(0) {
}

MediaCodec::~MediaCodec() {
    CHECK_EQ(mState, UNINITIALIZED);
}

// static
status_t MediaCodec::PostAndAwaitResponse(
        const sp<AMessage> &msg, sp<AMessage> *response) {
    status_t err = msg->postAndAwaitResponse(response);

    if (err != OK) {
        return err;
    }

    if (!(*response)->findInt32("err", &err)) {
        err = OK;
    }

    return err;
}

status_t MediaCodec::init(const char *name, bool nameIsType, bool encoder) {
    // Current video decoders do not return from OMX_FillThisBuffer
    // quickly, violating the OpenMAX specs, until that is remedied
    // we need to invest in an extra looper to free the main event
    // queue.
    bool needDedicatedLooper = false;
    if (nameIsType && !strncasecmp(name, "video/", 6)) {
        needDedicatedLooper = true;
    } else if (!nameIsType && !strncmp(name, "OMX.TI.DUCATI1.VIDEO.", 21)) {
        needDedicatedLooper = true;
    }

    if (needDedicatedLooper) {
        if (mCodecLooper == NULL) {
            mCodecLooper = new ALooper;
            mCodecLooper->setName("CodecLooper");
            mCodecLooper->start(false, false, ANDROID_PRIORITY_AUDIO);
        }

        mCodecLooper->registerHandler(mCodec);
    } else {
        mLooper->registerHandler(mCodec);
    }

    mLooper->registerHandler(this);

    mCodec->setNotificationMessage(new AMessage(kWhatCodecNotify, id()));

    sp<AMessage> msg = new AMessage(kWhatInit, id());
    msg->setString("name", name);
    msg->setInt32("nameIsType", nameIsType);

    if (nameIsType) {
        msg->setInt32("encoder", encoder);
    }

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::configure(
        const sp<AMessage> &format,
        const sp<SurfaceTextureClient> &nativeWindow,
        uint32_t flags) {
    sp<AMessage> msg = new AMessage(kWhatConfigure, id());

    msg->setMessage("format", format);
    msg->setInt32("flags", flags);

    if (nativeWindow != NULL) {
        if (!(mFlags & kFlagIsSoftwareCodec)) {
            msg->setObject(
                    "native-window",
                    new NativeWindowWrapper(nativeWindow));
        } else {
            mNativeWindow = nativeWindow;
        }
    }

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::start() {
    sp<AMessage> msg = new AMessage(kWhatStart, id());

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::stop() {
    sp<AMessage> msg = new AMessage(kWhatStop, id());

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::queueInputBuffer(
        size_t index,
        size_t offset,
        size_t size,
        int64_t presentationTimeUs,
        uint32_t flags) {
    sp<AMessage> msg = new AMessage(kWhatQueueInputBuffer, id());
    msg->setSize("index", index);
    msg->setSize("offset", offset);
    msg->setSize("size", size);
    msg->setInt64("timeUs", presentationTimeUs);
    msg->setInt32("flags", flags);

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::dequeueInputBuffer(size_t *index, int64_t timeoutUs) {
    sp<AMessage> msg = new AMessage(kWhatDequeueInputBuffer, id());
    msg->setInt64("timeoutUs", timeoutUs);

    sp<AMessage> response;
    status_t err;
    if ((err = PostAndAwaitResponse(msg, &response)) != OK) {
        return err;
    }

    CHECK(response->findSize("index", index));

    return OK;
}

status_t MediaCodec::dequeueOutputBuffer(
        size_t *index,
        size_t *offset,
        size_t *size,
        int64_t *presentationTimeUs,
        uint32_t *flags,
        int64_t timeoutUs) {
    sp<AMessage> msg = new AMessage(kWhatDequeueOutputBuffer, id());
    msg->setInt64("timeoutUs", timeoutUs);

    sp<AMessage> response;
    status_t err;
    if ((err = PostAndAwaitResponse(msg, &response)) != OK) {
        return err;
    }

    CHECK(response->findSize("index", index));
    CHECK(response->findSize("offset", offset));
    CHECK(response->findSize("size", size));
    CHECK(response->findInt64("timeUs", presentationTimeUs));
    CHECK(response->findInt32("flags", (int32_t *)flags));

    return OK;
}

status_t MediaCodec::renderOutputBufferAndRelease(size_t index) {
    sp<AMessage> msg = new AMessage(kWhatReleaseOutputBuffer, id());
    msg->setSize("index", index);
    msg->setInt32("render", true);

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::releaseOutputBuffer(size_t index) {
    sp<AMessage> msg = new AMessage(kWhatReleaseOutputBuffer, id());
    msg->setSize("index", index);

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::getOutputFormat(sp<AMessage> *format) const {
    sp<AMessage> msg = new AMessage(kWhatGetOutputFormat, id());

    sp<AMessage> response;
    status_t err;
    if ((err = PostAndAwaitResponse(msg, &response)) != OK) {
        return err;
    }

    CHECK(response->findMessage("format", format));

    return OK;
}

status_t MediaCodec::getInputBuffers(Vector<sp<ABuffer> > *buffers) const {
    sp<AMessage> msg = new AMessage(kWhatGetBuffers, id());
    msg->setInt32("portIndex", kPortIndexInput);
    msg->setPointer("buffers", buffers);

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::getOutputBuffers(Vector<sp<ABuffer> > *buffers) const {
    sp<AMessage> msg = new AMessage(kWhatGetBuffers, id());
    msg->setInt32("portIndex", kPortIndexOutput);
    msg->setPointer("buffers", buffers);

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

status_t MediaCodec::flush() {
    sp<AMessage> msg = new AMessage(kWhatFlush, id());

    sp<AMessage> response;
    return PostAndAwaitResponse(msg, &response);
}

////////////////////////////////////////////////////////////////////////////////

void MediaCodec::cancelPendingDequeueOperations() {
    if (mFlags & kFlagDequeueInputPending) {
        sp<AMessage> response = new AMessage;
        response->setInt32("err", INVALID_OPERATION);
        response->postReply(mDequeueInputReplyID);

        ++mDequeueInputTimeoutGeneration;
        mDequeueInputReplyID = 0;
        mFlags &= ~kFlagDequeueInputPending;
    }

    if (mFlags & kFlagDequeueOutputPending) {
        sp<AMessage> response = new AMessage;
        response->setInt32("err", INVALID_OPERATION);
        response->postReply(mDequeueOutputReplyID);

        ++mDequeueOutputTimeoutGeneration;
        mDequeueOutputReplyID = 0;
        mFlags &= ~kFlagDequeueOutputPending;
    }
}

bool MediaCodec::handleDequeueInputBuffer(uint32_t replyID, bool newRequest) {
    if (mState != STARTED
            || (mFlags & kFlagStickyError)
            || (newRequest && (mFlags & kFlagDequeueInputPending))) {
        sp<AMessage> response = new AMessage;
        response->setInt32("err", INVALID_OPERATION);

        response->postReply(replyID);

        return true;
    }

    ssize_t index = dequeuePortBuffer(kPortIndexInput);

    if (index < 0) {
        CHECK_EQ(index, -EAGAIN);
        return false;
    }

    sp<AMessage> response = new AMessage;
    response->setSize("index", index);
    response->postReply(replyID);

    return true;
}

bool MediaCodec::handleDequeueOutputBuffer(uint32_t replyID, bool newRequest) {
    sp<AMessage> response = new AMessage;

    if (mState != STARTED
            || (mFlags & kFlagStickyError)
            || (newRequest && (mFlags & kFlagDequeueOutputPending))) {
        response->setInt32("err", INVALID_OPERATION);
    } else if (mFlags & kFlagOutputBuffersChanged) {
        response->setInt32("err", INFO_OUTPUT_BUFFERS_CHANGED);
        mFlags &= ~kFlagOutputBuffersChanged;
    } else if (mFlags & kFlagOutputFormatChanged) {
        response->setInt32("err", INFO_FORMAT_CHANGED);
        mFlags &= ~kFlagOutputFormatChanged;
    } else {
        ssize_t index = dequeuePortBuffer(kPortIndexOutput);

        if (index < 0) {
            CHECK_EQ(index, -EAGAIN);
            return false;
        }

        const sp<ABuffer> &buffer =
            mPortBuffers[kPortIndexOutput].itemAt(index).mData;

        response->setSize("index", index);
        response->setSize("offset", buffer->offset());
        response->setSize("size", buffer->size());

        int64_t timeUs;
        CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

        response->setInt64("timeUs", timeUs);

        int32_t omxFlags;
        CHECK(buffer->meta()->findInt32("omxFlags", &omxFlags));

        uint32_t flags = 0;
        if (omxFlags & OMX_BUFFERFLAG_SYNCFRAME) {
            flags |= BUFFER_FLAG_SYNCFRAME;
        }
        if (omxFlags & OMX_BUFFERFLAG_CODECCONFIG) {
            flags |= BUFFER_FLAG_CODECCONFIG;
        }
        if (omxFlags & OMX_BUFFERFLAG_EOS) {
            flags |= BUFFER_FLAG_EOS;
        }

        response->setInt32("flags", flags);
    }

    response->postReply(replyID);

    return true;
}

void MediaCodec::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatCodecNotify:
        {
            int32_t what;
            CHECK(msg->findInt32("what", &what));

            switch (what) {
                case ACodec::kWhatError:
                {
                    int32_t omxError, internalError;
                    CHECK(msg->findInt32("omx-error", &omxError));
                    CHECK(msg->findInt32("err", &internalError));

                    ALOGE("Codec reported an error. "
                          "(omx error 0x%08x, internalError %d)",
                          omxError, internalError);

                    bool sendErrorReponse = true;

                    switch (mState) {
                        case INITIALIZING:
                        {
                            setState(UNINITIALIZED);
                            break;
                        }

                        case CONFIGURING:
                        {
                            setState(INITIALIZED);
                            break;
                        }

                        case STARTING:
                        {
                            setState(CONFIGURED);
                            break;
                        }

                        case STOPPING:
                        {
                            // Ignore the error, assuming we'll still get
                            // the shutdown complete notification.

                            sendErrorReponse = false;
                            break;
                        }

                        case FLUSHING:
                        {
                            setState(STARTED);
                            break;
                        }

                        case STARTED:
                        {
                            sendErrorReponse = false;

                            mFlags |= kFlagStickyError;

                            cancelPendingDequeueOperations();
                            break;
                        }

                        default:
                        {
                            sendErrorReponse = false;

                            mFlags |= kFlagStickyError;
                            break;
                        }
                    }

                    if (sendErrorReponse) {
                        sp<AMessage> response = new AMessage;
                        response->setInt32("err", UNKNOWN_ERROR);

                        response->postReply(mReplyID);
                    }
                    break;
                }

                case ACodec::kWhatComponentAllocated:
                {
                    CHECK_EQ(mState, INITIALIZING);
                    setState(INITIALIZED);

                    AString componentName;
                    CHECK(msg->findString("componentName", &componentName));

                    if (componentName.startsWith("OMX.google.")) {
                        mFlags |= kFlagIsSoftwareCodec;
                    } else {
                        mFlags &= ~kFlagIsSoftwareCodec;
                    }

                    (new AMessage)->postReply(mReplyID);
                    break;
                }

                case ACodec::kWhatComponentConfigured:
                {
                    CHECK_EQ(mState, CONFIGURING);
                    setState(CONFIGURED);

                    (new AMessage)->postReply(mReplyID);
                    break;
                }

                case ACodec::kWhatBuffersAllocated:
                {
                    int32_t portIndex;
                    CHECK(msg->findInt32("portIndex", &portIndex));

                    ALOGV("%s buffers allocated",
                          portIndex == kPortIndexInput ? "input" : "output");

                    CHECK(portIndex == kPortIndexInput
                            || portIndex == kPortIndexOutput);

                    mPortBuffers[portIndex].clear();

                    Vector<BufferInfo> *buffers = &mPortBuffers[portIndex];
                    for (size_t i = 0;; ++i) {
                        AString name = StringPrintf("buffer-id_%d", i);

                        void *bufferID;
                        if (!msg->findPointer(name.c_str(), &bufferID)) {
                            break;
                        }

                        name = StringPrintf("data_%d", i);

                        BufferInfo info;
                        info.mBufferID = bufferID;
                        info.mOwnedByClient = false;
                        CHECK(msg->findBuffer(name.c_str(), &info.mData));

                        buffers->push_back(info);
                    }

                    if (portIndex == kPortIndexOutput) {
                        if (mState == STARTING) {
                            // We're always allocating output buffers after
                            // allocating input buffers, so this is a good
                            // indication that now all buffers are allocated.
                            setState(STARTED);
                            (new AMessage)->postReply(mReplyID);
                        } else {
                            mFlags |= kFlagOutputBuffersChanged;
                        }
                    }
                    break;
                }

                case ACodec::kWhatOutputFormatChanged:
                {
                    ALOGV("codec output format changed");

                    if ((mFlags & kFlagIsSoftwareCodec)
                            && mNativeWindow != NULL) {
                        AString mime;
                        CHECK(msg->findString("mime", &mime));

                        if (!strncasecmp("video/", mime.c_str(), 6)) {
                            delete mSoftRenderer;
                            mSoftRenderer = NULL;

                            int32_t width, height;
                            CHECK(msg->findInt32("width", &width));
                            CHECK(msg->findInt32("height", &height));

                            int32_t colorFormat;
                            CHECK(msg->findInt32(
                                        "color-format", &colorFormat));

                            sp<MetaData> meta = new MetaData;
                            meta->setInt32(kKeyWidth, width);
                            meta->setInt32(kKeyHeight, height);
                            meta->setInt32(kKeyColorFormat, colorFormat);

                            mSoftRenderer =
                                new SoftwareRenderer(mNativeWindow, meta);
                        }
                    }

                    mOutputFormat = msg;
                    mFlags |= kFlagOutputFormatChanged;
                    break;
                }

                case ACodec::kWhatFillThisBuffer:
                {
                    /* size_t index = */updateBuffers(kPortIndexInput, msg);

                    if (mState == FLUSHING || mState == STOPPING) {
                        returnBuffersToCodecOnPort(kPortIndexInput);
                        break;
                    }

                    if (mFlags & kFlagDequeueInputPending) {
                        CHECK(handleDequeueInputBuffer(mDequeueInputReplyID));

                        ++mDequeueInputTimeoutGeneration;
                        mFlags &= ~kFlagDequeueInputPending;
                        mDequeueInputReplyID = 0;
                    }
                    break;
                }

                case ACodec::kWhatDrainThisBuffer:
                {
                    /* size_t index = */updateBuffers(kPortIndexOutput, msg);

                    if (mState == FLUSHING || mState == STOPPING) {
                        returnBuffersToCodecOnPort(kPortIndexOutput);
                        break;
                    }

                    sp<ABuffer> buffer;
                    CHECK(msg->findBuffer("buffer", &buffer));

                    int32_t omxFlags;
                    CHECK(msg->findInt32("flags", &omxFlags));

                    buffer->meta()->setInt32("omxFlags", omxFlags);

                    if (mFlags & kFlagDequeueOutputPending) {
                        CHECK(handleDequeueOutputBuffer(mDequeueOutputReplyID));

                        ++mDequeueOutputTimeoutGeneration;
                        mFlags &= ~kFlagDequeueOutputPending;
                        mDequeueOutputReplyID = 0;
                    }
                    break;
                }

                case ACodec::kWhatEOS:
                {
                    // We already notify the client of this by using the
                    // corresponding flag in "onOutputBufferReady".
                    break;
                }

                case ACodec::kWhatShutdownCompleted:
                {
                    CHECK_EQ(mState, STOPPING);
                    setState(UNINITIALIZED);

                    (new AMessage)->postReply(mReplyID);
                    break;
                }

                case ACodec::kWhatFlushCompleted:
                {
                    CHECK_EQ(mState, FLUSHING);
                    setState(STARTED);

                    mCodec->signalResume();

                    (new AMessage)->postReply(mReplyID);
                    break;
                }

                default:
                    TRESPASS();
            }
            break;
        }

        case kWhatInit:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (mState != UNINITIALIZED) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            mReplyID = replyID;
            setState(INITIALIZING);

            AString name;
            CHECK(msg->findString("name", &name));

            int32_t nameIsType;
            int32_t encoder = false;
            if (!msg->findInt32("nameIsType", &nameIsType)) {
                nameIsType = false;
            } else {
                CHECK(msg->findInt32("encoder", &encoder));
            }

            sp<AMessage> format = new AMessage;

            if (nameIsType) {
                format->setString("mime", name.c_str());
                format->setInt32("encoder", encoder);
            } else {
                format->setString("componentName", name.c_str());
            }

            mCodec->initiateAllocateComponent(format);
            break;
        }

        case kWhatConfigure:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (mState != INITIALIZED) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            mReplyID = replyID;
            setState(CONFIGURING);

            sp<RefBase> obj;
            if (!msg->findObject("native-window", &obj)) {
                obj.clear();
            }

            sp<AMessage> format;
            CHECK(msg->findMessage("format", &format));

            if (obj != NULL) {
                format->setObject("native-window", obj);
            }

            uint32_t flags;
            CHECK(msg->findInt32("flags", (int32_t *)&flags));

            if (flags & CONFIGURE_FLAG_ENCODE) {
                format->setInt32("encoder", true);
            }

            mCodec->initiateConfigureComponent(format);
            break;
        }

        case kWhatStart:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (mState != CONFIGURED) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            mReplyID = replyID;
            setState(STARTING);

            mCodec->initiateStart();
            break;
        }

        case kWhatStop:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (mState != INITIALIZED
                    && mState != CONFIGURED && mState != STARTED) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            mReplyID = replyID;
            setState(STOPPING);

            mCodec->initiateShutdown();
            returnBuffersToCodec();
            break;
        }

        case kWhatDequeueInputBuffer:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (handleDequeueInputBuffer(replyID, true /* new request */)) {
                break;
            }

            int64_t timeoutUs;
            CHECK(msg->findInt64("timeoutUs", &timeoutUs));

            if (timeoutUs == 0ll) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", -EAGAIN);
                response->postReply(replyID);
                break;
            }

            mFlags |= kFlagDequeueInputPending;
            mDequeueInputReplyID = replyID;

            if (timeoutUs > 0ll) {
                sp<AMessage> timeoutMsg =
                    new AMessage(kWhatDequeueInputTimedOut, id());
                timeoutMsg->setInt32(
                        "generation", ++mDequeueInputTimeoutGeneration);
                timeoutMsg->post(timeoutUs);
            }
            break;
        }

        case kWhatDequeueInputTimedOut:
        {
            int32_t generation;
            CHECK(msg->findInt32("generation", &generation));

            if (generation != mDequeueInputTimeoutGeneration) {
                // Obsolete
                break;
            }

            CHECK(mFlags & kFlagDequeueInputPending);

            sp<AMessage> response = new AMessage;
            response->setInt32("err", -EAGAIN);
            response->postReply(mDequeueInputReplyID);

            mFlags &= ~kFlagDequeueInputPending;
            mDequeueInputReplyID = 0;
            break;
        }

        case kWhatQueueInputBuffer:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (mState != STARTED || (mFlags & kFlagStickyError)) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            status_t err = onQueueInputBuffer(msg);

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatDequeueOutputBuffer:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (handleDequeueOutputBuffer(replyID, true /* new request */)) {
                break;
            }

            int64_t timeoutUs;
            CHECK(msg->findInt64("timeoutUs", &timeoutUs));

            if (timeoutUs == 0ll) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", -EAGAIN);
                response->postReply(replyID);
                break;
            }

            mFlags |= kFlagDequeueOutputPending;
            mDequeueOutputReplyID = replyID;

            if (timeoutUs > 0ll) {
                sp<AMessage> timeoutMsg =
                    new AMessage(kWhatDequeueOutputTimedOut, id());
                timeoutMsg->setInt32(
                        "generation", ++mDequeueOutputTimeoutGeneration);
                timeoutMsg->post(timeoutUs);
            }
            break;
        }

        case kWhatDequeueOutputTimedOut:
        {
            int32_t generation;
            CHECK(msg->findInt32("generation", &generation));

            if (generation != mDequeueOutputTimeoutGeneration) {
                // Obsolete
                break;
            }

            CHECK(mFlags & kFlagDequeueOutputPending);

            sp<AMessage> response = new AMessage;
            response->setInt32("err", -EAGAIN);
            response->postReply(mDequeueOutputReplyID);

            mFlags &= ~kFlagDequeueOutputPending;
            mDequeueOutputReplyID = 0;
            break;
        }

        case kWhatReleaseOutputBuffer:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (mState != STARTED || (mFlags & kFlagStickyError)) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            status_t err = onReleaseOutputBuffer(msg);

            sp<AMessage> response = new AMessage;
            response->setInt32("err", err);
            response->postReply(replyID);
            break;
        }

        case kWhatGetBuffers:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (mState != STARTED || (mFlags & kFlagStickyError)) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            int32_t portIndex;
            CHECK(msg->findInt32("portIndex", &portIndex));

            Vector<sp<ABuffer> > *dstBuffers;
            CHECK(msg->findPointer("buffers", (void **)&dstBuffers));

            dstBuffers->clear();
            const Vector<BufferInfo> &srcBuffers = mPortBuffers[portIndex];

            for (size_t i = 0; i < srcBuffers.size(); ++i) {
                const BufferInfo &info = srcBuffers.itemAt(i);

                dstBuffers->push_back(info.mData);
            }

            (new AMessage)->postReply(replyID);
            break;
        }

        case kWhatFlush:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if (mState != STARTED || (mFlags & kFlagStickyError)) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            mReplyID = replyID;
            setState(FLUSHING);

            mCodec->signalFlush();
            returnBuffersToCodec();
            break;
        }

        case kWhatGetOutputFormat:
        {
            uint32_t replyID;
            CHECK(msg->senderAwaitsResponse(&replyID));

            if ((mState != STARTED && mState != FLUSHING)
                    || (mFlags & kFlagStickyError)) {
                sp<AMessage> response = new AMessage;
                response->setInt32("err", INVALID_OPERATION);

                response->postReply(replyID);
                break;
            }

            sp<AMessage> response = new AMessage;
            response->setMessage("format", mOutputFormat);
            response->postReply(replyID);
            break;
        }

        default:
            TRESPASS();
    }
}

void MediaCodec::setState(State newState) {
    if (newState == UNINITIALIZED) {
        delete mSoftRenderer;
        mSoftRenderer = NULL;

        mNativeWindow.clear();

        mOutputFormat.clear();
        mFlags &= ~kFlagOutputFormatChanged;
        mFlags &= ~kFlagOutputBuffersChanged;
        mFlags &= ~kFlagStickyError;
    }

    mState = newState;

    cancelPendingDequeueOperations();
}

void MediaCodec::returnBuffersToCodec() {
    returnBuffersToCodecOnPort(kPortIndexInput);
    returnBuffersToCodecOnPort(kPortIndexOutput);
}

void MediaCodec::returnBuffersToCodecOnPort(int32_t portIndex) {
    CHECK(portIndex == kPortIndexInput || portIndex == kPortIndexOutput);

    Vector<BufferInfo> *buffers = &mPortBuffers[portIndex];

    for (size_t i = 0; i < buffers->size(); ++i) {
        BufferInfo *info = &buffers->editItemAt(i);

        if (info->mNotify != NULL) {
            sp<AMessage> msg = info->mNotify;
            info->mNotify = NULL;
            info->mOwnedByClient = false;

            if (portIndex == kPortIndexInput) {
                msg->setInt32("err", ERROR_END_OF_STREAM);
            }
            msg->post();
        }
    }

    mAvailPortBuffers[portIndex].clear();
}

size_t MediaCodec::updateBuffers(
        int32_t portIndex, const sp<AMessage> &msg) {
    CHECK(portIndex == kPortIndexInput || portIndex == kPortIndexOutput);

    void *bufferID;
    CHECK(msg->findPointer("buffer-id", &bufferID));

    Vector<BufferInfo> *buffers = &mPortBuffers[portIndex];

    for (size_t i = 0; i < buffers->size(); ++i) {
        BufferInfo *info = &buffers->editItemAt(i);

        if (info->mBufferID == bufferID) {
            CHECK(info->mNotify == NULL);
            CHECK(msg->findMessage("reply", &info->mNotify));

            mAvailPortBuffers[portIndex].push_back(i);

            return i;
        }
    }

    TRESPASS();

    return 0;
}

status_t MediaCodec::onQueueInputBuffer(const sp<AMessage> &msg) {
    size_t index;
    size_t offset;
    size_t size;
    int64_t timeUs;
    uint32_t flags;
    CHECK(msg->findSize("index", &index));
    CHECK(msg->findSize("offset", &offset));
    CHECK(msg->findSize("size", &size));
    CHECK(msg->findInt64("timeUs", &timeUs));
    CHECK(msg->findInt32("flags", (int32_t *)&flags));

    if (index >= mPortBuffers[kPortIndexInput].size()) {
        return -ERANGE;
    }

    BufferInfo *info = &mPortBuffers[kPortIndexInput].editItemAt(index);

    if (info->mNotify == NULL || !info->mOwnedByClient) {
        return -EACCES;
    }

    if (offset + size > info->mData->capacity()) {
        return -EINVAL;
    }

    sp<AMessage> reply = info->mNotify;
    info->mNotify = NULL;
    info->mOwnedByClient = false;

    info->mData->setRange(offset, size);
    info->mData->meta()->setInt64("timeUs", timeUs);

    if (flags & BUFFER_FLAG_EOS) {
        info->mData->meta()->setInt32("eos", true);
    }

    if (flags & BUFFER_FLAG_CODECCONFIG) {
        info->mData->meta()->setInt32("csd", true);
    }

    reply->setBuffer("buffer", info->mData);
    reply->post();

    return OK;
}

status_t MediaCodec::onReleaseOutputBuffer(const sp<AMessage> &msg) {
    size_t index;
    CHECK(msg->findSize("index", &index));

    int32_t render;
    if (!msg->findInt32("render", &render)) {
        render = 0;
    }

    if (mState != STARTED) {
        return -EINVAL;
    }

    if (index >= mPortBuffers[kPortIndexOutput].size()) {
        return -ERANGE;
    }

    BufferInfo *info = &mPortBuffers[kPortIndexOutput].editItemAt(index);

    if (info->mNotify == NULL || !info->mOwnedByClient) {
        return -EACCES;
    }

    if (render) {
        info->mNotify->setInt32("render", true);

        if (mSoftRenderer != NULL) {
            mSoftRenderer->render(
                    info->mData->data(), info->mData->size(), NULL);
        }
    }

    info->mNotify->post();
    info->mNotify = NULL;
    info->mOwnedByClient = false;

    return OK;
}

ssize_t MediaCodec::dequeuePortBuffer(int32_t portIndex) {
    CHECK(portIndex == kPortIndexInput || portIndex == kPortIndexOutput);

    List<size_t> *availBuffers = &mAvailPortBuffers[portIndex];

    if (availBuffers->empty()) {
        return -EAGAIN;
    }

    size_t index = *availBuffers->begin();
    availBuffers->erase(availBuffers->begin());

    BufferInfo *info = &mPortBuffers[portIndex].editItemAt(index);
    CHECK(!info->mOwnedByClient);
    info->mOwnedByClient = true;

    return index;
}

}  // namespace android
