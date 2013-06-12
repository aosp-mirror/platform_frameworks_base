/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "BatteryService"

#include "JNIHelp.h"
#include "jni.h"
#include <utils/Log.h>
#include <utils/misc.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <errno.h>
#include <unistd.h>
#include <dirent.h>
#include <linux/ioctl.h>
#include <utils/Vector.h>
#include <utils/String8.h>

namespace android {

#define POWER_SUPPLY_PATH "/sys/class/power_supply"

struct FieldIds {
    // members
    jfieldID mAcOnline;
    jfieldID mUsbOnline;
    jfieldID mWirelessOnline;
    jfieldID mBatteryStatus;
    jfieldID mBatteryHealth;
    jfieldID mBatteryPresent;
    jfieldID mBatteryLevel;
    jfieldID mBatteryVoltage;
    jfieldID mBatteryTemperature;
    jfieldID mBatteryTechnology;
};
static FieldIds gFieldIds;

struct BatteryManagerConstants {
    jint statusUnknown;
    jint statusCharging;
    jint statusDischarging;
    jint statusNotCharging;
    jint statusFull;
    jint healthUnknown;
    jint healthGood;
    jint healthOverheat;
    jint healthDead;
    jint healthOverVoltage;
    jint healthUnspecifiedFailure;
    jint healthCold;
};
static BatteryManagerConstants gConstants;

struct PowerSupplyPaths {
    String8 batteryStatusPath;
    String8 batteryHealthPath;
    String8 batteryPresentPath;
    String8 batteryCapacityPath;
    String8 batteryVoltagePath;
    String8 batteryTemperaturePath;
    String8 batteryTechnologyPath;
};
static PowerSupplyPaths gPaths;

static Vector<String8> gChargerNames;

static int gVoltageDivisor = 1;

enum PowerSupplyType {
     ANDROID_POWER_SUPPLY_TYPE_UNKNOWN = 0,
     ANDROID_POWER_SUPPLY_TYPE_AC,
     ANDROID_POWER_SUPPLY_TYPE_USB,
     ANDROID_POWER_SUPPLY_TYPE_WIRELESS,
     ANDROID_POWER_SUPPLY_TYPE_BATTERY
};

static jint getBatteryStatus(const char* status)
{
    switch (status[0]) {
        case 'C': return gConstants.statusCharging;         // Charging
        case 'D': return gConstants.statusDischarging;      // Discharging
        case 'F': return gConstants.statusFull;             // Full
        case 'N': return gConstants.statusNotCharging;      // Not charging
        case 'U': return gConstants.statusUnknown;          // Unknown
            
        default: {
            ALOGW("Unknown battery status '%s'", status);
            return gConstants.statusUnknown;
        }
    }
}

static jint getBatteryHealth(const char* status)
{
    switch (status[0]) {
        case 'C': return gConstants.healthCold;         // Cold
        case 'D': return gConstants.healthDead;         // Dead
        case 'G': return gConstants.healthGood;         // Good
        case 'O': {
            if (strcmp(status, "Overheat") == 0) {
                return gConstants.healthOverheat;
            } else if (strcmp(status, "Over voltage") == 0) {
                return gConstants.healthOverVoltage;
            }
            ALOGW("Unknown battery health[1] '%s'", status);
            return gConstants.healthUnknown;
        }
        
        case 'U': {
            if (strcmp(status, "Unspecified failure") == 0) {
                return gConstants.healthUnspecifiedFailure;
            } else if (strcmp(status, "Unknown") == 0) {
                return gConstants.healthUnknown;
            }
            // fall through
        }
            
        default: {
            ALOGW("Unknown battery health[2] '%s'", status);
            return gConstants.healthUnknown;
        }
    }
}

static int readFromFile(const String8& path, char* buf, size_t size)
{
    if (path.isEmpty())
        return -1;
    int fd = open(path.string(), O_RDONLY, 0);
    if (fd == -1) {
        ALOGE("Could not open '%s'", path.string());
        return -1;
    }
    
    ssize_t count = read(fd, buf, size);
    if (count > 0) {
        while (count > 0 && buf[count-1] == '\n')
            count--;
        buf[count] = '\0';
    } else {
        buf[0] = '\0';
    } 

    close(fd);
    return count;
}

static void setBooleanField(JNIEnv* env, jobject obj, const String8& path, jfieldID fieldID)
{
    const int SIZE = 16;
    char buf[SIZE];
    
    jboolean value = false;
    if (readFromFile(path, buf, SIZE) > 0) {
        if (buf[0] != '0') {
            value = true;
        }
    }
    env->SetBooleanField(obj, fieldID, value);
}

static void setIntField(JNIEnv* env, jobject obj, const String8& path, jfieldID fieldID)
{
    const int SIZE = 128;
    char buf[SIZE];
    
    jint value = 0;
    if (readFromFile(path, buf, SIZE) > 0) {
        value = atoi(buf);
    }
    env->SetIntField(obj, fieldID, value);
}

static void setVoltageField(JNIEnv* env, jobject obj, const String8& path, jfieldID fieldID)
{
    const int SIZE = 128;
    char buf[SIZE];

    jint value = 0;
    if (readFromFile(path, buf, SIZE) > 0) {
        value = atoi(buf);
        value /= gVoltageDivisor;
    }
    env->SetIntField(obj, fieldID, value);
}

static PowerSupplyType readPowerSupplyType(const String8& path) {
    const int SIZE = 128;
    char buf[SIZE];
    int length = readFromFile(path, buf, SIZE);

    if (length <= 0)
        return ANDROID_POWER_SUPPLY_TYPE_UNKNOWN;
    if (buf[length - 1] == '\n')
        buf[length - 1] = 0;
    if (strcmp(buf, "Battery") == 0)
        return ANDROID_POWER_SUPPLY_TYPE_BATTERY;
    else if (strcmp(buf, "Mains") == 0 || strcmp(buf, "USB_DCP") == 0 ||
             strcmp(buf, "USB_CDP") == 0 || strcmp(buf, "USB_ACA") == 0)
        return ANDROID_POWER_SUPPLY_TYPE_AC;
    else if (strcmp(buf, "USB") == 0)
        return ANDROID_POWER_SUPPLY_TYPE_USB;
    else if (strcmp(buf, "Wireless") == 0)
        return ANDROID_POWER_SUPPLY_TYPE_WIRELESS;
    else
        return ANDROID_POWER_SUPPLY_TYPE_UNKNOWN;
}

static void android_server_BatteryService_update(JNIEnv* env, jobject obj)
{
    setBooleanField(env, obj, gPaths.batteryPresentPath, gFieldIds.mBatteryPresent);
    
    setIntField(env, obj, gPaths.batteryCapacityPath, gFieldIds.mBatteryLevel);
    setVoltageField(env, obj, gPaths.batteryVoltagePath, gFieldIds.mBatteryVoltage);
    setIntField(env, obj, gPaths.batteryTemperaturePath, gFieldIds.mBatteryTemperature);
    
    const int SIZE = 128;
    char buf[SIZE];
    
    if (readFromFile(gPaths.batteryStatusPath, buf, SIZE) > 0)
        env->SetIntField(obj, gFieldIds.mBatteryStatus, getBatteryStatus(buf));
    else
        env->SetIntField(obj, gFieldIds.mBatteryStatus,
                         gConstants.statusUnknown);
    
    if (readFromFile(gPaths.batteryHealthPath, buf, SIZE) > 0)
        env->SetIntField(obj, gFieldIds.mBatteryHealth, getBatteryHealth(buf));

    if (readFromFile(gPaths.batteryTechnologyPath, buf, SIZE) > 0)
        env->SetObjectField(obj, gFieldIds.mBatteryTechnology, env->NewStringUTF(buf));

    unsigned int i;
    String8 path;
    jboolean acOnline = false;
    jboolean usbOnline = false;
    jboolean wirelessOnline = false;

    for (i = 0; i < gChargerNames.size(); i++) {
        path.clear();
        path.appendFormat("%s/%s/online", POWER_SUPPLY_PATH,
                          gChargerNames[i].string());

        if (readFromFile(path, buf, SIZE) > 0) {
            if (buf[0] != '0') {
                path.clear();
                path.appendFormat("%s/%s/type", POWER_SUPPLY_PATH,
                                  gChargerNames[i].string());
                switch(readPowerSupplyType(path)) {
                case ANDROID_POWER_SUPPLY_TYPE_AC:
                    acOnline = true;
                    break;
                case ANDROID_POWER_SUPPLY_TYPE_USB:
                    usbOnline = true;
                    break;
                case ANDROID_POWER_SUPPLY_TYPE_WIRELESS:
                    wirelessOnline = true;
                    break;
                default:
                    ALOGW("%s: Unknown power supply type",
                          gChargerNames[i].string());
                }
            }
        }
    }

    env->SetBooleanField(obj, gFieldIds.mAcOnline, acOnline);
    env->SetBooleanField(obj, gFieldIds.mUsbOnline, usbOnline);
    env->SetBooleanField(obj, gFieldIds.mWirelessOnline, wirelessOnline);
}

static JNINativeMethod sMethods[] = {
     /* name, signature, funcPtr */
        {"native_update", "()V", (void*)android_server_BatteryService_update},
};

int register_android_server_BatteryService(JNIEnv* env)
{
    String8 path;
    struct dirent* entry;

    DIR* dir = opendir(POWER_SUPPLY_PATH);
    if (dir == NULL) {
        ALOGE("Could not open %s\n", POWER_SUPPLY_PATH);
    } else {
        while ((entry = readdir(dir))) {
            const char* name = entry->d_name;

            // ignore "." and ".."
            if (name[0] == '.' && (name[1] == 0 || (name[1] == '.' && name[2] == 0))) {
                continue;
            }

            char buf[20];
            // Look for "type" file in each subdirectory
            path.clear();
            path.appendFormat("%s/%s/type", POWER_SUPPLY_PATH, name);
            switch(readPowerSupplyType(path)) {
            case ANDROID_POWER_SUPPLY_TYPE_AC:
            case ANDROID_POWER_SUPPLY_TYPE_USB:
            case ANDROID_POWER_SUPPLY_TYPE_WIRELESS:
                path.clear();
                path.appendFormat("%s/%s/online", POWER_SUPPLY_PATH, name);
                if (access(path.string(), R_OK) == 0)
                    gChargerNames.add(String8(name));
                break;

            case ANDROID_POWER_SUPPLY_TYPE_BATTERY:
                path.clear();
                path.appendFormat("%s/%s/status", POWER_SUPPLY_PATH, name);
                if (access(path, R_OK) == 0)
                    gPaths.batteryStatusPath = path;
                path.clear();
                path.appendFormat("%s/%s/health", POWER_SUPPLY_PATH, name);
                if (access(path, R_OK) == 0)
                    gPaths.batteryHealthPath = path;
                path.clear();
                path.appendFormat("%s/%s/present", POWER_SUPPLY_PATH, name);
                if (access(path, R_OK) == 0)
                    gPaths.batteryPresentPath = path;
                path.clear();
                path.appendFormat("%s/%s/capacity", POWER_SUPPLY_PATH, name);
                if (access(path, R_OK) == 0)
                    gPaths.batteryCapacityPath = path;

                path.clear();
                path.appendFormat("%s/%s/voltage_now", POWER_SUPPLY_PATH, name);
                if (access(path, R_OK) == 0) {
                    gPaths.batteryVoltagePath = path;
                    // voltage_now is in microvolts, not millivolts
                    gVoltageDivisor = 1000;
                } else {
                    path.clear();
                    path.appendFormat("%s/%s/batt_vol", POWER_SUPPLY_PATH, name);
                    if (access(path, R_OK) == 0)
                            gPaths.batteryVoltagePath = path;
                }

                path.clear();
                path.appendFormat("%s/%s/temp", POWER_SUPPLY_PATH, name);
                if (access(path, R_OK) == 0) {
                    gPaths.batteryTemperaturePath = path;
                } else {
                    path.clear();
                    path.appendFormat("%s/%s/batt_temp", POWER_SUPPLY_PATH, name);
                    if (access(path, R_OK) == 0)
                            gPaths.batteryTemperaturePath = path;
                }

                path.clear();
                path.appendFormat("%s/%s/technology", POWER_SUPPLY_PATH, name);
                if (access(path, R_OK) == 0)
                    gPaths.batteryTechnologyPath = path;
                break;
            }
        }
        closedir(dir);
    }

    if (!gChargerNames.size())
        ALOGE("No charger supplies found");
    if (!gPaths.batteryStatusPath)
        ALOGE("batteryStatusPath not found");
    if (!gPaths.batteryHealthPath)
        ALOGE("batteryHealthPath not found");
    if (!gPaths.batteryPresentPath)
        ALOGE("batteryPresentPath not found");
    if (!gPaths.batteryCapacityPath)
        ALOGE("batteryCapacityPath not found");
    if (!gPaths.batteryVoltagePath)
        ALOGE("batteryVoltagePath not found");
    if (!gPaths.batteryTemperaturePath)
        ALOGE("batteryTemperaturePath not found");
    if (!gPaths.batteryTechnologyPath)
        ALOGE("batteryTechnologyPath not found");

    jclass clazz = env->FindClass("com/android/server/BatteryService");

    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/BatteryService");
        return -1;
    }
    
