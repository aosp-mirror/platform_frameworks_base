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

#include "rsContext.h"

#include "rsThreadIO.h"

using namespace android;
using namespace android::renderscript;

ThreadIO::ThreadIO() : mUsingSocket(false) {
}

ThreadIO::~ThreadIO() {
}

void ThreadIO::init(bool useSocket) {
    mUsingSocket = useSocket;
    mToCore.init(16 * 1024);

    if (mUsingSocket) {
        mToClientSocket.init();
        mToCoreSocket.init();
    } else {
        mToClient.init(1024);
    }
}

void ThreadIO::shutdown() {
    //LOGE("shutdown 1");
    mToCore.shutdown();
    //LOGE("shutdown 2");
}

void ThreadIO::coreFlush() {
    //LOGE("coreFlush 1");
    if (mUsingSocket) {
    } else {
        mToCore.flush();
    }
    //LOGE("coreFlush 2");
}

void * ThreadIO::coreHeader(uint32_t cmdID, size_t dataLen) {
    //LOGE("coreHeader %i %i", cmdID, dataLen);
    if (mUsingSocket) {
        CoreCmdHeader hdr;
        hdr.bytes = dataLen;
        hdr.cmdID = cmdID;
        mToCoreSocket.writeAsync(&hdr, sizeof(hdr));
    } else {
        mCoreCommandSize = dataLen;
        mCoreCommandID = cmdID;
        mCoreDataPtr = (uint8_t *)mToCore.reserve(dataLen);
        mCoreDataBasePtr = mCoreDataPtr;
    }
    //LOGE("coreHeader ret %p", mCoreDataPtr);
    return mCoreDataPtr;
}

void ThreadIO::coreData(const void *data, size_t dataLen) {
    //LOGE("coreData %p %i", data, dataLen);
    mToCoreSocket.writeAsync(data, dataLen);
    //LOGE("coreData ret %p", mCoreDataPtr);
}

void ThreadIO::coreCommit() {
    //LOGE("coreCommit %p %p %i", mCoreDataPtr, mCoreDataBasePtr, mCoreCommandSize);
    if (mUsingSocket) {
    } else {
        rsAssert((size_t)(mCoreDataPtr - mCoreDataBasePtr) <= mCoreCommandSize);
        mToCore.commit(mCoreCommandID, mCoreCommandSize);
    }
    //LOGE("coreCommit ret");
}

void ThreadIO::coreCommitSync() {
    //LOGE("coreCommitSync %p %p %i", mCoreDataPtr, mCoreDataBasePtr, mCoreCommandSize);
    if (mUsingSocket) {
    } else {
        rsAssert((size_t)(mCoreDataPtr - mCoreDataBasePtr) <= mCoreCommandSize);
        mToCore.commitSync(mCoreCommandID, mCoreCommandSize);
    }
    //LOGE("coreCommitSync ret");
}

void ThreadIO::clientShutdown() {
    //LOGE("coreShutdown 1");
    mToClient.shutdown();
    //LOGE("coreShutdown 2");
}

void ThreadIO::coreSetReturn(const void *data, size_t dataLen) {
    rsAssert(dataLen <= sizeof(mToCoreRet));
    memcpy(&mToCoreRet, data, dataLen);
}

void ThreadIO::coreGetReturn(void *data, size_t dataLen) {
    memcpy(data, &mToCoreRet, dataLen);
}

void ThreadIO::setTimoutCallback(void (*cb)(void *), void *dat, uint64_t timeout) {
    mToCore.setTimoutCallback(cb, dat, timeout);
}


