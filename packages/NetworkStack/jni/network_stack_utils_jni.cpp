/*
 * Copyright 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "NetworkStackUtils-JNI"

#include <errno.h>
#include <jni.h>
#include <linux/filter.h>
#include <linux/if_arp.h>
#include <net/if.h>
#include <netinet/ether.h>
#include <netinet/icmp6.h>
#include <netinet/ip.h>
#include <netinet/ip6.h>
#include <netinet/udp.h>
#include <stdlib.h>

#include <string>

#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

namespace android {
constexpr const char NETWORKSTACKUTILS_PKG_NAME[] = "android/net/util/NetworkStackUtils";

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

static bool checkLenAndCopy(JNIEnv* env, const jbyteArray& addr, int len, void* dst) {
    if (env->GetArrayLength(addr) != len) {
        return false;
    }
    env->GetByteArrayRegion(addr, 0, len, reinterpret_cast<jbyte*>(dst));
    return true;
}

static void network_stack_utils_addArpEntry(JNIEnv *env, jobject thiz, jbyteArray ethAddr,
        jbyteArray ipv4Addr, jstring ifname, jobject javaFd) {
    arpreq req = {};
    sockaddr_in& netAddrStruct = *reinterpret_cast<sockaddr_in*>(&req.arp_pa);
    sockaddr& ethAddrStruct = req.arp_ha;

    ethAddrStruct.sa_family = ARPHRD_ETHER;
    if (!checkLenAndCopy(env, ethAddr, ETH_ALEN, ethAddrStruct.sa_data)) {
        jniThrowException(env, "java/io/IOException", "Invalid ethAddr length");
        return;
    }

    netAddrStruct.sin_family = AF_INET;
    if (!checkLenAndCopy(env, ipv4Addr, sizeof(in_addr), &netAddrStruct.sin_addr)) {
        jniThrowException(env, "java/io/IOException", "Invalid ipv4Addr length");
        return;
    }

    int ifLen = env->GetStringLength(ifname);
    // IFNAMSIZ includes the terminating NULL character
    if (ifLen >= IFNAMSIZ) {
        jniThrowException(env, "java/io/IOException", "ifname too long");
        return;
    }
    env->GetStringUTFRegion(ifname, 0, ifLen, req.arp_dev);

    req.arp_flags = ATF_COM;  // Completed entry (ha valid)
    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    if (fd < 0) {
        jniThrowExceptionFmt(env, "java/io/IOException", "Invalid file descriptor");
        return;
    }
    // See also: man 7 arp
    if (ioctl(fd, SIOCSARP, &req)) {
        jniThrowExceptionFmt(env, "java/io/IOException", "ioctl error: %s", strerror(errno));
        return;
    }
}

static void network_stack_utils_attachDhcpFilter(JNIEnv *env, jobject clazz, jobject javaFd) {
    static sock_filter filter_code[] = {
        // Check the protocol is UDP.
        BPF_STMT(BPF_LD  | BPF_B    | BPF_ABS, kIPv4Protocol),
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   IPPROTO_UDP, 0, 6),

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
    static const sock_fprog filter = {
        sizeof(filter_code) / sizeof(filter_code[0]),
        filter_code,
    };

    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    if (setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER, &filter, sizeof(filter)) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(SO_ATTACH_FILTER): %s", strerror(errno));
    }
}

static void network_stack_utils_attachRaFilter(JNIEnv *env, jobject clazz, jobject javaFd,
        jint hardwareAddressType) {
    if (hardwareAddressType != ARPHRD_ETHER) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "attachRaFilter only supports ARPHRD_ETHER");
        return;
    }

    static sock_filter filter_code[] = {
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
    static const sock_fprog filter = {
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
static void network_stack_utils_attachControlPacketFilter(
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
    static sock_filter filter_code[] = {
        // Load the link layer next payload field.
        BPF_STMT(BPF_LD  | BPF_H    | BPF_ABS,  kEtherTypeOffset),

        // Accept all ARP.
        // TODO: Figure out how to better filter ARPs on noisy networks.
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   ETHERTYPE_ARP, 16, 0),

        // If IPv4:
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   ETHERTYPE_IP, 0, 9),

        // Check the protocol is UDP.
        BPF_STMT(BPF_LD  | BPF_B    | BPF_ABS, kIPv4Protocol),
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   IPPROTO_UDP, 0, 14),

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
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   ETHERTYPE_IPV6, 0, 6),
        // ... check IPv6 Next Header is ICMPv6 (ignore fragments), ...
        BPF_STMT(BPF_LD  | BPF_B    | BPF_ABS, kIPv6NextHeader),
        BPF_JUMP(BPF_JMP | BPF_JEQ  | BPF_K,   IPPROTO_ICMPV6, 0, 4),
        // ... and check the ICMPv6 type is one of RS/RA/NS/NA.
        BPF_STMT(BPF_LD  | BPF_B    | BPF_ABS, kICMPv6TypeOffset),
        BPF_JUMP(BPF_JMP | BPF_JGE  | BPF_K,   ND_ROUTER_SOLICIT, 0, 2),
        BPF_JUMP(BPF_JMP | BPF_JGT  | BPF_K,   ND_NEIGHBOR_ADVERT, 1, 0),

        // Accept or reject.
        BPF_STMT(BPF_RET | BPF_K,              0xffff),
        BPF_STMT(BPF_RET | BPF_K,              0)
    };
    static const sock_fprog filter = {
        sizeof(filter_code) / sizeof(filter_code[0]),
        filter_code,
    };

    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    if (setsockopt(fd, SOL_SOCKET, SO_ATTACH_FILTER, &filter, sizeof(filter)) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(SO_ATTACH_FILTER): %s", strerror(errno));
    }
}

/*
 * JNI registration.
 */
static const JNINativeMethod gNetworkStackUtilsMethods[] = {
    /* name, signature, funcPtr */
    { "addArpEntry", "([B[BLjava/lang/String;Ljava/io/FileDescriptor;)V", (void*) network_stack_utils_addArpEntry },
    { "attachDhcpFilter", "(Ljava/io/FileDescriptor;)V", (void*) network_stack_utils_attachDhcpFilter },
    { "attachRaFilter", "(Ljava/io/FileDescriptor;I)V", (void*) network_stack_utils_attachRaFilter },
    { "attachControlPacketFilter", "(Ljava/io/FileDescriptor;I)V", (void*) network_stack_utils_attachControlPacketFilter },
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed");
        return JNI_ERR;
    }

    if (jniRegisterNativeMethods(env, NETWORKSTACKUTILS_PKG_NAME,
            gNetworkStackUtilsMethods, NELEM(gNetworkStackUtilsMethods)) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;

}
}; // namespace android