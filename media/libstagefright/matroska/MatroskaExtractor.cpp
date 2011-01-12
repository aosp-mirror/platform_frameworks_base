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
#define LOG_TAG "MatroskaExtractor"
#include <utils/Log.h>

#include "MatroskaExtractor.h"

#include "mkvparser.hpp"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <utils/String8.h>

namespace android {

struct DataSourceReader : public mkvparser::IMkvReader {
    DataSourceReader(const sp<DataSource> &source)
        : mSource(source) {
    }

    virtual int Read(long long position, long length, unsigned char* buffer) {
        CHECK(position >= 0);
        CHECK(length >= 0);

        if (length == 0) {
            return 0;
        }

        ssize_t n = mSource->readAt(position, buffer, length);

        if (n <= 0) {
            return -1;
        }

        return 0;
    }

    virtual int Length(long long* total, long long* available) {
        off_t size;
        if (mSource->getSize(&size) != OK) {
            return -1;
        }

        if (total) {
            *total = size;
        }

        if (available) {
            *available = size;
        }

        return 0;
    }

private:
    sp<DataSource> mSource;

    DataSourceReader(const DataSourceReader &);
    DataSourceReader &operator=(const DataSourceReader &);
};

////////////////////////////////////////////////////////////////////////////////

struct BlockIterator {
    BlockIterator(mkvparser::Segment *segment, unsigned long trackNum);

    bool eos() const;

    void advance();
    void reset();
    void seek(int64_t seekTimeUs);

    const mkvparser::Block *block() const;
    int64_t blockTimeUs() const;

private:
    mkvparser::Segment *mSegment;
    unsigned long mTrackNum;

    mkvparser::Cluster *mCluster;
    const mkvparser::BlockEntry *mBlockEntry;

    BlockIterator(const BlockIterator &);
    BlockIterator &operator=(const BlockIterator &);
};

struct MatroskaSource : public MediaSource {
    MatroskaSource(
            const sp<MatroskaExtractor> &extractor, size_t index);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

protected:
    virtual ~MatroskaSource();

private:
    enum Type {
        AVC,
        AAC,
        OTHER
    };

    sp<MatroskaExtractor> mExtractor;
    size_t mTrackIndex;
    Type mType;
    BlockIterator mBlockIter;
    size_t mNALSizeLen;  // for type AVC

    List<MediaBuffer *> mPendingFrames;

    status_t advance();

    status_t readBlock();
    void clearPendingFrames();

    MatroskaSource(const MatroskaSource &);
    MatroskaSource &operator=(const MatroskaSource &);
};

MatroskaSource::MatroskaSource(
        const sp<MatroskaExtractor> &extractor, size_t index)
    : mExtractor(extractor),
      mTrackIndex(index),
      mType(OTHER),
      mBlockIter(mExtractor->mSegment,
                 mExtractor->mTracks.itemAt(index).mTrackNum),
      mNALSizeLen(0) {
    sp<MetaData> meta = mExtractor->mTracks.itemAt(index).mMeta;

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        mType = AVC;

        uint32_t dummy;
        const uint8_t *avcc;
        size_t avccSize;
        CHECK(meta->findData(
                    kKeyAVCC, &dummy, (const void **)&avcc, &avccSize));

        CHECK_GE(avccSize, 5u);

        mNALSizeLen = 1 + (avcc[4] & 3);
        LOGV("mNALSizeLen = %d", mNALSizeLen);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        mType = AAC;
    }
}

MatroskaSource::~MatroskaSource() {
    clearPendingFrames();
}

status_t MatroskaSource::start(MetaData *params) {
    mBlockIter.reset();

    return OK;
}

status_t MatroskaSource::stop() {
    clearPendingFrames();

    return OK;
}

sp<MetaData> MatroskaSource::getFormat() {
    return mExtractor->mTracks.itemAt(mTrackIndex).mMeta;
}

////////////////////////////////////////////////////////////////////////////////

BlockIterator::BlockIterator(
        mkvparser::Segment *segment, unsigned long trackNum)
    : mSegment(segment),
      mTrackNum(trackNum),
      mCluster(NULL),
      mBlockEntry(NULL) {
    reset();
}

bool BlockIterator::eos() const {
    return mCluster == NULL || mCluster->EOS();
}

