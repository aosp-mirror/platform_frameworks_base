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
#define LOG_TAG "TimedText3GPPSource"
#include <utils/Log.h>

#include <binder/Parcel.h>
#include <media/stagefright/foundation/ADebug.h>  // CHECK_XX macro
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>  // for MEDIA_MIMETYPE_xxx
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

#include "TimedText3GPPSource.h"
#include "TextDescriptions.h"

namespace android {

TimedText3GPPSource::TimedText3GPPSource(const sp<MediaSource>& mediaSource)
    : mSource(mediaSource) {
}

TimedText3GPPSource::~TimedText3GPPSource() {
}

status_t TimedText3GPPSource::read(
        int64_t *startTimeUs, int64_t *endTimeUs, Parcel *parcel,
        const MediaSource::ReadOptions *options) {
    MediaBuffer *textBuffer = NULL;
    status_t err = mSource->read(&textBuffer, options);
    if (err != OK) {
        return err;
    }
    CHECK(textBuffer != NULL);
    textBuffer->meta_data()->findInt64(kKeyTime, startTimeUs);
    CHECK_GE(*startTimeUs, 0);
    extractAndAppendLocalDescriptions(*startTimeUs, textBuffer, parcel);
    textBuffer->release();
    // endTimeUs is a dummy parameter for 3gpp timed text format.
    // Set a negative value to it to mark it is unavailable.
    *endTimeUs = -1;
    return OK;
}

// Each text sample consists of a string of text, optionally with sample
// modifier description. The modifier description could specify a new
// text style for the string of text. These descriptions are present only
// if they are needed. This method is used to extract the modifier
// description and append it at the end of the text.
status_t TimedText3GPPSource::extractAndAppendLocalDescriptions(
        int64_t timeUs, const MediaBuffer *textBuffer, Parcel *parcel) {
    const void *data;
    size_t size = 0;
    int32_t flag = TextDescriptions::LOCAL_DESCRIPTIONS;

    const char *mime;
    CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));
    CHECK(strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP) == 0);

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

// To extract and send the global text descriptions for all the text samples
// in the text track or text file.
// TODO: send error message to application via notifyListener()...?
status_t TimedText3GPPSource::extractGlobalDescriptions(Parcel *parcel) {
    const void *data;
    size_t size = 0;
    int32_t flag = TextDescriptions::GLOBAL_DESCRIPTIONS;

    const char *mime;
    CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));
    CHECK(strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP) == 0);

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

sp<MetaData> TimedText3GPPSource::getFormat() {
    return mSource->getFormat();
}

}  // namespace android
