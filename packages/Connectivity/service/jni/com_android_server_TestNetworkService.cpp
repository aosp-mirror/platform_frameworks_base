/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define LOG_NDEBUG 0

#define LOG_TAG "TestNetworkServiceJni"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <linux/ipv6_route.h>
#include <linux/route.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>

#include <log/log.h>

#include "netutils/ifc.h"

#include "jni.h"
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>

namespace android {

//------------------------------------------------------------------------------

static void throwException(JNIEnv* env, int error, const char* action, const char* iface) {
    const std::string& msg =
        android::base::StringPrintf("Error %s %s: %s", action, iface, strerror(error));

    jniThrowException(env, "java/lang/IllegalStateException", msg.c_str());
}

static int createTunTapInterface(JNIEnv* env, bool isTun, const char* iface) {
    base::unique_fd tun(open("/dev/tun", O_RDWR | O_NONBLOCK));
    ifreq ifr{};

    // Allocate interface.
    ifr.ifr_flags = (isTun ? IFF_TUN : IFF_TAP) | IFF_NO_PI;
    strlcpy(ifr.ifr_name, iface, IFNAMSIZ);
    if (ioctl(tun.get(), TUNSETIFF, &ifr)) {
        throwException(env, errno, "allocating", ifr.ifr_name);
        return -1;
    }

    // Activate interface using an unconnected datagram socket.
    base::unique_fd inet6CtrlSock(socket(AF_INET6, SOCK_DGRAM, 0));
    ifr.ifr_flags = IFF_UP;

    if (ioctl(inet6CtrlSock.get(), SIOCSIFFLAGS, &ifr)) {
        throwException(env, errno, "activating", ifr.ifr_name);
        return -1;
    }

    return tun.release();
}

//------------------------------------------------------------------------------

static jint create(JNIEnv* env, jobject /* thiz */, jboolean isTun, jstring jIface) {
    ScopedUtfChars iface(env, jIface);
    if (!iface.c_str()) {
        jniThrowNullPointerException(env, "iface");
        return -1;
    }

    int tun = createTunTapInterface(env, isTun, iface.c_str());

    // Any exceptions will be thrown from the createTunTapInterface call
    return tun;
}

//------------------------------------------------------------------------------

static const JNINativeMethod gMethods[] = {
    {"jniCreateTunTap", "(ZLjava/lang/String;)I", (void*)create},
};

int register_android_server_TestNetworkService(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/TestNetworkService", gMethods,
                                    NELEM(gMethods));
}

}; // namespace android