void BlockIterator::advance() {
    while (!eos()) {
        if (mBlockEntry != NULL) {
            mBlockEntry = mCluster->GetNext(mBlockEntry);
        } else if (mCluster != NULL) {
            mCluster = mSegment->GetNext(mCluster);

            if (eos()) {
                break;
            }

            mBlockEntry = mCluster->GetFirst();
        }

        if (mBlockEntry != NULL
                && mBlockEntry->GetBlock()->GetTrackNumber() == mTrackNum) {
            break;
        }
    }
}

void BlockIterator::reset() {
    mCluster = mSegment->GetFirst();
    mBlockEntry = mCluster->GetFirst();

    while (!eos() && block()->GetTrackNumber() != mTrackNum) {
        advance();
    }
}

void BlockIterator::seek(int64_t seekTimeUs) {
    mCluster = mSegment->FindCluster(seekTimeUs * 1000ll);
    mBlockEntry = mCluster != NULL ? mCluster->GetFirst() : NULL;

    while (!eos() && block()->GetTrackNumber() != mTrackNum) {
        advance();
    }

    while (!eos() && !mBlockEntry->GetBlock()->IsKey()) {
        advance();
    }
}

const mkvparser::Block *BlockIterator::block() const {
    CHECK(!eos());

    return mBlockEntry->GetBlock();
}

int64_t BlockIterator::blockTimeUs() const {
    return (mBlockEntry->GetBlock()->GetTime(mCluster) + 500ll) / 1000ll;
}

////////////////////////////////////////////////////////////////////////////////

static unsigned U24_AT(const uint8_t *ptr) {
    return ptr[0] << 16 | ptr[1] << 8 | ptr[2];
}

static size_t clz(uint8_t x) {
    size_t numLeadingZeroes = 0;

    while (!(x & 0x80)) {
        ++numLeadingZeroes;
        x = x << 1;
    }

    return numLeadingZeroes;
}

void MatroskaSource::clearPendingFrames() {
    while (!mPendingFrames.empty()) {
        MediaBuffer *frame = *mPendingFrames.begin();
        mPendingFrames.erase(mPendingFrames.begin());

        frame->release();
        frame = NULL;
    }
}

#define BAIL(err) \
    do {                        \
        if (bigbuf) {           \
            bigbuf->release();  \
            bigbuf = NULL;      \
        }                       \
                                \
        return err;             \
    } while (0)

