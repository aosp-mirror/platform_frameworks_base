/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <JNIHelp.h>
#include <vintf/VintfObject.h>
#include <vintf/parse_xml.h>

#include "core_jni_helpers.h"

namespace android {

using vintf::HalManifest;
using vintf::RuntimeInfo;
using vintf::VintfObject;
using vintf::gHalManifestConverter;

static jstring android_os_VintfObject_getDeviceManifest(JNIEnv* env, jclass clazz)
{
    const HalManifest *manifest = VintfObject::GetDeviceHalManifest();
    if (manifest == nullptr) {
        return nullptr;
    }
    std::string xml = gHalManifestConverter(*manifest);
    return env->NewStringUTF(xml.c_str());
}

static jstring android_os_VintfObject_getFrameworkManifest(JNIEnv* env, jclass clazz)
{
    const HalManifest *manifest = VintfObject::GetFrameworkHalManifest();
    if (manifest == nullptr) {
        return nullptr;
    }
    std::string xml = gHalManifestConverter(*manifest);
    return env->NewStringUTF(xml.c_str());
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
    int32_t status = VintfObject::CheckCompatibility(cPackageInfo, false /* mount */);
    return status;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gVintfObjectMethods[] = {
    {"getDeviceManifest",    "()Ljava/lang/String;",   (void*)android_os_VintfObject_getDeviceManifest},
    {"getFrameworkManifest", "()Ljava/lang/String;",   (void*)android_os_VintfObject_getFrameworkManifest},
    {"verify",               "([Ljava/lang/String;)I", (void*)android_os_VintfObject_verify},
};

const char* const kVintfObjectPathName = "android/os/VintfObject";

int register_android_os_VintfObject(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, kVintfObjectPathName, gVintfObjectMethods,
            NELEM(gVintfObjectMethods));
}

};
