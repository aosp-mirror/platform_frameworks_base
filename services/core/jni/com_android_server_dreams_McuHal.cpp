/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "McuHal"

//#define LOG_NDEBUG 0

#include "JNIHelp.h"
#include "jni.h"

#include <ScopedUtfChars.h>
#include <ScopedPrimitiveArray.h>

#include <utils/Errors.h>
#include <utils/Log.h>
#include <hardware/mcu.h>

namespace android {

static jlong nativeOpen(JNIEnv* env, jclass clazz) {
    mcu_module_t* module = NULL;
    status_t err = hw_get_module(MCU_HARDWARE_MODULE_ID,
            (hw_module_t const**)&module);
    if (err) {
        ALOGE("Couldn't load %s module (%s)", MCU_HARDWARE_MODULE_ID, strerror(-err));
        return 0;
    }

    err = module->init(module);
    if (err) {
        ALOGE("Couldn't initialize %s module (%s)", MCU_HARDWARE_MODULE_ID, strerror(-err));
        return 0;
    }

    return reinterpret_cast<jlong>(module);
}

static jbyteArray nativeSendMessage(JNIEnv* env, jclass clazz,
        jlong ptr, jstring msgStr, jbyteArray argArray) {
    mcu_module_t* module = reinterpret_cast<mcu_module_t*>(ptr);

    ScopedUtfChars msg(env, msgStr);
    ALOGV("Sending message %s to MCU", msg.c_str());

    void* result = NULL;
    size_t resultSize = 0;
    status_t err;
    if (argArray) {
        ScopedByteArrayRO arg(env, argArray);
        err = module->sendMessage(module, msg.c_str(), arg.get(), arg.size(),
                &result, &resultSize);
    } else {
        err = module->sendMessage(module, msg.c_str(), NULL, 0, &result, &resultSize);
    }
    if (err) {
        ALOGE("Couldn't send message to MCU (%s)", strerror(-err));
        return NULL;
    }

    if (!result) {
        return NULL;
    }

    jbyteArray resultArray = env->NewByteArray(resultSize);
    if (resultArray) {
        env->SetByteArrayRegion(resultArray, 0, resultSize, static_cast<jbyte*>(result));
    }
    free(result);
    return resultArray;
}

static JNINativeMethod gMcuHalMethods[] = {
    /* name, signature, funcPtr */
    { "nativeOpen", "()J",
            (void*) nativeOpen },
    { "nativeSendMessage", "(JLjava/lang/String;[B)[B",
            (void*) nativeSendMessage },
};

int register_android_server_dreams_McuHal(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "com/android/server/dreams/McuHal",
            gMcuHalMethods, NELEM(gMcuHalMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

} /* namespace android */
