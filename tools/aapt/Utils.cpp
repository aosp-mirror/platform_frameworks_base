/*
 * Copyright (C) 2005 The Android Open Source Project
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

#include "Utils.h"

#include <utils/Compat.h>

// Separator used by resource paths. This is not platform dependent contrary
// to OS_PATH_SEPARATOR.
#define RES_PATH_SEPARATOR '/'

using android::String8;

void convertToResPath([[maybe_unused]] String8& s) {
#if OS_PATH_SEPARATOR != RES_PATH_SEPARATOR
    size_t len = s.length();
    if (len > 0) {
        char* buf = s.lockBuffer(len);
        for (char* end = buf + len; buf < end; ++buf) {
            if (*buf == OS_PATH_SEPARATOR) *buf = RES_PATH_SEPARATOR;
        }
        s.unlockBuffer(len);
    }
#endif
}

String8 walkPath(const String8& path, String8* outRemains) {
    const char* cp;
    const char* const str = path.c_str();
    const char* buf = str;

    cp = strchr(buf, OS_PATH_SEPARATOR);
    if (cp == buf) {
        // don't include a leading '/'.
        buf = buf + 1;
        cp = strchr(buf, OS_PATH_SEPARATOR);
    }

    if (cp == nullptr) {
        String8 res = buf != str ? String8(buf) : path;
        if (outRemains) *outRemains = String8();
        return res;
    }

    String8 res(buf, cp - buf);
    if (outRemains) *outRemains = String8(cp + 1);
    return res;
}
