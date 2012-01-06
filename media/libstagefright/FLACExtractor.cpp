/*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_TAG "FLACExtractor"
#include <utils/Log.h>

#include "include/FLACExtractor.h"
// Vorbis comments
#include "include/OggExtractor.h"
// libFLAC parser
#include "FLAC/stream_decoder.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MediaBuffer.h>

namespace android {

class FLACParser;

class FLACSource : public MediaSource {

public:
    FLACSource(
            const sp<DataSource> &dataSource,
            const sp<MetaData> &trackMetadata);

    virtual status_t start(MetaData *params);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~FLACSource();

private:
    sp<DataSource> mDataSource;
    sp<MetaData> mTrackMetadata;
    sp<FLACParser> mParser;
    bool mInitCheck;
    bool mStarted;

    status_t init();

    // no copy constructor or assignment
    FLACSource(const FLACSource &);
    FLACSource &operator=(const FLACSource &);

};

// FLACParser wraps a C libFLAC parser aka stream decoder

class FLACParser : public RefBase {

public:
    FLACParser(
        const sp<DataSource> &dataSource,
        // If metadata pointers aren't provided, we don't fill them
        const sp<MetaData> &fileMetadata = 0,
        const sp<MetaData> &trackMetadata = 0);

    status_t initCheck() const {
        return mInitCheck;
    }

    // stream properties
    unsigned getMaxBlockSize() const {
        return mStreamInfo.max_blocksize;
    }
    unsigned getSampleRate() const {
        return mStreamInfo.sample_rate;
    }
    unsigned getChannels() const {
        return mStreamInfo.channels;
    }
    unsigned getBitsPerSample() const {
        return mStreamInfo.bits_per_sample;
    }
    FLAC__uint64 getTotalSamples() const {
        return mStreamInfo.total_samples;
    }

    // media buffers
    void allocateBuffers();
    void releaseBuffers();
    MediaBuffer *readBuffer() {
        return readBuffer(false, 0LL);
    }
    MediaBuffer *readBuffer(FLAC__uint64 sample) {
        return readBuffer(true, sample);
    }

protected:
    virtual ~FLACParser();

private:
    sp<DataSource> mDataSource;
    sp<MetaData> mFileMetadata;
    sp<MetaData> mTrackMetadata;
    bool mInitCheck;

    // media buffers
    size_t mMaxBufferSize;
    MediaBufferGroup *mGroup;
    void (*mCopy)(short *dst, const int *const *src, unsigned nSamples);

    // handle to underlying libFLAC parser
    FLAC__StreamDecoder *mDecoder;

    // current position within the data source
    off64_t mCurrentPos;
    bool mEOF;

    // cached when the STREAMINFO metadata is parsed by libFLAC
    FLAC__StreamMetadata_StreamInfo mStreamInfo;
    bool mStreamInfoValid;

    // cached when a decoded PCM block is "written" by libFLAC parser
    bool mWriteRequested;
    bool mWriteCompleted;
    FLAC__FrameHeader mWriteHeader;
    const FLAC__int32 * const *mWriteBuffer;

    // most recent error reported by libFLAC parser
    FLAC__StreamDecoderErrorStatus mErrorStatus;

    status_t init();
    MediaBuffer *readBuffer(bool doSeek, FLAC__uint64 sample);

    // no copy constructor or assignment
    FLACParser(const FLACParser &);
    FLACParser &operator=(const FLACParser &);

    // FLAC parser callbacks as C++ instance methods
    FLAC__StreamDecoderReadStatus readCallback(
            FLAC__byte buffer[], size_t *bytes);
    FLAC__StreamDecoderSeekStatus seekCallback(
            FLAC__uint64 absolute_byte_offset);
    FLAC__StreamDecoderTellStatus tellCallback(
            FLAC__uint64 *absolute_byte_offset);
    FLAC__StreamDecoderLengthStatus lengthCallback(
            FLAC__uint64 *stream_length);
    FLAC__bool eofCallback();
    FLAC__StreamDecoderWriteStatus writeCallback(
            const FLAC__Frame *frame, const FLAC__int32 * const buffer[]);
    void metadataCallback(const FLAC__StreamMetadata *metadata);
    void errorCallback(FLAC__StreamDecoderErrorStatus status);

    // FLAC parser callbacks as C-callable functions
    static FLAC__StreamDecoderReadStatus read_callback(
            const FLAC__StreamDecoder *decoder,
            FLAC__byte buffer[], size_t *bytes,
            void *client_data);
    static FLAC__StreamDecoderSeekStatus seek_callback(
            const FLAC__StreamDecoder *decoder,
            FLAC__uint64 absolute_byte_offset,
            void *client_data);
    static FLAC__StreamDecoderTellStatus tell_callback(
            const FLAC__StreamDecoder *decoder,
            FLAC__uint64 *absolute_byte_offset,
            void *client_data);
    static FLAC__StreamDecoderLengthStatus length_callback(
            const FLAC__StreamDecoder *decoder,
            FLAC__uint64 *stream_length,
            void *client_data);
    static FLAC__bool eof_callback(
            const FLAC__StreamDecoder *decoder,
            void *client_data);
    static FLAC__StreamDecoderWriteStatus write_callback(
            const FLAC__StreamDecoder *decoder,
            const FLAC__Frame *frame, const FLAC__int32 * const buffer[],
            void *client_data);
    static void metadata_callback(
            const FLAC__StreamDecoder *decoder,
            const FLAC__StreamMetadata *metadata,
            void *client_data);
    static void error_callback(
            const FLAC__StreamDecoder *decoder,
            FLAC__StreamDecoderErrorStatus status,
            void *client_data);

};

// The FLAC parser calls our C++ static callbacks using C calling conventions,
// inside FLAC__stream_decoder_process_until_end_of_metadata
// and FLAC__stream_decoder_process_single.
// We immediately then call our corresponding C++ instance methods
// with the same parameter list, but discard redundant information.

FLAC__StreamDecoderReadStatus FLACParser::read_callback(
        const FLAC__StreamDecoder *decoder, FLAC__byte buffer[],
        size_t *bytes, void *client_data)
{
    return ((FLACParser *) client_data)->readCallback(buffer, bytes);
}

FLAC__StreamDecoderSeekStatus FLACParser::seek_callback(
        const FLAC__StreamDecoder *decoder,
        FLAC__uint64 absolute_byte_offset, void *client_data)
{
    return ((FLACParser *) client_data)->seekCallback(absolute_byte_offset);
}

FLAC__StreamDecoderTellStatus FLACParser::tell_callback(
        const FLAC__StreamDecoder *decoder,
        FLAC__uint64 *absolute_byte_offset, void *client_data)
{
    return ((FLACParser *) client_data)->tellCallback(absolute_byte_offset);
}

FLAC__StreamDecoderLengthStatus FLACParser::length_callback(
        const FLAC__StreamDecoder *decoder,
        FLAC__uint64 *stream_length, void *client_data)
{
    return ((FLACParser *) client_data)->lengthCallback(stream_length);
}

FLAC__bool FLACParser::eof_callback(
        const FLAC__StreamDecoder *decoder, void *client_data)
{
    return ((FLACParser *) client_data)->eofCallback();
}

FLAC__StreamDecoderWriteStatus FLACParser::write_callback(
        const FLAC__StreamDecoder *decoder, const FLAC__Frame *frame,
        const FLAC__int32 * const buffer[], void *client_data)
{
    return ((FLACParser *) client_data)->writeCallback(frame, buffer);
}

void FLACParser::metadata_callback(
        const FLAC__StreamDecoder *decoder,
        const FLAC__StreamMetadata *metadata, void *client_data)
{
    ((FLACParser *) client_data)->metadataCallback(metadata);
}

void FLACParser::error_callback(
        const FLAC__StreamDecoder *decoder,
        FLAC__StreamDecoderErrorStatus status, void *client_data)
{
    ((FLACParser *) client_data)->errorCallback(status);
}

// These are the corresponding callbacks with C++ calling conventions

FLAC__StreamDecoderReadStatus FLACParser::readCallback(
        FLAC__byte buffer[], size_t *bytes)
{
    size_t requested = *bytes;
    ssize_t actual = mDataSource->readAt(mCurrentPos, buffer, requested);
    if (0 > actual) {
        *bytes = 0;
        return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
    } else if (0 == actual) {
        *bytes = 0;
        mEOF = true;
        return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
    } else {
        assert(actual <= requested);
        *bytes = actual;
        mCurrentPos += actual;
        return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
    }
}

FLAC__StreamDecoderSeekStatus FLACParser::seekCallback(
        FLAC__uint64 absolute_byte_offset)
{
    mCurrentPos = absolute_byte_offset;
    mEOF = false;
    return FLAC__STREAM_DECODER_SEEK_STATUS_OK;
}

FLAC__StreamDecoderTellStatus FLACParser::tellCallback(
        FLAC__uint64 *absolute_byte_offset)
{
    *absolute_byte_offset = mCurrentPos;
    return FLAC__STREAM_DECODER_TELL_STATUS_OK;
}

FLAC__StreamDecoderLengthStatus FLACParser::lengthCallback(
        FLAC__uint64 *stream_length)
{
    off64_t size;
    if (OK == mDataSource->getSize(&size)) {
        *stream_length = size;
        return FLAC__STREAM_DECODER_LENGTH_STATUS_OK;
    } else {
        return FLAC__STREAM_DECODER_LENGTH_STATUS_UNSUPPORTED;
    }
}

FLAC__bool FLACParser::eofCallback()
{
    return mEOF;
}

FLAC__StreamDecoderWriteStatus FLACParser::writeCallback(
        const FLAC__Frame *frame, const FLAC__int32 * const buffer[])
{
    if (mWriteRequested) {
        mWriteRequested = false;
        // FLAC parser doesn't free or realloc buffer until next frame or finish
        mWriteHeader = frame->header;
        mWriteBuffer = buffer;
        mWriteCompleted = true;
        return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
    } else {
        ALOGE("FLACParser::writeCallback unexpected");
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }
}

void FLACParser::metadataCallback(const FLAC__StreamMetadata *metadata)
{
    switch (metadata->type) {
    case FLAC__METADATA_TYPE_STREAMINFO:
        if (!mStreamInfoValid) {
            mStreamInfo = metadata->data.stream_info;
            mStreamInfoValid = true;
        } else {
            ALOGE("FLACParser::metadataCallback unexpected STREAMINFO");
        }
        break;
    case FLAC__METADATA_TYPE_VORBIS_COMMENT:
        {
        const FLAC__StreamMetadata_VorbisComment *vc;
        vc = &metadata->data.vorbis_comment;
        for (FLAC__uint32 i = 0; i < vc->num_comments; ++i) {
            FLAC__StreamMetadata_VorbisComment_Entry *vce;
            vce = &vc->comments[i];
            if (mFileMetadata != 0) {
                parseVorbisComment(mFileMetadata, (const char *) vce->entry,
                        vce->length);
            }
        }
        }
        break;
    case FLAC__METADATA_TYPE_PICTURE:
        if (mFileMetadata != 0) {
            const FLAC__StreamMetadata_Picture *p = &metadata->data.picture;
            mFileMetadata->setData(kKeyAlbumArt,
                    MetaData::TYPE_NONE, p->data, p->data_length);
            mFileMetadata->setCString(kKeyAlbumArtMIME, p->mime_type);
        }
        break;
    default:
        ALOGW("FLACParser::metadataCallback unexpected type %u", metadata->type);
        break;
    }
}

void FLACParser::errorCallback(FLAC__StreamDecoderErrorStatus status)
{
    ALOGE("FLACParser::errorCallback status=%d", status);
    mErrorStatus = status;
}

// Copy samples from FLAC native 32-bit non-interleaved to 16-bit interleaved.
// These are candidates for optimization if needed.

static void copyMono8(short *dst, const int *const *src, unsigned nSamples)
{
    for (unsigned i = 0; i < nSamples; ++i) {
        *dst++ = src[0][i] << 8;
    }
}

static void copyStereo8(short *dst, const int *const *src, unsigned nSamples)
{
    for (unsigned i = 0; i < nSamples; ++i) {
        *dst++ = src[0][i] << 8;
        *dst++ = src[1][i] << 8;
    }
}

static void copyMono16(short *dst, const int *const *src, unsigned nSamples)
{
    for (unsigned i = 0; i < nSamples; ++i) {
        *dst++ = src[0][i];
    }
}

static void copyStereo16(short *dst, const int *const *src, unsigned nSamples)
{
    for (unsigned i = 0; i < nSamples; ++i) {
        *dst++ = src[0][i];
        *dst++ = src[1][i];
    }
}

// 24-bit versions should do dithering or noise-shaping, here or in AudioFlinger

static void copyMono24(short *dst, const int *const *src, unsigned nSamples)
{
    for (unsigned i = 0; i < nSamples; ++i) {
        *dst++ = src[0][i] >> 8;
    }
}

static void copyStereo24(short *dst, const int *const *src, unsigned nSamples)
{
    for (unsigned i = 0; i < nSamples; ++i) {
        *dst++ = src[0][i] >> 8;
        *dst++ = src[1][i] >> 8;
    }
}

static void copyTrespass(short *dst, const int *const *src, unsigned nSamples)
{
    TRESPASS();
}

// FLACParser

FLACParser::FLACParser(
        const sp<DataSource> &dataSource,
        const sp<MetaData> &fileMetadata,
        const sp<MetaData> &trackMetadata)
    : mDataSource(dataSource),
      mFileMetadata(fileMetadata),
      mTrackMetadata(trackMetadata),
      mInitCheck(false),
      mMaxBufferSize(0),
      mGroup(NULL),
      mCopy(copyTrespass),
      mDecoder(NULL),
      mCurrentPos(0LL),
      mEOF(false),
      mStreamInfoValid(false),
      mWriteRequested(false),
      mWriteCompleted(false),
      mWriteBuffer(NULL),
      mErrorStatus((FLAC__StreamDecoderErrorStatus) -1)
{
    ALOGV("FLACParser::FLACParser");
    memset(&mStreamInfo, 0, sizeof(mStreamInfo));
    memset(&mWriteHeader, 0, sizeof(mWriteHeader));
    mInitCheck = init();
}

FLACParser::~FLACParser()
{
    ALOGV("FLACParser::~FLACParser");
    if (mDecoder != NULL) {
        FLAC__stream_decoder_delete(mDecoder);
        mDecoder = NULL;
    }
}

status_t FLACParser::init()
{
    // setup libFLAC parser
    mDecoder = FLAC__stream_decoder_new();
    if (mDecoder == NULL) {
        // The new should succeed, since probably all it does is a malloc
        // that always succeeds in Android.  But to avoid dependence on the
        // libFLAC internals, we check and log here.
        ALOGE("new failed");
        return NO_INIT;
    }
    FLAC__stream_decoder_set_md5_checking(mDecoder, false);
    FLAC__stream_decoder_set_metadata_ignore_all(mDecoder);
    FLAC__stream_decoder_set_metadata_respond(
            mDecoder, FLAC__METADATA_TYPE_STREAMINFO);
    FLAC__stream_decoder_set_metadata_respond(
            mDecoder, FLAC__METADATA_TYPE_PICTURE);
    FLAC__stream_decoder_set_metadata_respond(
            mDecoder, FLAC__METADATA_TYPE_VORBIS_COMMENT);
    FLAC__StreamDecoderInitStatus initStatus;
    initStatus = FLAC__stream_decoder_init_stream(
            mDecoder,
            read_callback, seek_callback, tell_callback,
            length_callback, eof_callback, write_callback,
            metadata_callback, error_callback, (void *) this);
    if (initStatus != FLAC__STREAM_DECODER_INIT_STATUS_OK) {
        // A failure here probably indicates a programming error and so is
        // unlikely to happen. But we check and log here similarly to above.
        ALOGE("init_stream failed %d", initStatus);
        return NO_INIT;
    }
    // parse all metadata
    if (!FLAC__stream_decoder_process_until_end_of_metadata(mDecoder)) {
        ALOGE("end_of_metadata failed");
        return NO_INIT;
    }
    if (mStreamInfoValid) {
        // check channel count
        switch (getChannels()) {
        case 1:
        case 2:
            break;
        default:
            ALOGE("unsupported channel count %u", getChannels());
            return NO_INIT;
        }
        // check bit depth
        switch (getBitsPerSample()) {
        case 8:
        case 16:
        case 24:
            break;
        default:
            ALOGE("unsupported bits per sample %u", getBitsPerSample());
            return NO_INIT;
        }
        // check sample rate
        switch (getSampleRate()) {
        case  8000:
        case 11025:
        case 12000:
        case 16000:
        case 22050:
        case 24000:
        case 32000:
        case 44100:
        case 48000:
            break;
        default:
            // 96000 would require a proper downsampler in AudioFlinger
            ALOGE("unsupported sample rate %u", getSampleRate());
            return NO_INIT;
        }
        // configure the appropriate copy function, defaulting to trespass
        static const struct {
            unsigned mChannels;
            unsigned mBitsPerSample;
            void (*mCopy)(short *dst, const int *const *src, unsigned nSamples);
        } table[] = {
            { 1,  8, copyMono8    },
            { 2,  8, copyStereo8  },
            { 1, 16, copyMono16   },
            { 2, 16, copyStereo16 },
            { 1, 24, copyMono24   },
            { 2, 24, copyStereo24 },
        };
        for (unsigned i = 0; i < sizeof(table)/sizeof(table[0]); ++i) {
            if (table[i].mChannels == getChannels() &&
                    table[i].mBitsPerSample == getBitsPerSample()) {
                mCopy = table[i].mCopy;
                break;
            }
        }
        // populate track metadata
        if (mTrackMetadata != 0) {
            mTrackMetadata->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);
            mTrackMetadata->setInt32(kKeyChannelCount, getChannels());
            mTrackMetadata->setInt32(kKeySampleRate, getSampleRate());
            // sample rate is non-zero, so division by zero not possible
            mTrackMetadata->setInt64(kKeyDuration,
                    (getTotalSamples() * 1000000LL) / getSampleRate());
        }
    } else {
        ALOGE("missing STREAMINFO");
        return NO_INIT;
    }
    if (mFileMetadata != 0) {
        mFileMetadata->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_FLAC);
    }
    return OK;
}

void FLACParser::allocateBuffers()
{
    CHECK(mGroup == NULL);
    mGroup = new MediaBufferGroup;
    mMaxBufferSize = getMaxBlockSize() * getChannels() * sizeof(short);
    mGroup->add_buffer(new MediaBuffer(mMaxBufferSize));
}

void FLACParser::releaseBuffers()
{
    CHECK(mGroup != NULL);
    delete mGroup;
    mGroup = NULL;
}

MediaBuffer *FLACParser::readBuffer(bool doSeek, FLAC__uint64 sample)
{
    mWriteRequested = true;
    mWriteCompleted = false;
    if (doSeek) {
        // We implement the seek callback, so this works without explicit flush
        if (!FLAC__stream_decoder_seek_absolute(mDecoder, sample)) {
            ALOGE("FLACParser::readBuffer seek to sample %llu failed", sample);
            return NULL;
        }
        ALOGV("FLACParser::readBuffer seek to sample %llu succeeded", sample);
    } else {
        if (!FLAC__stream_decoder_process_single(mDecoder)) {
            ALOGE("FLACParser::readBuffer process_single failed");
            return NULL;
        }
    }
    if (!mWriteCompleted) {
        ALOGV("FLACParser::readBuffer write did not complete");
        return NULL;
    }
    // verify that block header keeps the promises made by STREAMINFO
    unsigned blocksize = mWriteHeader.blocksize;
    if (blocksize == 0 || blocksize > getMaxBlockSize()) {
        ALOGE("FLACParser::readBuffer write invalid blocksize %u", blocksize);
        return NULL;
    }
    if (mWriteHeader.sample_rate != getSampleRate() ||
        mWriteHeader.channels != getChannels() ||
        mWriteHeader.bits_per_sample != getBitsPerSample()) {
        ALOGE("FLACParser::readBuffer write changed parameters mid-stream");
    }
    // acquire a media buffer
    CHECK(mGroup != NULL);
    MediaBuffer *buffer;
    status_t err = mGroup->acquire_buffer(&buffer);
    if (err != OK) {
        return NULL;
    }
    size_t bufferSize = blocksize * getChannels() * sizeof(short);
    CHECK(bufferSize <= mMaxBufferSize);
    short *data = (short *) buffer->data();
    buffer->set_range(0, bufferSize);
    // copy PCM from FLAC write buffer to our media buffer, with interleaving
    (*mCopy)(data, mWriteBuffer, blocksize);
    // fill in buffer metadata
    CHECK(mWriteHeader.number_type == FLAC__FRAME_NUMBER_TYPE_SAMPLE_NUMBER);
    FLAC__uint64 sampleNumber = mWriteHeader.number.sample_number;
    int64_t timeUs = (1000000LL * sampleNumber) / getSampleRate();
    buffer->meta_data()->setInt64(kKeyTime, timeUs);
    buffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);
    return buffer;
}

// FLACsource

FLACSource::FLACSource(
        const sp<DataSource> &dataSource,
        const sp<MetaData> &trackMetadata)
    : mDataSource(dataSource),
      mTrackMetadata(trackMetadata),
      mParser(0),
      mInitCheck(false),
      mStarted(false)
{
    ALOGV("FLACSource::FLACSource");
    mInitCheck = init();
}

FLACSource::~FLACSource()
{
    ALOGV("~FLACSource::FLACSource");
    if (mStarted) {
        stop();
    }
}

status_t FLACSource::start(MetaData *params)
{
    ALOGV("FLACSource::start");

    CHECK(!mStarted);
    mParser->allocateBuffers();
    mStarted = true;

    return OK;
}

status_t FLACSource::stop()
{
    ALOGV("FLACSource::stop");

    CHECK(mStarted);
    mParser->releaseBuffers();
    mStarted = false;

    return OK;
}

sp<MetaData> FLACSource::getFormat()
{
    return mTrackMetadata;
}

status_t FLACSource::read(
        MediaBuffer **outBuffer, const ReadOptions *options)
{
    MediaBuffer *buffer;
    // process an optional seek request
    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if ((NULL != options) && options->getSeekTo(&seekTimeUs, &mode)) {
        FLAC__uint64 sample;
        if (seekTimeUs <= 0LL) {
            sample = 0LL;
        } else {
            // sample and total samples are both zero-based, and seek to EOF ok
            sample = (seekTimeUs * mParser->getSampleRate()) / 1000000LL;
            if (sample >= mParser->getTotalSamples()) {
                sample = mParser->getTotalSamples();
            }
        }
        buffer = mParser->readBuffer(sample);
    // otherwise read sequentially
    } else {
        buffer = mParser->readBuffer();
    }
    *outBuffer = buffer;
    return buffer != NULL ? (status_t) OK : (status_t) ERROR_END_OF_STREAM;
}

status_t FLACSource::init()
{
    ALOGV("FLACSource::init");
    // re-use the same track metadata passed into constructor from FLACExtractor
    mParser = new FLACParser(mDataSource);
    return mParser->initCheck();
}

// FLACExtractor

FLACExtractor::FLACExtractor(
        const sp<DataSource> &dataSource)
    : mDataSource(dataSource),
      mInitCheck(false)
{
    ALOGV("FLACExtractor::FLACExtractor");
    mInitCheck = init();
}

FLACExtractor::~FLACExtractor()
{
    ALOGV("~FLACExtractor::FLACExtractor");
}

size_t FLACExtractor::countTracks()
{
    return mInitCheck == OK ? 1 : 0;
}

sp<MediaSource> FLACExtractor::getTrack(size_t index)
{
    if (mInitCheck != OK || index > 0) {
        return NULL;
    }
    return new FLACSource(mDataSource, mTrackMetadata);
}

sp<MetaData> FLACExtractor::getTrackMetaData(
        size_t index, uint32_t flags)
{
    if (mInitCheck != OK || index > 0) {
        return NULL;
    }
    return mTrackMetadata;
}

status_t FLACExtractor::init()
{
    mFileMetadata = new MetaData;
    mTrackMetadata = new MetaData;
    // FLACParser will fill in the metadata for us
    mParser = new FLACParser(mDataSource, mFileMetadata, mTrackMetadata);
    return mParser->initCheck();
}

sp<MetaData> FLACExtractor::getMetaData()
{
    return mFileMetadata;
}

// Sniffer

bool SniffFLAC(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *)
{
    // first 4 is the signature word
    // second 4 is the sizeof STREAMINFO
    // 042 is the mandatory STREAMINFO
    // no need to read rest of the header, as a premature EOF will be caught later
    uint8_t header[4+4];
    if (source->readAt(0, header, sizeof(header)) != sizeof(header)
            || memcmp("fLaC\0\0\0\042", header, 4+4))
    {
        return false;
    }

    *mimeType = MEDIA_MIMETYPE_AUDIO_FLAC;
    *confidence = 0.5;

    return true;
}

}  // namespace android
