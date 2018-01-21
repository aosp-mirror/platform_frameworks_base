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

#define LOG_TAG "incidentd"

#include "io_util.h"

#include <unistd.h>

status_t write_all(int fd, uint8_t const* buf, size_t size)
{
    while (size > 0) {
        ssize_t amt = TEMP_FAILURE_RETRY(::write(fd, buf, size));
        if (amt < 0) {
            return -errno;
        }
        size -= amt;
        buf += amt;
    }
    return NO_ERROR;
}

Fpipe::Fpipe() {}

Fpipe::~Fpipe() { close(); }

bool Fpipe::close() { return !(::close(mFds[0]) || ::close(mFds[1])); }

bool Fpipe::init() { return pipe(mFds) != -1; }

int Fpipe::readFd() const { return mFds[0]; }

int Fpipe::writeFd() const { return mFds[1]; }
