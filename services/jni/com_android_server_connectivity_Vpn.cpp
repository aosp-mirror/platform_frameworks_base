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

#define LOG_NDEBUG 0

#define LOG_TAG "VpnJni"
#include <cutils/log.h>
#include <cutils/properties.h>

#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>

#include <linux/if.h>
#include <linux/if_tun.h>
#include <linux/route.h>
#include <linux/ipv6_route.h>

#include "jni.h"
#include "JNIHelp.h"

namespace android
{

static inline in_addr_t *as_in_addr(sockaddr *sa) {
    return &((sockaddr_in *)sa)->sin_addr.s_addr;
}

static inline in_addr_t *as_in_addr(sockaddr_storage *ss) {
    return &((sockaddr_in *)ss)->sin_addr.s_addr;
}

static inline in6_addr *as_in6_addr(sockaddr_storage *ss) {
    return &((sockaddr_in6 *)&ss)->sin6_addr;
}

//------------------------------------------------------------------------------

#define SYSTEM_ERROR -1
#define BAD_ARGUMENT -2

static int create_interface(char *name, int *index)
{
    int tun = open("/dev/tun", O_RDWR);
    int inet4 = socket(AF_INET, SOCK_DGRAM, 0);

    ifreq ifr4;
    memset(&ifr4, 0, sizeof(ifr4));

    // Allocate interface.
    ifr4.ifr_flags = IFF_TUN;
    if (ioctl(tun, TUNSETIFF, &ifr4)) {
        LOGE("Cannot allocate TUN: %s", strerror(errno));
        goto error;
    }

    // Activate interface.
    ifr4.ifr_flags = IFF_UP;
    if (ioctl(inet4, SIOCSIFFLAGS, &ifr4)) {
        LOGE("Cannot activate %s: %s", ifr4.ifr_name, strerror(errno));
        goto error;
    }

    // Get interface index.
    if (ioctl(inet4, SIOGIFINDEX, &ifr4)) {
        LOGE("Cannot get index of %s: %s", ifr4.ifr_name, strerror(errno));
        goto error;
    }

    strcpy(name, ifr4.ifr_name);
    *index = ifr4.ifr_ifindex;
    close(inet4);
    return tun;

error:
    close(tun);
    close(inet4);
    return SYSTEM_ERROR;
}

static int set_addresses(const char *name, int index, const char *addresses)
{
    int inet4 = socket(AF_INET, SOCK_DGRAM, 0);
    int inet6 = socket(AF_INET6, SOCK_DGRAM, 0);

    ifreq ifr4;
    memset(&ifr4, 0, sizeof(ifr4));
    strcpy(ifr4.ifr_name, name);
    ifr4.ifr_addr.sa_family = AF_INET;

    in6_ifreq ifr6;
    memset(&ifr6, 0, sizeof(ifr6));
    ifr6.ifr6_ifindex = index;

    char address[65];
    int prefix;

    int chars;
    int count = 0;

    while (sscanf(addresses, " %64[^/]/%d %n", address, &prefix, &chars) == 2) {
        addresses += chars;

        if (strchr(address, ':')) {
            // Add an IPv6 address.
            if (inet_pton(AF_INET6, address, &ifr6.ifr6_addr) != 1 ||
                    prefix < 0 || prefix > 128) {
                count = BAD_ARGUMENT;
                break;
            }

            ifr6.ifr6_prefixlen = prefix;
            if (ioctl(inet6, SIOCSIFADDR, &ifr6)) {
                count = (errno == EINVAL) ? BAD_ARGUMENT : SYSTEM_ERROR;
                break;
            }
        } else {
            // Add an IPv4 address.
            if (inet_pton(AF_INET, address, as_in_addr(&ifr4.ifr_addr)) != 1 ||
                    prefix < 0 || prefix > 32) {
                count = BAD_ARGUMENT;
                break;
            }

            if (count) {
                sprintf(ifr4.ifr_name, "%s:%d", name, count);
            }
            if (ioctl(inet4, SIOCSIFADDR, &ifr4)) {
                count = (errno == EINVAL) ? BAD_ARGUMENT : SYSTEM_ERROR;
                break;
            }

            in_addr_t mask = prefix ? (~0 << (32 - prefix)) : 0;
            *as_in_addr(&ifr4.ifr_addr) = htonl(mask);
            if (ioctl(inet4, SIOCSIFNETMASK, &ifr4)) {
                count = (errno == EINVAL) ? BAD_ARGUMENT : SYSTEM_ERROR;
                break;
            }
        }
        LOGV("Address added on %s: %s/%d", name, address, prefix);
        ++count;
    }

    if (count == BAD_ARGUMENT) {
        LOGE("Invalid address: %s/%d", address, prefix);
    } else if (count == SYSTEM_ERROR) {
        LOGE("Cannot add address: %s/%d: %s", address, prefix, strerror(errno));
    } else if (*addresses) {
        LOGE("Invalid address: %s", addresses);
        count = BAD_ARGUMENT;
    }

    close(inet4);
    close(inet6);
    return count;
}

static int set_routes(const char *name, int index, const char *routes)
{
    int inet4 = socket(AF_INET, SOCK_DGRAM, 0);
    int inet6 = socket(AF_INET6, SOCK_DGRAM, 0);

    rtentry rt4;
    memset(&rt4, 0, sizeof(rt4));
    rt4.rt_dev = (char *)name;
    rt4.rt_flags = RTF_UP;
    rt4.rt_dst.sa_family = AF_INET;
    rt4.rt_genmask.sa_family = AF_INET;
    rt4.rt_gateway.sa_family = AF_INET;

    in6_rtmsg rt6;
    memset(&rt6, 0, sizeof(rt6));
    rt6.rtmsg_ifindex = index;
    rt6.rtmsg_flags = RTF_UP;

    char address[65];
    int prefix;
    char gateway[65];

    int chars;
    int count = 0;

    while (sscanf(routes, " %64[^/]/%d>%64[^ ] %n",
            address, &prefix, gateway, &chars) == 3) {
        routes += chars;

        if (strchr(address, ':')) {
            // Add an IPv6 route.
            if (inet_pton(AF_INET6, gateway, &rt6.rtmsg_gateway) != 1 ||
                    inet_pton(AF_INET6, address, &rt6.rtmsg_dst) != 1 ||
                    prefix < 0 || prefix > 128) {
                count = BAD_ARGUMENT;
                break;
            }

            rt6.rtmsg_dst_len = prefix;
            if (memcmp(&rt6.rtmsg_gateway, &in6addr_any, sizeof(in6addr_any))) {
                rt6.rtmsg_flags |= RTF_GATEWAY;
            }
            if (ioctl(inet6, SIOCADDRT, &rt6)) {
                count = (errno == EINVAL) ? BAD_ARGUMENT : SYSTEM_ERROR;
                break;
            }
        } else {
            // Add an IPv4 route.
            if (inet_pton(AF_INET, gateway, as_in_addr(&rt4.rt_gateway)) != 1 ||
                    inet_pton(AF_INET, address, as_in_addr(&rt4.rt_dst)) != 1 ||
                    prefix < 0 || prefix > 32) {
                count = BAD_ARGUMENT;
                break;
            }

            in_addr_t mask = prefix ? (~0 << (32 - prefix)) : 0;
            *as_in_addr(&rt4.rt_genmask) = htonl(mask);
            if (*as_in_addr(&rt4.rt_gateway)) {
                rt4.rt_flags |= RTF_GATEWAY;
            }
            if (ioctl(inet4, SIOCADDRT, &rt4)) {
                count = (errno == EINVAL) ? BAD_ARGUMENT : SYSTEM_ERROR;
                break;
            }
        }
        LOGV("Route added on %s: %s/%d -> %s", name, address, prefix, gateway);
        ++count;
    }

    if (count == BAD_ARGUMENT) {
        LOGE("Invalid route: %s/%d -> %s", address, prefix, gateway);
    } else if (count == SYSTEM_ERROR) {
        LOGE("Cannot add route: %s/%d -> %s: %s",
                address, prefix, gateway, strerror(errno));
    } else if (*routes) {
        LOGE("Invalid route: %s", routes);
        count = BAD_ARGUMENT;
    }

    close(inet4);
    close(inet6);
    return count;
}

static int get_interface_name(char *name, int tun)
{
    ifreq ifr4;
    if (ioctl(tun, TUNGETIFF, &ifr4)) {
        LOGE("Cannot get interface name: %s", strerror(errno));
        return SYSTEM_ERROR;
    }
    strcpy(name, ifr4.ifr_name);
    return 0;
}

static int reset_interface(const char *name)
{
    int inet4 = socket(AF_INET, SOCK_DGRAM, 0);

    ifreq ifr4;
    ifr4.ifr_flags = 0;
    strncpy(ifr4.ifr_name, name, IFNAMSIZ);

    if (ioctl(inet4, SIOCSIFFLAGS, &ifr4) && errno != ENODEV) {
        LOGE("Cannot reset %s: %s", name, strerror(errno));
        close(inet4);
        return SYSTEM_ERROR;
    }
    close(inet4);
    return 0;
}

static int check_interface(const char *name)
{
    int inet4 = socket(AF_INET, SOCK_DGRAM, 0);

    ifreq ifr4;
    strncpy(ifr4.ifr_name, name, IFNAMSIZ);

    if (ioctl(inet4, SIOCGIFFLAGS, &ifr4) && errno != ENODEV) {
        LOGE("Cannot check %s: %s", name, strerror(errno));
        ifr4.ifr_flags = 0;
    }
    close(inet4);
    return ifr4.ifr_flags;
}

static int bind_to_interface(int fd, const char *name)
{
    if (setsockopt(fd, SOL_SOCKET, SO_BINDTODEVICE, name, strlen(name))) {
        LOGE("Cannot bind socket to %s: %s", name, strerror(errno));
        return SYSTEM_ERROR;
    }
    return 0;
}

//------------------------------------------------------------------------------

static void throwException(JNIEnv *env, int error, const char *message)
{
    if (error == SYSTEM_ERROR) {
        jniThrowException(env, "java/lang/IllegalStateException", message);
    } else {
        jniThrowException(env, "java/lang/IllegalArgumentException", message);
    }
}

static jint establish(JNIEnv *env, jobject thiz,
        jstring jAddresses, jstring jRoutes)
{
    char name[IFNAMSIZ];
    int index;
    int tun = create_interface(name, &index);
    if (tun < 0) {
        throwException(env, tun, "Cannot create interface");
        return -1;
    }
    LOGD("%s is created", name);

    const char *addresses;
    const char *routes;
    int count;

    // Addresses are required.
    addresses = jAddresses ? env->GetStringUTFChars(jAddresses, NULL) : NULL;
    if (!addresses) {
        jniThrowNullPointerException(env, "address");
        goto error;
    }
    count = set_addresses(name, index, addresses);
    env->ReleaseStringUTFChars(jAddresses, addresses);
    if (count <= 0) {
        throwException(env, count, "Cannot set address");
        goto error;
    }
    LOGD("Configured %d address(es) on %s", count, name);

    // Routes are optional.
    routes = jRoutes ? env->GetStringUTFChars(jRoutes, NULL) : NULL;
    if (routes) {
        count = set_routes(name, index, routes);
        env->ReleaseStringUTFChars(jRoutes, routes);
        if (count < 0) {
            throwException(env, count, "Cannot set route");
            goto error;
        }
        LOGD("Configured %d route(s) on %s", count, name);
    }

    return tun;

error:
    close(tun);
    LOGD("%s is destroyed", name);
    return -1;
}

static jstring getName(JNIEnv *env, jobject thiz, jint fd)
{
    char name[IFNAMSIZ];
    if (get_interface_name(name, fd) < 0) {
        throwException(env, SYSTEM_ERROR, "Cannot get interface name");
        return NULL;
    }
    return env->NewStringUTF(name);
}

static void reset(JNIEnv *env, jobject thiz, jstring jName)
{
    const char *name = jName ?
            env->GetStringUTFChars(jName, NULL) : NULL;
    if (!name) {
        jniThrowNullPointerException(env, "name");
        return;
    }
    if (reset_interface(name) < 0) {
        throwException(env, SYSTEM_ERROR, "Cannot reset interface");
    } else {
        LOGD("%s is deactivated", name);
    }
    env->ReleaseStringUTFChars(jName, name);
}

static jint check(JNIEnv *env, jobject thiz, jstring jName)
{
    const char *name = jName ?
            env->GetStringUTFChars(jName, NULL) : NULL;
    if (!name) {
        jniThrowNullPointerException(env, "name");
        return 0;
    }
    int flags = check_interface(name);
    env->ReleaseStringUTFChars(jName, name);
    return flags;
}

static void protect(JNIEnv *env, jobject thiz, jint fd, jstring jName)
{
    const char *name = jName ?
            env->GetStringUTFChars(jName, NULL) : NULL;
    if (!name) {
        jniThrowNullPointerException(env, "name");
        return;
    }
    if (bind_to_interface(fd, name) < 0) {
        throwException(env, SYSTEM_ERROR, "Cannot protect socket");
    }
    env->ReleaseStringUTFChars(jName, name);
}

//------------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"nativeEstablish", "(Ljava/lang/String;Ljava/lang/String;)I", (void *)establish},
    {"nativeGetName", "(I)Ljava/lang/String;", (void *)getName},
    {"nativeReset", "(Ljava/lang/String;)V", (void *)reset},
    {"nativeCheck", "(Ljava/lang/String;)I", (void *)check},
    {"nativeProtect", "(ILjava/lang/String;)V", (void *)protect},
};

int register_android_server_connectivity_Vpn(JNIEnv *env)
{
    return jniRegisterNativeMethods(env, "com/android/server/connectivity/Vpn",
            gMethods, NELEM(gMethods));
}

};
