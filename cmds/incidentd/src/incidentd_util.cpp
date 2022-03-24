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

#include <android/util/EncodedBuffer.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <wait.h>

#include "section_list.h"

namespace android {
namespace os {
namespace incidentd {

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

std::vector<sp<EncodedBuffer>> gBufferPool;
std::mutex gBufferPoolLock;

sp<EncodedBuffer> get_buffer_from_pool() {
    std::scoped_lock<std::mutex> lock(gBufferPoolLock);
    if (gBufferPool.size() == 0) {
        return new EncodedBuffer();
    }
    sp<EncodedBuffer> buffer = gBufferPool.back();
    gBufferPool.pop_back();
    return buffer;
}

void return_buffer_to_pool(sp<EncodedBuffer> buffer) {
    buffer->clear();
    std::scoped_lock<std::mutex> lock(gBufferPoolLock);
    gBufferPool.push_back(buffer);
}

void clear_buffer_pool() {
    std::scoped_lock<std::mutex> lock(gBufferPoolLock);
    gBufferPool.clear();
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

pid_t fork_execute_cmd(char* const argv[], Fpipe* input, Fpipe* output, int* status) {
    int in = -1;
    if (input != nullptr) {
        in = input->readFd().release();
        // Auto close write end of the input pipe on exec to prevent leaking fd in child process
        fcntl(input->writeFd().get(), F_SETFD, FD_CLOEXEC);
    }
    int out = output->writeFd().release();
    // Auto close read end of the output pipe on exec
    fcntl(output->readFd().get(), F_SETFD, FD_CLOEXEC);
    return fork_execute_cmd(argv, in, out, status);
}

pid_t fork_execute_cmd(char* const argv[], int in, int out, int* status) {
    int dummy_status = 0;
    if (status == nullptr) {
        status = &dummy_status;
    }
    *status = 0;
    pid_t pid = vfork();
    if (pid < 0) {
        *status = -errno;
        return -1;
    }
    if (pid == 0) {
        // In child
        if (in >= 0 && (TEMP_FAILURE_RETRY(dup2(in, STDIN_FILENO)) < 0 || close(in))) {
            ALOGW("Failed to dup2 stdin.");
            _exit(EXIT_FAILURE);
        }
        if (TEMP_FAILURE_RETRY(dup2(out, STDOUT_FILENO)) < 0 || close(out)) {
            ALOGW("Failed to dup2 stdout.");
            _exit(EXIT_FAILURE);
        }
        // Make sure the child dies when incidentd dies
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        execvp(argv[0], argv);
        _exit(errno);  // always exits with failure if any
    }
    // In parent
    if ((in >= 0 && close(in) < 0) || close(out) < 0) {
        ALOGW("Failed to close pd. Killing child process");
        *status = -errno;
        kill_child(pid);
        return -1;
    }
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

static bool waitpid_with_timeout(pid_t pid, int timeout_ms, int* status) {
    sigset_t child_mask, old_mask;
    sigemptyset(&child_mask);
    sigaddset(&child_mask, SIGCHLD);

    // block SIGCHLD before we check if a process has exited
    if (sigprocmask(SIG_BLOCK, &child_mask, &old_mask) == -1) {
        ALOGW("*** sigprocmask failed: %s\n", strerror(errno));
        return false;
    }

    // if the child has exited already, handle and reset signals before leaving
    pid_t child_pid = waitpid(pid, status, WNOHANG);
    if (child_pid != pid) {
        if (child_pid > 0) {
            ALOGW("*** Waiting for pid %d, got pid %d instead\n", pid, child_pid);
            sigprocmask(SIG_SETMASK, &old_mask, nullptr);
            return false;
        }
    } else {
        sigprocmask(SIG_SETMASK, &old_mask, nullptr);
        return true;
    }

    // wait for a SIGCHLD
    timespec ts;
    ts.tv_sec = timeout_ms / 1000;
    ts.tv_nsec = (timeout_ms % 1000) * 1000000;
    int ret = TEMP_FAILURE_RETRY(sigtimedwait(&child_mask, nullptr, &ts));
    int saved_errno = errno;

    // Set the signals back the way they were.
    if (sigprocmask(SIG_SETMASK, &old_mask, nullptr) == -1) {
        ALOGW("*** sigprocmask failed: %s\n", strerror(errno));
        if (ret == 0) {
            return false;
        }
    }
    if (ret == -1) {
        errno = saved_errno;
        if (errno == EAGAIN) {
            errno = ETIMEDOUT;
        } else {
            ALOGW("*** sigtimedwait failed: %s\n", strerror(errno));
        }
        return false;
    }

    child_pid = waitpid(pid, status, WNOHANG);
    if (child_pid != pid) {
        if (child_pid != -1) {
            ALOGW("*** Waiting for pid %d, got pid %d instead\n", pid, child_pid);
        } else {
            ALOGW("*** waitpid failed: %s\n", strerror(errno));
        }
        return false;
    }
    return true;
}

status_t kill_child(pid_t pid) {
    int status;
    kill(pid, SIGKILL);
    if (waitpid(pid, &status, 0) == -1) return -1;
    return statusCode(status);
}

status_t wait_child(pid_t pid, int timeout_ms) {
    int status;
    if (waitpid_with_timeout(pid, timeout_ms, &status)) {
        return statusCode(status);
    }
    return kill_child(pid);
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
