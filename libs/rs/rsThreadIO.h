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

#ifndef ANDROID_RS_THREAD_IO_H
#define ANDROID_RS_THREAD_IO_H

#include "rsUtils.h"
#include "rsLocklessFifo.h"
#include "rsFifoSocket.h"

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class Context;

class ThreadIO {
public:
    ThreadIO();
    ~ThreadIO();

    void init(bool useSocket = false);
    void shutdown();

    // Plays back commands from the client.
    // Returns true if any commands were processed.
    //
    // waitForCommand: true, block until a command arrives or
    // the specified time expires.
    //
    // timeToWait: Max time to block in microseconds.  A value of zero indicates
    // an indefinite wait.
    bool playCoreCommands(Context *con, bool waitForCommand, uint64_t timeToWait);

    void setTimoutCallback(void (*)(void *), void *, uint64_t timeout);
    //LocklessCommandFifo mToCore;



    void coreFlush();
    void * coreHeader(uint32_t, size_t dataLen);
    void coreData(const void *data, size_t dataLen);
    void coreCommit();
    void coreCommitSync();
    void coreSetReturn(const void *data, size_t dataLen);
    void coreGetReturn(void *data, size_t dataLen);


    RsMessageToClientType getClientHeader(size_t *receiveLen, uint32_t *usrID);
    RsMessageToClientType getClientPayload(void *data, size_t *receiveLen, uint32_t *subID, size_t bufferLen);
    bool sendToClient(RsMessageToClientType cmdID, uint32_t usrID, const void *data, size_t dataLen, bool waitForSpace);
    void clientShutdown();


protected:
    typedef struct CoreCmdHeaderRec {
        uint32_t cmdID;
        uint32_t bytes;
    } CoreCmdHeader;
    typedef struct ClientCmdHeaderRec {
        uint32_t cmdID;
        uint32_t bytes;
        uint32_t userID;
    } ClientCmdHeader;
    ClientCmdHeader mLastClientHeader;

    size_t mCoreCommandSize;
    uint32_t mCoreCommandID;
    uint8_t * mCoreDataPtr;
    uint8_t * mCoreDataBasePtr;

    bool mUsingSocket;
    LocklessCommandFifo mToClient;
    LocklessCommandFifo mToCore;

    FifoSocket mToClientSocket;
    FifoSocket mToCoreSocket;

    intptr_t mToCoreRet;

};


}
}
#endif

