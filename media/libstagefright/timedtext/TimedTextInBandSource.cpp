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
#define LOG_TAG "TimedTextInBandSource"
#include <utils/Log.h>

#include <binder/Parcel.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>  // CHECK_XX macro
#include <media/stagefright/MediaDefs.h>  // for MEDIA_MIMETYPE_xxx
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

#include "TimedTextInBandSource.h"
#include "TextDescriptions.h"

namespace android {

TimedTextInBandSource::TimedTextInBandSource(const sp<MediaSource>& mediaSource)
    : mSource(mediaSource) {
}

TimedTextInBandSource::~TimedTextInBandSource() {
}

status_t TimedTextInBandSource::read(
        int64_t *timeUs, Parcel *parcel, const MediaSource::ReadOptions *options) {
    MediaBuffer *textBuffer = NULL;
    status_t err = mSource->read(&textBuffer, options);
    if (err != OK) {
        return err;
    }
    CHECK(textBuffer != NULL);
    textBuffer->meta_data()->findInt64(kKeyTime, timeUs);
    // TODO: this is legacy code. when 'timeUs' can be <= 0?
    if (*timeUs > 0) {
        extractAndAppendLocalDescriptions(*timeUs, textBuffer, parcel);
    }
    textBuffer->release();
    return OK;
}

// Each text sample consists of a string of text, optionally with sample
// modifier description. The modifier description could specify a new
// text style for the string of text. These descriptions are present only
// if they are needed. This method is used to extract the modifier
// description and append it at the end of the text.
status_t TimedTextInBandSource::extractAndAppendLocalDescriptions(
        int64_t timeUs, const MediaBuffer *textBuffer, Parcel *parcel) {
    const void *data;
    size_t size = 0;
    int32_t flag = TextDescriptions::LOCAL_DESCRIPTIONS;

    const char *mime;
    CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));

    if (strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP) == 0) {
        data = textBuffer->data();
        size = textBuffer->size();

        if (size > 0) {
            parcel->freeData();
            flag |= TextDescriptions::IN_BAND_TEXT_3GPP;
            return TextDescriptions::getParcelOfDescriptions(
                    (const uint8_t *)data, size, flag, timeUs / 1000, parcel);
        }
        return OK;
    }
    return ERROR_UNSUPPORTED;
}

// To extract and send the global text descriptions for all the text samples
// in the text track or text file.
// TODO: send error message to application via notifyListener()...?
status_t TimedTextInBandSource::extractGlobalDescriptions(Parcel *parcel) {
    const void *data;
    size_t size = 0;
    int32_t flag = TextDescriptions::GLOBAL_DESCRIPTIONS;

    const char *mime;
    CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));

    // support 3GPP only for now
    if (strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP) == 0) {
        uint32_t type;
        // get the 'tx3g' box content. This box contains the text descriptions
        // used to render the text track
        if (!mSource->getFormat()->findData(
                kKeyTextFormatData, &type, &data, &size)) {
            return ERROR_MALFORMED;
        }

        if (size > 0) {
            flag |= TextDescriptions::IN_BAND_TEXT_3GPP;
            return TextDescriptions::getParcelOfDescriptions(
                    (const uint8_t *)data, size, flag, 0, parcel);
        }
        return OK;
    }
    return ERROR_UNSUPPORTED;
}

}  // namespace android
