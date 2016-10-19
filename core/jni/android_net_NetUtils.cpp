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

static const uint32_t kEtherTypeOffset = offsetof(ether_header, ether_type);
static const uint32_t kEtherHeaderLen = sizeof(ether_header);
static const uint32_t kIPv4Protocol = kEtherHeaderLen + offsetof(iphdr, protocol);
static const uint32_t kIPv4FlagsOffset = kEtherHeaderLen + offsetof(iphdr, frag_off);
static const uint32_t kIPv6NextHeader = kEtherHeaderLen + offsetof(ip6_hdr, ip6_nxt);
static const uint32_t kIPv6PayloadStart = kEtherHeaderLen + sizeof(ip6_hdr);
static const uint32_t kICMPv6TypeOffset = kIPv6PayloadStart + offsetof(icmp6_hdr, icmp6_type);
static const uint32_t kUDPSrcPortIndirectOffset = kEtherHeaderLen + offsetof(udphdr, source);
static const uint32_t kUDPDstPortIndirectOffset = kEtherHeaderLen + offsetof(udphdr, dest);
static const uint16_t kDhcpClientPort = 68;

static void android_net_utils_attachDhcpFilter(JNIEnv *env, jobject clazz, jobject javaFd)
{
    struct sock_filter filter_code[] = {
        // Check the protocol is UDP.
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  kIPv4Protocol),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    IPPROTO_UDP, 0, 6),

        // Check this is not a fragment.
        BPF_STMT(BPF_LD  | BPF_H    | BPF_ABS, kIPv4FlagsOffset),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K,   IP_OFFMASK, 4, 0),

        // Get the IP header length.
        BPF_STMT(BPF_LDX | BPF_B    | BPF_MSH, kEtherHeaderLen),

        // Check the destination port.
        BPF_STMT(BPF_LD  | BPF_H    | BPF_IND, kUDPDstPortIndirectOffset),
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

    struct sock_filter filter_code[] = {
        // Check IPv6 Next Header is ICMPv6.
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  kIPv6NextHeader),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    IPPROTO_ICMPV6, 0, 3),

        // Check ICMPv6 type is Router Advertisement.
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  kICMPv6TypeOffset),
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

// TODO: Move all this filter code into libnetutils.
static void android_net_utils_attachControlPacketFilter(
        JNIEnv *env, jobject clazz, jobject javaFd, jint hardwareAddressType) {
    if (hardwareAddressType != ARPHRD_ETHER) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "attachControlPacketFilter only supports ARPHRD_ETHER");
        return;
    }

    // Capture all:
    //     - ARPs
    //     - DHCPv4 packets
    //     - Router Advertisements & Solicitations
    //     - Neighbor Advertisements & Solicitations
    //
    // tcpdump:
    //     arp or
    //     '(ip and udp port 68)' or
    //     '(icmp6 and ip6[40] >= 133 and ip6[40] <= 136)'
    struct sock_filter filter_code[] = {
        // Load the link layer next payload field.
        BPF_STMT(BPF_LD  | BPF_H   | BPF_ABS,  kEtherTypeOffset),

        // Accept all ARP.
        // TODO: Figure out how to better filter ARPs on noisy networks.
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ETHERTYPE_ARP, 16, 0),

        // If IPv4:
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ETHERTYPE_IP, 0, 9),

        // Check the protocol is UDP.
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  kIPv4Protocol),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    IPPROTO_UDP, 0, 14),

        // Check this is not a fragment.
        BPF_STMT(BPF_LD  | BPF_H    | BPF_ABS, kIPv4FlagsOffset),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K,   IP_OFFMASK, 12, 0),

        // Get the IP header length.
        BPF_STMT(BPF_LDX | BPF_B    | BPF_MSH, kEtherHeaderLen),

        // Check the source port.
        BPF_STMT(BPF_LD  | BPF_H    | BPF_IND, kUDPSrcPortIndirectOffset),
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   kDhcpClientPort, 8, 0),

        // Check the destination port.
        BPF_STMT(BPF_LD  | BPF_H    | BPF_IND, kUDPDstPortIndirectOffset),
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   kDhcpClientPort, 6, 7),

        // IPv6 ...
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, ETHERTYPE_IPV6, 0, 6),
        // ... check IPv6 Next Header is ICMPv6 (ignore fragments), ...
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  kIPv6NextHeader),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,    IPPROTO_ICMPV6, 0, 4),
        // ... and check the ICMPv6 type is one of RS/RA/NS/NA.
        BPF_STMT(BPF_LD  | BPF_B   | BPF_ABS,  kICMPv6TypeOffset),
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K,    ND_ROUTER_SOLICIT, 0, 2),
        BPF_JUMP(BPF_JMP | BPF_JGT | BPF_K,    ND_NEIGHBOR_ADVERT, 1, 0),

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

