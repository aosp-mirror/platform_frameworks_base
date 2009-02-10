/*
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
#define LOG_TAG "VorbisPlayer"
#include "utils/Log.h"

#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <sched.h>
#include <sys/types.h>
#include <sys/stat.h>


#include "VorbisPlayer.h"

#ifdef HAVE_GETTID
static pid_t myTid() { return gettid(); }
#else
static pid_t myTid() { return getpid(); }
#endif

// ----------------------------------------------------------------------------

namespace android {

// ----------------------------------------------------------------------------

// TODO: Determine appropriate return codes
static status_t ERROR_NOT_OPEN = -1;
static status_t ERROR_OPEN_FAILED = -2;
static status_t ERROR_ALLOCATE_FAILED = -4;
static status_t ERROR_NOT_SUPPORTED = -8;
static status_t ERROR_NOT_READY = -16;
static status_t STATE_INIT = 0;
static status_t STATE_ERROR = 1;
static status_t STATE_OPEN = 2;


VorbisPlayer::VorbisPlayer() :
    mAudioBuffer(NULL), mPlayTime(-1), mDuration(-1), mState(STATE_ERROR),
    mStreamType(AudioTrack::MUSIC), mLoop(false), mAndroidLoop(false),
    mExit(false), mPaused(false), mRender(false), mRenderTid(-1)
{
    LOGV("constructor\n");
    memset(&mVorbisFile, 0, sizeof mVorbisFile);
}

void VorbisPlayer::onFirstRef()
{
    LOGV("onFirstRef");
    // create playback thread
    Mutex::Autolock l(mMutex);
    createThreadEtc(renderThread, this, "vorbis decoder");
    mCondition.wait(mMutex);
    if (mRenderTid > 0) {
        LOGV("render thread(%d) started", mRenderTid);
        mState = STATE_INIT;
    }
}

status_t VorbisPlayer::initCheck()
{
    if (mState != STATE_ERROR) return NO_ERROR;
    return ERROR_NOT_READY;
}

VorbisPlayer::~VorbisPlayer() {
    LOGV("VorbisPlayer destructor\n");
    release();
}

status_t VorbisPlayer::setDataSource(const char* path)
{
    return setdatasource(path, -1, 0, 0x7ffffffffffffffLL); // intentionally less than LONG_MAX
}

status_t VorbisPlayer::setDataSource(int fd, int64_t offset, int64_t length)
{
    return setdatasource(NULL, fd, offset, length);
}

size_t VorbisPlayer::vp_fread(void *buf, size_t size, size_t nmemb, void *me) {
    VorbisPlayer *self = (VorbisPlayer*) me;

    long curpos = vp_ftell(me);
    while (nmemb != 0 && (curpos + size * nmemb) > self->mLength) {
        nmemb--;
    }
    return fread(buf, size, nmemb, self->mFile);
}

int VorbisPlayer::vp_fseek(void *me, ogg_int64_t off, int whence) {
    VorbisPlayer *self = (VorbisPlayer*) me;
    if (whence == SEEK_SET)
        return fseek(self->mFile, off + self->mOffset, whence);
    else if (whence == SEEK_CUR)
        return fseek(self->mFile, off, whence);
    else if (whence == SEEK_END)
        return fseek(self->mFile, self->mOffset + self->mLength + off, SEEK_SET);
    return -1;
}

int VorbisPlayer::vp_fclose(void *me) {
    LOGV("vp_fclose");
    VorbisPlayer *self = (VorbisPlayer*) me;
    int ret = fclose (self->mFile);
    self->mFile = NULL;
    return ret;
}

long VorbisPlayer::vp_ftell(void *me) {
    VorbisPlayer *self = (VorbisPlayer*) me;
    return ftell(self->mFile) - self->mOffset;
}

status_t VorbisPlayer::setdatasource(const char *path, int fd, int64_t offset, int64_t length)
{
    LOGV("setDataSource url=%s, fd=%d\n", path, fd);

    // file still open?
    Mutex::Autolock l(mMutex);
    if (mState == STATE_OPEN) {
        reset_nosync();
    }

    // open file and set paused state
    if (path) {
        mFile = fopen(path, "r");
    } else {
        mFile = fdopen(dup(fd), "r");
    }
    if (mFile == NULL) {
        return ERROR_OPEN_FAILED;
    }

    struct stat sb;
    int ret;
    if (path) {
        ret = stat(path, &sb);
    } else {
        ret = fstat(fd, &sb);
    }
    if (ret != 0) {
        mState = STATE_ERROR;
        fclose(mFile);
        return ERROR_OPEN_FAILED;
    }
    if (sb.st_size > (length + offset)) {
        mLength = length;
    } else {
        mLength = sb.st_size - offset;
    }

    ov_callbacks callbacks = {
        (size_t (*)(void *, size_t, size_t, void *))  vp_fread,
        (int (*)(void *, ogg_int64_t, int))           vp_fseek,
        (int (*)(void *))                             vp_fclose,
        (long (*)(void *))                            vp_ftell
    };

    mOffset = offset;
    fseek(mFile, offset, SEEK_SET);

    int result = ov_open_callbacks(this, &mVorbisFile, NULL, 0, callbacks);
    if (result < 0) {
        LOGE("ov_open() failed: [%d]\n", (int)result);
        mState = STATE_ERROR;
        fclose(mFile);
        return ERROR_OPEN_FAILED;
    }

    // look for the android loop tag  (for ringtones)
    char **ptr = ov_comment(&mVorbisFile,-1)->user_comments;
    while(*ptr) {
        // does the comment start with ANDROID_LOOP_TAG
        if(strncmp(*ptr, ANDROID_LOOP_TAG, strlen(ANDROID_LOOP_TAG)) == 0) {
            // read the value of the tag
            char *val = *ptr + strlen(ANDROID_LOOP_TAG) + 1;
            mAndroidLoop = (strncmp(val, "true", 4) == 0);
        }
        // we keep parsing even after finding one occurence of ANDROID_LOOP_TAG,
        // as we could find another one  (the tag might have been appended more than once).
        ++ptr;
    }
    LOGV_IF(mAndroidLoop, "looped sound");

    mState = STATE_OPEN;
    return NO_ERROR;
}

status_t VorbisPlayer::prepare()
{
    LOGV("prepare\n");
    if (mState != STATE_OPEN ) {
        return ERROR_NOT_OPEN;
    }
    return NO_ERROR;
}

status_t VorbisPlayer::prepareAsync() {
    LOGV("prepareAsync\n");
    // can't hold the lock here because of the callback
    // it's safe because we don't change state
    if (mState != STATE_OPEN ) {
        sendEvent(MEDIA_ERROR);
        return NO_ERROR;
    }
    sendEvent(MEDIA_PREPARED);
    return NO_ERROR;
}

status_t VorbisPlayer::start()
{
    LOGV("start\n");
    Mutex::Autolock l(mMutex);
    if (mState != STATE_OPEN) {
        return ERROR_NOT_OPEN;
    }

    mPaused = false;
    mRender = true;

    // wake up render thread
    LOGV("  wakeup render thread\n");
    mCondition.signal();
    return NO_ERROR;
}

status_t VorbisPlayer::stop()
{
    LOGV("stop\n");
    Mutex::Autolock l(mMutex);
    if (mState != STATE_OPEN) {
        return ERROR_NOT_OPEN;
    }
    mPaused = true;
    mRender = false;
    return NO_ERROR;
}

status_t VorbisPlayer::seekTo(int position)
{
    LOGV("seekTo %d\n", position);
    Mutex::Autolock l(mMutex);
    if (mState != STATE_OPEN) {
        return ERROR_NOT_OPEN;
    }

    int result = ov_time_seek(&mVorbisFile, position);
    if (result != 0) {
        LOGE("ov_time_seek() returned %d\n", result);
        return result;
    }
    sendEvent(MEDIA_SEEK_COMPLETE);
    return NO_ERROR;
}

status_t VorbisPlayer::pause()
{
    LOGV("pause\n");
    Mutex::Autolock l(mMutex);
    if (mState != STATE_OPEN) {
        return ERROR_NOT_OPEN;
    }
    mPaused = true;
    return NO_ERROR;
}

bool VorbisPlayer::isPlaying()
{
    LOGV("isPlaying\n");
    if (mState == STATE_OPEN) {
        return mRender;
    }
    return false;
}

status_t VorbisPlayer::getCurrentPosition(int* position)
{
    LOGV("getCurrentPosition\n");
    Mutex::Autolock l(mMutex);
    if (mState != STATE_OPEN) {
        LOGE("getCurrentPosition(): file not open");
        return ERROR_NOT_OPEN;
    }
    *position = ov_time_tell(&mVorbisFile);
    if (*position < 0) {
        LOGE("getCurrentPosition(): ov_time_tell returned %d", *position);
        return *position;
    }
    return NO_ERROR;
}

status_t VorbisPlayer::getDuration(int* duration)
{
    LOGV("getDuration\n");
    Mutex::Autolock l(mMutex);
    if (mState != STATE_OPEN) {
        return ERROR_NOT_OPEN;
    }

    int ret = ov_time_total(&mVorbisFile, -1);
    if (ret == OV_EINVAL) {
        return -1;
    }

    *duration = ret;
    return NO_ERROR;
}

status_t VorbisPlayer::release()
{
    LOGV("release\n");
    Mutex::Autolock l(mMutex);
    reset_nosync();

    // TODO: timeout when thread won't exit
    // wait for render thread to exit
    if (mRenderTid > 0) {
        mExit = true;
        mCondition.signal();
        mCondition.wait(mMutex);
    }
    return NO_ERROR;
}

status_t VorbisPlayer::reset()
{
    LOGV("reset\n");
    Mutex::Autolock l(mMutex);
    if (mState != STATE_OPEN) {
        return NO_ERROR;
    }
    return reset_nosync();
}

// always call with lock held
status_t VorbisPlayer::reset_nosync()
{
    // close file
    ov_clear(&mVorbisFile); // this also closes the FILE
    if (mFile != NULL) {
        LOGV("OOPS! Vorbis didn't close the file");
        fclose(mFile);
    }
    mState = STATE_ERROR;

    mPlayTime = -1;
    mDuration = -1;
    mLoop = false;
    mAndroidLoop = false;
    mPaused = false;
    mRender = false;
    return NO_ERROR;
}

status_t VorbisPlayer::setLooping(int loop)
{
    LOGV("setLooping\n");
    Mutex::Autolock l(mMutex);
    mLoop = (loop != 0);
    return NO_ERROR;
}

status_t VorbisPlayer::createOutputTrack() {
    // open audio track
    vorbis_info *vi = ov_info(&mVorbisFile, -1);

    LOGV("Create AudioTrack object: rate=%ld, channels=%d\n",
            vi->rate, vi->channels);
    if (mAudioSink->open(vi->rate, vi->channels, AudioSystem::PCM_16_BIT, DEFAULT_AUDIOSINK_BUFFERCOUNT) != NO_ERROR) {
        LOGE("mAudioSink open failed");
        return ERROR_OPEN_FAILED;
    }
    return NO_ERROR;
}

int VorbisPlayer::renderThread(void* p) {
    return ((VorbisPlayer*)p)->render();
}

#define AUDIOBUFFER_SIZE 4096

int VorbisPlayer::render() {
    int result = -1;
    int temp;
    int current_section = 0;
    bool audioStarted = false;

    LOGV("render\n");

    // allocate render buffer
    mAudioBuffer = new char[AUDIOBUFFER_SIZE];
    if (!mAudioBuffer) {
        LOGE("mAudioBuffer allocate failed\n");
        goto threadExit;
    }

    // let main thread know we're ready
    {
        Mutex::Autolock l(mMutex);
        mRenderTid = myTid();
        mCondition.signal();
    }

    while (1) {
        long numread = 0;
        {
            Mutex::Autolock l(mMutex);

            // pausing?
            if (mPaused) {
                if (mAudioSink->ready()) mAudioSink->pause();
                mRender = false;
                audioStarted = false;
            }

            // nothing to render, wait for client thread to wake us up
            if (!mExit && !mRender) {
                LOGV("render - signal wait\n");
                mCondition.wait(mMutex);
                LOGV("render - signal rx'd\n");
            }
            if (mExit) break;

            // We could end up here if start() is called, and before we get a
            // chance to run, the app calls stop() or reset(). Re-check render
            // flag so we don't try to render in stop or reset state.
            if (!mRender) continue;

            // render vorbis data into the input buffer
            numread = ov_read(&mVorbisFile, mAudioBuffer, AUDIOBUFFER_SIZE, &current_section);
            if (numread == 0) {
                // end of file, do we need to loop?
                // ...
                if (mLoop || mAndroidLoop) {
                    ov_time_seek(&mVorbisFile, 0);
                    current_section = 0;
                    numread = ov_read(&mVorbisFile, mAudioBuffer, AUDIOBUFFER_SIZE, &current_section);
                } else {
                    mAudioSink->stop();
                    audioStarted = false;
                    mRender = false;
                    mPaused = true;
                    int endpos = ov_time_tell(&mVorbisFile);

                    LOGV("send MEDIA_PLAYBACK_COMPLETE");
                    sendEvent(MEDIA_PLAYBACK_COMPLETE);

                    // wait until we're started again
                    LOGV("playback complete - wait for signal");
                    mCondition.wait(mMutex);
                    LOGV("playback complete - signal rx'd");
                    if (mExit) break;

                    // if we're still at the end, restart from the beginning
                    if (mState == STATE_OPEN) {
                        int curpos = ov_time_tell(&mVorbisFile);
                        if (curpos == endpos) {
                            ov_time_seek(&mVorbisFile, 0);
                        }
                        current_section = 0;
                        numread = ov_read(&mVorbisFile, mAudioBuffer, AUDIOBUFFER_SIZE, &current_section);
                    }
                }
            }
        }

        // codec returns negative number on error
        if (numread < 0) {
            LOGE("Error in Vorbis decoder");
            sendEvent(MEDIA_ERROR);
            break;
        }

        // create audio output track if necessary
        if (!mAudioSink->ready()) {
            LOGV("render - create output track\n");
            if (createOutputTrack() != NO_ERROR)
                break;
        }

        // Write data to the audio hardware
        if ((temp = mAudioSink->write(mAudioBuffer, numread)) < 0) {
            LOGE("Error in writing:%d",temp);
            result = temp;
            break;
        }

        // start audio output if necessary
        if (!audioStarted && !mPaused && !mExit) {
            LOGV("render - starting audio\n");
            mAudioSink->start();
            audioStarted = true;
        }
    }

threadExit:
    mAudioSink.clear();
    if (mAudioBuffer) {
        delete [] mAudioBuffer;
        mAudioBuffer = NULL;
    }

    // tell main thread goodbye
    Mutex::Autolock l(mMutex);
    mRenderTid = -1;
    mCondition.signal();
    return result;
}

} // end namespace android
