/*
 * Copyright 2016 The Android Open Source Project
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

#define LOG_TAG "GraphicsEnvironment"

#include <graphicsenv/GraphicsEnv.h>
#include <nativehelper/ScopedUtfChars.h>
#include <nativeloader/native_loader.h>
#include "core_jni_helpers.h"

namespace {

int getCanLoadSystemLibraries_native() {
    return android::GraphicsEnv::getInstance().getCanLoadSystemLibraries();
}

void setDriverPath(JNIEnv* env, jobject clazz, jstring path) {
    ScopedUtfChars pathChars(env, path);
    android::GraphicsEnv::getInstance().setDriverPath(pathChars.c_str());
}

void setGpuStats_native(JNIEnv* env, jobject clazz, jstring driverPackageName,
                        jstring driverVersionName, jlong driverVersionCode,
                        jstring appPackageName) {
    ScopedUtfChars driverPackageNameChars(env, driverPackageName);
    ScopedUtfChars driverVersionNameChars(env, driverVersionName);
    ScopedUtfChars appPackageNameChars(env, appPackageName);
    android::GraphicsEnv::getInstance().setGpuStats(driverPackageNameChars.c_str(),
                                                    driverVersionNameChars.c_str(),
                                                    driverVersionCode, appPackageNameChars.c_str());
}

void setAngleInfo_native(JNIEnv* env, jobject clazz, jstring path, jstring appName, jstring devOptIn,
                         jobject rulesFd, jlong rulesOffset, jlong rulesLength) {
    ScopedUtfChars pathChars(env, path);
    ScopedUtfChars appNameChars(env, appName);
    ScopedUtfChars devOptInChars(env, devOptIn);

    int rulesFd_native = jniGetFDFromFileDescriptor(env, rulesFd);

    android::GraphicsEnv::getInstance().setAngleInfo(pathChars.c_str(), appNameChars.c_str(),
            devOptInChars.c_str(), rulesFd_native, rulesOffset, rulesLength);
}

void setLayerPaths_native(JNIEnv* env, jobject clazz, jobject classLoader, jstring layerPaths) {
    android::NativeLoaderNamespace* appNamespace = android::FindNativeLoaderNamespaceByClassLoader(
        env, classLoader);
    ScopedUtfChars layerPathsChars(env, layerPaths);
    android::GraphicsEnv::getInstance().setLayerPaths(appNamespace, layerPathsChars.c_str());
}

void setDebugLayers_native(JNIEnv* env, jobject clazz, jstring layers) {
    if (layers != nullptr) {
        ScopedUtfChars layersChars(env, layers);
        android::GraphicsEnv::getInstance().setDebugLayers(layersChars.c_str());
    }
}

void setDebugLayersGLES_native(JNIEnv* env, jobject clazz, jstring layers) {
    if (layers != nullptr) {
        ScopedUtfChars layersChars(env, layers);
        android::GraphicsEnv::getInstance().setDebugLayersGLES(layersChars.c_str());
    }
}

const JNINativeMethod g_methods[] = {
    { "getCanLoadSystemLibraries", "()I", reinterpret_cast<void*>(getCanLoadSystemLibraries_native) },
    { "setDriverPath", "(Ljava/lang/String;)V", reinterpret_cast<void*>(setDriverPath) },
    { "setGpuStats", "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V", reinterpret_cast<void*>(setGpuStats_native) },
    { "setAngleInfo", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/io/FileDescriptor;JJ)V", reinterpret_cast<void*>(setAngleInfo_native) },
    { "setLayerPaths", "(Ljava/lang/ClassLoader;Ljava/lang/String;)V", reinterpret_cast<void*>(setLayerPaths_native) },
    { "setDebugLayers", "(Ljava/lang/String;)V", reinterpret_cast<void*>(setDebugLayers_native) },
    { "setDebugLayersGLES", "(Ljava/lang/String;)V", reinterpret_cast<void*>(setDebugLayersGLES_native) },
};

const char* const kGraphicsEnvironmentName = "android/os/GraphicsEnvironment";

} // anonymous namespace

namespace android {

int register_android_os_GraphicsEnvironment(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kGraphicsEnvironmentName, g_methods, NELEM(g_methods));
}

} // namespace android
