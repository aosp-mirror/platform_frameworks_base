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

// Note that the name MidiFile is misleading; this actually represents a MIDI file player
class MidiFile : public MediaPlayerInterface {
public:
                        MidiFile();
                        ~MidiFile();

    virtual status_t    initCheck();

    virtual status_t    setDataSource(
            const char* path, const KeyedVector<String8, String8> *headers);

    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length);
    virtual status_t    setVideoSurfaceTexture(
                                const sp<ISurfaceTexture>& surfaceTexture)
                            { return UNKNOWN_ERROR; }
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
    virtual status_t    invoke(const Parcel& request, Parcel *reply) {
        return INVALID_OPERATION;
    }
    virtual status_t    setParameter(int key, const Parcel &request) {
        return INVALID_OPERATION;
    }
    virtual status_t    getParameter(int key, Parcel *reply) {
        return INVALID_OPERATION;
    }


private:
            status_t    createOutputTrack();
            status_t    reset_nosync();
            int         render();
            void        updateState(){ EAS_State(mEasData, mEasHandle, &mState); }

    Mutex               mMutex;
    Condition           mCondition;
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

    class MidiFileThread : public Thread {
    public:
        MidiFileThread(MidiFile *midiPlayer) : mMidiFile(midiPlayer) {
        }

    protected:
        virtual ~MidiFileThread() {}

    private:
        MidiFile *mMidiFile;

        bool threadLoop() {
            int result;
            result = mMidiFile->render();
            return false;
        }

        MidiFileThread(const MidiFileThread &);
        MidiFileThread &operator=(const MidiFileThread &);
    };

    sp<MidiFileThread> mThread;
};

}; // namespace android

#endif // ANDROID_MIDIFILE_H
