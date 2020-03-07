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

#define ATRACE_TAG ATRACE_TAG_RESOURCES

#include "android-base/logging.h"
#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "android-base/unique_fd.h"
#include "androidfw/ApkAssets.h"
#include "utils/misc.h"
#include "utils/Trace.h"

#include "core_jni_helpers.h"
#include "jni.h"
#include "nativehelper/ScopedUtfChars.h"

using ::android::base::unique_fd;

namespace android {

static struct overlayableinfo_offsets_t {
  jclass classObject;
  jmethodID constructor;
} gOverlayableInfoOffsets;

static jlong NativeLoad(JNIEnv* env, jclass /*clazz*/, jstring java_path, jboolean system,
                        jboolean force_shared_lib, jboolean overlay, jboolean for_loader) {
  ScopedUtfChars path(env, java_path);
  if (path.c_str() == nullptr) {
    return 0;
  }

  ATRACE_NAME(base::StringPrintf("LoadApkAssets(%s)", path.c_str()).c_str());

  std::unique_ptr<const ApkAssets> apk_assets;
  if (overlay) {
    apk_assets = ApkAssets::LoadOverlay(path.c_str(), system);
  } else if (force_shared_lib) {
    apk_assets = ApkAssets::LoadAsSharedLibrary(path.c_str(), system);
  } else {
    apk_assets = ApkAssets::Load(path.c_str(), system, for_loader);
  }

  if (apk_assets == nullptr) {
    std::string error_msg = base::StringPrintf("Failed to load asset path %s", path.c_str());
    jniThrowException(env, "java/io/IOException", error_msg.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(apk_assets.release());
}

static jlong NativeLoadFromFd(JNIEnv* env, jclass /*clazz*/, jobject file_descriptor,
                              jstring friendly_name, jboolean system, jboolean force_shared_lib,
                              jboolean for_loader) {
  ScopedUtfChars friendly_name_utf8(env, friendly_name);
  if (friendly_name_utf8.c_str() == nullptr) {
    return 0;
  }

  ATRACE_NAME(base::StringPrintf("LoadApkAssetsFd(%s)", friendly_name_utf8.c_str()).c_str());

  int fd = jniGetFDFromFileDescriptor(env, file_descriptor);
  if (fd < 0) {
    jniThrowException(env, "java/lang/IllegalArgumentException", "Bad FileDescriptor");
    return 0;
  }

  unique_fd dup_fd(::fcntl(fd, F_DUPFD_CLOEXEC, 0));
  if (dup_fd < 0) {
    jniThrowIOException(env, errno);
    return 0;
  }

  auto dup_fd_id = dup_fd.get();
  std::unique_ptr<const ApkAssets> apk_assets = ApkAssets::LoadFromFd(std::move(dup_fd),
                                                                      friendly_name_utf8.c_str(),
                                                                      system, force_shared_lib,
                                                                      for_loader);

  if (apk_assets == nullptr) {
    std::string error_msg = base::StringPrintf("Failed to load asset path %s from fd %d",
                                               friendly_name_utf8.c_str(), dup_fd_id);
    jniThrowException(env, "java/io/IOException", error_msg.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(apk_assets.release());
}

static jlong NativeLoadArsc(JNIEnv* env, jclass /*clazz*/, jstring java_path,
                            jboolean for_loader) {
  ScopedUtfChars path(env, java_path);
  if (path.c_str() == nullptr) {
    return 0;
  }

  ATRACE_NAME(base::StringPrintf("LoadApkAssetsArsc(%s)", path.c_str()).c_str());

  std::unique_ptr<const ApkAssets> apk_assets = ApkAssets::LoadArsc(path.c_str(), for_loader);

  if (apk_assets == nullptr) {
    std::string error_msg = base::StringPrintf("Failed to load asset path %s", path.c_str());
    jniThrowException(env, "java/io/IOException", error_msg.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(apk_assets.release());
}

static jlong NativeLoadArscFromFd(JNIEnv* env, jclass /*clazz*/, jobject file_descriptor,
                                  jstring friendly_name, jboolean for_loader) {
  ScopedUtfChars friendly_name_utf8(env, friendly_name);
  if (friendly_name_utf8.c_str() == nullptr) {
    return 0;
  }

  int fd = jniGetFDFromFileDescriptor(env, file_descriptor);
  ATRACE_NAME(base::StringPrintf("LoadApkAssetsArscFd(%d)", fd).c_str());
  if (fd < 0) {
    jniThrowException(env, "java/lang/IllegalArgumentException", "Bad FileDescriptor");
    return 0;
  }

  unique_fd dup_fd(::fcntl(fd, F_DUPFD_CLOEXEC, 0));
  if (dup_fd < 0) {
    jniThrowIOException(env, errno);
    return 0;
  }

  std::unique_ptr<const ApkAssets> apk_assets =
      ApkAssets::LoadArsc(std::move(dup_fd), friendly_name_utf8.c_str(), for_loader);
  if (apk_assets == nullptr) {
    std::string error_msg = base::StringPrintf("Failed to load asset path from fd %d", fd);
    jniThrowException(env, "java/io/IOException", error_msg.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(apk_assets.release());
}

static jlong NativeLoadEmpty(JNIEnv* env, jclass /*clazz*/, jboolean for_loader) {
  std::unique_ptr<const ApkAssets> apk_assets = ApkAssets::LoadEmpty(for_loader);
  return reinterpret_cast<jlong>(apk_assets.release());
}

static void NativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
  delete reinterpret_cast<ApkAssets*>(ptr);
}

static jstring NativeGetAssetPath(JNIEnv* env, jclass /*clazz*/, jlong ptr) {
  const ApkAssets* apk_assets = reinterpret_cast<const ApkAssets*>(ptr);
  return env->NewStringUTF(apk_assets->GetPath().c_str());
}

static jlong NativeGetStringBlock(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
  const ApkAssets* apk_assets = reinterpret_cast<const ApkAssets*>(ptr);
  return reinterpret_cast<jlong>(apk_assets->GetLoadedArsc()->GetStringPool());
}

static jboolean NativeIsUpToDate(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
  const ApkAssets* apk_assets = reinterpret_cast<const ApkAssets*>(ptr);
  return apk_assets->IsUpToDate() ? JNI_TRUE : JNI_FALSE;
}

static jlong NativeOpenXml(JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring file_name) {
  ScopedUtfChars path_utf8(env, file_name);
  if (path_utf8.c_str() == nullptr) {
    return 0;
  }

  const ApkAssets* apk_assets = reinterpret_cast<const ApkAssets*>(ptr);
  std::unique_ptr<Asset> asset = apk_assets->Open(path_utf8.c_str(),
                                                  Asset::AccessMode::ACCESS_RANDOM);
  if (asset == nullptr) {
    jniThrowException(env, "java/io/FileNotFoundException", path_utf8.c_str());
    return 0;
  }

  // DynamicRefTable is only needed when looking up resource references. Opening an XML file
  // directly from an ApkAssets has no notion of proper resource references.
  std::unique_ptr<ResXMLTree> xml_tree = util::make_unique<ResXMLTree>(nullptr /*dynamicRefTable*/);
  status_t err = xml_tree->setTo(asset->getBuffer(true), asset->getLength(), true);
  asset.reset();

  if (err != NO_ERROR) {
    jniThrowException(env, "java/io/FileNotFoundException", "Corrupt XML binary file");
    return 0;
  }
  return reinterpret_cast<jlong>(xml_tree.release());
}

static jobject NativeGetOverlayableInfo(JNIEnv* env, jclass /*clazz*/, jlong ptr,
                                         jstring overlayable_name) {
  const ApkAssets* apk_assets = reinterpret_cast<const ApkAssets*>(ptr);

  const auto& packages = apk_assets->GetLoadedArsc()->GetPackages();
  if (packages.empty()) {
    jniThrowException(env, "java/io/IOException", "Error reading overlayable from APK");
    return 0;
  }

  // TODO(b/119899133): Convert this to a search for the info rather than assuming it's at index 0
  const auto& overlayable_map = packages[0]->GetOverlayableMap();
  if (overlayable_map.empty()) {
    return nullptr;
  }

  auto overlayable_name_native = std::string(env->GetStringUTFChars(overlayable_name, NULL));
  auto actor = overlayable_map.find(overlayable_name_native);
  if (actor == overlayable_map.end()) {
    return nullptr;
  }

  jstring actor_string = env->NewStringUTF(actor->second.c_str());
  if (env->ExceptionCheck() || actor_string == nullptr) {
    jniThrowException(env, "java/io/IOException", "Error reading overlayable from APK");
    return 0;
  }

  return env->NewObject(
      gOverlayableInfoOffsets.classObject,
      gOverlayableInfoOffsets.constructor,
      overlayable_name,
      actor_string
  );
}

static jboolean NativeDefinesOverlayable(JNIEnv* env, jclass /*clazz*/, jlong ptr) {
  const ApkAssets* apk_assets = reinterpret_cast<const ApkAssets*>(ptr);

  const auto& packages = apk_assets->GetLoadedArsc()->GetPackages();
  if (packages.empty()) {
    // Must throw to prevent bypass by returning false
    jniThrowException(env, "java/io/IOException", "Error reading overlayable from APK");
    return 0;
  }

  const auto& overlayable_infos = packages[0]->GetOverlayableMap();
  return overlayable_infos.empty() ? JNI_FALSE : JNI_TRUE;
}

// JNI registration.
static const JNINativeMethod gApkAssetsMethods[] = {
    {"nativeLoad", "(Ljava/lang/String;ZZZZ)J", (void*)NativeLoad},
    {"nativeLoadFromFd", "(Ljava/io/FileDescriptor;Ljava/lang/String;ZZZ)J",
        (void*)NativeLoadFromFd},
    {"nativeLoadArsc", "(Ljava/lang/String;Z)J", (void*)NativeLoadArsc},
    {"nativeLoadArscFromFd", "(Ljava/io/FileDescriptor;Ljava/lang/String;Z)J",
        (void*)NativeLoadArscFromFd},
    {"nativeLoadEmpty", "(Z)J", (void*)NativeLoadEmpty},
    {"nativeDestroy", "(J)V", (void*)NativeDestroy},
    {"nativeGetAssetPath", "(J)Ljava/lang/String;", (void*)NativeGetAssetPath},
    {"nativeGetStringBlock", "(J)J", (void*)NativeGetStringBlock},
    {"nativeIsUpToDate", "(J)Z", (void*)NativeIsUpToDate},
    {"nativeOpenXml", "(JLjava/lang/String;)J", (void*)NativeOpenXml},
    {"nativeGetOverlayableInfo", "(JLjava/lang/String;)Landroid/content/om/OverlayableInfo;",
     (void*)NativeGetOverlayableInfo},
    {"nativeDefinesOverlayable", "(J)Z", (void*)NativeDefinesOverlayable},
};

int register_android_content_res_ApkAssets(JNIEnv* env) {
  jclass overlayableInfoClass = FindClassOrDie(env, "android/content/om/OverlayableInfo");
  gOverlayableInfoOffsets.classObject = MakeGlobalRefOrDie(env, overlayableInfoClass);
  gOverlayableInfoOffsets.constructor = GetMethodIDOrDie(env, gOverlayableInfoOffsets.classObject,
      "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");

  return RegisterMethodsOrDie(env, "android/content/res/ApkAssets", gApkAssetsMethods,
                              arraysize(gApkAssetsMethods));
}

}  // namespace android
