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

#include "MP3Decoder.h"

#include <media/stagefright/MediaDebug.h>

namespace android {

MP3Decoder::MP3Decoder(
        const OMX_CALLBACKTYPE *callbacks, OMX_PTR appData)
    : OMXComponentBase(callbacks, appData),
      mState(OMX_StateLoaded),
      mTargetState(OMX_StateLoaded) {
    initPort(kPortIndexInput);
    initPort(kPortIndexOutput);
}

void MP3Decoder::initPort(OMX_U32 portIndex) {
    mPorts[portIndex].mFlags = 0;

    OMX_PARAM_PORTDEFINITIONTYPE *def = &mPorts[portIndex].mDefinition;

    def->nSize = sizeof(*def);
    def->nVersion.s.nVersionMajor = 1;
    def->nVersion.s.nVersionMinor = 0;
    def->nVersion.s.nRevision = 0;
    def->nVersion.s.nStep = 0;
    def->nPortIndex = portIndex;
    def->eDir = (portIndex == kPortIndexInput) ? OMX_DirInput : OMX_DirOutput;
    def->nBufferCountActual = 1;
    def->nBufferCountMin = 1;
    def->bEnabled = OMX_TRUE;
    def->bPopulated = OMX_FALSE;
    def->eDomain = OMX_PortDomainAudio;

    OMX_AUDIO_PORTDEFINITIONTYPE *audioDef = &def->format.audio;

    if (portIndex == kPortIndexInput) {
        def->nBufferSize = 8192;
        strcpy(audioDef->cMIMEType, "audio/mpeg");
        audioDef->pNativeRender = NULL;
        audioDef->bFlagErrorConcealment = OMX_FALSE;
        audioDef->eEncoding = OMX_AUDIO_CodingMP3;
    } else {
        CHECK_EQ(portIndex, kPortIndexOutput);

        def->nBufferSize = 8192;
        strcpy(audioDef->cMIMEType, "audio/raw");
        audioDef->pNativeRender = NULL;
        audioDef->bFlagErrorConcealment = OMX_FALSE;
        audioDef->eEncoding = OMX_AUDIO_CodingPCM;
    }

    def->bBuffersContiguous = OMX_TRUE;  // XXX What's this?
    def->nBufferAlignment = 1;
}

MP3Decoder::~MP3Decoder() {
}

OMX_ERRORTYPE MP3Decoder::sendCommand(
        OMX_COMMANDTYPE cmd, OMX_U32 param, OMX_PTR cmdData) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE MP3Decoder::getParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamPortDefinition:
        {
            OMX_PARAM_PORTDEFINITIONTYPE *def =
                (OMX_PARAM_PORTDEFINITIONTYPE *)params;

            if (def->nSize < sizeof(OMX_PARAM_PORTDEFINITIONTYPE)) {
                return OMX_ErrorBadParameter;
            }

            if (def->nPortIndex != kPortIndexInput
                && def->nPortIndex != kPortIndexOutput) {
                return OMX_ErrorBadPortIndex;
            }

            if (mPorts[def->nPortIndex].mDefinition.bEnabled
                    && mState != OMX_StateLoaded) {
                return OMX_ErrorIncorrectStateOperation;
            }

            memcpy(def, &mPorts[def->nPortIndex].mDefinition,
                   sizeof(OMX_PARAM_PORTDEFINITIONTYPE));

            return OMX_ErrorNone;
        }

        default:
            return OMX_ErrorUnsupportedIndex;
    }
}

OMX_ERRORTYPE MP3Decoder::setParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE MP3Decoder::getConfig(
        OMX_INDEXTYPE index, OMX_PTR config) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE MP3Decoder::setConfig(
        OMX_INDEXTYPE index, const OMX_PTR config) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE MP3Decoder::getExtensionIndex(
        const OMX_STRING name, OMX_INDEXTYPE *index) {
    return OMX_ErrorUndefined;
}

bool MP3Decoder::portIsDisabledOrPopulated(OMX_U32 portIndex) const {
    return !mPorts[portIndex].mDefinition.bEnabled
        || mPorts[portIndex].mDefinition.bPopulated;
}

