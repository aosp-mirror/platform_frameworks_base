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
#include <ScopedUtfChars.h>
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/String16.h>

#include "wifi.h"

#define WIFI_PKG_NAME "android/net/wifi/WifiNative"
#define BUF_SIZE 256

//TODO: This file can be refactored to push a lot of the functionality to java
//with just a few JNI calls - doBoolean/doInt/doString

namespace android {

static jboolean sScanModeActive = false;
static jint DBG = false;

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

static jint doIntCommand(const char* fmt, ...)
{
    char buf[BUF_SIZE];
    va_list args;
    va_start(args, fmt);
    int byteCount = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (byteCount < 0 || byteCount >= BUF_SIZE) {
        return -1;
    }
    char reply[BUF_SIZE];
    if (doCommand(buf, reply, sizeof(reply)) != 0) {
        return -1;
    }
    return static_cast<jint>(atoi(reply));
}

static jboolean doBooleanCommand(const char* expect, const char* fmt, ...)
{
    char buf[BUF_SIZE];
    va_list args;
    va_start(args, fmt);
    int byteCount = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (byteCount < 0 || byteCount >= BUF_SIZE) {
        return JNI_FALSE;
    }
    char reply[BUF_SIZE];
    if (doCommand(buf, reply, sizeof(reply)) != 0) {
        return JNI_FALSE;
    }
    return (strcmp(reply, expect) == 0);
}

// Send a command to the supplicant, and return the reply as a String
static jstring doStringCommand(JNIEnv* env, const char* fmt, ...) {
    char buf[BUF_SIZE];
    va_list args;
    va_start(args, fmt);
    int byteCount = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (byteCount < 0 || byteCount >= BUF_SIZE) {
        return NULL;
    }
    char reply[4096];
    if (doCommand(buf, reply, sizeof(reply)) != 0) {
        return NULL;
    }
    // TODO: why not just NewStringUTF?
    String16 str((char *)reply);
    return env->NewString((const jchar *)str.string(), str.size());
}

static jboolean android_net_wifi_isDriverLoaded(JNIEnv* env, jobject)
{
    return (jboolean)(::is_wifi_driver_loaded() == 1);
}

static jboolean android_net_wifi_loadDriver(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_load_driver() == 0);
}

static jboolean android_net_wifi_unloadDriver(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_unload_driver() == 0);
}

static jboolean android_net_wifi_startSupplicant(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_start_supplicant() == 0);
}

static jboolean android_net_wifi_startP2pSupplicant(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_start_p2p_supplicant() == 0);
}

static jboolean android_net_wifi_stopSupplicant(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "TERMINATE");
}

static jboolean android_net_wifi_killSupplicant(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_stop_supplicant() == 0);
}

static jboolean android_net_wifi_connectToSupplicant(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_connect_to_supplicant() == 0);
}

static void android_net_wifi_closeSupplicantConnection(JNIEnv* env, jobject)
{
    ::wifi_close_supplicant_connection();
}

static jstring android_net_wifi_waitForEvent(JNIEnv* env, jobject)
{
    char buf[BUF_SIZE];

    int nread = ::wifi_wait_for_event(buf, sizeof buf);
    if (nread > 0) {
        return env->NewStringUTF(buf);
    } else {
        return NULL;
    }
}

static jstring android_net_wifi_listNetworksCommand(JNIEnv* env, jobject)
{
    return doStringCommand(env, "LIST_NETWORKS");
}

static jint android_net_wifi_addNetworkCommand(JNIEnv* env, jobject)
{
    return doIntCommand("ADD_NETWORK");
}

static jboolean android_net_wifi_wpsPbcCommand(JNIEnv* env, jobject, jstring javaBssid)
{
    ScopedUtfChars bssid(env, javaBssid);
    if (bssid.c_str() == NULL) {
        return JNI_FALSE;
    }
    return doBooleanCommand("OK", "WPS_PBC %s", bssid.c_str());
}

