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

#include "android_util_AssetManager_private.h"
#include "core_jni_helpers.h"
#include "jni.h"
#include "nativehelper/ScopedUtfChars.h"

using ::android::base::unique_fd;

namespace android {

static struct overlayableinfo_offsets_t {
  jclass classObject;
  jmethodID constructor;
} gOverlayableInfoOffsets;

static struct assetfiledescriptor_offsets_t {
  jfieldID mFd;
  jfieldID mStartOffset;
  jfieldID mLength;
} gAssetFileDescriptorOffsets;

static struct assetsprovider_offsets_t {
  jclass classObject;
  jmethodID loadAssetFd;
  jmethodID toString;
} gAssetsProviderOffsets;

static struct {
  jmethodID detachFd;
} gParcelFileDescriptorOffsets;

// Keep in sync with f/b/android/content/res/ApkAssets.java
using format_type_t = jint;
enum : format_type_t {
  // The path used to load the apk assets represents an APK file.
  FORMAT_APK = 0,

  // The path used to load the apk assets represents an idmap file.
  FORMAT_IDMAP = 1,

  // The path used to load the apk assets represents an resources.arsc file.
  FORMAT_ARSC = 2,

  // The path used to load the apk assets represents the a directory.
  FORMAT_DIRECTORY = 3,
};

class LoaderAssetsProvider : public AssetsProvider {
 public:
  static std::unique_ptr<AssetsProvider> Create(JNIEnv* env, jobject assets_provider) {
    return (!assets_provider) ? EmptyAssetsProvider::Create()
                              : std::unique_ptr<AssetsProvider>(new LoaderAssetsProvider(
                                    env, assets_provider));
  }

  bool ForEachFile(const std::string& /* root_path */,
                   const std::function<void(const StringPiece&, FileType)>& /* f */) const {
    return true;
  }

  const std::string& GetDebugName() const override {
    return debug_name_;
  }

  bool IsUpToDate() const override {
    return true;
  }

  ~LoaderAssetsProvider() override {
    const auto env = AndroidRuntime::getJNIEnv();
    CHECK(env != nullptr)  << "Current thread not attached to a Java VM."
                           << " Failed to close LoaderAssetsProvider.";
    env->DeleteGlobalRef(assets_provider_);
  }

 protected:
  std::unique_ptr<Asset> OpenInternal(const std::string& path,
                                      Asset::AccessMode mode,
                                      bool* file_exists) const override {
    const auto env = AndroidRuntime::getJNIEnv();
    CHECK(env != nullptr) << "Current thread not attached to a Java VM."
                          << " ResourcesProvider assets cannot be retrieved on current thread.";

    jstring java_string = env->NewStringUTF(path.c_str());
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
      return nullptr;
    }

    // Check if the AssetsProvider provides a value for the path.
    jobject asset_fd = env->CallObjectMethod(assets_provider_,
                                             gAssetsProviderOffsets.loadAssetFd,
                                             java_string, static_cast<jint>(mode));
    env->DeleteLocalRef(java_string);
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
      return nullptr;
    }

    if (!asset_fd) {
      if (file_exists) {
        *file_exists = false;
      }
      return nullptr;
    }

    const jlong mOffset = env->GetLongField(asset_fd, gAssetFileDescriptorOffsets.mStartOffset);
    const jlong mLength = env->GetLongField(asset_fd, gAssetFileDescriptorOffsets.mLength);
    jobject mFd = env->GetObjectField(asset_fd, gAssetFileDescriptorOffsets.mFd);
    env->DeleteLocalRef(asset_fd);

    if (!mFd) {
      jniThrowException(env, "java/lang/NullPointerException", nullptr);
      env->ExceptionDescribe();
      env->ExceptionClear();
      return nullptr;
    }

    // Gain ownership of the file descriptor.
    const jint fd = env->CallIntMethod(mFd, gParcelFileDescriptorOffsets.detachFd);
    env->DeleteLocalRef(mFd);
    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
      return nullptr;
    }

    if (file_exists) {
      *file_exists = true;
    }

    return AssetsProvider::CreateAssetFromFd(base::unique_fd(fd),
                                             nullptr /* path */,
                                             static_cast<off64_t>(mOffset),
                                             static_cast<off64_t>(mLength));
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LoaderAssetsProvider);

  explicit LoaderAssetsProvider(JNIEnv* env, jobject assets_provider) {
    assets_provider_ = env->NewGlobalRef(assets_provider);
    auto string_result = static_cast<jstring>(env->CallObjectMethod(
        assets_provider_, gAssetsProviderOffsets.toString));
    ScopedUtfChars str(env, string_result);
    debug_name_ = std::string(str.c_str(), str.size());
  }

  // The global reference to the AssetsProvider
  jobject assets_provider_;
  std::string debug_name_;
};

