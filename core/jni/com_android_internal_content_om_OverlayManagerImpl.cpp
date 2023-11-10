/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <dlfcn.h>

#include <optional>

#define LOG_TAG "OverlayManagerImpl"

#include "android-base/no_destructor.h"
#include "androidfw/ResourceTypes.h"
#include "core_jni_helpers.h"
#include "jni.h"

namespace android {

static struct fabricated_overlay_internal_offsets_t {
    jclass classObject;
    jfieldID packageName;
    jfieldID overlayName;
    jfieldID targetPackageName;
    jfieldID targetOverlayable;
    jfieldID entries;
} gFabricatedOverlayInternalOffsets;

static struct fabricated_overlay_internal_entry_offsets_t {
    jclass classObject;
    jfieldID resourceName;
    jfieldID dataType;
    jfieldID data;
    jfieldID stringData;
    jfieldID binaryData;
    jfieldID configuration;
    jfieldID binaryDataOffset;
    jfieldID binaryDataSize;
} gFabricatedOverlayInternalEntryOffsets;

static struct parcel_file_descriptor_offsets_t {
    jclass classObject;
    jmethodID getFd;
} gParcelFileDescriptorOffsets;

static struct List_offsets_t {
    jclass classObject;
    jmethodID size;
    jmethodID get;
} gListOffsets;

static struct fabricated_overlay_info_offsets_t {
    jclass classObject;
    jmethodID constructor;
    jfieldID packageName;
    jfieldID overlayName;
    jfieldID targetPackageName;
    jfieldID targetOverlayable;
    jfieldID path;
} gFabricatedOverlayInfoOffsets;

namespace self_targeting {

constexpr const char kIOException[] = "java/io/IOException";
constexpr const char IllegalArgumentException[] = "java/lang/IllegalArgumentException";

class DynamicLibraryLoader {
public:
    explicit DynamicLibraryLoader(JNIEnv* env) {
        /* For SelfTargeting, there are 2 types of files to be handled. One is frro and the other is
         * idmap. For creating frro/idmap files and reading frro files, it needs libandroid_runtime
         * to do a shared link to libidmap2. However, libidmap2 contains the codes generated from
         * google protocol buffer. When libandroid_runtime does a shared link to libidmap2, it will
         * impact the memory for system_server and zygote(a.k.a. all applications).
         *
         * Not all applications need to either create/read frro files or create idmap files all the
         * time. When the apps apply the SelfTargeting overlay effect, it only needs libandroifw
         * that is loaded. To use dlopen(libidmap2.so) is to make sure that applications don't
         * impact themselves' memory by loading libidmap2 until they need to create/read frro files
         * or create idmap files.
         */
        handle_ = dlopen("libidmap2.so", RTLD_NOW);
        if (handle_ == nullptr) {
            jniThrowNullPointerException(env);
            return;
        }

        createIdmapFileFuncPtr_ =
                reinterpret_cast<CreateIdmapFileFunc>(dlsym(handle_, "CreateIdmapFile"));
        if (createIdmapFileFuncPtr_ == nullptr) {
            jniThrowNullPointerException(env, "The symbol CreateIdmapFile is not found.");
            return;
        }
        getFabricatedOverlayInfoFuncPtr_ = reinterpret_cast<GetFabricatedOverlayInfoFunc>(
                dlsym(handle_, "GetFabricatedOverlayInfo"));
        if (getFabricatedOverlayInfoFuncPtr_ == nullptr) {
            jniThrowNullPointerException(env, "The symbol GetFabricatedOverlayInfo is not found.");
            return;
        }
        createFrroFile_ = reinterpret_cast<CreateFrroFileFunc>(dlsym(handle_, "CreateFrroFile"));
        if (createFrroFile_ == nullptr) {
            jniThrowNullPointerException(env, "The symbol CreateFrroFile is not found.");
            return;
        }
    }

    bool callCreateFrroFile(std::string& out_error, const std::string& packageName,
                            const std::string& overlayName, const std::string& targetPackageName,
                            const std::optional<std::string>& targetOverlayable,
                            const std::vector<FabricatedOverlayEntryParameters>& entries_params,
                            const std::string& frro_file_path) {
        return createFrroFile_(out_error, packageName, overlayName, targetPackageName,
                               targetOverlayable, entries_params, frro_file_path);
    }

