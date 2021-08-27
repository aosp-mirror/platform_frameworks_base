/*
 * Copyright 2006, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define ATRACE_TAG ATRACE_TAG_RESOURCES
#define LOG_TAG "asset"

#include <inttypes.h>
#include <linux/capability.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <sstream>
#include <string>

#include "android-base/logging.h"
#include "android-base/properties.h"
#include "android-base/stringprintf.h"
#include "android_runtime/android_util_AssetManager.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_util_Binder.h"
#include "androidfw/Asset.h"
#include "androidfw/AssetManager.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/AttributeResolution.h"
#include "androidfw/MutexGuard.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/ResourceUtils.h"

#include "android_content_res_ApkAssets.h"
#include "core_jni_helpers.h"
#include "jni.h"
#include "nativehelper/JNIPlatformHelp.h"
#include "nativehelper/ScopedPrimitiveArray.h"
#include "nativehelper/ScopedStringChars.h"
#include "nativehelper/ScopedUtfChars.h"
#include "utils/Log.h"
#include "utils/String8.h"
#include "utils/Trace.h"
#include "utils/misc.h"

extern "C" int capget(cap_user_header_t hdrp, cap_user_data_t datap);
extern "C" int capset(cap_user_header_t hdrp, const cap_user_data_t datap);

using ::android::base::StringPrintf;

namespace android {

// ----------------------------------------------------------------------------

static struct typedvalue_offsets_t {
  jfieldID mType;
  jfieldID mData;
  jfieldID mString;
  jfieldID mAssetCookie;
  jfieldID mResourceId;
  jfieldID mChangingConfigurations;
  jfieldID mDensity;
} gTypedValueOffsets;

// This is also used by asset_manager.cpp.
assetmanager_offsets_t gAssetManagerOffsets;

static struct {
  jfieldID native_ptr;
} gApkAssetsFields;

static struct sparsearray_offsets_t {
  jclass classObject;
  jmethodID constructor;
  jmethodID put;
} gSparseArrayOffsets;

static struct configuration_offsets_t {
  jclass classObject;
  jmethodID constructor;
  jfieldID mSmallestScreenWidthDpOffset;
  jfieldID mScreenWidthDpOffset;
  jfieldID mScreenHeightDpOffset;
} gConfigurationOffsets;

static struct arraymap_offsets_t {
  jclass classObject;
  jmethodID constructor;
  jmethodID put;
} gArrayMapOffsets;

static jclass g_stringClass = nullptr;

// ----------------------------------------------------------------------------

// Java asset cookies have 0 as an invalid cookie, but TypedArray expects < 0.
constexpr inline static jint ApkAssetsCookieToJavaCookie(ApkAssetsCookie cookie) {
  return cookie != kInvalidCookie ? static_cast<jint>(cookie + 1) : -1;
}

constexpr inline static ApkAssetsCookie JavaCookieToApkAssetsCookie(jint cookie) {
  return cookie > 0 ? static_cast<ApkAssetsCookie>(cookie - 1) : kInvalidCookie;
}

static jint CopyValue(JNIEnv* env, const AssetManager2::SelectedValue& value,
                      jobject out_typed_value) {
  env->SetIntField(out_typed_value, gTypedValueOffsets.mType, value.type);
  env->SetIntField(out_typed_value, gTypedValueOffsets.mAssetCookie,
                   ApkAssetsCookieToJavaCookie(value.cookie));
  env->SetIntField(out_typed_value, gTypedValueOffsets.mData, value.data);
  env->SetObjectField(out_typed_value, gTypedValueOffsets.mString, nullptr);
  env->SetIntField(out_typed_value, gTypedValueOffsets.mResourceId, value.resid);
  env->SetIntField(out_typed_value, gTypedValueOffsets.mChangingConfigurations, value.flags);
  env->SetIntField(out_typed_value, gTypedValueOffsets.mDensity, value.config.density);
  return static_cast<jint>(ApkAssetsCookieToJavaCookie(value.cookie));
}

// ----------------------------------------------------------------------------

// Let the opaque type AAssetManager refer to a guarded AssetManager2 instance.
struct GuardedAssetManager : public ::AAssetManager {
  Guarded<AssetManager2> guarded_assetmanager;
};

::AAssetManager* NdkAssetManagerForJavaObject(JNIEnv* env, jobject jassetmanager) {
  jlong assetmanager_handle = env->GetLongField(jassetmanager, gAssetManagerOffsets.mObject);
  ::AAssetManager* am = reinterpret_cast<::AAssetManager*>(assetmanager_handle);
  if (am == nullptr) {
    jniThrowException(env, "java/lang/IllegalStateException", "AssetManager has been finalized!");
    return nullptr;
  }
  return am;
}

Guarded<AssetManager2>* AssetManagerForNdkAssetManager(::AAssetManager* assetmanager) {
  if (assetmanager == nullptr) {
    return nullptr;
  }
  return &reinterpret_cast<GuardedAssetManager*>(assetmanager)->guarded_assetmanager;
}

Guarded<AssetManager2>* AssetManagerForJavaObject(JNIEnv* env, jobject jassetmanager) {
  return AssetManagerForNdkAssetManager(NdkAssetManagerForJavaObject(env, jassetmanager));
}

static Guarded<AssetManager2>& AssetManagerFromLong(jlong ptr) {
  return *AssetManagerForNdkAssetManager(reinterpret_cast<AAssetManager*>(ptr));
}

static jobject NativeGetOverlayableMap(JNIEnv* env, jclass /*clazz*/, jlong ptr,
                                       jstring package_name) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  const ScopedUtfChars package_name_utf8(env, package_name);
  CHECK(package_name_utf8.c_str() != nullptr);
  const std::string std_package_name(package_name_utf8.c_str());
  const std::unordered_map<std::string, std::string>* map = nullptr;

  assetmanager->ForEachPackage([&](const std::string& this_package_name, uint8_t package_id) {
    if (this_package_name == std_package_name) {
      map = assetmanager->GetOverlayableMapForPackage(package_id);
      return false;
    }
    return true;
  });

  if (map == nullptr) {
    return nullptr;
  }

  jobject array_map = env->NewObject(gArrayMapOffsets.classObject, gArrayMapOffsets.constructor);
  if (array_map == nullptr) {
    return nullptr;
  }

  for (const auto& iter : *map) {
    jstring name = env->NewStringUTF(iter.first.c_str());
    if (env->ExceptionCheck()) {
      return nullptr;
    }

    jstring actor = env->NewStringUTF(iter.second.c_str());
    if (env->ExceptionCheck()) {
      env->DeleteLocalRef(name);
      return nullptr;
    }

    env->CallObjectMethod(array_map, gArrayMapOffsets.put, name, actor);

    env->DeleteLocalRef(name);
    env->DeleteLocalRef(actor);
  }

  return array_map;
}

static jstring NativeGetOverlayablesToString(JNIEnv* env, jclass /*clazz*/, jlong ptr,
                                             jstring package_name) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  const ScopedUtfChars package_name_utf8(env, package_name);
  CHECK(package_name_utf8.c_str() != nullptr);
  const std::string std_package_name(package_name_utf8.c_str());

  std::string result;
  if (!assetmanager->GetOverlayablesToString(std_package_name, &result)) {
    return nullptr;
  }

  return env->NewStringUTF(result.c_str());
}

#ifdef __ANDROID__ // Layoutlib does not support parcel
static jobject ReturnParcelFileDescriptor(JNIEnv* env, std::unique_ptr<Asset> asset,
                                          jlongArray out_offsets) {
  off64_t start_offset, length;
  int fd = asset->openFileDescriptor(&start_offset, &length);
  asset.reset();

  if (fd < 0) {
    jniThrowException(env, "java/io/FileNotFoundException",
                      "This file can not be opened as a file descriptor; it is probably "
                      "compressed");
    return nullptr;
  }

  jlong* offsets = reinterpret_cast<jlong*>(env->GetPrimitiveArrayCritical(out_offsets, 0));
  if (offsets == nullptr) {
    close(fd);
    return nullptr;
  }

  offsets[0] = start_offset;
  offsets[1] = length;

  env->ReleasePrimitiveArrayCritical(out_offsets, offsets, 0);

  jobject file_desc = jniCreateFileDescriptor(env, fd);
  if (file_desc == nullptr) {
    close(fd);
    return nullptr;
  }
  return newParcelFileDescriptor(env, file_desc);
}
#else
static jobject ReturnParcelFileDescriptor(JNIEnv* env, std::unique_ptr<Asset> asset,
                                          jlongArray out_offsets) {
  jniThrowException(env, "java/lang/UnsupportedOperationException",
                    "Implement me");
  // never reached
  return nullptr;
}
#endif

