/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef HTTP_BASE_H_

#define HTTP_BASE_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/DataSource.h>

namespace android {

struct HTTPBase : public DataSource {
    enum Flags {
        // Don't log any URLs.
        kFlagIncognito = 1
    };

    HTTPBase();

    virtual status_t connect(
            const char *uri,
            const KeyedVector<String8, String8> *headers = NULL,
            off64_t offset = 0) = 0;

    virtual void disconnect() = 0;

    // Returns true if bandwidth could successfully be estimated,
    // false otherwise.
    virtual bool estimateBandwidth(int32_t *bandwidth_bps) = 0;

    static sp<HTTPBase> Create(uint32_t flags = 0);

private:
    DISALLOW_EVIL_CONSTRUCTORS(HTTPBase);
};

}  // namespace android

#endif  // HTTP_BASE_H_
