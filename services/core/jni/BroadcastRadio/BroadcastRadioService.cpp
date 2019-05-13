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

#define LOG_TAG "BroadcastRadioService.jni"
#define LOG_NDEBUG 0

#include "BroadcastRadioService.h"

#include "Tuner.h"
#include "convert.h"

#include <android/hardware/broadcastradio/1.1/IBroadcastRadio.h>
#include <android/hardware/broadcastradio/1.1/IBroadcastRadioFactory.h>
#include <android/hidl/manager/1.2/IServiceManager.h>
#include <broadcastradio-utils-1x/Utils.h>
#include <core_jni_helpers.h>
#include <hidl/ServiceManagement.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>

namespace android {
namespace server {
namespace BroadcastRadio {
namespace BroadcastRadioService {

using std::lock_guard;
using std::mutex;

using hardware::Return;
using hardware::hidl_string;
using hardware::hidl_vec;

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;
namespace utils = hardware::broadcastradio::utils;

using V1_0::BandConfig;
using V1_0::Class;
using V1_0::ITuner;
using V1_0::MetaData;
using V1_0::ProgramInfo;
using V1_0::Result;
using utils::HalRevision;

static mutex gContextMutex;

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

struct Module {
    sp<V1_0::IBroadcastRadio> radioModule;
    HalRevision halRev;
    std::vector<hardware::broadcastradio::V1_0::BandConfig> bands;
};

struct ServiceContext {
    ServiceContext() {}

    std::vector<Module> mModules;

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
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);

    auto nativeContext = new ServiceContext();
    static_assert(sizeof(jlong) >= sizeof(nativeContext), "jlong is smaller than a pointer");
    return reinterpret_cast<jlong>(nativeContext);
}

static void nativeFinalize(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);