static jint NativeGetGlobalAssetCount(JNIEnv* /*env*/, jobject /*clazz*/) {
  return Asset::getGlobalCount();
}

static jobject NativeGetAssetAllocations(JNIEnv* env, jobject /*clazz*/) {
  String8 alloc = Asset::getAssetAllocations();
  if (alloc.length() <= 0) {
    return nullptr;
  }
  return env->NewStringUTF(alloc.string());
}

static jint NativeGetGlobalAssetManagerCount(JNIEnv* /*env*/, jobject /*clazz*/) {
  // TODO(adamlesinski): Switch to AssetManager2.
  return AssetManager::getGlobalCount();
}

static jlong NativeCreate(JNIEnv* /*env*/, jclass /*clazz*/) {
  // AssetManager2 needs to be protected by a lock. To avoid cache misses, we allocate the lock and
  // AssetManager2 in a contiguous block (GuardedAssetManager).
  return reinterpret_cast<jlong>(new GuardedAssetManager());
}

static void NativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
  delete reinterpret_cast<GuardedAssetManager*>(ptr);
}

static void NativeSetApkAssets(JNIEnv* env, jclass /*clazz*/, jlong ptr,
                               jobjectArray apk_assets_array, jboolean invalidate_caches) {
  ATRACE_NAME("AssetManager::SetApkAssets");

  const jsize apk_assets_len = env->GetArrayLength(apk_assets_array);
  std::vector<const ApkAssets*> apk_assets;
  apk_assets.reserve(apk_assets_len);
  for (jsize i = 0; i < apk_assets_len; i++) {
    jobject obj = env->GetObjectArrayElement(apk_assets_array, i);
    if (obj == nullptr) {
      std::string msg = StringPrintf("ApkAssets at index %d is null", i);
      jniThrowNullPointerException(env, msg.c_str());
      return;
    }

    jlong apk_assets_native_ptr = env->GetLongField(obj, gApkAssetsFields.native_ptr);
    if (env->ExceptionCheck()) {
      return;
    }

    auto scoped_assets = ScopedLock(ApkAssetsFromLong(apk_assets_native_ptr));
    apk_assets.push_back(scoped_assets->get());
  }

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  assetmanager->SetApkAssets(apk_assets, invalidate_caches);
}

static void NativeSetConfiguration(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint mcc, jint mnc,
                                   jstring locale, jint orientation, jint touchscreen, jint density,
                                   jint keyboard, jint keyboard_hidden, jint navigation,
                                   jint screen_width, jint screen_height,
                                   jint smallest_screen_width_dp, jint screen_width_dp,
                                   jint screen_height_dp, jint screen_layout, jint ui_mode,
                                   jint color_mode, jint major_version) {
  ATRACE_NAME("AssetManager::SetConfiguration");

  ResTable_config configuration;
  memset(&configuration, 0, sizeof(configuration));
  configuration.mcc = static_cast<uint16_t>(mcc);
  configuration.mnc = static_cast<uint16_t>(mnc);
  configuration.orientation = static_cast<uint8_t>(orientation);
  configuration.touchscreen = static_cast<uint8_t>(touchscreen);
  configuration.density = static_cast<uint16_t>(density);
  configuration.keyboard = static_cast<uint8_t>(keyboard);
  configuration.inputFlags = static_cast<uint8_t>(keyboard_hidden);
  configuration.navigation = static_cast<uint8_t>(navigation);
  configuration.screenWidth = static_cast<uint16_t>(screen_width);
  configuration.screenHeight = static_cast<uint16_t>(screen_height);
  configuration.smallestScreenWidthDp = static_cast<uint16_t>(smallest_screen_width_dp);
  configuration.screenWidthDp = static_cast<uint16_t>(screen_width_dp);
  configuration.screenHeightDp = static_cast<uint16_t>(screen_height_dp);
  configuration.screenLayout = static_cast<uint8_t>(screen_layout);
  configuration.uiMode = static_cast<uint8_t>(ui_mode);
  configuration.colorMode = static_cast<uint8_t>(color_mode);
  configuration.sdkVersion = static_cast<uint16_t>(major_version);

  if (locale != nullptr) {
    ScopedUtfChars locale_utf8(env, locale);
    CHECK(locale_utf8.c_str() != nullptr);
    configuration.setBcp47Locale(locale_utf8.c_str());
  }

  // Constants duplicated from Java class android.content.res.Configuration.
  static const jint kScreenLayoutRoundMask = 0x300;
  static const jint kScreenLayoutRoundShift = 8;

  // In Java, we use a 32bit integer for screenLayout, while we only use an 8bit integer
  // in C++. We must extract the round qualifier out of the Java screenLayout and put it
  // into screenLayout2.
  configuration.screenLayout2 =
      static_cast<uint8_t>((screen_layout & kScreenLayoutRoundMask) >> kScreenLayoutRoundShift);

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  assetmanager->SetConfiguration(configuration);
}

static jobject NativeGetAssignedPackageIdentifiers(JNIEnv* env, jclass /*clazz*/, jlong ptr,
                                                   jboolean includeOverlays,
                                                   jboolean includeLoaders) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));

  jobject sparse_array =
        env->NewObject(gSparseArrayOffsets.classObject, gSparseArrayOffsets.constructor);

  if (sparse_array == nullptr) {
    // An exception is pending.
    return nullptr;
  }

  // Optionally exclude overlays and loaders.
  uint64_t exclusion_flags = ((includeOverlays) ? 0U : PROPERTY_OVERLAY)
      | ((includeLoaders) ? 0U : PROPERTY_LOADER);

  assetmanager->ForEachPackage([&](const std::string& package_name, uint8_t package_id) -> bool {
    jstring jpackage_name = env->NewStringUTF(package_name.c_str());
    if (jpackage_name == nullptr) {
      // An exception is pending.
      return false;
    }

    env->CallVoidMethod(sparse_array, gSparseArrayOffsets.put, static_cast<jint>(package_id),
                        jpackage_name);
    return true;
  }, exclusion_flags);

  return sparse_array;
}

static jboolean ContainsAllocatedTable(JNIEnv* env, jclass /*clazz*/, jlong ptr) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  return assetmanager->ContainsAllocatedTable();
}

static jobjectArray NativeList(JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring path) {
  ScopedUtfChars path_utf8(env, path);
  if (path_utf8.c_str() == nullptr) {
    // This will throw NPE.
    return nullptr;
  }

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  std::unique_ptr<AssetDir> asset_dir =
      assetmanager->OpenDir(path_utf8.c_str());
  if (asset_dir == nullptr) {
    jniThrowException(env, "java/io/FileNotFoundException", path_utf8.c_str());
    return nullptr;
  }

  const size_t file_count = asset_dir->getFileCount();

  jobjectArray array = env->NewObjectArray(file_count, g_stringClass, nullptr);
  if (array == nullptr) {
    return nullptr;
  }

  for (size_t i = 0; i < file_count; i++) {
    jstring java_string = env->NewStringUTF(asset_dir->getFileName(i).string());

    // Check for errors creating the strings (if malformed or no memory).
    if (env->ExceptionCheck()) {
     return nullptr;
    }

    env->SetObjectArrayElement(array, i, java_string);

    // If we have a large amount of string in our array, we might overflow the
    // local reference table of the VM.
    env->DeleteLocalRef(java_string);
  }
  return array;
}

