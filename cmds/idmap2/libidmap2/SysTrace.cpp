/*
 * Copyright (C) 2022 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_RRO

#include "idmap2/SysTrace.h"

#ifdef __ANDROID__
namespace android::idmap2::utils {

ScopedTraceNoStart::~ScopedTraceNoStart() {
    ATRACE_END();
};

ScopedTraceMessageHelper::~ScopedTraceMessageHelper() {
    ATRACE_BEGIN(buffer_.str().c_str());
}

bool atrace_enabled() {
    return ATRACE_ENABLED();
}
}  // namespace android::idmap2::utils
#endif
