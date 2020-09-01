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

#include <dlfcn.h>
#include <pthread.h>

#include <chrono>
#include <thread>

#include <jni.h>
#include <nativehelper/JNIHelp.h>

#include <binder/IServiceManager.h>
#include <hidl/HidlTransportSupport.h>
#include <incremental_service.h>

#include <schedulerservice/SchedulingPolicyService.h>
#include <sensorservice/SensorService.h>
#include <sensorservicehidl/SensorManager.h>
#include <stats/StatsHal.h>

#include <bionic/malloc.h>
#include <bionic/reserved_signals.h>

#include <android-base/properties.h>
#include <cutils/properties.h>
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/AndroidThreads.h>

using android::base::GetIntProperty;
using namespace std::chrono_literals;

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
    using ::android::frameworks::stats::V1_0::IStats;
    using ::android::frameworks::stats::V1_0::implementation::StatsHal;
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

    sp<IStats> statsHal = new StatsHal();
    err = statsHal->registerAsService();
    ALOGE_IF(err != OK, "Cannot register %s: %d", IStats::descriptor, err);
}

static void android_server_SystemServer_initZygoteChildHeapProfiling(JNIEnv* /* env */,
                                                                     jobject /* clazz */) {
    android_mallopt(M_INIT_ZYGOTE_CHILD_PROFILING, nullptr, 0);
}

static int get_current_max_fd() {
    // Not actually guaranteed to be the max, but close enough for our purposes.
    int fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    LOG_ALWAYS_FATAL_IF(fd == -1, "failed to open /dev/null: %s", strerror(errno));
    close(fd);
    return fd;
}

static const char kFdLeakEnableThresholdProperty[] = "persist.sys.debug.fdtrack_enable_threshold";
static const char kFdLeakAbortThresholdProperty[] = "persist.sys.debug.fdtrack_abort_threshold";
static const char kFdLeakCheckIntervalProperty[] = "persist.sys.debug.fdtrack_interval";

static void android_server_SystemServer_spawnFdLeakCheckThread(JNIEnv*, jobject) {
    std::thread([]() {
        pthread_setname_np(pthread_self(), "FdLeakCheckThread");
        bool loaded = false;
        while (true) {
            const int enable_threshold = GetIntProperty(kFdLeakEnableThresholdProperty, 1024);
            const int abort_threshold = GetIntProperty(kFdLeakAbortThresholdProperty, 2048);
            const int check_interval = GetIntProperty(kFdLeakCheckIntervalProperty, 120);
            int max_fd = get_current_max_fd();
            if (max_fd > enable_threshold && !loaded) {
                loaded = true;
                ALOGE("fd count above threshold of %d, starting fd backtraces", enable_threshold);
                if (dlopen("libfdtrack.so", RTLD_GLOBAL) == nullptr) {
                    ALOGE("failed to load libfdtrack.so: %s", dlerror());
                }
            } else if (max_fd > abort_threshold) {
                raise(BIONIC_SIGNAL_FDTRACK);

                // Wait for a bit to allow fdtrack to dump backtraces to logcat.
                std::this_thread::sleep_for(5s);

                LOG_ALWAYS_FATAL(
                    "b/140703823: aborting due to fd leak: check logs for fd "
                    "backtraces");
            }

            std::this_thread::sleep_for(std::chrono::seconds(check_interval));
        }
    }).detach();
}

static jlong android_server_SystemServer_startIncrementalService(JNIEnv* env, jclass klass,
                                                                 jobject self) {
    return Incremental_IncrementalService_Start(env);
}

static void android_server_SystemServer_setIncrementalServiceSystemReady(JNIEnv* env, jclass klass,
                                                                         jlong handle) {
    Incremental_IncrementalService_OnSystemReady(handle);
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"startSensorService", "()V", (void*)android_server_SystemServer_startSensorService},
        {"startHidlServices", "()V", (void*)android_server_SystemServer_startHidlServices},
        {"initZygoteChildHeapProfiling", "()V",
         (void*)android_server_SystemServer_initZygoteChildHeapProfiling},
        {"spawnFdLeakCheckThread", "()V",
         (void*)android_server_SystemServer_spawnFdLeakCheckThread},
        {"startIncrementalService", "()J",
         (void*)android_server_SystemServer_startIncrementalService},
        {"setIncrementalServiceSystemReady", "(J)V",
         (void*)android_server_SystemServer_setIncrementalServiceSystemReady},
};

int register_android_server_SystemServer(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/server/SystemServer",
            gMethods, NELEM(gMethods));
}

}; // namespace android
