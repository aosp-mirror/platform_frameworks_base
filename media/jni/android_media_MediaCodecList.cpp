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

#include "android_runtime/AndroidRuntime.h"
#include "jni.h"
#include "JNIHelp.h"
#include "android_media_Utils.h"

using namespace android;

static jint android_media_MediaCodecList_getCodecCount(
        JNIEnv *env, jobject thiz) {
    return MediaCodecList::getInstance()->countCodecs();
}

static jstring android_media_MediaCodecList_getCodecName(
        JNIEnv *env, jobject thiz, jint index) {
    const char *name = MediaCodecList::getInstance()->getCodecName(index);

    if (name == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    return env->NewStringUTF(name);
}

static jint android_media_MediaCodecList_findCodecByName(
        JNIEnv *env, jobject thiz, jstring name) {
    if (name == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return -ENOENT;
    }

    const char *nameStr = env->GetStringUTFChars(name, NULL);

    if (nameStr == NULL) {
        // Out of memory exception already pending.
        return -ENOENT;
    }

    jint ret = MediaCodecList::getInstance()->findCodecByName(nameStr);
    env->ReleaseStringUTFChars(name, nameStr);
    return ret;
}

static jboolean android_media_MediaCodecList_isEncoder(
        JNIEnv *env, jobject thiz, jint index) {
    return MediaCodecList::getInstance()->isEncoder(index);
}

static jarray android_media_MediaCodecList_getSupportedTypes(
        JNIEnv *env, jobject thiz, jint index) {
    Vector<AString> types;
    status_t err =
        MediaCodecList::getInstance()->getSupportedTypes(index, &types);

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

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
        JNIEnv *env, jobject thiz, jint index, jstring type) {
    if (type == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    const char *typeStr = env->GetStringUTFChars(type, NULL);

    if (typeStr == NULL) {
        // Out of memory exception already pending.
        return NULL;
    }

    Vector<MediaCodecList::ProfileLevel> profileLevels;
    Vector<uint32_t> colorFormats;
    uint32_t flags;
    sp<AMessage> capabilities;

    sp<AMessage> defaultFormat = new AMessage();
    defaultFormat->setString("mime", typeStr);

    // TODO query default-format also from codec/codec list

    status_t err =
        MediaCodecList::getInstance()->getCodecCapabilities(
                index, typeStr, &profileLevels, &colorFormats, &flags,
                &capabilities);

    bool isEncoder = MediaCodecList::getInstance()->isEncoder(index);

    env->ReleaseStringUTFChars(type, typeStr);
    typeStr = NULL;

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    jobject defaultFormatObj = NULL;
    if (ConvertMessageToMap(env, defaultFormat, &defaultFormatObj)) {
        return NULL;
    }

    jobject infoObj = NULL;
    if (ConvertMessageToMap(env, capabilities, &infoObj)) {
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
        const MediaCodecList::ProfileLevel &src = profileLevels.itemAt(i);

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
            "([Landroid/media/MediaCodecInfo$CodecProfileLevel;[IZI"
            "Ljava/util/Map;Ljava/util/Map;)V");

    jobject caps = env->NewObject(capsClazz, capsConstructID,
            profileLevelArray, colorFormatsArray, isEncoder, flags,
            defaultFormatObj, infoObj);

#if 0
    jfieldID profileLevelsField = env->GetFieldID(
            capsClazz,
            "profileLevels",
            "[Landroid/media/MediaCodecInfo$CodecProfileLevel;");

    env->SetObjectField(caps, profileLevelsField, profileLevelArray);

    jfieldID flagsField =
        env->GetFieldID(capsClazz, "mFlagsVerified", "I");

    env->SetIntField(caps, flagsField, flags);

    jfieldID colorFormatsField = env->GetFieldID(
            capsClazz, "colorFormats", "[I");

    env->SetObjectField(caps, colorFormatsField, colorFormatsArray);

#endif

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

static void android_media_MediaCodecList_native_init(JNIEnv *env) {
}

static JNINativeMethod gMethods[] = {
    { "native_getCodecCount", "()I", (void *)android_media_MediaCodecList_getCodecCount },
    { "getCodecName", "(I)Ljava/lang/String;",
      (void *)android_media_MediaCodecList_getCodecName },
    { "isEncoder", "(I)Z", (void *)android_media_MediaCodecList_isEncoder },
    { "getSupportedTypes", "(I)[Ljava/lang/String;",
      (void *)android_media_MediaCodecList_getSupportedTypes },

    { "getCodecCapabilities",
      "(ILjava/lang/String;)Landroid/media/MediaCodecInfo$CodecCapabilities;",
      (void *)android_media_MediaCodecList_getCodecCapabilities },

    { "findCodecByName", "(Ljava/lang/String;)I",
      (void *)android_media_MediaCodecList_findCodecByName },

    { "native_init", "()V", (void *)android_media_MediaCodecList_native_init },
};

int register_android_media_MediaCodecList(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaCodecList", gMethods, NELEM(gMethods));
}

