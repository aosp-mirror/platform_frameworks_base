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

#include <cutils/qtaguid.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <jni.h>
#include <nativehelper/ScopedUtfChars.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include "bpf/BpfUtils.h"
#include "netdbpf/BpfNetworkStats.h"

using android::bpf::bpfGetUidStats;
using android::bpf::bpfGetIfaceStats;

namespace android {

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

static uint64_t getStatsType(Stats* stats, StatsType type) {
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

static jlong getTotalStat(JNIEnv* env, jclass clazz, jint type) {
    Stats stats = {};

    if (bpfGetIfaceStats(NULL, &stats) == 0) {
        return getStatsType(&stats, (StatsType) type);
    } else {
        return UNKNOWN;
    }
}

static jlong getIfaceStat(JNIEnv* env, jclass clazz, jstring iface, jint type) {
    ScopedUtfChars iface8(env, iface);
    if (iface8.c_str() == NULL) {
        return UNKNOWN;
    }

    Stats stats = {};

    if (bpfGetIfaceStats(iface8.c_str(), &stats) == 0) {
        return getStatsType(&stats, (StatsType) type);
    } else {
        return UNKNOWN;
    }
}

static jlong getUidStat(JNIEnv* env, jclass clazz, jint uid, jint type) {
    Stats stats = {};

    if (bpfGetUidStats(uid, &stats) == 0) {
        return getStatsType(&stats, (StatsType) type);
    } else {
        return UNKNOWN;
    }
}

static const JNINativeMethod gMethods[] = {
        {"nativeGetTotalStat", "(I)J", (void*)getTotalStat},
        {"nativeGetIfaceStat", "(Ljava/lang/String;I)J", (void*)getIfaceStat},
        {"nativeGetUidStat", "(II)J", (void*)getUidStat},
};

int register_android_server_net_NetworkStatsService(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/net/NetworkStatsService", gMethods,
                                    NELEM(gMethods));
}

}
