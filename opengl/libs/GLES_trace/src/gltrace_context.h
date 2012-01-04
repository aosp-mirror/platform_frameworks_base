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

#include "hooks.h"

namespace android {
namespace gltrace {

using ::android::gl_hooks_t;

enum FBBinding {CURRENTLY_BOUND_FB, FB0};

class GLTraceContext {
    void *fbcontents;           /* memory area to read framebuffer contents */
    void *fbcompressed;         /* destination for lzf compressed framebuffer */
    unsigned fbcontentsSize;    /* size of fbcontents & fbcompressed buffers */

    void resizeFBMemory(unsigned minSize);
public:
    gl_hooks_t *hooks;

    GLTraceContext();
    void getCompressedFB(void **fb, unsigned *fbsize,
                            unsigned *fbwidth, unsigned *fbheight,
                            FBBinding fbToRead);
};

GLTraceContext *getGLTraceContext();
void setGLTraceContext(GLTraceContext *c);
void initContext(unsigned version, gl_hooks_t *hooks);
void releaseContext();

};
};

#endif
