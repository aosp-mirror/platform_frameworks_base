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

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
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

#include <ctype.h>
static void hexdump(const void *_data, size_t size) {
    const uint8_t *data = (const uint8_t *)_data;
    size_t offset = 0;
    while (offset < size) {
        printf("0x%04x  ", offset);

        size_t n = size - offset;
        if (n > 16) {
            n = 16;
        }

        for (size_t i = 0; i < 16; ++i) {
            if (i == 8) {
                printf(" ");
            }

            if (offset + i < size) {
                printf("%02x ", data[offset + i]);
            } else {
                printf("   ");
            }
        }

        printf(" ");

        for (size_t i = 0; i < n; ++i) {
            if (isprint(data[offset + i])) {
                printf("%c", data[offset + i]);
            } else {
                printf(".");
            }
        }

        printf("\n");

        offset += 16;
    }
}

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

    status_t advance();

    MatroskaSource(const MatroskaSource &);
    MatroskaSource &operator=(const MatroskaSource &);
};

MatroskaSource::MatroskaSource(
        const sp<MatroskaExtractor> &extractor, size_t index)
    : mExtractor(extractor),
      mTrackIndex(index),
      mType(OTHER),
      mBlockIter(mExtractor->mSegment,
                 mExtractor->mTracks.itemAt(index).mTrackNum) {
    const char *mime;
    CHECK(mExtractor->mTracks.itemAt(index).mMeta->
            findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        mType = AVC;
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        mType = AAC;
    }
}

status_t MatroskaSource::start(MetaData *params) {
    mBlockIter.reset();

    return OK;
}

status_t MatroskaSource::stop() {
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
    mCluster = mSegment->GetCluster(seekTimeUs * 1000ll);
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

status_t MatroskaSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        mBlockIter.seek(seekTimeUs);
    }

    if (mBlockIter.eos()) {
        return ERROR_END_OF_STREAM;
    }

    const mkvparser::Block *block = mBlockIter.block();
    size_t size = block->GetSize();
    int64_t timeUs = mBlockIter.blockTimeUs();

    MediaBuffer *buffer = new MediaBuffer(size + 2);
    buffer->meta_data()->setInt64(kKeyTime, timeUs);
    buffer->meta_data()->setInt32(kKeyIsSyncFrame, block->IsKey());

    long res = block->Read(
            mExtractor->mReader, (unsigned char *)buffer->data() + 2);

    if (res != 0) {
        return ERROR_END_OF_STREAM;
    }

    buffer->set_range(2, size);

    if (mType == AVC) {
        CHECK(size >= 2);

        uint8_t *data = (uint8_t *)buffer->data();

        unsigned NALsize = data[2] << 8 | data[3];
        CHECK_EQ(size, NALsize + 2);

        memcpy(data, "\x00\x00\x00\x01", 4);
        buffer->set_range(0, size + 2);
    } else if (mType == AAC) {
        // There's strange junk at the beginning...

        const uint8_t *data = (const uint8_t *)buffer->data() + 2;
        size_t offset = 0;
        while (offset < size && data[offset] != 0x21) {
            ++offset;
        }
        buffer->set_range(2 + offset, size - offset);
    }

    *out = buffer;

#if 0
    hexdump((const uint8_t *)buffer->data() + buffer->range_offset(),
            buffer->range_length());
#endif

    mBlockIter.advance();

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
            track->GetCodecPrivate(&codecPrivateSize);

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