static jlong NativeOpenAsset(JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring asset_path,
                             jint access_mode) {
  ScopedUtfChars asset_path_utf8(env, asset_path);
  if (asset_path_utf8.c_str() == nullptr) {
    // This will throw NPE.
    return 0;
  }

  ATRACE_NAME(base::StringPrintf("AssetManager::OpenAsset(%s)", asset_path_utf8.c_str()).c_str());

  if (access_mode != Asset::ACCESS_UNKNOWN && access_mode != Asset::ACCESS_RANDOM &&
      access_mode != Asset::ACCESS_STREAMING && access_mode != Asset::ACCESS_BUFFER) {
    jniThrowException(env, "java/lang/IllegalArgumentException", "Bad access mode");
    return 0;
  }

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  std::unique_ptr<Asset> asset =
      assetmanager->Open(asset_path_utf8.c_str(), static_cast<Asset::AccessMode>(access_mode));
  if (!asset) {
    jniThrowException(env, "java/io/FileNotFoundException", asset_path_utf8.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(asset.release());
}

static jobject NativeOpenAssetFd(JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring asset_path,
                                 jlongArray out_offsets) {
  ScopedUtfChars asset_path_utf8(env, asset_path);
  if (asset_path_utf8.c_str() == nullptr) {
    // This will throw NPE.
    return nullptr;
  }

  ATRACE_NAME(base::StringPrintf("AssetManager::OpenAssetFd(%s)", asset_path_utf8.c_str()).c_str());

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  std::unique_ptr<Asset> asset = assetmanager->Open(asset_path_utf8.c_str(), Asset::ACCESS_RANDOM);
  if (!asset) {
    jniThrowException(env, "java/io/FileNotFoundException", asset_path_utf8.c_str());
    return nullptr;
  }
  return ReturnParcelFileDescriptor(env, std::move(asset), out_offsets);
}

static jlong NativeOpenNonAsset(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint jcookie,
                                jstring asset_path, jint access_mode) {
  ApkAssetsCookie cookie = JavaCookieToApkAssetsCookie(jcookie);
  ScopedUtfChars asset_path_utf8(env, asset_path);
  if (asset_path_utf8.c_str() == nullptr) {
    // This will throw NPE.
    return 0;
  }

  ATRACE_NAME(base::StringPrintf("AssetManager::OpenNonAsset(%s)", asset_path_utf8.c_str()).c_str());

  if (access_mode != Asset::ACCESS_UNKNOWN && access_mode != Asset::ACCESS_RANDOM &&
      access_mode != Asset::ACCESS_STREAMING && access_mode != Asset::ACCESS_BUFFER) {
    jniThrowException(env, "java/lang/IllegalArgumentException", "Bad access mode");
    return 0;
  }

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  std::unique_ptr<Asset> asset;
  if (cookie != kInvalidCookie) {
    asset = assetmanager->OpenNonAsset(asset_path_utf8.c_str(), cookie,
                                       static_cast<Asset::AccessMode>(access_mode));
  } else {
    asset = assetmanager->OpenNonAsset(asset_path_utf8.c_str(),
                                       static_cast<Asset::AccessMode>(access_mode));
  }

  if (!asset) {
    jniThrowException(env, "java/io/FileNotFoundException", asset_path_utf8.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(asset.release());
}

static jobject NativeOpenNonAssetFd(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint jcookie,
                                    jstring asset_path, jlongArray out_offsets) {
  ApkAssetsCookie cookie = JavaCookieToApkAssetsCookie(jcookie);
  ScopedUtfChars asset_path_utf8(env, asset_path);
  if (asset_path_utf8.c_str() == nullptr) {
    // This will throw NPE.
    return nullptr;
  }

  ATRACE_NAME(base::StringPrintf("AssetManager::OpenNonAssetFd(%s)", asset_path_utf8.c_str()).c_str());

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  std::unique_ptr<Asset> asset;
  if (cookie != kInvalidCookie) {
    asset = assetmanager->OpenNonAsset(asset_path_utf8.c_str(), cookie, Asset::ACCESS_RANDOM);
  } else {
    asset = assetmanager->OpenNonAsset(asset_path_utf8.c_str(), Asset::ACCESS_RANDOM);
  }

  if (!asset) {
    jniThrowException(env, "java/io/FileNotFoundException", asset_path_utf8.c_str());
    return nullptr;
  }
  return ReturnParcelFileDescriptor(env, std::move(asset), out_offsets);
}

static jlong NativeOpenXmlAsset(JNIEnv* env, jobject /*clazz*/, jlong ptr, jint jcookie,
                                jstring asset_path) {
  ApkAssetsCookie cookie = JavaCookieToApkAssetsCookie(jcookie);
  ScopedUtfChars asset_path_utf8(env, asset_path);
  if (asset_path_utf8.c_str() == nullptr) {
    // This will throw NPE.
    return 0;
  }

  ATRACE_NAME(base::StringPrintf("AssetManager::OpenXmlAsset(%s)", asset_path_utf8.c_str()).c_str());

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  std::unique_ptr<Asset> asset;
  if (cookie != kInvalidCookie) {
    asset = assetmanager->OpenNonAsset(asset_path_utf8.c_str(), cookie, Asset::ACCESS_RANDOM);
  } else {
    asset = assetmanager->OpenNonAsset(asset_path_utf8.c_str(), Asset::ACCESS_RANDOM, &cookie);
  }

  if (!asset) {
    jniThrowException(env, "java/io/FileNotFoundException", asset_path_utf8.c_str());
    return 0;
  }

  const incfs::map_ptr<void> buffer = asset->getIncFsBuffer(true /* aligned */);
  const size_t length = asset->getLength();
  if (!buffer.convert<uint8_t>().verify(length)) {
      jniThrowException(env, "java/io/FileNotFoundException",
                        "File not fully present due to incremental installation");
      return 0;
  }

  auto xml_tree = util::make_unique<ResXMLTree>(assetmanager->GetDynamicRefTableForCookie(cookie));
  status_t err = xml_tree->setTo(buffer.unsafe_ptr(), length, true);
  if (err != NO_ERROR) {
    jniThrowException(env, "java/io/FileNotFoundException", "Corrupt XML binary file");
    return 0;
  }
  return reinterpret_cast<jlong>(xml_tree.release());
}

static jlong NativeOpenXmlAssetFd(JNIEnv* env, jobject /*clazz*/, jlong ptr, int jcookie,
                                  jobject file_descriptor) {
  int fd = jniGetFDFromFileDescriptor(env, file_descriptor);
  ATRACE_NAME(base::StringPrintf("AssetManager::OpenXmlAssetFd(%d)", fd).c_str());
  if (fd < 0) {
    jniThrowException(env, "java/lang/IllegalArgumentException", "Bad FileDescriptor");
    return 0;
  }

  base::unique_fd dup_fd(::fcntl(fd, F_DUPFD_CLOEXEC, 0));
  if (dup_fd < 0) {
    jniThrowIOException(env, errno);
    return 0;
  }

  std::unique_ptr<Asset>
      asset(Asset::createFromFd(dup_fd.release(), nullptr, Asset::AccessMode::ACCESS_BUFFER));

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  ApkAssetsCookie cookie = JavaCookieToApkAssetsCookie(jcookie);

  const incfs::map_ptr<void> buffer = asset->getIncFsBuffer(true /* aligned */);
  const size_t length = asset->getLength();
  if (!buffer.convert<uint8_t>().verify(length)) {
      jniThrowException(env, "java/io/FileNotFoundException",
                        "File not fully present due to incremental installation");
      return 0;
  }

  auto xml_tree = util::make_unique<ResXMLTree>(assetmanager->GetDynamicRefTableForCookie(cookie));
  status_t err = xml_tree->setTo(buffer.unsafe_ptr(), length, true);
  if (err != NO_ERROR) {
    jniThrowException(env, "java/io/FileNotFoundException", "Corrupt XML binary file");
    return 0;
  }
  return reinterpret_cast<jlong>(xml_tree.release());
}

static jint NativeGetResourceValue(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid,
                                   jshort density, jobject typed_value,
                                   jboolean resolve_references) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto value = assetmanager->GetResource(static_cast<uint32_t>(resid), false /*may_be_bag*/,
                                         static_cast<uint16_t>(density));
  if (!value.has_value()) {
    return ApkAssetsCookieToJavaCookie(kInvalidCookie);
  }

  if (resolve_references) {
    auto result = assetmanager->ResolveReference(value.value());
    if (!result.has_value()) {
      return ApkAssetsCookieToJavaCookie(kInvalidCookie);
    }
  }
  return CopyValue(env, *value, typed_value);
}

static jint NativeGetResourceBagValue(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid,
                                      jint bag_entry_id, jobject typed_value) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto bag = assetmanager->GetBag(static_cast<uint32_t>(resid));
  if (!bag.has_value()) {
    return ApkAssetsCookieToJavaCookie(kInvalidCookie);
  }

  // The legacy would find the last entry with the target bag entry id
  using reverse_bag_iterator = std::reverse_iterator<const ResolvedBag::Entry*>;
  const auto rbegin = reverse_bag_iterator(end(*bag));
  const auto rend = reverse_bag_iterator(begin(*bag));
  auto entry = std::find_if(rbegin, rend, [bag_entry_id](auto&& e) {
    return e.key == static_cast<uint32_t>(bag_entry_id);
  });

  if (entry == rend) {
    return ApkAssetsCookieToJavaCookie(kInvalidCookie);
  }

  AssetManager2::SelectedValue attr_value(*bag, *entry);
  auto result = assetmanager->ResolveReference(attr_value);
  if (!result.has_value()) {
    return ApkAssetsCookieToJavaCookie(kInvalidCookie);
  }
  return CopyValue(env, attr_value, typed_value);
}

static jintArray NativeGetStyleAttributes(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto bag_result = assetmanager->GetBag(static_cast<uint32_t>(resid));
  if (!bag_result.has_value()) {
    return nullptr;
  }

  const ResolvedBag* bag = *bag_result;
  jintArray array = env->NewIntArray(bag->entry_count);
  if (env->ExceptionCheck()) {
    return nullptr;
  }

  for (uint32_t i = 0; i < bag->entry_count; i++) {
    jint attr_resid = bag->entries[i].key;
    env->SetIntArrayRegion(array, i, 1, &attr_resid);
  }
  return array;
}

static jobjectArray NativeGetResourceStringArray(JNIEnv* env, jclass /*clazz*/, jlong ptr,
                                                 jint resid) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto bag_result = assetmanager->GetBag(static_cast<uint32_t>(resid));
  if (!bag_result.has_value()) {
    return nullptr;
  }

  const ResolvedBag* bag = *bag_result;
  jobjectArray array = env->NewObjectArray(bag->entry_count, g_stringClass, nullptr);
  if (array == nullptr) {
    return nullptr;
  }

  for (uint32_t i = 0; i < bag->entry_count; i++) {
    // Resolve any references to their final value.
    AssetManager2::SelectedValue attr_value(bag, bag->entries[i]);
    auto result = assetmanager->ResolveReference(attr_value);
    if (!result.has_value()) {
      return nullptr;
    }

    if (attr_value.type == Res_value::TYPE_STRING) {
      const ApkAssets* apk_assets = assetmanager->GetApkAssets()[attr_value.cookie];
      const ResStringPool* pool = apk_assets->GetLoadedArsc()->GetStringPool();

      jstring java_string;
      if (auto str_utf8 = pool->string8At(attr_value.data); str_utf8.has_value()) {
          java_string = env->NewStringUTF(str_utf8->data());
      } else {
          auto str_utf16 = pool->stringAt(attr_value.data);
          if (!str_utf16.has_value()) {
              return nullptr;
          }
          java_string = env->NewString(reinterpret_cast<const jchar*>(str_utf16->data()),
                                       str_utf16->size());
      }

      // Check for errors creating the strings (if malformed or no memory).
      if (env->ExceptionCheck()) {
        return nullptr;
      }

      env->SetObjectArrayElement(array, i, java_string);

      // If we have a large amount of string in our array, we might overflow the
      // local reference table of the VM.
      env->DeleteLocalRef(java_string);
    }
  }
  return array;
}

static jintArray NativeGetResourceStringArrayInfo(JNIEnv* env, jclass /*clazz*/, jlong ptr,
                                                  jint resid) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto bag_result = assetmanager->GetBag(static_cast<uint32_t>(resid));
  if (!bag_result.has_value()) {
    return nullptr;
  }

  const ResolvedBag* bag = *bag_result;
  jintArray array = env->NewIntArray(bag->entry_count * 2);
  if (array == nullptr) {
    return nullptr;
  }

  jint* buffer = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(array, nullptr));
  if (buffer == nullptr) {
    return nullptr;
  }

  for (size_t i = 0; i < bag->entry_count; i++) {
    AssetManager2::SelectedValue attr_value(bag, bag->entries[i]);
    auto result = assetmanager->ResolveReference(attr_value);
    if (!result.has_value()) {
      env->ReleasePrimitiveArrayCritical(array, buffer, JNI_ABORT);
      return nullptr;
    }

    jint string_index = -1;
    if (attr_value.type == Res_value::TYPE_STRING) {
      string_index = static_cast<jint>(attr_value.data);
    }

    buffer[i * 2] = ApkAssetsCookieToJavaCookie(attr_value.cookie);
    buffer[(i * 2) + 1] = string_index;
  }
  env->ReleasePrimitiveArrayCritical(array, buffer, 0);
  return array;
}