    bool callCreateIdmapFile(std::string& out_error, const std::string& targetPath,
                             const std::string& overlayPath, const std::string& idmapPath,
                             const std::string& overlayName, const bool isSystem,
                             const bool isVendor, const bool isProduct,
                             const bool isTargetSignature, const bool isOdm, const bool isOem) {
        return createIdmapFileFuncPtr_(out_error, targetPath, overlayPath, idmapPath, overlayName,
                                       isSystem, isVendor, isProduct, isTargetSignature, isOdm,
                                       isOem);
    }

    bool callGetFabricatedOverlayInfo(std::string& out_error, const std::string& overlay_path,
                                      OverlayManifestInfo& out_overlay_manifest_info) {
        return getFabricatedOverlayInfoFuncPtr_(out_error, overlay_path, out_overlay_manifest_info);
    }

    explicit operator bool() const {
        return handle_ != nullptr && createFrroFile_ != nullptr &&
                createIdmapFileFuncPtr_ != nullptr && getFabricatedOverlayInfoFuncPtr_ != nullptr;
    }

    DynamicLibraryLoader(const DynamicLibraryLoader&) = delete;

    DynamicLibraryLoader& operator=(const DynamicLibraryLoader&) = delete;

    ~DynamicLibraryLoader() {
        if (handle_ != nullptr) {
            dlclose(handle_);
        }
    }

private:
    typedef bool (*CreateFrroFileFunc)(
            std::string& out_error, const std::string& packageName, const std::string& overlayName,
            const std::string& targetPackageName,
            const std::optional<std::string>& targetOverlayable,
            const std::vector<FabricatedOverlayEntryParameters>& entries_params,
            const std::string& frro_file_path);

    typedef bool (*CreateIdmapFileFunc)(std::string& out_error, const std::string& targetPath,
                                        const std::string& overlayPath,
                                        const std::string& idmapPath,
                                        const std::string& overlayName, const jboolean isSystem,
                                        const jboolean isVendor, const jboolean isProduct,
                                        const jboolean isSameWithTargetSignature,
                                        const jboolean isOdm, const jboolean isOem);

    typedef bool (*GetFabricatedOverlayInfoFunc)(std::string& out_error,
                                                 const std::string& overlay_path,
                                                 OverlayManifestInfo& out_overlay_manifest_info);

