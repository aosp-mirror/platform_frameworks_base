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
#include "resolv_netid.h"
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <arpa/inet.h>
#include <cutils/properties.h>

extern "C" {
int ifc_enable(const char *ifname);
int ifc_disable(const char *ifname);
int ifc_reset_connections(const char *ifname, int reset_mask);

int dhcp_do_request(const char * const ifname,
                    const char *ipaddr,
                    const char *gateway,
                    uint32_t *prefixLength,
                    const char *dns[],
                    const char *server,
                    uint32_t *lease,
                    const char *vendorInfo,
                    const char *domains,
                    const char *mtu);

int dhcp_do_request_renew(const char * const ifname,
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

/*
 * The following remembers the jfieldID's of the fields
 * of the DhcpInfo Java object, so that we don't have
 * to look them up every time.
 */
static struct fieldIds {
    jmethodID clear;
    jmethodID setInterfaceName;
    jmethodID addLinkAddress;
    jmethodID addGateway;
    jmethodID addDns;
    jmethodID setDomains;
    jmethodID setServerAddress;
    jmethodID setLeaseDuration;
    jmethodID setVendorInfo;
} dhcpResultsFieldIds;

static jint android_net_utils_enableInterface(JNIEnv* env, jobject clazz, jstring ifname)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_enable(nameStr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

static jint android_net_utils_disableInterface(JNIEnv* env, jobject clazz, jstring ifname)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_disable(nameStr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

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

static jboolean android_net_utils_runDhcpCommon(JNIEnv* env, jobject clazz, jstring ifname,
        jobject dhcpResults, bool renew)
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

    if (renew) {
        result = ::dhcp_do_request_renew(nameStr, ipaddr, gateway, &prefixLength,
                dns, server, &lease, vendorInfo, domains, mtu);
    } else {
        result = ::dhcp_do_request(nameStr, ipaddr, gateway, &prefixLength,
                dns, server, &lease, vendorInfo, domains, mtu);
    }
    if (result != 0) {
        ALOGD("dhcp_do_request failed : %s (%s)", nameStr, renew ? "renew" : "new");
    }

    env->ReleaseStringUTFChars(ifname, nameStr);
    if (result == 0) {
        env->CallVoidMethod(dhcpResults, dhcpResultsFieldIds.clear);

        // set mIfaceName
        // dhcpResults->setInterfaceName(ifname)
        env->CallVoidMethod(dhcpResults, dhcpResultsFieldIds.setInterfaceName, ifname);

        // set the linkAddress
        // dhcpResults->addLinkAddress(inetAddress, prefixLength)
        result = env->CallBooleanMethod(dhcpResults, dhcpResultsFieldIds.addLinkAddress,
                env->NewStringUTF(ipaddr), prefixLength);
    }

    if (result == 0) {
        // set the gateway
        // dhcpResults->addGateway(gateway)
        result = env->CallBooleanMethod(dhcpResults,
                dhcpResultsFieldIds.addGateway, env->NewStringUTF(gateway));
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


static jboolean android_net_utils_runDhcp(JNIEnv* env, jobject clazz, jstring ifname, jobject info)
{
    return android_net_utils_runDhcpCommon(env, clazz, ifname, info, false);
}

static jboolean android_net_utils_runDhcpRenew(JNIEnv* env, jobject clazz, jstring ifname, jobject info)
{
    return android_net_utils_runDhcpCommon(env, clazz, ifname, info, true);
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

static void android_net_utils_markSocket(JNIEnv *env, jobject thiz, jint socket, jint mark)
{
    if (setsockopt(socket, SOL_SOCKET, SO_MARK, &mark, sizeof(mark)) < 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "Error marking socket");
    }
}

static void android_net_utils_bindProcessToNetwork(JNIEnv *env, jobject thiz, jint netId)
{
    setNetworkForProcess(netId);
}

static void android_net_utils_unbindProcessToNetwork(JNIEnv *env, jobject thiz)
{
    setNetworkForProcess(NETID_UNSET);
}

static jint android_net_utils_getNetworkBoundToProcess(JNIEnv *env, jobject thiz)
{
    return getNetworkForProcess();
}

static void android_net_utils_bindProcessToNetworkForHostResolution(JNIEnv *env, jobject thiz, jint netId)
{
    setNetworkForResolv(netId);
}

static void android_net_utils_unbindProcessToNetworkForHostResolution(JNIEnv *env, jobject thiz)
{
    setNetworkForResolv(NETID_UNSET);
}

static void android_net_utils_bindSocketToNetwork(JNIEnv *env, jobject thiz, jint socket, jint netId)
{
    setNetworkForSocket(netId, socket);
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gNetworkUtilMethods[] = {
    /* name, signature, funcPtr */

    { "enableInterface", "(Ljava/lang/String;)I",  (void *)android_net_utils_enableInterface },
    { "disableInterface", "(Ljava/lang/String;)I",  (void *)android_net_utils_disableInterface },
    { "resetConnections", "(Ljava/lang/String;I)I",  (void *)android_net_utils_resetConnections },
    { "runDhcp", "(Ljava/lang/String;Landroid/net/DhcpResults;)Z",  (void *)android_net_utils_runDhcp },
    { "runDhcpRenew", "(Ljava/lang/String;Landroid/net/DhcpResults;)Z",  (void *)android_net_utils_runDhcpRenew },
    { "stopDhcp", "(Ljava/lang/String;)Z",  (void *)android_net_utils_stopDhcp },
    { "releaseDhcpLease", "(Ljava/lang/String;)Z",  (void *)android_net_utils_releaseDhcpLease },
    { "getDhcpError", "()Ljava/lang/String;", (void*) android_net_utils_getDhcpError },
    { "markSocket", "(II)V", (void*) android_net_utils_markSocket },
    { "bindProcessToNetwork", "(I)V", (void*) android_net_utils_bindProcessToNetwork },
    { "getNetworkBoundToProcess", "()I", (void*) android_net_utils_getNetworkBoundToProcess },
    { "unbindProcessToNetwork", "()V", (void*) android_net_utils_unbindProcessToNetwork },
    { "bindProcessToNetworkForHostResolution", "(I)V", (void*) android_net_utils_bindProcessToNetworkForHostResolution },
    { "unbindProcessToNetworkForHostResolution", "()V", (void*) android_net_utils_unbindProcessToNetworkForHostResolution },
    { "bindSocketToNetwork", "(II)V", (void*) android_net_utils_bindSocketToNetwork },
};

int register_android_net_NetworkUtils(JNIEnv* env)
{
    jclass dhcpResultsClass = env->FindClass("android/net/DhcpResults");
    LOG_FATAL_IF(dhcpResultsClass == NULL, "Unable to find class android/net/DhcpResults");
    dhcpResultsFieldIds.clear =
            env->GetMethodID(dhcpResultsClass, "clear", "()V");
    dhcpResultsFieldIds.setInterfaceName =
            env->GetMethodID(dhcpResultsClass, "setInterfaceName", "(Ljava/lang/String;)V");
    dhcpResultsFieldIds.addLinkAddress =
            env->GetMethodID(dhcpResultsClass, "addLinkAddress", "(Ljava/lang/String;I)Z");
    dhcpResultsFieldIds.addGateway =
            env->GetMethodID(dhcpResultsClass, "addGateway", "(Ljava/lang/String;)Z");
    dhcpResultsFieldIds.addDns =
            env->GetMethodID(dhcpResultsClass, "addDns", "(Ljava/lang/String;)Z");
    dhcpResultsFieldIds.setDomains =
            env->GetMethodID(dhcpResultsClass, "setDomains", "(Ljava/lang/String;)V");
    dhcpResultsFieldIds.setServerAddress =
            env->GetMethodID(dhcpResultsClass, "setServerAddress", "(Ljava/lang/String;)Z");
    dhcpResultsFieldIds.setLeaseDuration =
            env->GetMethodID(dhcpResultsClass, "setLeaseDuration", "(I)V");
    dhcpResultsFieldIds.setVendorInfo =
            env->GetMethodID(dhcpResultsClass, "setVendorInfo", "(Ljava/lang/String;)V");

    return AndroidRuntime::registerNativeMethods(env,
            NETUTILS_PKG_NAME, gNetworkUtilMethods, NELEM(gNetworkUtilMethods));
}

}; // namespace android
