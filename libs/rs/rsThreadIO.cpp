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
#include "rsgApiStructs.h"

#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>

#include <fcntl.h>
#include <poll.h>


using namespace android;
using namespace android::renderscript;

ThreadIO::ThreadIO() {
    mRunning = true;
    mPureFifo = false;
    mMaxInlineSize = 1024;
}

ThreadIO::~ThreadIO() {
}

void ThreadIO::init() {
    mToClient.init();
    mToCore.init();
}

void ThreadIO::shutdown() {
    mRunning = false;
    mToCore.shutdown();
}

void * ThreadIO::coreHeader(uint32_t cmdID, size_t dataLen) {
    //ALOGE("coreHeader %i %i", cmdID, dataLen);
    CoreCmdHeader *hdr = (CoreCmdHeader *)&mSendBuffer[0];
    hdr->bytes = dataLen;
    hdr->cmdID = cmdID;
    mSendLen = dataLen + sizeof(CoreCmdHeader);
    //mToCoreSocket.writeAsync(&hdr, sizeof(hdr));
    //ALOGE("coreHeader ret ");
    return &mSendBuffer[sizeof(CoreCmdHeader)];
}

void ThreadIO::coreCommit() {
    mToCore.writeAsync(&mSendBuffer, mSendLen);
}

void ThreadIO::clientShutdown() {
    mToClient.shutdown();
}

void ThreadIO::coreWrite(const void *data, size_t len) {
    //ALOGV("core write %p %i", data, (int)len);
    mToCore.writeAsync(data, len, true);
}

void ThreadIO::coreRead(void *data, size_t len) {
    //ALOGV("core read %p %i", data, (int)len);
    mToCore.read(data, len);
}

void ThreadIO::coreSetReturn(const void *data, size_t dataLen) {
    uint32_t buf;
    if (data == NULL) {
        data = &buf;
        dataLen = sizeof(buf);
    }

    mToCore.readReturn(data, dataLen);
}

void ThreadIO::coreGetReturn(void *data, size_t dataLen) {
    uint32_t buf;
    if (data == NULL) {
        data = &buf;
        dataLen = sizeof(buf);
    }

    mToCore.writeWaitReturn(data, dataLen);
}

void ThreadIO::setTimeoutCallback(void (*cb)(void *), void *dat, uint64_t timeout) {
    //mToCore.setTimeoutCallback(cb, dat, timeout);
}

bool ThreadIO::playCoreCommands(Context *con, int waitFd) {
    bool ret = false;
    const bool isLocal = !isPureFifo();

    uint8_t buf[2 * 1024];
    const CoreCmdHeader *cmd = (const CoreCmdHeader *)&buf[0];
    const void * data = (const void *)&buf[sizeof(CoreCmdHeader)];

    struct pollfd p[2];
    p[0].fd = mToCore.getReadFd();
    p[0].events = POLLIN;
    p[0].revents = 0;
    p[1].fd = waitFd;
    p[1].events = POLLIN;
    p[1].revents = 0;
    int pollCount = 1;
    if (waitFd >= 0) {
        pollCount = 2;
    }

    if (con->props.mLogTimes) {
        con->timerSet(Context::RS_TIMER_IDLE);
    }

    int waitTime = -1;
    while (mRunning) {
        int pr = poll(p, pollCount, waitTime);
        if (pr <= 0) {
            break;
        }

        if (p[0].revents) {
            size_t r = 0;
            if (isLocal) {
                r = mToCore.read(&buf[0], sizeof(CoreCmdHeader));
                mToCore.read(&buf[sizeof(CoreCmdHeader)], cmd->bytes);
                if (r != sizeof(CoreCmdHeader)) {
                    // exception or timeout occurred.
                    break;
                }
            } else {
                r = mToCore.read((void *)&cmd->cmdID, sizeof(cmd->cmdID));
            }


            ret = true;
            if (con->props.mLogTimes) {
                con->timerSet(Context::RS_TIMER_INTERNAL);
            }
            //ALOGV("playCoreCommands 3 %i %i", cmd->cmdID, cmd->bytes);

            if (cmd->cmdID >= (sizeof(gPlaybackFuncs) / sizeof(void *))) {
                rsAssert(cmd->cmdID < (sizeof(gPlaybackFuncs) / sizeof(void *)));
                ALOGE("playCoreCommands error con %p, cmd %i", con, cmd->cmdID);
            }

            if (isLocal) {
                gPlaybackFuncs[cmd->cmdID](con, data, cmd->bytes);
            } else {
                gPlaybackRemoteFuncs[cmd->cmdID](con, this);
            }

            if (con->props.mLogTimes) {
                con->timerSet(Context::RS_TIMER_IDLE);
            }

            if (waitFd < 0) {
                // If we don't have a secondary wait object we should stop blocking now
                // that at least one command has been processed.
                waitTime = 0;
            }
        }

        if (p[1].revents && !p[0].revents) {
            // We want to finish processing fifo events before processing the vsync.
            // Otherwise we can end up falling behind and having tremendous lag.
            break;
        }
    }
    return ret;
}

RsMessageToClientType ThreadIO::getClientHeader(size_t *receiveLen, uint32_t *usrID) {
    //ALOGE("getClientHeader");
    mToClient.read(&mLastClientHeader, sizeof(mLastClientHeader));

    receiveLen[0] = mLastClientHeader.bytes;
    usrID[0] = mLastClientHeader.userID;
    //ALOGE("getClientHeader %i %i %i", mLastClientHeader.cmdID, usrID[0], receiveLen[0]);
    return (RsMessageToClientType)mLastClientHeader.cmdID;
}

RsMessageToClientType ThreadIO::getClientPayload(void *data, size_t *receiveLen,
                                uint32_t *usrID, size_t bufferLen) {
    //ALOGE("getClientPayload");
    receiveLen[0] = mLastClientHeader.bytes;
    usrID[0] = mLastClientHeader.userID;
    if (bufferLen < mLastClientHeader.bytes) {
        return RS_MESSAGE_TO_CLIENT_RESIZE;
    }
    if (receiveLen[0]) {
        mToClient.read(data, receiveLen[0]);
    }
    //ALOGE("getClientPayload x");
    return (RsMessageToClientType)mLastClientHeader.cmdID;
}

bool ThreadIO::sendToClient(RsMessageToClientType cmdID, uint32_t usrID, const void *data,
                            size_t dataLen, bool waitForSpace) {

    //ALOGE("sendToClient %i %i %i", cmdID, usrID, (int)dataLen);
    ClientCmdHeader hdr;
    hdr.bytes = dataLen;
    hdr.cmdID = cmdID;
    hdr.userID = usrID;

    mToClient.writeAsync(&hdr, sizeof(hdr));
    if (dataLen) {
        mToClient.writeAsync(data, dataLen);
    }

    //ALOGE("sendToClient x");
    return true;
}

