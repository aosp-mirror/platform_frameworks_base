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

#ifndef ANDROID_MIDIFILE_H
#define ANDROID_MIDIFILE_H

#include <media/MediaPlayerInterface.h>
#include <media/AudioTrack.h>
#include <libsonivox/eas.h>

namespace android {

class MidiFile : public MediaPlayerInterface {
public:
                        MidiFile();
                        ~MidiFile();

    virtual status_t    initCheck();
    virtual status_t    setSigBusHandlerStructTLSKey(pthread_key_t key);
    virtual status_t    setDataSource(const char* path);
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
    virtual player_type playerType() { return SONIVOX_PLAYER; }

private:
            status_t    createOutputTrack();
            status_t    reset_nosync();
    static  int         renderThread(void*);
            int         render();
            void        updateState(){ EAS_State(mEasData, mEasHandle, &mState); }

    Mutex               mMutex;
    Condition           mCondition;
    int*                mMemFailedVar;
    EAS_DATA_HANDLE     mEasData;
    EAS_HANDLE          mEasHandle;
    EAS_PCM*            mAudioBuffer;
    EAS_I32             mPlayTime;
    EAS_I32             mDuration;
    EAS_STATE           mState;
    EAS_FILE            mFileLocator;
    int                 mStreamType;
    bool                mLoop;
    volatile bool       mExit;
    bool                mPaused;
    volatile bool       mRender;
    pid_t               mTid;
};

}; // namespace android

#endif // ANDROID_MIDIFILE_H

