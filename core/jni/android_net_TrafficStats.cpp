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
#include <ScopedUtfChars.h>
#include <utils/misc.h>
#include <utils/Log.h>

namespace android {

enum Tx_Rx {
    TX,
    RX
};

enum Tcp_Udp {
    TCP,
    UDP,
    TCP_AND_UDP
};

// Returns an ASCII decimal number read from the specified file, -1 on error.
static jlong readNumber(char const* filename) {
    char buf[80];
    int fd = open(filename, O_RDONLY);
    if (fd < 0) {
        if (errno != ENOENT) ALOGE("Can't open %s: %s", filename, strerror(errno));
        return -1;
    }

    int len = read(fd, buf, sizeof(buf) - 1);
    if (len < 0) {
        ALOGE("Can't read %s: %s", filename, strerror(errno));
        close(fd);
        return -1;
    }

    close(fd);
    buf[len] = '\0';
    return atoll(buf);
}

static const char* mobile_iface_list[] = {
    "rmnet0",
    "rmnet1",
    "rmnet2",
    "rmnet3",
    "cdma_rmnet4",
    "ppp0",
    0
};

static jlong getAll(const char** iface_list, const char* what) {

    char filename[80];
    int idx = 0;
    bool supported = false;
    jlong total = 0;
    while (iface_list[idx] != 0) {

        snprintf(filename, sizeof(filename), "/sys/class/net/%s/statistics/%s",
                 iface_list[idx], what);
        jlong number = readNumber(filename);
        if (number >= 0) {
            supported = true;
            total += number;
        }
        idx++;
    }
    if (supported) return total;

    return -1;
}

// Returns the sum of numbers from the specified path under /sys/class/net/*,
// -1 if no such file exists.
static jlong readTotal(char const* suffix) {
    char filename[PATH_MAX] = "/sys/class/net/";
    DIR *dir = opendir(filename);
    if (dir == NULL) {
        ALOGE("Can't list %s: %s", filename, strerror(errno));
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
}

// Mobile stats get accessed a lot more often than total stats.
// Note the individual files can come and go at runtime, so we check
// each file every time (rather than caching which ones exist).

static jlong getMobileTxPackets(JNIEnv* env, jobject clazz) {
    return getAll(mobile_iface_list, "tx_packets");
}

static jlong getMobileRxPackets(JNIEnv* env, jobject clazz) {
    return getAll(mobile_iface_list, "rx_packets");
}

static jlong getMobileTxBytes(JNIEnv* env, jobject clazz) {
    return getAll(mobile_iface_list, "tx_bytes");
}

static jlong getMobileRxBytes(JNIEnv* env, jobject clazz) {
    return getAll(mobile_iface_list, "rx_bytes");
}

static jlong getData(JNIEnv* env, const char* what, jstring javaInterface) {
    ScopedUtfChars interface(env, javaInterface);
    if (interface.c_str() == NULL) {
        return -1;
    }

    char filename[80];
    snprintf(filename, sizeof(filename), "/sys/class/net/%s/statistics/%s", interface.c_str(), what);
    return readNumber(filename);
}

static jlong getTxPackets(JNIEnv* env, jobject clazz, jstring interface) {
    return getData(env, "tx_packets", interface);
}

static jlong getRxPackets(JNIEnv* env, jobject clazz, jstring interface) {
    return getData(env, "rx_packets", interface);
}

static jlong getTxBytes(JNIEnv* env, jobject clazz, jstring interface) {
    return getData(env, "tx_bytes", interface);
}

static jlong getRxBytes(JNIEnv* env, jobject clazz, jstring interface) {
    return getData(env, "rx_bytes", interface);
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

static jlong getUidBytes(JNIEnv* env, jobject clazz, jint uid,
                         enum Tx_Rx tx_or_rx, enum Tcp_Udp tcp_or_udp) {
    char tcp_filename[80], udp_filename[80];
    jlong tcp_bytes = -1, udp_bytes = -1, total_bytes = -1;

    switch (tx_or_rx) {
        case TX:
            sprintf(tcp_filename, "/proc/uid_stat/%d/tcp_snd", uid);
            sprintf(udp_filename, "/proc/uid_stat/%d/udp_snd", uid);
            break;
        case RX:
            sprintf(tcp_filename, "/proc/uid_stat/%d/tcp_rcv", uid);
            sprintf(udp_filename, "/proc/uid_stat/%d/udp_rcv", uid);
            break;
        default:
            return -1;
    }

    switch (tcp_or_udp) {
        case TCP:
            tcp_bytes = readNumber(tcp_filename);
            total_bytes = (tcp_bytes >= 0) ? tcp_bytes : -1;
            break;
        case UDP:
            udp_bytes = readNumber(udp_filename);
            total_bytes = (udp_bytes >= 0) ? udp_bytes : -1;
            break;
        case TCP_AND_UDP:
            tcp_bytes = readNumber(tcp_filename);
            total_bytes += (tcp_bytes >= 0 ? tcp_bytes : 0);

            udp_bytes = readNumber(udp_filename);
            total_bytes += (udp_bytes >= 0 ? udp_bytes : 0);
            break;
        default:
            return -1;
    }

    return total_bytes;
}

static jlong getUidPkts(JNIEnv* env, jobject clazz, jint uid,
                         enum Tx_Rx tx_or_rx, enum Tcp_Udp tcp_or_udp) {
    char tcp_filename[80], udp_filename[80];
    jlong tcp_pkts = -1, udp_pkts = -1, total_pkts = -1;

    switch (tx_or_rx) {
        case TX:
            sprintf(tcp_filename, "/proc/uid_stat/%d/tcp_snd_pkt", uid);
            sprintf(udp_filename, "/proc/uid_stat/%d/udp_snd_pkt", uid);
            break;
        case RX:
            sprintf(tcp_filename, "/proc/uid_stat/%d/tcp_rcv_pkt", uid);
            sprintf(udp_filename, "/proc/uid_stat/%d/udp_rcv_pkt", uid);
            break;
        default:
            return -1;
    }

    switch (tcp_or_udp) {
        case TCP:
            tcp_pkts = readNumber(tcp_filename);
            total_pkts = (tcp_pkts >= 0) ? tcp_pkts : -1;
            break;
        case UDP:
            udp_pkts = readNumber(udp_filename);
            total_pkts = (udp_pkts >= 0) ? udp_pkts : -1;
            break;
        case TCP_AND_UDP:
            tcp_pkts = readNumber(tcp_filename);
            total_pkts += (tcp_pkts >= 0 ? tcp_pkts : 0);

            udp_pkts = readNumber(udp_filename);
            total_pkts += (udp_pkts >= 0 ? udp_pkts : 0);
            break;
        default:
            return -1;
    }

    return total_pkts;
}

static jlong getUidRxBytes(JNIEnv* env, jobject clazz, jint uid) {
    return getUidBytes(env, clazz, uid, RX, TCP_AND_UDP);
}

static jlong getUidTxBytes(JNIEnv* env, jobject clazz, jint uid) {
    return getUidBytes(env, clazz, uid, TX, TCP_AND_UDP);
}

/* TCP Segments + UDP Packets */
static jlong getUidTxPackets(JNIEnv* env, jobject clazz, jint uid) {
    return getUidPkts(env, clazz, uid, TX, TCP_AND_UDP);
}

/* TCP Segments + UDP Packets */
static jlong getUidRxPackets(JNIEnv* env, jobject clazz, jint uid) {
    return getUidPkts(env, clazz, uid, RX, TCP_AND_UDP);
}

static jlong getUidTcpTxBytes(JNIEnv* env, jobject clazz, jint uid) {
    return getUidBytes(env, clazz, uid, TX, TCP);
}

static jlong getUidTcpRxBytes(JNIEnv* env, jobject clazz, jint uid) {
    return getUidBytes(env, clazz, uid, RX, TCP);
}

static jlong getUidUdpTxBytes(JNIEnv* env, jobject clazz, jint uid) {
    return getUidBytes(env, clazz, uid, TX, UDP);
}

static jlong getUidUdpRxBytes(JNIEnv* env, jobject clazz, jint uid) {
    return getUidBytes(env, clazz, uid, RX, UDP);
}

static jlong getUidTcpTxSegments(JNIEnv* env, jobject clazz, jint uid) {
    return getUidPkts(env, clazz, uid, TX, TCP);
}

static jlong getUidTcpRxSegments(JNIEnv* env, jobject clazz, jint uid) {
    return getUidPkts(env, clazz, uid, RX, TCP);
}

static jlong getUidUdpTxPackets(JNIEnv* env, jobject clazz, jint uid) {
    return getUidPkts(env, clazz, uid, TX, UDP);
}

static jlong getUidUdpRxPackets(JNIEnv* env, jobject clazz, jint uid) {
    return getUidPkts(env, clazz, uid, RX, UDP);
}

static JNINativeMethod gMethods[] = {
    {"getMobileTxPackets", "()J", (void*) getMobileTxPackets},
    {"getMobileRxPackets", "()J", (void*) getMobileRxPackets},
    {"getMobileTxBytes", "()J", (void*) getMobileTxBytes},
    {"getMobileRxBytes", "()J", (void*) getMobileRxBytes},
    {"getTxPackets", "(Ljava/lang/String;)J", (void*) getTxPackets},
    {"getRxPackets", "(Ljava/lang/String;)J", (void*) getRxPackets},
    {"getTxBytes", "(Ljava/lang/String;)J", (void*) getTxBytes},
    {"getRxBytes", "(Ljava/lang/String;)J", (void*) getRxBytes},
    {"getTotalTxPackets", "()J", (void*) getTotalTxPackets},
    {"getTotalRxPackets", "()J", (void*) getTotalRxPackets},
    {"getTotalTxBytes", "()J", (void*) getTotalTxBytes},
    {"getTotalRxBytes", "()J", (void*) getTotalRxBytes},

    /* Per-UID Stats */
    {"getUidTxBytes", "(I)J", (void*) getUidTxBytes},
    {"getUidRxBytes", "(I)J", (void*) getUidRxBytes},
    {"getUidTxPackets", "(I)J", (void*) getUidTxPackets},
    {"getUidRxPackets", "(I)J", (void*) getUidRxPackets},

    {"getUidTcpTxBytes", "(I)J", (void*) getUidTcpTxBytes},
    {"getUidTcpRxBytes", "(I)J", (void*) getUidTcpRxBytes},
    {"getUidUdpTxBytes", "(I)J", (void*) getUidUdpTxBytes},
    {"getUidUdpRxBytes", "(I)J", (void*) getUidUdpRxBytes},

    {"getUidTcpTxSegments", "(I)J", (void*) getUidTcpTxSegments},
    {"getUidTcpRxSegments", "(I)J", (void*) getUidTcpRxSegments},
    {"getUidUdpTxPackets", "(I)J", (void*) getUidUdpTxPackets},
    {"getUidUdpRxPackets", "(I)J", (void*) getUidUdpRxPackets},
};

int register_android_net_TrafficStats(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, "android/net/TrafficStats",
            gMethods, NELEM(gMethods));
}

}