static jintArray NativeGetResourceIntArray(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto bag_result = assetmanager->GetBag(static_cast<uint32_t>(resid));
  if (!bag_result.has_value()) {
    return nullptr;
  }

  const ResolvedBag* bag = *bag_result;
  jintArray array = env->NewIntArray(bag->entry_count);
  if (array == nullptr) {
    return nullptr;
  }

  jint* buffer = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(array, nullptr));
  if (buffer == nullptr) {
    return nullptr;
  }

  for (size_t i = 0; i < bag->entry_count; i++) {
    AssetManager2::SelectedValue attr_value(bag, bag->entries[i]);
    auto result = assetmanager->ResolveReference(attr_value);
    if (!result.has_value()) {
      env->ReleasePrimitiveArrayCritical(array, buffer, 0);
      return nullptr;
    }

    if (attr_value.type >= Res_value::TYPE_FIRST_INT &&
      attr_value.type <= Res_value::TYPE_LAST_INT) {
      buffer[i] = static_cast<jint>(attr_value.data);
    }
  }
  env->ReleasePrimitiveArrayCritical(array, buffer, 0);
  return array;
}

static jint NativeGetResourceArraySize(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid) {
    ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
    auto bag = assetmanager->GetBag(static_cast<uint32_t>(resid));
    if (!bag.has_value()) {
      return -1;
    }
    return static_cast<jint>((*bag)->entry_count);
}

static jint NativeGetResourceArray(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid,
                                   jintArray out_data) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto bag_result = assetmanager->GetBag(static_cast<uint32_t>(resid));
  if (!bag_result.has_value()) {
    return -1;
  }

  const jsize out_data_length = env->GetArrayLength(out_data);
  if (env->ExceptionCheck()) {
    return -1;
  }

  const ResolvedBag* bag = *bag_result;
  if (static_cast<jsize>(bag->entry_count) > out_data_length * STYLE_NUM_ENTRIES) {
    jniThrowException(env, "java/lang/IllegalArgumentException",
                      "Input array is not large enough");
    return -1;
  }

  jint* buffer = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(out_data, nullptr));
  if (buffer == nullptr) {
    return -1;
  }

  jint* cursor = buffer;
  for (size_t i = 0; i < bag->entry_count; i++) {
    AssetManager2::SelectedValue attr_value(bag, bag->entries[i]);
    auto result = assetmanager->ResolveReference(attr_value);
    if (!result.has_value()) {
      env->ReleasePrimitiveArrayCritical(out_data, buffer, JNI_ABORT);
      return -1;
    }

    // Deal with the special @null value -- it turns back to TYPE_NULL.
    if (attr_value.type == Res_value::TYPE_REFERENCE && attr_value.data == 0) {
      attr_value.type = Res_value::TYPE_NULL;
      attr_value.data = Res_value::DATA_NULL_UNDEFINED;
    }

    cursor[STYLE_TYPE] = static_cast<jint>(attr_value.type);
    cursor[STYLE_DATA] = static_cast<jint>(attr_value.data);
    cursor[STYLE_ASSET_COOKIE] = ApkAssetsCookieToJavaCookie(attr_value.cookie);
    cursor[STYLE_RESOURCE_ID] = static_cast<jint>(attr_value.resid);
    cursor[STYLE_CHANGING_CONFIGURATIONS] = static_cast<jint>(attr_value.flags);
    cursor[STYLE_DENSITY] = static_cast<jint>(attr_value.config.density);
    cursor += STYLE_NUM_ENTRIES;
  }
  env->ReleasePrimitiveArrayCritical(out_data, buffer, 0);
  return static_cast<jint>(bag->entry_count);
}

