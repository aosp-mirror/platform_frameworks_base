/*
 * Copyright 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "NetUtils"

#include "jni.h"
#include "JNIHelp.h"
#include "NetdClient.h"
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <arpa/inet.h>
#include <net/if.h>
#include <linux/filter.h>
#include <linux/if.h>
#include <linux/if_arp.h>
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <net/if_ether.h>
#include <netinet/icmp6.h>
#include <netinet/ip.h>
#include <netinet/ip6.h>
#include <netinet/udp.h>
#include <cutils/properties.h>

#include "core_jni_helpers.h"

extern "C" {
int ifc_enable(const char *ifname);
int ifc_disable(const char *ifname);
}

#define NETUTILS_PKG_NAME "android/net/NetworkUtils"

namespace android {

static const uint16_t kDhcpClientPort = 68;

static void android_net_utils_attachDhcpFilter(JNIEnv *env, jobject clazz, jobject javaFd)
{
    uint32_t ip_offset = sizeof(ether_header);
    uint32_t proto_offset = ip_offset + offsetof(iphdr, protocol);
    uint32_t flags_offset = ip_offset + offsetof(iphdr, frag_off);
    uint32_t dport_indirect_offset = ip_offset + offsetof(udphdr, dest);
    struct sock_filter filter_code[] = {
        // Check the protocol is UDP.
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  proto_offset),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    IPPROTO_UDP, 0, 6),

        // Check this is not a fragment.
        BPF_STMT(BPF_LD  | BPF_H    | BPF_ABS, flags_offset),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K,   0x1fff, 4, 0),

        // Get the IP header length.
        BPF_STMT(BPF_LDX | BPF_B    | BPF_MSH, ip_offset),

        // Check the destination port.
        BPF_STMT(BPF_LD  | BPF_H    | BPF_IND, dport_indirect_offset),
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   kDhcpClientPort, 0, 1),

        // Accept or reject.
        BPF_STMT(BPF_RET | BPF_K,              0xffff),
        BPF_STMT(BPF_RET | BPF_K,              0)
    };
    struct sock_fprog filter = {
        sizeof(filter_code) / sizeof(filter_code[0]),
        filter_code,
    };

    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    if (setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER, &filter, sizeof(filter)) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(SO_ATTACH_FILTER): %s", strerror(errno));
    }
}

static void android_net_utils_attachRaFilter(JNIEnv *env, jobject clazz, jobject javaFd,
        jint hardwareAddressType)
{
    if (hardwareAddressType != ARPHRD_ETHER) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "attachRaFilter only supports ARPHRD_ETHER");
        return;
    }

    uint32_t ipv6_offset = sizeof(ether_header);
    uint32_t ipv6_next_header_offset = ipv6_offset + offsetof(ip6_hdr, ip6_nxt);
    uint32_t icmp6_offset = ipv6_offset + sizeof(ip6_hdr);
    uint32_t icmp6_type_offset = icmp6_offset + offsetof(icmp6_hdr, icmp6_type);
    struct sock_filter filter_code[] = {
        // Check IPv6 Next Header is ICMPv6.
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  ipv6_next_header_offset),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    IPPROTO_ICMPV6, 0, 3),

        // Check ICMPv6 type is Router Advertisement.
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  icmp6_type_offset),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    ND_ROUTER_ADVERT, 0, 1),

        // Accept or reject.
        BPF_STMT(BPF_RET | BPF_K,              0xffff),
        BPF_STMT(BPF_RET | BPF_K,              0)
    };
    struct sock_fprog filter = {
        sizeof(filter_code) / sizeof(filter_code[0]),
        filter_code,
    };

    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    if (setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER, &filter, sizeof(filter)) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(SO_ATTACH_FILTER): %s", strerror(errno));
    }
}

static jboolean android_net_utils_bindProcessToNetwork(JNIEnv *env, jobject thiz, jint netId)
{
    return (jboolean) !setNetworkForProcess(netId);
}

static jint android_net_utils_getBoundNetworkForProcess(JNIEnv *env, jobject thiz)
{
    return getNetworkForProcess();
}

static jboolean android_net_utils_bindProcessToNetworkForHostResolution(JNIEnv *env, jobject thiz,
        jint netId)
{
    return (jboolean) !setNetworkForResolv(netId);
}

static jint android_net_utils_bindSocketToNetwork(JNIEnv *env, jobject thiz, jint socket,
        jint netId)
{
    return setNetworkForSocket(netId, socket);
}

static jboolean android_net_utils_protectFromVpn(JNIEnv *env, jobject thiz, jint socket)
{
    return (jboolean) !protectFromVpn(socket);
}

static jboolean android_net_utils_queryUserAccess(JNIEnv *env, jobject thiz, jint uid, jint netId)
{
    return (jboolean) !queryUserAccess(uid, netId);
}


// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static const JNINativeMethod gNetworkUtilMethods[] = {
    /* name, signature, funcPtr */
    { "bindProcessToNetwork", "(I)Z", (void*) android_net_utils_bindProcessToNetwork },
    { "getBoundNetworkForProcess", "()I", (void*) android_net_utils_getBoundNetworkForProcess },
    { "bindProcessToNetworkForHostResolution", "(I)Z", (void*) android_net_utils_bindProcessToNetworkForHostResolution },
    { "bindSocketToNetwork", "(II)I", (void*) android_net_utils_bindSocketToNetwork },
    { "protectFromVpn", "(I)Z", (void*)android_net_utils_protectFromVpn },
    { "queryUserAccess", "(II)Z", (void*)android_net_utils_queryUserAccess },
    { "attachDhcpFilter", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_attachDhcpFilter },
    { "attachRaFilter", "(Ljava/io/FileDescriptor;I)V", (void*) android_net_utils_attachRaFilter },
};

int register_android_net_NetworkUtils(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, NETUTILS_PKG_NAME, gNetworkUtilMethods,
                                NELEM(gNetworkUtilMethods));
}

}; // namespace android
