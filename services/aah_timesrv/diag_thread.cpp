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

#define LOG_TAG "aah_timesrv"
#include <utils/Log.h>

#include <fcntl.h>
#include <linux/in.h>
#include <linux/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>
#include <utils/Errors.h>
#include <utils/misc.h>

#include <aah_timesrv/local_clock.h>

#include "common_clock.h"
#include "diag_thread.h"

#define kMaxEvents 16
#define kListenPort 9876

static bool setNonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
        LOGE("Failed to set socket (%d) to non-blocking mode (errno %d)",
             fd, errno);
        return false;
    }

    return true;
}

static bool setNodelay(int fd) {
    int tmp = 1;
    if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &tmp, sizeof(tmp)) < 0) {
        LOGE("Failed to set socket (%d) to no-delay mode (errno %d)",
             fd, errno);
        return false;
    }

    return true;
}

namespace android {

DiagThread::DiagThread(CommonClock* common_clock, LocalClock* local_clock) {
    common_clock_ = common_clock;
    local_clock_ = local_clock;
    listen_fd_ = -1;
    data_fd_ = -1;
    kernel_logID_basis_known_ = false;
    discipline_log_ID_ = 0;
}

DiagThread::~DiagThread() {
}

status_t DiagThread::startWorkThread() {
    status_t res;
    stopWorkThread();
    res = run("Diag");

    if (res != OK)
        LOGE("Failed to start work thread (res = %d)", res);

    return res;
}

void DiagThread::stopWorkThread() {
    status_t res;
    res = requestExitAndWait(); // block until thread exit.
    if (res != OK)
        LOGE("Failed to stop work thread (res = %d)", res);
}

bool DiagThread::openListenSocket() {
    bool ret = false;
    int flags;
    cleanupListenSocket();

    if ((listen_fd_ = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)) < 0) {
        LOGE("Socket failed.");
        goto bailout;
    }

    // Set non-blocking operation
    if (!setNonblocking(listen_fd_))
        goto bailout;

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(kListenPort);

