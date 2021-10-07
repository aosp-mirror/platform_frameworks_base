/*
 * Copyright 2012, The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaCodec-JNI"
#include <utils/Log.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaCodecList.h>
#include <media/IMediaCodecList.h>
#include <media/MediaCodecInfo.h>

#include <utils/Vector.h>

#include <mutex>
#include <vector>

#include "android_runtime/AndroidRuntime.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_media_Streams.h"

using namespace android;

/**
 * This object unwraps codec aliases into individual codec infos as the Java interface handles
 * aliases in this way.
 */
class JavaMediaCodecListWrapper {
public:
    struct Info {
        sp<MediaCodecInfo> info;
        AString alias;
    };

    const Info getCodecInfo(size_t index) const {
        if (index < mInfoList.size()) {
            return mInfoList[index];
        }
        // return
        return Info { nullptr /* info */, "(none)" /* alias */ };
    }

    size_t countCodecs() const {
        return mInfoList.size();
    }

    sp<IMediaCodecList> getCodecList() const {
        return mCodecList;
    }

    size_t findCodecByName(AString name) const {
        auto it = mInfoIndex.find(name);
        return it == mInfoIndex.end() ? -ENOENT : it->second;
    }

    JavaMediaCodecListWrapper(sp<IMediaCodecList> mcl)
            : mCodecList(mcl) {
        size_t numCodecs = mcl->countCodecs();
        for (size_t ix = 0; ix < numCodecs; ++ix) {
            sp<MediaCodecInfo> info = mcl->getCodecInfo(ix);
            Vector<AString> namesAndAliases;
            info->getAliases(&namesAndAliases);
            namesAndAliases.insertAt(0);
            namesAndAliases.editItemAt(0) = info->getCodecName();
            for (const AString &nameOrAlias : namesAndAliases) {
                if (mInfoIndex.count(nameOrAlias) > 0) {
                    // skip duplicate names or aliases
                    continue;
                }
                mInfoIndex.emplace(nameOrAlias, mInfoList.size());
                mInfoList.emplace_back(Info { info, nameOrAlias });
            }
        }
    }

private:
    sp<IMediaCodecList> mCodecList;
    std::vector<Info> mInfoList;
    std::map<AString, size_t> mInfoIndex;
};

static std::mutex sMutex;
static std::unique_ptr<JavaMediaCodecListWrapper> sListWrapper;

static const JavaMediaCodecListWrapper *getCodecList(JNIEnv *env) {
    std::lock_guard<std::mutex> lock(sMutex);
    if (sListWrapper == nullptr) {
        sp<IMediaCodecList> mcl = MediaCodecList::getInstance();
        if (mcl == NULL) {
            // This should never happen unless something is really wrong
            jniThrowException(
                        env, "java/lang/RuntimeException", "cannot get MediaCodecList");
            return NULL;
        }

        sListWrapper.reset(new JavaMediaCodecListWrapper(mcl));
    }
    return sListWrapper.get();
}

static JavaMediaCodecListWrapper::Info getCodecInfo(JNIEnv *env, jint index) {
    const JavaMediaCodecListWrapper *mcl = getCodecList(env);
    if (mcl == nullptr) {
        // Runtime exception already pending.
        return JavaMediaCodecListWrapper::Info { nullptr /* info */, "(none)" /* alias */ };
    }

    JavaMediaCodecListWrapper::Info info = mcl->getCodecInfo(index);
    if (info.info == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
    }

    return info;
}

static jint android_media_MediaCodecList_getCodecCount(
        JNIEnv *env, jobject /* thiz */) {
    const JavaMediaCodecListWrapper *mcl = getCodecList(env);
    if (mcl == NULL) {
        // Runtime exception already pending.
        return 0;
    }

    return mcl->countCodecs();
}

static jstring android_media_MediaCodecList_getCodecName(
        JNIEnv *env, jobject /* thiz */, jint index) {
    JavaMediaCodecListWrapper::Info info = getCodecInfo(env, index);
    if (info.info == NULL) {
        // Runtime exception already pending.
        return NULL;
    }

    const char *name = info.alias.c_str();
    return env->NewStringUTF(name);
}

