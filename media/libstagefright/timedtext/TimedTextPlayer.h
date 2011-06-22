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

#ifndef TIMEDTEXT_PLAYER_H_

#define TIMEDTEXT_PLAYER_H_

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AString.h>

#include "include/TimedEventQueue.h"
#include "TimedTextParser.h"

namespace android {

class MediaSource;
class AwesomePlayer;
class MediaBuffer;

class TimedTextPlayer {
public:
    TimedTextPlayer(AwesomePlayer *observer,
                    const wp<MediaPlayerBase> &listener,
                    TimedEventQueue *queue);

    virtual ~TimedTextPlayer();

    // index: the index of the text track which will
    // be turned on
    status_t start(uint8_t index);

    void pause();

    void resume();

    status_t seekTo(int64_t time_us);

    void addTextSource(sp<MediaSource> source);

    status_t setTimedTextTrackIndex(int32_t index);
    status_t setParameter(int key, const Parcel &request);

private:
    enum TextType {
        kNoText        = 0,
        kInBandText    = 1,
        kOutOfBandText = 2,
    };

    Mutex mLock;

    sp<MediaSource> mSource;
    sp<DataSource> mOutOfBandSource;

    bool mSeeking;
    int64_t mSeekTimeUs;

    bool mStarted;

    sp<TimedEventQueue::Event> mTextEvent;
    bool mTextEventPending;

    TimedEventQueue *mQueue;

    wp<MediaPlayerBase> mListener;
    AwesomePlayer *mObserver;

    MediaBuffer *mTextBuffer;
    Parcel mData;

    // for in-band timed text
    Vector<sp<MediaSource> > mTextTrackVector;

    // for out-of-band timed text
    struct OutOfBandText {
        TimedTextParser::FileType type;
        sp<DataSource> source;
    };
    Vector<OutOfBandText > mTextOutOfBandVector;

    sp<TimedTextParser> mTextParser;
    AString mText;

    TextType mTextType;

    void reset();

    void onTextEvent();
    void postTextEvent(int64_t delayUs = -1);
    void cancelTextEvent();

    void notifyListener(int msg, const Parcel *parcel = NULL);

    status_t extractAndAppendLocalDescriptions(int64_t timeUs);
    status_t extractAndSendGlobalDescriptions();

    DISALLOW_EVIL_CONSTRUCTORS(TimedTextPlayer);
};

}  // namespace android

#endif  // TIMEDTEXT_PLAYER_H_
