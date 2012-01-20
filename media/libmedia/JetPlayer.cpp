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

//#define LOG_NDEBUG 0
#define LOG_TAG "JetPlayer-C"

#include <utils/Log.h>
#include <utils/threads.h>

#include <media/JetPlayer.h>


namespace android
{

static const int MIX_NUM_BUFFERS = 4;
static const S_EAS_LIB_CONFIG* pLibConfig = NULL;

//-------------------------------------------------------------------------------------------------
JetPlayer::JetPlayer(jobject javaJetPlayer, int maxTracks, int trackBufferSize) :
        mEventCallback(NULL),
        mJavaJetPlayerRef(javaJetPlayer),
        mTid(-1),
        mRender(false),
        mPaused(false),
        mMaxTracks(maxTracks),
        mEasData(NULL),
        mEasJetFileLoc(NULL),
        mAudioTrack(NULL),
        mTrackBufferSize(trackBufferSize)
{
    ALOGV("JetPlayer constructor");
    mPreviousJetStatus.currentUserID = -1;
    mPreviousJetStatus.segmentRepeatCount = -1;
    mPreviousJetStatus.numQueuedSegments = -1;
    mPreviousJetStatus.paused = true;
}

//-------------------------------------------------------------------------------------------------
JetPlayer::~JetPlayer()
{
    ALOGV("~JetPlayer");
    release();

}

//-------------------------------------------------------------------------------------------------
int JetPlayer::init()
{
    //Mutex::Autolock lock(&mMutex);

    EAS_RESULT result;

    // retrieve the EAS library settings
    if (pLibConfig == NULL)
        pLibConfig = EAS_Config();
    if (pLibConfig == NULL) {
        LOGE("JetPlayer::init(): EAS library configuration could not be retrieved, aborting.");
        return EAS_FAILURE;
    }

    // init the EAS library
    result = EAS_Init(&mEasData);
    if( result != EAS_SUCCESS) {
        LOGE("JetPlayer::init(): Error initializing Sonivox EAS library, aborting.");
        mState = EAS_STATE_ERROR;
        return result;
    }
    // init the JET library with the default app event controller range
    result = JET_Init(mEasData, NULL, sizeof(S_JET_CONFIG));
    if( result != EAS_SUCCESS) {
        LOGE("JetPlayer::init(): Error initializing JET library, aborting.");
        mState = EAS_STATE_ERROR;
        return result;
    }

    // create the output AudioTrack
    mAudioTrack = new AudioTrack();
    mAudioTrack->set(AUDIO_STREAM_MUSIC,  //TODO parametrize this
            pLibConfig->sampleRate,
            1, // format = PCM 16bits per sample,
            (pLibConfig->numChannels == 2) ? AUDIO_CHANNEL_OUT_STEREO : AUDIO_CHANNEL_OUT_MONO,
            mTrackBufferSize,
            0);

    // create render and playback thread
    {
        Mutex::Autolock l(mMutex);
        ALOGV("JetPlayer::init(): trying to start render thread");
        createThreadEtc(renderThread, this, "jetRenderThread", ANDROID_PRIORITY_AUDIO);
        mCondition.wait(mMutex);
    }
    if (mTid > 0) {
        // render thread started, we're ready
        ALOGV("JetPlayer::init(): render thread(%d) successfully started.", mTid);
        mState = EAS_STATE_READY;
    } else {
        LOGE("JetPlayer::init(): failed to start render thread.");
        mState = EAS_STATE_ERROR;
        return EAS_FAILURE;
    }

    return EAS_SUCCESS;
}

void JetPlayer::setEventCallback(jetevent_callback eventCallback)
{
    Mutex::Autolock l(mMutex);
    mEventCallback = eventCallback;
}

//-------------------------------------------------------------------------------------------------
int JetPlayer::release()
{
    ALOGV("JetPlayer::release()");
    Mutex::Autolock lock(mMutex);
    mPaused = true;
    mRender = false;
    if (mEasData) {
        JET_Pause(mEasData);
        JET_CloseFile(mEasData);
        JET_Shutdown(mEasData);
        EAS_Shutdown(mEasData);
    }
    if (mEasJetFileLoc) {
        free(mEasJetFileLoc);
        mEasJetFileLoc = NULL;
    }
    if (mAudioTrack) {
        mAudioTrack->stop();
        mAudioTrack->flush();
        delete mAudioTrack;
        mAudioTrack = NULL;
    }
    if (mAudioBuffer) {
        delete mAudioBuffer;
        mAudioBuffer = NULL;
    }
    mEasData = NULL;
    
    return EAS_SUCCESS;
}


//-------------------------------------------------------------------------------------------------
int JetPlayer::renderThread(void* p) {

    return ((JetPlayer*)p)->render();
}

//-------------------------------------------------------------------------------------------------
int JetPlayer::render() {
    EAS_RESULT result = EAS_FAILURE;
    EAS_I32 count;
    int temp;
    bool audioStarted = false;

    ALOGV("JetPlayer::render(): entering");

    // allocate render buffer
    mAudioBuffer = 
        new EAS_PCM[pLibConfig->mixBufferSize * pLibConfig->numChannels * MIX_NUM_BUFFERS];
    if (!mAudioBuffer) {
        LOGE("JetPlayer::render(): mAudioBuffer allocate failed");
        goto threadExit;
    }

    // signal main thread that we started
    {
        Mutex::Autolock l(mMutex);
        mTid = gettid();
        ALOGV("JetPlayer::render(): render thread(%d) signal", mTid);
        mCondition.signal();
    }

   while (1) {
    
        mMutex.lock(); // [[[[[[[[ LOCK ---------------------------------------

        if (mEasData == NULL) {
            mMutex.unlock();
            ALOGV("JetPlayer::render(): NULL EAS data, exiting render.");
            goto threadExit;
        }
            
        // nothing to render, wait for client thread to wake us up
        while (!mRender)
        {
            ALOGV("JetPlayer::render(): signal wait");
            if (audioStarted) { 
                mAudioTrack->pause(); 
                // we have to restart the playback once we start rendering again
                audioStarted = false;
            }
            mCondition.wait(mMutex);
            ALOGV("JetPlayer::render(): signal rx'd");
        }
        
        // render midi data into the input buffer
        int num_output = 0;
        EAS_PCM* p = mAudioBuffer;
        for (int i = 0; i < MIX_NUM_BUFFERS; i++) {
            result = EAS_Render(mEasData, p, pLibConfig->mixBufferSize, &count);
            if (result != EAS_SUCCESS) {
                LOGE("JetPlayer::render(): EAS_Render returned error %ld", result);
            }
            p += count * pLibConfig->numChannels;
            num_output += count * pLibConfig->numChannels * sizeof(EAS_PCM);
            
             // send events that were generated (if any) to the event callback
            fireEventsFromJetQueue();
        }

        // update playback state
        //ALOGV("JetPlayer::render(): updating state");
        JET_Status(mEasData, &mJetStatus);
        fireUpdateOnStatusChange();
        mPaused = mJetStatus.paused;

        mMutex.unlock(); // UNLOCK ]]]]]]]] -----------------------------------

        // check audio output track
        if (mAudioTrack == NULL) {
            LOGE("JetPlayer::render(): output AudioTrack was not created");
            goto threadExit;
        }

        // Write data to the audio hardware
        //ALOGV("JetPlayer::render(): writing to audio output");
        if ((temp = mAudioTrack->write(mAudioBuffer, num_output)) < 0) {
            LOGE("JetPlayer::render(): Error in writing:%d",temp);
            return temp;
        }

        // start audio output if necessary
        if (!audioStarted) {
            ALOGV("JetPlayer::render(): starting audio playback");
            mAudioTrack->start();
            audioStarted = true;
        }

    }//while (1)

threadExit:
    if (mAudioTrack) {
        mAudioTrack->stop();
        mAudioTrack->flush();
    }
    if (mAudioBuffer) {
        delete [] mAudioBuffer;
        mAudioBuffer = NULL;
    }
    mMutex.lock();
    mTid = -1;
    mCondition.signal();
    mMutex.unlock();
    return result;
}


//-------------------------------------------------------------------------------------------------
// fire up an update if any of the status fields has changed
// precondition: mMutex locked
void JetPlayer::fireUpdateOnStatusChange()
{
    if(  (mJetStatus.currentUserID      != mPreviousJetStatus.currentUserID)
       ||(mJetStatus.segmentRepeatCount != mPreviousJetStatus.segmentRepeatCount) ) {
        if(mEventCallback)  {
            mEventCallback(
                JetPlayer::JET_USERID_UPDATE,
                mJetStatus.currentUserID,
                mJetStatus.segmentRepeatCount,
                mJavaJetPlayerRef);
        }
        mPreviousJetStatus.currentUserID      = mJetStatus.currentUserID;
        mPreviousJetStatus.segmentRepeatCount = mJetStatus.segmentRepeatCount;
    }

    if(mJetStatus.numQueuedSegments != mPreviousJetStatus.numQueuedSegments) {
        if(mEventCallback)  {
            mEventCallback(
                JetPlayer::JET_NUMQUEUEDSEGMENT_UPDATE,
                mJetStatus.numQueuedSegments,
                -1,
                mJavaJetPlayerRef);
        }
        mPreviousJetStatus.numQueuedSegments  = mJetStatus.numQueuedSegments;
    }

    if(mJetStatus.paused != mPreviousJetStatus.paused) {
        if(mEventCallback)  {
            mEventCallback(JetPlayer::JET_PAUSE_UPDATE,
                mJetStatus.paused,
                -1,
                mJavaJetPlayerRef);
        }
        mPreviousJetStatus.paused = mJetStatus.paused;
    }

}


//-------------------------------------------------------------------------------------------------
// fire up all the JET events in the JET engine queue (until the queue is empty)
// precondition: mMutex locked
void JetPlayer::fireEventsFromJetQueue()
{
    if(!mEventCallback) {
        // no callback, just empty the event queue
        while (JET_GetEvent(mEasData, NULL, NULL)) { }
        return;
    }

    EAS_U32 rawEvent;
    while (JET_GetEvent(mEasData, &rawEvent, NULL)) {
        mEventCallback(
            JetPlayer::JET_EVENT,
            rawEvent,
            -1,
            mJavaJetPlayerRef);
    }
}


//-------------------------------------------------------------------------------------------------
int JetPlayer::loadFromFile(const char* path)
{
    ALOGV("JetPlayer::loadFromFile(): path=%s", path);

    Mutex::Autolock lock(mMutex);

    mEasJetFileLoc = (EAS_FILE_LOCATOR) malloc(sizeof(EAS_FILE));
    memset(mJetFilePath, 0, 256);
    strncpy(mJetFilePath, path, strlen(path));
    mEasJetFileLoc->path = mJetFilePath;

    mEasJetFileLoc->fd = 0;
    mEasJetFileLoc->length = 0;
    mEasJetFileLoc->offset = 0;

    EAS_RESULT result = JET_OpenFile(mEasData, mEasJetFileLoc);
    if(result != EAS_SUCCESS)
        mState = EAS_STATE_ERROR;
    else
        mState = EAS_STATE_OPEN;
    return( result );
}


//-------------------------------------------------------------------------------------------------
int JetPlayer::loadFromFD(const int fd, const long long offset, const long long length)
{
    ALOGV("JetPlayer::loadFromFD(): fd=%d offset=%lld length=%lld", fd, offset, length);
    
    Mutex::Autolock lock(mMutex);

    mEasJetFileLoc = (EAS_FILE_LOCATOR) malloc(sizeof(EAS_FILE));
    mEasJetFileLoc->fd = fd;
    mEasJetFileLoc->offset = offset;
    mEasJetFileLoc->length = length;
    mEasJetFileLoc->path = NULL;
    
    EAS_RESULT result = JET_OpenFile(mEasData, mEasJetFileLoc);
    if(result != EAS_SUCCESS)
        mState = EAS_STATE_ERROR;
    else
        mState = EAS_STATE_OPEN;
    return( result );
}


//-------------------------------------------------------------------------------------------------
int JetPlayer::closeFile()
{
    Mutex::Autolock lock(mMutex);
    return JET_CloseFile(mEasData);
}


//-------------------------------------------------------------------------------------------------
int JetPlayer::play()
{
    ALOGV("JetPlayer::play(): entering");
    Mutex::Autolock lock(mMutex);

    EAS_RESULT result = JET_Play(mEasData);

    mPaused = false;
    mRender = true;

    JET_Status(mEasData, &mJetStatus);
    this->dumpJetStatus(&mJetStatus);
    
    fireUpdateOnStatusChange();

    // wake up render thread
    ALOGV("JetPlayer::play(): wakeup render thread");
    mCondition.signal();

    return result;
}

//-------------------------------------------------------------------------------------------------
int JetPlayer::pause()
{
    Mutex::Autolock lock(mMutex);
    mPaused = true;
    EAS_RESULT result = JET_Pause(mEasData);

    mRender = false;

    JET_Status(mEasData, &mJetStatus);
    this->dumpJetStatus(&mJetStatus);
    fireUpdateOnStatusChange();


    return result;
}


//-------------------------------------------------------------------------------------------------
int JetPlayer::queueSegment(int segmentNum, int libNum, int repeatCount, int transpose,
        EAS_U32 muteFlags, EAS_U8 userID)
{
    ALOGV("JetPlayer::queueSegment segmentNum=%d, libNum=%d, repeatCount=%d, transpose=%d",
        segmentNum, libNum, repeatCount, transpose);
    Mutex::Autolock lock(mMutex);
    return JET_QueueSegment(mEasData, segmentNum, libNum, repeatCount, transpose, muteFlags, userID);
}

//-------------------------------------------------------------------------------------------------
int JetPlayer::setMuteFlags(EAS_U32 muteFlags, bool sync)
{
    Mutex::Autolock lock(mMutex);
    return JET_SetMuteFlags(mEasData, muteFlags, sync);
}

//-------------------------------------------------------------------------------------------------
int JetPlayer::setMuteFlag(int trackNum, bool muteFlag, bool sync)
{
    Mutex::Autolock lock(mMutex);
    return JET_SetMuteFlag(mEasData, trackNum, muteFlag, sync);
}

//-------------------------------------------------------------------------------------------------
int JetPlayer::triggerClip(int clipId)
{
    ALOGV("JetPlayer::triggerClip clipId=%d", clipId);
    Mutex::Autolock lock(mMutex);
    return JET_TriggerClip(mEasData, clipId);
}

//-------------------------------------------------------------------------------------------------
int JetPlayer::clearQueue()
{
    ALOGV("JetPlayer::clearQueue");
    Mutex::Autolock lock(mMutex);
    return JET_Clear_Queue(mEasData);
}

//-------------------------------------------------------------------------------------------------
void JetPlayer::dump()
{
    LOGE("JetPlayer dump: JET file=%s", mEasJetFileLoc->path);
}

void JetPlayer::dumpJetStatus(S_JET_STATUS* pJetStatus)
{
    if(pJetStatus!=NULL)
        ALOGV(">> current JET player status: userID=%d segmentRepeatCount=%d numQueuedSegments=%d paused=%d",
                pJetStatus->currentUserID, pJetStatus->segmentRepeatCount,
                pJetStatus->numQueuedSegments, pJetStatus->paused);
    else
        LOGE(">> JET player status is NULL");
}


} // end namespace android

