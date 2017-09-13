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

#ifndef IO_UTIL_H
#define IO_UTIL_H

#include <stdint.h>
#include <utils/Errors.h>

using namespace android;

status_t write_all(int fd, uint8_t const* buf, size_t size);

class Fpipe {
public:
    Fpipe();
    ~Fpipe();

    bool init();
    bool close();
    int readFd() const;
    int writeFd() const;

private:
    int mFds[2];
};

#endif // IO_UTIL_H