static jboolean android_net_wifi_wpsPinFromAccessPointCommand(JNIEnv* env, jobject,
        jstring javaBssid, jstring javaApPin)
{
    ScopedUtfChars bssid(env, javaBssid);
    if (bssid.c_str() == NULL) {
        return JNI_FALSE;
    }
    ScopedUtfChars apPin(env, javaApPin);
    if (apPin.c_str() == NULL) {
        return JNI_FALSE;
    }
    return doBooleanCommand("OK", "WPS_REG %s %s", bssid.c_str(), apPin.c_str());
}

static jstring android_net_wifi_wpsPinFromDeviceCommand(JNIEnv* env, jobject, jstring javaBssid)
{
    ScopedUtfChars bssid(env, javaBssid);
    if (bssid.c_str() == NULL) {
        return NULL;
    }
    return doStringCommand(env, "WPS_PIN %s", bssid.c_str());
}

static jboolean android_net_wifi_setCountryCodeCommand(JNIEnv* env, jobject, jstring javaCountry)
{
    ScopedUtfChars country(env, javaCountry);
    if (country.c_str() == NULL) {
        return JNI_FALSE;
    }
    return doBooleanCommand("OK", "DRIVER COUNTRY %s", country.c_str());
}

static jboolean android_net_wifi_setNetworkVariableCommand(JNIEnv* env,
                                                           jobject,
                                                           jint netId,
                                                           jstring javaName,
                                                           jstring javaValue)
{
    ScopedUtfChars name(env, javaName);
    if (name.c_str() == NULL) {
        return JNI_FALSE;
    }
    ScopedUtfChars value(env, javaValue);
    if (value.c_str() == NULL) {
        return JNI_FALSE;
    }
    return doBooleanCommand("OK", "SET_NETWORK %d %s %s", netId, name.c_str(), value.c_str());
}

static jstring android_net_wifi_getNetworkVariableCommand(JNIEnv* env,
                                                          jobject,
                                                          jint netId,
                                                          jstring javaName)
{
    ScopedUtfChars name(env, javaName);
    if (name.c_str() == NULL) {
        return NULL;
    }
    return doStringCommand(env, "GET_NETWORK %d %s", netId, name.c_str());
}

static jboolean android_net_wifi_removeNetworkCommand(JNIEnv* env, jobject, jint netId)
{
    return doBooleanCommand("OK", "REMOVE_NETWORK %d", netId);
}

static jboolean android_net_wifi_enableNetworkCommand(JNIEnv* env,
                                                  jobject,
                                                  jint netId,
                                                  jboolean disableOthers)
{
    return doBooleanCommand("OK", "%s_NETWORK %d", disableOthers ? "SELECT" : "ENABLE", netId);
}

static jboolean android_net_wifi_disableNetworkCommand(JNIEnv* env, jobject, jint netId)
{
    return doBooleanCommand("OK", "DISABLE_NETWORK %d", netId);
}

static jstring android_net_wifi_statusCommand(JNIEnv* env, jobject)
{
    return doStringCommand(env, "STATUS");
}

static jboolean android_net_wifi_pingCommand(JNIEnv* env, jobject)
{
    return doBooleanCommand("PONG", "PING");
}

static jstring android_net_wifi_scanResultsCommand(JNIEnv* env, jobject)
{
    return doStringCommand(env, "SCAN_RESULTS");
}

static jboolean android_net_wifi_disconnectCommand(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "DISCONNECT");
}

static jboolean android_net_wifi_reconnectCommand(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "RECONNECT");
}
static jboolean android_net_wifi_reassociateCommand(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "REASSOCIATE");
}

static jboolean doSetScanMode(jboolean setActive)
{
    return doBooleanCommand("OK", (setActive ? "DRIVER SCAN-ACTIVE" : "DRIVER SCAN-PASSIVE"));
}

static jboolean android_net_wifi_scanCommand(JNIEnv* env, jobject, jboolean forceActive)
{
    jboolean result;

    // Ignore any error from setting the scan mode.
    // The scan will still work.
    if (forceActive && !sScanModeActive)
        doSetScanMode(true);
    result = doBooleanCommand("OK", "SCAN");
    if (forceActive && !sScanModeActive)
        doSetScanMode(sScanModeActive);
    return result;
}

