/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "NetworkStats"

#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <android_runtime/AndroidRuntime.h>
#include <jni.h>

#include <ScopedUtfChars.h>
#include <ScopedLocalRef.h>
#include <ScopedPrimitiveArray.h>

#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/Vector.h>

namespace android {

static jclass gStringClass;

static struct {
    jfieldID size;
    jfieldID iface;
    jfieldID uid;
    jfieldID set;
    jfieldID tag;
    jfieldID rxBytes;
    jfieldID rxPackets;
    jfieldID txBytes;
    jfieldID txPackets;
    jfieldID operations;
} gNetworkStatsClassInfo;

struct stats_line {
    int32_t idx;
    char iface[32];
    int32_t uid;
    int32_t set;
    int32_t tag;
    int64_t rxBytes;
    int64_t rxPackets;
    int64_t txBytes;
    int64_t txPackets;
};

static int readNetworkStatsDetail(JNIEnv* env, jclass clazz, jobject stats,
        jstring path, jint limitUid) {
    ScopedUtfChars path8(env, path);
    if (path8.c_str() == NULL) {
        return -1;
    }

    FILE *fp = fopen(path8.c_str(), "r");
    if (fp == NULL) {
        return -1;
    }

    Vector<stats_line> lines;

    int lastIdx = 1;
    char buffer[384];
    while (fgets(buffer, sizeof(buffer), fp) != NULL) {
        stats_line s;
        int64_t rawTag;
        if (sscanf(buffer, "%d %31s 0x%llx %u %u %llu %llu %llu %llu", &s.idx,
                &s.iface, &rawTag, &s.uid, &s.set, &s.rxBytes, &s.rxPackets,
                &s.txBytes, &s.txPackets) == 9) {
            if (s.idx != lastIdx + 1) {
                ALOGE("inconsistent idx=%d after lastIdx=%d", s.idx, lastIdx);
                return -1;
            }
            lastIdx = s.idx;

            s.tag = rawTag >> 32;
            lines.push_back(s);
        }
    }

    if (fclose(fp) != 0) {
        return -1;
    }

    int size = lines.size();

    ScopedLocalRef<jobjectArray> iface(env, env->NewObjectArray(size, gStringClass, NULL));
    if (iface.get() == NULL) return -1;
    ScopedIntArrayRW uid(env, env->NewIntArray(size));
    if (uid.get() == NULL) return -1;
    ScopedIntArrayRW set(env, env->NewIntArray(size));
    if (set.get() == NULL) return -1;
    ScopedIntArrayRW tag(env, env->NewIntArray(size));
    if (tag.get() == NULL) return -1;
    ScopedLongArrayRW rxBytes(env, env->NewLongArray(size));
    if (rxBytes.get() == NULL) return -1;
    ScopedLongArrayRW rxPackets(env, env->NewLongArray(size));
    if (rxPackets.get() == NULL) return -1;
    ScopedLongArrayRW txBytes(env, env->NewLongArray(size));
    if (txBytes.get() == NULL) return -1;
    ScopedLongArrayRW txPackets(env, env->NewLongArray(size));
    if (txPackets.get() == NULL) return -1;
    ScopedLongArrayRW operations(env, env->NewLongArray(size));
    if (operations.get() == NULL) return -1;

    for (int i = 0; i < size; i++) {
        ScopedLocalRef<jstring> ifaceString(env, env->NewStringUTF(lines[i].iface));
        env->SetObjectArrayElement(iface.get(), i, ifaceString.get());

        uid[i] = lines[i].uid;
        set[i] = lines[i].set;
        tag[i] = lines[i].tag;
        rxBytes[i] = lines[i].rxBytes;
        rxPackets[i] = lines[i].rxPackets;
        txBytes[i] = lines[i].txBytes;
        txPackets[i] = lines[i].txPackets;
    }

    env->SetIntField(stats, gNetworkStatsClassInfo.size, size);
    env->SetObjectField(stats, gNetworkStatsClassInfo.iface, iface.get());
    env->SetObjectField(stats, gNetworkStatsClassInfo.uid, uid.getJavaArray());
    env->SetObjectField(stats, gNetworkStatsClassInfo.set, set.getJavaArray());
    env->SetObjectField(stats, gNetworkStatsClassInfo.tag, tag.getJavaArray());
    env->SetObjectField(stats, gNetworkStatsClassInfo.rxBytes, rxBytes.getJavaArray());
    env->SetObjectField(stats, gNetworkStatsClassInfo.rxPackets, rxPackets.getJavaArray());
    env->SetObjectField(stats, gNetworkStatsClassInfo.txBytes, txBytes.getJavaArray());
    env->SetObjectField(stats, gNetworkStatsClassInfo.txPackets, txPackets.getJavaArray());
    env->SetObjectField(stats, gNetworkStatsClassInfo.operations, operations.getJavaArray());

    return 0;
}

static jclass findClass(JNIEnv* env, const char* name) {
    ScopedLocalRef<jclass> localClass(env, env->FindClass(name));
    jclass result = reinterpret_cast<jclass>(env->NewGlobalRef(localClass.get()));
    if (result == NULL) {
        ALOGE("failed to find class '%s'", name);
        abort();
    }
    return result;
}

static JNINativeMethod gMethods[] = {
        { "nativeReadNetworkStatsDetail",
                "(Landroid/net/NetworkStats;Ljava/lang/String;I)I",
                (void*) readNetworkStatsDetail }
};

int register_com_android_internal_net_NetworkStatsFactory(JNIEnv* env) {
    int err = AndroidRuntime::registerNativeMethods(env,
            "com/android/internal/net/NetworkStatsFactory", gMethods,
            NELEM(gMethods));

    gStringClass = findClass(env, "java/lang/String");

    jclass clazz = env->FindClass("android/net/NetworkStats");
    gNetworkStatsClassInfo.size = env->GetFieldID(clazz, "size", "I");
    gNetworkStatsClassInfo.iface = env->GetFieldID(clazz, "iface", "[Ljava/lang/String;");
    gNetworkStatsClassInfo.uid = env->GetFieldID(clazz, "uid", "[I");
    gNetworkStatsClassInfo.set = env->GetFieldID(clazz, "set", "[I");
    gNetworkStatsClassInfo.tag = env->GetFieldID(clazz, "tag", "[I");
    gNetworkStatsClassInfo.rxBytes = env->GetFieldID(clazz, "rxBytes", "[J");
    gNetworkStatsClassInfo.rxPackets = env->GetFieldID(clazz, "rxPackets", "[J");
    gNetworkStatsClassInfo.txBytes = env->GetFieldID(clazz, "txBytes", "[J");
    gNetworkStatsClassInfo.txPackets = env->GetFieldID(clazz, "txPackets", "[J");
    gNetworkStatsClassInfo.operations = env->GetFieldID(clazz, "operations", "[J");

    return err;
}

}
