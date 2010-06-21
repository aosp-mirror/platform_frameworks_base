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
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <arpa/inet.h>

#include <netutils/ifc.h>
#include <netutils/dhcp.h>

#define NETUTILS_PKG_NAME "android/net/NetworkUtils"

namespace android {

/*
 * The following remembers the jfieldID's of the fields
 * of the DhcpInfo Java object, so that we don't have
 * to look them up every time.
 */
static struct fieldIds {
    jclass dhcpInfoClass;
    jmethodID constructorId;
    jfieldID ipaddress;
    jfieldID gateway;
    jfieldID netmask;
    jfieldID dns1;
    jfieldID dns2;
    jfieldID serverAddress;
    jfieldID leaseDuration;
} dhcpInfoFieldIds;

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

static jint android_net_utils_addHostRoute(JNIEnv* env, jobject clazz, jstring ifname, jint addr)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_add_host_route(nameStr, addr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

static jint android_net_utils_removeHostRoutes(JNIEnv* env, jobject clazz, jstring ifname)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_remove_host_routes(nameStr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

static jint android_net_utils_setDefaultRoute(JNIEnv* env, jobject clazz, jstring ifname, jint gateway)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_set_default_route(nameStr, gateway);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

static jint android_net_utils_getDefaultRoute(JNIEnv* env, jobject clazz, jstring ifname)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_get_default_route(nameStr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

static jint android_net_utils_removeDefaultRoute(JNIEnv* env, jobject clazz, jstring ifname)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_remove_default_route(nameStr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

static jint android_net_utils_resetConnections(JNIEnv* env, jobject clazz, jstring ifname)
{
    int result;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_reset_connections(nameStr);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jint)result;
}

static jboolean android_net_utils_runDhcp(JNIEnv* env, jobject clazz, jstring ifname, jobject info)
{
    int result;
    in_addr_t ipaddr, gateway, mask, dns1, dns2, server;
    uint32_t lease;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::dhcp_do_request(nameStr, &ipaddr, &gateway, &mask,
                                        &dns1, &dns2, &server, &lease);
    env->ReleaseStringUTFChars(ifname, nameStr);
    if (result == 0 && dhcpInfoFieldIds.dhcpInfoClass != NULL) {
        env->SetIntField(info, dhcpInfoFieldIds.ipaddress, ipaddr);
        env->SetIntField(info, dhcpInfoFieldIds.gateway, gateway);
        env->SetIntField(info, dhcpInfoFieldIds.netmask, mask);
        env->SetIntField(info, dhcpInfoFieldIds.dns1, dns1);
        env->SetIntField(info, dhcpInfoFieldIds.dns2, dns2);
        env->SetIntField(info, dhcpInfoFieldIds.serverAddress, server);
        env->SetIntField(info, dhcpInfoFieldIds.leaseDuration, lease);
    }
    return (jboolean)(result == 0);
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

static jboolean android_net_utils_configureInterface(JNIEnv* env,
        jobject clazz,
        jstring ifname,
        jint ipaddr,
        jint mask,
        jint gateway,
        jint dns1,
        jint dns2)
{
    int result;
    uint32_t lease;

    const char *nameStr = env->GetStringUTFChars(ifname, NULL);
    result = ::ifc_configure(nameStr, ipaddr, mask, gateway, dns1, dns2);
    env->ReleaseStringUTFChars(ifname, nameStr);
    return (jboolean)(result == 0);
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gNetworkUtilMethods[] = {
    /* name, signature, funcPtr */

    { "enableInterface", "(Ljava/lang/String;)I",  (void *)android_net_utils_enableInterface },
    { "disableInterface", "(Ljava/lang/String;)I",  (void *)android_net_utils_disableInterface },
    { "addHostRoute", "(Ljava/lang/String;I)I",  (void *)android_net_utils_addHostRoute },
    { "removeHostRoutes", "(Ljava/lang/String;)I",  (void *)android_net_utils_removeHostRoutes },
    { "setDefaultRoute", "(Ljava/lang/String;I)I",  (void *)android_net_utils_setDefaultRoute },
    { "getDefaultRoute", "(Ljava/lang/String;)I",  (void *)android_net_utils_getDefaultRoute },
    { "removeDefaultRoute", "(Ljava/lang/String;)I",  (void *)android_net_utils_removeDefaultRoute },
    { "resetConnections", "(Ljava/lang/String;)I",  (void *)android_net_utils_resetConnections },
    { "runDhcp", "(Ljava/lang/String;Landroid/net/DhcpInfo;)Z",  (void *)android_net_utils_runDhcp },
    { "stopDhcp", "(Ljava/lang/String;)Z",  (void *)android_net_utils_stopDhcp },
    { "releaseDhcpLease", "(Ljava/lang/String;)Z",  (void *)android_net_utils_releaseDhcpLease },
    { "configureNative", "(Ljava/lang/String;IIIII)Z",  (void *)android_net_utils_configureInterface },
    { "getDhcpError", "()Ljava/lang/String;", (void*) android_net_utils_getDhcpError },
};

int register_android_net_NetworkUtils(JNIEnv* env)
{
    jclass netutils = env->FindClass(NETUTILS_PKG_NAME);
    LOG_FATAL_IF(netutils == NULL, "Unable to find class " NETUTILS_PKG_NAME);

    dhcpInfoFieldIds.dhcpInfoClass = env->FindClass("android/net/DhcpInfo");
    if (dhcpInfoFieldIds.dhcpInfoClass != NULL) {
        dhcpInfoFieldIds.constructorId = env->GetMethodID(dhcpInfoFieldIds.dhcpInfoClass, "<init>", "()V");
        dhcpInfoFieldIds.ipaddress = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "ipAddress", "I");
        dhcpInfoFieldIds.gateway = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "gateway", "I");
        dhcpInfoFieldIds.netmask = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "netmask", "I");
        dhcpInfoFieldIds.dns1 = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "dns1", "I");
        dhcpInfoFieldIds.dns2 = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "dns2", "I");
        dhcpInfoFieldIds.serverAddress = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "serverAddress", "I");
        dhcpInfoFieldIds.leaseDuration = env->GetFieldID(dhcpInfoFieldIds.dhcpInfoClass, "leaseDuration", "I");
    }

    return AndroidRuntime::registerNativeMethods(env,
            NETUTILS_PKG_NAME, gNetworkUtilMethods, NELEM(gNetworkUtilMethods));
}

}; // namespace android
