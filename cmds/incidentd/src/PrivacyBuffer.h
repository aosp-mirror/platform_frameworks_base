/*
 * Copyright (C) 2017 The Android Open Source Project
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
#pragma once

#ifndef PRIVACY_BUFFER_H
#define PRIVACY_BUFFER_H

#include "Privacy.h"

#include <android/util/EncodedBuffer.h>
#include <android/util/ProtoOutputStream.h>
#include <stdint.h>
#include <utils/Errors.h>

namespace android {
namespace os {
namespace incidentd {

using namespace android::util;

/**
 * PrivacyBuffer holds the original protobuf data and strips PII-sensitive fields
 * based on the request and holds stripped data in its own buffer for output.
 */
class PrivacyBuffer {
public:
    PrivacyBuffer(const Privacy* policy, EncodedBuffer::iterator data);
    ~PrivacyBuffer();

    /**
     * Strip based on the request and hold data in its own buffer. Return NO_ERROR if strip
     * succeeds.
     */
    status_t strip(const PrivacySpec& spec);

    /**
     * Clear encoded buffer so it can be reused by another request.
     */
    void clear();

    /**
     * Return the size of the stripped data.
     */
    size_t size() const;

    /**
     * Flush buffer to the given fd. NO_ERROR is returned if the flush succeeds.
     */
    status_t flush(int fd);

private:
    const Privacy* mPolicy;
    EncodedBuffer::iterator mData;

    ProtoOutputStream mProto;
    size_t mSize;

    status_t stripField(const Privacy* parentPolicy, const PrivacySpec& spec, int depth);
    void writeFieldOrSkip(uint32_t fieldTag, bool skip);
};

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // PRIVACY_BUFFER_H