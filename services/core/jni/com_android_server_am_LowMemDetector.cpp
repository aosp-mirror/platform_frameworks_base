/**
** Copyright 2019, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "LowMemDetector"

#include <errno.h>
#include <psi/psi.h>
#include <string.h>
#include <sys/epoll.h>

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

namespace android {

enum pressure_levels {
    PRESSURE_NONE,
    PRESSURE_LOW,
    PRESSURE_MEDIUM,
    PRESSURE_HIGH,
    PRESSURE_LEVEL_COUNT = PRESSURE_HIGH
};

// amount of stall in us for each level
static constexpr int PSI_LOW_STALL_US = 15000;
static constexpr int PSI_MEDIUM_STALL_US = 30000;
static constexpr int PSI_HIGH_STALL_US = 50000;

// stall tracking window size in us
static constexpr int PSI_WINDOW_SIZE_US = 1000000;

static int psi_epollfd = -1;

static jint android_server_am_LowMemDetector_init(JNIEnv*, jobject) {
    int epollfd;
    int low_psi_fd;
    int medium_psi_fd;
    int high_psi_fd;

    epollfd = epoll_create(PRESSURE_LEVEL_COUNT);
    if (epollfd == -1) {
        ALOGE("epoll_create failed: %s", strerror(errno));
        return -1;
    }

    low_psi_fd = init_psi_monitor(PSI_SOME, PSI_LOW_STALL_US, PSI_WINDOW_SIZE_US);
    if (low_psi_fd < 0 ||
        register_psi_monitor(epollfd, low_psi_fd, (void*)PRESSURE_LOW) != 0) {
        goto low_fail;
    }

    medium_psi_fd =
        init_psi_monitor(PSI_FULL, PSI_MEDIUM_STALL_US, PSI_WINDOW_SIZE_US);
    if (medium_psi_fd < 0 || register_psi_monitor(epollfd, medium_psi_fd,
                                                  (void*)PRESSURE_MEDIUM) != 0) {
        goto medium_fail;
    }

    high_psi_fd =
        init_psi_monitor(PSI_FULL, PSI_HIGH_STALL_US, PSI_WINDOW_SIZE_US);
    if (high_psi_fd < 0 ||
        register_psi_monitor(epollfd, high_psi_fd, (void*)PRESSURE_HIGH) != 0) {
        goto high_fail;
    }

    psi_epollfd = epollfd;
    return 0;

high_fail:
    unregister_psi_monitor(epollfd, medium_psi_fd);
medium_fail:
    unregister_psi_monitor(epollfd, low_psi_fd);
low_fail:
    ALOGE("Failed to register psi trigger");
    close(epollfd);
    return -1;
}

static jint android_server_am_LowMemDetector_waitForPressure(JNIEnv*, jobject) {
    static uint32_t pressure_level = PRESSURE_NONE;
    struct epoll_event events[PRESSURE_LEVEL_COUNT];
    int nevents = 0;

    if (psi_epollfd < 0) {
        ALOGE("Memory pressure detector is not initialized");
        return -1;
    }

    do {
        if (pressure_level == PRESSURE_NONE) {
            /* Wait for events with no timeout */
            nevents = epoll_wait(psi_epollfd, events, PRESSURE_LEVEL_COUNT, -1);
        } else {
            // This is simpler than lmkd. Assume that the memory pressure
            // state will stay high for at least 1s. Within that 1s window,
            // the memory pressure state can go up due to a different FD
            // becoming available or it can go down when that window expires.
            // Accordingly, there's no polling: just epoll_wait with a 1s timeout.
            nevents = epoll_wait(psi_epollfd, events, PRESSURE_LEVEL_COUNT, 1000);
            if (nevents == 0) {
                pressure_level = PRESSURE_NONE;
                return pressure_level;
            }
        }
        // keep waiting if interrupted
    } while (nevents == -1 && errno == EINTR);

    if (nevents == -1) {
        ALOGE("epoll_wait failed while waiting for psi events: %s", strerror(errno));
        return -1;
    }

    // reset pressure_level and raise it based on received events
    pressure_level = PRESSURE_NONE;
    for (int i = 0; i < nevents; i++) {
        if (events[i].events & (EPOLLERR | EPOLLHUP)) {
            // should never happen unless psi got disabled in kernel
            ALOGE("Memory pressure events are not available anymore");
            return -1;
        }
        // record the highest reported level
        if (events[i].data.u32 > pressure_level) {
            pressure_level = events[i].data.u32;
        }
    }

    return pressure_level;
}

static const JNINativeMethod sMethods[] = {
    /* name, signature, funcPtr */
    {"init", "()I", (void*)android_server_am_LowMemDetector_init},
    {"waitForPressure", "()I",
     (void*)android_server_am_LowMemDetector_waitForPressure},
};

int register_android_server_am_LowMemDetector(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/am/LowMemDetector",
                                    sMethods, NELEM(sMethods));
}

} // namespace android
