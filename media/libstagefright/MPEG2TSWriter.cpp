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
#define LOG_TAG "MPEG2TSWriter"
#include <media/stagefright/foundation/ADebug.h>

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MPEG2TSWriter.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

#include "include/ESDS.h"

namespace android {

struct MPEG2TSWriter::SourceInfo : public AHandler {
    SourceInfo(const sp<MediaSource> &source);

    void start(const sp<AMessage> &notify);
    void stop();

    unsigned streamType() const;
    unsigned incrementContinuityCounter();

    void readMore();

    enum {
        kNotifyStartFailed,
        kNotifyBuffer,
        kNotifyReachedEOS,
    };

    sp<ABuffer> lastAccessUnit();
    int64_t lastAccessUnitTimeUs();
    void setLastAccessUnit(const sp<ABuffer> &accessUnit);

    void setEOSReceived();
    bool eosReceived() const;

protected:
    virtual void onMessageReceived(const sp<AMessage> &msg);

    virtual ~SourceInfo();

private:
    enum {
        kWhatStart = 'strt',
        kWhatRead  = 'read',
    };

    sp<MediaSource> mSource;
    sp<ALooper> mLooper;
    sp<AMessage> mNotify;

    sp<ABuffer> mAACCodecSpecificData;

    sp<ABuffer> mAACBuffer;

    sp<ABuffer> mLastAccessUnit;
    bool mEOSReceived;

    unsigned mStreamType;
    unsigned mContinuityCounter;

    void extractCodecSpecificData();

    bool appendAACFrames(MediaBuffer *buffer);
    bool flushAACFrames();

    void postAVCFrame(MediaBuffer *buffer);

    DISALLOW_EVIL_CONSTRUCTORS(SourceInfo);
};

MPEG2TSWriter::SourceInfo::SourceInfo(const sp<MediaSource> &source)
    : mSource(source),
      mLooper(new ALooper),
      mEOSReceived(false),
      mStreamType(0),
      mContinuityCounter(0) {
    mLooper->setName("MPEG2TSWriter source");

    sp<MetaData> meta = mSource->getFormat();
    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        mStreamType = 0x0f;
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        mStreamType = 0x1b;
    } else {
        TRESPASS();
    }
}

MPEG2TSWriter::SourceInfo::~SourceInfo() {
}

unsigned MPEG2TSWriter::SourceInfo::streamType() const {
    return mStreamType;
}

unsigned MPEG2TSWriter::SourceInfo::incrementContinuityCounter() {
    if (++mContinuityCounter == 16) {
        mContinuityCounter = 0;
    }

    return mContinuityCounter;
}

void MPEG2TSWriter::SourceInfo::start(const sp<AMessage> &notify) {
    mLooper->registerHandler(this);
    mLooper->start();

    mNotify = notify;

    (new AMessage(kWhatStart, id()))->post();
}

void MPEG2TSWriter::SourceInfo::stop() {
    mLooper->unregisterHandler(id());
    mLooper->stop();

    mSource->stop();
}

