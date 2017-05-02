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

#include <JNIHelp.h>
#include <vintf/VintfObject.h>
#include <vintf/parse_xml.h>

#include "core_jni_helpers.h"

static jclass gString;

namespace android {

using vintf::VintfObject;
using vintf::gHalManifestConverter;
using vintf::gCompatibilityMatrixConverter;
using vintf::XmlConverter;

static inline jobjectArray toJavaStringArray(JNIEnv* env, const std::vector<std::string>& v) {
    jobjectArray ret = env->NewObjectArray(v.size(), gString, NULL /* init element */);
    for (size_t i = 0; i < v.size(); ++i) {
        env->SetObjectArrayElement(ret, i, env->NewStringUTF(v[i].c_str()));
    }
    return ret;
}

template<typename T>
static void tryAddSchema(const T* object, const XmlConverter<T>& converter,
        const std::string& description,
        std::vector<std::string>* cStrings) {
    if (object == nullptr) {
        LOG(WARNING) << __FUNCTION__ << "Cannot get " << description;
    } else {
        cStrings->push_back(converter(*object));
    }
}

static jobjectArray android_os_VintfObject_report(JNIEnv* env, jclass clazz)
{
    std::vector<std::string> cStrings;

    tryAddSchema(VintfObject::GetDeviceHalManifest(), gHalManifestConverter,
            "device manifest", &cStrings);
    tryAddSchema(VintfObject::GetFrameworkHalManifest(), gHalManifestConverter,
            "framework manifest", &cStrings);
    tryAddSchema(VintfObject::GetDeviceCompatibilityMatrix(), gCompatibilityMatrixConverter,
            "device compatibility matrix", &cStrings);
    tryAddSchema(VintfObject::GetFrameworkCompatibilityMatrix(), gCompatibilityMatrixConverter,
            "framework compatibility matrix", &cStrings);

    return toJavaStringArray(env, cStrings);
}

static jint android_os_VintfObject_verify(JNIEnv *env, jclass clazz, jobjectArray packageInfo) {
    size_t count = env->GetArrayLength(packageInfo);
    std::vector<std::string> cPackageInfo{count};
    for (size_t i = 0; i < count; ++i) {
        jstring element = (jstring)env->GetObjectArrayElement(packageInfo, i);
        const char *cString = env->GetStringUTFChars(element, NULL /* isCopy */);
        cPackageInfo[i] = cString;
        env->ReleaseStringUTFChars(element, cString);
    }
    int32_t status = VintfObject::CheckCompatibility(cPackageInfo);
    return status;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gVintfObjectMethods[] = {
    {"report", "()[Ljava/lang/String;", (void*)android_os_VintfObject_report},
    {"verify", "([Ljava/lang/String;)I", (void*)android_os_VintfObject_verify},
};


const char* const kVintfObjectPathName = "android/os/VintfObject";

int register_android_os_VintfObject(JNIEnv* env)
{

    gString = MakeGlobalRefOrDie(env, FindClassOrDie(env, "java/lang/String"));

    return RegisterMethodsOrDie(env, kVintfObjectPathName, gVintfObjectMethods,
            NELEM(gVintfObjectMethods));
}

};
