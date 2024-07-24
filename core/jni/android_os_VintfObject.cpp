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
#include <vintf/VintfObject.h>
#include <vintf/parse_string.h>
#include <vintf/parse_xml.h>

#include <vector>
#include <string>

#include "jni_wrappers.h"

static jclass gString;
static jclass gHashMapClazz;
static jmethodID gHashMapInit;
static jmethodID gHashMapPut;
static jclass gLongClazz;
static jmethodID gLongValueOf;
static jclass gVintfObjectClazz;
static jmethodID gRunCommand;

namespace android {

using vintf::CompatibilityMatrix;
using vintf::HalManifest;
using vintf::Level;
using vintf::SchemaType;
using vintf::SepolicyVersion;
using vintf::to_string;
using vintf::toXml;
using vintf::Version;
using vintf::VintfObject;
using vintf::Vndk;
using vintf::CheckFlags::ENABLE_ALL_CHECKS;

// Instead of VintfObject::GetXxx(), we construct
// HalManifest/CompatibilityMatrix objects by calling `vintf` through
// UiAutomation.executeShellCommand() so that the commands are executed
// using shell identity. Otherwise, we would need to allow "apps" to access
// files like apex-info-list.xml which we don't want to open to apps.
// This is okay because VintfObject is @TestApi and only used in CTS tests.

static std::string runCmd(JNIEnv* env, const char* cmd) {
    jstring jstr = (jstring)env->CallStaticObjectMethod(gVintfObjectClazz, gRunCommand,
                                                        env->NewStringUTF(cmd));
    std::string output;
    if (jstr) {
        auto cstr = env->GetStringUTFChars(jstr, nullptr);
        output = std::string(cstr);
        env->ReleaseStringUTFChars(jstr, cstr);
    } else {
        LOG(WARNING) << "Failed to run " << cmd;
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return output;
}

template <typename T>
static std::shared_ptr<const T> fromXml(const std::string& content) {
    std::shared_ptr<T> object = std::make_unique<T>();
    std::string error;
    if (fromXml(object.get(), content, &error)) {
        return object;
    }
    LOG(WARNING) << "Unabled to parse: " << error;
    return nullptr;
}

static std::shared_ptr<const HalManifest> getDeviceHalManifest(JNIEnv* env) {
    return fromXml<HalManifest>(runCmd(env, "vintf dm"));
}

static std::shared_ptr<const HalManifest> getFrameworkHalManifest(JNIEnv* env) {
    return fromXml<HalManifest>(runCmd(env, "vintf fm"));
}

static std::shared_ptr<const CompatibilityMatrix> getDeviceCompatibilityMatrix(JNIEnv* env) {
    return fromXml<CompatibilityMatrix>(runCmd(env, "vintf dcm"));
}

static std::shared_ptr<const CompatibilityMatrix> getFrameworkCompatibilityMatrix(JNIEnv* env) {
    return fromXml<CompatibilityMatrix>(runCmd(env, "vintf fcm"));
}

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

    tryAddSchema(getDeviceHalManifest(env), "device manifest", &cStrings);
    tryAddSchema(getFrameworkHalManifest(env), "framework manifest", &cStrings);
    tryAddSchema(getDeviceCompatibilityMatrix(env), "device compatibility matrix", &cStrings);
    tryAddSchema(getFrameworkCompatibilityMatrix(env), "framework compatibility matrix", &cStrings);

    return toJavaStringArray(env, cStrings);
}

static jint android_os_VintfObject_verifyBuildAtBoot(JNIEnv*, jclass) {
    std::string error;
    // Use temporary VintfObject, not the shared instance, to release memory
    // after check.
    int32_t status =
            VintfObject::Builder()
                    .build()
                    ->checkCompatibility(&error, ENABLE_ALL_CHECKS.disableAvb().disableKernel());
    if (status)
        LOG(WARNING) << "VintfObject.verifyBuildAtBoot() returns " << status << ": " << error;
    return status;
}

static jobjectArray android_os_VintfObject_getHalNamesAndVersions(JNIEnv* env, jclass) {
    std::set<std::string> halNames;
    tryAddHalNamesAndVersions(getDeviceHalManifest(env), "device manifest", &halNames);
    tryAddHalNamesAndVersions(getFrameworkHalManifest(env), "framework manifest", &halNames);
    return toJavaStringArray(env, halNames);
}

static jstring android_os_VintfObject_getSepolicyVersion(JNIEnv* env, jclass) {
    std::shared_ptr<const HalManifest> manifest = getDeviceHalManifest(env);
    if (manifest == nullptr || manifest->type() != SchemaType::DEVICE) {
        LOG(WARNING) << __FUNCTION__ << "Cannot get device manifest";
        return nullptr;
    }
    std::string cString = to_string(manifest->sepolicyVersion());
    return env->NewStringUTF(cString.c_str());
}

static jstring android_os_VintfObject_getPlatformSepolicyVersion(JNIEnv* env, jclass) {
    std::shared_ptr<const CompatibilityMatrix> matrix = getFrameworkCompatibilityMatrix(env);
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

    SepolicyVersion latest;
    for (const auto& range : versions) {
        latest = std::max(latest, range.maxVer());
    }
    return env->NewStringUTF(to_string(latest).c_str());
}

static jobject android_os_VintfObject_getVndkSnapshots(JNIEnv* env, jclass) {
    std::shared_ptr<const HalManifest> manifest = getFrameworkHalManifest(env);
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
    std::shared_ptr<const HalManifest> manifest = getDeviceHalManifest(env);
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

int register_android_os_VintfObject(JNIEnv* env) {
    gString = MakeGlobalRefOrDie(env, FindClassOrDie(env, "java/lang/String"));
    gHashMapClazz = MakeGlobalRefOrDie(env, FindClassOrDie(env, "java/util/HashMap"));
    gHashMapInit = GetMethodIDOrDie(env, gHashMapClazz, "<init>", "()V");
    gHashMapPut = GetMethodIDOrDie(env, gHashMapClazz, "put",
                                   "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    gLongClazz = MakeGlobalRefOrDie(env, FindClassOrDie(env, "java/lang/Long"));
    gLongValueOf = GetStaticMethodIDOrDie(env, gLongClazz, "valueOf", "(J)Ljava/lang/Long;");
    gVintfObjectClazz = MakeGlobalRefOrDie(env, FindClassOrDie(env, kVintfObjectPathName));
    gRunCommand = GetStaticMethodIDOrDie(env, gVintfObjectClazz, "runShellCommand",
                                         "(Ljava/lang/String;)Ljava/lang/String;");

    return RegisterMethodsOrDie(env, kVintfObjectPathName, gVintfObjectMethods,
                                NELEM(gVintfObjectMethods));
}

extern int register_android_os_VintfRuntimeInfo(JNIEnv* env);

} // namespace android

jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    JNIEnv* env = NULL;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    if (android::register_android_os_VintfObject(env) < 0) {
        return JNI_ERR;
    }

    if (android::register_android_os_VintfRuntimeInfo(env) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
