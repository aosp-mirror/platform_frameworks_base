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

#include <vector>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include "NetdClient.h"
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <arpa/inet.h>
#include <net/if.h>
#include <linux/filter.h>
#include <linux/if_arp.h>
#include <linux/tcp.h>
#include <netinet/ether.h>
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

constexpr int MAXPACKETSIZE = 8 * 1024;
// FrameworkListener limits the size of commands to 1024 bytes. TODO: fix this.
constexpr int MAXCMDSIZE = 1024;

static void throwErrnoException(JNIEnv* env, const char* functionName, int error) {
    ScopedLocalRef<jstring> detailMessage(env, env->NewStringUTF(functionName));
    if (detailMessage.get() == NULL) {
        // Not really much we can do here. We're probably dead in the water,
        // but let's try to stumble on...
        env->ExceptionClear();
    }
    static jclass errnoExceptionClass =
            MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/system/ErrnoException"));

    static jmethodID errnoExceptionCtor =
            GetMethodIDOrDie(env, errnoExceptionClass,
            "<init>", "(Ljava/lang/String;I)V");

    jobject exception = env->NewObject(errnoExceptionClass,
                                       errnoExceptionCtor,
                                       detailMessage.get(),
                                       error);
    env->Throw(reinterpret_cast<jthrowable>(exception));
}

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

