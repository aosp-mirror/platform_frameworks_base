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

#define LOG_TAG "OmxJpegDecoder"
#include <sys/time.h>
#include <utils/Log.h>

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <media/IMediaPlayerService.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <SkMallocPixelRef.h>

#include "omx_jpeg_decoder.h"
#include "SkOmxPixelRef.h"
#include "StreamSource.h"

using namespace android;

static void getJpegOutput(MediaBuffer* buffer, const char* filename) {
    int size = buffer->range_length();
    int offset = buffer->range_offset();
    FILE *pFile = fopen(filename, "w+");

    if (pFile == NULL) {
        printf("Error: cannot open %s.\n", filename);
    } else {
        char* data = (char*) buffer->data();
        data += offset;
        while (size > 0) {
            int numChars = fwrite(data, sizeof(char), 1024, pFile);
            int numBytes = numChars * sizeof(char);
            size -= numBytes;
            data += numBytes;
        }
        fclose(pFile);
    }
    return;
}

extern int storeBitmapToFile(SkBitmap* bitmap, const char* filename) {
    bitmap->lockPixels();
    uint8_t* data = (uint8_t *)bitmap->getPixels();
    int size = bitmap->getSize();
    FILE* fp = fopen(filename, "w+");

    if (NULL == fp) {
        printf("Cannot open the output file! \n");
        return -1;
    } else {
        while (size > 0) {
            int numChars = fwrite(data, sizeof(char), 1024, fp);
            int numBytes = numChars * sizeof(char);
            size -= numBytes;
            data += numBytes;
        }
        fclose(fp);
    }
    return 0;
}

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return (int64_t)tv.tv_usec + tv.tv_sec * 1000000;
}

OmxJpegImageDecoder::OmxJpegImageDecoder() {
    status_t err = mClient.connect();
    CHECK_EQ(err, OK);
}

OmxJpegImageDecoder::~OmxJpegImageDecoder() {
    mClient.disconnect();
}

bool OmxJpegImageDecoder::onDecode(SkStream* stream,
        SkBitmap* bm, Mode mode) {
    sp<MediaSource> source = prepareMediaSource(stream);
    sp<MetaData> meta = source->getFormat();
    int width;
    int height;
    meta->findInt32(kKeyWidth, &width);
    meta->findInt32(kKeyHeight, &height);
    configBitmapSize(bm, getPrefConfig(k32Bit_SrcDepth, false), width, height);

    // mode == DecodeBounds
    if (mode == SkImageDecoder::kDecodeBounds_Mode) {
        return true;
    }

    // mode == DecodePixels
    if (!this->allocPixelRef(bm, NULL)) {
        ALOGI("Cannot allocPixelRef()!");
        return false;
    }

    sp<MediaSource> decoder = getDecoder(&mClient, source);
    return decodeSource(decoder, source, bm);
}

JPEGSource* OmxJpegImageDecoder::prepareMediaSource(SkStream* stream) {
    DataSource::RegisterDefaultSniffers();
    sp<DataSource> dataSource = new StreamSource(stream);
    return new JPEGSource(dataSource);
}

sp<MediaSource> OmxJpegImageDecoder::getDecoder(
        OMXClient *client, const sp<MediaSource>& source) {
    sp<MetaData> meta = source->getFormat();
    sp<MediaSource> decoder = OMXCodec::Create(
            client->interface(), meta, false /* createEncoder */, source);

    CHECK(decoder != NULL);
    return decoder;
}

bool OmxJpegImageDecoder::decodeSource(sp<MediaSource> decoder,
        const sp<MediaSource>& source, SkBitmap* bm) {
    status_t rt = decoder->start();
    if (rt != OK) {
        LOGE("Cannot start OMX Decoder!");
        return false;
    }
    int64_t startTime = getNowUs();
    MediaBuffer *buffer;

    // decode source
    status_t err = decoder->read(&buffer, NULL);
    int64_t duration = getNowUs() - startTime;

    if (err != OK) {
        CHECK_EQ(buffer, NULL);
    }
    printf("Duration in decoder->read(): %.1f (msecs). \n",
                duration / 1E3 );

    /* Mark the code for now, since we attend to copy buffer to SkBitmap.
    // Install pixelRef to Bitmap.
    installPixelRef(buffer, decoder, bm);*/

    // Copy pixels from buffer to bm.
    // May need to check buffer->rawBytes() == bm->rawBytes().
    CHECK_EQ(buffer->size(), bm->getSize());
    memcpy(bm->getPixels(), buffer->data(), buffer->size());
    buffer->release();
    decoder->stop();

    return true;
}

void OmxJpegImageDecoder::installPixelRef(MediaBuffer *buffer, sp<MediaSource> decoder,
        SkBitmap* bm) {

    // set bm's pixelref based on the data in buffer.
    SkAutoLockPixels alp(*bm);
    SkPixelRef* pr = new SkOmxPixelRef(NULL, buffer, decoder);
    bm->setPixelRef(pr)->unref();
    bm->lockPixels();
    return;
}

void OmxJpegImageDecoder::configBitmapSize(SkBitmap* bm, SkBitmap::Config pref,
        int width, int height) {
    bm->setConfig(getColorSpaceConfig(pref), width, height);
    bm->setIsOpaque(true);
}

SkBitmap::Config OmxJpegImageDecoder::getColorSpaceConfig(
        SkBitmap::Config pref) {

    // Set the color space to ARGB_8888 for now
    // because of limitation in hardware support.
    return SkBitmap::kARGB_8888_Config;
}
