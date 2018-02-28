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

#include "section_list.h"

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

int Fpipe::readFd() const { return mRead.get(); }

int Fpipe::writeFd() const { return mWrite.get(); }

pid_t fork_execute_cmd(const char* cmd, char* const argv[], Fpipe* input, Fpipe* output) {
    // fork used in multithreaded environment, avoid adding unnecessary code in child process
    pid_t pid = fork();
    if (pid == 0) {
        if (TEMP_FAILURE_RETRY(dup2(input->readFd(), STDIN_FILENO)) < 0 || !input->close() ||
            TEMP_FAILURE_RETRY(dup2(output->writeFd(), STDOUT_FILENO)) < 0 || !output->close()) {
            ALOGW("Can't setup stdin and stdout for command %s", cmd);
            _exit(EXIT_FAILURE);
        }

        /* make sure the child dies when incidentd dies */
        prctl(PR_SET_PDEATHSIG, SIGKILL);

        execv(cmd, argv);

        ALOGW("%s failed in the child process: %s", cmd, strerror(errno));
        _exit(EXIT_FAILURE);  // always exits with failure if any
    }
    // close the fds used in child process.
    close(input->readFd());
    close(output->writeFd());
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
    for (int i = 0; i < numOfArgs; i++) {
        const char* arg = va_arg(rest, const char*);
        ret[i + 1] = arg;
    }
    ret[numOfArgs + 1] = NULL;
    return ret;
}