status_t MatroskaSource::readBlock() {
    CHECK(mPendingFrames.empty());

    if (mBlockIter.eos()) {
        return ERROR_END_OF_STREAM;
    }

    const mkvparser::Block *block = mBlockIter.block();

    size_t size = block->GetSize();
    int64_t timeUs = mBlockIter.blockTimeUs();
    int32_t isSync = block->IsKey();

    MediaBuffer *bigbuf = new MediaBuffer(size);

    long res = block->Read(
            mExtractor->mReader, (unsigned char *)bigbuf->data());

    if (res != 0) {
        bigbuf->release();
        bigbuf = NULL;

        return ERROR_END_OF_STREAM;
    }

    mBlockIter.advance();

    bigbuf->meta_data()->setInt64(kKeyTime, timeUs);
    bigbuf->meta_data()->setInt32(kKeyIsSyncFrame, isSync);

    unsigned lacing = (block->Flags() >> 1) & 3;

    if (lacing == 0) {
        mPendingFrames.push_back(bigbuf);
        return OK;
    }

    LOGV("lacing = %u, size = %d", lacing, size);

    const uint8_t *data = (const uint8_t *)bigbuf->data();
    // hexdump(data, size);

    if (size == 0) {
        BAIL(ERROR_MALFORMED);
    }

    unsigned numFrames = (unsigned)data[0] + 1;
    ++data;
    --size;

    Vector<uint64_t> frameSizes;

    switch (lacing) {
        case 1:  // Xiph
        {
            for (size_t i = 0; i < numFrames - 1; ++i) {
                size_t frameSize = 0;
                uint8_t byte;
                do {
                    if (size == 0) {
                        BAIL(ERROR_MALFORMED);
                    }
                    byte = data[0];
                    ++data;
                    --size;

                    frameSize += byte;
                } while (byte == 0xff);

                frameSizes.push(frameSize);
            }

            break;
        }

        case 2:  // fixed-size
        {
            if ((size % numFrames) != 0) {
                BAIL(ERROR_MALFORMED);
            }

            size_t frameSize = size / numFrames;
            for (size_t i = 0; i < numFrames - 1; ++i) {
                frameSizes.push(frameSize);
            }

            break;
        }

        case 3:  // EBML
        {
            uint64_t lastFrameSize = 0;
            for (size_t i = 0; i < numFrames - 1; ++i) {
                uint8_t byte;

                if (size == 0) {
                    BAIL(ERROR_MALFORMED);
                }
                byte = data[0];
                ++data;
                --size;

                size_t numLeadingZeroes = clz(byte);

                uint64_t frameSize = byte & ~(0x80 >> numLeadingZeroes);
                for (size_t j = 0; j < numLeadingZeroes; ++j) {
                    if (size == 0) {
                        BAIL(ERROR_MALFORMED);
                    }

                    frameSize = frameSize << 8;
                    frameSize |= data[0];
                    ++data;
                    --size;
                }

                if (i == 0) {
                    frameSizes.push(frameSize);
                } else {
                    size_t shift =
                        7 - numLeadingZeroes + 8 * numLeadingZeroes;

                    int64_t delta =
                        (int64_t)frameSize - (1ll << (shift - 1)) + 1;

                    frameSize = lastFrameSize + delta;

                    frameSizes.push(frameSize);
                }

                lastFrameSize = frameSize;
            }
            break;
        }

        default:
            TRESPASS();
    }

#if 0
    AString out;
    for (size_t i = 0; i < frameSizes.size(); ++i) {
        if (i > 0) {
            out.append(", ");
        }
        out.append(StringPrintf("%llu", frameSizes.itemAt(i)));
    }
    LOGV("sizes = [%s]", out.c_str());
#endif

    for (size_t i = 0; i < frameSizes.size(); ++i) {
        uint64_t frameSize = frameSizes.itemAt(i);

        if (size < frameSize) {
            BAIL(ERROR_MALFORMED);
        }

        MediaBuffer *mbuf = new MediaBuffer(frameSize);
        mbuf->meta_data()->setInt64(kKeyTime, timeUs);
        mbuf->meta_data()->setInt32(kKeyIsSyncFrame, isSync);
        memcpy(mbuf->data(), data, frameSize);
        mPendingFrames.push_back(mbuf);

        data += frameSize;
        size -= frameSize;
    }

    size_t offset = bigbuf->range_length() - size;
    bigbuf->set_range(offset, size);

    mPendingFrames.push_back(bigbuf);

    return OK;
}

#undef BAIL

status_t MatroskaSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        clearPendingFrames();
        mBlockIter.seek(seekTimeUs);
    }

again:
    while (mPendingFrames.empty()) {
        status_t err = readBlock();

        if (err != OK) {
            clearPendingFrames();

            return err;
        }
    }

    MediaBuffer *frame = *mPendingFrames.begin();
    mPendingFrames.erase(mPendingFrames.begin());

    size_t size = frame->range_length();

    if (mType != AVC) {
        *out = frame;

        return OK;
    }

    if (size < mNALSizeLen) {
        frame->release();
        frame = NULL;

        return ERROR_MALFORMED;
    }

    // In the case of AVC content, each NAL unit is prefixed by
    // mNALSizeLen bytes of length. We want to prefix the data with
    // a four-byte 0x00000001 startcode instead of the length prefix.
    // mNALSizeLen ranges from 1 through 4 bytes, so add an extra
    // 3 bytes of padding to the buffer start.
    static const size_t kPadding = 3;

    MediaBuffer *buffer = new MediaBuffer(size + kPadding);

    int64_t timeUs;
    CHECK(frame->meta_data()->findInt64(kKeyTime, &timeUs));
    int32_t isSync;
    CHECK(frame->meta_data()->findInt32(kKeyIsSyncFrame, &isSync));

    buffer->meta_data()->setInt64(kKeyTime, timeUs);
    buffer->meta_data()->setInt32(kKeyIsSyncFrame, isSync);

    memcpy((uint8_t *)buffer->data() + kPadding,
           (const uint8_t *)frame->data() + frame->range_offset(),
           size);

    buffer->set_range(kPadding, size);

    frame->release();
    frame = NULL;

    uint8_t *data = (uint8_t *)buffer->data();

    size_t NALsize;
    switch (mNALSizeLen) {
        case 1: NALsize = data[kPadding]; break;
        case 2: NALsize = U16_AT(&data[kPadding]); break;
        case 3: NALsize = U24_AT(&data[kPadding]); break;
        case 4: NALsize = U32_AT(&data[kPadding]); break;
        default:
            TRESPASS();
    }

    if (size < NALsize + mNALSizeLen) {
        buffer->release();
        buffer = NULL;

        return ERROR_MALFORMED;
    }

    if (size > NALsize + mNALSizeLen) {
        LOGW("discarding %d bytes of data.", size - NALsize - mNALSizeLen);
    }

    // actual data starts at &data[kPadding + mNALSizeLen]

    memcpy(&data[mNALSizeLen - 1], "\x00\x00\x00\x01", 4);
    buffer->set_range(mNALSizeLen - 1, NALsize + 4);

    *out = buffer;

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

