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

//#define LOG_NDEBUG 0

#define LOG_TAG "AudioTrackCallback-JNI"

#include <algorithm>

#include <nativehelper/JNIHelp.h>
#include <utils/Errors.h>
#include <utils/Log.h>

#include "android_media_AudioTrackCallback.h"
#include "core_jni_helpers.h"

using namespace android;

#define BYTE_BUFFER_NAME "java/nio/ByteBuffer"
#define BYTE_BUFFER_ALLOCATE_DIRECT_NAME "allocateDirect"

JNIAudioTrackCallback::JNIAudioTrackCallback(JNIEnv* env, jobject thiz, jobject weak_thiz,
                                             jmethodID postEventFromNative) {
    // Hold onto the AudioTrack class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == nullptr) {
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the AudioTrack object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject = env->NewGlobalRef(weak_thiz);

    mPostEventFromNative = postEventFromNative;

    jclass byteBufferClass = FindClassOrDie(env, BYTE_BUFFER_NAME);
    mByteBufferClass = (jclass)env->NewGlobalRef(byteBufferClass);
    mAllocateDirectMethod =
            GetStaticMethodIDOrDie(env, mByteBufferClass, BYTE_BUFFER_ALLOCATE_DIRECT_NAME,
                                   "(I)Ljava/nio/ByteBuffer;");
}

JNIAudioTrackCallback::~JNIAudioTrackCallback() {
    // remove global references
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (env == nullptr) {
        return;
    }
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
    env->DeleteGlobalRef(mByteBufferClass);
}

binder::Status JNIAudioTrackCallback::onCodecFormatChanged(
        const std::vector<uint8_t>& audioMetadata) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (env == nullptr) {
        return binder::Status::ok();
    }

    jobject byteBuffer = env->CallStaticObjectMethod(mByteBufferClass, mAllocateDirectMethod,
                                                     (jint)audioMetadata.size());
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while allocating direct buffer");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (byteBuffer == nullptr) {
        ALOGE("Failed allocating a direct ByteBuffer");
        return binder::Status::fromStatusT(NO_MEMORY);
    }

    uint8_t* byteBufferAddr = (uint8_t*)env->GetDirectBufferAddress(byteBuffer);
    std::copy(audioMetadata.begin(), audioMetadata.end(), byteBufferAddr);
    env->CallStaticVoidMethod(mClass, mPostEventFromNative, mObject,
                              AUDIO_NATIVE_EVENT_CODEC_FORMAT_CHANGE, 0, 0, byteBuffer);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying codec format changed.");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    return binder::Status::ok();
}
