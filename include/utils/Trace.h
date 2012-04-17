/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_TRACE_H
#define ANDROID_TRACE_H

#define ATRACE_TAG_NEVER    0           // The "never" tag is never enabled.
#define ATRACE_TAG_ALWAYS   (1<<0)      // The "always" tag is always enabled.
#define ATRACE_TAG_GRAPHICS (1<<1)
#define ATRACE_TAG_INPUT    (1<<2)
#define ATRACE_TAG_VIEW     (1<<3)
#define ATRACE_TAG_WEBVIEW  (1<<4)

#define ATRACE_CALL()

#define ATRACE_INT(name, value)

#define ATRACE_ENABLED() false

namespace android {

class ScopedTrace {

public:
    inline ScopedTrace(uint64_t tag, const char* name) {}
};

}; // namespace android

#endif // ANDROID_TRACE_H
