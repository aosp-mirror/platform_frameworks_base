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
#define DEBUG false
#include "Log.h"

#include "incidentd_util.h"

#include <sys/prctl.h>
#include <wait.h>

#include "section_list.h"

namespace android {
namespace os {
namespace incidentd {

using namespace android::base;

const Privacy* get_privacy_of_section(int id) {
    int l = 0;
    int r = PRIVACY_POLICY_COUNT - 1;
    while (l <= r) {
        int mid = (l + r) >> 1;
        const Privacy* p = PRIVACY_POLICY_LIST[mid];

        if (p->field_id < (uint32_t)id) {
            l = mid + 1;
        } else if (p->field_id > (uint32_t)id) {
            r = mid - 1;
        } else {
            return p;
        }
    }
    return NULL;
}

// ================================================================================
Fpipe::Fpipe() : mRead(), mWrite() {}

Fpipe::~Fpipe() { close(); }

bool Fpipe::close() {
    mRead.reset();
    mWrite.reset();
    return true;
}

bool Fpipe::init() { return Pipe(&mRead, &mWrite); }

unique_fd& Fpipe::readFd() { return mRead; }

unique_fd& Fpipe::writeFd() { return mWrite; }

pid_t fork_execute_cmd(char* const argv[], Fpipe* input, Fpipe* output) {
    // fork used in multithreaded environment, avoid adding unnecessary code in child process
    pid_t pid = fork();
    if (pid == 0) {
        if (input != NULL && (TEMP_FAILURE_RETRY(dup2(input->readFd().get(), STDIN_FILENO)) < 0 ||
                              !input->close())) {
            ALOGW("Failed to dup2 stdin.");
            _exit(EXIT_FAILURE);
        }
        if (TEMP_FAILURE_RETRY(dup2(output->writeFd().get(), STDOUT_FILENO)) < 0 ||
            !output->close()) {
            ALOGW("Failed to dup2 stdout.");
            _exit(EXIT_FAILURE);
        }
        /* make sure the child dies when incidentd dies */
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        execvp(argv[0], argv);
        _exit(errno);  // always exits with failure if any
    }
    // close the fds used in child process.
    if (input != NULL) input->readFd().reset();
    output->writeFd().reset();
    return pid;
}

// ================================================================================
const char** varargs(const char* first, va_list rest) {
    va_list copied_rest;
    int numOfArgs = 1;  // first is already count.

    va_copy(copied_rest, rest);
    while (va_arg(copied_rest, const char*) != NULL) {
        numOfArgs++;
    }
    va_end(copied_rest);

    // allocate extra 1 for NULL terminator
    const char** ret = (const char**)malloc(sizeof(const char*) * (numOfArgs + 1));
    ret[0] = first;
    for (int i = 1; i < numOfArgs; i++) {
        const char* arg = va_arg(rest, const char*);
        ret[i] = arg;
    }
    ret[numOfArgs] = NULL;
    return ret;
}

// ================================================================================
const uint64_t NANOS_PER_SEC = 1000000000;
uint64_t Nanotime() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec * NANOS_PER_SEC + ts.tv_nsec);
}

// ================================================================================
const int WAIT_MAX = 5;
const struct timespec WAIT_INTERVAL_NS = {0, 200 * 1000 * 1000};

static status_t statusCode(int status) {
    if (WIFSIGNALED(status)) {
        VLOG("return by signal: %s", strerror(WTERMSIG(status)));
        return -WTERMSIG(status);
    } else if (WIFEXITED(status) && WEXITSTATUS(status) > 0) {
        VLOG("return by exit: %s", strerror(WEXITSTATUS(status)));
        return -WEXITSTATUS(status);
    }
    return NO_ERROR;
}

status_t kill_child(pid_t pid) {
    int status;
    VLOG("try to kill child process %d", pid);
    kill(pid, SIGKILL);
    if (waitpid(pid, &status, 0) == -1) return -1;
    return statusCode(status);
}

status_t wait_child(pid_t pid) {
    int status;
    bool died = false;
    // wait for child to report status up to 1 seconds
    for (int loop = 0; !died && loop < WAIT_MAX; loop++) {
        if (waitpid(pid, &status, WNOHANG) == pid) died = true;
        // sleep for 0.2 second
        nanosleep(&WAIT_INTERVAL_NS, NULL);
    }
    if (!died) return kill_child(pid);
    return statusCode(status);
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
