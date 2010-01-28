/*
**
** Copyright 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifndef ANDROID_VORBISPLAYER_H
#define ANDROID_VORBISPLAYER_H

#include <utils/threads.h>

#include <media/MediaPlayerInterface.h>
#include <media/AudioTrack.h>

#include "ivorbiscodec.h"
#include "ivorbisfile.h"

#define ANDROID_LOOP_TAG "ANDROID_LOOP"

namespace android {

class VorbisPlayer : public MediaPlayerInterface {
public:
                        VorbisPlayer();
                        ~VorbisPlayer();

    virtual void        onFirstRef();
    virtual status_t    initCheck();

    virtual status_t    setDataSource(
            const char *uri, const KeyedVector<String8, String8> *headers);

    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length);
    virtual status_t    setVideoSurface(const sp<ISurface>& surface) { return UNKNOWN_ERROR; }
    virtual status_t    prepare();
    virtual status_t    prepareAsync();
    virtual status_t    start();
    virtual status_t    stop();
    virtual status_t    seekTo(int msec);
    virtual status_t    pause();
    virtual bool        isPlaying();
    virtual status_t    getCurrentPosition(int* msec);
    virtual status_t    getDuration(int* msec);
    virtual status_t    release();
    virtual status_t    reset();
    virtual status_t    setLooping(int loop);
    virtual player_type playerType() { return VORBIS_PLAYER; }
    virtual status_t    invoke(const Parcel& request, Parcel *reply) {return INVALID_OPERATION;}

private:
            status_t    setdatasource(const char *path, int fd, int64_t offset, int64_t length);
            status_t    reset_nosync();
            status_t    createOutputTrack();
    static  int         renderThread(void*);
            int         render();

    static  size_t      vp_fread(void *, size_t, size_t, void *);
    static  int         vp_fseek(void *, ogg_int64_t, int);
    static  int         vp_fclose(void *);
    static  long        vp_ftell(void *);

    Mutex               mMutex;
    Condition           mCondition;
    FILE*               mFile;
    int64_t             mOffset;
    int64_t             mLength;
    OggVorbis_File      mVorbisFile;
    char*               mAudioBuffer;
    int                 mPlayTime;
    int                 mDuration;
    status_t            mState;
    int                 mStreamType;
    bool                mLoop;
    bool                mAndroidLoop;
    volatile bool       mExit;
    bool                mPaused;
    volatile bool       mRender;
    pid_t               mRenderTid;
};

}; // namespace android

#endif // ANDROID_VORBISPLAYER_H
