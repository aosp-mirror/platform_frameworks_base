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

#include "OMXComponentBase.h"

#include <stdlib.h>

#include <media/stagefright/MediaDebug.h>

namespace android {

OMXComponentBase::OMXComponentBase(
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData)
    : mCallbacks(callbacks),
      mAppData(appData),
      mComponentHandle(NULL) {
}

OMXComponentBase::~OMXComponentBase() {}

void OMXComponentBase::setComponentHandle(OMX_COMPONENTTYPE *handle) {
    CHECK_EQ(mComponentHandle, NULL);
    mComponentHandle = handle;
}

void OMXComponentBase::postEvent(
        OMX_EVENTTYPE event, OMX_U32 param1, OMX_U32 param2) {
    (*mCallbacks->EventHandler)(
            mComponentHandle, mAppData, event, param1, param2, NULL);
}

void OMXComponentBase::postFillBufferDone(OMX_BUFFERHEADERTYPE *bufHdr) {
    (*mCallbacks->FillBufferDone)(mComponentHandle, mAppData, bufHdr);
}

void OMXComponentBase::postEmptyBufferDone(OMX_BUFFERHEADERTYPE *bufHdr) {
    (*mCallbacks->EmptyBufferDone)(mComponentHandle, mAppData, bufHdr);
}

static OMXComponentBase *getBase(OMX_HANDLETYPE hComponent) {
    return (OMXComponentBase *)
        ((OMX_COMPONENTTYPE *)hComponent)->pComponentPrivate;
}

static OMX_ERRORTYPE SendCommandWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent,
        OMX_IN  OMX_COMMANDTYPE Cmd,
        OMX_IN  OMX_U32 nParam1,
        OMX_IN  OMX_PTR pCmdData) {
    return getBase(hComponent)->sendCommand(Cmd, nParam1, pCmdData);
}

static OMX_ERRORTYPE GetParameterWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent, 
        OMX_IN  OMX_INDEXTYPE nParamIndex,  
        OMX_INOUT OMX_PTR pComponentParameterStructure) {
    return getBase(hComponent)->getParameter(
            nParamIndex, pComponentParameterStructure);
}

static OMX_ERRORTYPE SetParameterWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent, 
        OMX_IN  OMX_INDEXTYPE nIndex,
        OMX_IN  OMX_PTR pComponentParameterStructure) {
    return getBase(hComponent)->getParameter(
            nIndex, pComponentParameterStructure);
}

static OMX_ERRORTYPE GetConfigWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent,
        OMX_IN  OMX_INDEXTYPE nIndex, 
        OMX_INOUT OMX_PTR pComponentConfigStructure) {
    return getBase(hComponent)->getConfig(nIndex, pComponentConfigStructure);
}

static OMX_ERRORTYPE SetConfigWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent,
        OMX_IN  OMX_INDEXTYPE nIndex, 
        OMX_IN  OMX_PTR pComponentConfigStructure) {
    return getBase(hComponent)->setConfig(nIndex, pComponentConfigStructure);
}

static OMX_ERRORTYPE GetExtensionIndexWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent,
        OMX_IN  OMX_STRING cParameterName,
        OMX_OUT OMX_INDEXTYPE* pIndexType) {
    return getBase(hComponent)->getExtensionIndex(cParameterName, pIndexType);
}

static OMX_ERRORTYPE GetStateWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent,
        OMX_OUT OMX_STATETYPE* pState) {
    return getBase(hComponent)->getState(pState);
}

static OMX_ERRORTYPE UseBufferWrapper(
        OMX_IN OMX_HANDLETYPE hComponent,
        OMX_INOUT OMX_BUFFERHEADERTYPE** ppBufferHdr,
        OMX_IN OMX_U32 nPortIndex,
        OMX_IN OMX_PTR pAppPrivate,
        OMX_IN OMX_U32 nSizeBytes,
        OMX_IN OMX_U8* pBuffer) {
    return getBase(hComponent)->useBuffer(
            ppBufferHdr, nPortIndex, pAppPrivate, nSizeBytes, pBuffer);
}

static OMX_ERRORTYPE AllocateBufferWrapper(
        OMX_IN OMX_HANDLETYPE hComponent,
        OMX_INOUT OMX_BUFFERHEADERTYPE** ppBuffer,
        OMX_IN OMX_U32 nPortIndex,
        OMX_IN OMX_PTR pAppPrivate,
        OMX_IN OMX_U32 nSizeBytes) {
    return getBase(hComponent)->allocateBuffer(
            ppBuffer, nPortIndex, pAppPrivate, nSizeBytes);
}

static OMX_ERRORTYPE FreeBufferWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent,
        OMX_IN  OMX_U32 nPortIndex,
        OMX_IN  OMX_BUFFERHEADERTYPE* pBuffer) {
    return getBase(hComponent)->freeBuffer(nPortIndex, pBuffer);
}

static OMX_ERRORTYPE EmptyThisBufferWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent,
        OMX_IN  OMX_BUFFERHEADERTYPE* pBuffer) {
    return getBase(hComponent)->emptyThisBuffer(pBuffer);
}

static OMX_ERRORTYPE FillThisBufferWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent,
        OMX_IN  OMX_BUFFERHEADERTYPE* pBuffer) {
    return getBase(hComponent)->fillThisBuffer(pBuffer);
}

static OMX_ERRORTYPE ComponentDeInitWrapper(
        OMX_IN  OMX_HANDLETYPE hComponent) {
    delete getBase(hComponent);
    delete (OMX_COMPONENTTYPE *)hComponent;

    return OMX_ErrorNone;
}

static OMX_ERRORTYPE ComponentRoleEnumWrapper(
        OMX_IN OMX_HANDLETYPE hComponent,
        OMX_OUT OMX_U8 *cRole,
        OMX_IN OMX_U32 nIndex) {
    return getBase(hComponent)->enumerateRoles(cRole, nIndex);
}

// static
OMX_COMPONENTTYPE *OMXComponentBase::MakeComponent(OMXComponentBase *base) {
    OMX_COMPONENTTYPE *result = new OMX_COMPONENTTYPE;

    result->nSize = sizeof(OMX_COMPONENTTYPE);
    result->nVersion.s.nVersionMajor = 1;
    result->nVersion.s.nVersionMinor = 0;
    result->nVersion.s.nRevision = 0;
    result->nVersion.s.nStep = 0;
    result->pComponentPrivate = base;
    result->pApplicationPrivate = NULL;

    result->GetComponentVersion = NULL;
    result->SendCommand = SendCommandWrapper;
    result->GetParameter = GetParameterWrapper;
    result->SetParameter = SetParameterWrapper;
    result->GetConfig = GetConfigWrapper;
    result->SetConfig = SetConfigWrapper;
    result->GetExtensionIndex = GetExtensionIndexWrapper;
    result->GetState = GetStateWrapper;
    result->ComponentTunnelRequest = NULL;
    result->UseBuffer = UseBufferWrapper;
    result->AllocateBuffer = AllocateBufferWrapper;
    result->FreeBuffer = FreeBufferWrapper;
    result->EmptyThisBuffer = EmptyThisBufferWrapper;
    result->FillThisBuffer = FillThisBufferWrapper;
    result->SetCallbacks = NULL;
    result->ComponentDeInit = ComponentDeInitWrapper;
    result->UseEGLImage = NULL;
    result->ComponentRoleEnum = ComponentRoleEnumWrapper;

    base->setComponentHandle(result);

    return result;
}

}  // namespace android
