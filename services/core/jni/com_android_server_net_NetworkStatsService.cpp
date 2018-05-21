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

#define LOG_TAG "NetworkStatsNative"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <sys/stat.h>
#include <sys/types.h>

#include "core_jni_helpers.h"
#include <jni.h>
#include <nativehelper/ScopedUtfChars.h>
#include <utils/misc.h>
#include <utils/Log.h>

#include "android-base/unique_fd.h"
#include "bpf/BpfNetworkStats.h"
#include "bpf/BpfUtils.h"

using android::bpf::Stats;
using android::bpf::hasBpfSupport;
using android::bpf::bpfGetUidStats;
using android::bpf::bpfGetIfaceStats;

namespace android {

static const char* QTAGUID_IFACE_STATS = "/proc/net/xt_qtaguid/iface_stat_fmt";
static const char* QTAGUID_UID_STATS = "/proc/net/xt_qtaguid/stats";

// NOTE: keep these in sync with TrafficStats.java
static const uint64_t UNKNOWN = -1;

enum StatsType {
    RX_BYTES = 0,
    RX_PACKETS = 1,
    TX_BYTES = 2,
    TX_PACKETS = 3,
    TCP_RX_PACKETS = 4,
    TCP_TX_PACKETS = 5
};

static uint64_t getStatsType(struct Stats* stats, StatsType type) {
    switch (type) {
        case RX_BYTES:
            return stats->rxBytes;
        case RX_PACKETS:
            return stats->rxPackets;
        case TX_BYTES:
            return stats->txBytes;
        case TX_PACKETS:
            return stats->txPackets;
        case TCP_RX_PACKETS:
            return stats->tcpRxPackets;
        case TCP_TX_PACKETS:
            return stats->tcpTxPackets;
        default:
            return UNKNOWN;
    }
}

static int parseIfaceStats(const char* iface, struct Stats* stats) {
    FILE *fp = fopen(QTAGUID_IFACE_STATS, "r");
    if (fp == NULL) {
        return -1;
    }

    char buffer[384];
    char cur_iface[32];
    bool foundTcp = false;
    uint64_t rxBytes, rxPackets, txBytes, txPackets, tcpRxPackets, tcpTxPackets;

    while (fgets(buffer, sizeof(buffer), fp) != NULL) {
        int matched = sscanf(buffer, "%31s %" SCNu64 " %" SCNu64 " %" SCNu64
                " %" SCNu64 " " "%*u %" SCNu64 " %*u %*u %*u %*u "
                "%*u %" SCNu64 " %*u %*u %*u %*u", cur_iface, &rxBytes,
                &rxPackets, &txBytes, &txPackets, &tcpRxPackets, &tcpTxPackets);
        if (matched >= 5) {
            if (matched == 7) {
                foundTcp = true;
            }
            if (!iface || !strcmp(iface, cur_iface)) {
                stats->rxBytes += rxBytes;
                stats->rxPackets += rxPackets;
                stats->txBytes += txBytes;
                stats->txPackets += txPackets;
                if (matched == 7) {
                    stats->tcpRxPackets += tcpRxPackets;
                    stats->tcpTxPackets += tcpTxPackets;
                }
            }
        }
    }

    if (!foundTcp) {
        stats->tcpRxPackets = UNKNOWN;
        stats->tcpTxPackets = UNKNOWN;
    }

    if (fclose(fp) != 0) {
        return -1;
    }
    return 0;
}

static int parseUidStats(const uint32_t uid, struct Stats* stats) {
    FILE *fp = fopen(QTAGUID_UID_STATS, "r");
    if (fp == NULL) {
        return -1;
    }

    char buffer[384];
    char iface[32];
    uint32_t idx, cur_uid, set;
    uint64_t tag, rxBytes, rxPackets, txBytes, txPackets;

    while (fgets(buffer, sizeof(buffer), fp) != NULL) {
        if (sscanf(buffer,
                "%" SCNu32 " %31s 0x%" SCNx64 " %u %u %" SCNu64 " %" SCNu64
                " %" SCNu64 " %" SCNu64 "",
                &idx, iface, &tag, &cur_uid, &set, &rxBytes, &rxPackets,
                &txBytes, &txPackets) == 9) {
            if (uid == cur_uid && tag == 0L) {
                stats->rxBytes += rxBytes;
                stats->rxPackets += rxPackets;
                stats->txBytes += txBytes;
                stats->txPackets += txPackets;
            }
        }
    }

    if (fclose(fp) != 0) {
        return -1;
    }
    return 0;
}

static jlong getTotalStat(JNIEnv* env, jclass clazz, jint type, jboolean useBpfStats) {
    struct Stats stats;
    memset(&stats, 0, sizeof(Stats));

    if (useBpfStats) {
        if (bpfGetIfaceStats(NULL, &stats) == 0) {
            return getStatsType(&stats, (StatsType) type);
        } else {
            return UNKNOWN;
        }
    }

    if (parseIfaceStats(NULL, &stats) == 0) {
        return getStatsType(&stats, (StatsType) type);
    } else {
        return UNKNOWN;
    }
}

static jlong getIfaceStat(JNIEnv* env, jclass clazz, jstring iface, jint type,
                          jboolean useBpfStats) {
    ScopedUtfChars iface8(env, iface);
    if (iface8.c_str() == NULL) {
        return UNKNOWN;
    }

    struct Stats stats;
    memset(&stats, 0, sizeof(Stats));

    if (useBpfStats) {
        if (bpfGetIfaceStats(iface8.c_str(), &stats) == 0) {
            return getStatsType(&stats, (StatsType) type);
        } else {
            return UNKNOWN;
        }
    }

    if (parseIfaceStats(iface8.c_str(), &stats) == 0) {
        return getStatsType(&stats, (StatsType) type);
    } else {
        return UNKNOWN;
    }
}

static jlong getUidStat(JNIEnv* env, jclass clazz, jint uid, jint type, jboolean useBpfStats) {
    struct Stats stats;
    memset(&stats, 0, sizeof(Stats));

    if (useBpfStats) {
        if (bpfGetUidStats(uid, &stats) == 0) {
            return getStatsType(&stats, (StatsType) type);
        } else {
            return UNKNOWN;
        }
    }

    if (parseUidStats(uid, &stats) == 0) {
        return getStatsType(&stats, (StatsType) type);
    } else {
        return UNKNOWN;
    }
}

static const JNINativeMethod gMethods[] = {
    {"nativeGetTotalStat", "(IZ)J", (void*) getTotalStat},
    {"nativeGetIfaceStat", "(Ljava/lang/String;IZ)J", (void*) getIfaceStat},
    {"nativeGetUidStat", "(IIZ)J", (void*) getUidStat},
};

int register_android_server_net_NetworkStatsService(JNIEnv* env) {
    jclass netStatsService = env->FindClass("com/android/server/net/NetworkStatsService");
    jfieldID rxBytesId = env->GetStaticFieldID(netStatsService, "TYPE_RX_BYTES", "I");
    jfieldID rxPacketsId = env->GetStaticFieldID(netStatsService, "TYPE_RX_PACKETS", "I");
    jfieldID txBytesId = env->GetStaticFieldID(netStatsService, "TYPE_TX_BYTES", "I");
    jfieldID txPacketsId = env->GetStaticFieldID(netStatsService, "TYPE_TX_PACKETS", "I");
    jfieldID tcpRxPacketsId = env->GetStaticFieldID(netStatsService, "TYPE_TCP_RX_PACKETS", "I");
    jfieldID tcpTxPacketsId = env->GetStaticFieldID(netStatsService, "TYPE_TCP_TX_PACKETS", "I");

    env->SetStaticIntField(netStatsService, rxBytesId, RX_BYTES);
    env->SetStaticIntField(netStatsService, rxPacketsId, RX_PACKETS);
    env->SetStaticIntField(netStatsService, txBytesId, TX_BYTES);
    env->SetStaticIntField(netStatsService, txPacketsId, TX_PACKETS);
    env->SetStaticIntField(netStatsService, tcpRxPacketsId, TCP_RX_PACKETS);
    env->SetStaticIntField(netStatsService, tcpTxPacketsId, TCP_TX_PACKETS);

    return jniRegisterNativeMethods(env, "com/android/server/net/NetworkStatsService", gMethods,
                                    NELEM(gMethods));
}

}