static jint NativeGetResourceIdentifier(JNIEnv* env, jclass /*clazz*/, jlong ptr, jstring name,
                                        jstring def_type, jstring def_package) {
  ScopedUtfChars name_utf8(env, name);
  if (name_utf8.c_str() == nullptr) {
    // This will throw NPE.
    return 0;
  }

  std::string type;
  if (def_type != nullptr) {
    ScopedUtfChars type_utf8(env, def_type);
    CHECK(type_utf8.c_str() != nullptr);
    type = type_utf8.c_str();
  }

  std::string package;
  if (def_package != nullptr) {
    ScopedUtfChars package_utf8(env, def_package);
    CHECK(package_utf8.c_str() != nullptr);
    package = package_utf8.c_str();
  }

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto resid = assetmanager->GetResourceId(name_utf8.c_str(), type, package);
  if (!resid.has_value()) {
    return 0;
  }

  return static_cast<jint>(*resid);
}

static jstring NativeGetResourceName(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto name = assetmanager->GetResourceName(static_cast<uint32_t>(resid));
  if (!name.has_value()) {
    return nullptr;
  }

  const std::string result = ToFormattedResourceString(name.value());
  return env->NewStringUTF(result.c_str());
}

static jstring NativeGetResourcePackageName(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto name = assetmanager->GetResourceName(static_cast<uint32_t>(resid));
  if (!name.has_value()) {
    return nullptr;
  }

  if (name->package != nullptr) {
    return env->NewStringUTF(name->package);
  }
  return nullptr;
}

static jstring NativeGetResourceTypeName(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto name = assetmanager->GetResourceName(static_cast<uint32_t>(resid));
  if (!name.has_value()) {
    return nullptr;
  }

  if (name->type != nullptr) {
    return env->NewStringUTF(name->type);
  } else if (name->type16 != nullptr) {
    return env->NewString(reinterpret_cast<const jchar*>(name->type16), name->type_len);
  }
  return nullptr;
}

static jstring NativeGetResourceEntryName(JNIEnv* env, jclass /*clazz*/, jlong ptr, jint resid) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto name = assetmanager->GetResourceName(static_cast<uint32_t>(resid));
  if (!name.has_value()) {
    return nullptr;
  }

  if (name->entry != nullptr) {
    return env->NewStringUTF(name->entry);
  } else if (name->entry16 != nullptr) {
    return env->NewString(reinterpret_cast<const jchar*>(name->entry16), name->entry_len);
  }
  return nullptr;
}

static void NativeSetResourceResolutionLoggingEnabled(JNIEnv* /*env*/,
                                                      jclass /*clazz*/,
                                                      jlong ptr,
                                                      jboolean enabled) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  assetmanager->SetResourceResolutionLoggingEnabled(enabled);
}

static jstring NativeGetLastResourceResolution(JNIEnv* env,
                                               jclass /*clazz*/,
                                               jlong ptr) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  std::string resolution = assetmanager->GetLastResourceResolution();
  if (resolution.empty()) {
    return nullptr;
  } else {
    return env->NewStringUTF(resolution.c_str());
  }
}

static jobjectArray NativeGetLocales(JNIEnv* env, jclass /*class*/, jlong ptr,
                                     jboolean exclude_system) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  std::set<std::string> locales =
      assetmanager->GetResourceLocales(exclude_system, true /*merge_equivalent_languages*/);

  jobjectArray array = env->NewObjectArray(locales.size(), g_stringClass, nullptr);
  if (array == nullptr) {
    return nullptr;
  }

  size_t idx = 0;
  for (const std::string& locale : locales) {
    jstring java_string = env->NewStringUTF(locale.c_str());
    if (java_string == nullptr) {
      return nullptr;
    }
    env->SetObjectArrayElement(array, idx++, java_string);
    env->DeleteLocalRef(java_string);
  }
  return array;
}

static jobject ConstructConfigurationObject(JNIEnv* env, const ResTable_config& config) {
  jobject result =
      env->NewObject(gConfigurationOffsets.classObject, gConfigurationOffsets.constructor);
  if (result == nullptr) {
    return nullptr;
  }

  env->SetIntField(result, gConfigurationOffsets.mSmallestScreenWidthDpOffset,
                   config.smallestScreenWidthDp);
  env->SetIntField(result, gConfigurationOffsets.mScreenWidthDpOffset, config.screenWidthDp);
  env->SetIntField(result, gConfigurationOffsets.mScreenHeightDpOffset, config.screenHeightDp);
  return result;
}

static jobjectArray NativeGetSizeConfigurations(JNIEnv* env, jclass /*clazz*/, jlong ptr) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  auto configurations = assetmanager->GetResourceConfigurations(true /*exclude_system*/,
                                                                false /*exclude_mipmap*/);
  if (!configurations.has_value()) {
    return nullptr;
  }

  jobjectArray array =
      env->NewObjectArray(configurations->size(), gConfigurationOffsets.classObject, nullptr);
  if (array == nullptr) {
    return nullptr;
  }

  size_t idx = 0;
  for (const ResTable_config& configuration : *configurations) {
    jobject java_configuration = ConstructConfigurationObject(env, configuration);
    if (java_configuration == nullptr) {
      return nullptr;
    }

    env->SetObjectArrayElement(array, idx++, java_configuration);
    env->DeleteLocalRef(java_configuration);
  }
  return array;
}

static jintArray NativeAttributeResolutionStack(
    JNIEnv* env, jclass /*clazz*/, jlong ptr,
    jlong theme_ptr, jint xml_style_res,
    jint def_style_attr, jint def_style_resid) {

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  Theme* theme = reinterpret_cast<Theme*>(theme_ptr);
  CHECK(theme->GetAssetManager() == &(*assetmanager));
  (void) assetmanager;

  // Load default style from attribute, if specified...
  if (def_style_attr != 0) {
    auto value = theme->GetAttribute(def_style_attr);
    if (value.has_value() && value->type == Res_value::TYPE_REFERENCE) {
      def_style_resid = value->data;
    }
  }

  auto style_stack = assetmanager->GetBagResIdStack(xml_style_res);
  auto def_style_stack = assetmanager->GetBagResIdStack(def_style_resid);

  jintArray array = env->NewIntArray(style_stack.size() + def_style_stack.size());
  if (env->ExceptionCheck()) {
    return nullptr;
  }

  for (uint32_t i = 0; i < style_stack.size(); i++) {
    jint attr_resid = style_stack[i];
    env->SetIntArrayRegion(array, i, 1, &attr_resid);
  }
  for (uint32_t i = 0; i < def_style_stack.size(); i++) {
    jint attr_resid = def_style_stack[i];
    env->SetIntArrayRegion(array, style_stack.size() + i, 1, &attr_resid);
  }
  return array;
}

static void NativeApplyStyle(JNIEnv* env, jclass /*clazz*/, jlong ptr, jlong theme_ptr,
                             jint def_style_attr, jint def_style_resid, jlong xml_parser_ptr,
                             jintArray java_attrs, jlong out_values_ptr, jlong out_indices_ptr) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  Theme* theme = reinterpret_cast<Theme*>(theme_ptr);
  CHECK(theme->GetAssetManager() == &(*assetmanager));
  (void) assetmanager;

  ResXMLParser* xml_parser = reinterpret_cast<ResXMLParser*>(xml_parser_ptr);
  uint32_t* out_values = reinterpret_cast<uint32_t*>(out_values_ptr);
  uint32_t* out_indices = reinterpret_cast<uint32_t*>(out_indices_ptr);

  jsize attrs_len = env->GetArrayLength(java_attrs);
  jint* attrs = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(java_attrs, nullptr));
  if (attrs == nullptr) {
    return;
  }

  ApplyStyle(theme, xml_parser, static_cast<uint32_t>(def_style_attr),
             static_cast<uint32_t>(def_style_resid), reinterpret_cast<uint32_t*>(attrs), attrs_len,
             out_values, out_indices);
  env->ReleasePrimitiveArrayCritical(java_attrs, attrs, JNI_ABORT);
}