    void* handle_;
    CreateFrroFileFunc createFrroFile_;
    CreateIdmapFileFunc createIdmapFileFuncPtr_;
    GetFabricatedOverlayInfoFunc getFabricatedOverlayInfoFuncPtr_;
};

static DynamicLibraryLoader& EnsureDynamicLibraryLoader(JNIEnv* env) {
    static android::base::NoDestructor<DynamicLibraryLoader> loader(env);
    return *loader;
}

static std::optional<std::string> getNullableString(JNIEnv* env, jobject object, jfieldID field) {
    auto javaString = reinterpret_cast<jstring>(env->GetObjectField(object, field));
    if (javaString == nullptr) {
        return std::nullopt;
    }

    const ScopedUtfChars result(env, javaString);
    if (result.c_str() == nullptr) {
        return std::nullopt;
    }

    return std::optional<std::string>{result.c_str()};
}

static std::optional<android::base::borrowed_fd> getNullableFileDescriptor(JNIEnv* env,
                                                                           jobject object,
                                                                           jfieldID field) {
    auto binaryData = env->GetObjectField(object, field);
    if (binaryData == nullptr) {
        return std::nullopt;
    }

    return env->CallIntMethod(binaryData, gParcelFileDescriptorOffsets.getFd);
}

static void CreateFrroFile(JNIEnv* env, jclass /*clazz*/, jstring jsFrroFilePath, jobject overlay) {
    DynamicLibraryLoader& dlLoader = EnsureDynamicLibraryLoader(env);
    if (!dlLoader) {
        jniThrowNullPointerException(env, "libidmap2 is not loaded");
        return;
    }

    if (overlay == nullptr) {
        jniThrowNullPointerException(env, "overlay is null");
        return;
    }
    auto jsPackageName =
            (jstring)env->GetObjectField(overlay, gFabricatedOverlayInternalOffsets.packageName);
    const ScopedUtfChars packageName(env, jsPackageName);
    if (packageName.c_str() == nullptr) {
        jniThrowNullPointerException(env, "packageName is null");
        return;
    }
    auto jsOverlayName =
            (jstring)env->GetObjectField(overlay, gFabricatedOverlayInternalOffsets.overlayName);
    const ScopedUtfChars overlayName(env, jsOverlayName);
    if (overlayName.c_str() == nullptr) {
        jniThrowNullPointerException(env, "overlayName is null");
        return;
    }
    auto jsTargetPackageName =
            (jstring)env->GetObjectField(overlay,
                                         gFabricatedOverlayInternalOffsets.targetPackageName);
    const ScopedUtfChars targetPackageName(env, jsTargetPackageName);
    if (targetPackageName.c_str() == nullptr) {
        jniThrowNullPointerException(env, "targetPackageName is null");
        return;
    }
    auto overlayable =
            getNullableString(env, overlay, gFabricatedOverlayInternalOffsets.targetOverlayable);
    const ScopedUtfChars frroFilePath(env, jsFrroFilePath);
    if (frroFilePath.c_str() == nullptr) {
        jniThrowNullPointerException(env, "frroFilePath is null");
        return;
    }
    jobject entries = env->GetObjectField(overlay, gFabricatedOverlayInternalOffsets.entries);
    if (entries == nullptr) {
        jniThrowNullPointerException(env, "overlay entries is null");
        return;
    }
    const jint size = env->CallIntMethod(entries, gListOffsets.size);
    ALOGV("frroFilePath = %s, packageName = %s, overlayName = %s, targetPackageName = %s,"
          " targetOverlayable = %s, size = %d",
          frroFilePath.c_str(), packageName.c_str(), overlayName.c_str(), targetPackageName.c_str(),
          overlayable.value_or(std::string()).c_str(), size);

    std::vector<FabricatedOverlayEntryParameters> entries_params;
    for (jint i = 0; i < size; i++) {
        jobject entry = env->CallObjectMethod(entries, gListOffsets.get, i);
        auto jsResourceName = reinterpret_cast<jstring>(
                env->GetObjectField(entry, gFabricatedOverlayInternalEntryOffsets.resourceName));
        const ScopedUtfChars resourceName(env, jsResourceName);
        const jint dataType =
                env->GetIntField(entry, gFabricatedOverlayInternalEntryOffsets.dataType);

        // In Java, the data type is int but the maximum value of data Type is less than 0xff.
        if (dataType >= static_cast<jint>(UCHAR_MAX)) {
            jniThrowException(env, IllegalArgumentException, "Unsupported data type");
            return;
        }

        const auto data = env->GetIntField(entry, gFabricatedOverlayInternalEntryOffsets.data);
        auto configuration =
                getNullableString(env, entry, gFabricatedOverlayInternalEntryOffsets.configuration);
        auto string_data =
                getNullableString(env, entry, gFabricatedOverlayInternalEntryOffsets.stringData);
        auto binary_data =
                getNullableFileDescriptor(env, entry,
                                          gFabricatedOverlayInternalEntryOffsets.binaryData);

        const auto data_offset =
                env->GetLongField(entry, gFabricatedOverlayInternalEntryOffsets.binaryDataOffset);
        const auto data_size =
                env->GetLongField(entry, gFabricatedOverlayInternalEntryOffsets.binaryDataSize);
        entries_params.push_back(
                FabricatedOverlayEntryParameters{resourceName.c_str(), (DataType)dataType,
                                                 (DataValue)data,
                                                 string_data.value_or(std::string()), binary_data,
                                                 static_cast<off64_t>(data_offset),
                                                 static_cast<size_t>(data_size),
                                                 configuration.value_or(std::string())});
        ALOGV("resourceName = %s, dataType = 0x%08x, data = 0x%08x, dataString = %s,"
              " binaryData = %d, configuration = %s",
              resourceName.c_str(), dataType, data, string_data.value_or(std::string()).c_str(),
              binary_data.has_value(), configuration.value_or(std::string()).c_str());
    }

    std::string err_result;
    if (!dlLoader.callCreateFrroFile(err_result, packageName.c_str(), overlayName.c_str(),
                                     targetPackageName.c_str(), overlayable, entries_params,
                                     frroFilePath.c_str())) {
        jniThrowException(env, IllegalArgumentException, err_result.c_str());
        return;
    }
}

static void CreateIdmapFile(JNIEnv* env, jclass /* clazz */, jstring jsTargetPath,
                            jstring jsOverlayPath, jstring jsIdmapPath, jstring jsOverlayName,
                            jboolean isSystem, jboolean isVendor, jboolean isProduct,
                            jboolean isTargetSignature, jboolean isOdm, jboolean isOem) {
    DynamicLibraryLoader& dlLoader = EnsureDynamicLibraryLoader(env);
    if (!dlLoader) {
        jniThrowNullPointerException(env, "libidmap2 is not loaded");
        return;
    }

    const ScopedUtfChars targetPath(env, jsTargetPath);
    if (targetPath.c_str() == nullptr) {
        jniThrowNullPointerException(env, "targetPath is null");
        return;
    }
    const ScopedUtfChars overlayPath(env, jsOverlayPath);
    if (overlayPath.c_str() == nullptr) {
        jniThrowNullPointerException(env, "overlayPath is null");
        return;
    }
    const ScopedUtfChars idmapPath(env, jsIdmapPath);
    if (idmapPath.c_str() == nullptr) {
        jniThrowNullPointerException(env, "idmapPath is null");
        return;
    }
    const ScopedUtfChars overlayName(env, jsOverlayName);
    if (overlayName.c_str() == nullptr) {
        jniThrowNullPointerException(env, "overlayName is null");
        return;
    }
    ALOGV("target_path = %s, overlay_path = %s, idmap_path = %s, overlay_name = %s",
          targetPath.c_str(), overlayPath.c_str(), idmapPath.c_str(), overlayName.c_str());

    std::string err_result;
    if (!dlLoader.callCreateIdmapFile(err_result, targetPath.c_str(), overlayPath.c_str(),
                                      idmapPath.c_str(), overlayName.c_str(),
                                      (isSystem == JNI_TRUE), (isVendor == JNI_TRUE),
                                      (isProduct == JNI_TRUE), (isTargetSignature == JNI_TRUE),
                                      (isOdm == JNI_TRUE), (isOem == JNI_TRUE))) {
        jniThrowException(env, kIOException, err_result.c_str());
        return;
    }
}

static jobject GetFabricatedOverlayInfo(JNIEnv* env, jclass /* clazz */, jstring jsOverlayPath) {
    const ScopedUtfChars overlay_path(env, jsOverlayPath);
    if (overlay_path.c_str() == nullptr) {
        jniThrowNullPointerException(env, "overlay_path is null");
        return nullptr;
    }
    ALOGV("overlay_path = %s", overlay_path.c_str());

    DynamicLibraryLoader& dlLoader = EnsureDynamicLibraryLoader(env);
    if (!dlLoader) {
        return nullptr;
    }

    std::string err_result;
    OverlayManifestInfo overlay_manifest_info;
    if (!dlLoader.callGetFabricatedOverlayInfo(err_result, overlay_path.c_str(),
                                               overlay_manifest_info) != 0) {
        jniThrowException(env, kIOException, err_result.c_str());
        return nullptr;
    }
    jobject info = env->NewObject(gFabricatedOverlayInfoOffsets.classObject,
                                  gFabricatedOverlayInfoOffsets.constructor);
    jstring jsOverName = env->NewStringUTF(overlay_manifest_info.name.c_str());
    jstring jsPackageName = env->NewStringUTF(overlay_manifest_info.package_name.c_str());
    jstring jsTargetPackage = env->NewStringUTF(overlay_manifest_info.target_package.c_str());
    jstring jsTargetOverlayable = env->NewStringUTF(overlay_manifest_info.target_name.c_str());
    env->SetObjectField(info, gFabricatedOverlayInfoOffsets.overlayName, jsOverName);
    env->SetObjectField(info, gFabricatedOverlayInfoOffsets.packageName, jsPackageName);
    env->SetObjectField(info, gFabricatedOverlayInfoOffsets.targetPackageName, jsTargetPackage);
    env->SetObjectField(info, gFabricatedOverlayInfoOffsets.targetOverlayable, jsTargetOverlayable);
    env->SetObjectField(info, gFabricatedOverlayInfoOffsets.path, jsOverlayPath);
    return info;
}

} // namespace self_targeting

// JNI registration.
static const JNINativeMethod gOverlayManagerMethods[] = {
        {"createFrroFile", "(Ljava/lang/String;Landroid/os/FabricatedOverlayInternal;)V",
         reinterpret_cast<void*>(self_targeting::CreateFrroFile)},
        {"createIdmapFile",
         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZZZZ)V",
         reinterpret_cast<void*>(self_targeting::CreateIdmapFile)},
        {"getFabricatedOverlayInfo", "(Ljava/lang/String;)Landroid/os/FabricatedOverlayInfo;",
         reinterpret_cast<void*>(self_targeting::GetFabricatedOverlayInfo)},
};