OMX_ERRORTYPE MP3Decoder::useOrAllocateBuffer(
        OMX_BUFFERHEADERTYPE **out,
        OMX_U32 portIndex,
        OMX_PTR appPrivate,
        OMX_U32 size,
        OMX_U8 *buffer) {
    if (portIndex != kPortIndexInput && portIndex != kPortIndexOutput) {
        return OMX_ErrorBadPortIndex;
    }

    if (!mPorts[portIndex].mDefinition.bEnabled) {
        if (!(mPorts[portIndex].mFlags & kPortFlagEnabling)) {
            return OMX_ErrorIncorrectStateOperation;
        }
    } else if (mState != OMX_StateLoaded || mTargetState != OMX_StateIdle)  {
        return OMX_ErrorIncorrectStateOperation;
    }

    if (size < mPorts[portIndex].mDefinition.nBufferSize) {
        return OMX_ErrorBadParameter;
    }

    if (out == NULL) {
        return OMX_ErrorBadParameter;
    }

    if (buffer == NULL) {
        // We need to allocate memory.
        buffer = new OMX_U8[size];

        // XXX Keep track of buffers we allocated and free them later.
    }

    OMX_BUFFERHEADERTYPE *bufHdr = new OMX_BUFFERHEADERTYPE;
    bufHdr->nSize = sizeof(*bufHdr);
    bufHdr->nVersion.s.nVersionMajor = 1;
    bufHdr->nVersion.s.nVersionMinor = 0;
    bufHdr->nVersion.s.nRevision = 0;
    bufHdr->nVersion.s.nStep = 0;
    bufHdr->pBuffer = buffer;
    bufHdr->nAllocLen = size;
    bufHdr->nFilledLen = 0;
    bufHdr->nOffset = 0;
    bufHdr->pAppPrivate = appPrivate;
    bufHdr->pPlatformPrivate = NULL;
    bufHdr->pInputPortPrivate = NULL;
    bufHdr->pOutputPortPrivate = NULL;
    bufHdr->hMarkTargetComponent = NULL;
    bufHdr->pMarkData = NULL;
    bufHdr->nTickCount = 0;
    bufHdr->nTimeStamp = 0;
    bufHdr->nFlags = 0;
    bufHdr->nOutputPortIndex = 0;
    bufHdr->nInputPortIndex = 0;

    mPorts[portIndex].mBuffers.push(bufHdr);

    if (mPorts[portIndex].mBuffers.size()
            == mPorts[portIndex].mDefinition.nBufferCountActual) {
        if (mPorts[portIndex].mDefinition.bEnabled) {
            mPorts[portIndex].mDefinition.bPopulated = OMX_TRUE;
        } else if (mPorts[portIndex].mFlags & kPortFlagEnabling) {
            mPorts[portIndex].mFlags &= ~kPortFlagEnabling;
            mPorts[portIndex].mDefinition.bEnabled = OMX_TRUE;
            mPorts[portIndex].mDefinition.bPopulated = OMX_TRUE;
            postEvent(OMX_EventCmdComplete, OMX_CommandPortEnable, portIndex);
        }
    }

    if (mState == OMX_StateLoaded
        && portIsDisabledOrPopulated(kPortIndexInput)
        && portIsDisabledOrPopulated(kPortIndexOutput)) {
        mState = OMX_StateIdle;
        postEvent(OMX_EventCmdComplete, OMX_CommandStateSet, mState);
    }

    *out = bufHdr;

    return OMX_ErrorNone;
}

OMX_ERRORTYPE MP3Decoder::useBuffer(
        OMX_BUFFERHEADERTYPE **out,
        OMX_U32 portIndex,
        OMX_PTR appPrivate,
        OMX_U32 size,
        OMX_U8 *buffer) {
    if (buffer == NULL) {
        return OMX_ErrorBadParameter;
    }

    return useOrAllocateBuffer(out, portIndex, appPrivate, size, buffer);
}

OMX_ERRORTYPE MP3Decoder::allocateBuffer(
        OMX_BUFFERHEADERTYPE **out,
        OMX_U32 portIndex,
        OMX_PTR appPrivate,
        OMX_U32 size) {
    return useOrAllocateBuffer(out, portIndex, appPrivate, size, NULL);
}

OMX_ERRORTYPE MP3Decoder::freeBuffer(
        OMX_U32 portIndex,
        OMX_BUFFERHEADERTYPE *buffer) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE MP3Decoder::emptyThisBuffer(OMX_BUFFERHEADERTYPE *buffer) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE MP3Decoder::fillThisBuffer(OMX_BUFFERHEADERTYPE *buffer) {
    return OMX_ErrorUndefined;
}

OMX_ERRORTYPE MP3Decoder::enumerateRoles(OMX_U8 *role, OMX_U32 index) {
    return OMX_ErrorNoMore;
}

OMX_ERRORTYPE MP3Decoder::getState(OMX_STATETYPE *state) {
    *state = mState;

    return OMX_ErrorNone;
}

}  // namespace android
