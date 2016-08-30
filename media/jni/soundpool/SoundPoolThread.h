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

#ifndef SOUNDPOOLTHREAD_H_
#define SOUNDPOOLTHREAD_H_

#include <utils/threads.h>
#include <utils/Vector.h>
#include <media/AudioTrack.h>

#include "SoundPool.h"

namespace android {

class SoundPoolMsg {
public:
    enum MessageType { INVALID, KILL, LOAD_SAMPLE };
    SoundPoolMsg() : mMessageType(INVALID), mData(0) {}
    SoundPoolMsg(MessageType MessageType, int data) :
        mMessageType(MessageType), mData(data) {}
    uint16_t         mMessageType;
    uint16_t         mData;
};

/*
 * This class handles background requests from the SoundPool
 */
class SoundPoolThread {
public:
    explicit SoundPoolThread(SoundPool* SoundPool);
    ~SoundPoolThread();
    void loadSample(int sampleID);
    void quit();
    void write(SoundPoolMsg msg);

private:
    static const size_t maxMessages = 128;

    static int beginThread(void* arg);
    int run();
    void doLoadSample(int sampleID);
    const SoundPoolMsg read();

    Mutex                   mLock;
    Condition               mCondition;
    Vector<SoundPoolMsg>    mMsgQueue;
    SoundPool*              mSoundPool;
    bool                    mRunning;
};

} // end namespace android

#endif /*SOUNDPOOLTHREAD_H_*/
