/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <string>

#include "nativeloader/native_loader.h"

#include "core_jni_helpers.h"


static jstring createClassloaderNamespace_native(JNIEnv* env,
                                              jobject clazz,
                                              jobject classLoader,
                                              jint targetSdkVersion,
                                              jstring librarySearchPath,
                                              jstring libraryPermittedPath,
                                              jboolean isShared) {
    return android::CreateClassLoaderNamespace(env, targetSdkVersion,
                                               classLoader, isShared == JNI_TRUE,
                                               librarySearchPath, libraryPermittedPath);
}

static const JNINativeMethod g_methods[] = {
    { "createClassloaderNamespace",
      "(Ljava/lang/ClassLoader;ILjava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;",
      reinterpret_cast<void*>(createClassloaderNamespace_native) },
};

static const char* const kPathClassLoaderFactoryPathName = "com/android/internal/os/PathClassLoaderFactory";

namespace android
{

int register_com_android_internal_os_PathClassLoaderFactory(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kPathClassLoaderFactoryPathName, g_methods, NELEM(g_methods));
}

} // namespace android