    gFieldIds.mAcOnline = env->GetFieldID(clazz, "mAcOnline", "Z");
    gFieldIds.mUsbOnline = env->GetFieldID(clazz, "mUsbOnline", "Z");
    gFieldIds.mWirelessOnline = env->GetFieldID(clazz, "mWirelessOnline", "Z");
    gFieldIds.mBatteryStatus = env->GetFieldID(clazz, "mBatteryStatus", "I");
    gFieldIds.mBatteryHealth = env->GetFieldID(clazz, "mBatteryHealth", "I");
    gFieldIds.mBatteryPresent = env->GetFieldID(clazz, "mBatteryPresent", "Z");
    gFieldIds.mBatteryLevel = env->GetFieldID(clazz, "mBatteryLevel", "I");
    gFieldIds.mBatteryTechnology = env->GetFieldID(clazz, "mBatteryTechnology", "Ljava/lang/String;");
    gFieldIds.mBatteryVoltage = env->GetFieldID(clazz, "mBatteryVoltage", "I");
    gFieldIds.mBatteryTemperature = env->GetFieldID(clazz, "mBatteryTemperature", "I");

    LOG_FATAL_IF(gFieldIds.mAcOnline == NULL, "Unable to find BatteryService.AC_ONLINE_PATH");
    LOG_FATAL_IF(gFieldIds.mUsbOnline == NULL, "Unable to find BatteryService.USB_ONLINE_PATH");
    LOG_FATAL_IF(gFieldIds.mWirelessOnline == NULL, "Unable to find BatteryService.WIRELESS_ONLINE_PATH");
    LOG_FATAL_IF(gFieldIds.mBatteryStatus == NULL, "Unable to find BatteryService.BATTERY_STATUS_PATH");
    LOG_FATAL_IF(gFieldIds.mBatteryHealth == NULL, "Unable to find BatteryService.BATTERY_HEALTH_PATH");
    LOG_FATAL_IF(gFieldIds.mBatteryPresent == NULL, "Unable to find BatteryService.BATTERY_PRESENT_PATH");
    LOG_FATAL_IF(gFieldIds.mBatteryLevel == NULL, "Unable to find BatteryService.BATTERY_CAPACITY_PATH");
    LOG_FATAL_IF(gFieldIds.mBatteryVoltage == NULL, "Unable to find BatteryService.BATTERY_VOLTAGE_PATH");
    LOG_FATAL_IF(gFieldIds.mBatteryTemperature == NULL, "Unable to find BatteryService.BATTERY_TEMPERATURE_PATH");
    LOG_FATAL_IF(gFieldIds.mBatteryTechnology == NULL, "Unable to find BatteryService.BATTERY_TECHNOLOGY_PATH");
    
