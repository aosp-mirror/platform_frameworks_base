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

#ifndef OMX_COMPONENT_BASE_H_

#define OMX_COMPONENT_BASE_H_

#include <OMX_Component.h>

namespace android {

struct OMXComponentBase {
    OMXComponentBase(
            const OMX_CALLBACKTYPE *callbacks,
            OMX_PTR appData);

    virtual ~OMXComponentBase();

    virtual OMX_ERRORTYPE sendCommand(
            OMX_COMMANDTYPE cmd, OMX_U32 param, OMX_PTR cmdData) = 0;

    virtual OMX_ERRORTYPE getParameter(
            OMX_INDEXTYPE index, OMX_PTR params) = 0;

    virtual OMX_ERRORTYPE setParameter(
            OMX_INDEXTYPE index, const OMX_PTR params) = 0;

    virtual OMX_ERRORTYPE getConfig(
            OMX_INDEXTYPE index, OMX_PTR config) = 0;

    virtual OMX_ERRORTYPE setConfig(
            OMX_INDEXTYPE index, const OMX_PTR config) = 0;

    virtual OMX_ERRORTYPE getExtensionIndex(
            const OMX_STRING name, OMX_INDEXTYPE *index) = 0;

    virtual OMX_ERRORTYPE useBuffer(
            OMX_BUFFERHEADERTYPE **bufHdr,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size,
            OMX_U8 *buffer) = 0;

    virtual OMX_ERRORTYPE allocateBuffer(
            OMX_BUFFERHEADERTYPE **bufHdr,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size) = 0;

    virtual OMX_ERRORTYPE freeBuffer(
            OMX_U32 portIndex,
            OMX_BUFFERHEADERTYPE *buffer) = 0;

    virtual OMX_ERRORTYPE emptyThisBuffer(OMX_BUFFERHEADERTYPE *buffer) = 0;
    virtual OMX_ERRORTYPE fillThisBuffer(OMX_BUFFERHEADERTYPE *buffer) = 0;

    virtual OMX_ERRORTYPE enumerateRoles(OMX_U8 *role, OMX_U32 index) = 0;

    virtual OMX_ERRORTYPE getState(OMX_STATETYPE *state) = 0;

    // Wraps a given OMXComponentBase instance into an OMX_COMPONENTTYPE
    // as required by OpenMAX APIs.
    static OMX_COMPONENTTYPE *MakeComponent(OMXComponentBase *base);

protected:
    void postEvent(OMX_EVENTTYPE event, OMX_U32 param1, OMX_U32 param2);
    void postFillBufferDone(OMX_BUFFERHEADERTYPE *bufHdr);
    void postEmptyBufferDone(OMX_BUFFERHEADERTYPE *bufHdr);

private:
    void setComponentHandle(OMX_COMPONENTTYPE *handle);

    const OMX_CALLBACKTYPE *mCallbacks;
    OMX_PTR mAppData;
    OMX_COMPONENTTYPE *mComponentHandle;

    OMXComponentBase(const OMXComponentBase &);
    OMXComponentBase &operator=(const OMXComponentBase &);
};

}  // namespace android

#endif  // OMX_COMPONENT_BASE_H_