static jboolean NativeResolveAttrs(JNIEnv* env, jclass /*clazz*/, jlong ptr, jlong theme_ptr,
                                   jint def_style_attr, jint def_style_resid, jintArray java_values,
                                   jintArray java_attrs, jintArray out_java_values,
                                   jintArray out_java_indices) {
  const jsize attrs_len = env->GetArrayLength(java_attrs);
  const jsize out_values_len = env->GetArrayLength(out_java_values);
  if (out_values_len < (attrs_len * STYLE_NUM_ENTRIES)) {
    jniThrowException(env, "java/lang/IndexOutOfBoundsException", "outValues too small");
    return JNI_FALSE;
  }

  jint* attrs = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(java_attrs, nullptr));
  if (attrs == nullptr) {
    return JNI_FALSE;
  }

  jint* values = nullptr;
  jsize values_len = 0;
  if (java_values != nullptr) {
    values_len = env->GetArrayLength(java_values);
    values = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(java_values, nullptr));
    if (values == nullptr) {
      env->ReleasePrimitiveArrayCritical(java_attrs, attrs, JNI_ABORT);
      return JNI_FALSE;
    }
  }

  jint* out_values =
      reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(out_java_values, nullptr));
  if (out_values == nullptr) {
    env->ReleasePrimitiveArrayCritical(java_attrs, attrs, JNI_ABORT);
    if (values != nullptr) {
      env->ReleasePrimitiveArrayCritical(java_values, values, JNI_ABORT);
    }
    return JNI_FALSE;
  }

  jint* out_indices = nullptr;
  if (out_java_indices != nullptr) {
    jsize out_indices_len = env->GetArrayLength(out_java_indices);
    if (out_indices_len > attrs_len) {
      out_indices =
          reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(out_java_indices, nullptr));
      if (out_indices == nullptr) {
        env->ReleasePrimitiveArrayCritical(java_attrs, attrs, JNI_ABORT);
        if (values != nullptr) {
          env->ReleasePrimitiveArrayCritical(java_values, values, JNI_ABORT);
        }
        env->ReleasePrimitiveArrayCritical(out_java_values, out_values, JNI_ABORT);
        return JNI_FALSE;
      }
    }
  }

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  Theme* theme = reinterpret_cast<Theme*>(theme_ptr);
  CHECK(theme->GetAssetManager() == &(*assetmanager));
  (void) assetmanager;
  auto result =
          ResolveAttrs(theme, static_cast<uint32_t>(def_style_attr),
                       static_cast<uint32_t>(def_style_resid), reinterpret_cast<uint32_t*>(values),
                       values_len, reinterpret_cast<uint32_t*>(attrs), attrs_len,
                       reinterpret_cast<uint32_t*>(out_values),
                       reinterpret_cast<uint32_t*>(out_indices));
  if (out_indices != nullptr) {
    env->ReleasePrimitiveArrayCritical(out_java_indices, out_indices, 0);
  }

  env->ReleasePrimitiveArrayCritical(out_java_values, out_values, 0);
  if (values != nullptr) {
    env->ReleasePrimitiveArrayCritical(java_values, values, JNI_ABORT);
  }

  env->ReleasePrimitiveArrayCritical(java_attrs, attrs, JNI_ABORT);
  return result.has_value() ? JNI_TRUE : JNI_FALSE;
}

static jboolean NativeRetrieveAttributes(JNIEnv* env, jclass /*clazz*/, jlong ptr,
                                         jlong xml_parser_ptr, jintArray java_attrs,
                                         jintArray out_java_values, jintArray out_java_indices) {
  const jsize attrs_len = env->GetArrayLength(java_attrs);
  const jsize out_values_len = env->GetArrayLength(out_java_values);
  if (out_values_len < (attrs_len * STYLE_NUM_ENTRIES)) {
    jniThrowException(env, "java/lang/IndexOutOfBoundsException", "outValues too small");
    return JNI_FALSE;
  }

  jint* attrs = reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(java_attrs, nullptr));
  if (attrs == nullptr) {
    return JNI_FALSE;
  }

  jint* out_values =
      reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(out_java_values, nullptr));
  if (out_values == nullptr) {
    env->ReleasePrimitiveArrayCritical(java_attrs, attrs, JNI_ABORT);
    return JNI_FALSE;
  }

  jint* out_indices = nullptr;
  if (out_java_indices != nullptr) {
    jsize out_indices_len = env->GetArrayLength(out_java_indices);
    if (out_indices_len > attrs_len) {
      out_indices =
          reinterpret_cast<jint*>(env->GetPrimitiveArrayCritical(out_java_indices, nullptr));
      if (out_indices == nullptr) {
        env->ReleasePrimitiveArrayCritical(java_attrs, attrs, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(out_java_values, out_values, JNI_ABORT);
        return JNI_FALSE;
      }
    }
  }

  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  ResXMLParser* xml_parser = reinterpret_cast<ResXMLParser*>(xml_parser_ptr);
  auto result =
          RetrieveAttributes(assetmanager.get(), xml_parser, reinterpret_cast<uint32_t*>(attrs),
                             attrs_len, reinterpret_cast<uint32_t*>(out_values),
                             reinterpret_cast<uint32_t*>(out_indices));

  if (out_indices != nullptr) {
    env->ReleasePrimitiveArrayCritical(out_java_indices, out_indices, 0);
  }

  env->ReleasePrimitiveArrayCritical(out_java_values, out_values, 0);
  env->ReleasePrimitiveArrayCritical(java_attrs, attrs, JNI_ABORT);
  return result.has_value() ? JNI_TRUE : JNI_FALSE;
}

static jlong NativeThemeCreate(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  return reinterpret_cast<jlong>(assetmanager->NewTheme().release());
}

static void NativeThemeDestroy(jlong theme_ptr) {
  delete reinterpret_cast<Theme*>(theme_ptr);
}

static jlong NativeGetThemeFreeFunction(JNIEnv* /*env*/, jclass /*clazz*/) {
  return static_cast<jlong>(reinterpret_cast<uintptr_t>(&NativeThemeDestroy));
}

static void NativeThemeApplyStyle(JNIEnv* env, jclass /*clazz*/, jlong ptr, jlong theme_ptr,
                                  jint resid, jboolean force) {
  // AssetManager is accessed via the theme, so grab an explicit lock here.
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  Theme* theme = reinterpret_cast<Theme*>(theme_ptr);
  CHECK(theme->GetAssetManager() == &(*assetmanager));
  (void) assetmanager;

  theme->ApplyStyle(static_cast<uint32_t>(resid), force);

  // TODO(adamlesinski): Consider surfacing exception when result is failure.
  // CTS currently expects no exceptions from this method.
  // std::string error_msg = StringPrintf("Failed to apply style 0x%08x to theme", resid);
  // jniThrowException(env, "java/lang/IllegalArgumentException", error_msg.c_str());
}

