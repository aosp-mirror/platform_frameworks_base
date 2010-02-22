/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_UTILS_FLATTENABLE_H
#define ANDROID_UTILS_FLATTENABLE_H


#include <stdint.h>
#include <sys/types.h>
#include <utils/Errors.h>

namespace android {

class Flattenable
{
public:
    // size in bytes of the flattened object
    virtual size_t getFlattenedSize() const = 0;

    // number of file descriptors to flatten
    virtual size_t getFdCount() const = 0;

    // flattens the object into buffer.
    // size should be at least of getFlattenedSize()
    // file descriptors are written in the fds[] array but ownership is
    // not transfered (ie: they must be dupped by the caller of
    // flatten() if needed).
    virtual status_t flatten(void* buffer, size_t size,
            int fds[], size_t count) const = 0;

    // unflattens the object from buffer.
    // size should be equal to the value of getFlattenedSize() when the
    // object was flattened.
    // unflattened file descriptors are found in the fds[] array and
    // don't need to be dupped(). ie: the caller of unflatten doesn't
    // keep ownership. If a fd is not retained by unflatten() it must be
    // explicitly closed.
    virtual status_t unflatten(void const* buffer, size_t size,
            int fds[], size_t count) = 0;

protected:
    virtual ~Flattenable() = 0;

};

}; // namespace android


#endif /* ANDROID_UTILS_FLATTENABLE_H */
