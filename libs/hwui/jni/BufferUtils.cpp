/*
 * Copyright (C) 2023 The Android Open Source Project
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
#include "BufferUtils.h"

#include "graphics_jni_helpers.h"

static void copyToVector(std::vector<uint8_t>& dst, const void* src, size_t srcSize) {
    if (src) {
        dst.resize(srcSize);
        memcpy(dst.data(), src, srcSize);
    }
}

/**
 * This code is taken and modified from com_google_android_gles_jni_GLImpl.cpp to extract data
 * from a java.nio.Buffer.
 */
static void* getDirectBufferPointer(JNIEnv* env, jobject buffer) {
    if (buffer == nullptr) {
        return nullptr;
    }

    jint position;
    jint limit;
    jint elementSizeShift;
    jlong pointer;
    pointer = jniGetNioBufferFields(env, buffer, &position, &limit, &elementSizeShift);
    if (pointer == 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          "Must use a native order direct Buffer");
        return nullptr;
    }
    pointer += position << elementSizeShift;
    return reinterpret_cast<void*>(pointer);
}

static void releasePointer(JNIEnv* env, jarray array, void* data, jboolean commit) {
    env->ReleasePrimitiveArrayCritical(array, data, commit ? 0 : JNI_ABORT);
}

static void* getPointer(JNIEnv* env, jobject buffer, jarray* array, jint* remaining, jint* offset) {
    jint position;
    jint limit;
    jint elementSizeShift;

    jlong pointer;
    pointer = jniGetNioBufferFields(env, buffer, &position, &limit, &elementSizeShift);
    *remaining = (limit - position) << elementSizeShift;
    if (pointer != 0L) {
        *array = nullptr;
        pointer += position << elementSizeShift;
        return reinterpret_cast<void*>(pointer);
    }

    *array = jniGetNioBufferBaseArray(env, buffer);
    *offset = jniGetNioBufferBaseArrayOffset(env, buffer);
    return nullptr;
}

/**
 * This is a copy of
 * static void android_glBufferData__IILjava_nio_Buffer_2I
 * from com_google_android_gles_jni_GLImpl.cpp
 */
static void setIndirectData(JNIEnv* env, size_t size, jobject data_buf,
                            std::vector<uint8_t>& result) {
    jint exception = 0;
    const char* exceptionType = nullptr;
    const char* exceptionMessage = nullptr;
    jarray array = nullptr;
    jint bufferOffset = 0;
    jint remaining;
    void* data = 0;
    char* dataBase = nullptr;

    if (data_buf) {
        data = getPointer(env, data_buf, (jarray*)&array, &remaining, &bufferOffset);
        if (remaining < size) {
            exception = 1;
            exceptionType = "java/lang/IllegalArgumentException";
            exceptionMessage = "remaining() < size < needed";
            goto exit;
        }
    }
    if (data_buf && data == nullptr) {
        dataBase = (char*)env->GetPrimitiveArrayCritical(array, (jboolean*)0);
        data = (void*)(dataBase + bufferOffset);
    }

    copyToVector(result, data, size);

exit:
    if (array) {
        releasePointer(env, array, (void*)dataBase, JNI_FALSE);
    }
    if (exception) {
        jniThrowException(env, exceptionType, exceptionMessage);
    }
}

std::vector<uint8_t> copyJavaNioBufferToVector(JNIEnv* env, jobject buffer, size_t size,
                                               jboolean isDirect) {
    std::vector<uint8_t> data;
    if (buffer == nullptr) {
        jniThrowNullPointerException(env);
    } else {
        if (isDirect) {
            void* directBufferPtr = getDirectBufferPointer(env, buffer);
            if (directBufferPtr) {
                copyToVector(data, directBufferPtr, size);
            }
        } else {
            setIndirectData(env, size, buffer, data);
        }
    }
    return data;
}