void MPEG2TSWriter::SourceInfo::extractCodecSpecificData() {
    sp<MetaData> meta = mSource->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        uint32_t type;
        const void *data;
        size_t size;
        if (!meta->findData(kKeyESDS, &type, &data, &size)) {
            // Codec specific data better be in the first data buffer.
            return;
        }

        ESDS esds((const char *)data, size);
        CHECK_EQ(esds.InitCheck(), (status_t)OK);

        const uint8_t *codec_specific_data;
        size_t codec_specific_data_size;
        esds.getCodecSpecificInfo(
                (const void **)&codec_specific_data, &codec_specific_data_size);

        CHECK_GE(codec_specific_data_size, 2u);

        mAACCodecSpecificData = new ABuffer(codec_specific_data_size);

        memcpy(mAACCodecSpecificData->data(), codec_specific_data,
               codec_specific_data_size);

        return;
    }

    if (strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        return;
    }

    uint32_t type;
    const void *data;
    size_t size;
    if (!meta->findData(kKeyAVCC, &type, &data, &size)) {
        // Codec specific data better be part of the data stream then.
        return;
    }

    sp<ABuffer> out = new ABuffer(1024);
    out->setRange(0, 0);

    const uint8_t *ptr = (const uint8_t *)data;

    size_t numSeqParameterSets = ptr[5] & 31;

    ptr += 6;
    size -= 6;

    for (size_t i = 0; i < numSeqParameterSets; ++i) {
        CHECK(size >= 2);
        size_t length = U16_AT(ptr);

        ptr += 2;
        size -= 2;

        CHECK(size >= length);

        CHECK_LE(out->size() + 4 + length, out->capacity());
        memcpy(out->data() + out->size(), "\x00\x00\x00\x01", 4);
        memcpy(out->data() + out->size() + 4, ptr, length);
        out->setRange(0, out->size() + length + 4);

        ptr += length;
        size -= length;
    }

    CHECK(size >= 1);
    size_t numPictureParameterSets = *ptr;
    ++ptr;
    --size;

    for (size_t i = 0; i < numPictureParameterSets; ++i) {
        CHECK(size >= 2);
        size_t length = U16_AT(ptr);

        ptr += 2;
        size -= 2;

        CHECK(size >= length);

        CHECK_LE(out->size() + 4 + length, out->capacity());
        memcpy(out->data() + out->size(), "\x00\x00\x00\x01", 4);
        memcpy(out->data() + out->size() + 4, ptr, length);
        out->setRange(0, out->size() + length + 4);

        ptr += length;
        size -= length;
    }

    out->meta()->setInt64("timeUs", 0ll);

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kNotifyBuffer);
    notify->setObject("buffer", out);
    notify->setInt32("oob", true);
    notify->post();
}

void MPEG2TSWriter::SourceInfo::postAVCFrame(MediaBuffer *buffer) {
    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kNotifyBuffer);

    sp<ABuffer> copy =
        new ABuffer(buffer->range_length());
    memcpy(copy->data(),
           (const uint8_t *)buffer->data()
            + buffer->range_offset(),
           buffer->range_length());

    int64_t timeUs;
    CHECK(buffer->meta_data()->findInt64(kKeyTime, &timeUs));
    copy->meta()->setInt64("timeUs", timeUs);

    int32_t isSync;
    if (buffer->meta_data()->findInt32(kKeyIsSyncFrame, &isSync)
            && isSync != 0) {
        copy->meta()->setInt32("isSync", true);
    }

    notify->setObject("buffer", copy);
    notify->post();
}

bool MPEG2TSWriter::SourceInfo::appendAACFrames(MediaBuffer *buffer) {
    bool accessUnitPosted = false;

    if (mAACBuffer != NULL
            && mAACBuffer->size() + 7 + buffer->range_length()
                    > mAACBuffer->capacity()) {
        accessUnitPosted = flushAACFrames();
    }

    if (mAACBuffer == NULL) {
        size_t alloc = 4096;
        if (buffer->range_length() + 7 > alloc) {
            alloc = 7 + buffer->range_length();
        }

        mAACBuffer = new ABuffer(alloc);

        int64_t timeUs;
        CHECK(buffer->meta_data()->findInt64(kKeyTime, &timeUs));

        mAACBuffer->meta()->setInt64("timeUs", timeUs);
        mAACBuffer->meta()->setInt32("isSync", true);

        mAACBuffer->setRange(0, 0);
    }

    const uint8_t *codec_specific_data = mAACCodecSpecificData->data();

    unsigned profile = (codec_specific_data[0] >> 3) - 1;

    unsigned sampling_freq_index =
        ((codec_specific_data[0] & 7) << 1)
        | (codec_specific_data[1] >> 7);

    unsigned channel_configuration =
        (codec_specific_data[1] >> 3) & 0x0f;

    uint8_t *ptr = mAACBuffer->data() + mAACBuffer->size();

    const uint32_t aac_frame_length = buffer->range_length() + 7;

    *ptr++ = 0xff;
    *ptr++ = 0xf1;  // b11110001, ID=0, layer=0, protection_absent=1

    *ptr++ =
        profile << 6
        | sampling_freq_index << 2
        | ((channel_configuration >> 2) & 1);  // private_bit=0

    // original_copy=0, home=0, copyright_id_bit=0, copyright_id_start=0
    *ptr++ =
        (channel_configuration & 3) << 6
        | aac_frame_length >> 11;
    *ptr++ = (aac_frame_length >> 3) & 0xff;
    *ptr++ = (aac_frame_length & 7) << 5;

    // adts_buffer_fullness=0, number_of_raw_data_blocks_in_frame=0
    *ptr++ = 0;

    memcpy(ptr,
           (const uint8_t *)buffer->data() + buffer->range_offset(),
           buffer->range_length());

    ptr += buffer->range_length();

    mAACBuffer->setRange(0, ptr - mAACBuffer->data());

    return accessUnitPosted;
}

