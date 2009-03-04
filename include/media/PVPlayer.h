/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_PVPLAYER_H
#define ANDROID_PVPLAYER_H

#include <utils/Errors.h>
#include <media/MediaPlayerInterface.h>

#define MAX_OPENCORE_INSTANCES 25

#ifdef MAX_OPENCORE_INSTANCES
#include <cutils/atomic.h>
#endif

class PlayerDriver;

namespace android {

class PVPlayer : public MediaPlayerInterface
{
public:
                        PVPlayer();
    virtual             ~PVPlayer();

    virtual status_t    initCheck();
    virtual status_t    setDataSource(const char *url);
    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length);
    virtual status_t    setVideoSurface(const sp<ISurface>& surface);
    virtual status_t    prepare();
    virtual status_t    prepareAsync();
    virtual status_t    start();
    virtual status_t    stop();
    virtual status_t    pause();
    virtual bool        isPlaying();
    virtual status_t    seekTo(int msec);
    virtual status_t    getCurrentPosition(int *msec);
    virtual status_t    getDuration(int *msec);
    virtual status_t    reset();
    virtual status_t    setLooping(int loop);
    virtual player_type playerType() { return PV_PLAYER; }

            // make available to PlayerDriver
            void        sendEvent(int msg, int ext1=0, int ext2=0) { MediaPlayerBase::sendEvent(msg, ext1, ext2); }

private:
    static void         do_nothing(status_t s, void *cookie, bool cancelled) { }
    static void         run_init(status_t s, void *cookie, bool cancelled);
    static void         run_set_video_surface(status_t s, void *cookie, bool cancelled);
    static void         run_set_audio_output(status_t s, void *cookie, bool cancelled);
    static void         run_prepare(status_t s, void *cookie, bool cancelled);

    PlayerDriver*               mPlayerDriver;
    char *                      mDataSourcePath;
    bool                        mIsDataSourceSet;
    sp<ISurface>                mSurface;
    int                         mSharedFd;
    status_t                    mInit;
    int                         mDuration;

#ifdef MAX_OPENCORE_INSTANCES
    static volatile int32_t     sNumInstances;
#endif
};

}; // namespace android

#endif // ANDROID_PVPLAYER_H
