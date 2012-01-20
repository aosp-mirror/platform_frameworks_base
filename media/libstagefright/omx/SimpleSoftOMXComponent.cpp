/*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_TAG "SimpleSoftOMXComponent"
#include <utils/Log.h>

#include "include/SimpleSoftOMXComponent.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>

namespace android {

SimpleSoftOMXComponent::SimpleSoftOMXComponent(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SoftOMXComponent(name, callbacks, appData, component),
      mLooper(new ALooper),
      mHandler(new AHandlerReflector<SimpleSoftOMXComponent>(this)),
      mState(OMX_StateLoaded),
      mTargetState(OMX_StateLoaded) {
    mLooper->setName(name);
    mLooper->registerHandler(mHandler);

    mLooper->start(
            false, // runOnCallingThread
            false, // canCallJava
            ANDROID_PRIORITY_FOREGROUND);
}

void SimpleSoftOMXComponent::prepareForDestruction() {
    // The looper's queue may still contain messages referencing this
    // object. Make sure those are flushed before returning so that
    // a subsequent dlunload() does not pull out the rug from under us.

    mLooper->unregisterHandler(mHandler->id());
    mLooper->stop();
}

OMX_ERRORTYPE SimpleSoftOMXComponent::sendCommand(
        OMX_COMMANDTYPE cmd, OMX_U32 param, OMX_PTR data) {
    CHECK(data == NULL);

    sp<AMessage> msg = new AMessage(kWhatSendCommand, mHandler->id());
    msg->setInt32("cmd", cmd);
    msg->setInt32("param", param);
    msg->post();

    return OMX_ErrorNone;
}

bool SimpleSoftOMXComponent::isSetParameterAllowed(
        OMX_INDEXTYPE index, const OMX_PTR params) const {
    if (mState == OMX_StateLoaded) {
        return true;
    }

    OMX_U32 portIndex;

    switch (index) {
        case OMX_IndexParamPortDefinition:
        {
            portIndex = ((OMX_PARAM_PORTDEFINITIONTYPE *)params)->nPortIndex;
            break;
        }

        case OMX_IndexParamAudioPcm:
        {
            portIndex = ((OMX_AUDIO_PARAM_PCMMODETYPE *)params)->nPortIndex;
            break;
        }

        case OMX_IndexParamAudioAac:
        {
            portIndex = ((OMX_AUDIO_PARAM_AACPROFILETYPE *)params)->nPortIndex;
            break;
        }

        default:
            return false;
    }

    CHECK(portIndex < mPorts.size());

    return !mPorts.itemAt(portIndex).mDef.bEnabled;
}

OMX_ERRORTYPE SimpleSoftOMXComponent::getParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    Mutex::Autolock autoLock(mLock);
    return internalGetParameter(index, params);
}

OMX_ERRORTYPE SimpleSoftOMXComponent::setParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    Mutex::Autolock autoLock(mLock);

    CHECK(isSetParameterAllowed(index, params));

    return internalSetParameter(index, params);
}

OMX_ERRORTYPE SimpleSoftOMXComponent::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamPortDefinition:
        {
            OMX_PARAM_PORTDEFINITIONTYPE *defParams =
                (OMX_PARAM_PORTDEFINITIONTYPE *)params;

            if (defParams->nPortIndex >= mPorts.size()
                    || defParams->nSize
                            != sizeof(OMX_PARAM_PORTDEFINITIONTYPE)) {
                return OMX_ErrorUndefined;
            }

            const PortInfo *port =
                &mPorts.itemAt(defParams->nPortIndex);

            memcpy(defParams, &port->mDef, sizeof(port->mDef));

            return OMX_ErrorNone;
        }

        default:
            return OMX_ErrorUnsupportedIndex;
    }
}

OMX_ERRORTYPE SimpleSoftOMXComponent::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamPortDefinition:
        {
            OMX_PARAM_PORTDEFINITIONTYPE *defParams =
                (OMX_PARAM_PORTDEFINITIONTYPE *)params;

            if (defParams->nPortIndex >= mPorts.size()
                    || defParams->nSize
                            != sizeof(OMX_PARAM_PORTDEFINITIONTYPE)) {
                return OMX_ErrorUndefined;
            }

            PortInfo *port =
                &mPorts.editItemAt(defParams->nPortIndex);

            if (defParams->nBufferSize != port->mDef.nBufferSize) {
                CHECK_GE(defParams->nBufferSize, port->mDef.nBufferSize);
                port->mDef.nBufferSize = defParams->nBufferSize;
            }

            if (defParams->nBufferCountActual
                    != port->mDef.nBufferCountActual) {
                CHECK_GE(defParams->nBufferCountActual,
                         port->mDef.nBufferCountMin);

                port->mDef.nBufferCountActual = defParams->nBufferCountActual;
            }

            return OMX_ErrorNone;
        }

        default:
            return OMX_ErrorUnsupportedIndex;
    }
}

OMX_ERRORTYPE SimpleSoftOMXComponent::useBuffer(
        OMX_BUFFERHEADERTYPE **header,
        OMX_U32 portIndex,
        OMX_PTR appPrivate,
        OMX_U32 size,
        OMX_U8 *ptr) {
    Mutex::Autolock autoLock(mLock);
    CHECK_LT(portIndex, mPorts.size());

    *header = new OMX_BUFFERHEADERTYPE;
    (*header)->nSize = sizeof(OMX_BUFFERHEADERTYPE);
    (*header)->nVersion.s.nVersionMajor = 1;
    (*header)->nVersion.s.nVersionMinor = 0;
    (*header)->nVersion.s.nRevision = 0;
    (*header)->nVersion.s.nStep = 0;
    (*header)->pBuffer = ptr;
    (*header)->nAllocLen = size;
    (*header)->nFilledLen = 0;
    (*header)->nOffset = 0;
    (*header)->pAppPrivate = appPrivate;
    (*header)->pPlatformPrivate = NULL;
    (*header)->pInputPortPrivate = NULL;
    (*header)->pOutputPortPrivate = NULL;
    (*header)->hMarkTargetComponent = NULL;
    (*header)->pMarkData = NULL;
    (*header)->nTickCount = 0;
    (*header)->nTimeStamp = 0;
    (*header)->nFlags = 0;
    (*header)->nOutputPortIndex = portIndex;
    (*header)->nInputPortIndex = portIndex;

    PortInfo *port = &mPorts.editItemAt(portIndex);

    CHECK(mState == OMX_StateLoaded || port->mDef.bEnabled == OMX_FALSE);

    CHECK_LT(port->mBuffers.size(), port->mDef.nBufferCountActual);

    port->mBuffers.push();

    BufferInfo *buffer =
        &port->mBuffers.editItemAt(port->mBuffers.size() - 1);

    buffer->mHeader = *header;
    buffer->mOwnedByUs = false;

    if (port->mBuffers.size() == port->mDef.nBufferCountActual) {
        port->mDef.bPopulated = OMX_TRUE;
        checkTransitions();
    }

    return OMX_ErrorNone;
}

OMX_ERRORTYPE SimpleSoftOMXComponent::allocateBuffer(
        OMX_BUFFERHEADERTYPE **header,
        OMX_U32 portIndex,
        OMX_PTR appPrivate,
        OMX_U32 size) {
    OMX_U8 *ptr = new OMX_U8[size];

    OMX_ERRORTYPE err =
        useBuffer(header, portIndex, appPrivate, size, ptr);

    if (err != OMX_ErrorNone) {
        delete[] ptr;
        ptr = NULL;

        return err;
    }

    CHECK((*header)->pPlatformPrivate == NULL);
    (*header)->pPlatformPrivate = ptr;

    return OMX_ErrorNone;
}

OMX_ERRORTYPE SimpleSoftOMXComponent::freeBuffer(
        OMX_U32 portIndex,
        OMX_BUFFERHEADERTYPE *header) {
    Mutex::Autolock autoLock(mLock);

    CHECK_LT(portIndex, mPorts.size());

    PortInfo *port = &mPorts.editItemAt(portIndex);

#if 0 // XXX
    CHECK((mState == OMX_StateIdle && mTargetState == OMX_StateLoaded)
            || port->mDef.bEnabled == OMX_FALSE);
#endif

    bool found = false;
    for (size_t i = 0; i < port->mBuffers.size(); ++i) {
        BufferInfo *buffer = &port->mBuffers.editItemAt(i);

        if (buffer->mHeader == header) {
            CHECK(!buffer->mOwnedByUs);

            if (header->pPlatformPrivate != NULL) {
                // This buffer's data was allocated by us.
                CHECK(header->pPlatformPrivate == header->pBuffer);

                delete[] header->pBuffer;
                header->pBuffer = NULL;
            }

            delete header;
            header = NULL;

            port->mBuffers.removeAt(i);
            port->mDef.bPopulated = OMX_FALSE;

            checkTransitions();

            found = true;
            break;
        }
    }

    CHECK(found);

    return OMX_ErrorNone;
}

OMX_ERRORTYPE SimpleSoftOMXComponent::emptyThisBuffer(
        OMX_BUFFERHEADERTYPE *buffer) {
    sp<AMessage> msg = new AMessage(kWhatEmptyThisBuffer, mHandler->id());
    msg->setPointer("header", buffer);
    msg->post();

    return OMX_ErrorNone;
}

OMX_ERRORTYPE SimpleSoftOMXComponent::fillThisBuffer(
        OMX_BUFFERHEADERTYPE *buffer) {
    sp<AMessage> msg = new AMessage(kWhatFillThisBuffer, mHandler->id());
    msg->setPointer("header", buffer);
    msg->post();

    return OMX_ErrorNone;
}

OMX_ERRORTYPE SimpleSoftOMXComponent::getState(OMX_STATETYPE *state) {
    Mutex::Autolock autoLock(mLock);

    *state = mState;

    return OMX_ErrorNone;
}

void SimpleSoftOMXComponent::onMessageReceived(const sp<AMessage> &msg) {
    Mutex::Autolock autoLock(mLock);

    switch (msg->what()) {
        case kWhatSendCommand:
        {
            int32_t cmd, param;
            CHECK(msg->findInt32("cmd", &cmd));
            CHECK(msg->findInt32("param", &param));

            onSendCommand((OMX_COMMANDTYPE)cmd, (OMX_U32)param);
            break;
        }

        case kWhatEmptyThisBuffer:
        case kWhatFillThisBuffer:
        {
            OMX_BUFFERHEADERTYPE *header;
            CHECK(msg->findPointer("header", (void **)&header));

            CHECK(mState == OMX_StateExecuting && mTargetState == mState);

            bool found = false;
            for (size_t i = 0; i < mPorts.size(); ++i) {
                PortInfo *port = &mPorts.editItemAt(i);

                for (size_t j = 0; j < port->mBuffers.size(); ++j) {
                    BufferInfo *buffer = &port->mBuffers.editItemAt(j);

                    if (buffer->mHeader == header) {
                        CHECK(!buffer->mOwnedByUs);

                        buffer->mOwnedByUs = true;

                        CHECK((msg->what() == kWhatEmptyThisBuffer
                                    && port->mDef.eDir == OMX_DirInput)
                                || (port->mDef.eDir == OMX_DirOutput));

                        port->mQueue.push_back(buffer);
                        onQueueFilled(i);

                        found = true;
                        break;
                    }
                }
            }

            CHECK(found);
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void SimpleSoftOMXComponent::onSendCommand(
        OMX_COMMANDTYPE cmd, OMX_U32 param) {
    switch (cmd) {
        case OMX_CommandStateSet:
        {
            onChangeState((OMX_STATETYPE)param);
            break;
        }

        case OMX_CommandPortEnable:
        case OMX_CommandPortDisable:
        {
            onPortEnable(param, cmd == OMX_CommandPortEnable);
            break;
        }

        case OMX_CommandFlush:
        {
            onPortFlush(param, true /* sendFlushComplete */);
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void SimpleSoftOMXComponent::onChangeState(OMX_STATETYPE state) {
    // We shouldn't be in a state transition already.
    CHECK_EQ((int)mState, (int)mTargetState);

    switch (mState) {
        case OMX_StateLoaded:
            CHECK_EQ((int)state, (int)OMX_StateIdle);
            break;
        case OMX_StateIdle:
            CHECK(state == OMX_StateLoaded || state == OMX_StateExecuting);
            break;
        case OMX_StateExecuting:
        {
            CHECK_EQ((int)state, (int)OMX_StateIdle);

            for (size_t i = 0; i < mPorts.size(); ++i) {
                onPortFlush(i, false /* sendFlushComplete */);
            }

            mState = OMX_StateIdle;
            notify(OMX_EventCmdComplete, OMX_CommandStateSet, state, NULL);
            break;
        }

        default:
            TRESPASS();
    }

    mTargetState = state;

    checkTransitions();
}

