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

#ifndef TIMEDTEXT_PLAYER_H_
#define TIMEDTEXT_PLAYER_H_

#include <binder/Parcel.h>
#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AHandler.h>
#include <media/stagefright/MediaSource.h>
#include <utils/RefBase.h>

#include "TimedTextSource.h"

namespace android {

class AMessage;
class MediaPlayerBase;
class TimedTextDriver;
class TimedTextSource;

class TimedTextPlayer : public AHandler {
public:
    TimedTextPlayer(const wp<MediaPlayerBase> &listener);

    virtual ~TimedTextPlayer();

    void start();
    void pause();
    void resume();
    void seekToAsync(int64_t timeUs);
    void setDataSource(sp<TimedTextSource> source);

protected:
    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    enum {
        kWhatPause = 'paus',
        kWhatSeek = 'seek',
        kWhatSendSubtitle = 'send',
        kWhatSetSource = 'ssrc',
    };

    // To add Parcel into an AMessage as an object, it should be 'RefBase'.
    struct ParcelEvent : public RefBase {
        Parcel parcel;
    };

    wp<MediaPlayerBase> mListener;
    sp<TimedTextSource> mSource;
    int32_t mSendSubtitleGeneration;

    void doSeekAndRead(int64_t seekTimeUs);
    void doRead(MediaSource::ReadOptions* options = NULL);
    void onTextEvent();
    void postTextEvent(const sp<ParcelEvent>& parcel = NULL, int64_t timeUs = -1);
    void notifyListener(int msg, const Parcel *parcel = NULL);

    DISALLOW_EVIL_CONSTRUCTORS(TimedTextPlayer);
};

}  // namespace android

#endif  // TIMEDTEXT_PLAYER_H_