static jstring android_media_MediaCodecList_getCanonicalName(
        JNIEnv *env, jobject /* thiz */, jint index) {
    JavaMediaCodecListWrapper::Info info = getCodecInfo(env, index);
    if (info.info == NULL) {
        // Runtime exception already pending.
        return NULL;
    }

    const char *name = info.info->getCodecName();
    return env->NewStringUTF(name);
}

static jint android_media_MediaCodecList_findCodecByName(
        JNIEnv *env, jobject /* thiz */, jstring name) {
    if (name == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return -ENOENT;
    }

    const char *nameStr = env->GetStringUTFChars(name, NULL);
    if (nameStr == NULL) {
        // Out of memory exception already pending.
        return -ENOENT;
    }

    const JavaMediaCodecListWrapper *mcl = getCodecList(env);
    if (mcl == NULL) {
        // Runtime exception already pending.
        env->ReleaseStringUTFChars(name, nameStr);
        return -ENOENT;
    }

    jint ret = mcl->findCodecByName(nameStr);
    env->ReleaseStringUTFChars(name, nameStr);
    return ret;
}

static jboolean android_media_MediaCodecList_getAttributes(
        JNIEnv *env, jobject /* thiz */, jint index) {
    JavaMediaCodecListWrapper::Info info = getCodecInfo(env, index);
    if (info.info == NULL) {
        // Runtime exception already pending.
        return 0;
    }

    return info.info->getAttributes();
}

static jarray android_media_MediaCodecList_getSupportedTypes(
        JNIEnv *env, jobject /* thiz */, jint index) {
    JavaMediaCodecListWrapper::Info info = getCodecInfo(env, index);
    if (info.info == NULL) {
        // Runtime exception already pending.
        return NULL;
    }

    Vector<AString> types;
    info.info->getSupportedMediaTypes(&types);

    jclass clazz = env->FindClass("java/lang/String");
    CHECK(clazz != NULL);

    jobjectArray array = env->NewObjectArray(types.size(), clazz, NULL);

    for (size_t i = 0; i < types.size(); ++i) {
        jstring obj = env->NewStringUTF(types.itemAt(i).c_str());
        env->SetObjectArrayElement(array, i, obj);
        env->DeleteLocalRef(obj);
        obj = NULL;
    }

    return array;
}

