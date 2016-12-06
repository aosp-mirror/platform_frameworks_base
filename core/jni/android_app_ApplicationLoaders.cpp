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

#define LOG_TAG "ApplicationLoaders"

#include <nativehelper/ScopedUtfChars.h>
#include <nativeloader/native_loader.h>
#include <vulkan/vulkan_loader_data.h>

#include "core_jni_helpers.h"

static void setupVulkanLayerPath_native(JNIEnv* env, jobject clazz,
        jobject classLoader, jstring librarySearchPath) {
    android_namespace_t* ns = android::FindNamespaceByClassLoader(env, classLoader);
    ScopedUtfChars layerPathChars(env, librarySearchPath);

    vulkan::LoaderData& loader_data = vulkan::LoaderData::GetInstance();
    if (loader_data.layer_path.empty()) {
        loader_data.layer_path = layerPathChars.c_str();
        loader_data.app_namespace = ns;
    } else {
        ALOGD("ignored Vulkan layer search path %s for namespace %p",
                layerPathChars.c_str(), ns);
    }
}

static const JNINativeMethod g_methods[] = {
    { "setupVulkanLayerPath", "(Ljava/lang/ClassLoader;Ljava/lang/String;)V",
      reinterpret_cast<void*>(setupVulkanLayerPath_native) },
};

static const char* const kApplicationLoadersName = "android/app/ApplicationLoaders";

namespace android
{

int register_android_app_ApplicationLoaders(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kApplicationLoadersName, g_methods, NELEM(g_methods));
}

} // namespace android
