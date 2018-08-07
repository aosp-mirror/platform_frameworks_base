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
#include <unistd.h>

#include <android-base/unique_fd.h>
#include <utils/Errors.h>

#include "Privacy.h"

namespace android {
namespace os {
namespace incidentd {

using namespace android::base;

/**
 * Looks up Privacy of a section in the auto-gen PRIVACY_POLICY_LIST;
 */
const Privacy* get_privacy_of_section(int id);

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
 * Forks and exec a command with two pipes, one connects stdin for input,
 * one connects stdout for output. It returns the pid of the child.
 * Input pipe can be NULL to indicate child process doesn't read stdin.
 */
pid_t fork_execute_cmd(char* const argv[], Fpipe* input, Fpipe* output);

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
status_t wait_child(pid_t pid);

}  // namespace incidentd
}  // namespace os
}  // namespace android

#endif  // INCIDENTD_UTIL_H
