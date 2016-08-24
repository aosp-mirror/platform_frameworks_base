/*
 * Copyright 2016, The Android Open Source Project
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

#include "context_hub.h"

#define LOG_NDEBUG 0
#define LOG_TAG "ContextHubService"

#include <inttypes.h>
#include <jni.h>
#include <queue>
#include <unordered_map>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include <cutils/log.h>

#include "JNIHelp.h"
#include "core_jni_helpers.h"

static constexpr int OS_APP_ID=-1;

static constexpr int MIN_APP_ID=1;
static constexpr int MAX_APP_ID=128;

static constexpr size_t MSG_HEADER_SIZE=4;
static constexpr int HEADER_FIELD_MSG_TYPE=0;
//static constexpr int HEADER_FIELD_MSG_VERSION=1;
static constexpr int HEADER_FIELD_HUB_HANDLE=2;
static constexpr int HEADER_FIELD_APP_INSTANCE=3;

namespace android {

namespace {

/*
 * Finds the length of a statically-sized array using template trickery that
 * also prevents it from being applied to the wrong type.
 */
template <typename T, size_t N>
constexpr size_t array_length(T (&)[N]) { return N; }

struct jniInfo_s {
    JavaVM *vm;
    jclass contextHubInfoClass;
    jclass contextHubServiceClass;
    jclass memoryRegionsClass;

    jobject jContextHubService;

    jmethodID msgReceiptCallBack;

    jmethodID contextHubInfoCtor;
    jmethodID contextHubInfoSetId;
    jmethodID contextHubInfoSetName;
    jmethodID contextHubInfoSetVendor;
    jmethodID contextHubInfoSetToolchain;
    jmethodID contextHubInfoSetPlatformVersion;
    jmethodID contextHubInfoSetStaticSwVersion;
    jmethodID contextHubInfoSetToolchainVersion;
    jmethodID contextHubInfoSetPeakMips;
    jmethodID contextHubInfoSetStoppedPowerDrawMw;
    jmethodID contextHubInfoSetSleepPowerDrawMw;
    jmethodID contextHubInfoSetPeakPowerDrawMw;
    jmethodID contextHubInfoSetSupportedSensors;
    jmethodID contextHubInfoSetMemoryRegions;
    jmethodID contextHubInfoSetMaxPacketLenBytes;

    jmethodID contextHubServiceMsgReceiptCallback;
    jmethodID contextHubServiceAddAppInstance;
};

struct context_hub_info_s {
    uint32_t *cookies;
    int numHubs;
    const struct context_hub_t *hubs;
    struct context_hub_module_t *contextHubModule;
};

struct app_instance_info_s {
    uint32_t hubHandle; // Id of the hub this app is on
    int instanceId; // systemwide unique instance id - assigned
    struct hub_app_info appInfo; // returned from the HAL
    uint64_t truncName; // Possibly truncated name - logging
};

struct contextHubServiceDb_s {
    int initialized;
    context_hub_info_s hubInfo;
    jniInfo_s jniInfo;
    std::queue<int> freeIds;
    std::unordered_map<int, app_instance_info_s> appInstances;
};

}  // unnamed namespace

static contextHubServiceDb_s db;

int context_hub_callback(uint32_t hubId, const struct hub_message_t *msg,
                         void *cookie);

const context_hub_t *get_hub_info(int hubHandle) {
    if (hubHandle >= 0 && hubHandle < db.hubInfo.numHubs) {
        return &db.hubInfo.hubs[hubHandle];
    }
    return nullptr;
}

static int send_msg_to_hub(const hub_message_t *msg, int hubHandle) {
    const context_hub_t *info = get_hub_info(hubHandle);

    if (info) {
        return db.hubInfo.contextHubModule->send_message(info->hub_id, msg);
    } else {
        ALOGD("%s: Hub information is null for hubHandle %d", __FUNCTION__, hubHandle);
        return -1;
    }
}

static int set_os_app_as_destination(hub_message_t *msg, int hubHandle) {
    const context_hub_t *info = get_hub_info(hubHandle);

    if (info) {
        msg->app_name = info->os_app_name;
        return 0;
    } else {
        ALOGD("%s: Hub information is null for hubHandle %d", __FUNCTION__, hubHandle);
        return -1;
    }
}

