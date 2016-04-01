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

#include <string.h>
#include <stdint.h>
#include <stdio.h>

#include <jni.h>
#include "JNIHelp.h"
#include "core_jni_helpers.h"
#include "stdint.h"
#include "stdlib.h"


namespace android {

namespace {

// TODO: We should share this array_length function widely around Android
//     code.
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

    jmethodID contextHubServiceMsgReceiptCallback;
};

struct context_hub_info_s {
    int cookie;
    int numHubs;
    const struct context_hub_t *hubs;
    struct context_hub_module_t *contextHubModule;
};

struct contextHubServiceDb_s {
    int initialized;
    context_hub_info_s hubInfo;
    jniInfo_s jniInfo;
};

}  // unnamed namespace

static contextHubServiceDb_s db;

int context_hub_callback(uint32_t hub_id, const struct hub_message_t *msg,
                         void *cookie);

static void initContextHubService() {
    int err = 0;
    db.hubInfo.hubs = NULL;
    db.hubInfo.numHubs = 0;
    db.hubInfo.cookie = 0;
    int i;

    err = hw_get_module(CONTEXT_HUB_MODULE_ID,
                        (hw_module_t const**)(&db.hubInfo.contextHubModule));

    if (err) {
      ALOGE("** Could not load %s module : err %s", CONTEXT_HUB_MODULE_ID,
            strerror(-err));
    }

    if (db.hubInfo.contextHubModule) {
      ALOGD("Fetching hub info");
      db.hubInfo.numHubs = db.hubInfo.contextHubModule->get_hubs(db.hubInfo.contextHubModule,
                                                                 &db.hubInfo.hubs);

      if (db.hubInfo.numHubs > 0) {
        for (i = 0; i < db.hubInfo.numHubs; i++) {
          // TODO : Event though one cookie is OK for now, lets change
          // this to be one per hub
          db.hubInfo.contextHubModule->subscribe_messages(db.hubInfo.hubs[i].hub_id,
                                                          context_hub_callback,
                                                          &db.hubInfo.cookie);
        }
      }
    }
}

static int onMessageReceipt(int *header, int headerLen, char *msg, int msgLen) {
    JNIEnv *env;
    if ((db.jniInfo.vm)->AttachCurrentThread(&env, NULL) != JNI_OK) {
      return -1;
    }

    jbyteArray jmsg = env->NewByteArray(msgLen);
    jintArray jheader = env->NewIntArray(headerLen);

    env->SetByteArrayRegion(jmsg, 0, msgLen, (jbyte *)msg);
    env->SetIntArrayRegion(jheader, 0, headerLen, (jint *)header);


    return env->CallIntMethod(db.jniInfo.jContextHubService,
                          db.jniInfo.contextHubServiceMsgReceiptCallback,
                          jheader, jmsg);
}

int context_hub_callback(uint32_t hub_id, const struct hub_message_t *msg,
                         void *cookie) {
  int msgHeader[4];

  msgHeader[0] = msg->message_type;
  msgHeader[1] = 0; // TODO : HAL does not have a version field
  msgHeader[2] = hub_id;

  onMessageReceipt(msgHeader, sizeof(msgHeader), (char *)msg->message, msg->message_len); // TODO : Populate this
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

    //TODO :: Add error checking
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


    db.jniInfo.contextHubServiceMsgReceiptCallback =
            env->GetMethodID(db.jniInfo.contextHubServiceClass, "onMessageReceipt",
                               "([I[B)I");
    db.jniInfo.contextHubInfoSetName =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setName",
            "(Ljava/lang/String;)V");


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

    jstrBuf = env->NewStringUTF(hub->vendor);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetVendor, jstrBuf);

    jstrBuf = env->NewStringUTF(hub->toolchain);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetToolchain, jstrBuf);

    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPlatformVersion, hub->platform_version);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetToolchainVersion, hub->toolchain_version);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPeakMips, hub->peak_mips);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetStoppedPowerDrawMw, hub->stopped_power_draw_mw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetSleepPowerDrawMw, hub->sleep_power_draw_mw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPeakPowerDrawMw, hub->peak_power_draw_mw);

    // TODO : jintBuf = env->NewIntArray(hub->num_connected_sensors);
    // TODO : env->SetIntArrayRegion(jintBuf, 0, hub->num_connected_sensors, hub->connected_sensors);
    jintBuf = env->NewIntArray(array_length(dummyConnectedSensors));
    env->SetIntArrayRegion(jintBuf, 0, hub->num_connected_sensors, dummyConnectedSensors);

    // We are not getting the memory regions from the CH Hal - change this when it is available
    jmemBuf = env->NewObjectArray(0, db.jniInfo.memoryRegionsClass, NULL);
    // Note the zero size above. We do not need to set any elements
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetMemoryRegions, jmemBuf);

    return jHub;
}

static jobjectArray nativeInitialize(JNIEnv *env, jobject instance)
{
    jobject hub;
    jobjectArray retArray;

    initContextHubService();

    if (init_jni(env, instance) < 0) {
        return NULL;
    }

    // Note : The service is clamping the number of hubs to 1
    db.hubInfo.numHubs = 1;

    initContextHubService();

    retArray = env->NewObjectArray(db.hubInfo.numHubs, db.jniInfo.contextHubInfoClass, NULL);

    for(int i = 0; i < db.hubInfo.numHubs; i++) {
        hub = constructJContextHubInfo(env, &db.hubInfo.hubs[i]);
        env->SetObjectArrayElement(retArray, i, hub);
    }

    return retArray;
}

static jint nativeSendMessage(JNIEnv *env, jobject instance, jintArray header_,
                              jbyteArray data_) {
    hub_message_t msg;
    hub_app_name_t dest;
    uint8_t os_name[8];

    memset(os_name, 0, sizeof(os_name));

    jint *header = env->GetIntArrayElements(header_, 0);
    //int numHeaderElements = env->GetArrayLength(header_);
    jbyte *data = env->GetByteArrayElements(data_, 0);
    int dataBufferLength = env->GetArrayLength(data_);

    /* Assume an int - thats all we understand */
    dest.app_name_len = array_length(os_name); // TODO : Check this
    //dest.app_name = &header[1];
    dest.app_name = os_name;

    msg.app = &dest;

    msg.message_type = header[3];
    msg.message_len = dataBufferLength;
    msg.message = data;

    jint retVal = db.hubInfo.contextHubModule->send_message(header[0], &msg);

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
