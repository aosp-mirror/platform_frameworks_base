/* //device/libs/android_runtime/android_util_AssetManager.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "asset"

#define DEBUG_STYLES(x) //x
#define THROW_ON_BAD_ID 0

#include <android_runtime/android_util_AssetManager.h>

#include "jni.h"
#include "JNIHelp.h"
#include "ScopedStringChars.h"
#include "ScopedUtfChars.h"
#include "android_util_Binder.h"
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>

#include <androidfw/Asset.h>
#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>

#include <private/android_filesystem_config.h> // for AID_SYSTEM

#include <stdio.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <linux/capability.h>
extern "C" int capget(cap_user_header_t hdrp, cap_user_data_t datap);
extern "C" int capset(cap_user_header_t hdrp, const cap_user_data_t datap);


namespace android {

// ----------------------------------------------------------------------------

static struct typedvalue_offsets_t
{
    jfieldID mType;
    jfieldID mData;
    jfieldID mString;
    jfieldID mAssetCookie;
    jfieldID mResourceId;
    jfieldID mChangingConfigurations;
    jfieldID mDensity;
} gTypedValueOffsets;

static struct assetfiledescriptor_offsets_t
{
    jfieldID mFd;
    jfieldID mStartOffset;
    jfieldID mLength;
} gAssetFileDescriptorOffsets;

static struct assetmanager_offsets_t
{
    jfieldID mObject;
} gAssetManagerOffsets;

static struct sparsearray_offsets_t
{
    jclass classObject;
    jmethodID constructor;
    jmethodID put;
} gSparseArrayOffsets;

jclass g_stringClass = NULL;

// ----------------------------------------------------------------------------

enum {
    STYLE_NUM_ENTRIES = 6,
    STYLE_TYPE = 0,
    STYLE_DATA = 1,
    STYLE_ASSET_COOKIE = 2,
    STYLE_RESOURCE_ID = 3,
    STYLE_CHANGING_CONFIGURATIONS = 4,
    STYLE_DENSITY = 5
};

static jint copyValue(JNIEnv* env, jobject outValue, const ResTable* table,
                      const Res_value& value, uint32_t ref, ssize_t block,
                      uint32_t typeSpecFlags, ResTable_config* config = NULL);

jint copyValue(JNIEnv* env, jobject outValue, const ResTable* table,
               const Res_value& value, uint32_t ref, ssize_t block,
               uint32_t typeSpecFlags, ResTable_config* config)
{
    env->SetIntField(outValue, gTypedValueOffsets.mType, value.dataType);
    env->SetIntField(outValue, gTypedValueOffsets.mAssetCookie,
                     static_cast<jint>(table->getTableCookie(block)));
    env->SetIntField(outValue, gTypedValueOffsets.mData, value.data);
    env->SetObjectField(outValue, gTypedValueOffsets.mString, NULL);
    env->SetIntField(outValue, gTypedValueOffsets.mResourceId, ref);
    env->SetIntField(outValue, gTypedValueOffsets.mChangingConfigurations,
            typeSpecFlags);
    if (config != NULL) {
        env->SetIntField(outValue, gTypedValueOffsets.mDensity, config->density);
    }
    return block;
}

// This is called by zygote (running as user root) as part of preloadResources.
static void verifySystemIdmaps()
{
    pid_t pid;
    char system_id[10];

    snprintf(system_id, sizeof(system_id), "%d", AID_SYSTEM);

    switch (pid = fork()) {
        case -1:
            ALOGE("failed to fork for idmap: %s", strerror(errno));
            break;
        case 0: // child
            {
                struct __user_cap_header_struct capheader;
                struct __user_cap_data_struct capdata;

                memset(&capheader, 0, sizeof(capheader));
                memset(&capdata, 0, sizeof(capdata));

                capheader.version = _LINUX_CAPABILITY_VERSION;
                capheader.pid = 0;

                if (capget(&capheader, &capdata) != 0) {
                    ALOGE("capget: %s\n", strerror(errno));
                    exit(1);
                }

                capdata.effective = capdata.permitted;
                if (capset(&capheader, &capdata) != 0) {
                    ALOGE("capset: %s\n", strerror(errno));
                    exit(1);
                }

                if (setgid(AID_SYSTEM) != 0) {
                    ALOGE("setgid: %s\n", strerror(errno));
                    exit(1);
                }

                if (setuid(AID_SYSTEM) != 0) {
                    ALOGE("setuid: %s\n", strerror(errno));
                    exit(1);
                }

                execl(AssetManager::IDMAP_BIN, AssetManager::IDMAP_BIN, "--scan",
                        AssetManager::OVERLAY_DIR, AssetManager::TARGET_PACKAGE_NAME,
                        AssetManager::TARGET_APK_PATH, AssetManager::IDMAP_DIR, (char*)NULL);
                ALOGE("failed to execl for idmap: %s", strerror(errno));
                exit(1); // should never get here
            }
            break;
        default: // parent
            waitpid(pid, NULL, 0);
            break;
    }
}

// ----------------------------------------------------------------------------

// this guy is exported to other jni routines
AssetManager* assetManagerForJavaObject(JNIEnv* env, jobject obj)
{
    jlong amHandle = env->GetLongField(obj, gAssetManagerOffsets.mObject);
    AssetManager* am = reinterpret_cast<AssetManager*>(amHandle);
    if (am != NULL) {
        return am;
    }
    jniThrowException(env, "java/lang/IllegalStateException", "AssetManager has been finalized!");
    return NULL;
}

static jlong android_content_AssetManager_openAsset(JNIEnv* env, jobject clazz,
                                                jstring fileName, jint mode)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }

    ALOGV("openAsset in %p (Java object %p)\n", am, clazz);

    ScopedUtfChars fileName8(env, fileName);
    if (fileName8.c_str() == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Empty file name");
        return -1;
    }

    if (mode != Asset::ACCESS_UNKNOWN && mode != Asset::ACCESS_RANDOM
        && mode != Asset::ACCESS_STREAMING && mode != Asset::ACCESS_BUFFER) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Bad access mode");
        return -1;
    }

    Asset* a = am->open(fileName8.c_str(), (Asset::AccessMode)mode);

    if (a == NULL) {
        jniThrowException(env, "java/io/FileNotFoundException", fileName8.c_str());
        return -1;
    }

    //printf("Created Asset Stream: %p\n", a);

    return reinterpret_cast<jlong>(a);
}

static jobject returnParcelFileDescriptor(JNIEnv* env, Asset* a, jlongArray outOffsets)
{
    off64_t startOffset, length;
    int fd = a->openFileDescriptor(&startOffset, &length);
    delete a;

    if (fd < 0) {
        jniThrowException(env, "java/io/FileNotFoundException",
                "This file can not be opened as a file descriptor; it is probably compressed");
        return NULL;
    }

    jlong* offsets = (jlong*)env->GetPrimitiveArrayCritical(outOffsets, 0);
    if (offsets == NULL) {
        close(fd);
        return NULL;
    }

    offsets[0] = startOffset;
    offsets[1] = length;

    env->ReleasePrimitiveArrayCritical(outOffsets, offsets, 0);

    jobject fileDesc = jniCreateFileDescriptor(env, fd);
    if (fileDesc == NULL) {
        close(fd);
        return NULL;
    }

    return newParcelFileDescriptor(env, fileDesc);
}

static jobject android_content_AssetManager_openAssetFd(JNIEnv* env, jobject clazz,
                                                jstring fileName, jlongArray outOffsets)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }

    ALOGV("openAssetFd in %p (Java object %p)\n", am, clazz);

    ScopedUtfChars fileName8(env, fileName);
    if (fileName8.c_str() == NULL) {
        return NULL;
    }

    Asset* a = am->open(fileName8.c_str(), Asset::ACCESS_RANDOM);

    if (a == NULL) {
        jniThrowException(env, "java/io/FileNotFoundException", fileName8.c_str());
        return NULL;
    }

    //printf("Created Asset Stream: %p\n", a);

    return returnParcelFileDescriptor(env, a, outOffsets);
}

static jlong android_content_AssetManager_openNonAssetNative(JNIEnv* env, jobject clazz,
                                                         jint cookie,
                                                         jstring fileName,
                                                         jint mode)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }

    ALOGV("openNonAssetNative in %p (Java object %p)\n", am, clazz);

    ScopedUtfChars fileName8(env, fileName);
    if (fileName8.c_str() == NULL) {
        return -1;
    }

    if (mode != Asset::ACCESS_UNKNOWN && mode != Asset::ACCESS_RANDOM
        && mode != Asset::ACCESS_STREAMING && mode != Asset::ACCESS_BUFFER) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Bad access mode");
        return -1;
    }

    Asset* a = cookie
        ? am->openNonAsset(static_cast<int32_t>(cookie), fileName8.c_str(),
                (Asset::AccessMode)mode)
        : am->openNonAsset(fileName8.c_str(), (Asset::AccessMode)mode);

    if (a == NULL) {
        jniThrowException(env, "java/io/FileNotFoundException", fileName8.c_str());
        return -1;
    }

    //printf("Created Asset Stream: %p\n", a);

    return reinterpret_cast<jlong>(a);
}

static jobject android_content_AssetManager_openNonAssetFdNative(JNIEnv* env, jobject clazz,
                                                         jint cookie,
                                                         jstring fileName,
                                                         jlongArray outOffsets)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }

    ALOGV("openNonAssetFd in %p (Java object %p)\n", am, clazz);

    ScopedUtfChars fileName8(env, fileName);
    if (fileName8.c_str() == NULL) {
        return NULL;
    }

    Asset* a = cookie
        ? am->openNonAsset(static_cast<int32_t>(cookie), fileName8.c_str(), Asset::ACCESS_RANDOM)
        : am->openNonAsset(fileName8.c_str(), Asset::ACCESS_RANDOM);

    if (a == NULL) {
        jniThrowException(env, "java/io/FileNotFoundException", fileName8.c_str());
        return NULL;
    }

    //printf("Created Asset Stream: %p\n", a);

    return returnParcelFileDescriptor(env, a, outOffsets);
}

static jobjectArray android_content_AssetManager_list(JNIEnv* env, jobject clazz,
                                                   jstring fileName)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }

    ScopedUtfChars fileName8(env, fileName);
    if (fileName8.c_str() == NULL) {
        return NULL;
    }

    AssetDir* dir = am->openDir(fileName8.c_str());

    if (dir == NULL) {
        jniThrowException(env, "java/io/FileNotFoundException", fileName8.c_str());
        return NULL;
    }

    size_t N = dir->getFileCount();

    jobjectArray array = env->NewObjectArray(dir->getFileCount(),
                                                g_stringClass, NULL);
    if (array == NULL) {
        delete dir;
        return NULL;
    }

    for (size_t i=0; i<N; i++) {
        const String8& name = dir->getFileName(i);
        jstring str = env->NewStringUTF(name.string());
        if (str == NULL) {
            delete dir;
            return NULL;
        }
        env->SetObjectArrayElement(array, i, str);
        env->DeleteLocalRef(str);
    }

    delete dir;

    return array;
}

static void android_content_AssetManager_destroyAsset(JNIEnv* env, jobject clazz,
                                                      jlong assetHandle)
{
    Asset* a = reinterpret_cast<Asset*>(assetHandle);

    //printf("Destroying Asset Stream: %p\n", a);

    if (a == NULL) {
        jniThrowNullPointerException(env, "asset");
        return;
    }

    delete a;
}

static jint android_content_AssetManager_readAssetChar(JNIEnv* env, jobject clazz,
                                                       jlong assetHandle)
{
    Asset* a = reinterpret_cast<Asset*>(assetHandle);

    if (a == NULL) {
        jniThrowNullPointerException(env, "asset");
        return -1;
    }

    uint8_t b;
    ssize_t res = a->read(&b, 1);
    return res == 1 ? b : -1;
}

static jint android_content_AssetManager_readAsset(JNIEnv* env, jobject clazz,
                                                jlong assetHandle, jbyteArray bArray,
                                                jint off, jint len)
{
    Asset* a = reinterpret_cast<Asset*>(assetHandle);

    if (a == NULL || bArray == NULL) {
        jniThrowNullPointerException(env, "asset");
        return -1;
    }

    if (len == 0) {
        return 0;
    }

    jsize bLen = env->GetArrayLength(bArray);
    if (off < 0 || off >= bLen || len < 0 || len > bLen || (off+len) > bLen) {
        jniThrowException(env, "java/lang/IndexOutOfBoundsException", "");
        return -1;
    }

    jbyte* b = env->GetByteArrayElements(bArray, NULL);
    ssize_t res = a->read(b+off, len);
    env->ReleaseByteArrayElements(bArray, b, 0);

    if (res > 0) return static_cast<jint>(res);

    if (res < 0) {
        jniThrowException(env, "java/io/IOException", "");
    }
    return -1;
}

static jlong android_content_AssetManager_seekAsset(JNIEnv* env, jobject clazz,
                                                 jlong assetHandle,
                                                 jlong offset, jint whence)
{
    Asset* a = reinterpret_cast<Asset*>(assetHandle);

    if (a == NULL) {
        jniThrowNullPointerException(env, "asset");
        return -1;
    }

    return a->seek(
        offset, (whence > 0) ? SEEK_END : (whence < 0 ? SEEK_SET : SEEK_CUR));
}

static jlong android_content_AssetManager_getAssetLength(JNIEnv* env, jobject clazz,
                                                      jlong assetHandle)
{
    Asset* a = reinterpret_cast<Asset*>(assetHandle);

    if (a == NULL) {
        jniThrowNullPointerException(env, "asset");
        return -1;
    }

    return a->getLength();
}

static jlong android_content_AssetManager_getAssetRemainingLength(JNIEnv* env, jobject clazz,
                                                               jlong assetHandle)
{
    Asset* a = reinterpret_cast<Asset*>(assetHandle);

    if (a == NULL) {
        jniThrowNullPointerException(env, "asset");
        return -1;
    }

    return a->getRemainingLength();
}

static jint android_content_AssetManager_addAssetPath(JNIEnv* env, jobject clazz,
                                                       jstring path)
{
    ScopedUtfChars path8(env, path);
    if (path8.c_str() == NULL) {
        return 0;
    }

    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }

    int32_t cookie;
    bool res = am->addAssetPath(String8(path8.c_str()), &cookie);

    return (res) ? static_cast<jint>(cookie) : 0;
}

static jint android_content_AssetManager_addOverlayPath(JNIEnv* env, jobject clazz,
                                                     jstring idmapPath)
{
    ScopedUtfChars idmapPath8(env, idmapPath);
    if (idmapPath8.c_str() == NULL) {
        return 0;
    }

    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }

    int32_t cookie;
    bool res = am->addOverlayPath(String8(idmapPath8.c_str()), &cookie);

    return (res) ? (jint)cookie : 0;
}

static jboolean android_content_AssetManager_isUpToDate(JNIEnv* env, jobject clazz)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return JNI_TRUE;
    }
    return am->isUpToDate() ? JNI_TRUE : JNI_FALSE;
}

static void android_content_AssetManager_setLocale(JNIEnv* env, jobject clazz,
                                                jstring locale)
{
    ScopedUtfChars locale8(env, locale);
    if (locale8.c_str() == NULL) {
        return;
    }

    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return;
    }

    am->setLocale(locale8.c_str());
}

static jobjectArray android_content_AssetManager_getLocales(JNIEnv* env, jobject clazz)
{
    Vector<String8> locales;

    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }

    am->getLocales(&locales);

    const int N = locales.size();

    jobjectArray result = env->NewObjectArray(N, g_stringClass, NULL);
    if (result == NULL) {
        return NULL;
    }

    for (int i=0; i<N; i++) {
        jstring str = env->NewStringUTF(locales[i].string());
        if (str == NULL) {
            return NULL;
        }
        env->SetObjectArrayElement(result, i, str);
        env->DeleteLocalRef(str);
    }

    return result;
}

static void android_content_AssetManager_setConfiguration(JNIEnv* env, jobject clazz,
                                                          jint mcc, jint mnc,
                                                          jstring locale, jint orientation,
                                                          jint touchscreen, jint density,
                                                          jint keyboard, jint keyboardHidden,
                                                          jint navigation,
                                                          jint screenWidth, jint screenHeight,
                                                          jint smallestScreenWidthDp,
                                                          jint screenWidthDp, jint screenHeightDp,
                                                          jint screenLayout, jint uiMode,
                                                          jint sdkVersion)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return;
    }

    ResTable_config config;
    memset(&config, 0, sizeof(config));

    const char* locale8 = locale != NULL ? env->GetStringUTFChars(locale, NULL) : NULL;

    config.mcc = (uint16_t)mcc;
    config.mnc = (uint16_t)mnc;
    config.orientation = (uint8_t)orientation;
    config.touchscreen = (uint8_t)touchscreen;
    config.density = (uint16_t)density;
    config.keyboard = (uint8_t)keyboard;
    config.inputFlags = (uint8_t)keyboardHidden;
    config.navigation = (uint8_t)navigation;
    config.screenWidth = (uint16_t)screenWidth;
    config.screenHeight = (uint16_t)screenHeight;
    config.smallestScreenWidthDp = (uint16_t)smallestScreenWidthDp;
    config.screenWidthDp = (uint16_t)screenWidthDp;
    config.screenHeightDp = (uint16_t)screenHeightDp;
    config.screenLayout = (uint8_t)screenLayout;
    config.uiMode = (uint8_t)uiMode;
    config.sdkVersion = (uint16_t)sdkVersion;
    config.minorVersion = 0;
    am->setConfiguration(config, locale8);

    if (locale != NULL) env->ReleaseStringUTFChars(locale, locale8);
}

static jint android_content_AssetManager_getResourceIdentifier(JNIEnv* env, jobject clazz,
                                                            jstring name,
                                                            jstring defType,
                                                            jstring defPackage)
{
    ScopedStringChars name16(env, name);
    if (name16.get() == NULL) {
        return 0;
    }

    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }

    const char16_t* defType16 = defType
        ? env->GetStringChars(defType, NULL) : NULL;
    jsize defTypeLen = defType
        ? env->GetStringLength(defType) : 0;
    const char16_t* defPackage16 = defPackage
        ? env->GetStringChars(defPackage, NULL) : NULL;
    jsize defPackageLen = defPackage
        ? env->GetStringLength(defPackage) : 0;

    jint ident = am->getResources().identifierForName(
        name16.get(), name16.size(), defType16, defTypeLen, defPackage16, defPackageLen);

    if (defPackage16) {
        env->ReleaseStringChars(defPackage, defPackage16);
    }
    if (defType16) {
        env->ReleaseStringChars(defType, defType16);
    }

    return ident;
}

static jstring android_content_AssetManager_getResourceName(JNIEnv* env, jobject clazz,
                                                            jint resid)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }

    ResTable::resource_name name;
    if (!am->getResources().getResourceName(resid, true, &name)) {
        return NULL;
    }

    String16 str;
    if (name.package != NULL) {
        str.setTo(name.package, name.packageLen);
    }
    if (name.type8 != NULL || name.type != NULL) {
        if (str.size() > 0) {
            char16_t div = ':';
            str.append(&div, 1);
        }
        if (name.type8 != NULL) {
            str.append(String16(name.type8, name.typeLen));
        } else {
            str.append(name.type, name.typeLen);
        }
    }
    if (name.name8 != NULL || name.name != NULL) {
        if (str.size() > 0) {
            char16_t div = '/';
            str.append(&div, 1);
        }
        if (name.name8 != NULL) {
            str.append(String16(name.name8, name.nameLen));
        } else {
            str.append(name.name, name.nameLen);
        }
    }

    return env->NewString((const jchar*)str.string(), str.size());
}

static jstring android_content_AssetManager_getResourcePackageName(JNIEnv* env, jobject clazz,
                                                                   jint resid)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }

    ResTable::resource_name name;
    if (!am->getResources().getResourceName(resid, true, &name)) {
        return NULL;
    }

    if (name.package != NULL) {
        return env->NewString((const jchar*)name.package, name.packageLen);
    }

    return NULL;
}

static jstring android_content_AssetManager_getResourceTypeName(JNIEnv* env, jobject clazz,
                                                                jint resid)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }

    ResTable::resource_name name;
    if (!am->getResources().getResourceName(resid, true, &name)) {
        return NULL;
    }

    if (name.type8 != NULL) {
        return env->NewStringUTF(name.type8);
    }

    if (name.type != NULL) {
        return env->NewString((const jchar*)name.type, name.typeLen);
    }

    return NULL;
}

static jstring android_content_AssetManager_getResourceEntryName(JNIEnv* env, jobject clazz,
                                                                 jint resid)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }

    ResTable::resource_name name;
    if (!am->getResources().getResourceName(resid, true, &name)) {
        return NULL;
    }

    if (name.name8 != NULL) {
        return env->NewStringUTF(name.name8);
    }

    if (name.name != NULL) {
        return env->NewString((const jchar*)name.name, name.nameLen);
    }

    return NULL;
}

static jint android_content_AssetManager_loadResourceValue(JNIEnv* env, jobject clazz,
                                                           jint ident,
                                                           jshort density,
                                                           jobject outValue,
                                                           jboolean resolve)
{
    if (outValue == NULL) {
         jniThrowNullPointerException(env, "outValue");
         return 0;
    }
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }
    const ResTable& res(am->getResources());

    Res_value value;
    ResTable_config config;
    uint32_t typeSpecFlags;
    ssize_t block = res.getResource(ident, &value, false, density, &typeSpecFlags, &config);
#if THROW_ON_BAD_ID
    if (block == BAD_INDEX) {
        jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
        return 0;
    }
#endif
    uint32_t ref = ident;
    if (resolve) {
        block = res.resolveReference(&value, block, &ref, &typeSpecFlags, &config);
#if THROW_ON_BAD_ID
        if (block == BAD_INDEX) {
            jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
            return 0;
        }
#endif
    }
    if (block >= 0) {
        return copyValue(env, outValue, &res, value, ref, block, typeSpecFlags, &config);
    }

    return static_cast<jint>(block);
}

static jint android_content_AssetManager_loadResourceBagValue(JNIEnv* env, jobject clazz,
                                                           jint ident, jint bagEntryId,
                                                           jobject outValue, jboolean resolve)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }
    const ResTable& res(am->getResources());

    // Now lock down the resource object and start pulling stuff from it.
    res.lock();

    ssize_t block = -1;
    Res_value value;

    const ResTable::bag_entry* entry = NULL;
    uint32_t typeSpecFlags;
    ssize_t entryCount = res.getBagLocked(ident, &entry, &typeSpecFlags);

    for (ssize_t i=0; i<entryCount; i++) {
        if (((uint32_t)bagEntryId) == entry->map.name.ident) {
            block = entry->stringBlock;
            value = entry->map.value;
        }
        entry++;
    }

    res.unlock();

    if (block < 0) {
        return static_cast<jint>(block);
    }

    uint32_t ref = ident;
    if (resolve) {
        block = res.resolveReference(&value, block, &ref, &typeSpecFlags);
#if THROW_ON_BAD_ID
        if (block == BAD_INDEX) {
            jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
            return 0;
        }
#endif
    }
    if (block >= 0) {
        return copyValue(env, outValue, &res, value, ref, block, typeSpecFlags);
    }

    return static_cast<jint>(block);
}

static jint android_content_AssetManager_getStringBlockCount(JNIEnv* env, jobject clazz)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }
    return am->getResources().getTableCount();
}

static jlong android_content_AssetManager_getNativeStringBlock(JNIEnv* env, jobject clazz,
                                                           jint block)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }
    return reinterpret_cast<jlong>(am->getResources().getTableStringBlock(block));
}

static jstring android_content_AssetManager_getCookieName(JNIEnv* env, jobject clazz,
                                                       jint cookie)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }
    String8 name(am->getAssetPath(static_cast<int32_t>(cookie)));
    if (name.length() == 0) {
        jniThrowException(env, "java/lang/IndexOutOfBoundsException", "Empty cookie name");
        return NULL;
    }
    jstring str = env->NewStringUTF(name.string());
    return str;
}

static jobject android_content_AssetManager_getAssignedPackageIdentifiers(JNIEnv* env, jobject clazz)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }

    const ResTable& res = am->getResources();

    jobject sparseArray = env->NewObject(gSparseArrayOffsets.classObject,
            gSparseArrayOffsets.constructor);
    const size_t N = res.getBasePackageCount();
    for (size_t i = 0; i < N; i++) {
        const String16 name = res.getBasePackageName(i);
        env->CallVoidMethod(sparseArray, gSparseArrayOffsets.put, (jint) res.getBasePackageId(i),
                env->NewString(name, name.size()));
    }
    return sparseArray;
}

static jlong android_content_AssetManager_newTheme(JNIEnv* env, jobject clazz)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }
    return reinterpret_cast<jlong>(new ResTable::Theme(am->getResources()));
}

static void android_content_AssetManager_deleteTheme(JNIEnv* env, jobject clazz,
                                                     jlong themeHandle)
{
    ResTable::Theme* theme = reinterpret_cast<ResTable::Theme*>(themeHandle);
    delete theme;
}

static void android_content_AssetManager_applyThemeStyle(JNIEnv* env, jobject clazz,
                                                         jlong themeHandle,
                                                         jint styleRes,
                                                         jboolean force)
{
    ResTable::Theme* theme = reinterpret_cast<ResTable::Theme*>(themeHandle);
    theme->applyStyle(styleRes, force ? true : false);
}

static void android_content_AssetManager_copyTheme(JNIEnv* env, jobject clazz,
                                                   jlong destHandle, jlong srcHandle)
{
    ResTable::Theme* dest = reinterpret_cast<ResTable::Theme*>(destHandle);
    ResTable::Theme* src = reinterpret_cast<ResTable::Theme*>(srcHandle);
    dest->setTo(*src);
}

static jint android_content_AssetManager_loadThemeAttributeValue(
    JNIEnv* env, jobject clazz, jlong themeHandle, jint ident, jobject outValue, jboolean resolve)
{
    ResTable::Theme* theme = reinterpret_cast<ResTable::Theme*>(themeHandle);
    const ResTable& res(theme->getResTable());

    Res_value value;
    // XXX value could be different in different configs!
    uint32_t typeSpecFlags = 0;
    ssize_t block = theme->getAttribute(ident, &value, &typeSpecFlags);
    uint32_t ref = 0;
    if (resolve) {
        block = res.resolveReference(&value, block, &ref, &typeSpecFlags);
#if THROW_ON_BAD_ID
        if (block == BAD_INDEX) {
            jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
            return 0;
        }
#endif
    }
    return block >= 0 ? copyValue(env, outValue, &res, value, ref, block, typeSpecFlags) : block;
}

static void android_content_AssetManager_dumpTheme(JNIEnv* env, jobject clazz,
                                                   jlong themeHandle, jint pri,
                                                   jstring tag, jstring prefix)
{
    ResTable::Theme* theme = reinterpret_cast<ResTable::Theme*>(themeHandle);
    const ResTable& res(theme->getResTable());

    // XXX Need to use params.
    theme->dumpToLog();
}

static jboolean android_content_AssetManager_resolveAttrs(JNIEnv* env, jobject clazz,
                                                          jlong themeToken,
                                                          jint defStyleAttr,
                                                          jint defStyleRes,
                                                          jintArray inValues,
                                                          jintArray attrs,
                                                          jintArray outValues,
                                                          jintArray outIndices)
{
    if (themeToken == 0) {
        jniThrowNullPointerException(env, "theme token");
        return JNI_FALSE;
    }
    if (attrs == NULL) {
        jniThrowNullPointerException(env, "attrs");
        return JNI_FALSE;
    }
    if (outValues == NULL) {
        jniThrowNullPointerException(env, "out values");
        return JNI_FALSE;
    }

    DEBUG_STYLES(ALOGI("APPLY STYLE: theme=0x%x defStyleAttr=0x%x defStyleRes=0x%x",
        themeToken, defStyleAttr, defStyleRes));

    ResTable::Theme* theme = reinterpret_cast<ResTable::Theme*>(themeToken);
    const ResTable& res = theme->getResTable();
    ResTable_config config;
    Res_value value;

    const jsize NI = env->GetArrayLength(attrs);
    const jsize NV = env->GetArrayLength(outValues);
    if (NV < (NI*STYLE_NUM_ENTRIES)) {
        jniThrowException(env, "java/lang/IndexOutOfBoundsException", "out values too small");
        return JNI_FALSE;
    }

    jint* src = (jint*)env->GetPrimitiveArrayCritical(attrs, 0);
    if (src == NULL) {
        return JNI_FALSE;
    }

    jint* srcValues = (jint*)env->GetPrimitiveArrayCritical(inValues, 0);
    const jsize NSV = srcValues == NULL ? 0 : env->GetArrayLength(inValues);

    jint* baseDest = (jint*)env->GetPrimitiveArrayCritical(outValues, 0);
    jint* dest = baseDest;
    if (dest == NULL) {
        env->ReleasePrimitiveArrayCritical(attrs, src, 0);
        return JNI_FALSE;
    }

    jint* indices = NULL;
    int indicesIdx = 0;
    if (outIndices != NULL) {
        if (env->GetArrayLength(outIndices) > NI) {
            indices = (jint*)env->GetPrimitiveArrayCritical(outIndices, 0);
        }
    }

    // Load default style from attribute, if specified...
    uint32_t defStyleBagTypeSetFlags = 0;
    if (defStyleAttr != 0) {
        Res_value value;
        if (theme->getAttribute(defStyleAttr, &value, &defStyleBagTypeSetFlags) >= 0) {
            if (value.dataType == Res_value::TYPE_REFERENCE) {
                defStyleRes = value.data;
            }
        }
    }

    // Now lock down the resource object and start pulling stuff from it.
    res.lock();

    // Retrieve the default style bag, if requested.
    const ResTable::bag_entry* defStyleEnt = NULL;
    uint32_t defStyleTypeSetFlags = 0;
    ssize_t bagOff = defStyleRes != 0
            ? res.getBagLocked(defStyleRes, &defStyleEnt, &defStyleTypeSetFlags) : -1;
    defStyleTypeSetFlags |= defStyleBagTypeSetFlags;
    const ResTable::bag_entry* endDefStyleEnt = defStyleEnt +
        (bagOff >= 0 ? bagOff : 0);;

    // Now iterate through all of the attributes that the client has requested,
    // filling in each with whatever data we can find.
    ssize_t block = 0;
    uint32_t typeSetFlags;
    for (jsize ii=0; ii<NI; ii++) {
        const uint32_t curIdent = (uint32_t)src[ii];

        DEBUG_STYLES(ALOGI("RETRIEVING ATTR 0x%08x...", curIdent));

        // Try to find a value for this attribute...  we prioritize values
        // coming from, first XML attributes, then XML style, then default
        // style, and finally the theme.
        value.dataType = Res_value::TYPE_NULL;
        value.data = 0;
        typeSetFlags = 0;
        config.density = 0;

        // Retrieve the current input value if available.
        if (NSV > 0 && srcValues[ii] != 0) {
            block = -1;
            value.dataType = Res_value::TYPE_ATTRIBUTE;
            value.data = srcValues[ii];
            DEBUG_STYLES(ALOGI("-> From values: type=0x%x, data=0x%08x",
                    value.dataType, value.data));
        }

        // Skip through the default style values until the end or the next possible match.
        while (defStyleEnt < endDefStyleEnt && curIdent > defStyleEnt->map.name.ident) {
            defStyleEnt++;
        }
        // Retrieve the current default style attribute if it matches, and step to next.
        if (defStyleEnt < endDefStyleEnt && curIdent == defStyleEnt->map.name.ident) {
            if (value.dataType == Res_value::TYPE_NULL) {
                block = defStyleEnt->stringBlock;
                typeSetFlags = defStyleTypeSetFlags;
                value = defStyleEnt->map.value;
                DEBUG_STYLES(ALOGI("-> From def style: type=0x%x, data=0x%08x",
                        value.dataType, value.data));
            }
            defStyleEnt++;
        }

        uint32_t resid = 0;
        if (value.dataType != Res_value::TYPE_NULL) {
            // Take care of resolving the found resource to its final value.
            ssize_t newBlock = theme->resolveAttributeReference(&value, block,
                    &resid, &typeSetFlags, &config);
            if (newBlock >= 0) block = newBlock;
            DEBUG_STYLES(ALOGI("-> Resolved attr: type=0x%x, data=0x%08x",
                    value.dataType, value.data));
        } else {
            // If we still don't have a value for this attribute, try to find
            // it in the theme!
            ssize_t newBlock = theme->getAttribute(curIdent, &value, &typeSetFlags);
            if (newBlock >= 0) {
                DEBUG_STYLES(ALOGI("-> From theme: type=0x%x, data=0x%08x",
                        value.dataType, value.data));
                newBlock = res.resolveReference(&value, block, &resid,
                        &typeSetFlags, &config);
#if THROW_ON_BAD_ID
                if (newBlock == BAD_INDEX) {
                    jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
                    return JNI_FALSE;
                }
#endif
                if (newBlock >= 0) block = newBlock;
                DEBUG_STYLES(ALOGI("-> Resolved theme: type=0x%x, data=0x%08x",
                        value.dataType, value.data));
            }
        }

        // Deal with the special @null value -- it turns back to TYPE_NULL.
        if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
            DEBUG_STYLES(ALOGI("-> Setting to @null!"));
            value.dataType = Res_value::TYPE_NULL;
            block = -1;
        }

        DEBUG_STYLES(ALOGI("Attribute 0x%08x: type=0x%x, data=0x%08x",
                curIdent, value.dataType, value.data));

        // Write the final value back to Java.
        dest[STYLE_TYPE] = value.dataType;
        dest[STYLE_DATA] = value.data;
        dest[STYLE_ASSET_COOKIE] =
            block != -1 ? reinterpret_cast<jint>(res.getTableCookie(block)) : (jint)-1;
        dest[STYLE_RESOURCE_ID] = resid;
        dest[STYLE_CHANGING_CONFIGURATIONS] = typeSetFlags;
        dest[STYLE_DENSITY] = config.density;

        if (indices != NULL && value.dataType != Res_value::TYPE_NULL) {
            indicesIdx++;
            indices[indicesIdx] = ii;
        }

        dest += STYLE_NUM_ENTRIES;
    }

    res.unlock();

    if (indices != NULL) {
        indices[0] = indicesIdx;
        env->ReleasePrimitiveArrayCritical(outIndices, indices, 0);
    }
    env->ReleasePrimitiveArrayCritical(outValues, baseDest, 0);
    env->ReleasePrimitiveArrayCritical(inValues, srcValues, 0);
    env->ReleasePrimitiveArrayCritical(attrs, src, 0);

    return JNI_TRUE;
}

static jboolean android_content_AssetManager_applyStyle(JNIEnv* env, jobject clazz,
                                                        jlong themeToken,
                                                        jint defStyleAttr,
                                                        jint defStyleRes,
                                                        jlong xmlParserToken,
                                                        jintArray attrs,
                                                        jintArray outValues,
                                                        jintArray outIndices)
{
    if (themeToken == 0) {
        jniThrowNullPointerException(env, "theme token");
        return JNI_FALSE;
    }
    if (attrs == NULL) {
        jniThrowNullPointerException(env, "attrs");
        return JNI_FALSE;
    }
    if (outValues == NULL) {
        jniThrowNullPointerException(env, "out values");
        return JNI_FALSE;
    }

    DEBUG_STYLES(ALOGI("APPLY STYLE: theme=0x%x defStyleAttr=0x%x defStyleRes=0x%x xml=0x%x",
        themeToken, defStyleAttr, defStyleRes, xmlParserToken));

    ResTable::Theme* theme = reinterpret_cast<ResTable::Theme*>(themeToken);
    const ResTable& res = theme->getResTable();
    ResXMLParser* xmlParser = reinterpret_cast<ResXMLParser*>(xmlParserToken);
    ResTable_config config;
    Res_value value;

    const jsize NI = env->GetArrayLength(attrs);
    const jsize NV = env->GetArrayLength(outValues);
    if (NV < (NI*STYLE_NUM_ENTRIES)) {
        jniThrowException(env, "java/lang/IndexOutOfBoundsException", "out values too small");
        return JNI_FALSE;
    }

    jint* src = (jint*)env->GetPrimitiveArrayCritical(attrs, 0);
    if (src == NULL) {
        return JNI_FALSE;
    }

    jint* baseDest = (jint*)env->GetPrimitiveArrayCritical(outValues, 0);
    jint* dest = baseDest;
    if (dest == NULL) {
        env->ReleasePrimitiveArrayCritical(attrs, src, 0);
        return JNI_FALSE;
    }

    jint* indices = NULL;
    int indicesIdx = 0;
    if (outIndices != NULL) {
        if (env->GetArrayLength(outIndices) > NI) {
            indices = (jint*)env->GetPrimitiveArrayCritical(outIndices, 0);
        }
    }

    // Load default style from attribute, if specified...
    uint32_t defStyleBagTypeSetFlags = 0;
    if (defStyleAttr != 0) {
        Res_value value;
        if (theme->getAttribute(defStyleAttr, &value, &defStyleBagTypeSetFlags) >= 0) {
            if (value.dataType == Res_value::TYPE_REFERENCE) {
                defStyleRes = value.data;
            }
        }
    }

    // Retrieve the style class associated with the current XML tag.
    int style = 0;
    uint32_t styleBagTypeSetFlags = 0;
    if (xmlParser != NULL) {
        ssize_t idx = xmlParser->indexOfStyle();
        if (idx >= 0 && xmlParser->getAttributeValue(idx, &value) >= 0) {
            if (value.dataType == value.TYPE_ATTRIBUTE) {
                if (theme->getAttribute(value.data, &value, &styleBagTypeSetFlags) < 0) {
                    value.dataType = Res_value::TYPE_NULL;
                }
            }
            if (value.dataType == value.TYPE_REFERENCE) {
                style = value.data;
            }
        }
    }

    // Now lock down the resource object and start pulling stuff from it.
    res.lock();

    // Retrieve the default style bag, if requested.
    const ResTable::bag_entry* defStyleEnt = NULL;
    uint32_t defStyleTypeSetFlags = 0;
    ssize_t bagOff = defStyleRes != 0
            ? res.getBagLocked(defStyleRes, &defStyleEnt, &defStyleTypeSetFlags) : -1;
    defStyleTypeSetFlags |= defStyleBagTypeSetFlags;
    const ResTable::bag_entry* endDefStyleEnt = defStyleEnt +
        (bagOff >= 0 ? bagOff : 0);

    // Retrieve the style class bag, if requested.
    const ResTable::bag_entry* styleEnt = NULL;
    uint32_t styleTypeSetFlags = 0;
    bagOff = style != 0 ? res.getBagLocked(style, &styleEnt, &styleTypeSetFlags) : -1;
    styleTypeSetFlags |= styleBagTypeSetFlags;
    const ResTable::bag_entry* endStyleEnt = styleEnt +
        (bagOff >= 0 ? bagOff : 0);

    // Retrieve the XML attributes, if requested.
    const jsize NX = xmlParser ? xmlParser->getAttributeCount() : 0;
    jsize ix=0;
    uint32_t curXmlAttr = xmlParser ? xmlParser->getAttributeNameResID(ix) : 0;

    static const ssize_t kXmlBlock = 0x10000000;

    // Now iterate through all of the attributes that the client has requested,
    // filling in each with whatever data we can find.
    ssize_t block = 0;
    uint32_t typeSetFlags;
    for (jsize ii=0; ii<NI; ii++) {
        const uint32_t curIdent = (uint32_t)src[ii];

        DEBUG_STYLES(ALOGI("RETRIEVING ATTR 0x%08x...", curIdent));

        // Try to find a value for this attribute...  we prioritize values
        // coming from, first XML attributes, then XML style, then default
        // style, and finally the theme.
        value.dataType = Res_value::TYPE_NULL;
        value.data = 0;
        typeSetFlags = 0;
        config.density = 0;

        // Skip through XML attributes until the end or the next possible match.
        while (ix < NX && curIdent > curXmlAttr) {
            ix++;
            curXmlAttr = xmlParser->getAttributeNameResID(ix);
        }
        // Retrieve the current XML attribute if it matches, and step to next.
        if (ix < NX && curIdent == curXmlAttr) {
            block = kXmlBlock;
            xmlParser->getAttributeValue(ix, &value);
            ix++;
            curXmlAttr = xmlParser->getAttributeNameResID(ix);
            DEBUG_STYLES(ALOGI("-> From XML: type=0x%x, data=0x%08x",
                    value.dataType, value.data));
        }

        // Skip through the style values until the end or the next possible match.
        while (styleEnt < endStyleEnt && curIdent > styleEnt->map.name.ident) {
            styleEnt++;
        }
        // Retrieve the current style attribute if it matches, and step to next.
        if (styleEnt < endStyleEnt && curIdent == styleEnt->map.name.ident) {
            if (value.dataType == Res_value::TYPE_NULL) {
                block = styleEnt->stringBlock;
                typeSetFlags = styleTypeSetFlags;
                value = styleEnt->map.value;
                DEBUG_STYLES(ALOGI("-> From style: type=0x%x, data=0x%08x",
                        value.dataType, value.data));
            }
            styleEnt++;
        }

        // Skip through the default style values until the end or the next possible match.
        while (defStyleEnt < endDefStyleEnt && curIdent > defStyleEnt->map.name.ident) {
            defStyleEnt++;
        }
        // Retrieve the current default style attribute if it matches, and step to next.
        if (defStyleEnt < endDefStyleEnt && curIdent == defStyleEnt->map.name.ident) {
            if (value.dataType == Res_value::TYPE_NULL) {
                block = defStyleEnt->stringBlock;
                typeSetFlags = defStyleTypeSetFlags;
                value = defStyleEnt->map.value;
                DEBUG_STYLES(ALOGI("-> From def style: type=0x%x, data=0x%08x",
                        value.dataType, value.data));
            }
            defStyleEnt++;
        }

        uint32_t resid = 0;
        if (value.dataType != Res_value::TYPE_NULL) {
            // Take care of resolving the found resource to its final value.
            ssize_t newBlock = theme->resolveAttributeReference(&value, block,
                    &resid, &typeSetFlags, &config);
            if (newBlock >= 0) block = newBlock;
            DEBUG_STYLES(ALOGI("-> Resolved attr: type=0x%x, data=0x%08x",
                    value.dataType, value.data));
        } else {
            // If we still don't have a value for this attribute, try to find
            // it in the theme!
            ssize_t newBlock = theme->getAttribute(curIdent, &value, &typeSetFlags);
            if (newBlock >= 0) {
                DEBUG_STYLES(ALOGI("-> From theme: type=0x%x, data=0x%08x",
                        value.dataType, value.data));
                newBlock = res.resolveReference(&value, block, &resid,
                        &typeSetFlags, &config);
#if THROW_ON_BAD_ID
                if (newBlock == BAD_INDEX) {
                    jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
                    return JNI_FALSE;
                }
#endif
                if (newBlock >= 0) block = newBlock;
                DEBUG_STYLES(ALOGI("-> Resolved theme: type=0x%x, data=0x%08x",
                        value.dataType, value.data));
            }
        }

        // Deal with the special @null value -- it turns back to TYPE_NULL.
        if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
            DEBUG_STYLES(ALOGI("-> Setting to @null!"));
            value.dataType = Res_value::TYPE_NULL;
            block = kXmlBlock;
        }

        DEBUG_STYLES(ALOGI("Attribute 0x%08x: type=0x%x, data=0x%08x",
                curIdent, value.dataType, value.data));

        // Write the final value back to Java.
        dest[STYLE_TYPE] = value.dataType;
        dest[STYLE_DATA] = value.data;
        dest[STYLE_ASSET_COOKIE] =
            block != kXmlBlock ? reinterpret_cast<jint>(res.getTableCookie(block)) : (jint)-1;
        dest[STYLE_RESOURCE_ID] = resid;
        dest[STYLE_CHANGING_CONFIGURATIONS] = typeSetFlags;
        dest[STYLE_DENSITY] = config.density;

        if (indices != NULL && value.dataType != Res_value::TYPE_NULL) {
            indicesIdx++;
            indices[indicesIdx] = ii;
        }

        dest += STYLE_NUM_ENTRIES;
    }

    res.unlock();

    if (indices != NULL) {
        indices[0] = indicesIdx;
        env->ReleasePrimitiveArrayCritical(outIndices, indices, 0);
    }
    env->ReleasePrimitiveArrayCritical(outValues, baseDest, 0);
    env->ReleasePrimitiveArrayCritical(attrs, src, 0);

    return JNI_TRUE;
}

static jboolean android_content_AssetManager_retrieveAttributes(JNIEnv* env, jobject clazz,
                                                        jlong xmlParserToken,
                                                        jintArray attrs,
                                                        jintArray outValues,
                                                        jintArray outIndices)
{
    if (xmlParserToken == 0) {
        jniThrowNullPointerException(env, "xmlParserToken");
        return JNI_FALSE;
    }
    if (attrs == NULL) {
        jniThrowNullPointerException(env, "attrs");
        return JNI_FALSE;
    }
    if (outValues == NULL) {
        jniThrowNullPointerException(env, "out values");
        return JNI_FALSE;
    }

    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return JNI_FALSE;
    }
    const ResTable& res(am->getResources());
    ResXMLParser* xmlParser = (ResXMLParser*)xmlParserToken;
    ResTable_config config;
    Res_value value;

    const jsize NI = env->GetArrayLength(attrs);
    const jsize NV = env->GetArrayLength(outValues);
    if (NV < (NI*STYLE_NUM_ENTRIES)) {
        jniThrowException(env, "java/lang/IndexOutOfBoundsException", "out values too small");
        return JNI_FALSE;
    }

    jint* src = (jint*)env->GetPrimitiveArrayCritical(attrs, 0);
    if (src == NULL) {
        return JNI_FALSE;
    }

    jint* baseDest = (jint*)env->GetPrimitiveArrayCritical(outValues, 0);
    jint* dest = baseDest;
    if (dest == NULL) {
        env->ReleasePrimitiveArrayCritical(attrs, src, 0);
        return JNI_FALSE;
    }

    jint* indices = NULL;
    int indicesIdx = 0;
    if (outIndices != NULL) {
        if (env->GetArrayLength(outIndices) > NI) {
            indices = (jint*)env->GetPrimitiveArrayCritical(outIndices, 0);
        }
    }

    // Now lock down the resource object and start pulling stuff from it.
    res.lock();

    // Retrieve the XML attributes, if requested.
    const jsize NX = xmlParser->getAttributeCount();
    jsize ix=0;
    uint32_t curXmlAttr = xmlParser->getAttributeNameResID(ix);

    static const ssize_t kXmlBlock = 0x10000000;

    // Now iterate through all of the attributes that the client has requested,
    // filling in each with whatever data we can find.
    ssize_t block = 0;
    uint32_t typeSetFlags;
    for (jsize ii=0; ii<NI; ii++) {
        const uint32_t curIdent = (uint32_t)src[ii];

        // Try to find a value for this attribute...
        value.dataType = Res_value::TYPE_NULL;
        value.data = 0;
        typeSetFlags = 0;
        config.density = 0;

        // Skip through XML attributes until the end or the next possible match.
        while (ix < NX && curIdent > curXmlAttr) {
            ix++;
            curXmlAttr = xmlParser->getAttributeNameResID(ix);
        }
        // Retrieve the current XML attribute if it matches, and step to next.
        if (ix < NX && curIdent == curXmlAttr) {
            block = kXmlBlock;
            xmlParser->getAttributeValue(ix, &value);
            ix++;
            curXmlAttr = xmlParser->getAttributeNameResID(ix);
        }

        //printf("Attribute 0x%08x: type=0x%x, data=0x%08x\n", curIdent, value.dataType, value.data);
        uint32_t resid = 0;
        if (value.dataType != Res_value::TYPE_NULL) {
            // Take care of resolving the found resource to its final value.
            //printf("Resolving attribute reference\n");
            ssize_t newBlock = res.resolveReference(&value, block, &resid,
                    &typeSetFlags, &config);
#if THROW_ON_BAD_ID
            if (newBlock == BAD_INDEX) {
                jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
                return JNI_FALSE;
            }
#endif
            if (newBlock >= 0) block = newBlock;
        }

        // Deal with the special @null value -- it turns back to TYPE_NULL.
        if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
            value.dataType = Res_value::TYPE_NULL;
        }

        //printf("Attribute 0x%08x: final type=0x%x, data=0x%08x\n", curIdent, value.dataType, value.data);

        // Write the final value back to Java.
        dest[STYLE_TYPE] = value.dataType;
        dest[STYLE_DATA] = value.data;
        dest[STYLE_ASSET_COOKIE] =
            block != kXmlBlock ? reinterpret_cast<jint>(res.getTableCookie(block)) : (jint)-1;
        dest[STYLE_RESOURCE_ID] = resid;
        dest[STYLE_CHANGING_CONFIGURATIONS] = typeSetFlags;
        dest[STYLE_DENSITY] = config.density;

        if (indices != NULL && value.dataType != Res_value::TYPE_NULL) {
            indicesIdx++;
            indices[indicesIdx] = ii;
        }

        dest += STYLE_NUM_ENTRIES;
    }

    res.unlock();

    if (indices != NULL) {
        indices[0] = indicesIdx;
        env->ReleasePrimitiveArrayCritical(outIndices, indices, 0);
    }

    env->ReleasePrimitiveArrayCritical(outValues, baseDest, 0);
    env->ReleasePrimitiveArrayCritical(attrs, src, 0);

    return JNI_TRUE;
}

static jint android_content_AssetManager_getArraySize(JNIEnv* env, jobject clazz,
                                                       jint id)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }
    const ResTable& res(am->getResources());

    res.lock();
    const ResTable::bag_entry* defStyleEnt = NULL;
    ssize_t bagOff = res.getBagLocked(id, &defStyleEnt);
    res.unlock();

    return static_cast<jint>(bagOff);
}

static jint android_content_AssetManager_retrieveArray(JNIEnv* env, jobject clazz,
                                                        jint id,
                                                        jintArray outValues)
{
    if (outValues == NULL) {
        jniThrowNullPointerException(env, "out values");
        return JNI_FALSE;
    }

    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return JNI_FALSE;
    }
    const ResTable& res(am->getResources());
    ResTable_config config;
    Res_value value;
    ssize_t block;

    const jsize NV = env->GetArrayLength(outValues);

    jint* baseDest = (jint*)env->GetPrimitiveArrayCritical(outValues, 0);
    jint* dest = baseDest;
    if (dest == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", "");
        return JNI_FALSE;
    }

    // Now lock down the resource object and start pulling stuff from it.
    res.lock();

    const ResTable::bag_entry* arrayEnt = NULL;
    uint32_t arrayTypeSetFlags = 0;
    ssize_t bagOff = res.getBagLocked(id, &arrayEnt, &arrayTypeSetFlags);
    const ResTable::bag_entry* endArrayEnt = arrayEnt +
        (bagOff >= 0 ? bagOff : 0);

    int i = 0;
    uint32_t typeSetFlags;
    while (i < NV && arrayEnt < endArrayEnt) {
        block = arrayEnt->stringBlock;
        typeSetFlags = arrayTypeSetFlags;
        config.density = 0;
        value = arrayEnt->map.value;

        uint32_t resid = 0;
        if (value.dataType != Res_value::TYPE_NULL) {
            // Take care of resolving the found resource to its final value.
            //printf("Resolving attribute reference\n");
            ssize_t newBlock = res.resolveReference(&value, block, &resid,
                    &typeSetFlags, &config);
#if THROW_ON_BAD_ID
            if (newBlock == BAD_INDEX) {
                jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
                return JNI_FALSE;
            }
#endif
            if (newBlock >= 0) block = newBlock;
        }

        // Deal with the special @null value -- it turns back to TYPE_NULL.
        if (value.dataType == Res_value::TYPE_REFERENCE && value.data == 0) {
            value.dataType = Res_value::TYPE_NULL;
        }

        //printf("Attribute 0x%08x: final type=0x%x, data=0x%08x\n", curIdent, value.dataType, value.data);

        // Write the final value back to Java.
        dest[STYLE_TYPE] = value.dataType;
        dest[STYLE_DATA] = value.data;
        dest[STYLE_ASSET_COOKIE] = reinterpret_cast<jint>(res.getTableCookie(block));
        dest[STYLE_RESOURCE_ID] = resid;
        dest[STYLE_CHANGING_CONFIGURATIONS] = typeSetFlags;
        dest[STYLE_DENSITY] = config.density;
        dest += STYLE_NUM_ENTRIES;
        i+= STYLE_NUM_ENTRIES;
        arrayEnt++;
    }

    i /= STYLE_NUM_ENTRIES;

    res.unlock();

    env->ReleasePrimitiveArrayCritical(outValues, baseDest, 0);

    return i;
}

static jlong android_content_AssetManager_openXmlAssetNative(JNIEnv* env, jobject clazz,
                                                         jint cookie,
                                                         jstring fileName)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return 0;
    }

    ALOGV("openXmlAsset in %p (Java object %p)\n", am, clazz);

    ScopedUtfChars fileName8(env, fileName);
    if (fileName8.c_str() == NULL) {
        return 0;
    }

    int32_t assetCookie = static_cast<int32_t>(cookie);
    Asset* a = assetCookie
        ? am->openNonAsset(assetCookie, fileName8.c_str(), Asset::ACCESS_BUFFER)
        : am->openNonAsset(fileName8.c_str(), Asset::ACCESS_BUFFER, &assetCookie);

    if (a == NULL) {
        jniThrowException(env, "java/io/FileNotFoundException", fileName8.c_str());
        return 0;
    }

    const DynamicRefTable* dynamicRefTable =
            am->getResources().getDynamicRefTableForCookie(assetCookie);
    ResXMLTree* block = new ResXMLTree(dynamicRefTable);
    status_t err = block->setTo(a->getBuffer(true), a->getLength(), true);
    a->close();
    delete a;

    if (err != NO_ERROR) {
        jniThrowException(env, "java/io/FileNotFoundException", "Corrupt XML binary file");
        return 0;
    }

    return reinterpret_cast<jlong>(block);
}

static jintArray android_content_AssetManager_getArrayStringInfo(JNIEnv* env, jobject clazz,
                                                                 jint arrayResId)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }
    const ResTable& res(am->getResources());

    const ResTable::bag_entry* startOfBag;
    const ssize_t N = res.lockBag(arrayResId, &startOfBag);
    if (N < 0) {
        return NULL;
    }

    jintArray array = env->NewIntArray(N * 2);
    if (array == NULL) {
        res.unlockBag(startOfBag);
        return NULL;
    }

    Res_value value;
    const ResTable::bag_entry* bag = startOfBag;
    for (size_t i = 0, j = 0; ((ssize_t)i)<N; i++, bag++) {
        jint stringIndex = -1;
        jint stringBlock = 0;
        value = bag->map.value;

        // Take care of resolving the found resource to its final value.
        stringBlock = res.resolveReference(&value, bag->stringBlock, NULL);
        if (value.dataType == Res_value::TYPE_STRING) {
            stringIndex = value.data;
        }

#if THROW_ON_BAD_ID
        if (stringBlock == BAD_INDEX) {
            jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
            return array;
        }
#endif

        //todo: It might be faster to allocate a C array to contain
        //      the blocknums and indices, put them in there and then
        //      do just one SetIntArrayRegion()
        env->SetIntArrayRegion(array, j, 1, &stringBlock);
        env->SetIntArrayRegion(array, j + 1, 1, &stringIndex);
        j = j + 2;
    }
    res.unlockBag(startOfBag);
    return array;
}

static jobjectArray android_content_AssetManager_getArrayStringResource(JNIEnv* env, jobject clazz,
                                                                        jint arrayResId)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }
    const ResTable& res(am->getResources());

    const ResTable::bag_entry* startOfBag;
    const ssize_t N = res.lockBag(arrayResId, &startOfBag);
    if (N < 0) {
        return NULL;
    }

    jobjectArray array = env->NewObjectArray(N, g_stringClass, NULL);
    if (env->ExceptionCheck()) {
        res.unlockBag(startOfBag);
        return NULL;
    }

    Res_value value;
    const ResTable::bag_entry* bag = startOfBag;
    size_t strLen = 0;
    for (size_t i=0; ((ssize_t)i)<N; i++, bag++) {
        value = bag->map.value;
        jstring str = NULL;

        // Take care of resolving the found resource to its final value.
        ssize_t block = res.resolveReference(&value, bag->stringBlock, NULL);
#if THROW_ON_BAD_ID
        if (block == BAD_INDEX) {
            jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
            return array;
        }
#endif
        if (value.dataType == Res_value::TYPE_STRING) {
            const ResStringPool* pool = res.getTableStringBlock(block);
            const char* str8 = pool->string8At(value.data, &strLen);
            if (str8 != NULL) {
                str = env->NewStringUTF(str8);
            } else {
                const char16_t* str16 = pool->stringAt(value.data, &strLen);
                str = env->NewString(str16, strLen);
            }

            // If one of our NewString{UTF} calls failed due to memory, an
            // exception will be pending.
            if (env->ExceptionCheck()) {
                res.unlockBag(startOfBag);
                return NULL;
            }

            env->SetObjectArrayElement(array, i, str);

            // str is not NULL at that point, otherwise ExceptionCheck would have been true.
            // If we have a large amount of strings in our array, we might
            // overflow the local reference table of the VM.
            env->DeleteLocalRef(str);
        }
    }
    res.unlockBag(startOfBag);
    return array;
}

static jintArray android_content_AssetManager_getArrayIntResource(JNIEnv* env, jobject clazz,
                                                                        jint arrayResId)
{
    AssetManager* am = assetManagerForJavaObject(env, clazz);
    if (am == NULL) {
        return NULL;
    }
    const ResTable& res(am->getResources());

    const ResTable::bag_entry* startOfBag;
    const ssize_t N = res.lockBag(arrayResId, &startOfBag);
    if (N < 0) {
        return NULL;
    }

    jintArray array = env->NewIntArray(N);
    if (array == NULL) {
        res.unlockBag(startOfBag);
        return NULL;
    }

    Res_value value;
    const ResTable::bag_entry* bag = startOfBag;
    for (size_t i=0; ((ssize_t)i)<N; i++, bag++) {
        value = bag->map.value;

        // Take care of resolving the found resource to its final value.
        ssize_t block = res.resolveReference(&value, bag->stringBlock, NULL);
#if THROW_ON_BAD_ID
        if (block == BAD_INDEX) {
            jniThrowException(env, "java/lang/IllegalStateException", "Bad resource!");
            return array;
        }
#endif
        if (value.dataType >= Res_value::TYPE_FIRST_INT
                && value.dataType <= Res_value::TYPE_LAST_INT) {
            int intVal = value.data;
            env->SetIntArrayRegion(array, i, 1, &intVal);
        }
    }
    res.unlockBag(startOfBag);
    return array;
}

static void android_content_AssetManager_init(JNIEnv* env, jobject clazz, jboolean isSystem)
{
    if (isSystem) {
        verifySystemIdmaps();
    }
    AssetManager* am = new AssetManager();
    if (am == NULL) {
        jniThrowException(env, "java/lang/OutOfMemoryError", "");
        return;
    }

    am->addDefaultAssets();

    ALOGV("Created AssetManager %p for Java object %p\n", am, clazz);
    env->SetLongField(clazz, gAssetManagerOffsets.mObject, reinterpret_cast<jlong>(am));
}

static void android_content_AssetManager_destroy(JNIEnv* env, jobject clazz)
{
    AssetManager* am = (AssetManager*)
        (env->GetLongField(clazz, gAssetManagerOffsets.mObject));
    ALOGV("Destroying AssetManager %p for Java object %p\n", am, clazz);
    if (am != NULL) {
        delete am;
        env->SetLongField(clazz, gAssetManagerOffsets.mObject, 0);
    }
}

static jint android_content_AssetManager_getGlobalAssetCount(JNIEnv* env, jobject clazz)
{
    return Asset::getGlobalCount();
}

static jobject android_content_AssetManager_getAssetAllocations(JNIEnv* env, jobject clazz)
{
    String8 alloc = Asset::getAssetAllocations();
    if (alloc.length() <= 0) {
        return NULL;
    }

    jstring str = env->NewStringUTF(alloc.string());
    return str;
}

static jint android_content_AssetManager_getGlobalAssetManagerCount(JNIEnv* env, jobject clazz)
{
    return AssetManager::getGlobalCount();
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gAssetManagerMethods[] = {
    /* name, signature, funcPtr */

    // Basic asset stuff.
    { "openAsset",      "(Ljava/lang/String;I)J",
        (void*) android_content_AssetManager_openAsset },
    { "openAssetFd",      "(Ljava/lang/String;[J)Landroid/os/ParcelFileDescriptor;",
        (void*) android_content_AssetManager_openAssetFd },
    { "openNonAssetNative", "(ILjava/lang/String;I)J",
        (void*) android_content_AssetManager_openNonAssetNative },
    { "openNonAssetFdNative", "(ILjava/lang/String;[J)Landroid/os/ParcelFileDescriptor;",
        (void*) android_content_AssetManager_openNonAssetFdNative },
    { "list",           "(Ljava/lang/String;)[Ljava/lang/String;",
        (void*) android_content_AssetManager_list },
    { "destroyAsset",   "(J)V",
        (void*) android_content_AssetManager_destroyAsset },
    { "readAssetChar",  "(J)I",
        (void*) android_content_AssetManager_readAssetChar },
    { "readAsset",      "(J[BII)I",
        (void*) android_content_AssetManager_readAsset },
    { "seekAsset",      "(JJI)J",
        (void*) android_content_AssetManager_seekAsset },
    { "getAssetLength", "(J)J",
        (void*) android_content_AssetManager_getAssetLength },
    { "getAssetRemainingLength", "(J)J",
        (void*) android_content_AssetManager_getAssetRemainingLength },
    { "addAssetPathNative", "(Ljava/lang/String;)I",
        (void*) android_content_AssetManager_addAssetPath },
    { "addOverlayPath",   "(Ljava/lang/String;)I",
        (void*) android_content_AssetManager_addOverlayPath },
    { "isUpToDate",     "()Z",
        (void*) android_content_AssetManager_isUpToDate },

    // Resources.
    { "setLocale",      "(Ljava/lang/String;)V",
        (void*) android_content_AssetManager_setLocale },
    { "getLocales",      "()[Ljava/lang/String;",
        (void*) android_content_AssetManager_getLocales },
    { "setConfiguration", "(IILjava/lang/String;IIIIIIIIIIIIII)V",
        (void*) android_content_AssetManager_setConfiguration },
    { "getResourceIdentifier","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
        (void*) android_content_AssetManager_getResourceIdentifier },
    { "getResourceName","(I)Ljava/lang/String;",
        (void*) android_content_AssetManager_getResourceName },
    { "getResourcePackageName","(I)Ljava/lang/String;",
        (void*) android_content_AssetManager_getResourcePackageName },
    { "getResourceTypeName","(I)Ljava/lang/String;",
        (void*) android_content_AssetManager_getResourceTypeName },
    { "getResourceEntryName","(I)Ljava/lang/String;",
        (void*) android_content_AssetManager_getResourceEntryName },
    { "loadResourceValue","(ISLandroid/util/TypedValue;Z)I",
        (void*) android_content_AssetManager_loadResourceValue },
    { "loadResourceBagValue","(IILandroid/util/TypedValue;Z)I",
        (void*) android_content_AssetManager_loadResourceBagValue },
    { "getStringBlockCount","()I",
        (void*) android_content_AssetManager_getStringBlockCount },
    { "getNativeStringBlock","(I)J",
        (void*) android_content_AssetManager_getNativeStringBlock },
    { "getCookieName","(I)Ljava/lang/String;",
        (void*) android_content_AssetManager_getCookieName },
    { "getAssignedPackageIdentifiers","()Landroid/util/SparseArray;",
        (void*) android_content_AssetManager_getAssignedPackageIdentifiers },

    // Themes.
    { "newTheme", "()J",
        (void*) android_content_AssetManager_newTheme },
    { "deleteTheme", "(J)V",
        (void*) android_content_AssetManager_deleteTheme },
    { "applyThemeStyle", "(JIZ)V",
        (void*) android_content_AssetManager_applyThemeStyle },
    { "copyTheme", "(JJ)V",
        (void*) android_content_AssetManager_copyTheme },
    { "loadThemeAttributeValue", "(JILandroid/util/TypedValue;Z)I",
        (void*) android_content_AssetManager_loadThemeAttributeValue },
    { "dumpTheme", "(JILjava/lang/String;Ljava/lang/String;)V",
        (void*) android_content_AssetManager_dumpTheme },
    { "applyStyle","(JIIJ[I[I[I)Z",
        (void*) android_content_AssetManager_applyStyle },
    { "retrieveAttributes","(J[I[I[I)Z",
        (void*) android_content_AssetManager_retrieveAttributes },
    { "getArraySize","(I)I",
        (void*) android_content_AssetManager_getArraySize },
    { "retrieveArray","(I[I)I",
        (void*) android_content_AssetManager_retrieveArray },

    // XML files.
    { "openXmlAssetNative", "(ILjava/lang/String;)J",
        (void*) android_content_AssetManager_openXmlAssetNative },

    // Arrays.
    { "getArrayStringResource","(I)[Ljava/lang/String;",
        (void*) android_content_AssetManager_getArrayStringResource },
    { "getArrayStringInfo","(I)[I",
        (void*) android_content_AssetManager_getArrayStringInfo },
    { "getArrayIntResource","(I)[I",
        (void*) android_content_AssetManager_getArrayIntResource },

    // Bookkeeping.
    { "init",           "(Z)V",
        (void*) android_content_AssetManager_init },
    { "destroy",        "()V",
        (void*) android_content_AssetManager_destroy },
    { "getGlobalAssetCount", "()I",
        (void*) android_content_AssetManager_getGlobalAssetCount },
    { "getAssetAllocations", "()Ljava/lang/String;",
        (void*) android_content_AssetManager_getAssetAllocations },
    { "getGlobalAssetManagerCount", "()I",
        (void*) android_content_AssetManager_getGlobalAssetCount },
};