static void NativeThemeRebase(JNIEnv* env, jclass /*clazz*/, jlong ptr, jlong theme_ptr,
                              jintArray style_ids, jbooleanArray force,
                              jint style_count) {
  // Lock both the original asset manager of the theme and the new asset manager to be used for the
  // theme.
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));

  uint32_t* style_id_args = nullptr;
  if (style_ids != nullptr) {
    CHECK(style_count <= env->GetArrayLength(style_ids));
    style_id_args = reinterpret_cast<uint32_t*>(env->GetPrimitiveArrayCritical(style_ids, nullptr));
    if (style_id_args == nullptr) {
      return;
    }
  } else {
    CHECK(style_count == 0) << "style_ids is null while style_count is non-zero";
  }

  jboolean* force_args = nullptr;
  if (force != nullptr) {
    CHECK(style_count <= env->GetArrayLength(force));
    force_args = reinterpret_cast<jboolean*>(env->GetPrimitiveArrayCritical(force, nullptr));
    if (force_args == nullptr) {
      env->ReleasePrimitiveArrayCritical(style_ids, style_id_args, JNI_ABORT);
      return;
    }
  } else {
    CHECK(style_count == 0) << "force is null while style_count is non-zero";
  }

  auto theme = reinterpret_cast<Theme*>(theme_ptr);
  theme->Rebase(&(*assetmanager), style_id_args, force_args, static_cast<size_t>(style_count));
  if (style_ids != nullptr) {
    env->ReleasePrimitiveArrayCritical(style_ids, style_id_args, JNI_ABORT);
  }
  if (force != nullptr) {
    env->ReleasePrimitiveArrayCritical(force, force_args, JNI_ABORT);
  }
}

static void NativeThemeCopy(JNIEnv* env, jclass /*clazz*/, jlong dst_asset_manager_ptr,
                            jlong dst_theme_ptr, jlong src_asset_manager_ptr, jlong src_theme_ptr) {
  Theme* dst_theme = reinterpret_cast<Theme*>(dst_theme_ptr);
  Theme* src_theme = reinterpret_cast<Theme*>(src_theme_ptr);

  ScopedLock<AssetManager2> src_assetmanager(AssetManagerFromLong(src_asset_manager_ptr));
  CHECK(src_theme->GetAssetManager() == &(*src_assetmanager));
  (void) src_assetmanager;

  if (dst_asset_manager_ptr != src_asset_manager_ptr) {
    ScopedLock<AssetManager2> dst_assetmanager(AssetManagerFromLong(dst_asset_manager_ptr));
    CHECK(dst_theme->GetAssetManager() == &(*dst_assetmanager));
    (void) dst_assetmanager;

    dst_theme->SetTo(*src_theme);
  } else {
      dst_theme->SetTo(*src_theme);
  }
}

static jint NativeThemeGetAttributeValue(JNIEnv* env, jclass /*clazz*/, jlong ptr, jlong theme_ptr,
                                         jint resid, jobject typed_value,
                                         jboolean resolve_references) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  Theme* theme = reinterpret_cast<Theme*>(theme_ptr);
  CHECK(theme->GetAssetManager() == &(*assetmanager));
  (void) assetmanager;

  auto value = theme->GetAttribute(static_cast<uint32_t>(resid));
  if (!value.has_value()) {
    return ApkAssetsCookieToJavaCookie(kInvalidCookie);
  }

  if (!resolve_references) {
    return CopyValue(env, *value, typed_value);
  }

  auto result = theme->GetAssetManager()->ResolveReference(*value);
  if (!result.has_value()) {
    return ApkAssetsCookieToJavaCookie(kInvalidCookie);
  }
  return CopyValue(env, *value, typed_value);
}

static void NativeThemeDump(JNIEnv* /*env*/, jclass /*clazz*/, jlong ptr, jlong theme_ptr,
                            jint priority, jstring tag, jstring prefix) {
  ScopedLock<AssetManager2> assetmanager(AssetManagerFromLong(ptr));
  Theme* theme = reinterpret_cast<Theme*>(theme_ptr);
  CHECK(theme->GetAssetManager() == &(*assetmanager));
  (void) assetmanager;
  (void) priority;
  (void) tag;
  (void) prefix;

  theme->Dump();
}

static jint NativeThemeGetChangingConfigurations(JNIEnv* /*env*/, jclass /*clazz*/,
                                                 jlong theme_ptr) {
  Theme* theme = reinterpret_cast<Theme*>(theme_ptr);
  return static_cast<jint>(theme->GetChangingConfigurations());
}

static void NativeAssetDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong asset_ptr) {
  delete reinterpret_cast<Asset*>(asset_ptr);
}

static jint NativeAssetReadChar(JNIEnv* /*env*/, jclass /*clazz*/, jlong asset_ptr) {
  Asset* asset = reinterpret_cast<Asset*>(asset_ptr);
  uint8_t b;
  ssize_t res = asset->read(&b, sizeof(b));
  return res == sizeof(b) ? static_cast<jint>(b) : -1;
}

static jint NativeAssetRead(JNIEnv* env, jclass /*clazz*/, jlong asset_ptr, jbyteArray java_buffer,
                            jint offset, jint len) {
  if (len == 0) {
    return 0;
  }

  jsize buffer_len = env->GetArrayLength(java_buffer);
  if (offset < 0 || offset >= buffer_len || len < 0 || len > buffer_len ||
      offset > buffer_len - len) {
    jniThrowException(env, "java/lang/IndexOutOfBoundsException", "");
    return -1;
  }

  ScopedByteArrayRW byte_array(env, java_buffer);
  if (byte_array.get() == nullptr) {
    return -1;
  }

  Asset* asset = reinterpret_cast<Asset*>(asset_ptr);
  ssize_t res = asset->read(byte_array.get() + offset, len);
  if (res < 0) {
    jniThrowException(env, "java/io/IOException", "");
    return -1;
  }
  return res > 0 ? static_cast<jint>(res) : -1;
}

static jlong NativeAssetSeek(JNIEnv* env, jclass /*clazz*/, jlong asset_ptr, jlong offset,
                             jint whence) {
  Asset* asset = reinterpret_cast<Asset*>(asset_ptr);
  return static_cast<jlong>(asset->seek(
      static_cast<off64_t>(offset), (whence > 0 ? SEEK_END : (whence < 0 ? SEEK_SET : SEEK_CUR))));
}

static jlong NativeAssetGetLength(JNIEnv* /*env*/, jclass /*clazz*/, jlong asset_ptr) {
  Asset* asset = reinterpret_cast<Asset*>(asset_ptr);
  return static_cast<jlong>(asset->getLength());
}

static jlong NativeAssetGetRemainingLength(JNIEnv* /*env*/, jclass /*clazz*/, jlong asset_ptr) {
  Asset* asset = reinterpret_cast<Asset*>(asset_ptr);
  return static_cast<jlong>(asset->getRemainingLength());
}

// ----------------------------------------------------------------------------

