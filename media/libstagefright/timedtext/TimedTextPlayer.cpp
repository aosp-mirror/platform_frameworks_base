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
#define LOG_TAG "TimedTextPlayer"
#include <utils/Log.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/timedtext/TimedTextDriver.h>
#include <media/stagefright/MediaErrors.h>
#include <media/MediaPlayerInterface.h>

#include "TimedTextPlayer.h"

#include "TimedTextSource.h"

namespace android {

static const int64_t kAdjustmentProcessingTimeUs = 100000ll;
static const int64_t kWaitTimeUsToRetryRead = 100000ll;

TimedTextPlayer::TimedTextPlayer(const wp<MediaPlayerBase> &listener)
    : mListener(listener),
      mSource(NULL),
      mSendSubtitleGeneration(0) {
}

TimedTextPlayer::~TimedTextPlayer() {
    if (mSource != NULL) {
        mSource->stop();
        mSource.clear();
        mSource = NULL;
    }
}

void TimedTextPlayer::start() {
    sp<AMessage> msg = new AMessage(kWhatSeek, id());
    msg->setInt64("seekTimeUs", -1);
    msg->post();
}

void TimedTextPlayer::pause() {
    (new AMessage(kWhatPause, id()))->post();
}

void TimedTextPlayer::seekToAsync(int64_t timeUs) {
    sp<AMessage> msg = new AMessage(kWhatSeek, id());
    msg->setInt64("seekTimeUs", timeUs);
    msg->post();
}

void TimedTextPlayer::setDataSource(sp<TimedTextSource> source) {
    sp<AMessage> msg = new AMessage(kWhatSetSource, id());
    msg->setObject("source", source);
    msg->post();
}

void TimedTextPlayer::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatPause: {
            mSendSubtitleGeneration++;
            break;
        }
        case kWhatSeek: {
            int64_t seekTimeUs = 0;
            msg->findInt64("seekTimeUs", &seekTimeUs);
            if (seekTimeUs < 0) {
                sp<MediaPlayerBase> listener = mListener.promote();
                if (listener != NULL) {
                    int32_t positionMs = 0;
                    listener->getCurrentPosition(&positionMs);
                    seekTimeUs = positionMs * 1000ll;
                }
            }
            doSeekAndRead(seekTimeUs);
            break;
        }
        case kWhatSendSubtitle: {
            int32_t generation;
            CHECK(msg->findInt32("generation", &generation));
            if (generation != mSendSubtitleGeneration) {
              // Drop obsolete msg.
              break;
            }
            sp<RefBase> obj;
            msg->findObject("subtitle", &obj);
            if (obj != NULL) {
                sp<ParcelEvent> parcelEvent;
                parcelEvent = static_cast<ParcelEvent*>(obj.get());
                notifyListener(&(parcelEvent->parcel));
            } else {
                notifyListener();
            }
            doRead();
            break;
        }
        case kWhatSetSource: {
            sp<RefBase> obj;
            msg->findObject("source", &obj);
            if (obj == NULL) break;
            if (mSource != NULL) {
                mSource->stop();
            }
            mSource = static_cast<TimedTextSource*>(obj.get());
            status_t err = mSource->start();
            if (err != OK) {
                notifyError(err);
                break;
            }
            Parcel parcel;
            err = mSource->extractGlobalDescriptions(&parcel);
            if (err != OK) {
                notifyError(err);
                break;
            }
            notifyListener(&parcel);
            break;
        }
    }
}

void TimedTextPlayer::doSeekAndRead(int64_t seekTimeUs) {
    MediaSource::ReadOptions options;
    options.setSeekTo(seekTimeUs, MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC);
    doRead(&options);
}

void TimedTextPlayer::doRead(MediaSource::ReadOptions* options) {
    int64_t startTimeUs = 0;
    int64_t endTimeUs = 0;
    sp<ParcelEvent> parcelEvent = new ParcelEvent();
    status_t err = mSource->read(&startTimeUs, &endTimeUs,
                                 &(parcelEvent->parcel), options);
    if (err == WOULD_BLOCK) {
        postTextEventDelayUs(NULL, kWaitTimeUsToRetryRead);
        return;
    } else if (err != OK) {
        notifyError(err);
        return;
    }

    postTextEvent(parcelEvent, startTimeUs);
    if (endTimeUs > 0) {
        CHECK_GE(endTimeUs, startTimeUs);
        // send an empty timed text to clear the subtitle when it reaches to the
        // end time.
        postTextEvent(NULL, endTimeUs);
    }
}

void TimedTextPlayer::postTextEvent(const sp<ParcelEvent>& parcel, int64_t timeUs) {
    sp<MediaPlayerBase> listener = mListener.promote();
    if (listener != NULL) {
        int64_t positionUs, delayUs;
        int32_t positionMs = 0;
        listener->getCurrentPosition(&positionMs);
        positionUs = positionMs * 1000ll;

        if (timeUs <= positionUs + kAdjustmentProcessingTimeUs) {
            delayUs = 0;
        } else {
            delayUs = timeUs - positionUs - kAdjustmentProcessingTimeUs;
        }
        postTextEventDelayUs(parcel, delayUs);
    }
}

void TimedTextPlayer::postTextEventDelayUs(const sp<ParcelEvent>& parcel, int64_t delayUs) {
    sp<MediaPlayerBase> listener = mListener.promote();
    if (listener != NULL) {
        sp<AMessage> msg = new AMessage(kWhatSendSubtitle, id());
        msg->setInt32("generation", mSendSubtitleGeneration);
        if (parcel != NULL) {
            msg->setObject("subtitle", parcel);
        }
        msg->post(delayUs);
    }
}

void TimedTextPlayer::notifyError(int error) {
    sp<MediaPlayerBase> listener = mListener.promote();
    if (listener != NULL) {
        listener->sendEvent(MEDIA_INFO, MEDIA_INFO_TIMED_TEXT_ERROR, error);
    }
}

void TimedTextPlayer::notifyListener(const Parcel *parcel) {
    sp<MediaPlayerBase> listener = mListener.promote();
    if (listener != NULL) {
        if (parcel != NULL && (parcel->dataSize() > 0)) {
            listener->sendEvent(MEDIA_TIMED_TEXT, 0, 0, parcel);
        } else {  // send an empty timed text to clear the screen
            listener->sendEvent(MEDIA_TIMED_TEXT);
        }
    }
}

}  // namespace android
