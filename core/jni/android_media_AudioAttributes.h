/*
 * Copyright (C) 2018 The Android Open Source Project
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

#pragma once

#include "jni.h"

#include <memory.h>

#include <system/audio.h>

namespace android {

class JNIAudioAttributeHelper
{
public:
    struct FreeDeleter {
        void operator()(void *p) const { ::free(p); }
    };

    using UniqueAaPtr = std::unique_ptr<audio_attributes_t, FreeDeleter>;

    /**
     * @brief makeUnique helper to prevent leak
     * @return a unique ptr of 0-initialized native audio attributes structure
     */
    static UniqueAaPtr makeUnique();

    /**
     * @brief nativeFromJava Gets the underlying AudioAttributes from an AudioAttributes Java
     * object.
     * @param env
     * @param jAudioAttributes JAVA AudioAttribute object
     * @param paa native AudioAttribute pointer
     * @return AUDIO_JAVA_SUCCESS on success, error code otherwise
     */
    static jint nativeFromJava(
            JNIEnv* env, jobject jAudioAttributes, audio_attributes_t *attributes);

    /**
     * @brief nativeToJava AudioAttributes Java object from a native AudioAttributes.
     * @param env
     * @param jAudioAttributes JAVA AudioAttribute object
     * @param attributes native AudioAttribute
     * @return AUDIO_JAVA_SUCCESS on success, error code otherwise
     */
    static jint nativeToJava(
            JNIEnv* env, jobject *jAudioAttributes, const audio_attributes_t &attributes);

    /**
     * @brief getJavaArray: creates an array of JAVA AudioAttributes objects
     * @param env
     * @param jAudioAttributeArray
     * @param numAudioAttributes
     * @return Array of AudioAttributes objects
     */
    static jint getJavaArray(
            JNIEnv* env, jobjectArray *jAudioAttributeArray, jint numAudioAttributes);
};

}; // namespace android
