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

#include <arpa/inet.h>
#include <linux/filter.h>
#include <linux/if_arp.h>
#include <linux/tcp.h>
#include <net/if.h>
#include <netinet/ether.h>
#include <netinet/ip.h>
#include <netinet/udp.h>

#include <DnsProxydProtocol.h> // NETID_USE_LOCAL_NAMESERVERS
#include <android_runtime/AndroidRuntime.h>
#include <cutils/properties.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <utils/Log.h>
#include <utils/misc.h>

#include "NetdClient.h"
#include "core_jni_helpers.h"
#include "jni.h"

extern "C" {
int ifc_enable(const char *ifname);
int ifc_disable(const char *ifname);
}

#define NETUTILS_PKG_NAME "android/net/NetworkUtils"

namespace android {

constexpr int MAXPACKETSIZE = 8 * 1024;
// FrameworkListener limits the size of commands to 4096 bytes.
constexpr int MAXCMDSIZE = 4096;

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

static jobject android_net_utils_getDnsNetwork(JNIEnv *env, jobject thiz) {
    unsigned dnsNetId = 0;
    if (int res = getNetworkForDns(&dnsNetId) < 0) {
        throwErrnoException(env, "getDnsNetId", -res);
        return nullptr;
    }
    bool privateDnsBypass = dnsNetId & NETID_USE_LOCAL_NAMESERVERS;

    static jclass class_Network = MakeGlobalRefOrDie(
            env, FindClassOrDie(env, "android/net/Network"));
    static jmethodID ctor = env->GetMethodID(class_Network, "<init>", "(IZ)V");
    return env->NewObject(
            class_Network, ctor, dnsNetId & ~NETID_USE_LOCAL_NAMESERVERS, privateDnsBypass);
}

static void android_net_utils_setAllowNetworkingForProcess(JNIEnv *env, jobject thiz,
                                                           jboolean hasConnectivity) {
    setAllowNetworkingForProcess(hasConnectivity == JNI_TRUE);
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
// clang-format off
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
    { "resNetworkSend", "(I[BII)Ljava/io/FileDescriptor;", (void*) android_net_utils_resNetworkSend },
    { "resNetworkQuery", "(ILjava/lang/String;III)Ljava/io/FileDescriptor;", (void*) android_net_utils_resNetworkQuery },
    { "resNetworkResult", "(Ljava/io/FileDescriptor;)Landroid/net/DnsResolver$DnsResponse;", (void*) android_net_utils_resNetworkResult },
    { "resNetworkCancel", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_resNetworkCancel },
    { "getDnsNetwork", "()Landroid/net/Network;", (void*) android_net_utils_getDnsNetwork },
    { "setAllowNetworkingForProcess", "(Z)V", (void *)android_net_utils_setAllowNetworkingForProcess },
};
// clang-format on

int register_android_net_NetworkUtils(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, NETUTILS_PKG_NAME, gNetworkUtilMethods,
                                NELEM(gNetworkUtilMethods));
}

}; // namespace android
