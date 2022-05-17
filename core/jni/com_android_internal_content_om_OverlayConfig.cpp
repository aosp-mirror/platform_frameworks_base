/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <sstream>
#include <string>
#include <vector>

#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include "core_jni_helpers.h"

#include "android-base/logging.h"
#include "androidfw/PosixUtils.h"

using ::android::util::ExecuteBinary;

static jclass g_stringClass = nullptr;

static jobjectArray createIdmap(JNIEnv* env, jclass /*clazz*/, jstring targetPath,
                           jobjectArray overlayPath, jobjectArray policies,
                           jboolean enforceOverlayable) {
  if (access("/system/bin/idmap2", X_OK) == -1) {
    PLOG(WARNING) << "unable to execute idmap2";
    return nullptr;
  }

  const char* targetApkPath = env->GetStringUTFChars(targetPath, NULL /* isCopy */);
  std::vector<std::string> argv{"/system/bin/idmap2",
    "create-multiple",
    "--target-apk-path", targetApkPath,
  };
  env->ReleaseStringUTFChars(targetPath, targetApkPath);

  // Add the overlays for which to generate idmap files to the idmap arguments.
  for (size_t i = 0, count = env->GetArrayLength(overlayPath); i < count; ++i) {
    jstring element = (jstring) env->GetObjectArrayElement(overlayPath, i);
    const char* overlayApkPath = env->GetStringUTFChars(element, NULL /* isCopy */);
    argv.emplace_back("--overlay-apk-path");
    argv.emplace_back(overlayApkPath);
    env->ReleaseStringUTFChars(element, overlayApkPath);
  }

  // Add the policies the overlays fulfill to the idmap arguments.
  for (size_t i = 0, count = env->GetArrayLength(policies); i < count; ++i) {
    jstring element = (jstring)env->GetObjectArrayElement(policies, i);
    const char* policy = env->GetStringUTFChars(element, NULL /* isCopy */);
    argv.emplace_back("--policy");
    argv.emplace_back(policy);
    env->ReleaseStringUTFChars(element, policy);
  }

  if (!enforceOverlayable) {
    argv.emplace_back("--ignore-overlayable");
  }

  const auto result = ExecuteBinary(argv);
  if (!result) {
      LOG(ERROR) << "failed to execute idmap2";
      return nullptr;
  }

  if (result->status != 0) {
      LOG(ERROR) << "idmap2: " << result->stderr_str;
      return nullptr;
  }

  // Return the paths of the idmaps created or updated during the idmap invocation.
  std::vector<std::string> idmap_paths;
  std::istringstream input(result->stdout_str);
  std::string path;
  while (std::getline(input, path)) {
    idmap_paths.push_back(path);
  }

  jobjectArray array = env->NewObjectArray(idmap_paths.size(), g_stringClass, nullptr);
  if (array == nullptr) {
    return nullptr;
  }
  for (size_t i = 0; i < idmap_paths.size(); i++) {
    const std::string path = idmap_paths[i];
    jstring java_string = env->NewStringUTF(path.c_str());
    if (env->ExceptionCheck()) {
      return nullptr;
    }
    env->SetObjectArrayElement(array, i, java_string);
    env->DeleteLocalRef(java_string);
  }

  return array;
}

static const JNINativeMethod g_methods[] = {
    { "createIdmap",
      "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;Z)[Ljava/lang/String;",
      (void *)createIdmap },
};

static const char* const kOverlayConfigPathName = "com/android/internal/content/om/OverlayConfig";

namespace android {

int register_com_android_internal_content_om_OverlayConfig(JNIEnv* env) {
  jclass stringClass = FindClassOrDie(env, "java/lang/String");
  g_stringClass = MakeGlobalRefOrDie(env, stringClass);

  return RegisterMethodsOrDie(env, kOverlayConfigPathName, g_methods, NELEM(g_methods));
}

} // namespace android
