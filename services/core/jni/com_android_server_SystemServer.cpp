/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include <binder/IServiceManager.h>
#include <hidl/HidlTransportSupport.h>

#include <schedulerservice/SchedulingPolicyService.h>
#include <sensorservice/SensorService.h>
#include <sensorservicehidl/SensorManager.h>

#include <bionic_malloc.h>

#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/AndroidThreads.h>

namespace android {

static void android_server_SystemServer_startSensorService(JNIEnv* /* env */, jobject /* clazz */) {
    char propBuf[PROPERTY_VALUE_MAX];
    property_get("system_init.startsensorservice", propBuf, "1");
    if (strcmp(propBuf, "1") == 0) {
        SensorService::publish(false /* allowIsolated */,
                               IServiceManager::DUMP_FLAG_PRIORITY_CRITICAL);
    }

}

static void android_server_SystemServer_startHidlServices(JNIEnv* env, jobject /* clazz */) {
    using ::android::frameworks::schedulerservice::V1_0::ISchedulingPolicyService;
    using ::android::frameworks::schedulerservice::V1_0::implementation::SchedulingPolicyService;
    using ::android::frameworks::sensorservice::V1_0::ISensorManager;
    using ::android::frameworks::sensorservice::V1_0::implementation::SensorManager;
    using ::android::hardware::configureRpcThreadpool;

    status_t err;

    configureRpcThreadpool(5, false /* callerWillJoin */);

    JavaVM *vm;
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Cannot get Java VM");

    sp<ISensorManager> sensorService = new SensorManager(vm);
    err = sensorService->registerAsService();
    ALOGE_IF(err != OK, "Cannot register %s: %d", ISensorManager::descriptor, err);

    sp<ISchedulingPolicyService> schedulingService = new SchedulingPolicyService();
    err = schedulingService->registerAsService();
    ALOGE_IF(err != OK, "Cannot register %s: %d", ISchedulingPolicyService::descriptor, err);
}

static void android_server_SystemServer_initZygoteChildHeapProfiling(JNIEnv* /* env */,
                                                                     jobject /* clazz */) {
   android_mallopt(M_INIT_ZYGOTE_CHILD_PROFILING, nullptr, 0);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "startSensorService", "()V", (void*) android_server_SystemServer_startSensorService },
    { "startHidlServices", "()V", (void*) android_server_SystemServer_startHidlServices },
    { "initZygoteChildHeapProfiling", "()V",
      (void*) android_server_SystemServer_initZygoteChildHeapProfiling },
};

int register_android_server_SystemServer(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/server/SystemServer",
            gMethods, NELEM(gMethods));
}

}; // namespace android
