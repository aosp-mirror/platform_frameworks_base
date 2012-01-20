/*
 * Copyright (C) 2007 The Android Open Source Project
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
#define LOG_TAG "SoundPoolThread"
#include "utils/Log.h"

#include "SoundPoolThread.h"

namespace android {

void SoundPoolThread::write(SoundPoolMsg msg) {
    Mutex::Autolock lock(&mLock);
    while (mMsgQueue.size() >= maxMessages) {
        mCondition.wait(mLock);
    }

    // if thread is quitting, don't add to queue
    if (mRunning) {
        mMsgQueue.push(msg);
        mCondition.signal();
    }
}

const SoundPoolMsg SoundPoolThread::read() {
    Mutex::Autolock lock(&mLock);
    while (mMsgQueue.size() == 0) {
        mCondition.wait(mLock);
    }
    SoundPoolMsg msg = mMsgQueue[0];
    mMsgQueue.removeAt(0);
    mCondition.signal();
    return msg;
}

void SoundPoolThread::quit() {
    Mutex::Autolock lock(&mLock);
    if (mRunning) {
        mRunning = false;
        mMsgQueue.clear();
        mMsgQueue.push(SoundPoolMsg(SoundPoolMsg::KILL, 0));
        mCondition.signal();
        mCondition.wait(mLock);
    }
    ALOGV("return from quit");
}

SoundPoolThread::SoundPoolThread(SoundPool* soundPool) :
    mSoundPool(soundPool)
{
    mMsgQueue.setCapacity(maxMessages);
    if (createThreadEtc(beginThread, this, "SoundPoolThread")) {
        mRunning = true;
    }
}

SoundPoolThread::~SoundPoolThread()
{
    quit();
}

int SoundPoolThread::beginThread(void* arg) {
    ALOGV("beginThread");
    SoundPoolThread* soundPoolThread = (SoundPoolThread*)arg;
    return soundPoolThread->run();
}

int SoundPoolThread::run() {
    ALOGV("run");
    for (;;) {
        SoundPoolMsg msg = read();
        ALOGV("Got message m=%d, mData=%d", msg.mMessageType, msg.mData);
        switch (msg.mMessageType) {
        case SoundPoolMsg::KILL:
            ALOGV("goodbye");
            return NO_ERROR;
        case SoundPoolMsg::LOAD_SAMPLE:
            doLoadSample(msg.mData);
            break;
        default:
            LOGW("run: Unrecognized message %d\n",
                    msg.mMessageType);
            break;
        }
    }
}

void SoundPoolThread::loadSample(int sampleID) {
    write(SoundPoolMsg(SoundPoolMsg::LOAD_SAMPLE, sampleID));
}

void SoundPoolThread::doLoadSample(int sampleID) {
    sp <Sample> sample = mSoundPool->findSample(sampleID);
    status_t status = -1;
    if (sample != 0) {
        status = sample->doLoad();
    }
    mSoundPool->notify(SoundPoolEvent(SoundPoolEvent::SAMPLE_LOADED, sampleID, status));
}

} // end namespace android
