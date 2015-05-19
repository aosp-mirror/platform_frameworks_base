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
#include <linux/if_ether.h>
#include <linux/if_packet.h>
#include <net/if_ether.h>
#include <netinet/ip.h>
#include <netinet/udp.h>
#include <cutils/properties.h>

#include "core_jni_helpers.h"

extern "C" {
int ifc_enable(const char *ifname);
int ifc_disable(const char *ifname);
int ifc_reset_connections(const char *ifname, int reset_mask);

int dhcp_start(const char * const ifname);
int dhcp_start_renew(const char * const ifname);
int dhcp_get_results(const char * const ifname,
                     const char *ipaddr,
                     const char *gateway,
                     uint32_t *prefixLength,
                     const char *dns[],
                     const char *server,
                     uint32_t *lease,
                     const char *vendorInfo,
                     const char *domains,
                     const char *mtu);

int dhcp_stop(const char *ifname);
int dhcp_release_lease(const char *ifname);
char *dhcp_get_errmsg();
}

#define NETUTILS_PKG_NAME "android/net/NetworkUtils"

namespace android {

static const uint16_t kDhcpClientPort = 68;

/*
 * The following remembers the jfieldID's of the fields
 * of the DhcpInfo Java object, so that we don't have
 * to look them up every time.
 */
static struct fieldIds {
    jmethodID clear;
    jmethodID setIpAddress;
    jmethodID setGateway;
    jmethodID addDns;
    jmethodID setDomains;
    jmethodID setServerAddress;
    jmethodID setLeaseDuration;
    jmethodID setVendorInfo;
} dhcpResultsFieldIds;

static jint android_net_utils_resetConnections(JNIEnv* env, jobject clazz,
      jstring ifname, jint mask)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);

    ALOGD("android_net_utils_resetConnections in env=%p clazz=%p iface=%s mask=0x%x\n",
          env, clazz, nameStr, mask);

    result = ::ifc_reset_connections(nameStr, mask);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

static jboolean android_net_utils_getDhcpResults(JNIEnv* env, jobject clazz, jstring ifname,
        jobject dhcpResults)
{
    int result;
    char  ipaddr[PROPERTY_VALUE_MAX];
    uint32_t prefixLength;
    char gateway[PROPERTY_VALUE_MAX];
    char    dns1[PROPERTY_VALUE_MAX];
    char    dns2[PROPERTY_VALUE_MAX];
    char    dns3[PROPERTY_VALUE_MAX];
    char    dns4[PROPERTY_VALUE_MAX];
    const char *dns[5] = {dns1, dns2, dns3, dns4, NULL};
    char  server[PROPERTY_VALUE_MAX];
    uint32_t lease;
    char vendorInfo[PROPERTY_VALUE_MAX];
    char domains[PROPERTY_VALUE_MAX];
    char mtu[PROPERTY_VALUE_MAX];

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    if (nameStr == NULL) return (jboolean)false;

    result = ::dhcp_get_results(nameStr, ipaddr, gateway, &prefixLength,
            dns, server, &lease, vendorInfo, domains, mtu);
    if (result != 0) {
        ALOGD("dhcp_get_results failed : %s (%s)", nameStr, ::dhcp_get_errmsg());
    }

    env->ReleaseStringUTFChars(ifname, nameStr);
    if (result == 0) {
        env->CallVoidMethod(dhcpResults, dhcpResultsFieldIds.clear);

        // set the linkAddress
        // dhcpResults->addLinkAddress(inetAddress, prefixLength)
        result = env->CallBooleanMethod(dhcpResults, dhcpResultsFieldIds.setIpAddress,
                env->NewStringUTF(ipaddr), prefixLength);
    }

    if (result == 0) {
        // set the gateway
        result = env->CallBooleanMethod(dhcpResults,
                dhcpResultsFieldIds.setGateway, env->NewStringUTF(gateway));
    }

    if (result == 0) {
        // dhcpResults->addDns(new InetAddress(dns1))
        result = env->CallBooleanMethod(dhcpResults,
                dhcpResultsFieldIds.addDns, env->NewStringUTF(dns1));
    }

    if (result == 0) {
        env->CallVoidMethod(dhcpResults, dhcpResultsFieldIds.setDomains,
                env->NewStringUTF(domains));

        result = env->CallBooleanMethod(dhcpResults,
                dhcpResultsFieldIds.addDns, env->NewStringUTF(dns2));

        if (result == 0) {
            result = env->CallBooleanMethod(dhcpResults,
                    dhcpResultsFieldIds.addDns, env->NewStringUTF(dns3));
            if (result == 0) {
                result = env->CallBooleanMethod(dhcpResults,
                        dhcpResultsFieldIds.addDns, env->NewStringUTF(dns4));
            }
        }
    }

    if (result == 0) {
        // dhcpResults->setServerAddress(new InetAddress(server))
        result = env->CallBooleanMethod(dhcpResults, dhcpResultsFieldIds.setServerAddress,
                env->NewStringUTF(server));
    }

    if (result == 0) {
        // dhcpResults->setLeaseDuration(lease)
        env->CallVoidMethod(dhcpResults,
                dhcpResultsFieldIds.setLeaseDuration, lease);

        // dhcpResults->setVendorInfo(vendorInfo)
        env->CallVoidMethod(dhcpResults, dhcpResultsFieldIds.setVendorInfo,
                env->NewStringUTF(vendorInfo));
    }
    return (jboolean)(result == 0);
}

