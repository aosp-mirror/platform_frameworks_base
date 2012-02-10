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

// #define LOG_NDEBUG   0
#define LOG_TAG "JPEGSource"
#include <utils/Log.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/JPEGSource.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

#define JPEG_SOF0  0xC0            /* nStart Of Frame N*/
#define JPEG_SOF1  0xC1            /* N indicates which compression process*/
#define JPEG_SOF2  0xC2            /* Only SOF0-SOF2 are now in common use*/
#define JPEG_SOF3  0xC3
#define JPEG_SOF5  0xC5            /* NB: codes C4 and CC are NOT SOF markers*/
#define JPEG_SOF6  0xC6
#define JPEG_SOF7  0xC7
#define JPEG_SOF9  0xC9
#define JPEG_SOF10 0xCA
#define JPEG_SOF11 0xCB
#define JPEG_SOF13 0xCD
#define JPEG_SOF14 0xCE
#define JPEG_SOF15 0xCF
#define JPEG_SOI   0xD8            /* nStart Of Image (beginning of datastream)*/
#define JPEG_EOI   0xD9            /* End Of Image (end of datastream)*/
#define JPEG_SOS   0xDA            /* nStart Of Scan (begins compressed data)*/
#define JPEG_JFIF  0xE0            /* Jfif marker*/
#define JPEG_EXIF  0xE1            /* Exif marker*/
#define JPEG_COM   0xFE            /* COMment */
#define JPEG_DQT   0xDB
#define JPEG_DHT   0xC4
#define JPEG_DRI   0xDD

namespace android {

JPEGSource::JPEGSource(const sp<DataSource> &source)
    : mSource(source),
      mGroup(NULL),
      mStarted(false),
      mSize(0),
      mWidth(0),
      mHeight(0),
      mOffset(0) {
    CHECK_EQ(parseJPEG(), (status_t)OK);
    CHECK(mSource->getSize(&mSize) == OK);
}

JPEGSource::~JPEGSource() {
    if (mStarted) {
        stop();
    }
}

status_t JPEGSource::start(MetaData *) {
    if (mStarted) {
        return UNKNOWN_ERROR;
    }

    mGroup = new MediaBufferGroup;
    mGroup->add_buffer(new MediaBuffer(mSize));

    mOffset = 0;

    mStarted = true;

    return OK;
}

status_t JPEGSource::stop() {
    if (!mStarted) {
        return UNKNOWN_ERROR;
    }

    delete mGroup;
    mGroup = NULL;

    mStarted = false;

    return OK;
}

sp<MetaData> JPEGSource::getFormat() {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_IMAGE_JPEG);
    meta->setInt32(kKeyWidth, mWidth);
    meta->setInt32(kKeyHeight, mHeight);
    meta->setInt32(kKeyMaxInputSize, mSize);

    return meta;
}

status_t JPEGSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options != NULL && options->getSeekTo(&seekTimeUs, &mode)) {
        return UNKNOWN_ERROR;
    }

    MediaBuffer *buffer;
    mGroup->acquire_buffer(&buffer);

    ssize_t n = mSource->readAt(mOffset, buffer->data(), mSize - mOffset);

    if (n <= 0) {
        buffer->release();
        buffer = NULL;

        return UNKNOWN_ERROR;
    }

    buffer->set_range(0, n);

    mOffset += n;

    *out = buffer;

    return OK;
}

status_t JPEGSource::parseJPEG() {
    mWidth = 0;
    mHeight = 0;

    off64_t i = 0;

    uint16_t soi;
    if (!mSource->getUInt16(i, &soi)) {
        return ERROR_IO;
    }

    i += 2;

    if (soi != 0xffd8) {
        return UNKNOWN_ERROR;
    }

    for (;;) {
        uint8_t marker;
        if (mSource->readAt(i++, &marker, 1) != 1) {
            return ERROR_IO;
        }

        CHECK_EQ(marker, 0xff);

        if (mSource->readAt(i++, &marker, 1) != 1) {
            return ERROR_IO;
        }

        CHECK(marker != 0xff);

        uint16_t chunkSize;
        if (!mSource->getUInt16(i, &chunkSize)) {
            return ERROR_IO;
        }

        i += 2;

        if (chunkSize < 2) {
            return UNKNOWN_ERROR;
        }

        switch (marker) {
            case JPEG_SOS:
            {
                return (mWidth > 0 && mHeight > 0) ? OK : UNKNOWN_ERROR;
            }

            case JPEG_EOI:
            {
                return UNKNOWN_ERROR;
            }

            case JPEG_SOF0:
            case JPEG_SOF1:
            case JPEG_SOF3:
            case JPEG_SOF5:
            case JPEG_SOF6:
            case JPEG_SOF7:
            case JPEG_SOF9:
            case JPEG_SOF10:
            case JPEG_SOF11:
            case JPEG_SOF13:
            case JPEG_SOF14:
            case JPEG_SOF15:
            {
                uint16_t width, height;
                if (!mSource->getUInt16(i + 1, &height)
                    || !mSource->getUInt16(i + 3, &width)) {
                    return ERROR_IO;
                }

                mWidth = width;
                mHeight = height;

                i += chunkSize - 2;
                break;
            }

            default:
            {
                // Skip chunk

                i += chunkSize - 2;

                break;
            }
        }
    }

    return OK;
}

}  // namespace android