static void android_net_utils_attachDropAllBPFFilter(JNIEnv *env, jobject clazz, jobject javaFd)
{
    struct sock_filter filter_code[] = {
        // Reject all.
        BPF_STMT(BPF_RET | BPF_K, 0)
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

static void android_net_utils_detachBPFFilter(JNIEnv *env, jobject clazz, jobject javaFd)
{
    int dummy = 0;
    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    if (setsockopt(fd, SOL_SOCKET, SO_DETACH_FILTER, &dummy, sizeof(dummy)) != 0) {
        jniThrowExceptionFmt(env, "java/net/SocketException",
                "setsockopt(SO_DETACH_FILTER): %s", strerror(errno));
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

static bool checkLenAndCopy(JNIEnv* env, const jbyteArray& addr, int len, void* dst)
{
    if (env->GetArrayLength(addr) != len) {
        return false;
    }
    env->GetByteArrayRegion(addr, 0, len, reinterpret_cast<jbyte*>(dst));
    return true;
}

static void android_net_utils_addArpEntry(JNIEnv *env, jobject thiz, jbyteArray ethAddr,
        jbyteArray ipv4Addr, jstring ifname, jobject javaFd)
{
    struct arpreq req = {};
    struct sockaddr_in& netAddrStruct = *reinterpret_cast<sockaddr_in*>(&req.arp_pa);
    struct sockaddr& ethAddrStruct = req.arp_ha;

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

static jobject android_net_utils_resNetworkQuery(JNIEnv *env, jobject thiz, jint netId,
        jstring dname, jint ns_class, jint ns_type, jint flags) {
    const jsize javaCharsCount = env->GetStringLength(dname);
    const jsize byteCountUTF8 = env->GetStringUTFLength(dname);

    // Only allow dname which could be simply formatted to UTF8.
    // In native layer, res_mkquery would re-format the input char array to packet.
    std::vector<char> queryname(byteCountUTF8 + 1, 0);

    env->GetStringUTFRegion(dname, 0, javaCharsCount, queryname.data());
    int fd = resNetworkQuery(netId, queryname.data(), ns_class, ns_type, flags);

    if (fd < 0) {
        throwErrnoException(env, "resNetworkQuery", -fd);
        return nullptr;
    }

    return jniCreateFileDescriptor(env, fd);
}

static jobject android_net_utils_resNetworkSend(JNIEnv *env, jobject thiz, jint netId,
        jbyteArray msg, jint msgLen, jint flags) {
    uint8_t data[MAXCMDSIZE];

    checkLenAndCopy(env, msg, msgLen, data);
    int fd = resNetworkSend(netId, data, msgLen, flags);

    if (fd < 0) {
        throwErrnoException(env, "resNetworkSend", -fd);
        return nullptr;
    }

    return jniCreateFileDescriptor(env, fd);
}

static jbyteArray android_net_utils_resNetworkResult(JNIEnv *env, jobject thiz, jobject javaFd) {
    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    int rcode;
    std::vector<uint8_t> buf(MAXPACKETSIZE, 0);

    int res = resNetworkResult(fd, &rcode, buf.data(), MAXPACKETSIZE);
    if (res < 0) {
        throwErrnoException(env, "resNetworkResult", -res);
        return nullptr;
    }

    jbyteArray answer = env->NewByteArray(res);
    if (answer == nullptr) {
        throwErrnoException(env, "resNetworkResult", ENOMEM);
        return nullptr;
    } else {
        env->SetByteArrayRegion(answer, 0, res,
                reinterpret_cast<jbyte*>(buf.data()));
    }

    return answer;
}

static jobject android_net_utils_getTcpRepairWindow(JNIEnv *env, jobject thiz, jobject javaFd) {
    if (javaFd == NULL) {
        jniThrowNullPointerException(env, NULL);
        return NULL;
    }

    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    struct tcp_repair_window trw = {};
    socklen_t size = sizeof(trw);

    // Obtain the parameters of the TCP repair window.
    int rc = getsockopt(fd, IPPROTO_TCP, TCP_REPAIR_WINDOW, &trw, &size);
    if (rc == -1) {
      throwErrnoException(env, "getsockopt : TCP_REPAIR_WINDOW", errno);
      return NULL;
    }

    struct tcp_info tcpinfo = {};
    socklen_t tcpinfo_size = sizeof(tcp_info);

    // Obtain the window scale from the tcp info structure. This contains a scale factor that
    // should be applied to the window size.
    rc = getsockopt(fd, IPPROTO_TCP, TCP_INFO, &tcpinfo, &tcpinfo_size);
    if (rc == -1) {
      throwErrnoException(env, "getsockopt : TCP_INFO", errno);
      return NULL;
    }

    jclass class_TcpRepairWindow = env->FindClass("android/net/TcpRepairWindow");
    jmethodID ctor = env->GetMethodID(class_TcpRepairWindow, "<init>", "(IIIIII)V");

    return env->NewObject(class_TcpRepairWindow, ctor, trw.snd_wl1, trw.snd_wnd, trw.max_window,
            trw.rcv_wnd, trw.rcv_wup, tcpinfo.tcpi_rcv_wscale);
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
    { "addArpEntry", "([B[BLjava/lang/String;Ljava/io/FileDescriptor;)V", (void*) android_net_utils_addArpEntry },
    { "attachDhcpFilter", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_attachDhcpFilter },
    { "attachRaFilter", "(Ljava/io/FileDescriptor;I)V", (void*) android_net_utils_attachRaFilter },
    { "attachControlPacketFilter", "(Ljava/io/FileDescriptor;I)V", (void*) android_net_utils_attachControlPacketFilter },
    { "attachDropAllBPFFilter", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_attachDropAllBPFFilter },
    { "detachBPFFilter", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_detachBPFFilter },
    { "getTcpRepairWindow", "(Ljava/io/FileDescriptor;)Landroid/net/TcpRepairWindow;", (void*) android_net_utils_getTcpRepairWindow },
    { "setupRaSocket", "(Ljava/io/FileDescriptor;I)V", (void*) android_net_utils_setupRaSocket },
    { "resNetworkSend", "(I[BII)Ljava/io/FileDescriptor;", (void*) android_net_utils_resNetworkSend },
    { "resNetworkQuery", "(ILjava/lang/String;III)Ljava/io/FileDescriptor;", (void*) android_net_utils_resNetworkQuery },
    { "resNetworkResult", "(Ljava/io/FileDescriptor;)[B", (void*) android_net_utils_resNetworkResult },
};

int register_android_net_NetworkUtils(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, NETUTILS_PKG_NAME, gNetworkUtilMethods,
                                NELEM(gNetworkUtilMethods));
}

}; // namespace android
