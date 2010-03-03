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

#include <binder/ProcessState.h>

#include "SkBitmap.h"
#include "SkImageDecoder.h"
#include "SkStream.h"
#include "omx_jpeg_decoder.h"

class SkJPEGImageDecoder : public SkImageDecoder {
public:
    virtual Format getFormat() const {
        return kJPEG_Format;
    }

protected:
    virtual bool onDecode(SkStream* stream, SkBitmap* bm, Mode);
};

int nullObjectReturn(const char msg[]) {
    if (msg) {
        SkDebugf("--- %s\n", msg);
    }
    return -1;
}

static int64_t getNowUs() {
    struct timeval tv;
    gettimeofday(&tv, NULL);

    return tv.tv_usec + (int64_t) tv.tv_sec * 1000000;
}

int testDecodeBounds(SkImageDecoder* decoder, SkStream* stream,
        SkBitmap* bitmap) {
    int64_t startTime = getNowUs();
    SkBitmap::Config prefConfig = SkBitmap::kARGB_8888_Config;
    SkImageDecoder::Mode decodeMode = SkImageDecoder::kDecodeBounds_Mode;

    // Decode the input stream and then use the bitmap.
    if (!decoder->decode(stream, bitmap, prefConfig, decodeMode)) {
        return nullObjectReturn("decoder->decode returned false");
    } else {
        int64_t delay = getNowUs() - startTime;
        printf("WidthxHeight: %dx%d\n", bitmap->width(), bitmap->height());
        printf("Decoding Time in BoundsMode %.1f msec.\n", delay / 1000.0f);
        return 0;
    }
}

int testDecodePixels(SkImageDecoder* decoder, SkStream* stream,
        SkBitmap* bitmap) {
    int64_t startTime = getNowUs();
    SkBitmap::Config prefConfig = SkBitmap::kARGB_8888_Config;
    SkImageDecoder::Mode decodeMode = SkImageDecoder::kDecodePixels_Mode;

    // Decode the input stream and then use the bitmap.
    if (!decoder->decode(stream, bitmap, prefConfig, decodeMode)) {
        return nullObjectReturn("decoder->decode returned false");
    } else {
        int64_t delay = getNowUs() - startTime;
        printf("Decoding Time in PixelsMode %.1f msec.\n", delay / 1000.0f);
        const char* filename = "/sdcard/omxJpegDecodedBitmap.rgba";
        return storeBitmapToFile(bitmap, filename);
    }
}

int testDecoder(SkImageDecoder* decoder, char* filename) {
    // test DecodeMode == Pixels
    SkStream* stream = new SkFILEStream(filename);
    SkBitmap* bitmap = new SkBitmap;
    testDecodePixels(decoder, stream, bitmap);
    delete bitmap;

    // test DecodeMode == Bounds
    stream = new SkFILEStream(filename);
    bitmap = new SkBitmap;
    testDecodeBounds(decoder, stream, bitmap);
    delete bitmap;

    delete decoder;
    return 0;
}

int main(int argc, char** argv) {
    android::ProcessState::self()->startThreadPool();

    printf("Decoding jpeg with libjpeg...\n");
    SkJPEGImageDecoder* libjpeg = new SkJPEGImageDecoder;
    testDecoder(libjpeg, argv[1]);

    printf("\nDecoding jpeg with OMX...\n");
    OmxJpegImageDecoder* omx = new OmxJpegImageDecoder;
    testDecoder(omx, argv[1]);
    return 0;
}