static jboolean android_net_wifi_setScanModeCommand(JNIEnv* env, jobject, jboolean setActive)
{
    sScanModeActive = setActive;
    return doSetScanMode(setActive);
}

static jboolean android_net_wifi_startDriverCommand(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "DRIVER START");
}

static jboolean android_net_wifi_stopDriverCommand(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "DRIVER STOP");
}

/*
    Multicast filtering rules work as follows:

    The driver can filter multicast (v4 and/or v6) and broadcast packets when in
    a power optimized mode (typically when screen goes off).

    In order to prevent the driver from filtering the multicast/broadcast packets, we have to
    add a DRIVER RXFILTER-ADD rule followed by DRIVER RXFILTER-START to make the rule effective

    DRIVER RXFILTER-ADD Num
        where Num = 0 - Unicast, 1 - Broadcast, 2 - Mutil4 or 3 - Multi6

    and DRIVER RXFILTER-START

    In order to stop the usage of these rules, we do

    DRIVER RXFILTER-STOP
    DRIVER RXFILTER-REMOVE Num
        where Num is as described for RXFILTER-ADD

    The  SETSUSPENDOPT driver command overrides the filtering rules
*/

static jboolean android_net_wifi_startMultiV4Filtering(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "DRIVER RXFILTER-STOP")
            && doBooleanCommand("OK", "DRIVER RXFILTER-REMOVE 2")
            && doBooleanCommand("OK", "DRIVER RXFILTER-START");
}

static jboolean android_net_wifi_stopMultiV4Filtering(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "DRIVER RXFILTER-ADD 2")
            && doBooleanCommand("OK", "DRIVER RXFILTER-START");
}

static jboolean android_net_wifi_startMultiV6Filtering(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "DRIVER RXFILTER-STOP")
            && doBooleanCommand("OK", "DRIVER RXFILTER-REMOVE 3")
            && doBooleanCommand("OK", "DRIVER RXFILTER-START");
}

static jboolean android_net_wifi_stopMultiV6Filtering(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "DRIVER RXFILTER-ADD 3")
        && doBooleanCommand("OK", "DRIVER RXFILTER-START");
}


static jint android_net_wifi_getRssiHelper(const char *cmd)
{
    char reply[BUF_SIZE];
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
        if (lastSpace && !strncasecmp(lastSpace - 4, "rssi", 4)) {
            sscanf(lastSpace + 1, "%d", &rssi);
        }
    }
    return (jint)rssi;
}

static jstring android_net_wifi_getMacAddressCommand(JNIEnv* env, jobject)
{
    char reply[BUF_SIZE];
    char buf[BUF_SIZE];

    if (doCommand("DRIVER MACADDR", reply, sizeof(reply)) != 0) {
        return NULL;
    }
    // reply comes back in the form "Macaddr = XX.XX.XX.XX.XX.XX" where XX
    // is the part of the string we're interested in.
    if (sscanf(reply, "%*s = %255s", buf) == 1) {
        return env->NewStringUTF(buf);
    }
    return NULL;
}

static jboolean android_net_wifi_setPowerModeCommand(JNIEnv* env, jobject, jint mode)
{
    return doBooleanCommand("OK", "DRIVER POWERMODE %d", mode);
}

static jint android_net_wifi_getPowerModeCommand(JNIEnv* env, jobject)
{
    char reply[BUF_SIZE];
    int power;

    if (doCommand("DRIVER GETPOWER", reply, sizeof(reply)) != 0) {
        return (jint)-1;
    }
    // reply comes back in the form "powermode = XX" where XX is the
    // number we're interested in.
    if (sscanf(reply, "%*s = %u", &power) != 1) {
        return (jint)-1;
    }
    return (jint)power;
}

static jboolean android_net_wifi_setBandCommand(JNIEnv* env, jobject, jint band)
{
    return doBooleanCommand("OK", "DRIVER SETBAND %d", band);
}