    clazz = env->FindClass("android/os/BatteryManager");
    
    if (clazz == NULL) {
        ALOGE("Can't find android/os/BatteryManager");
        return -1;
    }
    
    gConstants.statusUnknown = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_STATUS_UNKNOWN", "I"));
            
    gConstants.statusCharging = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_STATUS_CHARGING", "I"));
            
    gConstants.statusDischarging = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_STATUS_DISCHARGING", "I"));
    
    gConstants.statusNotCharging = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_STATUS_NOT_CHARGING", "I"));
    
    gConstants.statusFull = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_STATUS_FULL", "I"));

    gConstants.healthUnknown = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_HEALTH_UNKNOWN", "I"));

    gConstants.healthGood = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_HEALTH_GOOD", "I"));

    gConstants.healthOverheat = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_HEALTH_OVERHEAT", "I"));

    gConstants.healthDead = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_HEALTH_DEAD", "I"));

    gConstants.healthOverVoltage = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_HEALTH_OVER_VOLTAGE", "I"));
            
    gConstants.healthUnspecifiedFailure = env->GetStaticIntField(clazz, 
            env->GetStaticFieldID(clazz, "BATTERY_HEALTH_UNSPECIFIED_FAILURE", "I"));
    
    gConstants.healthCold = env->GetStaticIntField(clazz,
            env->GetStaticFieldID(clazz, "BATTERY_HEALTH_COLD", "I"));

    return jniRegisterNativeMethods(env, "com/android/server/BatteryService", sMethods, NELEM(sMethods));
}

} /* namespace android */
