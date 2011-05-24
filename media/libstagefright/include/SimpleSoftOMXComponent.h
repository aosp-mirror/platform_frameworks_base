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

#ifndef SIMPLE_SOFT_OMX_COMPONENT_H_

#define SIMPLE_SOFT_OMX_COMPONENT_H_

#include "SoftOMXComponent.h"

#include <media/stagefright/foundation/AHandlerReflector.h>
#include <utils/RefBase.h>
#include <utils/threads.h>
#include <utils/Vector.h>

namespace android {

struct ALooper;

struct SimpleSoftOMXComponent : public SoftOMXComponent {
    SimpleSoftOMXComponent(
            const char *name,
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData,
            OMX_COMPONENTTYPE **component);

    virtual void prepareForDestruction();

    void onMessageReceived(const sp<AMessage> &msg);

protected:
    struct BufferInfo {
        OMX_BUFFERHEADERTYPE *mHeader;
        bool mOwnedByUs;
    };

    struct PortInfo {
        OMX_PARAM_PORTDEFINITIONTYPE mDef;
        Vector<BufferInfo> mBuffers;
        List<BufferInfo *> mQueue;

        enum {
            NONE,
            DISABLING,
            ENABLING,
        } mTransition;
    };

    void addPort(const OMX_PARAM_PORTDEFINITIONTYPE &def);

    virtual OMX_ERRORTYPE internalGetParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE internalSetParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual void onQueueFilled(OMX_U32 portIndex);
    List<BufferInfo *> &getPortQueue(OMX_U32 portIndex);

    virtual void onPortFlushCompleted(OMX_U32 portIndex);
    virtual void onPortEnableCompleted(OMX_U32 portIndex, bool enabled);

    PortInfo *editPortInfo(OMX_U32 portIndex);

private:
    enum {
        kWhatSendCommand,
        kWhatEmptyThisBuffer,
        kWhatFillThisBuffer,
    };

    Mutex mLock;

    sp<ALooper> mLooper;
    sp<AHandlerReflector<SimpleSoftOMXComponent> > mHandler;

    OMX_STATETYPE mState;
    OMX_STATETYPE mTargetState;

    Vector<PortInfo> mPorts;

    bool isSetParameterAllowed(
            OMX_INDEXTYPE index, const OMX_PTR params) const;

    virtual OMX_ERRORTYPE sendCommand(
            OMX_COMMANDTYPE cmd, OMX_U32 param, OMX_PTR data);

    virtual OMX_ERRORTYPE getParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE setParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual OMX_ERRORTYPE useBuffer(
            OMX_BUFFERHEADERTYPE **buffer,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size,
            OMX_U8 *ptr);

    virtual OMX_ERRORTYPE allocateBuffer(
            OMX_BUFFERHEADERTYPE **buffer,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size);

    virtual OMX_ERRORTYPE freeBuffer(
            OMX_U32 portIndex,
            OMX_BUFFERHEADERTYPE *buffer);

    virtual OMX_ERRORTYPE emptyThisBuffer(
            OMX_BUFFERHEADERTYPE *buffer);

    virtual OMX_ERRORTYPE fillThisBuffer(
            OMX_BUFFERHEADERTYPE *buffer);

    virtual OMX_ERRORTYPE getState(OMX_STATETYPE *state);

    void onSendCommand(OMX_COMMANDTYPE cmd, OMX_U32 param);
    void onChangeState(OMX_STATETYPE state);
    void onPortEnable(OMX_U32 portIndex, bool enable);
    void onPortFlush(OMX_U32 portIndex, bool sendFlushComplete);

    void checkTransitions();

    DISALLOW_EVIL_CONSTRUCTORS(SimpleSoftOMXComponent);
};

}  // namespace android

#endif  // SIMPLE_SOFT_OMX_COMPONENT_H_