void SimpleSoftOMXComponent::onPortEnable(OMX_U32 portIndex, bool enable) {
    CHECK_LT(portIndex, mPorts.size());

    PortInfo *port = &mPorts.editItemAt(portIndex);
    CHECK_EQ((int)port->mTransition, (int)PortInfo::NONE);
    CHECK(port->mDef.bEnabled == !enable);

    if (!enable) {
        port->mDef.bEnabled = OMX_FALSE;
        port->mTransition = PortInfo::DISABLING;

        for (size_t i = 0; i < port->mBuffers.size(); ++i) {
            BufferInfo *buffer = &port->mBuffers.editItemAt(i);

            if (buffer->mOwnedByUs) {
                buffer->mOwnedByUs = false;

                if (port->mDef.eDir == OMX_DirInput) {
                    notifyEmptyBufferDone(buffer->mHeader);
                } else {
                    CHECK_EQ(port->mDef.eDir, OMX_DirOutput);
                    notifyFillBufferDone(buffer->mHeader);
                }
            }
        }

        port->mQueue.clear();
    } else {
        port->mTransition = PortInfo::ENABLING;
    }

    checkTransitions();
}

void SimpleSoftOMXComponent::onPortFlush(
        OMX_U32 portIndex, bool sendFlushComplete) {
    if (portIndex == OMX_ALL) {
        for (size_t i = 0; i < mPorts.size(); ++i) {
            onPortFlush(i, sendFlushComplete);
        }

        if (sendFlushComplete) {
            notify(OMX_EventCmdComplete, OMX_CommandFlush, OMX_ALL, NULL);
        }

        return;
    }

    CHECK_LT(portIndex, mPorts.size());

    PortInfo *port = &mPorts.editItemAt(portIndex);
    CHECK_EQ((int)port->mTransition, (int)PortInfo::NONE);

    for (size_t i = 0; i < port->mBuffers.size(); ++i) {
        BufferInfo *buffer = &port->mBuffers.editItemAt(i);

        if (!buffer->mOwnedByUs) {
            continue;
        }

        buffer->mHeader->nFilledLen = 0;
        buffer->mHeader->nOffset = 0;
        buffer->mHeader->nFlags = 0;

        buffer->mOwnedByUs = false;

        if (port->mDef.eDir == OMX_DirInput) {
            notifyEmptyBufferDone(buffer->mHeader);
        } else {
            CHECK_EQ(port->mDef.eDir, OMX_DirOutput);

            notifyFillBufferDone(buffer->mHeader);
        }
    }

    port->mQueue.clear();

    if (sendFlushComplete) {
        notify(OMX_EventCmdComplete, OMX_CommandFlush, portIndex, NULL);

        onPortFlushCompleted(portIndex);
    }
}

