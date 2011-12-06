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

#ifndef __DECODER_PUMP_H__
#define __DECODER_PUMP_H__

#include <pthread.h>

#include <media/stagefright/MediaSource.h>
#include <utils/LinearTransform.h>
#include <utils/List.h>
#include <utils/threads.h>

namespace android {

class MetaData;
class OMXClient;
class TimedAudioTrack;

class AAH_DecoderPump : public MediaSource {
  public:
    explicit AAH_DecoderPump(OMXClient& omx);
    status_t initCheck();

    status_t queueForDecode(MediaBuffer* buf);

    status_t init(sp<MetaData> params);
    status_t shutdown();

    void setRenderTSTransform(const LinearTransform& trans);
    void setRenderVolume(uint8_t volume);
    bool isAboutToUnderflow(int64_t threshold);
    bool getStatus() const { return thread_status_; }

    // MediaSource methods
    virtual status_t     start(MetaData *params) { return OK; }
    virtual sp<MetaData> getFormat() { return format_; }
    virtual status_t     stop() { return OK; }
    virtual status_t     read(MediaBuffer **buffer,
                              const ReadOptions *options);

  protected:
    virtual ~AAH_DecoderPump();

  private:
    class ThreadWrapper : public Thread {
      public:
        friend class AAH_DecoderPump;
        explicit ThreadWrapper(AAH_DecoderPump* owner);

      private:
        virtual bool threadLoop();
        AAH_DecoderPump* owner_;

        DISALLOW_EVIL_CONSTRUCTORS(ThreadWrapper);
    };

    void* workThread();
    virtual status_t shutdown_l();
    void queueToRenderer(MediaBuffer* decoded_sample);
    void stopAndCleanupRenderer();

    sp<MetaData>        format_;
    int32_t             format_channels_;
    int32_t             format_sample_rate_;

    sp<MediaSource>     decoder_;
    OMXClient&          omx_;
    Mutex               init_lock_;

    sp<ThreadWrapper>   thread_;
    Condition           thread_cond_;
    Mutex               thread_lock_;
    status_t            thread_status_;

    Mutex               render_lock_;
    TimedAudioTrack*    renderer_;
    bool                last_queued_pts_valid_;
    int64_t             last_queued_pts_;
    bool                last_ts_transform_valid_;
    LinearTransform     last_ts_transform_;
    uint8_t             last_volume_;

    // protected by the thread_lock_
    typedef List<MediaBuffer*> MBQueue;
    MBQueue in_queue_;

    DISALLOW_EVIL_CONSTRUCTORS(AAH_DecoderPump);
};

}  // namespace android
#endif  // __DECODER_PUMP_H__