    if (bind(listen_fd_, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("Bind failed.");
        goto bailout;
    }

    if (listen(listen_fd_, 1) < 0) {
        LOGE("Listen failed.");
        goto bailout;
    }

    ret = true;
bailout:
    if (!ret)
        cleanupListenSocket();

    return ret;
}

void DiagThread::cleanupListenSocket() {
    if (listen_fd_ >= 0) {
        int res;

        struct linger l;
        l.l_onoff  = 1;
        l.l_linger = 0;

        setsockopt(listen_fd_, SOL_SOCKET, SO_LINGER, &l, sizeof(l));
        shutdown(listen_fd_, SHUT_RDWR);
        close(listen_fd_);
        listen_fd_ = -1;
    }
}

void DiagThread::cleanupDataSocket() {
    if (data_fd_ >= 0) {
        int res;

        struct linger l;
        l.l_onoff  = 1;
        l.l_linger = 0;

        setsockopt(data_fd_, SOL_SOCKET, SO_LINGER, &l, sizeof(l));
        shutdown(data_fd_, SHUT_RDWR);
        close(data_fd_);
        data_fd_ = -1;
    }
}

void DiagThread::resetLogIDs() {
    // Drain and discard all of the events from the kernel
    struct local_time_debug_event events[kMaxEvents];
    while(local_clock_->getDebugLog(events, kMaxEvents) > 0)
        ;

    {
        Mutex::Autolock lock(&discipline_log_lock_);
        discipline_log_.clear();
        discipline_log_ID_ = 0;
    }

    kernel_logID_basis_known_ = false;
}

void DiagThread::pushDisciplineEvent(int64_t observed_local_time,
                                     int64_t observed_common_time,
                                     int64_t nominal_common_time,
                                     int32_t total_correction,
                                     int32_t P_correction,
                                     int32_t I_correction,
                                     int32_t D_correction) {
    Mutex::Autolock lock(&discipline_log_lock_);

    DisciplineEventRecord evt;

    evt.event_id = discipline_log_ID_++;

    evt.action_local_time = local_clock_->getLocalTime();
    common_clock_->localToCommon(evt.action_local_time,
            &evt.action_common_time);

    evt.observed_local_time  = observed_local_time;
    evt.observed_common_time = observed_common_time;
    evt.nominal_common_time  = nominal_common_time;
    evt.total_correction     = total_correction;
    evt.P_correction         = P_correction;
    evt.I_correction         = I_correction;
    evt.D_correction         = D_correction;

    discipline_log_.push_back(evt);
    while (discipline_log_.size() > kMaxDisciplineLogSize)
        discipline_log_.erase(discipline_log_.begin());
}

bool DiagThread::threadLoop() {
    struct pollfd poll_fds[1];

    if (!openListenSocket()) {
        LOGE("Failed to open listen socket");
        goto bailout;
    }

    while (!exitPending()) {
        memset(&poll_fds, 0, sizeof(poll_fds));

        if (data_fd_ < 0) {
            poll_fds[0].fd     = listen_fd_;
            poll_fds[0].events = POLLIN;
        } else {
            poll_fds[0].fd     = data_fd_;
            poll_fds[0].events = POLLRDHUP | POLLIN;
        }

        int poll_res = poll(poll_fds, NELEM(poll_fds), 50);
        if (poll_res < 0) {
            LOGE("Fatal error (%d,%d) while waiting on events",
                 poll_res, errno);
            goto bailout;
        }

        if (exitPending())
            break;

        if (poll_fds[0].revents) {
            if (poll_fds[0].fd == listen_fd_) {
                data_fd_ = accept(listen_fd_, NULL, NULL);

                if (data_fd_ < 0) {
                    LOGW("Failed accept on socket %d with err %d",
                         listen_fd_, errno);
                } else {
                    if (!setNonblocking(data_fd_))
                        cleanupDataSocket();
                    if (!setNodelay(data_fd_))
                        cleanupDataSocket();
                }
            } else
                if (poll_fds[0].fd == data_fd_) {
                    if (poll_fds[0].revents & POLLRDHUP) {
                        // Connection hung up; time to clean up.
                        cleanupDataSocket();
                    } else
                        if (poll_fds[0].revents & POLLIN) {
                            uint8_t cmd;
                            if (read(data_fd_, &cmd, sizeof(cmd)) > 0) {
                                switch(cmd) {
                                    case 'r':
                                    case 'R':
                                        resetLogIDs();
                                        break;
                                }
                            }
                        }
                }
        }

        struct local_time_debug_event events[kMaxEvents];
        int amt = local_clock_->getDebugLog(events, kMaxEvents);

        if (amt > 0) {
            for (int i = 0; i < amt; i++) {
                struct local_time_debug_event& e = events[i];

                if (!kernel_logID_basis_known_) {
                    kernel_logID_basis_ = e.local_timesync_event_id;
                    kernel_logID_basis_known_ = true;
                }

                char buf[1024];
                int64_t common_time;
                status_t res = common_clock_->localToCommon(e.local_time,
                                                            &common_time);
                snprintf(buf, sizeof(buf), "E,%lld,%lld,%lld,%d\n",
                         e.local_timesync_event_id - kernel_logID_basis_,
                         e.local_time,
                         common_time,
                         (OK == res) ? 1 : 0);
                buf[sizeof(buf) - 1] = 0;

                if (data_fd_ >= 0)
                    write(data_fd_, buf, strlen(buf));
            }
        }

        { // scope for autolock pattern
            Mutex::Autolock lock(&discipline_log_lock_);

            while (discipline_log_.size() > 0) {
                char buf[1024];
                DisciplineEventRecord& e = *discipline_log_.begin();
                snprintf(buf, sizeof(buf),
                         "D,%lld,%lld,%lld,%lld,%lld,%lld,%d,%d,%d,%d\n",
                         e.event_id,
                         e.action_local_time,
                         e.action_common_time,
                         e.observed_local_time,
                         e.observed_common_time,
                         e.nominal_common_time,
                         e.total_correction,
                         e.P_correction,
                         e.I_correction,
                         e.D_correction);
                buf[sizeof(buf) - 1] = 0;

                if (data_fd_ >= 0)
                    write(data_fd_, buf, strlen(buf));

                discipline_log_.erase(discipline_log_.begin());
            }
        }
    }

bailout:
    cleanupDataSocket();
    cleanupListenSocket();
    return false;
}

}  // namespace android
