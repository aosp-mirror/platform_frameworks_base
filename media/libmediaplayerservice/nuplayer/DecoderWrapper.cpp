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

//#define LOG_NDEBUG 0
#define LOG_TAG "DecoderWrapper"
#include <utils/Log.h>

#include "DecoderWrapper.h"

#include "AACDecoder.h"

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/ACodec.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

namespace android {

struct DecoderWrapper::WrapperSource : public MediaSource {
    WrapperSource(
            const sp<MetaData> &meta,
            const sp<AMessage> &notify);

    virtual status_t start(MetaData *params);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

    void queueBuffer(const sp<ABuffer> &buffer);
    void queueEOS(status_t finalResult);
    void clear();

protected:
    virtual ~WrapperSource();

private:
    Mutex mLock;
    Condition mCondition;

    sp<MetaData> mMeta;
    sp<AMessage> mNotify;

    List<sp<ABuffer> > mQueue;
    status_t mFinalResult;

    DISALLOW_EVIL_CONSTRUCTORS(WrapperSource);
};

DecoderWrapper::WrapperSource::WrapperSource(
        const sp<MetaData> &meta, const sp<AMessage> &notify)
    : mMeta(meta),
      mNotify(notify),
      mFinalResult(OK) {
}

DecoderWrapper::WrapperSource::~WrapperSource() {
}

status_t DecoderWrapper::WrapperSource::start(MetaData *params) {
    return OK;
}

status_t DecoderWrapper::WrapperSource::stop() {
    return OK;
}

sp<MetaData> DecoderWrapper::WrapperSource::getFormat() {
    return mMeta;
}

status_t DecoderWrapper::WrapperSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    Mutex::Autolock autoLock(mLock);

    bool requestedBuffer = false;

    while (mQueue.empty() && mFinalResult == OK) {
        if (!requestedBuffer) {
            mNotify->dup()->post();
            requestedBuffer = true;
        }

        mCondition.wait(mLock);
    }

    if (mQueue.empty()) {
        return mFinalResult;
    }

    sp<ABuffer> src = *mQueue.begin();
    mQueue.erase(mQueue.begin());

    MediaBuffer *dst = new MediaBuffer(src->size());
    memcpy(dst->data(), src->data(), src->size());

    int64_t timeUs;
    CHECK(src->meta()->findInt64("timeUs", &timeUs));

    dst->meta_data()->setInt64(kKeyTime, timeUs);

    *out = dst;

    return OK;
}

void DecoderWrapper::WrapperSource::queueBuffer(const sp<ABuffer> &buffer) {
    Mutex::Autolock autoLock(mLock);
    mQueue.push_back(buffer);
    mCondition.broadcast();
}

void DecoderWrapper::WrapperSource::queueEOS(status_t finalResult) {
    CHECK_NE(finalResult, (status_t)OK);

    Mutex::Autolock autoLock(mLock);
    mFinalResult = finalResult;
    mCondition.broadcast();
}

void DecoderWrapper::WrapperSource::clear() {
    Mutex::Autolock autoLock(mLock);
    mQueue.clear();
    mFinalResult = OK;
}

////////////////////////////////////////////////////////////////////////////////

struct DecoderWrapper::WrapperReader : public AHandler {
    WrapperReader(
            const sp<MediaSource> &decoder,
            const sp<AMessage> &notify);

    void start();
    void stop();
    void readMore(bool flush = false);

protected:
    virtual ~WrapperReader();

    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    enum {
        kWhatRead
    };

    sp<MediaSource> mDecoder;
    sp<AMessage> mNotify;
    bool mEOS;
    bool mSentFormat;

    void sendFormatChange();

    DISALLOW_EVIL_CONSTRUCTORS(WrapperReader);
};

DecoderWrapper::WrapperReader::WrapperReader(
        const sp<MediaSource> &decoder, const sp<AMessage> &notify)
    : mDecoder(decoder),
      mNotify(notify),
      mEOS(false),
      mSentFormat(false) {
}

DecoderWrapper::WrapperReader::~WrapperReader() {
}

void DecoderWrapper::WrapperReader::start() {
    CHECK_EQ(mDecoder->start(), (status_t)OK);
    readMore();
}

void DecoderWrapper::WrapperReader::stop() {
    CHECK_EQ(mDecoder->stop(), (status_t)OK);
}

void DecoderWrapper::WrapperReader::readMore(bool flush) {
    if (!flush && mEOS) {
        return;
    }

    sp<AMessage> msg = new AMessage(kWhatRead, id());
    msg->setInt32("flush", static_cast<int32_t>(flush));
    msg->post();
}

