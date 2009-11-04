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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaExtractor"
#include <utils/Log.h>

#include "include/AMRExtractor.h"
#include "include/MP3Extractor.h"
#include "include/MPEG4Extractor.h"
#include "include/WAVExtractor.h"

#include <media/stagefright/CachingDataSource.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/HTTPDataSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MmapSource.h>
#include <utils/String8.h>

namespace android {

// static
sp<MediaExtractor> MediaExtractor::Create(
        const sp<DataSource> &source, const char *mime) {
    String8 tmp;
    if (mime == NULL) {
        float confidence;
        if (!source->sniff(&tmp, &confidence)) {
            LOGE("FAILED to autodetect media content.");

            return NULL;
        }

        mime = tmp.string();
        LOGV("Autodetected media content as '%s' with confidence %.2f",
             mime, confidence);
    }

    if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_MPEG4)
            || !strcasecmp(mime, "audio/mp4")) {
        return new MPEG4Extractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_MPEG)) {
        return new MP3Extractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_NB)
            || !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_WB)) {
        return new AMRExtractor(source);
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_CONTAINER_WAV)) {
        return new WAVExtractor(source);
    }

    return NULL;
}

// static
sp<MediaExtractor> MediaExtractor::CreateFromURI(
        const char *uri, const char *mime) {
    sp<DataSource> source;
    if (!strncasecmp("file://", uri, 7)) {
        source = new MmapSource(uri + 7);
    } else if (!strncasecmp("http://", uri, 7)) {
        source = new HTTPDataSource(uri);
        source = new CachingDataSource(source, 64 * 1024, 10);
    } else {
        // Assume it's a filename.
        source = new MmapSource(uri);
    }

    if (source == NULL || source->initCheck() != OK) {
        return NULL;
    }

    return Create(source, mime);
}

}  // namespace android