static jboolean android_net_utils_startDhcp(JNIEnv* env, jobject clazz, jstring ifname)
{
    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    if (nameStr == NULL) return (jboolean)false;
    if (::dhcp_start(nameStr) != 0) {
        ALOGD("dhcp_start failed : %s", nameStr);
        return (jboolean)false;
    }
    return (jboolean)true;
}

static jboolean android_net_utils_startDhcpRenew(JNIEnv* env, jobject clazz, jstring ifname)
{
    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    if (nameStr == NULL) return (jboolean)false;
    if (::dhcp_start_renew(nameStr) != 0) {
        ALOGD("dhcp_start_renew failed : %s", nameStr);
        return (jboolean)false;
    }
    return (jboolean)true;
}

static jboolean android_net_utils_stopDhcp(JNIEnv* env, jobject clazz, jstring ifname)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::dhcp_stop(nameStr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jboolean)(result == 0);
}

static jboolean android_net_utils_releaseDhcpLease(JNIEnv* env, jobject clazz, jstring ifname)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::dhcp_release_lease(nameStr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jboolean)(result == 0);
}

static jstring android_net_utils_getDhcpError(JNIEnv* env, jobject clazz)
{
    return env->NewStringUTF(::dhcp_get_errmsg());
}

static void android_net_utils_attachDhcpFilter(JNIEnv *env, jobject clazz, jobject javaFd)
{
    int fd = jniGetFDFromFileDescriptor(env, javaFd);
    uint32_t ip_offset = sizeof(ether_header);
    uint32_t proto_offset = ip_offset + offsetof(iphdr, protocol);
    uint32_t flags_offset = ip_offset +  offsetof(iphdr, frag_off);
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
static JNINativeMethod gNetworkUtilMethods[] = {
    /* name, signature, funcPtr */
    { "resetConnections", "(Ljava/lang/String;I)I",  (void *)android_net_utils_resetConnections },
    { "startDhcp", "(Ljava/lang/String;)Z",  (void *)android_net_utils_startDhcp },
    { "startDhcpRenew", "(Ljava/lang/String;)Z",  (void *)android_net_utils_startDhcpRenew },
    { "getDhcpResults", "(Ljava/lang/String;Landroid/net/DhcpResults;)Z",  (void *)android_net_utils_getDhcpResults },
    { "stopDhcp", "(Ljava/lang/String;)Z",  (void *)android_net_utils_stopDhcp },
    { "releaseDhcpLease", "(Ljava/lang/String;)Z",  (void *)android_net_utils_releaseDhcpLease },
    { "getDhcpError", "()Ljava/lang/String;", (void*) android_net_utils_getDhcpError },
    { "bindProcessToNetwork", "(I)Z", (void*) android_net_utils_bindProcessToNetwork },
    { "getBoundNetworkForProcess", "()I", (void*) android_net_utils_getBoundNetworkForProcess },
    { "bindProcessToNetworkForHostResolution", "(I)Z", (void*) android_net_utils_bindProcessToNetworkForHostResolution },
    { "bindSocketToNetwork", "(II)I", (void*) android_net_utils_bindSocketToNetwork },
    { "protectFromVpn", "(I)Z", (void*)android_net_utils_protectFromVpn },
    { "queryUserAccess", "(II)Z", (void*)android_net_utils_queryUserAccess },
    { "attachDhcpFilter", "(Ljava/io/FileDescriptor;)V", (void*) android_net_utils_attachDhcpFilter },
};

int register_android_net_NetworkUtils(JNIEnv* env)
{
    jclass dhcpResultsClass = FindClassOrDie(env, "android/net/DhcpResults");

    dhcpResultsFieldIds.clear = GetMethodIDOrDie(env, dhcpResultsClass, "clear", "()V");
    dhcpResultsFieldIds.setIpAddress =GetMethodIDOrDie(env, dhcpResultsClass, "setIpAddress",
            "(Ljava/lang/String;I)Z");
    dhcpResultsFieldIds.setGateway = GetMethodIDOrDie(env, dhcpResultsClass, "setGateway",
            "(Ljava/lang/String;)Z");
    dhcpResultsFieldIds.addDns = GetMethodIDOrDie(env, dhcpResultsClass, "addDns",
            "(Ljava/lang/String;)Z");
    dhcpResultsFieldIds.setDomains = GetMethodIDOrDie(env, dhcpResultsClass, "setDomains",
            "(Ljava/lang/String;)V");
    dhcpResultsFieldIds.setServerAddress = GetMethodIDOrDie(env, dhcpResultsClass,
            "setServerAddress", "(Ljava/lang/String;)Z");
    dhcpResultsFieldIds.setLeaseDuration = GetMethodIDOrDie(env, dhcpResultsClass,
            "setLeaseDuration", "(I)V");
    dhcpResultsFieldIds.setVendorInfo = GetMethodIDOrDie(env, dhcpResultsClass, "setVendorInfo",
            "(Ljava/lang/String;)V");

    return RegisterMethodsOrDie(env, NETUTILS_PKG_NAME, gNetworkUtilMethods,
                                NELEM(gNetworkUtilMethods));
}

}; // namespace android
