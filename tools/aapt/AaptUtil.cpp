/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include "AaptUtil.h"

using android::Vector;
using android::String8;

namespace AaptUtil {

Vector<String8> split(const String8& str, const char sep) {
    Vector<String8> parts;
    const char* p = str.c_str();
    const char* q;

    while (true) {
        q = strchr(p, sep);
        if (q == NULL) {
            parts.add(String8(p, strlen(p)));
            return parts;
        }

        parts.add(String8(p, q-p));
        p = q + 1;
    }
    return parts;
}

Vector<String8> splitAndLowerCase(const String8& str, const char sep) {
    Vector<String8> parts;
    const char* p = str.c_str();
    const char* q;

    while (true) {
        q = strchr(p, sep);
        if (q == NULL) {
            String8 val(p, strlen(p));
            val.toLower();
            parts.add(val);
            return parts;
        }

        String8 val(p, q-p);
        val.toLower();
        parts.add(val);
        p = q + 1;
    }
    return parts;
}

} // namespace AaptUtil