MatroskaExtractor::MatroskaExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mReader(new DataSourceReader(mDataSource)),
      mSegment(NULL),
      mExtractedThumbnails(false) {
    mkvparser::EBMLHeader ebmlHeader;
    long long pos;
    if (ebmlHeader.Parse(mReader, pos) < 0) {
        return;
    }

    long long ret =
        mkvparser::Segment::CreateInstance(mReader, pos, mSegment);

    if (ret) {
        CHECK(mSegment == NULL);
        return;
    }

    ret = mSegment->Load();

    if (ret < 0) {
        delete mSegment;
        mSegment = NULL;
        return;
    }

    addTracks();
}

MatroskaExtractor::~MatroskaExtractor() {
    delete mSegment;
    mSegment = NULL;

    delete mReader;
    mReader = NULL;
}

size_t MatroskaExtractor::countTracks() {
    return mTracks.size();
}

sp<MediaSource> MatroskaExtractor::getTrack(size_t index) {
    if (index >= mTracks.size()) {
        return NULL;
    }

    return new MatroskaSource(this, index);
}

sp<MetaData> MatroskaExtractor::getTrackMetaData(
        size_t index, uint32_t flags) {
    if (index >= mTracks.size()) {
        return NULL;
    }

    if ((flags & kIncludeExtensiveMetaData) && !mExtractedThumbnails) {
        findThumbnails();
        mExtractedThumbnails = true;
    }

    return mTracks.itemAt(index).mMeta;
}

static void addESDSFromAudioSpecificInfo(
        const sp<MetaData> &meta, const void *asi, size_t asiSize) {
    static const uint8_t kStaticESDS[] = {
        0x03, 22,
        0x00, 0x00,     // ES_ID
        0x00,           // streamDependenceFlag, URL_Flag, OCRstreamFlag

        0x04, 17,
        0x40,                       // Audio ISO/IEC 14496-3
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,

        0x05,
        // AudioSpecificInfo (with size prefix) follows
    };

    CHECK(asiSize < 128);
    size_t esdsSize = sizeof(kStaticESDS) + asiSize + 1;
    uint8_t *esds = new uint8_t[esdsSize];
    memcpy(esds, kStaticESDS, sizeof(kStaticESDS));
    uint8_t *ptr = esds + sizeof(kStaticESDS);
    *ptr++ = asiSize;
    memcpy(ptr, asi, asiSize);

    meta->setData(kKeyESDS, 0, esds, esdsSize);

    delete[] esds;
    esds = NULL;
}

void addVorbisCodecInfo(
        const sp<MetaData> &meta,
        const void *_codecPrivate, size_t codecPrivateSize) {
    // printf("vorbis private data follows:\n");
    // hexdump(_codecPrivate, codecPrivateSize);

    CHECK(codecPrivateSize >= 3);

    const uint8_t *codecPrivate = (const uint8_t *)_codecPrivate;
    CHECK(codecPrivate[0] == 0x02);

    size_t len1 = codecPrivate[1];
    size_t len2 = codecPrivate[2];

    CHECK(codecPrivateSize > 3 + len1 + len2);

    CHECK(codecPrivate[3] == 0x01);
    meta->setData(kKeyVorbisInfo, 0, &codecPrivate[3], len1);

    CHECK(codecPrivate[len1 + 3] == 0x03);

    CHECK(codecPrivate[len1 + len2 + 3] == 0x05);
    meta->setData(
            kKeyVorbisBooks, 0, &codecPrivate[len1 + len2 + 3],
            codecPrivateSize - len1 - len2 - 3);
}

