 /*
 * Copyright (C) 2012 The Android Open Source Project
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
#define LOG_TAG "TimedTextSource"
#include <utils/Log.h>

#include <media/stagefright/foundation/ADebug.h>  // CHECK_XX macro
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDefs.h>  // for MEDIA_MIMETYPE_xxx
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

#include "TimedTextSource.h"

#include "TimedText3GPPSource.h"
#include "TimedTextSRTSource.h"

namespace android {

// static
sp<TimedTextSource> TimedTextSource::CreateTimedTextSource(
        const sp<MediaSource>& mediaSource) {
    const char *mime;
    CHECK(mediaSource->getFormat()->findCString(kKeyMIMEType, &mime));
    if (strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP) == 0) {
        return new TimedText3GPPSource(mediaSource);
    }
    ALOGE("Unsupported mime type for subtitle. : %s", mime);
    return NULL;
}

// static
sp<TimedTextSource> TimedTextSource::CreateTimedTextSource(
        const sp<DataSource>& dataSource, FileType filetype) {
    switch(filetype) {
        case OUT_OF_BAND_FILE_SRT:
            return new TimedTextSRTSource(dataSource);
        case OUT_OF_BAND_FILE_SMI:
            // TODO: Implement for SMI.
            ALOGE("Supporting SMI is not implemented yet");
            break;
        default:
            ALOGE("Undefined subtitle format. : %d", filetype);
    }
    return NULL;
}

sp<MetaData> TimedTextSource::getFormat() {
    return NULL;
}

}  // namespace android
