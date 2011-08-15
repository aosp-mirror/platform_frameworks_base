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

#define LOG_TAG "LibAAH_RTP"
#include <utils/Log.h>

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>

#include "pipe_event.h"

namespace android {

PipeEvent::PipeEvent() {
    pipe_[0] = -1;
    pipe_[1] = -1;

    // Create the pipe.
    if (pipe(pipe_) >= 0) {
        // Set non-blocking mode on the read side of the pipe so we can
        // easily drain it whenever we wakeup.
        fcntl(pipe_[0], F_SETFL, O_NONBLOCK);
    } else {
        LOGE("Failed to create pipe event %d %d %d",
             pipe_[0], pipe_[1], errno);
        pipe_[0] = -1;
        pipe_[1] = -1;
    }
}

PipeEvent::~PipeEvent() {
    if (pipe_[0] >= 0) {
        close(pipe_[0]);
    }

    if (pipe_[1] >= 0) {
        close(pipe_[1]);
    }
}

void PipeEvent::clearPendingEvents() {
    char drain_buffer[16];
    while (read(pipe_[0], drain_buffer, sizeof(drain_buffer)) > 0) {
        // No body.
    }
}

bool PipeEvent::wait(int timeout) {
    struct pollfd wait_fd;

    wait_fd.fd = getWakeupHandle();
    wait_fd.events = POLLIN;
    wait_fd.revents = 0;

    int res = poll(&wait_fd, 1, timeout);

    if (res < 0) {
        LOGE("Wait error in PipeEvent; sleeping to prevent overload!");
        usleep(1000);
    }

    return (res > 0);
}

void PipeEvent::setEvent() {
    char foo = 'q';
    write(pipe_[1], &foo, 1);
}

}  // namespace android

