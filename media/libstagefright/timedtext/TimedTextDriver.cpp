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
#define LOG_TAG "TimedTextDriver"
#include <utils/Log.h>

#include <binder/IPCThreadState.h>

#include <media/mediaplayer.h>
#include <media/MediaPlayerInterface.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/timedtext/TimedTextDriver.h>

#include "TextDescriptions.h"
#include "TimedTextPlayer.h"
#include "TimedTextSource.h"

namespace android {

TimedTextDriver::TimedTextDriver(
        const wp<MediaPlayerBase> &listener)
    : mLooper(new ALooper),
      mListener(listener),
      mState(UNINITIALIZED) {
    mLooper->setName("TimedTextDriver");
    mLooper->start();
    mPlayer = new TimedTextPlayer(listener);
    mLooper->registerHandler(mPlayer);
}

TimedTextDriver::~TimedTextDriver() {
    mTextSourceVector.clear();
    mLooper->stop();
}

status_t TimedTextDriver::selectTrack_l(int32_t index) {
    if (index >= (int)(mTextSourceVector.size())) {
        return BAD_VALUE;
    }

    sp<TimedTextSource> source;
    source = mTextSourceVector.itemAt(index);
    mPlayer->setDataSource(source);
    if (mState == UNINITIALIZED) {
        mState = PAUSED;
    }
    mCurrentTrackIndex = index;
    return OK;
}

status_t TimedTextDriver::start() {
    Mutex::Autolock autoLock(mLock);
    switch (mState) {
        case UNINITIALIZED:
            return INVALID_OPERATION;
        case PLAYING:
            return OK;
        case PAUSED:
            mPlayer->start();
            break;
        default:
            TRESPASS();
    }
    mState = PLAYING;
    return OK;
}

// TODO: Test if pause() works properly.
// Scenario 1: start - pause - resume
// Scenario 2: start - seek
// Scenario 3: start - pause - seek - resume
status_t TimedTextDriver::pause() {
    Mutex::Autolock autoLock(mLock);
    switch (mState) {
        case UNINITIALIZED:
            return INVALID_OPERATION;
        case PLAYING:
            mPlayer->pause();
            break;
        case PAUSED:
            return OK;
        default:
            TRESPASS();
    }
    mState = PAUSED;
    return OK;
}

status_t TimedTextDriver::selectTrack(int32_t index) {
    status_t ret = OK;
    Mutex::Autolock autoLock(mLock);
    switch (mState) {
        case UNINITIALIZED:
        case PAUSED:
            ret = selectTrack_l(index);
            break;
        case PLAYING:
            mPlayer->pause();
            ret = selectTrack_l(index);
            if (ret != OK) {
                break;
            }
            mPlayer->start();
            break;
        defaut:
            TRESPASS();
    }
    return ret;
}

status_t TimedTextDriver::unselectTrack(int32_t index) {
    if (mCurrentTrackIndex != index) {
        return INVALID_OPERATION;
    }
    status_t err = pause();
    if (err != OK) {
        return err;
    }
    Mutex::Autolock autoLock(mLock);
    mState = UNINITIALIZED;
    return OK;
}

status_t TimedTextDriver::seekToAsync(int64_t timeUs) {
    mPlayer->seekToAsync(timeUs);
    return OK;
}

status_t TimedTextDriver::addInBandTextSource(
        const sp<MediaSource>& mediaSource) {
    sp<TimedTextSource> source =
            TimedTextSource::CreateTimedTextSource(mediaSource);
    if (source == NULL) {
        return ERROR_UNSUPPORTED;
    }
    Mutex::Autolock autoLock(mLock);
    mTextSourceVector.add(source);
    return OK;
}

status_t TimedTextDriver::addOutOfBandTextSource(
        const char *uri, const char *mimeType) {
    // TODO: Define "TimedTextSource::CreateFromURI(uri)"
    // and move below lines there..?

    // To support local subtitle file only for now
    if (strncasecmp("file://", uri, 7)) {
        return ERROR_UNSUPPORTED;
    }
    sp<DataSource> dataSource =
            DataSource::CreateFromURI(uri);
    if (dataSource == NULL) {
        return ERROR_UNSUPPORTED;
    }

    sp<TimedTextSource> source;
    if (strcasecmp(mimeType, MEDIA_MIMETYPE_TEXT_SUBRIP)) {
        source = TimedTextSource::CreateTimedTextSource(
                dataSource, TimedTextSource::OUT_OF_BAND_FILE_SRT);
    }

    if (source == NULL) {
        return ERROR_UNSUPPORTED;
    }

    Mutex::Autolock autoLock(mLock);
    mTextSourceVector.add(source);
    return OK;
}

status_t TimedTextDriver::addOutOfBandTextSource(
        int fd, off64_t offset, size_t length, const char *mimeType) {
    // Not supported yet. This requires DataSource::sniff to detect various text
    // formats such as srt/smi/ttml.
    return ERROR_UNSUPPORTED;
}

void TimedTextDriver::getTrackInfo(Parcel *parcel) {
    Mutex::Autolock autoLock(mLock);
    Vector<sp<TimedTextSource> >::const_iterator iter;
    parcel->writeInt32(mTextSourceVector.size());
    for (iter = mTextSourceVector.begin();
         iter != mTextSourceVector.end(); ++iter) {
        sp<MetaData> meta = (*iter)->getFormat();
        if (meta != NULL) {
            // There are two fields.
            parcel->writeInt32(2);

            // track type.
            parcel->writeInt32(MEDIA_TRACK_TYPE_TIMEDTEXT);

            const char *lang = "und";
            meta->findCString(kKeyMediaLanguage, &lang);
            parcel->writeString16(String16(lang));
        } else {
            parcel->writeInt32(0);
        }
    }
}

}  // namespace android
