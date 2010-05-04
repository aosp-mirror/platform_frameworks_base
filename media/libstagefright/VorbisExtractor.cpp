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
#define LOG_TAG "VorbisExtractor"
#include <utils/Log.h>

#include "include/VorbisExtractor.h"

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/String8.h>

#include <ivorbisfile.h>

namespace android {

struct VorbisDataSource {
    sp<DataSource> mDataSource;
    off_t mOffset;
};

static size_t VorbisRead(
        void *ptr, size_t size, size_t nmemb, void *datasource) {
    VorbisDataSource *vds = (VorbisDataSource *)datasource;

    ssize_t n = vds->mDataSource->readAt(vds->mOffset, ptr, size * nmemb);

    if (n < 0) {
        return n;
    }

    vds->mOffset += n;

    return n / size;
}

static int VorbisSeek(
        void *datasource, ogg_int64_t offset, int whence) {
    VorbisDataSource *vds = (VorbisDataSource *)datasource;

    switch (whence) {
        case SEEK_SET:
            vds->mOffset = offset;
            break;
        case SEEK_END:
        {
            off_t size;
            if (vds->mDataSource->getSize(&size) != OK) {
                errno = ESPIPE;
                return -1;
            }

            vds->mOffset = offset + size;
            break;
        }

        case SEEK_CUR:
        {
            vds->mOffset += offset;
            break;
        }

        default:
        {
            errno = EINVAL;
            return -1;
        }
    }

    return 0;
}

static int VorbisClose(void *datasource) {
    return 0;
}

static long VorbisTell(void *datasource) {
    VorbisDataSource *vds = (VorbisDataSource *)datasource;

    return vds->mOffset;
}

static const ov_callbacks gVorbisCallbacks = {
    &VorbisRead,
    &VorbisSeek,
    &VorbisClose,
    &VorbisTell
};

////////////////////////////////////////////////////////////////////////////////

struct VorbisSource : public MediaSource {
    VorbisSource(const sp<VorbisExtractor> &extractor,
                 const sp<MetaData> &meta, OggVorbis_File *file);

    virtual sp<MetaData> getFormat();

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~VorbisSource();

private:
    enum {
        kMaxBufferSize = 8192
    };

    sp<VorbisExtractor> mExtractor;
    sp<MetaData> mMeta;
    OggVorbis_File *mFile;
    MediaBufferGroup *mGroup;

    VorbisSource(const VorbisSource &);
    VorbisSource &operator=(const VorbisSource &);
};

VorbisSource::VorbisSource(
        const sp<VorbisExtractor> &extractor,
        const sp<MetaData> &meta, OggVorbis_File *file)
    : mExtractor(extractor),
      mMeta(meta),
      mFile(file),
      mGroup(NULL) {
}

VorbisSource::~VorbisSource() {
    if (mGroup) {
        stop();
    }
}

sp<MetaData> VorbisSource::getFormat() {
    return mMeta;
}

status_t VorbisSource::start(MetaData *params) {
    if (mGroup != NULL) {
        return INVALID_OPERATION;
    }

    mGroup = new MediaBufferGroup;
    mGroup->add_buffer(new MediaBuffer(kMaxBufferSize));

    return OK;
}

status_t VorbisSource::stop() {
    delete mGroup;
    mGroup = NULL;

    return OK;
}

status_t VorbisSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
        ov_time_seek(mFile, seekTimeUs / 1000ll);
    }

    MediaBuffer *buffer;
    CHECK_EQ(OK, mGroup->acquire_buffer(&buffer));

    ogg_int64_t positionMs = ov_time_tell(mFile);

    int bitstream;
    long n = ov_read(mFile, buffer->data(), buffer->size(), &bitstream);

    if (n <= 0) {
        LOGE("ov_read returned %ld", n);

        buffer->release();
        buffer = NULL;

        return n < 0 ? ERROR_MALFORMED : ERROR_END_OF_STREAM;
    }

    buffer->set_range(0, n);
    buffer->meta_data()->setInt64(kKeyTime, positionMs * 1000ll);

    *out = buffer;

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

VorbisExtractor::VorbisExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mFile(new OggVorbis_File),
      mVorbisDataSource(new VorbisDataSource),
      mInitCheck(NO_INIT) {
    mVorbisDataSource->mDataSource = mDataSource;
    mVorbisDataSource->mOffset = 0;

    int res = ov_open_callbacks(
            mVorbisDataSource, mFile, NULL, 0, gVorbisCallbacks);

    if (res != 0) {
        return;
    }

    mMeta = new MetaData;
    mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);

    vorbis_info *vi = ov_info(mFile, -1);
    mMeta->setInt32(kKeySampleRate, vi->rate);
    mMeta->setInt32(kKeyChannelCount, vi->channels);

    ogg_int64_t durationMs = ov_time_total(mFile, -1);
    mMeta->setInt64(kKeyDuration, durationMs * 1000ll);

    LOGI("Successfully initialized.");

    mInitCheck = OK;
}

VorbisExtractor::~VorbisExtractor() {
    CHECK_EQ(0, ov_clear(mFile));

    delete mVorbisDataSource;
    mVorbisDataSource = NULL;

    delete mFile;
    mFile = NULL;
}

size_t VorbisExtractor::countTracks() {
    return mInitCheck != OK ? 0 : 1;
}

sp<MediaSource> VorbisExtractor::getTrack(size_t index) {
    if (index >= 1) {
        return NULL;
    }

    return new VorbisSource(this, mMeta, mFile);
}

sp<MetaData> VorbisExtractor::getTrackMetaData(
        size_t index, uint32_t flags) {
    if (index >= 1) {
        return NULL;
    }

    return mMeta;
}

sp<MetaData> VorbisExtractor::getMetaData() {
    sp<MetaData> meta = new MetaData;

    if (mInitCheck != OK) {
        return meta;
    }

    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_CONTAINER_VORBIS);

    return meta;
}

bool SniffVorbis(
        const sp<DataSource> &source, String8 *mimeType, float *confidence) {
    OggVorbis_File file;

    VorbisDataSource vds;
    vds.mDataSource = source;
    vds.mOffset = 0;

    int res = ov_test_callbacks(&vds, &file, NULL, 0, gVorbisCallbacks);

    CHECK_EQ(0, ov_clear(&file));

    if (res != 0) {
        return false;
    }

    *mimeType = MEDIA_MIMETYPE_CONTAINER_VORBIS;
    *confidence = 0.4f;

    LOGV("This looks like an Ogg file.");

    return true;
}

}  // namespace android