static int get_hub_id_for_hub_handle(int hubHandle) {
    if (hubHandle < 0 || hubHandle >= db.hubInfo.numHubs) {
      return -1;
    } else {
      return db.hubInfo.hubs[hubHandle].hub_id;
    }
}

static int get_hub_id_for_app_instance(int id) {
    if (!db.appInstances.count(id)) {
        ALOGD("%s: Cannot find app for app instance %d", __FUNCTION__, id);
        return -1;
    }

    int hubHandle = db.appInstances[id].hubHandle;

    return db.hubInfo.hubs[hubHandle].hub_id;
}

static int get_app_instance_for_app_id(uint64_t app_id) {
    auto end = db.appInstances.end();
    for (auto current = db.appInstances.begin(); current != end; ++current) {
        if (current->second.appInfo.app_name.id == app_id) {
            return current->first;
        }
    }
    ALOGD("Cannot find app for app instance %" PRIu64 ".", app_id);
    return -1;
}

static int set_dest_app(hub_message_t *msg, int id) {
    if (!db.appInstances.count(id)) {
        ALOGD("%s: Cannot find app for app instance %d", __FUNCTION__, id);
        return -1;
    }

    msg->app_name = db.appInstances[id].appInfo.app_name;
    return 0;
}

static void send_query_for_apps() {
    hub_message_t msg;

    msg.message_type = CONTEXT_HUB_QUERY_APPS;
    msg.message_len  = 0;

    for (int i = 0; i < db.hubInfo.numHubs; i++ ) {
        ALOGD("Sending query for apps to hub %d", i);
        set_os_app_as_destination(&msg, i);
        if (send_msg_to_hub(&msg, i) != 0) {
          ALOGW("Could not query hub %i for apps", i);
        }
    }
}

static int return_id(int id) {
    // Note : This method is not thread safe.
    // id returned is guarenteed to be in use
    db.freeIds.push(id);
    return 0;
}

static int generate_id(void) {
    // Note : This method is not thread safe.
    int retVal = -1;

    if (!db.freeIds.empty()) {
        retVal = db.freeIds.front();
        db.freeIds.pop();
    }

    return retVal;
}

int add_app_instance(const hub_app_info *appInfo, uint32_t hubHandle, JNIEnv *env) {
    // Not checking if the apps are indeed distinct
    app_instance_info_s entry;
    int appInstanceHandle = generate_id();

    assert(appInfo);

    if (appInstanceHandle < 0) {
        ALOGE("Cannot find resources to add app instance %d",
              appInstanceHandle);
        return -1;
    }

    entry.appInfo = *appInfo;
    entry.instanceId = appInstanceHandle;
    entry.truncName = appInfo->app_name.id;
    entry.hubHandle = hubHandle;
    db.appInstances[appInstanceHandle] = entry;

    // Finally - let the service know of this app instance
    env->CallIntMethod(db.jniInfo.jContextHubService,
                       db.jniInfo.contextHubServiceAddAppInstance,
                       hubHandle, entry.instanceId, entry.truncName,
                       entry.appInfo.version);

    ALOGW("Added App 0x%" PRIx64 " on hub Handle %" PRId32
          " as appInstance %d", entry.truncName,
          entry.hubHandle, appInstanceHandle);

    return appInstanceHandle;
}

int delete_app_instance(int id) {
    if (!db.appInstances.count(id)) {
        return -1;
    }

    return_id(id);
    db.appInstances.erase(id);

    return 0;
}


static void initContextHubService() {
    int err = 0;
    db.hubInfo.hubs = nullptr;
    db.hubInfo.numHubs = 0;
    int i;

    err = hw_get_module(CONTEXT_HUB_MODULE_ID,
                        (hw_module_t const**)(&db.hubInfo.contextHubModule));

    if (err) {
      ALOGE("** Could not load %s module : err %s", CONTEXT_HUB_MODULE_ID,
            strerror(-err));
    }

    // Prep for storing app info
    for(i = MIN_APP_ID; i <= MAX_APP_ID; i++) {
        db.freeIds.push(i);
    }

    if (db.hubInfo.contextHubModule) {
        int retNumHubs = db.hubInfo.contextHubModule->get_hubs(db.hubInfo.contextHubModule,
                                                                 &db.hubInfo.hubs);
        ALOGD("ContextHubModule returned %d hubs ", retNumHubs);
        db.hubInfo.numHubs = retNumHubs;

        if (db.hubInfo.numHubs > 0) {
            db.hubInfo.numHubs = retNumHubs;
            db.hubInfo.cookies = (uint32_t *)malloc(sizeof(uint32_t) * db.hubInfo.numHubs);

            if (!db.hubInfo.cookies) {
                ALOGW("Ran out of memory allocating cookies, bailing");
                return;
            }

            for (i = 0; i < db.hubInfo.numHubs; i++) {
                db.hubInfo.cookies[i] = db.hubInfo.hubs[i].hub_id;
                if (db.hubInfo.contextHubModule->subscribe_messages(db.hubInfo.hubs[i].hub_id,
                                                                    context_hub_callback,
                                                                    &db.hubInfo.cookies[i]) == 0) {
                }
            }
        }

        send_query_for_apps();
    } else {
        ALOGW("No Context Hub Module present");
    }
}

