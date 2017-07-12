/**
 * Copyright (C) 2017 The Android Open Source Project
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

#define LOG_TAG "radio.RadioService.jni"
#define LOG_NDEBUG 0

#include "com_android_server_radio_RadioService.h"

#include "com_android_server_radio_Tuner.h"
#include "com_android_server_radio_convert.h"

#include <android/hardware/broadcastradio/1.1/IBroadcastRadio.h>
#include <android/hardware/broadcastradio/1.1/IBroadcastRadioFactory.h>
#include <android/hidl/manager/1.0/IServiceManager.h>
#include <core_jni_helpers.h>
#include <hidl/ServiceManagement.h>
#include <utils/Log.h>
#include <JNIHelp.h>

namespace android {
namespace server {
namespace radio {
namespace RadioService {

using hardware::Return;
using hardware::hidl_string;
using hardware::hidl_vec;

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;

using V1_0::Class;
using V1_0::Result;

using V1_0::BandConfig;
using V1_0::ProgramInfo;
using V1_0::MetaData;
using V1_0::ITuner;

static Mutex gContextMutex;

static struct {
    struct {
        jclass clazz;
        jmethodID cstor;
        jmethodID add;
    } ArrayList;
    struct {
        jclass clazz;
        jmethodID cstor;
    } Tuner;
} gjni;

struct ServiceContext {
    ServiceContext() {}

    std::vector<sp<V1_0::IBroadcastRadio>> mModules;

private:
    DISALLOW_COPY_AND_ASSIGN(ServiceContext);
};

const std::vector<Class> gAllClasses = {
    Class::AM_FM,
    Class::SAT,
    Class::DT,
};


/**
 * Always lock gContextMutex when using native context.
 */
static ServiceContext& getNativeContext(jlong nativeContextHandle) {
    auto nativeContext = reinterpret_cast<ServiceContext*>(nativeContextHandle);
    LOG_ALWAYS_FATAL_IF(nativeContext == nullptr, "Native context not initialized");
    return *nativeContext;
}

static jlong nativeInit(JNIEnv *env, jobject obj) {
    ALOGV("nativeInit()");
    AutoMutex _l(gContextMutex);

    auto nativeContext = new ServiceContext();
    static_assert(sizeof(jlong) >= sizeof(nativeContext), "jlong is smaller than a pointer");
    return reinterpret_cast<jlong>(nativeContext);
}

static void nativeFinalize(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("nativeFinalize()");
    AutoMutex _l(gContextMutex);

    auto ctx = reinterpret_cast<ServiceContext*>(nativeContext);
    delete ctx;
}

