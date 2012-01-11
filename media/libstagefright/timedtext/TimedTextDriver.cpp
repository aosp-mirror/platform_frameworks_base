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

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/Utils.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>

#include "TimedTextDriver.h"

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
    mTextInBandVector.clear();
    mTextOutOfBandVector.clear();
    mLooper->stop();
}

status_t TimedTextDriver::setTimedTextTrackIndex_l(int32_t index) {
    if (index >=
            (int)(mTextInBandVector.size() + mTextOutOfBandVector.size())) {
        return BAD_VALUE;
    }

    sp<TimedTextSource> source;
    if (index < mTextInBandVector.size()) {
        source = mTextInBandVector.itemAt(index);
    } else {
        source = mTextOutOfBandVector.itemAt(index - mTextInBandVector.size());
    }
    mPlayer->setDataSource(source);
    return OK;
}

status_t TimedTextDriver::start() {
    Mutex::Autolock autoLock(mLock);
    switch (mState) {
        case UNINITIALIZED:
            return INVALID_OPERATION;
        case STOPPED:
            mPlayer->start();
            break;
        case PLAYING:
            return OK;
        case PAUSED:
            mPlayer->resume();
            break;
        default:
            TRESPASS();
    }
    mState = PLAYING;
    return OK;
}

status_t TimedTextDriver::stop() {
    return pause();
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
        case STOPPED:
            return OK;
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

status_t TimedTextDriver::resume() {
    return start();
}

status_t TimedTextDriver::seekToAsync(int64_t timeUs) {
    mPlayer->seekToAsync(timeUs);
    return OK;
}

status_t TimedTextDriver::setTimedTextTrackIndex(int32_t index) {
    // TODO: This is current implementation for MediaPlayer::disableTimedText().
    // Find better way for readability.
    if (index < 0) {
        mPlayer->pause();
        return OK;
    }

    status_t ret = OK;
    Mutex::Autolock autoLock(mLock);
    switch (mState) {
        case UNINITIALIZED:
            ret = INVALID_OPERATION;
            break;
        case PAUSED:
            ret = setTimedTextTrackIndex_l(index);
            break;
        case PLAYING:
            mPlayer->pause();
            ret = setTimedTextTrackIndex_l(index);
            if (ret != OK) {
                break;
            }
            mPlayer->start();
            break;
        case STOPPED:
            // TODO: The only difference between STOPPED and PAUSED is this
            // part. Revise the flow from "MediaPlayer::enableTimedText()" and
            // remove one of the status, PAUSED and STOPPED, if possible.
            ret = setTimedTextTrackIndex_l(index);
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

status_t TimedTextDriver::addInBandTextSource(
        const sp<MediaSource>& mediaSource) {
    sp<TimedTextSource> source =
            TimedTextSource::CreateTimedTextSource(mediaSource);
    if (source == NULL) {
        return ERROR_UNSUPPORTED;
    }
    Mutex::Autolock autoLock(mLock);
    mTextInBandVector.add(source);
    if (mState == UNINITIALIZED) {
        mState = STOPPED;
    }
    return OK;
}

status_t TimedTextDriver::addOutOfBandTextSource(
        const Parcel &request) {
    // TODO: Define "TimedTextSource::CreateFromURI(uri)"
    // and move below lines there..?

    // String values written in Parcel are UTF-16 values.
    const String16 uri16 = request.readString16();
    String8 uri = String8(request.readString16());

    uri.toLower();
    // To support local subtitle file only for now
    if (strncasecmp("file://", uri.string(), 7)) {
        return ERROR_UNSUPPORTED;
    }
    sp<DataSource> dataSource =
            DataSource::CreateFromURI(uri);
    if (dataSource == NULL) {
        return ERROR_UNSUPPORTED;
    }

    sp<TimedTextSource> source;
    if (uri.getPathExtension() == String8(".srt")) {
        source = TimedTextSource::CreateTimedTextSource(
                dataSource, TimedTextSource::OUT_OF_BAND_FILE_SRT);
    }

    if (source == NULL) {
        return ERROR_UNSUPPORTED;
    }

    Mutex::Autolock autoLock(mLock);

    mTextOutOfBandVector.add(source);
    if (mState == UNINITIALIZED) {
        mState = STOPPED;
    }
    return OK;
}

}  // namespace android
