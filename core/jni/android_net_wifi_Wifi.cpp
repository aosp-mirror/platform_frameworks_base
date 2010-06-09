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

#define LOG_TAG "wifi"

#include "jni.h"
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>

#include "wifi.h"

#define WIFI_PKG_NAME "android/net/wifi/WifiNative"

namespace android {

static jboolean sScanModeActive = false;

static int doCommand(const char *cmd, char *replybuf, int replybuflen)
{
    size_t reply_len = replybuflen - 1;

    if (::wifi_command(cmd, replybuf, &reply_len) != 0)
        return -1;
    else {
        // Strip off trailing newline
        if (reply_len > 0 && replybuf[reply_len-1] == '\n')
            replybuf[reply_len-1] = '\0';
        else
            replybuf[reply_len] = '\0';
        return 0;
    }
}

static jint doIntCommand(const char *cmd)
{
    char reply[256];

    if (doCommand(cmd, reply, sizeof(reply)) != 0) {
        return (jint)-1;
    } else {
        return (jint)atoi(reply);
    }
}

static jboolean doBooleanCommand(const char *cmd, const char *expect)
{
    char reply[256];

    if (doCommand(cmd, reply, sizeof(reply)) != 0) {
        return (jboolean)JNI_FALSE;
    } else {
        return (jboolean)(strcmp(reply, expect) == 0);
    }
}

// Send a command to the supplicant, and return the reply as a String
static jstring doStringCommand(JNIEnv *env, const char *cmd)
{
    char reply[4096];

    if (doCommand(cmd, reply, sizeof(reply)) != 0) {
        return env->NewStringUTF(NULL);
    } else {
        return env->NewStringUTF(reply);
    }
}

static jboolean android_net_wifi_loadDriver(JNIEnv* env, jobject clazz)
{
    return (jboolean)(::wifi_load_driver() == 0);
}

static jboolean android_net_wifi_unloadDriver(JNIEnv* env, jobject clazz)
{
    return (jboolean)(::wifi_unload_driver() == 0);
}

static jboolean android_net_wifi_startSupplicant(JNIEnv* env, jobject clazz)
{
    return (jboolean)(::wifi_start_supplicant() == 0);
}

static jboolean android_net_wifi_stopSupplicant(JNIEnv* env, jobject clazz)
{
    return (jboolean)(::wifi_stop_supplicant() == 0);
}

static jboolean android_net_wifi_connectToSupplicant(JNIEnv* env, jobject clazz)
{
    return (jboolean)(::wifi_connect_to_supplicant() == 0);
}

static void android_net_wifi_closeSupplicantConnection(JNIEnv* env, jobject clazz)
{
    ::wifi_close_supplicant_connection();
}

static jstring android_net_wifi_waitForEvent(JNIEnv* env, jobject clazz)
{
    char buf[256];

    int nread = ::wifi_wait_for_event(buf, sizeof buf);
    if (nread > 0) {
        return env->NewStringUTF(buf);
    } else {
        return  env->NewStringUTF(NULL);
    }
}

static jstring android_net_wifi_listNetworksCommand(JNIEnv* env, jobject clazz)
{
    return doStringCommand(env, "LIST_NETWORKS");
}

static jint android_net_wifi_addNetworkCommand(JNIEnv* env, jobject clazz)
{
    return doIntCommand("ADD_NETWORK");
}

static jboolean android_net_wifi_setNetworkVariableCommand(JNIEnv* env,
                                                           jobject clazz,
                                                           jint netId,
                                                           jstring name,
                                                           jstring value)
{
    char cmdstr[256];
    jboolean isCopy;

    const char *nameStr = env->GetStringUTFChars(name, &isCopy);
    const char *valueStr = env->GetStringUTFChars(value, &isCopy);

    if (nameStr == NULL || valueStr == NULL)
        return JNI_FALSE;

    int cmdTooLong = snprintf(cmdstr, sizeof(cmdstr), "SET_NETWORK %d %s %s",
                 netId, nameStr, valueStr) >= (int)sizeof(cmdstr);

    env->ReleaseStringUTFChars(name, nameStr);
    env->ReleaseStringUTFChars(value, valueStr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jstring android_net_wifi_getNetworkVariableCommand(JNIEnv* env,
                                                          jobject clazz,
                                                          jint netId,
                                                          jstring name)
{
    char cmdstr[256];
    jboolean isCopy;

    const char *nameStr = env->GetStringUTFChars(name, &isCopy);

    if (nameStr == NULL)
        return env->NewStringUTF(NULL);

    int cmdTooLong = snprintf(cmdstr, sizeof(cmdstr), "GET_NETWORK %d %s",
                             netId, nameStr) >= (int)sizeof(cmdstr);

    env->ReleaseStringUTFChars(name, nameStr);

    return cmdTooLong ? env->NewStringUTF(NULL) : doStringCommand(env, cmdstr);
}

static jboolean android_net_wifi_removeNetworkCommand(JNIEnv* env, jobject clazz, jint netId)
{
    char cmdstr[256];

    int numWritten = snprintf(cmdstr, sizeof(cmdstr), "REMOVE_NETWORK %d", netId);
    int cmdTooLong = numWritten >= (int)sizeof(cmdstr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jboolean android_net_wifi_enableNetworkCommand(JNIEnv* env,
                                                  jobject clazz,
                                                  jint netId,
                                                  jboolean disableOthers)
{
    char cmdstr[256];
    const char *cmd = disableOthers ? "SELECT_NETWORK" : "ENABLE_NETWORK";

    int numWritten = snprintf(cmdstr, sizeof(cmdstr), "%s %d", cmd, netId);
    int cmdTooLong = numWritten >= (int)sizeof(cmdstr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jboolean android_net_wifi_disableNetworkCommand(JNIEnv* env, jobject clazz, jint netId)
{
    char cmdstr[256];

    int numWritten = snprintf(cmdstr, sizeof(cmdstr), "DISABLE_NETWORK %d", netId);
    int cmdTooLong = numWritten >= (int)sizeof(cmdstr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jstring android_net_wifi_statusCommand(JNIEnv* env, jobject clazz)
{
    return doStringCommand(env, "STATUS");
}

static jboolean android_net_wifi_pingCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("PING", "PONG");
}

static jstring android_net_wifi_scanResultsCommand(JNIEnv* env, jobject clazz)
{
    return doStringCommand(env, "SCAN_RESULTS");
}

static jboolean android_net_wifi_disconnectCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DISCONNECT", "OK");
}

static jboolean android_net_wifi_reconnectCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("RECONNECT", "OK");
}
static jboolean android_net_wifi_reassociateCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("REASSOCIATE", "OK");
}

static jboolean doSetScanMode(jboolean setActive)
{
    return doBooleanCommand((setActive ? "DRIVER SCAN-ACTIVE" : "DRIVER SCAN-PASSIVE"), "OK");
}

static jboolean android_net_wifi_scanCommand(JNIEnv* env, jobject clazz, jboolean forceActive)
{
    jboolean result;

    // Ignore any error from setting the scan mode.
    // The scan will still work.
    if (forceActive && !sScanModeActive)
        doSetScanMode(true);
    result = doBooleanCommand("SCAN", "OK");
    if (forceActive && !sScanModeActive)
        doSetScanMode(sScanModeActive);
    return result;
}

static jboolean android_net_wifi_setScanModeCommand(JNIEnv* env, jobject clazz, jboolean setActive)
{
    sScanModeActive = setActive;
    return doSetScanMode(setActive);
}

static jboolean android_net_wifi_startDriverCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DRIVER START", "OK");
}

static jboolean android_net_wifi_stopDriverCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DRIVER STOP", "OK");
}

static jboolean android_net_wifi_startPacketFiltering(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("DRIVER RXFILTER-ADD 0", "OK")
	&& doBooleanCommand("DRIVER RXFILTER-ADD 1", "OK")
	&& doBooleanCommand("DRIVER RXFILTER-ADD 3", "OK")
	&& doBooleanCommand("DRIVER RXFILTER-START", "OK");
}

static jboolean android_net_wifi_stopPacketFiltering(JNIEnv* env, jobject clazz)
{
    jboolean result = doBooleanCommand("DRIVER RXFILTER-STOP", "OK");
    if (result) {
	(void)doBooleanCommand("DRIVER RXFILTER-REMOVE 3", "OK");
	(void)doBooleanCommand("DRIVER RXFILTER-REMOVE 1", "OK");
	(void)doBooleanCommand("DRIVER RXFILTER-REMOVE 0", "OK");
    }

    return result;
}

static jint android_net_wifi_getRssiHelper(const char *cmd)
{
    char reply[256];
    int rssi = -200;

    if (doCommand(cmd, reply, sizeof(reply)) != 0) {
        return (jint)-1;
    }

    // reply comes back in the form "<SSID> rssi XX" where XX is the
    // number we're interested in.  if we're associating, it returns "OK".
    // beware - <SSID> can contain spaces.
    if (strcmp(reply, "OK") != 0) {
        // beware of trailing spaces
        char* end = reply + strlen(reply);
        while (end > reply && end[-1] == ' ') {
            end--;
        }
        *end = 0;

        char* lastSpace = strrchr(reply, ' ');
        // lastSpace should be preceded by "rssi" and followed by the value
        if (lastSpace && !strncmp(lastSpace - 4, "rssi", 4)) {
            sscanf(lastSpace + 1, "%d", &rssi);
        }
    }
    return (jint)rssi;
}

static jint android_net_wifi_getRssiCommand(JNIEnv* env, jobject clazz)
{
    return android_net_wifi_getRssiHelper("DRIVER RSSI");
}

static jint android_net_wifi_getRssiApproxCommand(JNIEnv* env, jobject clazz)
{
    return android_net_wifi_getRssiHelper("DRIVER RSSI-APPROX");
}

static jint android_net_wifi_getLinkSpeedCommand(JNIEnv* env, jobject clazz)
{
    char reply[256];
    int linkspeed;

    if (doCommand("DRIVER LINKSPEED", reply, sizeof(reply)) != 0) {
        return (jint)-1;
    }
    // reply comes back in the form "LinkSpeed XX" where XX is the
    // number we're interested in.
    sscanf(reply, "%*s %u", &linkspeed);
    return (jint)linkspeed;
}

static jstring android_net_wifi_getMacAddressCommand(JNIEnv* env, jobject clazz)
{
    char reply[256];
    char buf[256];

    if (doCommand("DRIVER MACADDR", reply, sizeof(reply)) != 0) {
        return env->NewStringUTF(NULL);
    }
    // reply comes back in the form "Macaddr = XX.XX.XX.XX.XX.XX" where XX
    // is the part of the string we're interested in.
    if (sscanf(reply, "%*s = %255s", buf) == 1)
        return env->NewStringUTF(buf);
    else
        return env->NewStringUTF(NULL);
}

static jboolean android_net_wifi_setPowerModeCommand(JNIEnv* env, jobject clazz, jint mode)
{
    char cmdstr[256];

    int numWritten = snprintf(cmdstr, sizeof(cmdstr), "DRIVER POWERMODE %d", mode);
    int cmdTooLong = numWritten >= (int)sizeof(cmdstr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jint android_net_wifi_getPowerModeCommand(JNIEnv* env, jobject clazz)
{
    char reply[256];
    int power;

    if (doCommand("DRIVER GETPOWER", reply, sizeof(reply)) != 0) {
        return (jint)-1;
    }
    // reply comes back in the form "powermode = XX" where XX is the
    // number we're interested in.
    sscanf(reply, "%*s = %u", &power);
    return (jint)power;
}

static jboolean android_net_wifi_setNumAllowedChannelsCommand(JNIEnv* env, jobject clazz, jint numChannels)
{
    char cmdstr[256];

    int numWritten = snprintf(cmdstr, sizeof(cmdstr), "DRIVER SCAN-CHANNELS %u", numChannels);
    int cmdTooLong = numWritten >= (int)sizeof(cmdstr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jint android_net_wifi_getNumAllowedChannelsCommand(JNIEnv* env, jobject clazz)
{
    char reply[256];
    int numChannels;

    if (doCommand("DRIVER SCAN-CHANNELS", reply, sizeof(reply)) != 0) {
        return -1;
    }
    // reply comes back in the form "Scan-Channels = X" where X is the
    // number of channels
    if (sscanf(reply, "%*s = %u", &numChannels) == 1)
        return numChannels;
    else
        return -1;
}

static jboolean android_net_wifi_setBluetoothCoexistenceModeCommand(JNIEnv* env, jobject clazz, jint mode)
{
    char cmdstr[256];

    int numWritten = snprintf(cmdstr, sizeof(cmdstr), "DRIVER BTCOEXMODE %d", mode);
    int cmdTooLong = numWritten >= (int)sizeof(cmdstr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jboolean android_net_wifi_setBluetoothCoexistenceScanModeCommand(JNIEnv* env, jobject clazz, jboolean setCoexScanMode)
{
    char cmdstr[256];

    int numWritten = snprintf(cmdstr, sizeof(cmdstr), "DRIVER BTCOEXSCAN-%s", setCoexScanMode ? "START" : "STOP");
    int cmdTooLong = numWritten >= (int)sizeof(cmdstr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jboolean android_net_wifi_saveConfigCommand(JNIEnv* env, jobject clazz)
{
    // Make sure we never write out a value for AP_SCAN other than 1
    (void)doBooleanCommand("AP_SCAN 1", "OK");
    return doBooleanCommand("SAVE_CONFIG", "OK");
}

static jboolean android_net_wifi_reloadConfigCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("RECONFIGURE", "OK");
}

static jboolean android_net_wifi_setScanResultHandlingCommand(JNIEnv* env, jobject clazz, jint mode)
{
    char cmdstr[256];

    int numWritten = snprintf(cmdstr, sizeof(cmdstr), "AP_SCAN %d", mode);
    int cmdTooLong = numWritten >= (int)sizeof(cmdstr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jboolean android_net_wifi_addToBlacklistCommand(JNIEnv* env, jobject clazz, jstring bssid)
{
    char cmdstr[256];
    jboolean isCopy;

    const char *bssidStr = env->GetStringUTFChars(bssid, &isCopy);

    int cmdTooLong = snprintf(cmdstr, sizeof(cmdstr), "BLACKLIST %s", bssidStr) >= (int)sizeof(cmdstr);

    env->ReleaseStringUTFChars(bssid, bssidStr);

    return (jboolean)!cmdTooLong && doBooleanCommand(cmdstr, "OK");
}

static jboolean android_net_wifi_clearBlacklistCommand(JNIEnv* env, jobject clazz)
{
    return doBooleanCommand("BLACKLIST clear", "OK");
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gWifiMethods[] = {
    /* name, signature, funcPtr */

    { "loadDriver", "()Z",  (void *)android_net_wifi_loadDriver },
    { "unloadDriver", "()Z",  (void *)android_net_wifi_unloadDriver },
    { "startSupplicant", "()Z",  (void *)android_net_wifi_startSupplicant },
    { "stopSupplicant", "()Z",  (void *)android_net_wifi_stopSupplicant },
    { "connectToSupplicant", "()Z",  (void *)android_net_wifi_connectToSupplicant },
    { "closeSupplicantConnection", "()V",  (void *)android_net_wifi_closeSupplicantConnection },

    { "listNetworksCommand", "()Ljava/lang/String;",
        (void*) android_net_wifi_listNetworksCommand },
    { "addNetworkCommand", "()I", (void*) android_net_wifi_addNetworkCommand },
    { "setNetworkVariableCommand", "(ILjava/lang/String;Ljava/lang/String;)Z",
        (void*) android_net_wifi_setNetworkVariableCommand },
    { "getNetworkVariableCommand", "(ILjava/lang/String;)Ljava/lang/String;",
        (void*) android_net_wifi_getNetworkVariableCommand },
    { "removeNetworkCommand", "(I)Z", (void*) android_net_wifi_removeNetworkCommand },
    { "enableNetworkCommand", "(IZ)Z", (void*) android_net_wifi_enableNetworkCommand },
    { "disableNetworkCommand", "(I)Z", (void*) android_net_wifi_disableNetworkCommand },
    { "waitForEvent", "()Ljava/lang/String;", (void*) android_net_wifi_waitForEvent },
    { "statusCommand", "()Ljava/lang/String;", (void*) android_net_wifi_statusCommand },
    { "scanResultsCommand", "()Ljava/lang/String;", (void*) android_net_wifi_scanResultsCommand },
    { "pingCommand", "()Z",  (void *)android_net_wifi_pingCommand },
    { "disconnectCommand", "()Z",  (void *)android_net_wifi_disconnectCommand },
    { "reconnectCommand", "()Z",  (void *)android_net_wifi_reconnectCommand },
    { "reassociateCommand", "()Z",  (void *)android_net_wifi_reassociateCommand },
    { "scanCommand", "(Z)Z", (void*) android_net_wifi_scanCommand },
    { "setScanModeCommand", "(Z)Z", (void*) android_net_wifi_setScanModeCommand },
    { "startDriverCommand", "()Z", (void*) android_net_wifi_startDriverCommand },
    { "stopDriverCommand", "()Z", (void*) android_net_wifi_stopDriverCommand },
    { "startPacketFiltering", "()Z", (void*) android_net_wifi_startPacketFiltering },
    { "stopPacketFiltering", "()Z", (void*) android_net_wifi_stopPacketFiltering },
    { "setPowerModeCommand", "(I)Z", (void*) android_net_wifi_setPowerModeCommand },
    { "getPowerModeCommand", "()I", (void*) android_net_wifi_getPowerModeCommand },
    { "setNumAllowedChannelsCommand", "(I)Z", (void*) android_net_wifi_setNumAllowedChannelsCommand },
    { "getNumAllowedChannelsCommand", "()I", (void*) android_net_wifi_getNumAllowedChannelsCommand },
    { "setBluetoothCoexistenceModeCommand", "(I)Z",
    		(void*) android_net_wifi_setBluetoothCoexistenceModeCommand },
    { "setBluetoothCoexistenceScanModeCommand", "(Z)Z",
    		(void*) android_net_wifi_setBluetoothCoexistenceScanModeCommand },
    { "getRssiCommand", "()I", (void*) android_net_wifi_getRssiCommand },
    { "getRssiApproxCommand", "()I",
            (void*) android_net_wifi_getRssiApproxCommand},
    { "getLinkSpeedCommand", "()I", (void*) android_net_wifi_getLinkSpeedCommand },
    { "getMacAddressCommand", "()Ljava/lang/String;", (void*) android_net_wifi_getMacAddressCommand },
    { "saveConfigCommand", "()Z", (void*) android_net_wifi_saveConfigCommand },
    { "reloadConfigCommand", "()Z", (void*) android_net_wifi_reloadConfigCommand },
    { "setScanResultHandlingCommand", "(I)Z", (void*) android_net_wifi_setScanResultHandlingCommand },
    { "addToBlacklistCommand", "(Ljava/lang/String;)Z", (void*) android_net_wifi_addToBlacklistCommand },
    { "clearBlacklistCommand", "()Z", (void*) android_net_wifi_clearBlacklistCommand },
};

int register_android_net_wifi_WifiManager(JNIEnv* env)
{
    jclass wifi = env->FindClass(WIFI_PKG_NAME);
    LOG_FATAL_IF(wifi == NULL, "Unable to find class " WIFI_PKG_NAME);

    return AndroidRuntime::registerNativeMethods(env,
            WIFI_PKG_NAME, gWifiMethods, NELEM(gWifiMethods));
}

}; // namespace android
