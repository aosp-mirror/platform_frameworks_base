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

#ifndef OMXJPEGIMAGEDECODER
#define OMXJPEGIMAGEDECODER

#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <media/stagefright/JPEGSource.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <SkImageDecoder.h>
#include <SkStream.h>

using namespace android;

extern int storeBitmapToFile(SkBitmap* bitmap, const char* filename);

class OmxJpegImageDecoder : public SkImageDecoder {
public:
    OmxJpegImageDecoder();
    ~OmxJpegImageDecoder();

    virtual Format getFormat() const {
        return kJPEG_Format;
    }

protected:
    virtual bool onDecode(SkStream* stream, SkBitmap* bm, Mode mode);

private:
    JPEGSource* prepareMediaSource(SkStream* stream);
    sp<MediaSource> getDecoder(OMXClient* client, const sp<MediaSource>& source);
    bool decodeSource(sp<MediaSource> decoder, const sp<MediaSource>& source,
            SkBitmap* bm);
    void installPixelRef(MediaBuffer* buffer, sp<MediaSource> decoder,
            SkBitmap* bm);
    void configBitmapSize(SkBitmap* bm, SkBitmap::Config pref, int width,
            int height);
    SkBitmap::Config getColorSpaceConfig(SkBitmap::Config pref);

    OMXClient mClient;
};

#endif