void SimpleSoftOMXComponent::checkTransitions() {
    if (mState != mTargetState) {
        bool transitionComplete = true;

        if (mState == OMX_StateLoaded) {
            CHECK_EQ((int)mTargetState, (int)OMX_StateIdle);

            for (size_t i = 0; i < mPorts.size(); ++i) {
                const PortInfo &port = mPorts.itemAt(i);
                if (port.mDef.bEnabled == OMX_FALSE) {
                    continue;
                }

                if (port.mDef.bPopulated == OMX_FALSE) {
                    transitionComplete = false;
                    break;
                }
            }
        } else if (mTargetState == OMX_StateLoaded) {
            CHECK_EQ((int)mState, (int)OMX_StateIdle);

            for (size_t i = 0; i < mPorts.size(); ++i) {
                const PortInfo &port = mPorts.itemAt(i);
                if (port.mDef.bEnabled == OMX_FALSE) {
                    continue;
                }

                size_t n = port.mBuffers.size();

                if (n > 0) {
                    CHECK_LE(n, port.mDef.nBufferCountActual);

                    if (n == port.mDef.nBufferCountActual) {
                        CHECK_EQ((int)port.mDef.bPopulated, (int)OMX_TRUE);
                    } else {
                        CHECK_EQ((int)port.mDef.bPopulated, (int)OMX_FALSE);
                    }

                    transitionComplete = false;
                    break;
                }
            }
        }

        if (transitionComplete) {
            mState = mTargetState;

            notify(OMX_EventCmdComplete, OMX_CommandStateSet, mState, NULL);
        }
    }

    for (size_t i = 0; i < mPorts.size(); ++i) {
        PortInfo *port = &mPorts.editItemAt(i);

        if (port->mTransition == PortInfo::DISABLING) {
            if (port->mBuffers.empty()) {
                ALOGV("Port %d now disabled.", i);

                port->mTransition = PortInfo::NONE;
                notify(OMX_EventCmdComplete, OMX_CommandPortDisable, i, NULL);

                onPortEnableCompleted(i, false /* enabled */);
            }
        } else if (port->mTransition == PortInfo::ENABLING) {
            if (port->mDef.bPopulated == OMX_TRUE) {
                ALOGV("Port %d now enabled.", i);

                port->mTransition = PortInfo::NONE;
                port->mDef.bEnabled = OMX_TRUE;
                notify(OMX_EventCmdComplete, OMX_CommandPortEnable, i, NULL);

                onPortEnableCompleted(i, true /* enabled */);
            }
        }
    }
}

void SimpleSoftOMXComponent::addPort(const OMX_PARAM_PORTDEFINITIONTYPE &def) {
    CHECK_EQ(def.nPortIndex, mPorts.size());

    mPorts.push();
    PortInfo *info = &mPorts.editItemAt(mPorts.size() - 1);
    info->mDef = def;
    info->mTransition = PortInfo::NONE;
}

void SimpleSoftOMXComponent::onQueueFilled(OMX_U32 portIndex) {
}

void SimpleSoftOMXComponent::onPortFlushCompleted(OMX_U32 portIndex) {
}

void SimpleSoftOMXComponent::onPortEnableCompleted(
        OMX_U32 portIndex, bool enabled) {
}

List<SimpleSoftOMXComponent::BufferInfo *> &
SimpleSoftOMXComponent::getPortQueue(OMX_U32 portIndex) {
    CHECK_LT(portIndex, mPorts.size());
    return mPorts.editItemAt(portIndex).mQueue;
}

SimpleSoftOMXComponent::PortInfo *SimpleSoftOMXComponent::editPortInfo(
        OMX_U32 portIndex) {
    CHECK_LT(portIndex, mPorts.size());
    return &mPorts.editItemAt(portIndex);
}

}  // namespace android