static jobject nativeLoadModules(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("nativeLoadModules()");
    AutoMutex _l(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    // Get list of registered HIDL HAL implementations.
    auto manager = hardware::defaultServiceManager();
    hidl_vec<hidl_string> services;
    if (manager == nullptr) {
        ALOGE("Can't reach service manager, using default service implementation only");
        services = std::vector<hidl_string>({ "default" });
    } else {
        manager->listByInterface(V1_0::IBroadcastRadioFactory::descriptor,
                [&services](const hidl_vec<hidl_string> &registered) {
            services = registered;
        });
    }

    // Scan provided list for actually implemented modules.
    ctx.mModules.clear();
    auto jModules = make_javaref(env, env->NewObject(gjni.ArrayList.clazz, gjni.ArrayList.cstor));
    for (auto&& serviceName : services) {
        ALOGV("checking service: %s", serviceName.c_str());

        auto factory = V1_0::IBroadcastRadioFactory::getService(serviceName);
        if (factory == nullptr) {
            ALOGE("can't load service %s", serviceName.c_str());
            continue;
        }

        // Second level of scanning - that's unfortunate.
        for (auto&& clazz : gAllClasses) {
            sp<V1_0::IBroadcastRadio> module10 = nullptr;
            sp<V1_1::IBroadcastRadio> module11 = nullptr;
            factory->connectModule(clazz, [&](Result res, const sp<V1_0::IBroadcastRadio>& module) {
                if (res == Result::OK) {
                    module10 = module;
                    module11 = V1_1::IBroadcastRadio::castFrom(module).withDefault(nullptr);
                } else if (res != Result::INVALID_ARGUMENTS) {
                    ALOGE("couldn't load %s:%s module",
                            serviceName.c_str(), V1_0::toString(clazz).c_str());
                }
            });
            if (module10 == nullptr) continue;

            auto idx = ctx.mModules.size();
            ctx.mModules.push_back(module10);
            ALOGI("loaded broadcast radio module %zu: %s:%s",
                    idx, serviceName.c_str(), V1_0::toString(clazz).c_str());

            JavaRef<jobject> jModule = nullptr;
            Result halResult = Result::OK;
            Return<void> hidlResult;
            if (module11 != nullptr) {
                hidlResult = module11->getProperties_1_1([&](const V1_1::Properties& properties) {
                    jModule = convert::ModulePropertiesFromHal(env, properties, idx, serviceName);
                });
            } else {
                hidlResult = module10->getProperties([&](Result result,
                        const V1_0::Properties& properties) {
                    halResult = result;
                    if (result != Result::OK) return;
                    jModule = convert::ModulePropertiesFromHal(env, properties, idx, serviceName);
                });
            }
            if (convert::ThrowIfFailed(env, hidlResult, halResult)) return nullptr;

            env->CallBooleanMethod(jModules.get(), gjni.ArrayList.add, jModule.get());
        }
    }

    return jModules.release();
}

static jobject nativeOpenTuner(JNIEnv *env, jobject obj, long nativeContext, jint moduleId,
        jobject bandConfig, bool withAudio, jobject callback) {
    ALOGV("nativeOpenTuner()");
    AutoMutex _l(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    if (callback == nullptr) {
        ALOGE("Callback is empty");
        return nullptr;
    }

    if (moduleId < 0 || static_cast<size_t>(moduleId) >= ctx.mModules.size()) {
        ALOGE("Invalid module ID: %d", moduleId);
        return nullptr;
    }
    auto module = ctx.mModules[moduleId];

    HalRevision halRev;
    if (V1_1::IBroadcastRadio::castFrom(module).withDefault(nullptr) != nullptr) {
        ALOGI("Opening tuner %d with broadcast radio HAL 1.1", moduleId);
        halRev = HalRevision::V1_1;
    } else {
        ALOGI("Opening tuner %d with broadcast radio HAL 1.0", moduleId);
        halRev = HalRevision::V1_0;
    }

    Region region;
    BandConfig bandConfigHal = convert::BandConfigToHal(env, bandConfig, region);

    auto tuner = make_javaref(env, env->NewObject(gjni.Tuner.clazz, gjni.Tuner.cstor,
            callback, halRev, region, withAudio, bandConfigHal.type));
    if (tuner == nullptr) {
        ALOGE("Unable to create new tuner object.");
        return nullptr;
    }

    auto tunerCb = Tuner::getNativeCallback(env, tuner);
    Result halResult;
    sp<ITuner> halTuner = nullptr;

    auto hidlResult = module->openTuner(bandConfigHal, withAudio, tunerCb,
            [&](Result result, const sp<ITuner>& tuner) {
                halResult = result;
                halTuner = tuner;
            });
    if (!hidlResult.isOk() || halResult != Result::OK || halTuner == nullptr) {
        ALOGE("Couldn't open tuner");
        ALOGE_IF(hidlResult.isOk(), "halResult = %d", halResult);
        ALOGE_IF(!hidlResult.isOk(), "hidlResult = %s", hidlResult.description().c_str());
        return nullptr;
    }

    Tuner::setHalTuner(env, tuner, halTuner);
    ALOGD("Opened tuner %p", halTuner.get());
    return tuner.release();
}

static const JNINativeMethod gRadioServiceMethods[] = {
    { "nativeInit", "()J", (void*)nativeInit },
    { "nativeFinalize", "(J)V", (void*)nativeFinalize },
    { "nativeLoadModules", "(J)Ljava/util/List;", (void*)nativeLoadModules },
    { "nativeOpenTuner", "(JILandroid/hardware/radio/RadioManager$BandConfig;Z"
            "Landroid/hardware/radio/ITunerCallback;)Lcom/android/server/radio/Tuner;",
            (void*)nativeOpenTuner },
};

} // namespace RadioService
} // namespace radio
} // namespace server

void register_android_server_radio_RadioService(JNIEnv *env) {
    using namespace server::radio::RadioService;

    register_android_server_radio_convert(env);

    auto tunerClass = FindClassOrDie(env, "com/android/server/radio/Tuner");
    gjni.Tuner.clazz = MakeGlobalRefOrDie(env, tunerClass);
    gjni.Tuner.cstor = GetMethodIDOrDie(env, tunerClass, "<init>",
            "(Landroid/hardware/radio/ITunerCallback;IIZI)V");

    auto arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gjni.ArrayList.clazz = MakeGlobalRefOrDie(env, arrayListClass);
    gjni.ArrayList.cstor = GetMethodIDOrDie(env, arrayListClass, "<init>", "()V");
    gjni.ArrayList.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");

    auto res = jniRegisterNativeMethods(env, "com/android/server/radio/RadioService",
            gRadioServiceMethods, NELEM(gRadioServiceMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
}

} // namespace android