static void android_net_utils_setupRaSocket(JNIEnv *env, jobject clazz, jobject javaFd,
        jint ifIndex)
{
    static const int kLinkLocalHopLimit = 255;

    int fd = jniGetFDFromFileDescriptor(env, javaFd);

    // Set an ICMPv6 filter that only passes Router Solicitations.
    struct icmp6_filter rs_only;
    ICMP6_FILTER_SETBLOCKALL(&rs_only);
    ICMP6_FILTER_SETPASS(ND_ROUTER_SOLICIT, &rs_only);
    socklen_t len = sizeof(rs_only);
    if (setsockopt(fd, IPPROTO_ICMPV6, ICMP6_FILTER, &rs_only, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(ICMP6_FILTER): %s", strerror(errno));
        return;
    }

    // Most/all of the rest of these options can be set via Java code, but
    // because we're here on account of setting an icmp6_filter go ahead
    // and do it all natively for now.
    //
    // TODO: Consider moving these out to Java.

    // Set the multicast hoplimit to 255 (link-local only).
    int hops = kLinkLocalHopLimit;
    len = sizeof(hops);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, &hops, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_MULTICAST_HOPS): %s", strerror(errno));
        return;
    }

    // Set the unicast hoplimit to 255 (link-local only).
    hops = kLinkLocalHopLimit;
    len = sizeof(hops);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &hops, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_UNICAST_HOPS): %s", strerror(errno));
        return;
    }

    // Explicitly disable multicast loopback.
    int off = 0;
    len = sizeof(off);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_MULTICAST_LOOP, &off, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_MULTICAST_LOOP): %s", strerror(errno));
        return;
    }

    // Specify the IPv6 interface to use for outbound multicast.
    len = sizeof(ifIndex);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_MULTICAST_IF, &ifIndex, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_MULTICAST_IF): %s", strerror(errno));
        return;
    }

    // Additional options to be considered:
    //     - IPV6_TCLASS
    //     - IPV6_RECVPKTINFO
    //     - IPV6_RECVHOPLIMIT

    // Bind to [::].
    const struct sockaddr_in6 sin6 = {
            .sin6_family = AF_INET6,
            .sin6_port = 0,
            .sin6_flowinfo = 0,
            .sin6_addr = IN6ADDR_ANY_INIT,
            .sin6_scope_id = 0,
    };
    auto sa = reinterpret_cast<const struct sockaddr *>(&sin6);
    len = sizeof(sin6);
    if (bind(fd, sa, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "bind(IN6ADDR_ANY): %s", strerror(errno));
        return;
    }

    // Join the all-routers multicast group, ff02::2%index.
    struct ipv6_mreq all_rtrs = {
        .ipv6mr_multiaddr = {{{0xff,2,0,0,0,0,0,0,0,0,0,0,0,0,0,2}}},
        .ipv6mr_interface = ifIndex,
    };
    len = sizeof(all_rtrs);
    if (setsockopt(fd, IPPROTO_IPV6, IPV6_JOIN_GROUP, &all_rtrs, len) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(IPV6_JOIN_GROUP): %s", strerror(errno));
        return;
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
    { "attachControlPacketFilter", "(Ljava/io/FileDescriptor;I)V", (void*) android_net_utils_attachControlPacketFilter },
    { "setupRaSocket", "(Ljava/io/FileDescriptor;I)V", (void*) android_net_utils_setupRaSocket },
};

int register_android_net_NetworkUtils(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, NETUTILS_PKG_NAME, gNetworkUtilMethods,
                                NELEM(gNetworkUtilMethods));
}

}; // namespace android