void MatroskaExtractor::addTracks() {
    const mkvparser::Tracks *tracks = mSegment->GetTracks();

    for (size_t index = 0; index < tracks->GetTracksCount(); ++index) {
        const mkvparser::Track *track = tracks->GetTrackByIndex(index);

        const char *const codecID = track->GetCodecId();
        LOGV("codec id = %s", codecID);
        LOGV("codec name = %s", track->GetCodecNameAsUTF8());

        size_t codecPrivateSize;
        const unsigned char *codecPrivate =
            track->GetCodecPrivate(codecPrivateSize);

        enum { VIDEO_TRACK = 1, AUDIO_TRACK = 2 };

        sp<MetaData> meta = new MetaData;

        switch (track->GetType()) {
            case VIDEO_TRACK:
            {
                const mkvparser::VideoTrack *vtrack =
                    static_cast<const mkvparser::VideoTrack *>(track);

                if (!strcmp("V_MPEG4/ISO/AVC", codecID)) {
                    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
                    meta->setData(kKeyAVCC, 0, codecPrivate, codecPrivateSize);
                } else if (!strcmp("V_VP8", codecID)) {
                    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_VPX);
                } else {
                    continue;
                }

                meta->setInt32(kKeyWidth, vtrack->GetWidth());
                meta->setInt32(kKeyHeight, vtrack->GetHeight());
                break;
            }

            case AUDIO_TRACK:
            {
                const mkvparser::AudioTrack *atrack =
                    static_cast<const mkvparser::AudioTrack *>(track);

                if (!strcmp("A_AAC", codecID)) {
                    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC);
                    CHECK(codecPrivateSize >= 2);

                    addESDSFromAudioSpecificInfo(
                            meta, codecPrivate, codecPrivateSize);
                } else if (!strcmp("A_VORBIS", codecID)) {
                    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_VORBIS);

                    addVorbisCodecInfo(meta, codecPrivate, codecPrivateSize);
                } else {
                    continue;
                }

                meta->setInt32(kKeySampleRate, atrack->GetSamplingRate());
                meta->setInt32(kKeyChannelCount, atrack->GetChannels());
                break;
            }

            default:
                continue;
        }

        long long durationNs = mSegment->GetDuration();
        meta->setInt64(kKeyDuration, (durationNs + 500) / 1000);

        mTracks.push();
        TrackInfo *trackInfo = &mTracks.editItemAt(mTracks.size() - 1);
        trackInfo->mTrackNum = track->GetNumber();
        trackInfo->mMeta = meta;
    }
}

void MatroskaExtractor::findThumbnails() {
    for (size_t i = 0; i < mTracks.size(); ++i) {
        TrackInfo *info = &mTracks.editItemAt(i);

        const char *mime;
        CHECK(info->mMeta->findCString(kKeyMIMEType, &mime));

        if (strncasecmp(mime, "video/", 6)) {
            continue;
        }

        BlockIterator iter(mSegment, info->mTrackNum);
        int32_t i = 0;
        int64_t thumbnailTimeUs = 0;
        size_t maxBlockSize = 0;
        while (!iter.eos() && i < 20) {
            if (iter.block()->IsKey()) {
                ++i;

                size_t blockSize = iter.block()->GetSize();
                if (blockSize > maxBlockSize) {
                    maxBlockSize = blockSize;
                    thumbnailTimeUs = iter.blockTimeUs();
                }
            }
            iter.advance();
        }
        info->mMeta->setInt64(kKeyThumbnailTime, thumbnailTimeUs);
    }
}

sp<MetaData> MatroskaExtractor::getMetaData() {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_CONTAINER_MATROSKA);

    return meta;
}

bool SniffMatroska(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {
    DataSourceReader reader(source);
    mkvparser::EBMLHeader ebmlHeader;
    long long pos;
    if (ebmlHeader.Parse(&reader, pos) < 0) {
        return false;
    }

    mimeType->setTo(MEDIA_MIMETYPE_CONTAINER_MATROSKA);
    *confidence = 0.6;

    return true;
}

}  // namespace android
