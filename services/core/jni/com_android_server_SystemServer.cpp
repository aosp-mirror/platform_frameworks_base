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

#include <android/binder_manager.h>
#include <android/binder_stability.h>
#include <android/hidl/manager/1.2/IServiceManager.h>
#include <binder/IServiceManager.h>
#include <hidl/HidlTransportSupport.h>
#include <incremental_service.h>

#include <memtrackproxy/MemtrackProxy.h>
#include <schedulerservice/SchedulingPolicyService.h>
#include <sensorservicehidl/SensorManager.h>
#include <stats/StatsAidl.h>
#include <stats/StatsHal.h>

#include <bionic/malloc.h>
#include <bionic/reserved_signals.h>

#include <android-base/properties.h>
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/AndroidThreads.h>

using namespace std::chrono_literals;

namespace {

static void startStatsAidlService() {
    using aidl::android::frameworks::stats::IStats;
    using aidl::android::frameworks::stats::StatsHal;

    std::shared_ptr<StatsHal> statsService = ndk::SharedRefBase::make<StatsHal>();

    const std::string instance = std::string() + IStats::descriptor + "/default";
    const binder_exception_t err =
            AServiceManager_addService(statsService->asBinder().get(), instance.c_str());
    LOG_ALWAYS_FATAL_IF(err != EX_NONE, "Cannot register AIDL %s: %d", instance.c_str(), err);
}

static void startStatsHidlService() {
    using android::frameworks::stats::V1_0::IStats;
    using android::frameworks::stats::V1_0::implementation::StatsHal;

    android::sp<IStats> statsHal = new StatsHal();
    const android::status_t err = statsHal->registerAsService();
    ALOGW_IF(err != android::OK, "Cannot register HIDL %s: %d", IStats::descriptor, err);
}

} // namespace

namespace android {

static void android_server_SystemServer_startIStatsService(JNIEnv* /* env */, jobject /* clazz */) {
    startStatsHidlService();
    startStatsAidlService();
}

static void android_server_SystemServer_startMemtrackProxyService(JNIEnv* env,
                                                                  jobject /* clazz */) {
    using aidl::android::hardware::memtrack::MemtrackProxy;

    const char* memtrackProxyService = "memtrack.proxy";

    std::shared_ptr<MemtrackProxy> memtrack_proxy = ndk::SharedRefBase::make<MemtrackProxy>();
    auto binder = memtrack_proxy->asBinder();

    AIBinder_forceDowngradeToLocalStability(binder.get());

    const binder_exception_t err = AServiceManager_addService(binder.get(), memtrackProxyService);
    LOG_ALWAYS_FATAL_IF(err != EX_NONE, "Cannot register %s: %d", memtrackProxyService, err);
}

static void android_server_SystemServer_startHidlServices(JNIEnv* env, jobject /* clazz */) {
    using ::android::frameworks::schedulerservice::V1_0::ISchedulingPolicyService;
    using ::android::frameworks::schedulerservice::V1_0::implementation::SchedulingPolicyService;
    using ::android::frameworks::sensorservice::V1_0::ISensorManager;
    using ::android::frameworks::sensorservice::V1_0::implementation::SensorManager;
    using ::android::hardware::configureRpcThreadpool;
    using ::android::hidl::manager::V1_0::IServiceManager;

    status_t err;

    configureRpcThreadpool(5, false /* callerWillJoin */);

    JavaVM *vm;
    LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&vm) != JNI_OK, "Cannot get Java VM");

    sp<ISensorManager> sensorService = new SensorManager(vm);
    err = sensorService->registerAsService();
    LOG_ALWAYS_FATAL_IF(err != OK, "Cannot register %s: %d", ISensorManager::descriptor, err);

    sp<ISchedulingPolicyService> schedulingService = new SchedulingPolicyService();
    if (IServiceManager::Transport::HWBINDER ==
        hardware::defaultServiceManager1_2()->getTransport(ISchedulingPolicyService::descriptor,
                                                           "default")) {
        err = schedulingService->registerAsService("default");
        LOG_ALWAYS_FATAL_IF(err != OK, "Cannot register %s: %d",
                            ISchedulingPolicyService::descriptor, err);
    } else {
        ALOGW("%s is deprecated. Skipping registration.", ISchedulingPolicyService::descriptor);
    }
}

static void android_server_SystemServer_initZygoteChildHeapProfiling(JNIEnv* /* env */,
                                                                     jobject /* clazz */) {
    android_mallopt(M_INIT_ZYGOTE_CHILD_PROFILING, nullptr, 0);
}

static void android_server_SystemServer_fdtrackAbort(JNIEnv*, jobject) {
    sigval val;
    val.sival_int = 1;
    sigqueue(getpid(), BIONIC_SIGNAL_FDTRACK, val);
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
        {"startIStatsService", "()V", (void*)android_server_SystemServer_startIStatsService},
        {"startMemtrackProxyService", "()V",
         (void*)android_server_SystemServer_startMemtrackProxyService},
        {"startHidlServices", "()V", (void*)android_server_SystemServer_startHidlServices},
        {"initZygoteChildHeapProfiling", "()V",
         (void*)android_server_SystemServer_initZygoteChildHeapProfiling},
        {"fdtrackAbort", "()V", (void*)android_server_SystemServer_fdtrackAbort},
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
