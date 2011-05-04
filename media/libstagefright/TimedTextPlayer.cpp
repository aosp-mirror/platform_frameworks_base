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
#include <media/stagefright/Utils.h>
#include "include/AwesomePlayer.h"
#include "include/TimedTextPlayer.h"

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
      mSeekTimeUs(0),
      mStarted(false),
      mTextEventPending(false),
      mQueue(queue),
      mListener(listener),
      mObserver(observer),
      mTextBuffer(NULL) {
    mTextEvent = new TimedTextEvent(this, &TimedTextPlayer::onTextEvent);
}

TimedTextPlayer::~TimedTextPlayer() {
    if (mStarted) {
        reset();
    }

    mTextTrackVector.clear();
}

status_t TimedTextPlayer::start(uint8_t index) {
    CHECK(!mStarted);

    if (index >= mTextTrackVector.size()) {
        LOGE("Incorrect text track index");
        return BAD_VALUE;
    }

    mSource = mTextTrackVector.itemAt(index);

    status_t err = mSource->start();

    if (err != OK) {
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

    if (mTextBuffer != NULL) {
        mTextBuffer->release();
        mTextBuffer = NULL;
    }

    if (mSource != NULL) {
        mSource->stop();
        mSource.clear();
        mSource = NULL;
    }
}

status_t TimedTextPlayer::seekTo(int64_t time_us) {
    Mutex::Autolock autoLock(mLock);

    mSeeking = true;
    mSeekTimeUs = time_us;

    return OK;
}

status_t TimedTextPlayer::setTimedTextTrackIndex(int32_t index) {
    if (index >= (int)(mTextTrackVector.size())) {
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

    MediaSource::ReadOptions options;
    if (mSeeking) {
        options.setSeekTo(mSeekTimeUs,
                MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC);
        mSeeking = false;

        if (mTextBuffer != NULL) {
            mTextBuffer->release();
            mTextBuffer = NULL;
        }

        notifyListener(MEDIA_TIMED_TEXT); //empty text to clear the screen
    }

    if (mTextBuffer != NULL) {
        uint8_t *tmp = (uint8_t *)(mTextBuffer->data());
        size_t len = (*tmp) << 8 | (*(tmp + 1));

        notifyListener(MEDIA_TIMED_TEXT,
                       tmp + 2,
                       len);

        mTextBuffer->release();
        mTextBuffer = NULL;

    }

    if (mSource->read(&mTextBuffer, &options) != OK) {
        return;
    }

    int64_t positionUs, timeUs;
    mObserver->getPosition(&positionUs);
    mTextBuffer->meta_data()->findInt64(kKeyTime, &timeUs);

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
    mTextTrackVector.add(source);
}

void TimedTextPlayer::notifyListener(
        int msg, const void *data, size_t size) {
    if (mListener != NULL) {
        sp<MediaPlayerBase> listener = mListener.promote();

        if (listener != NULL) {
            if (size > 0) {
                mData.freeData();
                mData.write(data, size);

                listener->sendEvent(msg, 0, 0, &mData);
            } else { // send an empty timed text to clear the screen
                listener->sendEvent(msg);
            }
        }
    }
}
}
