 /*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_TAG "TimedTextPlayer"
#include <utils/Log.h>

#include <binder/IPCThreadState.h>

#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/Utils.h>

#include "include/AwesomePlayer.h"
#include "TimedTextPlayer.h"
#include "TimedTextParser.h"
#include "TextDescriptions.h"

namespace android {

struct TimedTextEvent : public TimedEventQueue::Event {
    TimedTextEvent(
            TimedTextPlayer *player,
            void (TimedTextPlayer::*method)())
        : mPlayer(player),
          mMethod(method) {
    }

protected:
    virtual ~TimedTextEvent() {}

    virtual void fire(TimedEventQueue *queue, int64_t /* now_us */) {
        (mPlayer->*mMethod)();
    }

private:
    TimedTextPlayer *mPlayer;
    void (TimedTextPlayer::*mMethod)();

    TimedTextEvent(const TimedTextEvent &);
    TimedTextEvent &operator=(const TimedTextEvent &);
};

TimedTextPlayer::TimedTextPlayer(
        AwesomePlayer *observer,
        const wp<MediaPlayerBase> &listener,
        TimedEventQueue *queue)
    : mSource(NULL),
      mOutOfBandSource(NULL),
      mSeekTimeUs(0),
      mStarted(false),
      mTextEventPending(false),
      mQueue(queue),
      mListener(listener),
      mObserver(observer),
      mTextBuffer(NULL),
      mTextParser(NULL),
      mTextType(kNoText) {
    mTextEvent = new TimedTextEvent(this, &TimedTextPlayer::onTextEvent);
}

TimedTextPlayer::~TimedTextPlayer() {
    if (mStarted) {
        reset();
    }

    mTextTrackVector.clear();
    mTextOutOfBandVector.clear();
}

status_t TimedTextPlayer::start(uint8_t index) {
    CHECK(!mStarted);

    if (index >=
            mTextTrackVector.size() + mTextOutOfBandVector.size()) {
        ALOGE("Incorrect text track index: %d", index);
        return BAD_VALUE;
    }

    status_t err;
    if (index < mTextTrackVector.size()) { // start an in-band text
        mSource = mTextTrackVector.itemAt(index);

        err = mSource->start();

        if (err != OK) {
            return err;
        }
        mTextType = kInBandText;
    } else { // start an out-of-band text
        OutOfBandText text =
            mTextOutOfBandVector.itemAt(index - mTextTrackVector.size());

        mOutOfBandSource = text.source;
        TimedTextParser::FileType fileType = text.type;

        if (mTextParser == NULL) {
            mTextParser = new TimedTextParser();
        }

        if ((err = mTextParser->init(mOutOfBandSource, fileType)) != OK) {
            return err;
        }
        mTextType = kOutOfBandText;
    }

    // send sample description format
    if ((err = extractAndSendGlobalDescriptions()) != OK) {
        return err;
    }

    int64_t positionUs;
    mObserver->getPosition(&positionUs);
    seekTo(positionUs);

    postTextEvent();

    mStarted = true;

    return OK;
}

void TimedTextPlayer::pause() {
    CHECK(mStarted);

    cancelTextEvent();
}

void TimedTextPlayer::resume() {
    CHECK(mStarted);

    postTextEvent();
}

void TimedTextPlayer::reset() {
    CHECK(mStarted);

    // send an empty text to clear the screen
    notifyListener(MEDIA_TIMED_TEXT);

    cancelTextEvent();

    mSeeking = false;
    mStarted = false;

    if (mTextType == kInBandText) {
        if (mTextBuffer != NULL) {
            mTextBuffer->release();
            mTextBuffer = NULL;
        }

        if (mSource != NULL) {
            mSource->stop();
            mSource.clear();
            mSource = NULL;
        }
    } else {
        if (mTextParser != NULL) {
            mTextParser.clear();
            mTextParser = NULL;
        }
        if (mOutOfBandSource != NULL) {
            mOutOfBandSource.clear();
            mOutOfBandSource = NULL;
        }
    }
}

status_t TimedTextPlayer::seekTo(int64_t time_us) {
    Mutex::Autolock autoLock(mLock);

    mSeeking = true;
    mSeekTimeUs = time_us;

    postTextEvent();

    return OK;
}

status_t TimedTextPlayer::setTimedTextTrackIndex(int32_t index) {
    if (index >=
            (int)(mTextTrackVector.size() + mTextOutOfBandVector.size())) {
        return BAD_VALUE;
    }

    if (mStarted) {
        reset();
    }

    if (index >= 0) {
        return start(index);
    }
    return OK;
}

void TimedTextPlayer::onTextEvent() {
    Mutex::Autolock autoLock(mLock);

    if (!mTextEventPending) {
        return;
    }
    mTextEventPending = false;

    if (mData.dataSize() > 0) {
        notifyListener(MEDIA_TIMED_TEXT, &mData);
        mData.freeData();
    }

    MediaSource::ReadOptions options;
    if (mSeeking) {
        options.setSeekTo(mSeekTimeUs,
                MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC);
        mSeeking = false;

        notifyListener(MEDIA_TIMED_TEXT); //empty text to clear the screen
    }

    int64_t positionUs, timeUs;
    mObserver->getPosition(&positionUs);

    if (mTextType == kInBandText) {
        if (mSource->read(&mTextBuffer, &options) != OK) {
            return;
        }

        mTextBuffer->meta_data()->findInt64(kKeyTime, &timeUs);
    } else {
        int64_t endTimeUs;
        if (mTextParser->getText(
                    &mText, &timeUs, &endTimeUs, &options) != OK) {
            return;
        }
    }

    if (timeUs > 0) {
        extractAndAppendLocalDescriptions(timeUs);
    }

    if (mTextType == kInBandText) {
        if (mTextBuffer != NULL) {
            mTextBuffer->release();
            mTextBuffer = NULL;
        }
    } else {
        mText.clear();
    }

    //send the text now
    if (timeUs <= positionUs + 100000ll) {
        postTextEvent();
    } else {
        postTextEvent(timeUs - positionUs - 100000ll);
    }
}