static jobject android_media_MediaCodecList_getCodecCapabilities(
        JNIEnv *env, jobject /* thiz */, jint index, jstring type) {
    if (type == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    JavaMediaCodecListWrapper::Info info = getCodecInfo(env, index);
    if (info.info == NULL) {
        // Runtime exception already pending.
        return NULL;
    }


    const char *typeStr = env->GetStringUTFChars(type, NULL);
    if (typeStr == NULL) {
        // Out of memory exception already pending.
        return NULL;
    }

    Vector<MediaCodecInfo::ProfileLevel> profileLevels;
    Vector<uint32_t> colorFormats;

    sp<AMessage> defaultFormat = new AMessage();
    defaultFormat->setString("mime", typeStr);

    // TODO query default-format also from codec/codec list
    const sp<MediaCodecInfo::Capabilities> &capabilities =
        info.info->getCapabilitiesFor(typeStr);
    env->ReleaseStringUTFChars(type, typeStr);
    typeStr = NULL;
    if (capabilities == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    capabilities->getSupportedColorFormats(&colorFormats);
    capabilities->getSupportedProfileLevels(&profileLevels);
    sp<AMessage> details = capabilities->getDetails();
    bool isEncoder = info.info->isEncoder();

    jobject defaultFormatObj = NULL;
    if (ConvertMessageToMap(env, defaultFormat, &defaultFormatObj)) {
        return NULL;
    }

    jobject infoObj = NULL;
    if (ConvertMessageToMap(env, details, &infoObj)) {
        env->DeleteLocalRef(defaultFormatObj);
        return NULL;
    }

    jclass capsClazz =
        env->FindClass("android/media/MediaCodecInfo$CodecCapabilities");
    CHECK(capsClazz != NULL);

    jclass profileLevelClazz =
        env->FindClass("android/media/MediaCodecInfo$CodecProfileLevel");
    CHECK(profileLevelClazz != NULL);

    jobjectArray profileLevelArray =
        env->NewObjectArray(profileLevels.size(), profileLevelClazz, NULL);

    jfieldID profileField =
        env->GetFieldID(profileLevelClazz, "profile", "I");

    jfieldID levelField =
        env->GetFieldID(profileLevelClazz, "level", "I");

    for (size_t i = 0; i < profileLevels.size(); ++i) {
        const MediaCodecInfo::ProfileLevel &src = profileLevels.itemAt(i);

        jobject profileLevelObj = env->AllocObject(profileLevelClazz);

        env->SetIntField(profileLevelObj, profileField, src.mProfile);
        env->SetIntField(profileLevelObj, levelField, src.mLevel);

        env->SetObjectArrayElement(profileLevelArray, i, profileLevelObj);

        env->DeleteLocalRef(profileLevelObj);
        profileLevelObj = NULL;
    }

    jintArray colorFormatsArray = env->NewIntArray(colorFormats.size());

    for (size_t i = 0; i < colorFormats.size(); ++i) {
        jint val = colorFormats.itemAt(i);
        env->SetIntArrayRegion(colorFormatsArray, i, 1, &val);
    }

    jmethodID capsConstructID = env->GetMethodID(capsClazz, "<init>",
            "([Landroid/media/MediaCodecInfo$CodecProfileLevel;[IZ"
            "Ljava/util/Map;Ljava/util/Map;)V");

    jobject caps = env->NewObject(capsClazz, capsConstructID,
            profileLevelArray, colorFormatsArray, isEncoder,
            defaultFormatObj, infoObj);

    env->DeleteLocalRef(profileLevelArray);
    profileLevelArray = NULL;

    env->DeleteLocalRef(colorFormatsArray);
    colorFormatsArray = NULL;

    env->DeleteLocalRef(defaultFormatObj);
    defaultFormatObj = NULL;

    env->DeleteLocalRef(infoObj);
    infoObj = NULL;

    return caps;
}

static jobject android_media_MediaCodecList_getGlobalSettings(JNIEnv *env, jobject /* thiz */) {
    const JavaMediaCodecListWrapper *mcl = getCodecList(env);
    if (mcl == NULL) {
        // Runtime exception already pending.
        return NULL;
    }

    const sp<AMessage> settings = mcl->getCodecList()->getGlobalSettings();
    if (settings == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "cannot get global settings");
        return NULL;
    }

    jobject settingsObj = NULL;
    if (ConvertMessageToMap(env, settings, &settingsObj)) {
        return NULL;
    }

    return settingsObj;
}

static void android_media_MediaCodecList_native_init(JNIEnv* /* env */) {
}

static const JNINativeMethod gMethods[] = {
    { "native_getCodecCount", "()I", (void *)android_media_MediaCodecList_getCodecCount },

    { "getCanonicalName", "(I)Ljava/lang/String;",
      (void *)android_media_MediaCodecList_getCanonicalName },

    { "getCodecName", "(I)Ljava/lang/String;",
      (void *)android_media_MediaCodecList_getCodecName },

    { "getAttributes", "(I)I", (void *)android_media_MediaCodecList_getAttributes },

    { "getSupportedTypes", "(I)[Ljava/lang/String;",
      (void *)android_media_MediaCodecList_getSupportedTypes },

    { "getCodecCapabilities",
      "(ILjava/lang/String;)Landroid/media/MediaCodecInfo$CodecCapabilities;",
      (void *)android_media_MediaCodecList_getCodecCapabilities },

    { "native_getGlobalSettings",
      "()Ljava/util/Map;",
      (void *)android_media_MediaCodecList_getGlobalSettings },

    { "findCodecByName", "(Ljava/lang/String;)I",
      (void *)android_media_MediaCodecList_findCodecByName },

    { "native_init", "()V", (void *)android_media_MediaCodecList_native_init },
};

int register_android_media_MediaCodecList(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaCodecList", gMethods, NELEM(gMethods));
}

