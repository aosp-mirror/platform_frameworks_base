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

#include "include/HTTPBase.h"

#if CHROMIUM_AVAILABLE
#include "include/ChromiumHTTPDataSource.h"
#endif

#include "include/NuHTTPDataSource.h"

#include <cutils/properties.h>

namespace android {

HTTPBase::HTTPBase() {}

// static
sp<HTTPBase> HTTPBase::Create(uint32_t flags) {
#if CHROMIUM_AVAILABLE
    char value[PROPERTY_VALUE_MAX];
    if (!property_get("media.stagefright.use-chromium", value, NULL)
            || (strcasecmp("false", value) && strcmp("0", value))) {
        return new ChromiumHTTPDataSource(flags);
    } else
#endif
    {
        return new NuHTTPDataSource(flags);
    }
}

}  // namespace android