bool MPEG2TSWriter::SourceInfo::flushAACFrames() {
    if (mAACBuffer == NULL) {
        return false;
    }

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("what", kNotifyBuffer);
    notify->setObject("buffer", mAACBuffer);
    notify->post();

    mAACBuffer.clear();

    return true;
}

void MPEG2TSWriter::SourceInfo::readMore() {
    (new AMessage(kWhatRead, id()))->post();
}

void MPEG2TSWriter::SourceInfo::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatStart:
        {
            status_t err = mSource->start();
            if (err != OK) {
                sp<AMessage> notify = mNotify->dup();
                notify->setInt32("what", kNotifyStartFailed);
                notify->post();
                break;
            }

            extractCodecSpecificData();

            readMore();
            break;
        }

        case kWhatRead:
        {
            MediaBuffer *buffer;
            status_t err = mSource->read(&buffer);

            if (err != OK && err != INFO_FORMAT_CHANGED) {
                if (mStreamType == 0x0f) {
                    flushAACFrames();
                }

                sp<AMessage> notify = mNotify->dup();
                notify->setInt32("what", kNotifyReachedEOS);
                notify->setInt32("status", err);
                notify->post();
                break;
            }

            if (err == OK) {
                if (mStreamType == 0x0f && mAACCodecSpecificData == NULL) {
                    // The first buffer contains codec specific data.

                    CHECK_GE(buffer->range_length(), 2u);

                    mAACCodecSpecificData = new ABuffer(buffer->range_length());

                    memcpy(mAACCodecSpecificData->data(),
                           (const uint8_t *)buffer->data()
                            + buffer->range_offset(),
                           buffer->range_length());
                } else if (buffer->range_length() > 0) {
                    if (mStreamType == 0x0f) {
                        if (!appendAACFrames(buffer)) {
                            msg->post();
                        }
                    } else {
                        postAVCFrame(buffer);
                    }
                }

                buffer->release();
                buffer = NULL;
            }

            // Do not read more data until told to.
            break;
        }

        default:
            TRESPASS();
    }
}

sp<ABuffer> MPEG2TSWriter::SourceInfo::lastAccessUnit() {
    return mLastAccessUnit;
}

void MPEG2TSWriter::SourceInfo::setLastAccessUnit(
        const sp<ABuffer> &accessUnit) {
    mLastAccessUnit = accessUnit;
}

int64_t MPEG2TSWriter::SourceInfo::lastAccessUnitTimeUs() {
    if (mLastAccessUnit == NULL) {
        return -1;
    }

    int64_t timeUs;
    CHECK(mLastAccessUnit->meta()->findInt64("timeUs", &timeUs));

    return timeUs;
}

void MPEG2TSWriter::SourceInfo::setEOSReceived() {
    CHECK(!mEOSReceived);
    mEOSReceived = true;
}

bool MPEG2TSWriter::SourceInfo::eosReceived() const {
    return mEOSReceived;
}

////////////////////////////////////////////////////////////////////////////////

MPEG2TSWriter::MPEG2TSWriter(int fd)
    : mFile(fdopen(dup(fd), "wb")),
      mWriteCookie(NULL),
      mWriteFunc(NULL),
      mStarted(false),
      mNumSourcesDone(0),
      mNumTSPacketsWritten(0),
      mNumTSPacketsBeforeMeta(0) {
    init();
}