// JNI registration.
static const JNINativeMethod gAssetManagerMethods[] = {
    // AssetManager setup methods.
    {"nativeCreate", "()J", (void*)NativeCreate},
    {"nativeDestroy", "(J)V", (void*)NativeDestroy},
    {"nativeSetApkAssets", "(J[Landroid/content/res/ApkAssets;Z)V", (void*)NativeSetApkAssets},
    {"nativeSetConfiguration", "(JIILjava/lang/String;IIIIIIIIIIIIIII)V",
     (void*)NativeSetConfiguration},
    {"nativeGetAssignedPackageIdentifiers", "(JZZ)Landroid/util/SparseArray;",
     (void*)NativeGetAssignedPackageIdentifiers},

    // AssetManager file methods.
    {"nativeContainsAllocatedTable", "(J)Z", (void*)ContainsAllocatedTable},
    {"nativeList", "(JLjava/lang/String;)[Ljava/lang/String;", (void*)NativeList},
    {"nativeOpenAsset", "(JLjava/lang/String;I)J", (void*)NativeOpenAsset},
    {"nativeOpenAssetFd", "(JLjava/lang/String;[J)Landroid/os/ParcelFileDescriptor;",
     (void*)NativeOpenAssetFd},
    {"nativeOpenNonAsset", "(JILjava/lang/String;I)J", (void*)NativeOpenNonAsset},
    {"nativeOpenNonAssetFd", "(JILjava/lang/String;[J)Landroid/os/ParcelFileDescriptor;",
     (void*)NativeOpenNonAssetFd},
    {"nativeOpenXmlAsset", "(JILjava/lang/String;)J", (void*)NativeOpenXmlAsset},
    {"nativeOpenXmlAssetFd", "(JILjava/io/FileDescriptor;)J", (void*)NativeOpenXmlAssetFd},

    // AssetManager resource methods.
    {"nativeGetResourceValue", "(JISLandroid/util/TypedValue;Z)I", (void*)NativeGetResourceValue},
    {"nativeGetResourceBagValue", "(JIILandroid/util/TypedValue;)I",
     (void*)NativeGetResourceBagValue},
    {"nativeGetStyleAttributes", "(JI)[I", (void*)NativeGetStyleAttributes},
    {"nativeGetResourceStringArray", "(JI)[Ljava/lang/String;",
     (void*)NativeGetResourceStringArray},
    {"nativeGetResourceStringArrayInfo", "(JI)[I", (void*)NativeGetResourceStringArrayInfo},
    {"nativeGetResourceIntArray", "(JI)[I", (void*)NativeGetResourceIntArray},
    {"nativeGetResourceArraySize", "(JI)I", (void*)NativeGetResourceArraySize},
    {"nativeGetResourceArray", "(JI[I)I", (void*)NativeGetResourceArray},

    // AssetManager resource name/ID methods.
    {"nativeGetResourceIdentifier", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
     (void*)NativeGetResourceIdentifier},
    {"nativeGetResourceName", "(JI)Ljava/lang/String;", (void*)NativeGetResourceName},
    {"nativeGetResourcePackageName", "(JI)Ljava/lang/String;", (void*)NativeGetResourcePackageName},
    {"nativeGetResourceTypeName", "(JI)Ljava/lang/String;", (void*)NativeGetResourceTypeName},
    {"nativeGetResourceEntryName", "(JI)Ljava/lang/String;", (void*)NativeGetResourceEntryName},
    {"nativeSetResourceResolutionLoggingEnabled", "(JZ)V",
     (void*) NativeSetResourceResolutionLoggingEnabled},
    {"nativeGetLastResourceResolution", "(J)Ljava/lang/String;",
     (void*) NativeGetLastResourceResolution},
    {"nativeGetLocales", "(JZ)[Ljava/lang/String;", (void*)NativeGetLocales},
    {"nativeGetSizeConfigurations", "(J)[Landroid/content/res/Configuration;",
     (void*)NativeGetSizeConfigurations},

    // Style attribute related methods.
    {"nativeAttributeResolutionStack", "(JJIII)[I", (void*)NativeAttributeResolutionStack},
    {"nativeApplyStyle", "(JJIIJ[IJJ)V", (void*)NativeApplyStyle},
    {"nativeResolveAttrs", "(JJII[I[I[I[I)Z", (void*)NativeResolveAttrs},
    {"nativeRetrieveAttributes", "(JJ[I[I[I)Z", (void*)NativeRetrieveAttributes},

    // Theme related methods.
    {"nativeThemeCreate", "(J)J", (void*)NativeThemeCreate},
    {"nativeGetThemeFreeFunction", "()J", (void*)NativeGetThemeFreeFunction},
    {"nativeThemeApplyStyle", "(JJIZ)V", (void*)NativeThemeApplyStyle},
    {"nativeThemeRebase", "(JJ[I[ZI)V", (void*)NativeThemeRebase},

    {"nativeThemeCopy", "(JJJJ)V", (void*)NativeThemeCopy},
    {"nativeThemeGetAttributeValue", "(JJILandroid/util/TypedValue;Z)I",
     (void*)NativeThemeGetAttributeValue},
    {"nativeThemeDump", "(JJILjava/lang/String;Ljava/lang/String;)V", (void*)NativeThemeDump},
    {"nativeThemeGetChangingConfigurations", "(J)I", (void*)NativeThemeGetChangingConfigurations},

    // AssetInputStream methods.
    {"nativeAssetDestroy", "(J)V", (void*)NativeAssetDestroy},
    {"nativeAssetReadChar", "(J)I", (void*)NativeAssetReadChar},
    {"nativeAssetRead", "(J[BII)I", (void*)NativeAssetRead},
    {"nativeAssetSeek", "(JJI)J", (void*)NativeAssetSeek},
    {"nativeAssetGetLength", "(J)J", (void*)NativeAssetGetLength},
    {"nativeAssetGetRemainingLength", "(J)J", (void*)NativeAssetGetRemainingLength},

    // System/idmap related methods.
    {"nativeGetOverlayableMap", "(JLjava/lang/String;)Ljava/util/Map;",
     (void*)NativeGetOverlayableMap},
    {"nativeGetOverlayablesToString", "(JLjava/lang/String;)Ljava/lang/String;",
     (void*)NativeGetOverlayablesToString},

    // Global management/debug methods.
    {"getGlobalAssetCount", "()I", (void*)NativeGetGlobalAssetCount},
    {"getAssetAllocations", "()Ljava/lang/String;", (void*)NativeGetAssetAllocations},
    {"getGlobalAssetManagerCount", "()I", (void*)NativeGetGlobalAssetManagerCount},
};

int register_android_content_AssetManager(JNIEnv* env) {
  jclass apk_assets_class = FindClassOrDie(env, "android/content/res/ApkAssets");
  gApkAssetsFields.native_ptr = GetFieldIDOrDie(env, apk_assets_class, "mNativePtr", "J");

  jclass typedValue = FindClassOrDie(env, "android/util/TypedValue");
  gTypedValueOffsets.mType = GetFieldIDOrDie(env, typedValue, "type", "I");
  gTypedValueOffsets.mData = GetFieldIDOrDie(env, typedValue, "data", "I");
  gTypedValueOffsets.mString =
      GetFieldIDOrDie(env, typedValue, "string", "Ljava/lang/CharSequence;");
  gTypedValueOffsets.mAssetCookie = GetFieldIDOrDie(env, typedValue, "assetCookie", "I");
  gTypedValueOffsets.mResourceId = GetFieldIDOrDie(env, typedValue, "resourceId", "I");
  gTypedValueOffsets.mChangingConfigurations =
      GetFieldIDOrDie(env, typedValue, "changingConfigurations", "I");
  gTypedValueOffsets.mDensity = GetFieldIDOrDie(env, typedValue, "density", "I");

  jclass assetManager = FindClassOrDie(env, "android/content/res/AssetManager");
  gAssetManagerOffsets.mObject = GetFieldIDOrDie(env, assetManager, "mObject", "J");

  jclass stringClass = FindClassOrDie(env, "java/lang/String");
  g_stringClass = MakeGlobalRefOrDie(env, stringClass);

  jclass sparseArrayClass = FindClassOrDie(env, "android/util/SparseArray");
  gSparseArrayOffsets.classObject = MakeGlobalRefOrDie(env, sparseArrayClass);
  gSparseArrayOffsets.constructor =
      GetMethodIDOrDie(env, gSparseArrayOffsets.classObject, "<init>", "()V");
  gSparseArrayOffsets.put =
      GetMethodIDOrDie(env, gSparseArrayOffsets.classObject, "put", "(ILjava/lang/Object;)V");

  jclass configurationClass = FindClassOrDie(env, "android/content/res/Configuration");
  gConfigurationOffsets.classObject = MakeGlobalRefOrDie(env, configurationClass);
  gConfigurationOffsets.constructor = GetMethodIDOrDie(env, configurationClass, "<init>", "()V");
  gConfigurationOffsets.mSmallestScreenWidthDpOffset =
      GetFieldIDOrDie(env, configurationClass, "smallestScreenWidthDp", "I");
  gConfigurationOffsets.mScreenWidthDpOffset =
      GetFieldIDOrDie(env, configurationClass, "screenWidthDp", "I");
  gConfigurationOffsets.mScreenHeightDpOffset =
      GetFieldIDOrDie(env, configurationClass, "screenHeightDp", "I");

  jclass arrayMapClass = FindClassOrDie(env, "android/util/ArrayMap");
  gArrayMapOffsets.classObject = MakeGlobalRefOrDie(env, arrayMapClass);
  gArrayMapOffsets.constructor =
      GetMethodIDOrDie(env, gArrayMapOffsets.classObject, "<init>", "()V");
  gArrayMapOffsets.put =
      GetMethodIDOrDie(env, gArrayMapOffsets.classObject, "put",
                       "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

  return RegisterMethodsOrDie(env, "android/content/res/AssetManager", gAssetManagerMethods,
                              NELEM(gAssetManagerMethods));
}

}; // namespace android