static jlong NativeLoad(JNIEnv* env, jclass /*clazz*/, const format_type_t format,
                        jstring java_path, const jint property_flags, jobject assets_provider) {
  ScopedUtfChars path(env, java_path);
  if (path.c_str() == nullptr) {
    return 0;
  }

  ATRACE_NAME(base::StringPrintf("LoadApkAssets(%s)", path.c_str()).c_str());

  auto loader_assets = LoaderAssetsProvider::Create(env, assets_provider);
  std::unique_ptr<ApkAssets> apk_assets;
  switch (format) {
    case FORMAT_APK: {
      auto assets = MultiAssetsProvider::Create(std::move(loader_assets),
                                                ZipAssetsProvider::Create(path.c_str()));
      apk_assets = ApkAssets::Load(std::move(assets), property_flags);
      break;
    }
    case FORMAT_IDMAP:
      apk_assets = ApkAssets::LoadOverlay(path.c_str(), property_flags);
      break;
    case FORMAT_ARSC:
      apk_assets = ApkAssets::LoadTable(AssetsProvider::CreateAssetFromFile(path.c_str()),
                                        std::move(loader_assets),
                                        property_flags);
      break;
    case FORMAT_DIRECTORY: {
      auto assets = MultiAssetsProvider::Create(std::move(loader_assets),
                                                DirectoryAssetsProvider::Create(path.c_str()));
      apk_assets = ApkAssets::Load(std::move(assets), property_flags);
      break;
    }
    default:
      const std::string error_msg = base::StringPrintf("Unsupported format type %d", format);
      jniThrowException(env, "java/lang/IllegalArgumentException", error_msg.c_str());
      return 0;
  }

  if (apk_assets == nullptr) {
    const std::string error_msg = base::StringPrintf("Failed to load asset path %s", path.c_str());
    jniThrowException(env, "java/io/IOException", error_msg.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(apk_assets.release());
}

static jlong NativeLoadFromFd(JNIEnv* env, jclass /*clazz*/, const format_type_t format,
                              jobject file_descriptor, jstring friendly_name,
                              const jint property_flags, jobject assets_provider) {
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

  auto loader_assets = LoaderAssetsProvider::Create(env, assets_provider);
  std::unique_ptr<const ApkAssets> apk_assets;
  switch (format) {
    case FORMAT_APK: {
      auto assets = MultiAssetsProvider::Create(
          std::move(loader_assets),
          ZipAssetsProvider::Create(std::move(dup_fd), friendly_name_utf8.c_str()));
      apk_assets = ApkAssets::Load(std::move(assets), property_flags);
      break;
    }
    case FORMAT_ARSC:
      apk_assets = ApkAssets::LoadTable(
          AssetsProvider::CreateAssetFromFd(std::move(dup_fd), nullptr /* path */),
          std::move(loader_assets), property_flags);
      break;
    default:
      const std::string error_msg = base::StringPrintf("Unsupported format type %d", format);
      jniThrowException(env, "java/lang/IllegalArgumentException", error_msg.c_str());
      return 0;
  }

  if (apk_assets == nullptr) {
    std::string error_msg = base::StringPrintf("Failed to load asset path %s from fd %d",
                                               friendly_name_utf8.c_str(), fd);
    jniThrowException(env, "java/io/IOException", error_msg.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(apk_assets.release());
}

static jlong NativeLoadFromFdOffset(JNIEnv* env, jclass /*clazz*/, const format_type_t format,
                                    jobject file_descriptor, jstring friendly_name,
                                    const jlong offset, const jlong length,
                                    const jint property_flags, jobject assets_provider) {
  ScopedUtfChars friendly_name_utf8(env, friendly_name);
  if (friendly_name_utf8.c_str() == nullptr) {
    return 0;
  }

  ATRACE_NAME(base::StringPrintf("LoadApkAssetsFd(%s)", friendly_name_utf8.c_str()).c_str());

  if (offset < 0) {
    jniThrowException(env, "java/lang/IllegalArgumentException",
                     "offset cannot be negative");
    return 0;
  }

  if (length < 0) {
    jniThrowException(env, "java/lang/IllegalArgumentException",
                     "length cannot be negative");
    return 0;
  }

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

  auto loader_assets = LoaderAssetsProvider::Create(env, assets_provider);
  std::unique_ptr<const ApkAssets> apk_assets;
  switch (format) {
    case FORMAT_APK: {
      auto assets = MultiAssetsProvider::Create(
          std::move(loader_assets),
          ZipAssetsProvider::Create(std::move(dup_fd), friendly_name_utf8.c_str(),
                                    static_cast<off64_t>(offset), static_cast<off64_t>(length)));
      apk_assets = ApkAssets::Load(std::move(assets), property_flags);
      break;
    }
    case FORMAT_ARSC:
      apk_assets = ApkAssets::LoadTable(
          AssetsProvider::CreateAssetFromFd(std::move(dup_fd), nullptr /* path */,
                                            static_cast<off64_t>(offset),
                                            static_cast<off64_t>(length)),
          std::move(loader_assets), property_flags);
      break;
    default:
      const std::string error_msg = base::StringPrintf("Unsupported format type %d", format);
      jniThrowException(env, "java/lang/IllegalArgumentException", error_msg.c_str());
      return 0;
  }

  if (apk_assets == nullptr) {
    std::string error_msg = base::StringPrintf("Failed to load asset path %s from fd %d",
                                               friendly_name_utf8.c_str(), fd);
    jniThrowException(env, "java/io/IOException", error_msg.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(apk_assets.release());
}

static jlong NativeLoadEmpty(JNIEnv* env, jclass /*clazz*/, jint flags, jobject assets_provider) {
  auto apk_assets = ApkAssets::Load(LoaderAssetsProvider::Create(env, assets_provider), flags);
  return reinterpret_cast<jlong>(apk_assets.release());
}

static void NativeDestroy(void* ptr) {
  delete reinterpret_cast<ApkAssets*>(ptr);
}

static jlong NativeGetFinalizer(JNIEnv* /*env*/, jclass /*clazz*/) {
  return reinterpret_cast<jlong>(&NativeDestroy);
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
  std::unique_ptr<Asset> asset = apk_assets->GetAssetsProvider()->Open(
      path_utf8.c_str(),Asset::AccessMode::ACCESS_RANDOM);
  if (asset == nullptr) {
    jniThrowException(env, "java/io/FileNotFoundException", path_utf8.c_str());
    return 0;
  }

  const auto buffer = asset->getIncFsBuffer(true /* aligned */);
  const size_t length = asset->getLength();
  if (!buffer.convert<uint8_t>().verify(length)) {
    jniThrowException(env, kResourcesNotFound, kIOErrorMessage);
    return 0;
  }

  // DynamicRefTable is only needed when looking up resource references. Opening an XML file
  // directly from an ApkAssets has no notion of proper resource references.
  auto xml_tree = util::make_unique<ResXMLTree>(nullptr /*dynamicRefTable*/);
  status_t err = xml_tree->setTo(buffer.unsafe_ptr(), length, true);
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
    {"nativeLoad", "(ILjava/lang/String;ILandroid/content/res/loader/AssetsProvider;)J",
     (void*)NativeLoad},
    {"nativeLoadEmpty", "(ILandroid/content/res/loader/AssetsProvider;)J", (void*)NativeLoadEmpty},
    {"nativeLoadFd",
     "(ILjava/io/FileDescriptor;Ljava/lang/String;ILandroid/content/res/loader/AssetsProvider;)J",
     (void*)NativeLoadFromFd},
    {"nativeLoadFdOffsets",
     "(ILjava/io/FileDescriptor;Ljava/lang/String;JJILandroid/content/res/loader/AssetsProvider;)J",
     (void*)NativeLoadFromFdOffset},
    {"nativeGetFinalizer", "()J", (void*)NativeGetFinalizer},
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

  jclass assetFd = FindClassOrDie(env, "android/content/res/AssetFileDescriptor");
  gAssetFileDescriptorOffsets.mFd =
      GetFieldIDOrDie(env, assetFd, "mFd", "Landroid/os/ParcelFileDescriptor;");
  gAssetFileDescriptorOffsets.mStartOffset = GetFieldIDOrDie(env, assetFd, "mStartOffset", "J");
  gAssetFileDescriptorOffsets.mLength = GetFieldIDOrDie(env, assetFd, "mLength", "J");

  jclass assetsProvider = FindClassOrDie(env, "android/content/res/loader/AssetsProvider");
  gAssetsProviderOffsets.classObject = MakeGlobalRefOrDie(env, assetsProvider);
  gAssetsProviderOffsets.loadAssetFd = GetMethodIDOrDie(
      env, gAssetsProviderOffsets.classObject, "loadAssetFd",
      "(Ljava/lang/String;I)Landroid/content/res/AssetFileDescriptor;");
  gAssetsProviderOffsets.toString = GetMethodIDOrDie(
      env, gAssetsProviderOffsets.classObject, "toString", "()Ljava/lang/String;");

  jclass parcelFd = FindClassOrDie(env, "android/os/ParcelFileDescriptor");
  gParcelFileDescriptorOffsets.detachFd = GetMethodIDOrDie(env, parcelFd, "detachFd", "()I");

  return RegisterMethodsOrDie(env, "android/content/res/ApkAssets", gApkAssetsMethods,
                              arraysize(gApkAssetsMethods));
}

}  // namespace android