static jint android_net_wifi_getBandCommand(JNIEnv* env, jobject)
{
    char reply[25];
    int band;

    if (doCommand("DRIVER GETBAND", reply, sizeof(reply)) != 0) {
        return (jint)-1;
    }
    // reply comes back in the form "Band X" where X is the
    // number we're interested in.
    sscanf(reply, "%*s %u", &band);
    return (jint)band;
}

static jboolean android_net_wifi_setBluetoothCoexistenceModeCommand(JNIEnv* env, jobject, jint mode)
{
    return doBooleanCommand("OK", "DRIVER BTCOEXMODE %d", mode);
}

static jboolean android_net_wifi_setBluetoothCoexistenceScanModeCommand(JNIEnv* env, jobject, jboolean setCoexScanMode)
{
    return doBooleanCommand("OK", "DRIVER BTCOEXSCAN-%s", setCoexScanMode ? "START" : "STOP");
}

static jboolean android_net_wifi_saveConfigCommand(JNIEnv* env, jobject)
{
    // Make sure we never write out a value for AP_SCAN other than 1
    (void)doBooleanCommand("OK", "AP_SCAN 1");
    return doBooleanCommand("OK", "SAVE_CONFIG");
}

static jboolean android_net_wifi_reloadConfigCommand(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "RECONFIGURE");
}

static jboolean android_net_wifi_setScanResultHandlingCommand(JNIEnv* env, jobject, jint mode)
{
    return doBooleanCommand("OK", "AP_SCAN %d", mode);
}

static jboolean android_net_wifi_addToBlacklistCommand(JNIEnv* env, jobject, jstring javaBssid)
{
    ScopedUtfChars bssid(env, javaBssid);
    if (bssid.c_str() == NULL) {
        return JNI_FALSE;
    }
    return doBooleanCommand("OK", "BLACKLIST %s", bssid.c_str());
}

static jboolean android_net_wifi_clearBlacklistCommand(JNIEnv* env, jobject)
{
    return doBooleanCommand("OK", "BLACKLIST clear");
}

static jboolean android_net_wifi_setSuspendOptimizationsCommand(JNIEnv* env, jobject, jboolean enabled)
{
    return doBooleanCommand("OK", "DRIVER SETSUSPENDOPT %d", enabled ? 0 : 1);
}

static void android_net_wifi_enableBackgroundScanCommand(JNIEnv* env, jobject, jboolean enable)
{
    //Note: BGSCAN-START and BGSCAN-STOP are documented in core/res/res/values/config.xml
    //and will need an update if the names are changed
    if (enable) {
        doBooleanCommand("OK", "DRIVER BGSCAN-START");
    } else {
        doBooleanCommand("OK", "DRIVER BGSCAN-STOP");
    }
}

static void android_net_wifi_setScanIntervalCommand(JNIEnv* env, jobject, jint scanInterval)
{
    doBooleanCommand("OK", "SCAN_INTERVAL %d", scanInterval);
}


static jboolean android_net_wifi_doBooleanCommand(JNIEnv* env, jobject, jstring javaCommand)
{
    ScopedUtfChars command(env, javaCommand);
    if (command.c_str() == NULL) {
        return JNI_FALSE;
    }
    if (DBG) LOGD("doBoolean: %s", command.c_str());
    return doBooleanCommand("OK", "%s", command.c_str());
}

static jint android_net_wifi_doIntCommand(JNIEnv* env, jobject, jstring javaCommand)
{
    ScopedUtfChars command(env, javaCommand);
    if (command.c_str() == NULL) {
        return -1;
    }
    if (DBG) LOGD("doInt: %s", command.c_str());
    return doIntCommand("%s", command.c_str());
}

static jstring android_net_wifi_doStringCommand(JNIEnv* env, jobject, jstring javaCommand)
{
    ScopedUtfChars command(env, javaCommand);
    if (command.c_str() == NULL) {
        return NULL;
    }
    if (DBG) LOGD("doString: %s", command.c_str());
    return doStringCommand(env, "%s", command.c_str());
}



// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gWifiMethods[] = {
    /* name, signature, funcPtr */

    { "loadDriver", "()Z",  (void *)android_net_wifi_loadDriver },
    { "isDriverLoaded", "()Z",  (void *)android_net_wifi_isDriverLoaded},
    { "unloadDriver", "()Z",  (void *)android_net_wifi_unloadDriver },
    { "startSupplicant", "()Z",  (void *)android_net_wifi_startSupplicant },
    { "startP2pSupplicant", "()Z",  (void *)android_net_wifi_startP2pSupplicant },
    { "stopSupplicant", "()Z", (void*) android_net_wifi_stopSupplicant },
    { "killSupplicant", "()Z",  (void *)android_net_wifi_killSupplicant },
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
    { "startFilteringMulticastV4Packets", "()Z", (void*) android_net_wifi_startMultiV4Filtering},
    { "stopFilteringMulticastV4Packets", "()Z", (void*) android_net_wifi_stopMultiV4Filtering},
    { "startFilteringMulticastV6Packets", "()Z", (void*) android_net_wifi_startMultiV6Filtering},
    { "stopFilteringMulticastV6Packets", "()Z", (void*) android_net_wifi_stopMultiV6Filtering},
    { "setPowerModeCommand", "(I)Z", (void*) android_net_wifi_setPowerModeCommand },
    { "getPowerModeCommand", "()I", (void*) android_net_wifi_getPowerModeCommand },
    { "setBandCommand", "(I)Z", (void*) android_net_wifi_setBandCommand},
    { "getBandCommand", "()I", (void*) android_net_wifi_getBandCommand},
    { "setBluetoothCoexistenceModeCommand", "(I)Z",
    		(void*) android_net_wifi_setBluetoothCoexistenceModeCommand },
    { "setBluetoothCoexistenceScanModeCommand", "(Z)Z",
    		(void*) android_net_wifi_setBluetoothCoexistenceScanModeCommand },
    { "getMacAddressCommand", "()Ljava/lang/String;", (void*) android_net_wifi_getMacAddressCommand },
    { "saveConfigCommand", "()Z", (void*) android_net_wifi_saveConfigCommand },
    { "reloadConfigCommand", "()Z", (void*) android_net_wifi_reloadConfigCommand },
    { "setScanResultHandlingCommand", "(I)Z", (void*) android_net_wifi_setScanResultHandlingCommand },
    { "addToBlacklistCommand", "(Ljava/lang/String;)Z", (void*) android_net_wifi_addToBlacklistCommand },
    { "clearBlacklistCommand", "()Z", (void*) android_net_wifi_clearBlacklistCommand },
    { "startWpsPbcCommand", "(Ljava/lang/String;)Z", (void*) android_net_wifi_wpsPbcCommand },
    { "startWpsWithPinFromAccessPointCommand", "(Ljava/lang/String;Ljava/lang/String;)Z",
        (void*) android_net_wifi_wpsPinFromAccessPointCommand },
    { "startWpsWithPinFromDeviceCommand", "(Ljava/lang/String;)Ljava/lang/String;",
        (void*) android_net_wifi_wpsPinFromDeviceCommand },
    { "setSuspendOptimizationsCommand", "(Z)Z",
        (void*) android_net_wifi_setSuspendOptimizationsCommand},
    { "setCountryCodeCommand", "(Ljava/lang/String;)Z",
        (void*) android_net_wifi_setCountryCodeCommand},
    { "enableBackgroundScanCommand", "(Z)V", (void*) android_net_wifi_enableBackgroundScanCommand},
    { "setScanIntervalCommand", "(I)V", (void*) android_net_wifi_setScanIntervalCommand},
    { "doBooleanCommand", "(Ljava/lang/String;)Z", (void*) android_net_wifi_doBooleanCommand},
    { "doIntCommand", "(Ljava/lang/String;)I", (void*) android_net_wifi_doIntCommand},
    { "doStringCommand", "(Ljava/lang/String;)Ljava/lang/String;", (void*) android_net_wifi_doStringCommand},
};

int register_android_net_wifi_WifiManager(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            WIFI_PKG_NAME, gWifiMethods, NELEM(gWifiMethods));
}

}; // namespace android
