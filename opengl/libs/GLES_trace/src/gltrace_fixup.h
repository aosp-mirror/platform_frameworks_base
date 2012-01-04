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

#ifndef __GLTRACE_FIXUP_H_
#define __GLTRACE_FIXUP_H_

#include <utils/Timers.h>

#include "gltrace.pb.h"
#include "gltrace_context.h"

namespace android {
namespace gltrace {

void fixupGLMessage(GLTraceContext *curContext, nsecs_t start, nsecs_t end, GLMessage *message);
void fixup_addFBContents(GLTraceContext *curContext, GLMessage *message, FBBinding fbToRead);

};
};

#endif
