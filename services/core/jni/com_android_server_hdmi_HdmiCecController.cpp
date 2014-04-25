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

#define LOG_TAG "HdmiCecControllerJni"

#define LOG_NDEBUG 1

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <hardware/hdmi_cec.h>

namespace android {

static struct {
    jmethodID handleMessage;
} gHdmiCecControllerClassInfo;


class HdmiCecController {
public:
    HdmiCecController(jobject callbacksObj);

private:
    static void onReceived(const hdmi_event_t* event, void* arg);

    jobject mCallbacksObj;
};

HdmiCecController::HdmiCecController(jobject callbacksObj) :
    mCallbacksObj(callbacksObj) {
}

// static
void HdmiCecController::onReceived(const hdmi_event_t* event, void* arg) {
    HdmiCecController* handler = static_cast<HdmiCecController*>(arg);
    if (handler == NULL) {
        return;
    }

    // TODO: propagate message to Java layer.
}


//------------------------------------------------------------------------------
static jlong nativeInit(JNIEnv* env, jclass clazz, jobject callbacksObj) {
    // TODO: initialize hal and pass it to controller if ready.

    HdmiCecController* controller = new HdmiCecController(
            env->NewGlobalRef(callbacksObj));

    return reinterpret_cast<jlong>(controller);
}

static JNINativeMethod sMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "(Lcom/android/server/hdmi/HdmiCecController;)J",
            (void *) nativeInit },
};

#define CLASS_PATH "com/android/server/hdmi/HdmiCecController"

int register_android_server_hdmi_HdmiCecController(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, CLASS_PATH, sMethods, NELEM(sMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");
    return 0;
}

}  /* namespace android */
