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

#ifndef JETPLAYER_H_
#define JETPLAYER_H_

#include <utils/threads.h>

#include <libsonivox/jet.h>
#include <libsonivox/eas_types.h>
#include <media/AudioTrack.h>
#include <media/MidiIoWrapper.h>


namespace android {

typedef void (*jetevent_callback)(int eventType, int val1, int val2, void *cookie);

class JetPlayer {

public:

    // to keep in sync with the JetPlayer class constants
    // defined in frameworks/base/media/java/android/media/JetPlayer.java
    static const int JET_EVENT                   = 1;
    static const int JET_USERID_UPDATE           = 2;
    static const int JET_NUMQUEUEDSEGMENT_UPDATE = 3;
    static const int JET_PAUSE_UPDATE            = 4;

    JetPlayer(void *javaJetPlayer,
            int maxTracks = 32,
            int trackBufferSize = 1200);
    ~JetPlayer();
    int init();
    int release();

    int loadFromFile(const char* url);
    int loadFromFD(const int fd, const long long offset, const long long length);
    int closeFile();
    int play();
    int pause();
    int queueSegment(int segmentNum, int libNum, int repeatCount, int transpose,
            EAS_U32 muteFlags, EAS_U8 userID);
    int setMuteFlags(EAS_U32 muteFlags, bool sync);
    int setMuteFlag(int trackNum, bool muteFlag, bool sync);
    int triggerClip(int clipId);
    int clearQueue();

    void setEventCallback(jetevent_callback callback);

    int getMaxTracks() { return mMaxTracks; };


private:
    int                 render();
    void                fireUpdateOnStatusChange();
    void                fireEventsFromJetQueue();

    JetPlayer() {} // no default constructor
    void dump();
    void dumpJetStatus(S_JET_STATUS* pJetStatus);

    jetevent_callback   mEventCallback;

    void*               mJavaJetPlayerRef;
    Mutex               mMutex; // mutex to sync the render and playback thread with the JET calls
    pid_t               mTid;
    Condition           mCondition;
    volatile bool       mRender;
    bool                mPaused;

    EAS_STATE           mState;
    int*                mMemFailedVar;

    int                 mMaxTracks; // max number of MIDI tracks, usually 32
    EAS_DATA_HANDLE     mEasData;
    MidiIoWrapper*      mIoWrapper;
    EAS_PCM*            mAudioBuffer;// EAS renders the MIDI data into this buffer,
    sp<AudioTrack>      mAudioTrack; // and we play it in this audio track
    int                 mTrackBufferSize;
    S_JET_STATUS        mJetStatus;
    S_JET_STATUS        mPreviousJetStatus;

    class JetPlayerThread : public Thread {
    public:
        JetPlayerThread(JetPlayer *player) : mPlayer(player) {
        }

    protected:
        virtual ~JetPlayerThread() {}

    private:
        JetPlayer *mPlayer;

        bool threadLoop() {
            int result;
            result = mPlayer->render();
            return false;
        }

        JetPlayerThread(const JetPlayerThread &);
        JetPlayerThread &operator=(const JetPlayerThread &);
    };

    sp<JetPlayerThread> mThread;

}; // end class JetPlayer

} // end namespace android



#endif /*JETPLAYER_H_*/
