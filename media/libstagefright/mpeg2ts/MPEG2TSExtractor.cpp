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
#define LOG_TAG "MPEG2TSExtractor"
#include <utils/Log.h>

#include "include/MPEG2TSExtractor.h"

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/String8.h>

#include "AnotherPacketSource.h"
#include "ATSParser.h"

namespace android {

struct MPEG2TSSource : public MediaSource {
    MPEG2TSSource(
            const sp<MPEG2TSExtractor> &extractor,
            const sp<AnotherPacketSource> &impl);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

private:
    sp<MPEG2TSExtractor> mExtractor;
    sp<AnotherPacketSource> mImpl;

    DISALLOW_EVIL_CONSTRUCTORS(MPEG2TSSource);
};

MPEG2TSSource::MPEG2TSSource(
        const sp<MPEG2TSExtractor> &extractor,
        const sp<AnotherPacketSource> &impl)
    : mExtractor(extractor),
      mImpl(impl) {
}

status_t MPEG2TSSource::start(MetaData *params) {
    return mImpl->start(params);
}

status_t MPEG2TSSource::stop() {
    return mImpl->stop();
}

sp<MetaData> MPEG2TSSource::getFormat() {
    return mImpl->getFormat();
}

status_t MPEG2TSSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    status_t finalResult;
    while (!mImpl->hasBufferAvailable(&finalResult)) {
        if (finalResult != OK) {
            return ERROR_END_OF_STREAM;
        }

        status_t err = mExtractor->feedMore();
        if (err != OK) {
            mImpl->signalEOS(err);
        }
    }

    return mImpl->read(out, options);
}

////////////////////////////////////////////////////////////////////////////////

MPEG2TSExtractor::MPEG2TSExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mParser(new ATSParser),
      mOffset(0) {
    init();
}

size_t MPEG2TSExtractor::countTracks() {
    return mSourceImpls.size();
}

sp<MediaSource> MPEG2TSExtractor::getTrack(size_t index) {
    if (index >= mSourceImpls.size()) {
        return NULL;
    }

    return new MPEG2TSSource(this, mSourceImpls.editItemAt(index));
}

sp<MetaData> MPEG2TSExtractor::getTrackMetaData(
        size_t index, uint32_t flags) {
    return index < mSourceImpls.size()
        ? mSourceImpls.editItemAt(index)->getFormat() : NULL;
}

sp<MetaData> MPEG2TSExtractor::getMetaData() {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_CONTAINER_MPEG2TS);

    return meta;
}

void MPEG2TSExtractor::init() {
    bool haveAudio = false;
    bool haveVideo = false;

    while (feedMore() == OK) {
        ATSParser::SourceType type;
        if (haveAudio && haveVideo) {
            break;
        }
        if (haveVideo) {
            type = ATSParser::MPEG2ADTS_AUDIO;
        } else {
            type = ATSParser::AVC_VIDEO;
        }
        sp<AnotherPacketSource> impl =
            (AnotherPacketSource *)mParser->getSource(type).get();

        if (impl != NULL) {
            if (type == ATSParser::MPEG2ADTS_AUDIO) {
                haveAudio = true;
            } else {
                haveVideo = true;
            }
            mSourceImpls.push(impl);
        }
    }

    LOGI("haveAudio=%d, haveVideo=%d", haveAudio, haveVideo);
}

status_t MPEG2TSExtractor::feedMore() {
    Mutex::Autolock autoLock(mLock);

    static const size_t kTSPacketSize = 188;

    uint8_t packet[kTSPacketSize];
    ssize_t n = mDataSource->readAt(mOffset, packet, kTSPacketSize);

    if (n < (ssize_t)kTSPacketSize) {
        return (n < 0) ? (status_t)n : ERROR_END_OF_STREAM;
    }

    mOffset += kTSPacketSize;
    mParser->feedTSPacket(packet, kTSPacketSize);

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

bool SniffMPEG2TS(
        const sp<DataSource> &source, String8 *mimeType, float *confidence) {
#if 0
    char header;
    if (source->readAt(0, &header, 1) != 1 || header != 0x47) {
        return false;
    }

    *confidence = 0.05f;
    mimeType->setTo(MEDIA_MIMETYPE_CONTAINER_MPEG2TS);

    return true;
#else
    // For now we're going to never identify this type of stream, since we'd
    // just base our decision on a single byte...
    // Instead you can instantiate an MPEG2TSExtractor by explicitly stating
    // its proper mime type in the call to MediaExtractor::Create(...).
    return false;
#endif
}

}  // namespace android