static int onMessageReceipt(uint32_t *header, size_t headerLen, char *msg, size_t msgLen) {
    JNIEnv *env;

    if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
      return -1;
    }

    jbyteArray jmsg = env->NewByteArray(msgLen);
    if (jmsg == nullptr) {
        ALOGW("Can't allocate %zu byte array", msgLen);
        return -1;
    }
    jintArray jheader = env->NewIntArray(headerLen);
    if (jheader == nullptr) {
        env->DeleteLocalRef(jmsg);
        ALOGW("Can't allocate %zu int array", headerLen);
        return -1;
    }

    env->SetByteArrayRegion(jmsg, 0, msgLen, (jbyte *)msg);
    env->SetIntArrayRegion(jheader, 0, headerLen, (jint *)header);

    int ret = (env->CallIntMethod(db.jniInfo.jContextHubService,
                          db.jniInfo.contextHubServiceMsgReceiptCallback,
                          jheader, jmsg) != 0);
    env->DeleteLocalRef(jmsg);
    env->DeleteLocalRef(jheader);

    return ret;
}

int handle_query_apps_response(char *msg, int msgLen, uint32_t hubHandle) {
    JNIEnv *env;
    if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return -1;
    }

    int numApps = msgLen/sizeof(hub_app_info);
    hub_app_info info;
    hub_app_info *unalignedInfoAddr = (hub_app_info*)msg;

    for (int i = 0; i < numApps; i++, unalignedInfoAddr++) {
        memcpy(&info, unalignedInfoAddr, sizeof(info));
        add_app_instance(&info, hubHandle, env);
    }

    return 0;
}


int handle_os_message(uint32_t msgType, uint32_t hubHandle,
                      char *msg, int msgLen) {
    int retVal;

    //ALOGD("Rcd OS message from hubHandle %" PRIu32 " type %" PRIu32 " length %d",
    //      hubHandle, msgType, msgLen);

    switch(msgType) {
        case CONTEXT_HUB_APPS_ENABLE:
            retVal = 0;
            break;

        case CONTEXT_HUB_APPS_DISABLE:
            retVal = 0;
            break;

        case CONTEXT_HUB_LOAD_APP:
            retVal = 0;
            break;

        case CONTEXT_HUB_UNLOAD_APP:
            retVal = 0;
            break;

        case CONTEXT_HUB_QUERY_APPS:
            retVal = handle_query_apps_response(msg, msgLen, hubHandle);
            break;

        case CONTEXT_HUB_QUERY_MEMORY:
            retVal = 0;
            break;

        default:
            retVal = -1;
            break;

    }

    return retVal;
}

static bool sanity_check_cookie(void *cookie, uint32_t hub_id) {
    int *ptr = (int *)cookie;

    if (!ptr || *ptr >= db.hubInfo.numHubs) {
        return false;
    }

    if (db.hubInfo.hubs[*ptr].hub_id != hub_id) {
        return false;
    } else {
        return true;
    }
}