void DecoderWrapper::WrapperReader::onMessageReceived(
        const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatRead:
        {
            int32_t flush;
            CHECK(msg->findInt32("flush", &flush));

            MediaSource::ReadOptions options;
            if (flush) {
                // Dummy seek
                options.setSeekTo(0);
                mEOS = false;
            }

            CHECK(!mEOS);

            MediaBuffer *src;
            status_t err = mDecoder->read(&src, &options);

            if (err == OK) {
                if (!mSentFormat) {
                    sendFormatChange();
                    mSentFormat = true;
                }

                sp<AMessage> notify = mNotify->dup();

                sp<AMessage> realNotify;
                CHECK(notify->findMessage("real-notify", &realNotify));

                realNotify->setInt32("what", ACodec::kWhatDrainThisBuffer);

                sp<ABuffer> dst = new ABuffer(src->range_length());
                memcpy(dst->data(),
                       (const uint8_t *)src->data() + src->range_offset(),
                       src->range_length());

                int64_t timeUs;
                CHECK(src->meta_data()->findInt64(kKeyTime, &timeUs));
                src->release();
                src = NULL;

                dst->meta()->setInt64("timeUs", timeUs);

                realNotify->setObject("buffer", dst);

                notify->post();
            } else if (err == INFO_FORMAT_CHANGED) {
                sendFormatChange();

                readMore(false /* flush */);
            } else {
                sp<AMessage> notify = mNotify->dup();

                sp<AMessage> realNotify;
                CHECK(notify->findMessage("real-notify", &realNotify));

                realNotify->setInt32("what", ACodec::kWhatEOS);
                mEOS = true;

                notify->post();
            }
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void DecoderWrapper::WrapperReader::sendFormatChange() {
    sp<AMessage> notify = mNotify->dup();

    sp<AMessage> realNotify;
    CHECK(notify->findMessage("real-notify", &realNotify));

    realNotify->setInt32("what", ACodec::kWhatOutputFormatChanged);

    sp<MetaData> meta = mDecoder->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    realNotify->setString("mime", mime);

    if (!strncasecmp("audio/", mime, 6)) {
        int32_t numChannels;
        CHECK(meta->findInt32(kKeyChannelCount, &numChannels));

        int32_t sampleRate;
        CHECK(meta->findInt32(kKeySampleRate, &sampleRate));

        realNotify->setInt32("channel-count", numChannels);
        realNotify->setInt32("sample-rate", sampleRate);
    } else {
        CHECK(!strncasecmp("video/", mime, 6));

        int32_t width, height;
        CHECK(meta->findInt32(kKeyWidth, &width));
        CHECK(meta->findInt32(kKeyHeight, &height));

        realNotify->setInt32("width", width);
        realNotify->setInt32("height", height);

        int32_t cropLeft, cropTop, cropRight, cropBottom;
        if (!meta->findRect(
                    kKeyCropRect,
                    &cropLeft, &cropTop, &cropRight, &cropBottom)) {
            cropLeft = 0;
            cropTop = 0;
            cropRight = width - 1;
            cropBottom = height - 1;
        }

        realNotify->setRect("crop", cropLeft, cropTop, cropRight, cropBottom);
    }

    notify->post();

    mSentFormat = true;
}

////////////////////////////////////////////////////////////////////////////////

DecoderWrapper::DecoderWrapper()
    : mNumOutstandingInputBuffers(0),
      mNumOutstandingOutputBuffers(0),
      mNumPendingDecodes(0),
      mFlushing(false) {
}

DecoderWrapper::~DecoderWrapper() {
}

void DecoderWrapper::setNotificationMessage(const sp<AMessage> &msg) {
    mNotify = msg;
}

void DecoderWrapper::initiateSetup(const sp<AMessage> &msg) {
    msg->setWhat(kWhatSetup);
    msg->setTarget(id());
    msg->post();
}

void DecoderWrapper::initiateShutdown() {
    (new AMessage(kWhatShutdown, id()))->post();
}

void DecoderWrapper::signalFlush() {
    (new AMessage(kWhatFlush, id()))->post();
}

void DecoderWrapper::signalResume() {
    (new AMessage(kWhatResume, id()))->post();
}

void DecoderWrapper::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatSetup:
            onSetup(msg);
            break;

        case kWhatShutdown:
            onShutdown();
            break;

        case kWhatInputDataRequested:
        {
            postFillBuffer();
            ++mNumOutstandingInputBuffers;
            break;
        }

        case kWhatInputBufferFilled:
        {
            CHECK_GT(mNumOutstandingInputBuffers, 0);
            --mNumOutstandingInputBuffers;

            if (mFlushing) {
                mSource->queueEOS(INFO_DISCONTINUITY);

                completeFlushIfPossible();
                break;
            }

            sp<RefBase> obj;
            if (!msg->findObject("buffer", &obj)) {
                int32_t err = OK;
                CHECK(msg->findInt32("err", &err));

                mSource->queueEOS(err);
                break;
            }

            sp<ABuffer> buffer = static_cast<ABuffer *>(obj.get());

            mSource->queueBuffer(buffer);
            break;
        }

        case kWhatFillBufferDone:
        {
            sp<AMessage> notify;
            CHECK(msg->findMessage("real-notify", &notify));

            int32_t what;
            CHECK(notify->findInt32("what", &what));

            if (what == ACodec::kWhatDrainThisBuffer) {
                CHECK_GT(mNumPendingDecodes, 0);
                --mNumPendingDecodes;

                sp<AMessage> reply =
                    new AMessage(kWhatOutputBufferDrained, id());

                notify->setMessage("reply", reply);

                ++mNumOutstandingOutputBuffers;
            } else if (what == ACodec::kWhatEOS) {
                CHECK_GT(mNumPendingDecodes, 0);
                --mNumPendingDecodes;

                if (mFlushing) {
                    completeFlushIfPossible();
                    break;
                }
            }

            notify->post();
            break;
        }

        case kWhatOutputBufferDrained:
        {
            CHECK_GT(mNumOutstandingOutputBuffers, 0);
            --mNumOutstandingOutputBuffers;

            if (mFlushing) {
                completeFlushIfPossible();
                break;
            }

            ++mNumPendingDecodes;
            mReader->readMore();
            break;
        }

        case kWhatFlush:
        {
            onFlush();
            break;
        }

        case kWhatResume:
        {
            onResume();
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void DecoderWrapper::onSetup(const sp<AMessage> &msg) {
    AString mime;
    CHECK(msg->findString("mime", &mime));

    CHECK(!strcasecmp(mime.c_str(), MEDIA_MIMETYPE_AUDIO_AAC));

    int32_t numChannels, sampleRate;
    CHECK(msg->findInt32("channel-count", &numChannels));
    CHECK(msg->findInt32("sample-rate", &sampleRate));

    sp<RefBase> obj;
    CHECK(msg->findObject("esds", &obj));
    sp<ABuffer> esds = static_cast<ABuffer *>(obj.get());

    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, mime.c_str());
    meta->setInt32(kKeySampleRate, sampleRate);
    meta->setInt32(kKeyChannelCount, numChannels);
    meta->setData(kKeyESDS, 0, esds->data(), esds->size());

    mSource = new WrapperSource(
            meta, new AMessage(kWhatInputDataRequested, id()));

    sp<MediaSource> decoder = new AACDecoder(mSource);

    mReaderLooper = new ALooper;
    mReaderLooper->setName("DecoderWrapper looper");

    mReaderLooper->start(
            false, /* runOnCallingThread */
            false, /* canCallJava */
            PRIORITY_AUDIO);

    sp<AMessage> notify = new AMessage(kWhatFillBufferDone, id());
    notify->setMessage("real-notify", mNotify);

    mReader = new WrapperReader(decoder, notify);
    mReaderLooper->registerHandler(mReader);

    mReader->start();
    ++mNumPendingDecodes;
}

void DecoderWrapper::onShutdown() {
    mReaderLooper->stop();
    mReaderLooper.clear();

    mReader->stop();
    mReader.clear();

    mSource.clear();

    mNumOutstandingInputBuffers = 0;
    mNumOutstandingOutputBuffers = 0;
    mNumPendingDecodes = 0;
    mFlushing = false;

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", ACodec::kWhatShutdownCompleted);
    notify->post();
}

void DecoderWrapper::postFillBuffer() {
    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", ACodec::kWhatFillThisBuffer);
    sp<AMessage> reply = new AMessage(kWhatInputBufferFilled, id());
    notify->setMessage("reply", reply);
    notify->post();
}

void DecoderWrapper::onFlush() {
    CHECK(!mFlushing);
    mFlushing = true;

    completeFlushIfPossible();
}

void DecoderWrapper::completeFlushIfPossible() {
    CHECK(mFlushing);

    if (mNumOutstandingInputBuffers > 0
            || mNumOutstandingOutputBuffers > 0
            || mNumPendingDecodes > 0) {
        return;
    }

    mFlushing = false;

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", ACodec::kWhatFlushCompleted);
    notify->post();
}

void DecoderWrapper::onResume() {
    CHECK(!mFlushing);

    ++mNumPendingDecodes;

    mSource->clear();
    mReader->readMore(true /* flush */);
}

}  // namespace android