bool ThreadIO::playCoreCommands(Context *con, bool waitForCommand, uint64_t timeToWait) {
    bool ret = false;
    uint64_t startTime = con->getTime();

    while (!mToCore.isEmpty() || waitForCommand) {
        uint32_t cmdID = 0;
        uint32_t cmdSize = 0;
        ret = true;
        if (con->props.mLogTimes) {
            con->timerSet(Context::RS_TIMER_IDLE);
        }

        uint64_t delay = 0;
        if (waitForCommand) {
            delay = timeToWait - (con->getTime() - startTime);
            if (delay > timeToWait) {
                delay = 0;
            }
        }
        const void * data = mToCore.get(&cmdID, &cmdSize, delay);
        if (!cmdSize) {
            // exception or timeout occurred.
            return false;
        }
        if (con->props.mLogTimes) {
            con->timerSet(Context::RS_TIMER_INTERNAL);
        }
        waitForCommand = false;
        //ALOGV("playCoreCommands 3 %i %i", cmdID, cmdSize);

        if (cmdID >= (sizeof(gPlaybackFuncs) / sizeof(void *))) {
            rsAssert(cmdID < (sizeof(gPlaybackFuncs) / sizeof(void *)));
            LOGE("playCoreCommands error con %p, cmd %i", con, cmdID);
            mToCore.printDebugData();
        }
        gPlaybackFuncs[cmdID](con, data, cmdSize << 2);
        mToCore.next();
    }
    return ret;
}

RsMessageToClientType ThreadIO::getClientHeader(size_t *receiveLen, uint32_t *usrID) {
    if (mUsingSocket) {
        mToClientSocket.read(&mLastClientHeader, sizeof(mLastClientHeader));
    } else {
        size_t bytesData = 0;
        const uint32_t *d = (const uint32_t *)mToClient.get(&mLastClientHeader.cmdID, (uint32_t*)&bytesData);
        if (bytesData >= sizeof(uint32_t)) {
            mLastClientHeader.userID = d[0];
            mLastClientHeader.bytes = bytesData - sizeof(uint32_t);
        } else {
            mLastClientHeader.userID = 0;
            mLastClientHeader.bytes = 0;
        }
    }
    receiveLen[0] = mLastClientHeader.bytes;
    usrID[0] = mLastClientHeader.userID;
    return (RsMessageToClientType)mLastClientHeader.cmdID;
}

RsMessageToClientType ThreadIO::getClientPayload(void *data, size_t *receiveLen,
                                uint32_t *usrID, size_t bufferLen) {
    receiveLen[0] = mLastClientHeader.bytes;
    usrID[0] = mLastClientHeader.userID;
    if (bufferLen < mLastClientHeader.bytes) {
        return RS_MESSAGE_TO_CLIENT_RESIZE;
    }
    if (mUsingSocket) {
        if (receiveLen[0]) {
            mToClientSocket.read(data, receiveLen[0]);
        }
        return (RsMessageToClientType)mLastClientHeader.cmdID;
    } else {
        uint32_t bytesData = 0;
        uint32_t commandID = 0;
        const uint32_t *d = (const uint32_t *)mToClient.get(&commandID, &bytesData);
        //LOGE("getMessageToClient 3    %i  %i", commandID, bytesData);
        //LOGE("getMessageToClient  %i %i", commandID, *subID);
        if (bufferLen >= receiveLen[0]) {
            memcpy(data, d+1, receiveLen[0]);
            mToClient.next();
            return (RsMessageToClientType)commandID;
        }
    }
    return RS_MESSAGE_TO_CLIENT_RESIZE;
}

bool ThreadIO::sendToClient(RsMessageToClientType cmdID, uint32_t usrID, const void *data,
                            size_t dataLen, bool waitForSpace) {
    ClientCmdHeader hdr;
    hdr.bytes = dataLen;
    hdr.cmdID = cmdID;
    hdr.userID = usrID;
    if (mUsingSocket) {
        mToClientSocket.writeAsync(&hdr, sizeof(hdr));
        if (dataLen) {
            mToClientSocket.writeAsync(data, dataLen);
        }
        return true;
    } else {
        if (!waitForSpace) {
            if (!mToClient.makeSpaceNonBlocking(dataLen + sizeof(hdr))) {
                // Not enough room, and not waiting.
                return false;
            }
        }

        //LOGE("sendMessageToClient 2");
        uint32_t *p = (uint32_t *)mToClient.reserve(dataLen + sizeof(usrID));
        p[0] = usrID;
        if (dataLen > 0) {
            memcpy(p+1, data, dataLen);
        }
        mToClient.commit(cmdID, dataLen + sizeof(usrID));
        //LOGE("sendMessageToClient 3");
        return true;
    }
    return false;
}