    auto ctx = reinterpret_cast<ServiceContext*>(nativeContext);
    delete ctx;
}

static jobject nativeLoadModules(JNIEnv *env, jobject obj, jlong nativeContext) {
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    // Get list of registered HIDL HAL implementations.
    auto manager = hardware::defaultServiceManager1_2();
    hidl_vec<hidl_string> services;
    if (manager == nullptr) {
        ALOGE("Can't reach service manager, using default service implementation only");
        services = std::vector<hidl_string>({ "default" });
    } else {
        manager->listManifestByInterface(V1_0::IBroadcastRadioFactory::descriptor,
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

        auto halRev = HalRevision::V1_0;
        auto halMinor = 0;
        if (V1_1::IBroadcastRadioFactory::castFrom(factory).withDefault(nullptr) != nullptr) {
            halRev = HalRevision::V1_1;
            halMinor = 1;
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
            ctx.mModules.push_back({module10, halRev, {}});
            auto& nModule = ctx.mModules[idx];
            ALOGI("loaded broadcast radio module %zu: %s:%s (HAL 1.%d)",
                    idx, serviceName.c_str(), V1_0::toString(clazz).c_str(), halMinor);

            JavaRef<jobject> jModule = nullptr;
            Result halResult = Result::OK;
            Return<void> hidlResult;
            if (module11 != nullptr) {
                hidlResult = module11->getProperties_1_1([&](const V1_1::Properties& properties) {
                    nModule.bands = properties.base.bands;
                    jModule = convert::ModulePropertiesFromHal(env, properties, idx, serviceName);
                });
            } else {
                hidlResult = module10->getProperties([&](Result result,
                        const V1_0::Properties& properties) {
                    halResult = result;
                    if (result != Result::OK) return;
                    nModule.bands = properties.bands;
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
    ALOGV("%s", __func__);
    lock_guard<mutex> lk(gContextMutex);
    auto& ctx = getNativeContext(nativeContext);

    if (callback == nullptr) {
        ALOGE("Callback is empty");
        return nullptr;
    }

    if (moduleId < 0 || static_cast<size_t>(moduleId) >= ctx.mModules.size()) {
        ALOGE("Invalid module ID: %d", moduleId);
        return nullptr;
    }

    ALOGI("Opening tuner %d", moduleId);
    auto module = ctx.mModules[moduleId];

    Region region;
    BandConfig bandConfigHal;
    if (bandConfig != nullptr) {
        bandConfigHal = convert::BandConfigToHal(env, bandConfig, region);
    } else {
        region = Region::INVALID;
        if (module.bands.size() == 0) {
            ALOGE("No bands defined");
            return nullptr;
        }
        bandConfigHal = module.bands[0];

        /* Prefer FM to workaround possible program list fetching limitation
         * (if tuner scans only configured band for programs). */
        auto fmIt = std::find_if(module.bands.begin(), module.bands.end(),
            [](const BandConfig & band) { return utils::isFm(band.type); });
        if (fmIt != module.bands.end()) bandConfigHal = *fmIt;

        if (bandConfigHal.spacings.size() > 1) {
            bandConfigHal.spacings = hidl_vec<uint32_t>({ *std::min_element(
                    bandConfigHal.spacings.begin(), bandConfigHal.spacings.end()) });
        }
    }

    auto tuner = make_javaref(env, env->NewObject(gjni.Tuner.clazz, gjni.Tuner.cstor,
            callback, module.halRev, region, withAudio, bandConfigHal.type));
    if (tuner == nullptr) {
        ALOGE("Unable to create new tuner object.");
        return nullptr;
    }

    auto tunerCb = Tuner::getNativeCallback(env, tuner);
    Result halResult;
    sp<ITuner> halTuner = nullptr;

    auto hidlResult = module.radioModule->openTuner(bandConfigHal, withAudio, tunerCb,
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

    Tuner::assignHalInterfaces(env, tuner, module.radioModule, halTuner);
    ALOGD("Opened tuner %p", halTuner.get());

    bool isConnected = true;
    halTuner->getConfiguration([&](Result result, const BandConfig& config) {
        if (result == Result::OK) isConnected = config.antennaConnected;
    });
    if (!isConnected) {
        tunerCb->antennaStateChange(false);
    }

    return tuner.release();
}

static const JNINativeMethod gRadioServiceMethods[] = {
    { "nativeInit", "()J", (void*)nativeInit },
    { "nativeFinalize", "(J)V", (void*)nativeFinalize },
    { "nativeLoadModules", "(J)Ljava/util/List;", (void*)nativeLoadModules },
    { "nativeOpenTuner", "(JILandroid/hardware/radio/RadioManager$BandConfig;Z"
            "Landroid/hardware/radio/ITunerCallback;)Lcom/android/server/broadcastradio/hal1/Tuner;",
            (void*)nativeOpenTuner },
};

} // namespace BroadcastRadioService
} // namespace BroadcastRadio
} // namespace server

void register_android_server_broadcastradio_BroadcastRadioService(JNIEnv *env) {
    using namespace server::BroadcastRadio::BroadcastRadioService;

    register_android_server_broadcastradio_convert(env);

    auto tunerClass = FindClassOrDie(env, "com/android/server/broadcastradio/hal1/Tuner");
    gjni.Tuner.clazz = MakeGlobalRefOrDie(env, tunerClass);
    gjni.Tuner.cstor = GetMethodIDOrDie(env, tunerClass, "<init>",
            "(Landroid/hardware/radio/ITunerCallback;IIZI)V");

    auto arrayListClass = FindClassOrDie(env, "java/util/ArrayList");
    gjni.ArrayList.clazz = MakeGlobalRefOrDie(env, arrayListClass);
    gjni.ArrayList.cstor = GetMethodIDOrDie(env, arrayListClass, "<init>", "()V");
    gjni.ArrayList.add = GetMethodIDOrDie(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");

    auto res = jniRegisterNativeMethods(env,
            "com/android/server/broadcastradio/hal1/BroadcastRadioService",
            gRadioServiceMethods, NELEM(gRadioServiceMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");
}

} // namespace android
