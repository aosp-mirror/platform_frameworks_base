/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef __GLTRACE_CONTEXT_H_
#define __GLTRACE_CONTEXT_H_

#include <map>

#include "hooks.h"
#include "gltrace_transport.h"

namespace android {
namespace gltrace {

using ::android::gl_hooks_t;

enum FBBinding {CURRENTLY_BOUND_FB, FB0};

/** GL Trace Context info associated with each EGLContext */
class GLTraceContext {
    int mId;                    /* unique context id */

    void *fbcontents;           /* memory area to read framebuffer contents */
    void *fbcompressed;         /* destination for lzf compressed framebuffer */
    unsigned fbcontentsSize;    /* size of fbcontents & fbcompressed buffers */

    BufferedOutputStream *mBufferedOutputStream; /* stream where trace info is sent */

    void resizeFBMemory(unsigned minSize);
public:
    gl_hooks_t *hooks;

    GLTraceContext(int id, BufferedOutputStream *stream);
    int getId();
    void getCompressedFB(void **fb, unsigned *fbsize,
                            unsigned *fbwidth, unsigned *fbheight,
                            FBBinding fbToRead);
    void traceGLMessage(GLMessage *msg);
};

/** Per process trace state. */
class GLTraceState {
    int mTraceContextIds;
    TCPStream *mStream;
    std::map<EGLContext, GLTraceContext*> mPerContextState;
public:
    GLTraceState(TCPStream *stream);
    ~GLTraceState();

    GLTraceContext *createTraceContext(int version, EGLContext c);
    GLTraceContext *getTraceContext(EGLContext c);

    TCPStream *getStream();
};

void setupTraceContextThreadSpecific(GLTraceContext *context);
GLTraceContext *getGLTraceContext();
void releaseContext();

};
};

#endif
