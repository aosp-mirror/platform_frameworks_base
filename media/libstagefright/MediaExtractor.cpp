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

#include <media/stagefright/AMRExtractor.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MP3Extractor.h>
#include <media/stagefright/MPEG4Extractor.h>
#include <media/stagefright/MediaExtractor.h>
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
        LOGI("Autodetected media content as '%s' with confidence %.2f",
             mime, confidence);
    }

    if (!strcasecmp(mime, "video/mp4") || !strcasecmp(mime, "audio/mp4")) {
        return new MPEG4Extractor(source);
    } else if (!strcasecmp(mime, "audio/mpeg")) {
        return new MP3Extractor(source);
    } else if (!strcasecmp(mime, "audio/3gpp")
            || !strcasecmp(mime, "audio/amr-wb")) {
        return new AMRExtractor(source);
    }

    return NULL;
}

}  // namespace android
