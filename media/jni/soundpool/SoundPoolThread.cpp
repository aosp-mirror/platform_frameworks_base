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

void SoundPoolThread::MessageQueue::write(SoundPoolMsg msg) {
    LOGV("MessageQueue::write - acquiring lock\n");
    Mutex::Autolock lock(&mLock);
    while (mQueue.size() >= maxMessages) {
        LOGV("MessageQueue::write - wait\n");
        mCondition.wait(mLock);
    }
    LOGV("MessageQueue::write - push message\n");
    mQueue.push(msg);
    mCondition.signal();
}

const SoundPoolMsg SoundPoolThread::MessageQueue::read() {
    LOGV("MessageQueue::read - acquiring lock\n");
    Mutex::Autolock lock(&mLock);
    while (mQueue.size() == 0) {
        LOGV("MessageQueue::read - wait\n");
        mCondition.wait(mLock);
    }
    SoundPoolMsg msg = mQueue[0];
    LOGV("MessageQueue::read - retrieve message\n");
    mQueue.removeAt(0);
    mCondition.signal();
    return msg;
}

void SoundPoolThread::MessageQueue::quit() {
    Mutex::Autolock lock(&mLock);
    mQueue.clear();
    mQueue.push(SoundPoolMsg(SoundPoolMsg::KILL, 0));
    mCondition.signal();
    mCondition.wait(mLock);
    LOGV("return from quit");
}

SoundPoolThread::SoundPoolThread(SoundPool* soundPool) :
    mSoundPool(soundPool)
{
    mMessages.setCapacity(maxMessages);
    createThread(beginThread, this);
}

SoundPoolThread::~SoundPoolThread()
{
}

int SoundPoolThread::beginThread(void* arg) {
    LOGV("beginThread");
    SoundPoolThread* soundPoolThread = (SoundPoolThread*)arg;
    return soundPoolThread->run();
}

int SoundPoolThread::run() {
    LOGV("run");
    for (;;) {
        SoundPoolMsg msg = mMessages.read();
        LOGV("Got message m=%d, mData=%d", msg.mMessageType, msg.mData);
        switch (msg.mMessageType) {
        case SoundPoolMsg::KILL:
            LOGV("goodbye");
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
    mMessages.write(SoundPoolMsg(SoundPoolMsg::LOAD_SAMPLE, sampleID));
}

void SoundPoolThread::doLoadSample(int sampleID) {
    sp <Sample> sample = mSoundPool->findSample(sampleID);
    if (sample != 0) {
        sample->doLoad();
    }
}

} // end namespace android
