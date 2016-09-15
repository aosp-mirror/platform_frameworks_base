/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "io/Io.h"

#include <algorithm>
#include <cstring>

namespace aapt {
namespace io {

bool copy(OutputStream* out, InputStream* in) {
    const void* inBuffer;
    int inLen;
    while (in->Next(&inBuffer, &inLen)) {
        void* outBuffer;
        int outLen;
        if (!out->Next(&outBuffer, &outLen)) {
            return !out->HadError();
        }

        const int bytesToCopy = std::min(inLen, outLen);
        memcpy(outBuffer, inBuffer, bytesToCopy);
        out->BackUp(outLen - bytesToCopy);
        in->BackUp(inLen - bytesToCopy);
    }
    return !in->HadError();
}

} // namespace io
} // namespace aapt
