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

#ifndef SOFT_OMX_COMPONENT_H_

#define SOFT_OMX_COMPONENT_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AString.h>
#include <utils/RefBase.h>

#include <OMX_Component.h>

namespace android {

struct SoftOMXComponent : public RefBase {
    SoftOMXComponent(
            const char *name,
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData,
            OMX_COMPONENTTYPE **component);

    virtual OMX_ERRORTYPE initCheck() const;

    void setLibHandle(void *libHandle);
    void *libHandle() const;

    virtual void prepareForDestruction() {}

protected:
    virtual ~SoftOMXComponent();

    const char *name() const;

    void notify(
            OMX_EVENTTYPE event,
            OMX_U32 data1, OMX_U32 data2, OMX_PTR data);

    void notifyEmptyBufferDone(OMX_BUFFERHEADERTYPE *header);
    void notifyFillBufferDone(OMX_BUFFERHEADERTYPE *header);

    virtual OMX_ERRORTYPE sendCommand(
            OMX_COMMANDTYPE cmd, OMX_U32 param, OMX_PTR data);

    virtual OMX_ERRORTYPE getParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE setParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual OMX_ERRORTYPE getConfig(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE setConfig(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual OMX_ERRORTYPE getExtensionIndex(
            const char *name, OMX_INDEXTYPE *index);

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

private:
    AString mName;
    const OMX_CALLBACKTYPE *mCallbacks;
    OMX_COMPONENTTYPE *mComponent;

    void *mLibHandle;

    static OMX_ERRORTYPE SendCommandWrapper(
            OMX_HANDLETYPE component,
            OMX_COMMANDTYPE cmd,
            OMX_U32 param,
            OMX_PTR data);

    static OMX_ERRORTYPE GetParameterWrapper(
            OMX_HANDLETYPE component,
            OMX_INDEXTYPE index,
            OMX_PTR params);

    static OMX_ERRORTYPE SetParameterWrapper(
            OMX_HANDLETYPE component,
            OMX_INDEXTYPE index,
            OMX_PTR params);

    static OMX_ERRORTYPE GetConfigWrapper(
            OMX_HANDLETYPE component,
            OMX_INDEXTYPE index,
            OMX_PTR params);

    static OMX_ERRORTYPE SetConfigWrapper(
            OMX_HANDLETYPE component,
            OMX_INDEXTYPE index,
            OMX_PTR params);

    static OMX_ERRORTYPE GetExtensionIndexWrapper(
            OMX_HANDLETYPE component,
            OMX_STRING name,
            OMX_INDEXTYPE *index);

    static OMX_ERRORTYPE UseBufferWrapper(
            OMX_HANDLETYPE component,
            OMX_BUFFERHEADERTYPE **buffer,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size,
            OMX_U8 *ptr);

    static OMX_ERRORTYPE AllocateBufferWrapper(
            OMX_HANDLETYPE component,
            OMX_BUFFERHEADERTYPE **buffer,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size);

    static OMX_ERRORTYPE FreeBufferWrapper(
            OMX_HANDLETYPE component,
            OMX_U32 portIndex,
            OMX_BUFFERHEADERTYPE *buffer);

    static OMX_ERRORTYPE EmptyThisBufferWrapper(
            OMX_HANDLETYPE component,
            OMX_BUFFERHEADERTYPE *buffer);

    static OMX_ERRORTYPE FillThisBufferWrapper(
            OMX_HANDLETYPE component,
            OMX_BUFFERHEADERTYPE *buffer);

    static OMX_ERRORTYPE GetStateWrapper(
            OMX_HANDLETYPE component,
            OMX_STATETYPE *state);

    DISALLOW_EVIL_CONSTRUCTORS(SoftOMXComponent);
};

}  // namespace android

#endif  // SOFT_OMX_COMPONENT_H_