int context_hub_callback(uint32_t hubId,
                         const struct hub_message_t *msg,
                         void *cookie) {
    if (!msg) {
        return -1;
    }
    if (!sanity_check_cookie(cookie, hubId)) {
        ALOGW("Incorrect cookie %" PRId32 " for cookie %p! Bailing",
              hubId, cookie);

        return -1;
    }

    uint32_t messageType = msg->message_type;
    uint32_t hubHandle = *(uint32_t*) cookie;

    if (messageType < CONTEXT_HUB_TYPE_PRIVATE_MSG_BASE) {
        handle_os_message(messageType, hubHandle, (char*) msg->message, msg->message_len);
    } else {
        int appHandle = get_app_instance_for_app_id(msg->app_name.id);
        if (appHandle < 0) {
            ALOGE("Filtering out message due to invalid App Instance.");
        } else {
            uint32_t msgHeader[MSG_HEADER_SIZE] = {};
            msgHeader[HEADER_FIELD_MSG_TYPE] = messageType;
            msgHeader[HEADER_FIELD_HUB_HANDLE] = hubHandle;
            msgHeader[HEADER_FIELD_APP_INSTANCE] = appHandle;
            onMessageReceipt(msgHeader, MSG_HEADER_SIZE, (char*) msg->message, msg->message_len);
        }
    }

    return 0;
}

static int init_jni(JNIEnv *env, jobject instance) {

    if (env->GetJavaVM(&db.jniInfo.vm) != JNI_OK) {
        return -1;
    }

    db.jniInfo.jContextHubService = env->NewGlobalRef(instance);

    db.jniInfo.contextHubInfoClass =
            env->FindClass("android/hardware/location/ContextHubInfo");

    db.jniInfo.contextHubServiceClass =
            env->FindClass("android/hardware/location/ContextHubService");

    db.jniInfo.memoryRegionsClass =
            env->FindClass("android/hardware/location/MemoryRegion");

    db.jniInfo.contextHubInfoCtor =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "<init>", "()V");
    db.jniInfo.contextHubInfoSetId =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setId", "(I)V");
    db.jniInfo.contextHubInfoSetName =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setName",
                                "(Ljava/lang/String;)V");

    db.jniInfo.contextHubInfoSetVendor =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setVendor", "(Ljava/lang/String;)V");
    db.jniInfo.contextHubInfoSetToolchain =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setToolchain", "(Ljava/lang/String;)V");
    db.jniInfo.contextHubInfoSetPlatformVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setPlatformVersion", "(I)V");
    db.jniInfo.contextHubInfoSetStaticSwVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setStaticSwVersion", "(I)V");
    db.jniInfo.contextHubInfoSetToolchainVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setToolchainVersion", "(I)V");
    db.jniInfo.contextHubInfoSetPeakMips =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setPeakMips", "(F)V");
    db.jniInfo.contextHubInfoSetStoppedPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setStoppedPowerDrawMw", "(F)V");
    db.jniInfo.contextHubInfoSetSleepPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setSleepPowerDrawMw", "(F)V");
    db.jniInfo.contextHubInfoSetPeakPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setPeakPowerDrawMw", "(F)V");
    db.jniInfo.contextHubInfoSetSupportedSensors =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setSupportedSensors", "([I)V");
    db.jniInfo.contextHubInfoSetMemoryRegions =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setMemoryRegions", "([Landroid/hardware/location/MemoryRegion;)V");
    db.jniInfo.contextHubInfoSetMaxPacketLenBytes =
             env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setMaxPacketLenBytes", "(I)V");


    db.jniInfo.contextHubServiceMsgReceiptCallback =
            env->GetMethodID(db.jniInfo.contextHubServiceClass, "onMessageReceipt",
                               "([I[B)I");
    db.jniInfo.contextHubInfoSetName =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setName",
            "(Ljava/lang/String;)V");

    db.jniInfo.contextHubServiceAddAppInstance =
                 env->GetMethodID(db.jniInfo.contextHubServiceClass,
                                    "addAppInstance", "(IIJI)I");



    return 0;
}