MPEG2TSWriter::MPEG2TSWriter(const char *filename)
    : mFile(fopen(filename, "wb")),
      mWriteCookie(NULL),
      mWriteFunc(NULL),
      mStarted(false),
      mNumSourcesDone(0),
      mNumTSPacketsWritten(0),
      mNumTSPacketsBeforeMeta(0) {
    init();
}

MPEG2TSWriter::MPEG2TSWriter(
        void *cookie,
        ssize_t (*write)(void *cookie, const void *data, size_t size))
    : mFile(NULL),
      mWriteCookie(cookie),
      mWriteFunc(write),
      mStarted(false),
      mNumSourcesDone(0),
      mNumTSPacketsWritten(0),
      mNumTSPacketsBeforeMeta(0) {
    init();
}

void MPEG2TSWriter::init() {
    CHECK(mFile != NULL || mWriteFunc != NULL);

    mLooper = new ALooper;
    mLooper->setName("MPEG2TSWriter");

    mReflector = new AHandlerReflector<MPEG2TSWriter>(this);

    mLooper->registerHandler(mReflector);
    mLooper->start();
}

MPEG2TSWriter::~MPEG2TSWriter() {
    if (mStarted) {
        stop();
    }

    mLooper->unregisterHandler(mReflector->id());
    mLooper->stop();

    if (mFile != NULL) {
        fclose(mFile);
        mFile = NULL;
    }
}

status_t MPEG2TSWriter::addSource(const sp<MediaSource> &source) {
    CHECK(!mStarted);

    sp<MetaData> meta = source->getFormat();
    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    if (strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)
            && strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        return ERROR_UNSUPPORTED;
    }

    sp<SourceInfo> info = new SourceInfo(source);

    mSources.push(info);

    return OK;
}

status_t MPEG2TSWriter::start(MetaData *param) {
    CHECK(!mStarted);

    mStarted = true;
    mNumSourcesDone = 0;
    mNumTSPacketsWritten = 0;
    mNumTSPacketsBeforeMeta = 0;

    for (size_t i = 0; i < mSources.size(); ++i) {
        sp<AMessage> notify =
            new AMessage(kWhatSourceNotify, mReflector->id());

        notify->setInt32("source-index", i);

        mSources.editItemAt(i)->start(notify);
    }

    return OK;
}

status_t MPEG2TSWriter::stop() {
    CHECK(mStarted);

    for (size_t i = 0; i < mSources.size(); ++i) {
        mSources.editItemAt(i)->stop();
    }
    mStarted = false;

    return OK;
}

status_t MPEG2TSWriter::pause() {
    CHECK(mStarted);

    return OK;
}

bool MPEG2TSWriter::reachedEOS() {
    return !mStarted || (mNumSourcesDone == mSources.size() ? true : false);
}

status_t MPEG2TSWriter::dump(int fd, const Vector<String16> &args) {
    return OK;
}