int register_android_content_AssetManager(JNIEnv* env)
{
    jclass typedValue = env->FindClass("android/util/TypedValue");
    LOG_FATAL_IF(typedValue == NULL, "Unable to find class android/util/TypedValue");
    gTypedValueOffsets.mType
        = env->GetFieldID(typedValue, "type", "I");
    LOG_FATAL_IF(gTypedValueOffsets.mType == NULL, "Unable to find TypedValue.type");
    gTypedValueOffsets.mData
        = env->GetFieldID(typedValue, "data", "I");
    LOG_FATAL_IF(gTypedValueOffsets.mData == NULL, "Unable to find TypedValue.data");
    gTypedValueOffsets.mString
        = env->GetFieldID(typedValue, "string", "Ljava/lang/CharSequence;");
    LOG_FATAL_IF(gTypedValueOffsets.mString == NULL, "Unable to find TypedValue.string");
    gTypedValueOffsets.mAssetCookie
        = env->GetFieldID(typedValue, "assetCookie", "I");
    LOG_FATAL_IF(gTypedValueOffsets.mAssetCookie == NULL, "Unable to find TypedValue.assetCookie");
    gTypedValueOffsets.mResourceId
        = env->GetFieldID(typedValue, "resourceId", "I");
    LOG_FATAL_IF(gTypedValueOffsets.mResourceId == NULL, "Unable to find TypedValue.resourceId");
    gTypedValueOffsets.mChangingConfigurations
        = env->GetFieldID(typedValue, "changingConfigurations", "I");
    LOG_FATAL_IF(gTypedValueOffsets.mChangingConfigurations == NULL, "Unable to find TypedValue.changingConfigurations");
    gTypedValueOffsets.mDensity = env->GetFieldID(typedValue, "density", "I");
    LOG_FATAL_IF(gTypedValueOffsets.mDensity == NULL, "Unable to find TypedValue.density");

    jclass assetFd = env->FindClass("android/content/res/AssetFileDescriptor");
    LOG_FATAL_IF(assetFd == NULL, "Unable to find class android/content/res/AssetFileDescriptor");
    gAssetFileDescriptorOffsets.mFd
        = env->GetFieldID(assetFd, "mFd", "Landroid/os/ParcelFileDescriptor;");
    LOG_FATAL_IF(gAssetFileDescriptorOffsets.mFd == NULL, "Unable to find AssetFileDescriptor.mFd");
    gAssetFileDescriptorOffsets.mStartOffset
        = env->GetFieldID(assetFd, "mStartOffset", "J");
    LOG_FATAL_IF(gAssetFileDescriptorOffsets.mStartOffset == NULL, "Unable to find AssetFileDescriptor.mStartOffset");
    gAssetFileDescriptorOffsets.mLength
        = env->GetFieldID(assetFd, "mLength", "J");
    LOG_FATAL_IF(gAssetFileDescriptorOffsets.mLength == NULL, "Unable to find AssetFileDescriptor.mLength");

    jclass assetManager = env->FindClass("android/content/res/AssetManager");
    LOG_FATAL_IF(assetManager == NULL, "Unable to find class android/content/res/AssetManager");
    gAssetManagerOffsets.mObject
        = env->GetFieldID(assetManager, "mObject", "J");
    LOG_FATAL_IF(gAssetManagerOffsets.mObject == NULL, "Unable to find AssetManager.mObject");

    jclass stringClass = env->FindClass("java/lang/String");
    LOG_FATAL_IF(stringClass == NULL, "Unable to find class java/lang/String");
    g_stringClass = (jclass)env->NewGlobalRef(stringClass);
    LOG_FATAL_IF(g_stringClass == NULL, "Unable to create global reference for class java/lang/String");

    jclass sparseArrayClass = env->FindClass("android/util/SparseArray");
    LOG_FATAL_IF(sparseArrayClass == NULL, "Unable to find class android/util/SparseArray");
    gSparseArrayOffsets.classObject = (jclass) env->NewGlobalRef(sparseArrayClass);
    gSparseArrayOffsets.constructor =
            env->GetMethodID(gSparseArrayOffsets.classObject, "<init>", "()V");
    LOG_FATAL_IF(gSparseArrayOffsets.constructor == NULL, "Unable to find SparseArray.<init>()");
    gSparseArrayOffsets.put =
            env->GetMethodID(gSparseArrayOffsets.classObject, "put", "(ILjava/lang/Object;)V");
    LOG_FATAL_IF(gSparseArrayOffsets.put == NULL, "Unable to find SparseArray.put(int, V)");

    return AndroidRuntime::registerNativeMethods(env,
            "android/content/res/AssetManager", gAssetManagerMethods, NELEM(gAssetManagerMethods));
}

}; // namespace android
