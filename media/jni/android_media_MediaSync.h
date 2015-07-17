/*
 * Copyright 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _ANDROID_MEDIA_MEDIASYNC_H_
#define _ANDROID_MEDIA_MEDIASYNC_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/MediaSync.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>

namespace android {

struct AudioPlaybackRate;
class AudioTrack;
class IGraphicBufferProducer;
struct MediaClock;
class MediaSync;

struct JMediaSync : public RefBase {
    JMediaSync();

    status_t setSurface(const sp<IGraphicBufferProducer> &bufferProducer);
    status_t setAudioTrack(const sp<AudioTrack> &audioTrack);

    status_t createInputSurface(sp<IGraphicBufferProducer>* bufferProducer);

    status_t updateQueuedAudioData(int sizeInBytes, int64_t presentationTimeUs);

    status_t getPlayTimeForPendingAudioFrames(int64_t *outTimeUs);

    status_t setPlaybackParams(const AudioPlaybackRate& rate);
    void getPlaybackParams(AudioPlaybackRate* rate /* nonnull */);
    status_t setSyncParams(const AVSyncSettings& syncParams);
    void getSyncParams(AVSyncSettings* syncParams /* nonnull */);
    status_t setVideoFrameRateHint(float rate);
    float getVideoFrameRate();

    void flush();

    sp<const MediaClock> getMediaClock();

protected:
    virtual ~JMediaSync();

private:
    sp<MediaSync> mSync;

    DISALLOW_EVIL_CONSTRUCTORS(JMediaSync);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIASYNC_H_
