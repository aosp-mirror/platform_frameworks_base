/*
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

#define LOG_TAG "VintfObject"
//#define LOG_NDEBUG 0
#include <android-base/logging.h>

#include <vector>
#include <string>

#include <nativehelper/JNIHelp.h>
#include <vintf/VintfObject.h>
#include <vintf/parse_string.h>
#include <vintf/parse_xml.h>

#include "core_jni_helpers.h"

static jclass gString;
static jclass gHashMapClazz;
static jmethodID gHashMapInit;
static jmethodID gHashMapPut;
static jclass gLongClazz;
static jmethodID gLongValueOf;

namespace android {

using vintf::CompatibilityMatrix;
using vintf::HalManifest;
using vintf::Level;
using vintf::SchemaType;
using vintf::to_string;
using vintf::toXml;
using vintf::Version;
using vintf::VintfObject;
using vintf::Vndk;
using vintf::CheckFlags::ENABLE_ALL_CHECKS;

template<typename V>
static inline jobjectArray toJavaStringArray(JNIEnv* env, const V& v) {
    size_t i;
    typename V::const_iterator it;
    jobjectArray ret = env->NewObjectArray(v.size(), gString, NULL /* init element */);
    for (i = 0, it = v.begin(); it != v.end(); ++i, ++it) {
        env->SetObjectArrayElement(ret, i, env->NewStringUTF(it->c_str()));
    }
    return ret;
}

template <typename T>
static void tryAddSchema(const std::shared_ptr<const T>& object, const std::string& description,
                         std::vector<std::string>* cStrings) {
    if (object == nullptr) {
        LOG(WARNING) << __FUNCTION__ << "Cannot get " << description;
    } else {
        cStrings->push_back(toXml(*object));
    }
}

static void tryAddHalNamesAndVersions(const std::shared_ptr<const HalManifest>& manifest,
        const std::string& description,
        std::set<std::string> *output) {
    if (manifest == nullptr) {
        LOG(WARNING) << __FUNCTION__ << "Cannot get " << description;
    } else {
        auto names = manifest->getHalNamesAndVersions();
        output->insert(names.begin(), names.end());
    }
}

static jobjectArray android_os_VintfObject_report(JNIEnv* env, jclass)
{
    std::vector<std::string> cStrings;

    tryAddSchema(VintfObject::GetDeviceHalManifest(), "device manifest", &cStrings);
    tryAddSchema(VintfObject::GetFrameworkHalManifest(), "framework manifest", &cStrings);
    tryAddSchema(VintfObject::GetDeviceCompatibilityMatrix(), "device compatibility matrix",
                 &cStrings);
    tryAddSchema(VintfObject::GetFrameworkCompatibilityMatrix(), "framework compatibility matrix",
                 &cStrings);

    return toJavaStringArray(env, cStrings);
}

static jint android_os_VintfObject_verifyBuildAtBoot(JNIEnv* env, jclass) {
    std::string error;
    int32_t status =
            VintfObject::GetInstance()
                    ->checkCompatibility(&error, ENABLE_ALL_CHECKS.disableAvb().disableKernel());
    if (status)
        LOG(WARNING) << "VintfObject.verifyBuildAtBoot() returns " << status << ": " << error;
    return status;
}

static jobjectArray android_os_VintfObject_getHalNamesAndVersions(JNIEnv* env, jclass) {
    std::set<std::string> halNames;
    tryAddHalNamesAndVersions(VintfObject::GetDeviceHalManifest(),
            "device manifest", &halNames);
    tryAddHalNamesAndVersions(VintfObject::GetFrameworkHalManifest(),
            "framework manifest", &halNames);
    return toJavaStringArray(env, halNames);
}

static jstring android_os_VintfObject_getSepolicyVersion(JNIEnv* env, jclass) {
    std::shared_ptr<const HalManifest> manifest = VintfObject::GetDeviceHalManifest();
    if (manifest == nullptr || manifest->type() != SchemaType::DEVICE) {
        LOG(WARNING) << __FUNCTION__ << "Cannot get device manifest";
        return nullptr;
    }
    std::string cString = to_string(manifest->sepolicyVersion());
    return env->NewStringUTF(cString.c_str());
}

