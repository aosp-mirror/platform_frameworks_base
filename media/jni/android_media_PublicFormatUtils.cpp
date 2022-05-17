/*
 * Copyright 2022 The Android Open Source Project
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

#define LOG_TAG "PublicFormatUtils_JNI"

#include <utils/misc.h>
#include <ui/PublicFormat.h>
#include <android_runtime/AndroidRuntime.h>
#include <jni.h>

using namespace android;

static jint android_media_PublicFormatUtils_getHalFormat(JNIEnv* /*env*/, jobject /*thiz*/,
                                                         jint imageFormat) {
    PublicFormat publicFormat = static_cast<PublicFormat>(imageFormat);
    int nativeFormat = mapPublicFormatToHalFormat(publicFormat);
    return static_cast<jint>(nativeFormat);
}

static jint android_media_PublicFormatUtils_getHalDataspace(JNIEnv* /*env*/, jobject /*thiz*/,
                                                             jint imageFormat) {
    PublicFormat publicFormat = static_cast<PublicFormat>(imageFormat);
    android_dataspace
        nativeDataspace = mapPublicFormatToHalDataspace(publicFormat);
    return static_cast<jint>(nativeDataspace);
}

static jint android_media_PublicFormatUtils_getPublicFormat(JNIEnv* /*env*/, jobject /*thiz*/,
                                                            jint hardwareBufferFormat,
                                                            jint dataspace) {
    PublicFormat nativeFormat = mapHalFormatDataspaceToPublicFormat(
            hardwareBufferFormat, static_cast<android_dataspace>(dataspace));
    return static_cast<jint>(nativeFormat);
}

static const JNINativeMethod gMethods[] = {
    {"nativeGetHalFormat",    "(I)I", (void*)android_media_PublicFormatUtils_getHalFormat},
    {"nativeGetHalDataspace", "(I)I", (void*)android_media_PublicFormatUtils_getHalDataspace},
    {"nativeGetPublicFormat", "(II)I",(void*)android_media_PublicFormatUtils_getPublicFormat}
};

int register_android_media_PublicFormatUtils(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
             "android/media/PublicFormatUtils", gMethods, NELEM(gMethods));
}

