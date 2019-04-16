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

static jobject android_net_utils_resNetworkResult(JNIEnv *env, jobject thiz, jobject javaFd) {
    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    int rcode;
    std::vector<uint8_t> buf(MAXPACKETSIZE, 0);

    int res = resNetworkResult(fd, &rcode, buf.data(), MAXPACKETSIZE);
    jniSetFileDescriptorOfFD(env, javaFd, -1);
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

    jclass class_DnsResponse = env->FindClass("android/net/DnsResolver$DnsResponse");
    jmethodID ctor = env->GetMethodID(class_DnsResponse, "<init>", "([BI)V");

    return env->NewObject(class_DnsResponse, ctor, answer, rcode);
}

static void android_net_utils_resNetworkCancel(JNIEnv *env, jobject thiz, jobject javaFd) {
    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    resNetworkCancel(fd);
    jniSetFileDescriptorOfFD(env, javaFd, -1);
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
    { "attachDropAllBPFFilter", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_attachDropAllBPFFilter },
    { "detachBPFFilter", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_detachBPFFilter },
    { "getTcpRepairWindow", "(Ljava/io/FileDescriptor;)Landroid/net/TcpRepairWindow;", (void*) android_net_utils_getTcpRepairWindow },
    { "setupRaSocket", "(Ljava/io/FileDescriptor;I)V", (void*) android_net_utils_setupRaSocket },
    { "resNetworkSend", "(I[BII)Ljava/io/FileDescriptor;", (void*) android_net_utils_resNetworkSend },
    { "resNetworkQuery", "(ILjava/lang/String;III)Ljava/io/FileDescriptor;", (void*) android_net_utils_resNetworkQuery },
    { "resNetworkResult", "(Ljava/io/FileDescriptor;)Landroid/net/DnsResolver$DnsResponse;", (void*) android_net_utils_resNetworkResult },
    { "resNetworkCancel", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_resNetworkCancel },
};

int register_android_net_NetworkUtils(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, NETUTILS_PKG_NAME, gNetworkUtilMethods,
                                NELEM(gNetworkUtilMethods));
}

}; // namespace android