int register_com_android_internal_content_om_OverlayManagerImpl(JNIEnv* env) {
    jclass ListClass = FindClassOrDie(env, "java/util/List");
    gListOffsets.classObject = MakeGlobalRefOrDie(env, ListClass);
    gListOffsets.size = GetMethodIDOrDie(env, gListOffsets.classObject, "size", "()I");
    gListOffsets.get =
            GetMethodIDOrDie(env, gListOffsets.classObject, "get", "(I)Ljava/lang/Object;");

    jclass fabricatedOverlayInternalClass =
            FindClassOrDie(env, "android/os/FabricatedOverlayInternal");
    gFabricatedOverlayInternalOffsets.classObject =
            MakeGlobalRefOrDie(env, fabricatedOverlayInternalClass);
    gFabricatedOverlayInternalOffsets.packageName =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalOffsets.classObject, "packageName",
                            "Ljava/lang/String;");
    gFabricatedOverlayInternalOffsets.overlayName =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalOffsets.classObject, "overlayName",
                            "Ljava/lang/String;");
    gFabricatedOverlayInternalOffsets.targetPackageName =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalOffsets.classObject, "targetPackageName",
                            "Ljava/lang/String;");
    gFabricatedOverlayInternalOffsets.targetOverlayable =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalOffsets.classObject, "targetOverlayable",
                            "Ljava/lang/String;");
    gFabricatedOverlayInternalOffsets.entries =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalOffsets.classObject, "entries",
                            "Ljava/util/List;");

    jclass fabricatedOverlayInternalEntryClass =
            FindClassOrDie(env, "android/os/FabricatedOverlayInternalEntry");
    gFabricatedOverlayInternalEntryOffsets.classObject =
            MakeGlobalRefOrDie(env, fabricatedOverlayInternalEntryClass);
    gFabricatedOverlayInternalEntryOffsets.resourceName =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalEntryOffsets.classObject, "resourceName",
                            "Ljava/lang/String;");
    gFabricatedOverlayInternalEntryOffsets.dataType =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalEntryOffsets.classObject, "dataType",
                            "I");
    gFabricatedOverlayInternalEntryOffsets.data =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalEntryOffsets.classObject, "data", "I");
    gFabricatedOverlayInternalEntryOffsets.stringData =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalEntryOffsets.classObject, "stringData",
                            "Ljava/lang/String;");
    gFabricatedOverlayInternalEntryOffsets.binaryData =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalEntryOffsets.classObject, "binaryData",
                            "Landroid/os/ParcelFileDescriptor;");
    gFabricatedOverlayInternalEntryOffsets.configuration =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalEntryOffsets.classObject,
                            "configuration", "Ljava/lang/String;");
    gFabricatedOverlayInternalEntryOffsets.binaryDataOffset =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalEntryOffsets.classObject,
                            "binaryDataOffset", "J");
    gFabricatedOverlayInternalEntryOffsets.binaryDataSize =
            GetFieldIDOrDie(env, gFabricatedOverlayInternalEntryOffsets.classObject,
                            "binaryDataSize", "J");

    jclass parcelFileDescriptorClass =
            android::FindClassOrDie(env, "android/os/ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.classObject = MakeGlobalRefOrDie(env, parcelFileDescriptorClass);
    gParcelFileDescriptorOffsets.getFd =
            GetMethodIDOrDie(env, gParcelFileDescriptorOffsets.classObject, "getFd", "()I");

    jclass fabricatedOverlayInfoClass = FindClassOrDie(env, "android/os/FabricatedOverlayInfo");
    gFabricatedOverlayInfoOffsets.classObject = MakeGlobalRefOrDie(env, fabricatedOverlayInfoClass);
    gFabricatedOverlayInfoOffsets.constructor =
            GetMethodIDOrDie(env, gFabricatedOverlayInfoOffsets.classObject, "<init>", "()V");
    gFabricatedOverlayInfoOffsets.packageName =
            GetFieldIDOrDie(env, gFabricatedOverlayInfoOffsets.classObject, "packageName",
                            "Ljava/lang/String;");
    gFabricatedOverlayInfoOffsets.overlayName =
            GetFieldIDOrDie(env, gFabricatedOverlayInfoOffsets.classObject, "overlayName",
                            "Ljava/lang/String;");
    gFabricatedOverlayInfoOffsets.targetPackageName =
            GetFieldIDOrDie(env, gFabricatedOverlayInfoOffsets.classObject, "targetPackageName",
                            "Ljava/lang/String;");
    gFabricatedOverlayInfoOffsets.targetOverlayable =
            GetFieldIDOrDie(env, gFabricatedOverlayInfoOffsets.classObject, "targetOverlayable",
                            "Ljava/lang/String;");
    gFabricatedOverlayInfoOffsets.path =
            GetFieldIDOrDie(env, gFabricatedOverlayInfoOffsets.classObject, "path",
                            "Ljava/lang/String;");

    return RegisterMethodsOrDie(env, "com/android/internal/content/om/OverlayManagerImpl",
                                gOverlayManagerMethods, NELEM(gOverlayManagerMethods));
}

} // namespace android