static jobject constructJContextHubInfo(JNIEnv *env, const struct context_hub_t *hub) {
    jstring jstrBuf;
    jintArray jintBuf;
    jobjectArray jmemBuf;

    int dummyConnectedSensors[] = {1, 2, 3, 4, 5};

    jobject jHub = env->NewObject(db.jniInfo.contextHubInfoClass,
                                  db.jniInfo.contextHubInfoCtor);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetId, hub->hub_id);

    jstrBuf = env->NewStringUTF(hub->name);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetName, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    jstrBuf = env->NewStringUTF(hub->vendor);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetVendor, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    jstrBuf = env->NewStringUTF(hub->toolchain);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetToolchain, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPlatformVersion, hub->platform_version);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetToolchainVersion, hub->toolchain_version);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPeakMips, hub->peak_mips);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetStoppedPowerDrawMw,
                        hub->stopped_power_draw_mw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetSleepPowerDrawMw,
                        hub->sleep_power_draw_mw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPeakPowerDrawMw,
                        hub->peak_power_draw_mw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetMaxPacketLenBytes,
                        hub->max_supported_msg_len);


    // TODO : jintBuf = env->NewIntArray(hub->num_connected_sensors);
    // TODO : env->SetIntArrayRegion(jintBuf, 0, hub->num_connected_sensors,
    //                               hub->connected_sensors);
    jintBuf = env->NewIntArray(array_length(dummyConnectedSensors));
    env->SetIntArrayRegion(jintBuf, 0, hub->num_connected_sensors, dummyConnectedSensors);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetSupportedSensors, jintBuf);
    env->DeleteLocalRef(jintBuf);

    // We are not getting the memory regions from the CH Hal - change this when it is available
    jmemBuf = env->NewObjectArray(0, db.jniInfo.memoryRegionsClass, nullptr);
    // Note the zero size above. We do not need to set any elements
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetMemoryRegions, jmemBuf);
    env->DeleteLocalRef(jmemBuf);


    return jHub;
}

static jobjectArray nativeInitialize(JNIEnv *env, jobject instance)
{
    jobject hub;
    jobjectArray retArray;

    if (init_jni(env, instance) < 0) {
        return nullptr;
    }

    initContextHubService();

    if (db.hubInfo.numHubs > 1) {
      ALOGW("Clamping the number of hubs to 1");
      db.hubInfo.numHubs = 1;
    }

    retArray = env->NewObjectArray(db.hubInfo.numHubs, db.jniInfo.contextHubInfoClass, nullptr);

    for(int i = 0; i < db.hubInfo.numHubs; i++) {
        hub = constructJContextHubInfo(env, &db.hubInfo.hubs[i]);
        env->SetObjectArrayElement(retArray, i, hub);
    }

    return retArray;
}

static jint nativeSendMessage(JNIEnv *env, jobject instance, jintArray header_,
                              jbyteArray data_) {
    jint retVal = -1; // Default to failure

    jint *header = env->GetIntArrayElements(header_, 0);
    unsigned int numHeaderElements = env->GetArrayLength(header_);
    jbyte *data = env->GetByteArrayElements(data_, 0);
    int dataBufferLength = env->GetArrayLength(data_);


    if (numHeaderElements >= MSG_HEADER_SIZE) {
        bool setAddressSuccess;
        int hubId;
        hub_message_t msg;

        if (header[HEADER_FIELD_APP_INSTANCE] == OS_APP_ID) {
            setAddressSuccess = (set_os_app_as_destination(&msg, header[HEADER_FIELD_HUB_HANDLE]) == 0);
            hubId = get_hub_id_for_hub_handle(header[HEADER_FIELD_HUB_HANDLE]);
        } else {
            setAddressSuccess = (set_dest_app(&msg, header[HEADER_FIELD_APP_INSTANCE]) == 0);
            hubId = get_hub_id_for_app_instance(header[HEADER_FIELD_APP_INSTANCE]);
        }

        if (setAddressSuccess && hubId >= 0) {
            msg.message_type = header[HEADER_FIELD_MSG_TYPE];
            msg.message_len = dataBufferLength;
            msg.message = data;
            retVal = db.hubInfo.contextHubModule->send_message(hubId, &msg);
        } else {
          ALOGD("Could not find app instance %d on hubHandle %d, setAddress %d",
                header[HEADER_FIELD_APP_INSTANCE],
                header[HEADER_FIELD_HUB_HANDLE],
                (int)setAddressSuccess);
        }
    } else {
        ALOGD("Malformed header len");
    }

    env->ReleaseIntArrayElements(header_, header, 0);
    env->ReleaseByteArrayElements(data_, data, 0);

    return retVal;
}

//--------------------------------------------------------------------------------------------------
//
static const JNINativeMethod gContextHubServiceMethods[] = {
    {"nativeInitialize",
             "()[Landroid/hardware/location/ContextHubInfo;",
             (void*)nativeInitialize },
    {"nativeSendMessage",
            "([I[B)I",
            (void*)nativeSendMessage }
};

}//namespace android

using namespace android;

int register_android_hardware_location_ContextHubService(JNIEnv *env)
{
    RegisterMethodsOrDie(env, "android/hardware/location/ContextHubService",
            gContextHubServiceMethods, NELEM(gContextHubServiceMethods));

    return 0;
}
