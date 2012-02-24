/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

#include <utils/Log.h>
#include <malloc.h>
#include <string.h>

#include "RenderScript.h"

bool RenderScript::gInitialized = false;
pthread_mutex_t RenderScript::gInitMutex = PTHREAD_MUTEX_INITIALIZER;

RenderScript::RenderScript() {
    mDev = NULL;
    mContext = NULL;
    mErrorFunc = NULL;
    mMessageFunc = NULL;
    mMessageRun = false;

    memset(&mElements, 0, sizeof(mElements));
}

RenderScript::~RenderScript() {
    mMessageRun = false;

    rsContextDeinitToClient(mContext);

    void *res = NULL;
    int status = pthread_join(mMessageThreadId, &res);

    rsContextDestroy(mContext);
    mContext = NULL;
    rsDeviceDestroy(mDev);
    mDev = NULL;
}

bool RenderScript::init(int targetApi) {
    mDev = rsDeviceCreate();
    if (mDev == 0) {
        ALOGE("Device creation failed");
        return false;
    }

    mContext = rsContextCreate(mDev, 0, targetApi);
    if (mContext == 0) {
        ALOGE("Context creation failed");
        return false;
    }


    pid_t mNativeMessageThreadId;

    int status = pthread_create(&mMessageThreadId, NULL, threadProc, this);
    if (status) {
        ALOGE("Failed to start RenderScript message thread.");
        return false;
    }
    // Wait for the message thread to be active.
    while (!mMessageRun) {
        usleep(1000);
    }

    return true;
}

void RenderScript::throwError(const char *err) const {
    ALOGE("RS CPP error: %s", err);
    int * v = NULL;
    v[0] = 0;
}


void * RenderScript::threadProc(void *vrsc) {
    RenderScript *rs = static_cast<RenderScript *>(vrsc);
    size_t rbuf_size = 256;
    void * rbuf = malloc(rbuf_size);

    rsContextInitToClient(rs->mContext);
    rs->mMessageRun = true;

    while (rs->mMessageRun) {
        size_t receiveLen = 0;
        uint32_t usrID = 0;
        uint32_t subID = 0;
        RsMessageToClientType r = rsContextPeekMessage(rs->mContext,
                                                       &receiveLen, sizeof(receiveLen),
                                                       &usrID, sizeof(usrID));

        if (receiveLen >= rbuf_size) {
            rbuf_size = receiveLen + 32;
            rbuf = realloc(rbuf, rbuf_size);
        }
        if (!rbuf) {
            ALOGE("RenderScript::message handler realloc error %zu", rbuf_size);
            // No clean way to recover now?
        }
        rsContextGetMessage(rs->mContext, rbuf, rbuf_size, &receiveLen, sizeof(receiveLen),
                            &subID, sizeof(subID));

        switch(r) {
        case RS_MESSAGE_TO_CLIENT_ERROR:
            ALOGE("RS Error %s", (const char *)rbuf);

            if(rs->mMessageFunc != NULL) {
                rs->mErrorFunc(usrID, (const char *)rbuf);
            }
            break;
        case RS_MESSAGE_TO_CLIENT_EXCEPTION:
            // teardown. But we want to avoid starving other threads during
            // teardown by yielding until the next line in the destructor can
            // execute to set mRun = false
            usleep(1000);
            break;
        case RS_MESSAGE_TO_CLIENT_USER:
            if(rs->mMessageFunc != NULL) {
                rs->mMessageFunc(usrID, rbuf, receiveLen);
            } else {
                ALOGE("Received a message from the script with no message handler installed.");
            }
            break;

        default:
            ALOGE("RenderScript unknown message type %i", r);
        }
    }

    if (rbuf) {
        free(rbuf);
    }
    ALOGE("RenderScript Message thread exiting.");
    return NULL;
}

void RenderScript::setErrorHandler(ErrorHandlerFunc_t func) {
    mErrorFunc = func;
}

void RenderScript::setMessageHandler(MessageHandlerFunc_t func) {
    mMessageFunc  = func;
}

void RenderScript::contextDump() {
}

void RenderScript::finish() {

}