void MPEG2TSWriter::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatSourceNotify:
        {
            int32_t sourceIndex;
            CHECK(msg->findInt32("source-index", &sourceIndex));

            int32_t what;
            CHECK(msg->findInt32("what", &what));

            if (what == SourceInfo::kNotifyReachedEOS
                    || what == SourceInfo::kNotifyStartFailed) {
                sp<SourceInfo> source = mSources.editItemAt(sourceIndex);
                source->setEOSReceived();

                sp<ABuffer> buffer = source->lastAccessUnit();
                source->setLastAccessUnit(NULL);

                if (buffer != NULL) {
                    writeTS();
                    writeAccessUnit(sourceIndex, buffer);
                }

                ++mNumSourcesDone;
            } else if (what == SourceInfo::kNotifyBuffer) {
                sp<RefBase> obj;
                CHECK(msg->findObject("buffer", &obj));

                sp<ABuffer> buffer = static_cast<ABuffer *>(obj.get());

                int32_t oob;
                if (msg->findInt32("oob", &oob) && oob) {
                    // This is codec specific data delivered out of band.
                    // It can be written out immediately.
                    writeTS();
                    writeAccessUnit(sourceIndex, buffer);
                    break;
                }

                // We don't just write out data as we receive it from
                // the various sources. That would essentially write them
                // out in random order (as the thread scheduler determines
                // how the messages are dispatched).
                // Instead we gather an access unit for all tracks and
                // write out the one with the smallest timestamp, then
                // request more data for the written out track.
                // Rinse, repeat.
                // If we don't have data on any track we don't write
                // anything just yet.

                sp<SourceInfo> source = mSources.editItemAt(sourceIndex);

                CHECK(source->lastAccessUnit() == NULL);
                source->setLastAccessUnit(buffer);

                ALOGV("lastAccessUnitTimeUs[%d] = %.2f secs",
                     sourceIndex, source->lastAccessUnitTimeUs() / 1E6);

                int64_t minTimeUs = -1;
                size_t minIndex = 0;

                for (size_t i = 0; i < mSources.size(); ++i) {
                    const sp<SourceInfo> &source = mSources.editItemAt(i);

                    if (source->eosReceived()) {
                        continue;
                    }

                    int64_t timeUs = source->lastAccessUnitTimeUs();
                    if (timeUs < 0) {
                        minTimeUs = -1;
                        break;
                    } else if (minTimeUs < 0 || timeUs < minTimeUs) {
                        minTimeUs = timeUs;
                        minIndex = i;
                    }
                }

                if (minTimeUs < 0) {
                    ALOGV("not a all tracks have valid data.");
                    break;
                }

                ALOGV("writing access unit at time %.2f secs (index %d)",
                     minTimeUs / 1E6, minIndex);

                source = mSources.editItemAt(minIndex);

                buffer = source->lastAccessUnit();
                source->setLastAccessUnit(NULL);

                writeTS();
                writeAccessUnit(minIndex, buffer);

                source->readMore();
            }
            break;
        }

        default:
            TRESPASS();
    }
}

void MPEG2TSWriter::writeProgramAssociationTable() {
    // 0x47
    // transport_error_indicator = b0
    // payload_unit_start_indicator = b1
    // transport_priority = b0
    // PID = b0000000000000 (13 bits)
    // transport_scrambling_control = b00
    // adaptation_field_control = b01 (no adaptation field, payload only)
    // continuity_counter = b????
    // skip = 0x00
    // --- payload follows
    // table_id = 0x00
    // section_syntax_indicator = b1
    // must_be_zero = b0
    // reserved = b11
    // section_length = 0x00d
    // transport_stream_id = 0x0000
    // reserved = b11
    // version_number = b00001
    // current_next_indicator = b1
    // section_number = 0x00
    // last_section_number = 0x00
    //   one program follows:
    //   program_number = 0x0001
    //   reserved = b111
    //   program_map_PID = 0x01e0 (13 bits!)
    // CRC = 0x????????

    static const uint8_t kData[] = {
        0x47,
        0x40, 0x00, 0x10, 0x00,  // b0100 0000 0000 0000 0001 ???? 0000 0000
        0x00, 0xb0, 0x0d, 0x00,  // b0000 0000 1011 0000 0000 1101 0000 0000
        0x00, 0xc3, 0x00, 0x00,  // b0000 0000 1100 0011 0000 0000 0000 0000
        0x00, 0x01, 0xe1, 0xe0,  // b0000 0000 0000 0001 1110 0001 1110 0000
        0x00, 0x00, 0x00, 0x00   // b???? ???? ???? ???? ???? ???? ???? ????
    };

    sp<ABuffer> buffer = new ABuffer(188);
    memset(buffer->data(), 0, buffer->size());
    memcpy(buffer->data(), kData, sizeof(kData));

    static const unsigned kContinuityCounter = 5;
    buffer->data()[3] |= kContinuityCounter;

    CHECK_EQ(internalWrite(buffer->data(), buffer->size()), buffer->size());
}