static jstring android_os_VintfObject_getPlatformSepolicyVersion(JNIEnv* env, jclass) {
    std::shared_ptr<const CompatibilityMatrix> matrix =
            VintfObject::GetFrameworkCompatibilityMatrix();
    if (matrix == nullptr || matrix->type() != SchemaType::FRAMEWORK) {
        jniThrowRuntimeException(env, "Cannot get framework compatibility matrix");
        return nullptr;
    }

    auto versions = matrix->getSepolicyVersions();
    if (versions.empty()) {
        jniThrowRuntimeException(env,
                                 "sepolicy_version in framework compatibility matrix is empty");
        return nullptr;
    }

    Version latest;
    for (const auto& range : versions) {
        latest = std::max(latest, range.maxVer());
    }
    return env->NewStringUTF(to_string(latest).c_str());
}

static jobject android_os_VintfObject_getVndkSnapshots(JNIEnv* env, jclass) {
    std::shared_ptr<const HalManifest> manifest = VintfObject::GetFrameworkHalManifest();
    if (manifest == nullptr || manifest->type() != SchemaType::FRAMEWORK) {
        LOG(WARNING) << __FUNCTION__ << "Cannot get framework manifest";
        return nullptr;
    }
    jobject jMap = env->NewObject(gHashMapClazz, gHashMapInit);
    for (const auto &vndk : manifest->vendorNdks()) {
        const std::string& key = vndk.version();
        env->CallObjectMethod(jMap, gHashMapPut,
                env->NewStringUTF(key.c_str()), toJavaStringArray(env, vndk.libraries()));
    }
    return jMap;
}

static jobject android_os_VintfObject_getTargetFrameworkCompatibilityMatrixVersion(JNIEnv* env, jclass) {
    std::shared_ptr<const HalManifest> manifest = VintfObject::GetDeviceHalManifest();
    if (manifest == nullptr || manifest->level() == Level::UNSPECIFIED) {
        return nullptr;
    }
    return env->CallStaticObjectMethod(gLongClazz, gLongValueOf, static_cast<jlong>(manifest->level()));
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gVintfObjectMethods[] = {
        {"report", "()[Ljava/lang/String;", (void*)android_os_VintfObject_report},
        {"verifyBuildAtBoot", "()I", (void*)android_os_VintfObject_verifyBuildAtBoot},
        {"getHalNamesAndVersions", "()[Ljava/lang/String;",
         (void*)android_os_VintfObject_getHalNamesAndVersions},
        {"getSepolicyVersion", "()Ljava/lang/String;",
         (void*)android_os_VintfObject_getSepolicyVersion},
        {"getPlatformSepolicyVersion", "()Ljava/lang/String;",
         (void*)android_os_VintfObject_getPlatformSepolicyVersion},
        {"getVndkSnapshots", "()Ljava/util/Map;", (void*)android_os_VintfObject_getVndkSnapshots},
        {"getTargetFrameworkCompatibilityMatrixVersion", "()Ljava/lang/Long;",
         (void*)android_os_VintfObject_getTargetFrameworkCompatibilityMatrixVersion},
};

const char* const kVintfObjectPathName = "android/os/VintfObject";

int register_android_os_VintfObject(JNIEnv* env)
{

    gString = MakeGlobalRefOrDie(env, FindClassOrDie(env, "java/lang/String"));
    gHashMapClazz = MakeGlobalRefOrDie(env, FindClassOrDie(env, "java/util/HashMap"));
    gHashMapInit = GetMethodIDOrDie(env, gHashMapClazz, "<init>", "()V");
    gHashMapPut = GetMethodIDOrDie(env, gHashMapClazz,
            "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    gLongClazz = MakeGlobalRefOrDie(env, FindClassOrDie(env, "java/lang/Long"));
    gLongValueOf = GetStaticMethodIDOrDie(env, gLongClazz, "valueOf", "(J)Ljava/lang/Long;");

    return RegisterMethodsOrDie(env, kVintfObjectPathName, gVintfObjectMethods,
            NELEM(gVintfObjectMethods));
}

};
