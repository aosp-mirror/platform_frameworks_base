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

#ifndef MP3_DECODER_H_

#define MP3_DECODER_H_

#include "../OMXComponentBase.h"

#include <OMX_Component.h>

#include <utils/Vector.h>

namespace android {

struct MP3Decoder : public OMXComponentBase {
    MP3Decoder(const OMX_CALLBACKTYPE *callbacks, OMX_PTR appData);
    virtual ~MP3Decoder();

    virtual OMX_ERRORTYPE sendCommand(
            OMX_COMMANDTYPE cmd, OMX_U32 param, OMX_PTR cmdData);

    virtual OMX_ERRORTYPE getParameter(
            OMX_INDEXTYPE index, OMX_PTR params);

    virtual OMX_ERRORTYPE setParameter(
            OMX_INDEXTYPE index, const OMX_PTR params);

    virtual OMX_ERRORTYPE getConfig(
            OMX_INDEXTYPE index, OMX_PTR config);

    virtual OMX_ERRORTYPE setConfig(
            OMX_INDEXTYPE index, const OMX_PTR config);

    virtual OMX_ERRORTYPE getExtensionIndex(
            const OMX_STRING name, OMX_INDEXTYPE *index);

    virtual OMX_ERRORTYPE useBuffer(
            OMX_BUFFERHEADERTYPE **bufHdr,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size,
            OMX_U8 *buffer);

    virtual OMX_ERRORTYPE allocateBuffer(
            OMX_BUFFERHEADERTYPE **bufHdr,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size);

    virtual OMX_ERRORTYPE freeBuffer(
            OMX_U32 portIndex,
            OMX_BUFFERHEADERTYPE *buffer);

    virtual OMX_ERRORTYPE emptyThisBuffer(OMX_BUFFERHEADERTYPE *buffer);
    virtual OMX_ERRORTYPE fillThisBuffer(OMX_BUFFERHEADERTYPE *buffer);

    virtual OMX_ERRORTYPE enumerateRoles(OMX_U8 *role, OMX_U32 index);

    virtual OMX_ERRORTYPE getState(OMX_STATETYPE *state);

private:
    enum {
        kPortIndexInput  = 0,
        kPortIndexOutput = 1,

        kNumPorts
    };

    enum {
        kPortFlagEnabling = 1
    };

    struct Port {
        uint32_t mFlags;
        Vector<OMX_BUFFERHEADERTYPE *> mBuffers;
        OMX_PARAM_PORTDEFINITIONTYPE mDefinition;
    };

    OMX_STATETYPE mState;
    OMX_STATETYPE mTargetState;

    Port mPorts[kNumPorts];

    void initPort(OMX_U32 portIndex);

    bool portIsDisabledOrPopulated(OMX_U32 portIndex) const;

    OMX_ERRORTYPE useOrAllocateBuffer(
            OMX_BUFFERHEADERTYPE **out,
            OMX_U32 portIndex,
            OMX_PTR appPrivate,
            OMX_U32 size,
            OMX_U8 *buffer);

    MP3Decoder(const MP3Decoder &);
    MP3Decoder &operator=(const MP3Decoder &);
};

}  // namespace android

#endif  // MP3_DECODER_H_
