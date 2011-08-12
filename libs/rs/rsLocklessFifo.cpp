/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include "rsLocklessFifo.h"
#include "utils/Timers.h"
#include "utils/StopWatch.h"

using namespace android;
using namespace android::renderscript;

LocklessCommandFifo::LocklessCommandFifo() : mBuffer(0) {
}

LocklessCommandFifo::~LocklessCommandFifo() {
    if (!mInShutdown) {
        shutdown();
    }
    if (mBuffer) {
        free(mBuffer);
    }
}

void LocklessCommandFifo::shutdown() {
    mInShutdown = true;
    mSignalToWorker.set();
}

bool LocklessCommandFifo::init(uint32_t sizeInBytes) {
    // Add room for a buffer reset command
    mBuffer = static_cast<uint8_t *>(malloc(sizeInBytes + 4));
    if (!mBuffer) {
        LOGE("LocklessFifo allocation failure");
        return false;
    }

    if (!mSignalToControl.init() || !mSignalToWorker.init()) {
        LOGE("Signal setup failed");
        free(mBuffer);
        return false;
    }

    mInShutdown = false;
    mSize = sizeInBytes;
    mPut = mBuffer;
    mGet = mBuffer;
    mEnd = mBuffer + (sizeInBytes) - 1;
    //dumpState("init");
    return true;
}

uint32_t LocklessCommandFifo::getFreeSpace() const {
    int32_t freeSpace = 0;
    //dumpState("getFreeSpace");

    if (mPut >= mGet) {
        freeSpace = mEnd - mPut;
    } else {
        freeSpace = mGet - mPut;
    }

    if (freeSpace < 0) {
        freeSpace = 0;
    }
    return freeSpace;
}

bool LocklessCommandFifo::isEmpty() const {
    uint32_t p = android_atomic_acquire_load((int32_t *)&mPut);
    return ((uint8_t *)p) == mGet;
}


void * LocklessCommandFifo::reserve(uint32_t sizeInBytes) {
    // Add space for command header and loop token;
    sizeInBytes += 8;

    //dumpState("reserve");
    if (getFreeSpace() < sizeInBytes) {
        makeSpace(sizeInBytes);
    }

    return mPut + 4;
}

void LocklessCommandFifo::commit(uint32_t command, uint32_t sizeInBytes) {
    if (mInShutdown) {
        return;
    }
    //dumpState("commit 1");
    reinterpret_cast<uint16_t *>(mPut)[0] = command;
    reinterpret_cast<uint16_t *>(mPut)[1] = sizeInBytes;

    int32_t s = ((sizeInBytes + 3) & ~3) + 4;
    android_atomic_add(s, (int32_t *)&mPut);
    //dumpState("commit 2");
    mSignalToWorker.set();
}

void LocklessCommandFifo::commitSync(uint32_t command, uint32_t sizeInBytes) {
    if (mInShutdown) {
        return;
    }

    //char buf[1024];
    //sprintf(buf, "RenderScript LocklessCommandFifo::commitSync  %p %i  %i", this, command, sizeInBytes);
    //StopWatch compileTimer(buf);
    commit(command, sizeInBytes);
    flush();
}

void LocklessCommandFifo::flush() {
    //dumpState("flush 1");
    while (mPut != mGet) {
        mSignalToControl.wait();
    }
    //dumpState("flush 2");
}

bool LocklessCommandFifo::wait(uint64_t timeout) {
    while (isEmpty() && !mInShutdown) {
        mSignalToControl.set();
        return mSignalToWorker.wait(timeout);
    }
    return true;
}

const void * LocklessCommandFifo::get(uint32_t *command, uint32_t *bytesData, uint64_t timeout) {
    while (1) {
        //dumpState("get");
        wait(timeout);

        if (isEmpty() || mInShutdown) {
            *command = 0;
            *bytesData = 0;
            return NULL;
        }

        *command = reinterpret_cast<const uint16_t *>(mGet)[0];
        *bytesData = reinterpret_cast<const uint16_t *>(mGet)[1];
        if (*command) {
            // non-zero command is valid
            return mGet+4;
        }

        // zero command means reset to beginning.
        mGet = mBuffer;
    }
}

void LocklessCommandFifo::next() {
    uint32_t bytes = reinterpret_cast<const uint16_t *>(mGet)[1];

    android_atomic_add(((bytes + 3) & ~3) + 4, (int32_t *)&mGet);
    //mGet += ((bytes + 3) & ~3) + 4;
    if (isEmpty()) {
        mSignalToControl.set();
    }
    //dumpState("next");
}

bool LocklessCommandFifo::makeSpaceNonBlocking(uint32_t bytes) {
    //dumpState("make space non-blocking");
    if ((mPut+bytes) > mEnd) {
        // Need to loop regardless of where get is.
        if ((mGet > mPut) || (mBuffer+4 >= mGet)) {
            return false;
        }

        // Toss in a reset then the normal wait for space will do the rest.
        reinterpret_cast<uint16_t *>(mPut)[0] = 0;
        reinterpret_cast<uint16_t *>(mPut)[1] = 0;
        mPut = mBuffer;
        mSignalToWorker.set();
    }

    // it will fit here so we just need to wait for space.
    if (getFreeSpace() < bytes) {
        return false;
    }

    return true;
}

void LocklessCommandFifo::makeSpace(uint32_t bytes) {
    //dumpState("make space");
    if ((mPut+bytes) > mEnd) {
        // Need to loop regardless of where get is.
        while ((mGet > mPut) || (mBuffer+4 >= mGet)) {
            usleep(100);
        }

        // Toss in a reset then the normal wait for space will do the rest.
        reinterpret_cast<uint16_t *>(mPut)[0] = 0;
        reinterpret_cast<uint16_t *>(mPut)[1] = 0;
        mPut = mBuffer;
        mSignalToWorker.set();
    }

    // it will fit here so we just need to wait for space.
    while (getFreeSpace() < bytes) {
        usleep(100);
    }

}

void LocklessCommandFifo::dumpState(const char *s) const {
    LOGV("%s %p  put %p, get %p,  buf %p,  end %p", s, this, mPut, mGet, mBuffer, mEnd);
}

void LocklessCommandFifo::printDebugData() const {
    dumpState("printing fifo debug");
    const uint32_t *pptr = (const uint32_t *)mGet;
    pptr -= 8 * 4;
    if (mGet < mBuffer) {
        pptr = (const uint32_t *)mBuffer;
    }


    for (int ct=0; ct < 16; ct++) {
        LOGV("fifo %p = 0x%08x  0x%08x  0x%08x  0x%08x", pptr, pptr[0], pptr[1], pptr[2], pptr[3]);
        pptr += 4;
    }

}
