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

#include "include/stagefright_string.h"
#include "include/HTTPStream.h"

#include <stdlib.h>

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/ShoutcastSource.h>

namespace android {

ShoutcastSource::ShoutcastSource(HTTPStream *http)
    : mHttp(http),
      mMetaDataOffset(0),
      mBytesUntilMetaData(0),
      mGroup(NULL),
      mStarted(false) {
    string metaint;
    if (mHttp->find_header_value("icy-metaint", &metaint)) {
        char *end;
        const char *start = metaint.c_str();
        mMetaDataOffset = strtol(start, &end, 10);
        CHECK(end > start && *end == '\0');
        CHECK(mMetaDataOffset > 0);

        mBytesUntilMetaData = mMetaDataOffset;
    }
}

ShoutcastSource::~ShoutcastSource() {
    if (mStarted) {
        stop();
    }

    delete mHttp;
    mHttp = NULL;
}

status_t ShoutcastSource::start(MetaData *) {
    CHECK(!mStarted);

    mGroup = new MediaBufferGroup;
    mGroup->add_buffer(new MediaBuffer(4096));  // XXX

    mStarted = true;

    return OK;
}

status_t ShoutcastSource::stop() {
    CHECK(mStarted);

    delete mGroup;
    mGroup = NULL;

    mStarted = false;

    return OK;
}

sp<MetaData> ShoutcastSource::getFormat() {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_MPEG);
    meta->setInt32(kKeySampleRate, 44100);
    meta->setInt32(kKeyChannelCount, 2);  // XXX

    return meta;
}

status_t ShoutcastSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    CHECK(mStarted);

    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        return ERROR_UNSUPPORTED;
    }

    MediaBuffer *buffer;
    status_t err = mGroup->acquire_buffer(&buffer);
    if (err != OK) {
        return err;
    }

    *out = buffer;

    size_t num_bytes = buffer->size();
    if (mMetaDataOffset > 0 && num_bytes > mBytesUntilMetaData) {
        num_bytes = mBytesUntilMetaData;
    }

    ssize_t n = mHttp->receive(buffer->data(), num_bytes);

    if (n <= 0) {
        return (status_t)n;
    }

    buffer->set_range(0, n);

    mBytesUntilMetaData -= (size_t)n;

    if (mBytesUntilMetaData == 0) {
        unsigned char num_16_byte_blocks = 0;
        n = mHttp->receive((char *)&num_16_byte_blocks, 1);
        CHECK_EQ(n, 1);

        char meta[255 * 16];
        size_t meta_size = num_16_byte_blocks * 16;
        size_t meta_length = 0;
        while (meta_length < meta_size) {
            n = mHttp->receive(&meta[meta_length], meta_size - meta_length);
            if (n <= 0) {
                return (status_t)n;
            }

            meta_length += (size_t) n;
        }

        while (meta_length > 0 && meta[meta_length - 1] == '\0') {
            --meta_length;
        }

        if (meta_length > 0) {
            // Technically we should probably attach this meta data to the
            // next buffer. XXX
            buffer->meta_data()->setData('shou', 'shou', meta, meta_length);
        }

        mBytesUntilMetaData = mMetaDataOffset;
    }

    return OK;
}

}  // namespace android

