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

static const uint64_t VALUE_UNKNOWN = -1;
static const char* IFACE_STAT_ALL = "/proc/net/xt_qtaguid/iface_stat_all";

enum Tx_Rx {
    TX,
    RX
};

enum Tcp_Udp {
    TCP,
    UDP,
    TCP_AND_UDP
};

// NOTE: keep these in sync with TrafficStats.java
enum IfaceStatType {
    RX_BYTES = 0,
    RX_PACKETS = 1,
    TX_BYTES = 2,
    TX_PACKETS = 3
};

struct IfaceStat {
    uint64_t rxBytes;
    uint64_t rxPackets;
    uint64_t txBytes;
    uint64_t txPackets;
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

static int parseIfaceStat(const char* iface, struct IfaceStat* stat) {
    FILE *fp = fopen(IFACE_STAT_ALL, "r");
    if (!fp) {
        return errno;
    }

    char buffer[256];
    char cur_iface[32];
    int active;
    uint64_t rxBytes, rxPackets, txBytes, txPackets, devRxBytes, devRxPackets, devTxBytes,
            devTxPackets;

    while (fgets(buffer, 256, fp) != NULL) {
        if (sscanf(buffer, "%31s %d %llu %llu %llu %llu %llu %llu %llu %llu", cur_iface, &active,
                   &rxBytes, &rxPackets, &txBytes, &txPackets, &devRxBytes, &devRxPackets,
                   &devTxBytes, &devTxPackets) != 10) {
            continue;
        }

        if (!iface || !strcmp(iface, cur_iface)) {
            stat->rxBytes += rxBytes;
            stat->rxPackets += rxPackets;
            stat->txBytes += txBytes;
            stat->txPackets += txPackets;

            if (active) {
                stat->rxBytes += devRxBytes;
                stat->rxPackets += devRxPackets;
                stat->txBytes += devTxBytes;
                stat->txPackets += devTxPackets;
            }
        }
    }

    fclose(fp);
    return 0;
}

static uint64_t getIfaceStatType(const char* iface, IfaceStatType type) {
    struct IfaceStat stat;
    memset(&stat, 0, sizeof(IfaceStat));

    if (parseIfaceStat(iface, &stat)) {
        return VALUE_UNKNOWN;
    }

    switch (type) {
        case RX_BYTES:
            return stat.rxBytes;
        case RX_PACKETS:
            return stat.rxPackets;
        case TX_BYTES:
            return stat.txBytes;
        case TX_PACKETS:
            return stat.txPackets;
        default:
            return VALUE_UNKNOWN;
    }
}

static jlong getTotalStat(JNIEnv* env, jclass clazz, jint type) {
    return getIfaceStatType(NULL, (IfaceStatType) type);
}

static jlong getIfaceStat(JNIEnv* env, jclass clazz, jstring iface, jint type) {
    struct IfaceStat stat;
    const char* ifaceChars = env->GetStringUTFChars(iface, NULL);
    if (ifaceChars) {
        uint64_t stat = getIfaceStatType(ifaceChars, (IfaceStatType) type);
        env->ReleaseStringUTFChars(iface, ifaceChars);
        return stat;
    } else {
        return VALUE_UNKNOWN;
    }
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
    {"nativeGetTotalStat", "(I)J", (void*) getTotalStat},
    {"nativeGetIfaceStat", "(Ljava/lang/String;I)J", (void*) getIfaceStat},

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
