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
#include <utils/Errors.h>
#include <utils/RefBase.h>

namespace android {

class AudioTrack;
struct IGraphicBufferProducer;
class MediaSync;

struct JMediaSync : public RefBase {
    JMediaSync();

    status_t configureSurface(const sp<IGraphicBufferProducer> &bufferProducer);
    status_t configureAudioTrack(
            const sp<AudioTrack> &audioTrack, int32_t nativeSampleRateInHz);

    status_t createInputSurface(sp<IGraphicBufferProducer>* bufferProducer);

    status_t updateQueuedAudioData(int sizeInBytes, int64_t presentationTimeUs);

    void setPlaybackRate(float rate);

protected:
    virtual ~JMediaSync();

private:
    sp<MediaSync> mSync;

    DISALLOW_EVIL_CONSTRUCTORS(JMediaSync);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIASYNC_H_
