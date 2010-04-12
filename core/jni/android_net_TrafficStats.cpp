/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "TrafficStats"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <android_runtime/AndroidRuntime.h>
#include <cutils/logger.h>
#include <jni.h>
#include <utils/misc.h>
#include <utils/Log.h>

namespace android {

// Returns an ASCII decimal number read from the specified file, -1 on error.
static jlong readNumber(char const* filename) {
#ifdef HAVE_ANDROID_OS
    char buf[80];
    int fd = open(filename, O_RDONLY);
    if (fd < 0) {
        if (errno != ENOENT) LOGE("Can't open %s: %s", filename, strerror(errno));
        return -1;
    }

    int len = read(fd, buf, sizeof(buf) - 1);
    if (len < 0) {
        LOGE("Can't read %s: %s", filename, strerror(errno));
        close(fd);
        return -1;
    }

    close(fd);
    buf[len] = '\0';
    return atoll(buf);
#else  // Simulator
    return -1;
#endif
}

// Return the number from the first file which exists and contains data
static jlong tryBoth(char const* a, char const* b) {
    jlong num = readNumber(a);
    return num >= 0 ? num : readNumber(b);
}

// Returns the sum of numbers from the specified path under /sys/class/net/*,
// -1 if no such file exists.
static jlong readTotal(char const* suffix) {
#ifdef HAVE_ANDROID_OS
    char filename[PATH_MAX] = "/sys/class/net/";
    DIR *dir = opendir(filename);
    if (dir == NULL) {
        LOGE("Can't list %s: %s", filename, strerror(errno));
        return -1;
    }

    int len = strlen(filename);
    jlong total = -1;
    while (struct dirent *entry = readdir(dir)) {
        // Skip ., .., and localhost interfaces.
        if (entry->d_name[0] != '.' && strncmp(entry->d_name, "lo", 2) != 0) {
            strlcpy(filename + len, entry->d_name, sizeof(filename) - len);
            strlcat(filename, suffix, sizeof(filename));
            jlong num = readNumber(filename);
            if (num >= 0) total = total < 0 ? num : total + num;
        }
    }

    closedir(dir);
    return total;
#else  // Simulator
    return -1;
#endif
}

// Mobile stats get accessed a lot more often than total stats.
// Note the individual files can come and go at runtime, so we check
// each file every time (rather than caching which ones exist).

static jlong getMobileTxPackets(JNIEnv* env, jobject clazz) {
    return tryBoth(
            "/sys/class/net/rmnet0/statistics/tx_packets",
            "/sys/class/net/ppp0/statistics/tx_packets");
}

static jlong getMobileRxPackets(JNIEnv* env, jobject clazz) {
    return tryBoth(
            "/sys/class/net/rmnet0/statistics/rx_packets",
            "/sys/class/net/ppp0/statistics/rx_packets");
}

static jlong getMobileTxBytes(JNIEnv* env, jobject clazz) {
    return tryBoth(
            "/sys/class/net/rmnet0/statistics/tx_bytes",
            "/sys/class/net/ppp0/statistics/tx_bytes");
}

static jlong getMobileRxBytes(JNIEnv* env, jobject clazz) {
    return tryBoth(
            "/sys/class/net/rmnet0/statistics/rx_bytes",
            "/sys/class/net/ppp0/statistics/rx_bytes");
}

// Total stats are read less often, so we're willing to put up
// with listing the directory and concatenating filenames.

static jlong getTotalTxPackets(JNIEnv* env, jobject clazz) {
    return readTotal("/statistics/tx_packets");
}

static jlong getTotalRxPackets(JNIEnv* env, jobject clazz) {
    return readTotal("/statistics/rx_packets");
}

static jlong getTotalTxBytes(JNIEnv* env, jobject clazz) {
    return readTotal("/statistics/tx_bytes");
}

static jlong getTotalRxBytes(JNIEnv* env, jobject clazz) {
    return readTotal("/statistics/rx_bytes");
}

// Per-UID stats require reading from a constructed filename.

static jlong getUidRxBytes(JNIEnv* env, jobject clazz, jint uid) {
    char filename[80];
    sprintf(filename, "/proc/uid_stat/%d/tcp_rcv", uid);
    return readNumber(filename);
}

static jlong getUidTxBytes(JNIEnv* env, jobject clazz, jint uid) {
    char filename[80];
    sprintf(filename, "/proc/uid_stat/%d/tcp_snd", uid);
    return readNumber(filename);
}

static JNINativeMethod gMethods[] = {
    {"getMobileTxPackets", "()J", (void*) getMobileTxPackets},
    {"getMobileRxPackets", "()J", (void*) getMobileRxPackets},
    {"getMobileTxBytes", "()J", (void*) getMobileTxBytes},
    {"getMobileRxBytes", "()J", (void*) getMobileRxBytes},
    {"getTotalTxPackets", "()J", (void*) getTotalTxPackets},
    {"getTotalRxPackets", "()J", (void*) getTotalRxPackets},
    {"getTotalTxBytes", "()J", (void*) getTotalTxBytes},
    {"getTotalRxBytes", "()J", (void*) getTotalRxBytes},
    {"getUidTxBytes", "(I)J", (void*) getUidTxBytes},
    {"getUidRxBytes", "(I)J", (void*) getUidRxBytes},
};

int register_android_net_TrafficStats(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, "android/net/TrafficStats",
            gMethods, NELEM(gMethods));
}

}
