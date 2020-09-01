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

#ifndef INCIDENTD_UTIL_H
#define INCIDENTD_UTIL_H

#include <stdarg.h>
#include <utils/Errors.h>

#include "Privacy.h"

namespace android {

namespace util {
class EncodedBuffer;
}

namespace os {
namespace incidentd {

using android::base::unique_fd;
using android::util::EncodedBuffer;

/**
 * Looks up Privacy of a section in the auto-gen PRIVACY_POLICY_LIST;
 */
const Privacy* get_privacy_of_section(int id);

/**
 * Get an EncodedBuffer from an internal pool, or create and return a new one if the pool is empty.
 * The EncodedBuffer should be returned after use.
 * Thread safe.
 */
sp<EncodedBuffer> get_buffer_from_pool();

/**
 * Return the EncodedBuffer back to the pool for reuse.
 * Thread safe.
 */
void return_buffer_to_pool(sp<EncodedBuffer> buffer);

/**
 * Clear the buffer pool to free memory, after taking an incident report.
 * Thread safe.
 */
void clear_buffer_pool();

/**
 * This class wraps android::base::Pipe.
 */
class Fpipe {
public:
    Fpipe();
    ~Fpipe();

    bool init();
    bool close();
    unique_fd& readFd();
    unique_fd& writeFd();

private:
    unique_fd mRead;
    unique_fd mWrite;
};

/**
 * Forks and exec a command with two pipes and returns the pid of the child, or -1 when it fails.
 *
 * input connects stdin for input. output connects stdout for output. input can be nullptr to
 * indicate that child process doesn't read stdin. This function will close in and out fds upon
 * success. If status is not NULL, the status information will be stored in the int to which it
 * points.
 */
pid_t fork_execute_cmd(char* const argv[], Fpipe* input, Fpipe* output, int* status = nullptr);

/**
 * Forks and exec a command that reads from in fd and writes to out fd and returns the pid of the
 * child, or -1 when it fails.
 *
 * in can be -1 to indicate that child process doesn't read stdin. This function will close in and
 * out fds upon success. If status is not NULL, the status information will be stored in the int
 * to which it points.
 */
pid_t fork_execute_cmd(char* const argv[], int in, int out, int* status = nullptr);

/**
 * Grabs varargs from stack and stores them in heap with NULL-terminated array.
 */
const char** varargs(const char* first, va_list rest);

/**
 * Returns the current monotonic clock time in nanoseconds.
 */
uint64_t Nanotime();

/**
 * Methods to wait or kill child process, return exit status code.
 */
status_t kill_child(pid_t pid);
status_t wait_child(pid_t pid, int timeout_ms = 1000);

status_t start_detached_thread(const function<void ()>& func);

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // INCIDENTD_UTIL_H