void MPEG2TSWriter::writeProgramMap() {
    // 0x47
    // transport_error_indicator = b0
    // payload_unit_start_indicator = b1
    // transport_priority = b0
    // PID = b0 0001 1110 0000 (13 bits) [0x1e0]
    // transport_scrambling_control = b00
    // adaptation_field_control = b01 (no adaptation field, payload only)
    // continuity_counter = b????
    // skip = 0x00
    // -- payload follows
    // table_id = 0x02
    // section_syntax_indicator = b1
    // must_be_zero = b0
    // reserved = b11
    // section_length = 0x???
    // program_number = 0x0001
    // reserved = b11
    // version_number = b00001
    // current_next_indicator = b1
    // section_number = 0x00
    // last_section_number = 0x00
    // reserved = b111
    // PCR_PID = b? ???? ???? ???? (13 bits)
    // reserved = b1111
    // program_info_length = 0x000
    //   one or more elementary stream descriptions follow:
    //   stream_type = 0x??
    //   reserved = b111
    //   elementary_PID = b? ???? ???? ???? (13 bits)
    //   reserved = b1111
    //   ES_info_length = 0x000
    // CRC = 0x????????

    static const uint8_t kData[] = {
        0x47,
        0x41, 0xe0, 0x10, 0x00,  // b0100 0001 1110 0000 0001 ???? 0000 0000
        0x02, 0xb0, 0x00, 0x00,  // b0000 0010 1011 ???? ???? ???? 0000 0000
        0x01, 0xc3, 0x00, 0x00,  // b0000 0001 1100 0011 0000 0000 0000 0000
        0xe0, 0x00, 0xf0, 0x00   // b111? ???? ???? ???? 1111 0000 0000 0000
    };

    sp<ABuffer> buffer = new ABuffer(188);
    memset(buffer->data(), 0, buffer->size());
    memcpy(buffer->data(), kData, sizeof(kData));

    static const unsigned kContinuityCounter = 5;
    buffer->data()[3] |= kContinuityCounter;

    size_t section_length = 5 * mSources.size() + 4 + 9;
    buffer->data()[6] |= section_length >> 8;
    buffer->data()[7] = section_length & 0xff;

    static const unsigned kPCR_PID = 0x1e1;
    buffer->data()[13] |= (kPCR_PID >> 8) & 0x1f;
    buffer->data()[14] = kPCR_PID & 0xff;

    uint8_t *ptr = &buffer->data()[sizeof(kData)];
    for (size_t i = 0; i < mSources.size(); ++i) {
        *ptr++ = mSources.editItemAt(i)->streamType();

        const unsigned ES_PID = 0x1e0 + i + 1;
        *ptr++ = 0xe0 | (ES_PID >> 8);
        *ptr++ = ES_PID & 0xff;
        *ptr++ = 0xf0;
        *ptr++ = 0x00;
    }

    *ptr++ = 0x00;
    *ptr++ = 0x00;
    *ptr++ = 0x00;
    *ptr++ = 0x00;

    CHECK_EQ(internalWrite(buffer->data(), buffer->size()), buffer->size());
}

