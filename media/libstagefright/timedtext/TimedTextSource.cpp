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

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaSource.h>

#include "TimedTextSource.h"

#include "TimedTextInBandSource.h"
#include "TimedTextSRTSource.h"

namespace android {

// static
sp<TimedTextSource> TimedTextSource::CreateTimedTextSource(
        const sp<MediaSource>& mediaSource) {
    return new TimedTextInBandSource(mediaSource);
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

}  // namespace android