void TimedTextPlayer::postTextEvent(int64_t delayUs) {
    if (mTextEventPending) {
        return;
    }

    mTextEventPending = true;
    mQueue->postEventWithDelay(mTextEvent, delayUs < 0 ? 10000 : delayUs);
}

void TimedTextPlayer::cancelTextEvent() {
    mQueue->cancelEvent(mTextEvent->eventID());
    mTextEventPending = false;
}

void TimedTextPlayer::addTextSource(sp<MediaSource> source) {
    Mutex::Autolock autoLock(mLock);
    mTextTrackVector.add(source);
}

status_t TimedTextPlayer::setParameter(int key, const Parcel &request) {
    Mutex::Autolock autoLock(mLock);

    if (key == KEY_PARAMETER_TIMED_TEXT_ADD_OUT_OF_BAND_SOURCE) {
        const String16 uri16 = request.readString16();
        String8 uri = String8(uri16);
        KeyedVector<String8, String8> headers;

        // To support local subtitle file only for now
        if (strncasecmp("file://", uri.string(), 7)) {
            return INVALID_OPERATION;
        }
        sp<DataSource> dataSource =
            DataSource::CreateFromURI(uri, &headers);
        status_t err = dataSource->initCheck();

        if (err != OK) {
            return err;
        }

        OutOfBandText text;
        text.source = dataSource;
        if (uri.getPathExtension() == String8(".srt")) {
            text.type = TimedTextParser::OUT_OF_BAND_FILE_SRT;
        } else {
            return ERROR_UNSUPPORTED;
        }

        mTextOutOfBandVector.add(text);

        return OK;
    }
    return INVALID_OPERATION;
}

void TimedTextPlayer::notifyListener(int msg, const Parcel *parcel) {
    if (mListener != NULL) {
        sp<MediaPlayerBase> listener = mListener.promote();

        if (listener != NULL) {
            if (parcel && (parcel->dataSize() > 0)) {
                listener->sendEvent(msg, 0, 0, parcel);
            } else { // send an empty timed text to clear the screen
                listener->sendEvent(msg);
            }
        }
    }
}

// Each text sample consists of a string of text, optionally with sample
// modifier description. The modifier description could specify a new
// text style for the string of text. These descriptions are present only
// if they are needed. This method is used to extract the modifier
// description and append it at the end of the text.
status_t TimedTextPlayer::extractAndAppendLocalDescriptions(int64_t timeUs) {
    const void *data;
    size_t size = 0;
    int32_t flag = TextDescriptions::LOCAL_DESCRIPTIONS;

    if (mTextType == kInBandText) {
        const char *mime;
        CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));

        if (!strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP)) {
            flag |= TextDescriptions::IN_BAND_TEXT_3GPP;
            data = mTextBuffer->data();
            size = mTextBuffer->size();
        } else {
            // support 3GPP only for now
            return ERROR_UNSUPPORTED;
        }
    } else {
        data = mText.c_str();
        size = mText.size();
        flag |= TextDescriptions::OUT_OF_BAND_TEXT_SRT;
    }

    if ((size > 0) && (flag != TextDescriptions::LOCAL_DESCRIPTIONS)) {
        mData.freeData();
        return TextDescriptions::getParcelOfDescriptions(
                (const uint8_t *)data, size, flag, timeUs / 1000, &mData);
    }

    return OK;
}

// To extract and send the global text descriptions for all the text samples
// in the text track or text file.
status_t TimedTextPlayer::extractAndSendGlobalDescriptions() {
    const void *data;
    size_t size = 0;
    int32_t flag = TextDescriptions::GLOBAL_DESCRIPTIONS;

    if (mTextType == kInBandText) {
        const char *mime;
        CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));

        // support 3GPP only for now
        if (!strcasecmp(mime, MEDIA_MIMETYPE_TEXT_3GPP)) {
            uint32_t type;
            // get the 'tx3g' box content. This box contains the text descriptions
            // used to render the text track
            if (!mSource->getFormat()->findData(
                        kKeyTextFormatData, &type, &data, &size)) {
                return ERROR_MALFORMED;
            }

            flag |= TextDescriptions::IN_BAND_TEXT_3GPP;
        }
    }

    if ((size > 0) && (flag != TextDescriptions::GLOBAL_DESCRIPTIONS)) {
        Parcel parcel;
        if (TextDescriptions::getParcelOfDescriptions(
                (const uint8_t *)data, size, flag, 0, &parcel) == OK) {
            if (parcel.dataSize() > 0) {
                notifyListener(MEDIA_TIMED_TEXT, &parcel);
            }
        }
    }

    return OK;
}
}