void MPEG2TSWriter::writeAccessUnit(
        int32_t sourceIndex, const sp<ABuffer> &accessUnit) {
    // 0x47
    // transport_error_indicator = b0
    // payload_unit_start_indicator = b1
    // transport_priority = b0
    // PID = b0 0001 1110 ???? (13 bits) [0x1e0 + 1 + sourceIndex]
    // transport_scrambling_control = b00
    // adaptation_field_control = b01 (no adaptation field, payload only)
    // continuity_counter = b????
    // -- payload follows
    // packet_startcode_prefix = 0x000001
    // stream_id = 0x?? (0xe0 for avc video, 0xc0 for aac audio)
    // PES_packet_length = 0x????
    // reserved = b10
    // PES_scrambling_control = b00
    // PES_priority = b0
    // data_alignment_indicator = b1
    // copyright = b0
    // original_or_copy = b0
    // PTS_DTS_flags = b10  (PTS only)
    // ESCR_flag = b0
    // ES_rate_flag = b0
    // DSM_trick_mode_flag = b0
    // additional_copy_info_flag = b0
    // PES_CRC_flag = b0
    // PES_extension_flag = b0
    // PES_header_data_length = 0x05
    // reserved = b0010 (PTS)
    // PTS[32..30] = b???
    // reserved = b1
    // PTS[29..15] = b??? ???? ???? ???? (15 bits)
    // reserved = b1
    // PTS[14..0] = b??? ???? ???? ???? (15 bits)
    // reserved = b1
    // the first fragment of "buffer" follows

    sp<ABuffer> buffer = new ABuffer(188);
    memset(buffer->data(), 0, buffer->size());

    const unsigned PID = 0x1e0 + sourceIndex + 1;

    const unsigned continuity_counter =
        mSources.editItemAt(sourceIndex)->incrementContinuityCounter();

    // XXX if there are multiple streams of a kind (more than 1 audio or
    // more than 1 video) they need distinct stream_ids.
    const unsigned stream_id =
        mSources.editItemAt(sourceIndex)->streamType() == 0x0f ? 0xc0 : 0xe0;

    int64_t timeUs;
    CHECK(accessUnit->meta()->findInt64("timeUs", &timeUs));

    uint32_t PTS = (timeUs * 9ll) / 100ll;

    size_t PES_packet_length = accessUnit->size() + 8;

    if (PES_packet_length >= 65536) {
        // This really should only happen for video.
        CHECK_EQ(stream_id, 0xe0u);

        // It's valid to set this to 0 for video according to the specs.
        PES_packet_length = 0;
    }

    uint8_t *ptr = buffer->data();
    *ptr++ = 0x47;
    *ptr++ = 0x40 | (PID >> 8);
    *ptr++ = PID & 0xff;
    *ptr++ = 0x10 | continuity_counter;
    *ptr++ = 0x00;
    *ptr++ = 0x00;
    *ptr++ = 0x01;
    *ptr++ = stream_id;
    *ptr++ = PES_packet_length >> 8;
    *ptr++ = PES_packet_length & 0xff;
    *ptr++ = 0x84;
    *ptr++ = 0x80;
    *ptr++ = 0x05;
    *ptr++ = 0x20 | (((PTS >> 30) & 7) << 1) | 1;
    *ptr++ = (PTS >> 22) & 0xff;
    *ptr++ = (((PTS >> 15) & 0x7f) << 1) | 1;
    *ptr++ = (PTS >> 7) & 0xff;
    *ptr++ = ((PTS & 0x7f) << 1) | 1;

    size_t sizeLeft = buffer->data() + buffer->size() - ptr;
    size_t copy = accessUnit->size();
    if (copy > sizeLeft) {
        copy = sizeLeft;
    }

    memcpy(ptr, accessUnit->data(), copy);

    CHECK_EQ(internalWrite(buffer->data(), buffer->size()), buffer->size());

    size_t offset = copy;
    while (offset < accessUnit->size()) {
        // for subsequent fragments of "buffer":
        // 0x47
        // transport_error_indicator = b0
        // payload_unit_start_indicator = b0
        // transport_priority = b0
        // PID = b0 0001 1110 ???? (13 bits) [0x1e0 + 1 + sourceIndex]
        // transport_scrambling_control = b00
        // adaptation_field_control = b01 (no adaptation field, payload only)
        // continuity_counter = b????
        // the fragment of "buffer" follows.

        memset(buffer->data(), 0, buffer->size());

        const unsigned continuity_counter =
            mSources.editItemAt(sourceIndex)->incrementContinuityCounter();

        ptr = buffer->data();
        *ptr++ = 0x47;
        *ptr++ = 0x00 | (PID >> 8);
        *ptr++ = PID & 0xff;
        *ptr++ = 0x10 | continuity_counter;

        size_t sizeLeft = buffer->data() + buffer->size() - ptr;
        size_t copy = accessUnit->size() - offset;
        if (copy > sizeLeft) {
            copy = sizeLeft;
        }

        memcpy(ptr, accessUnit->data() + offset, copy);
        CHECK_EQ(internalWrite(buffer->data(), buffer->size()),
                 buffer->size());

        offset += copy;
    }
}

void MPEG2TSWriter::writeTS() {
    if (mNumTSPacketsWritten >= mNumTSPacketsBeforeMeta) {
        writeProgramAssociationTable();
        writeProgramMap();

        mNumTSPacketsBeforeMeta = mNumTSPacketsWritten + 2500;
    }
}

ssize_t MPEG2TSWriter::internalWrite(const void *data, size_t size) {
    if (mFile != NULL) {
        return fwrite(data, 1, size, mFile);
    }

    return (*mWriteFunc)(mWriteCookie, data, size);
}

}  // namespace android

