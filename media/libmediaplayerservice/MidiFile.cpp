/* MidiFile.cpp
**
** Copyright 2007, The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "MidiFile"
#include "utils/Log.h"

#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <sched.h>
#include <utils/threads.h>
#include <libsonivox/eas_reverb.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <unistd.h>

#include <system/audio.h>

#include "MidiFile.h"

// ----------------------------------------------------------------------------

namespace android {

// ----------------------------------------------------------------------------

// The midi engine buffers are a bit small (128 frames), so we batch them up
static const int NUM_BUFFERS = 4;

// TODO: Determine appropriate return codes
static status_t ERROR_NOT_OPEN = -1;
static status_t ERROR_OPEN_FAILED = -2;
static status_t ERROR_EAS_FAILURE = -3;
static status_t ERROR_ALLOCATE_FAILED = -4;

static const S_EAS_LIB_CONFIG* pLibConfig = NULL;

MidiFile::MidiFile() :
    mEasData(NULL), mEasHandle(NULL), mAudioBuffer(NULL),
    mPlayTime(-1), mDuration(-1), mState(EAS_STATE_ERROR),
    mStreamType(AUDIO_STREAM_MUSIC), mLoop(false), mExit(false),
    mPaused(false), mRender(false), mTid(-1)
{
    ALOGV("constructor");

    mFileLocator.path = NULL;
    mFileLocator.fd = -1;
    mFileLocator.offset = 0;
    mFileLocator.length = 0;

    // get the library configuration and do sanity check
    if (pLibConfig == NULL)
        pLibConfig = EAS_Config();
    if ((pLibConfig == NULL) || (LIB_VERSION != pLibConfig->libVersion)) {
        ALOGE("EAS library/header mismatch");
        goto Failed;
    }

    // initialize EAS library
    if (EAS_Init(&mEasData) != EAS_SUCCESS) {
        ALOGE("EAS_Init failed");
        goto Failed;
    }

    // select reverb preset and enable
    EAS_SetParameter(mEasData, EAS_MODULE_REVERB, EAS_PARAM_REVERB_PRESET, EAS_PARAM_REVERB_CHAMBER);
    EAS_SetParameter(mEasData, EAS_MODULE_REVERB, EAS_PARAM_REVERB_BYPASS, EAS_FALSE);

    // create playback thread
    {
        Mutex::Autolock l(mMutex);
        mThread = new MidiFileThread(this);
        mThread->run("midithread", ANDROID_PRIORITY_AUDIO);
        mCondition.wait(mMutex);
        ALOGV("thread started");
    }

    // indicate success
    if (mTid > 0) {
        ALOGV(" render thread(%d) started", mTid);
        mState = EAS_STATE_READY;
    }

Failed:
    return;
}

status_t MidiFile::initCheck()
{
    if (mState == EAS_STATE_ERROR) return ERROR_EAS_FAILURE;
    return NO_ERROR;
}

MidiFile::~MidiFile() {
    ALOGV("MidiFile destructor");
    release();
}

status_t MidiFile::setDataSource(
        const char* path, const KeyedVector<String8, String8> *) {
    ALOGV("MidiFile::setDataSource url=%s", path);
    Mutex::Autolock lock(mMutex);

    // file still open?
    if (mEasHandle) {
        reset_nosync();
    }

    // open file and set paused state
    mFileLocator.path = strdup(path);
    mFileLocator.fd = -1;
    mFileLocator.offset = 0;
    mFileLocator.length = 0;
    EAS_RESULT result = EAS_OpenFile(mEasData, &mFileLocator, &mEasHandle);
    if (result == EAS_SUCCESS) {
        updateState();
    }

    if (result != EAS_SUCCESS) {
        ALOGE("EAS_OpenFile failed: [%d]", (int)result);
        mState = EAS_STATE_ERROR;
        return ERROR_OPEN_FAILED;
    }

    mState = EAS_STATE_OPEN;
    mPlayTime = 0;
    return NO_ERROR;
}

status_t MidiFile::setDataSource(int fd, int64_t offset, int64_t length)
{
    ALOGV("MidiFile::setDataSource fd=%d", fd);
    Mutex::Autolock lock(mMutex);

    // file still open?
    if (mEasHandle) {
        reset_nosync();
    }

    // open file and set paused state
    mFileLocator.fd = dup(fd);
    mFileLocator.offset = offset;
    mFileLocator.length = length;
    EAS_RESULT result = EAS_OpenFile(mEasData, &mFileLocator, &mEasHandle);
    updateState();

    if (result != EAS_SUCCESS) {
        ALOGE("EAS_OpenFile failed: [%d]", (int)result);
        mState = EAS_STATE_ERROR;
        return ERROR_OPEN_FAILED;
    }

    mState = EAS_STATE_OPEN;
    mPlayTime = 0;
    return NO_ERROR;
}

status_t MidiFile::prepare()
{
    ALOGV("MidiFile::prepare");
    Mutex::Autolock lock(mMutex);
    if (!mEasHandle) {
        return ERROR_NOT_OPEN;
    }
    EAS_RESULT result;
    if ((result = EAS_Prepare(mEasData, mEasHandle)) != EAS_SUCCESS) {
        ALOGE("EAS_Prepare failed: [%ld]", result);
        return ERROR_EAS_FAILURE;
    }
    updateState();
    return NO_ERROR;
}

status_t MidiFile::prepareAsync()
{
    ALOGV("MidiFile::prepareAsync");
    status_t ret = prepare();

    // don't hold lock during callback
    if (ret == NO_ERROR) {
        sendEvent(MEDIA_PREPARED);
    } else {
        sendEvent(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN, ret);
    }
    return ret;
}

status_t MidiFile::start()
{
    ALOGV("MidiFile::start");
    Mutex::Autolock lock(mMutex);
    if (!mEasHandle) {
        return ERROR_NOT_OPEN;
    }

    // resuming after pause?
    if (mPaused) {
        if (EAS_Resume(mEasData, mEasHandle) != EAS_SUCCESS) {
            return ERROR_EAS_FAILURE;
        }
        mPaused = false;
        updateState();
    }

    mRender = true;

    // wake up render thread
    ALOGV("  wakeup render thread");
    mCondition.signal();
    return NO_ERROR;
}

status_t MidiFile::stop()
{
    ALOGV("MidiFile::stop");
    Mutex::Autolock lock(mMutex);
    if (!mEasHandle) {
        return ERROR_NOT_OPEN;
    }
    if (!mPaused && (mState != EAS_STATE_STOPPED)) {
        EAS_RESULT result = EAS_Pause(mEasData, mEasHandle);
        if (result != EAS_SUCCESS) {
            ALOGE("EAS_Pause returned error %ld", result);
            return ERROR_EAS_FAILURE;
        }
    }
    mPaused = false;
    return NO_ERROR;
}

status_t MidiFile::seekTo(int position)
{
    ALOGV("MidiFile::seekTo %d", position);
    // hold lock during EAS calls
    {
        Mutex::Autolock lock(mMutex);
        if (!mEasHandle) {
            return ERROR_NOT_OPEN;
        }
        EAS_RESULT result;
        if ((result = EAS_Locate(mEasData, mEasHandle, position, false))
                != EAS_SUCCESS)
        {
            ALOGE("EAS_Locate returned %ld", result);
            return ERROR_EAS_FAILURE;
        }
        EAS_GetLocation(mEasData, mEasHandle, &mPlayTime);
    }
    sendEvent(MEDIA_SEEK_COMPLETE);
    return NO_ERROR;
}

status_t MidiFile::pause()
{
    ALOGV("MidiFile::pause");
    Mutex::Autolock lock(mMutex);
    if (!mEasHandle) {
        return ERROR_NOT_OPEN;
    }
    if ((mState == EAS_STATE_PAUSING) || (mState == EAS_STATE_PAUSED)) return NO_ERROR;
    if (EAS_Pause(mEasData, mEasHandle) != EAS_SUCCESS) {
        return ERROR_EAS_FAILURE;
    }
    mPaused = true;
    return NO_ERROR;
}

bool MidiFile::isPlaying()
{
    ALOGV("MidiFile::isPlaying, mState=%d", int(mState));
    if (!mEasHandle || mPaused) return false;
    return (mState == EAS_STATE_PLAY);
}

status_t MidiFile::getCurrentPosition(int* position)
{
    ALOGV("MidiFile::getCurrentPosition");
    if (!mEasHandle) {
        ALOGE("getCurrentPosition(): file not open");
        return ERROR_NOT_OPEN;
    }
    if (mPlayTime < 0) {
        ALOGE("getCurrentPosition(): mPlayTime = %ld", mPlayTime);
        return ERROR_EAS_FAILURE;
    }
    *position = mPlayTime;
    return NO_ERROR;
}

status_t MidiFile::getDuration(int* duration)
{

    ALOGV("MidiFile::getDuration");
    {
        Mutex::Autolock lock(mMutex);
        if (!mEasHandle) return ERROR_NOT_OPEN;
        *duration = mDuration;
    }

    // if no duration cached, get the duration
    // don't need a lock here because we spin up a new engine
    if (*duration < 0) {
        EAS_I32 temp;
        EAS_DATA_HANDLE easData = NULL;
        EAS_HANDLE easHandle = NULL;
        EAS_RESULT result = EAS_Init(&easData);
        if (result == EAS_SUCCESS) {
            result = EAS_OpenFile(easData, &mFileLocator, &easHandle);
        }
        if (result == EAS_SUCCESS) {
            result = EAS_Prepare(easData, easHandle);
        }
        if (result == EAS_SUCCESS) {
            result = EAS_ParseMetaData(easData, easHandle, &temp);
        }
        if (easHandle) {
            EAS_CloseFile(easData, easHandle);
        }
        if (easData) {
            EAS_Shutdown(easData);
        }

        if (result != EAS_SUCCESS) {
            return ERROR_EAS_FAILURE;
        }

        // cache successful result
        mDuration = *duration = int(temp);
    }

    return NO_ERROR;
}

status_t MidiFile::release()
{
    ALOGV("MidiFile::release");
    Mutex::Autolock l(mMutex);
    reset_nosync();

    // wait for render thread to exit
    mExit = true;
    mCondition.signal();

    // wait for thread to exit
    if (mAudioBuffer) {
        mCondition.wait(mMutex);
    }

    // release resources
    if (mEasData) {
        EAS_Shutdown(mEasData);
        mEasData = NULL;
    }
    return NO_ERROR;
}

status_t MidiFile::reset()
{
    ALOGV("MidiFile::reset");
    Mutex::Autolock lock(mMutex);
    return reset_nosync();
}

// call only with mutex held
status_t MidiFile::reset_nosync()
{
    ALOGV("MidiFile::reset_nosync");
    // close file
    if (mEasHandle) {
        EAS_CloseFile(mEasData, mEasHandle);
        mEasHandle = NULL;
    }
    if (mFileLocator.path) {
        free((void*)mFileLocator.path);
        mFileLocator.path = NULL;
    }
    if (mFileLocator.fd >= 0) {
        close(mFileLocator.fd);
    }
    mFileLocator.fd = -1;
    mFileLocator.offset = 0;
    mFileLocator.length = 0;

    mPlayTime = -1;
    mDuration = -1;
    mLoop = false;
    mPaused = false;
    mRender = false;
    return NO_ERROR;
}

status_t MidiFile::setLooping(int loop)
{
    ALOGV("MidiFile::setLooping");
    Mutex::Autolock lock(mMutex);
    if (!mEasHandle) {
        return ERROR_NOT_OPEN;
    }
    loop = loop ? -1 : 0;
    if (EAS_SetRepeat(mEasData, mEasHandle, loop) != EAS_SUCCESS) {
        return ERROR_EAS_FAILURE;
    }
    return NO_ERROR;
}

status_t MidiFile::createOutputTrack() {
    if (mAudioSink->open(pLibConfig->sampleRate, pLibConfig->numChannels, AUDIO_FORMAT_PCM_16_BIT, 2) != NO_ERROR) {
        ALOGE("mAudioSink open failed");
        return ERROR_OPEN_FAILED;
    }
    return NO_ERROR;
}

int MidiFile::render() {
    EAS_RESULT result = EAS_FAILURE;
    EAS_I32 count;
    int temp;
    bool audioStarted = false;

    ALOGV("MidiFile::render");

    // allocate render buffer
    mAudioBuffer = new EAS_PCM[pLibConfig->mixBufferSize * pLibConfig->numChannels * NUM_BUFFERS];
    if (!mAudioBuffer) {
        ALOGE("mAudioBuffer allocate failed");
        goto threadExit;
    }

    // signal main thread that we started
    {
        Mutex::Autolock l(mMutex);
        mTid = gettid();
        ALOGV("render thread(%d) signal", mTid);
        mCondition.signal();
    }

    while (1) {
        mMutex.lock();

        // nothing to render, wait for client thread to wake us up
        while (!mRender && !mExit)
        {
            ALOGV("MidiFile::render - signal wait");
            mCondition.wait(mMutex);
            ALOGV("MidiFile::render - signal rx'd");
        }
        if (mExit) {
            mMutex.unlock();
            break;
        }

        // render midi data into the input buffer
        //ALOGV("MidiFile::render - rendering audio");
        int num_output = 0;
        EAS_PCM* p = mAudioBuffer;
        for (int i = 0; i < NUM_BUFFERS; i++) {
            result = EAS_Render(mEasData, p, pLibConfig->mixBufferSize, &count);
            if (result != EAS_SUCCESS) {
                ALOGE("EAS_Render returned %ld", result);
            }
            p += count * pLibConfig->numChannels;
            num_output += count * pLibConfig->numChannels * sizeof(EAS_PCM);
        }

        // update playback state and position
        // ALOGV("MidiFile::render - updating state");
        EAS_GetLocation(mEasData, mEasHandle, &mPlayTime);
        EAS_State(mEasData, mEasHandle, &mState);
        mMutex.unlock();

        // create audio output track if necessary
        if (!mAudioSink->ready()) {
            ALOGV("MidiFile::render - create output track");
            if (createOutputTrack() != NO_ERROR)
                goto threadExit;
        }

        // Write data to the audio hardware
        // ALOGV("MidiFile::render - writing to audio output");
        if ((temp = mAudioSink->write(mAudioBuffer, num_output)) < 0) {
            ALOGE("Error in writing:%d",temp);
            return temp;
        }

        // start audio output if necessary
        if (!audioStarted) {
            //ALOGV("MidiFile::render - starting audio");
            mAudioSink->start();
            audioStarted = true;
        }

        // still playing?
        if ((mState == EAS_STATE_STOPPED) || (mState == EAS_STATE_ERROR) ||
                (mState == EAS_STATE_PAUSED))
        {
            switch(mState) {
            case EAS_STATE_STOPPED:
            {
                ALOGV("MidiFile::render - stopped");
                sendEvent(MEDIA_PLAYBACK_COMPLETE);
                break;
            }
            case EAS_STATE_ERROR:
            {
                ALOGE("MidiFile::render - error");
                sendEvent(MEDIA_ERROR, MEDIA_ERROR_UNKNOWN);
                break;
            }
            case EAS_STATE_PAUSED:
                ALOGV("MidiFile::render - paused");
                break;
            default:
                break;
            }
            mAudioSink->stop();
            audioStarted = false;
            mRender = false;
        }
    }

threadExit:
    mAudioSink.clear();
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

} // end namespace android
