/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <nativehelper/JNIPlatformHelp.h>

#include <stddef.h>

#include "JniConstants.h"

static int GetBufferPosition(JNIEnv* env, jobject nioBuffer) {
    return(*env)->GetIntField(env, nioBuffer, JniConstants_NioBuffer_position(env));
}

static int GetBufferLimit(JNIEnv* env, jobject nioBuffer) {
    return(*env)->GetIntField(env, nioBuffer, JniConstants_NioBuffer_limit(env));
}

static int GetBufferElementSizeShift(JNIEnv* env, jobject nioBuffer) {
    jclass byteBufferClass = JniConstants_NioByteBufferClass(env);
    jclass shortBufferClass = JniConstants_NioShortBufferClass(env);
    jclass charBufferClass = JniConstants_NioCharBufferClass(env);
    jclass intBufferClass = JniConstants_NioIntBufferClass(env);
    jclass floatBufferClass = JniConstants_NioFloatBufferClass(env);
    jclass longBufferClass = JniConstants_NioLongBufferClass(env);
    jclass doubleBufferClass = JniConstants_NioDoubleBufferClass(env);

    // Check the type of the Buffer
    if ((*env)->IsInstanceOf(env, nioBuffer, byteBufferClass)) {
        return 0;
    } else if ((*env)->IsInstanceOf(env, nioBuffer, shortBufferClass) ||
               (*env)->IsInstanceOf(env, nioBuffer, charBufferClass)) {
        return 1;
    } else if ((*env)->IsInstanceOf(env, nioBuffer, intBufferClass) ||
               (*env)->IsInstanceOf(env, nioBuffer, floatBufferClass)) {
        return 2;
    } else if ((*env)->IsInstanceOf(env, nioBuffer, longBufferClass) ||
               (*env)->IsInstanceOf(env, nioBuffer, doubleBufferClass)) {
        return 3;
    }
    return 0;
}

jarray jniGetNioBufferBaseArray(JNIEnv* env, jobject nioBuffer) {
    jmethodID hasArrayMethod = JniConstants_NioBuffer_hasArray(env);
    jboolean hasArray = (*env)->CallBooleanMethod(env, nioBuffer, hasArrayMethod);
    if (hasArray) {
        jmethodID arrayMethod = JniConstants_NioBuffer_array(env);
        return (*env)->CallObjectMethod(env, nioBuffer, arrayMethod);
    } else {
        return NULL;
    }
}

int jniGetNioBufferBaseArrayOffset(JNIEnv* env, jobject nioBuffer) {
    jmethodID hasArrayMethod = JniConstants_NioBuffer_hasArray(env);
    jboolean hasArray = (*env)->CallBooleanMethod(env, nioBuffer, hasArrayMethod);
    if (hasArray) {
        jmethodID arrayOffsetMethod = JniConstants_NioBuffer_arrayOffset(env);
        jint arrayOffset = (*env)->CallIntMethod(env, nioBuffer, arrayOffsetMethod);
        const int position = GetBufferPosition(env, nioBuffer);
        jint elementSizeShift = GetBufferElementSizeShift(env, nioBuffer);
        return (arrayOffset + position) << elementSizeShift;
    } else {
        return 0;
    }
}

jlong jniGetNioBufferPointer(JNIEnv* env, jobject nioBuffer) {
    // in Java 11, the address field of a HeapByteBuffer contains a non-zero value despite
    // HeapByteBuffer being a non-direct buffer. In that case, this should still return 0.
    jmethodID isDirectMethod = JniConstants_NioBuffer_isDirect(env);
    jboolean isDirect = (*env)->CallBooleanMethod(env, nioBuffer, isDirectMethod);
    if (isDirect == JNI_FALSE) {
        return 0L;
    }
    jlong baseAddress = (*env)->GetLongField(env, nioBuffer, JniConstants_NioBuffer_address(env));
    if (baseAddress != 0) {
        const int position = GetBufferPosition(env, nioBuffer);
        const int shift = GetBufferElementSizeShift(env, nioBuffer);
        baseAddress += position << shift;
    }
    return baseAddress;
}

jlong jniGetNioBufferFields(JNIEnv* env, jobject nioBuffer,
                            jint* position, jint* limit, jint* elementSizeShift) {
    *position = GetBufferPosition(env, nioBuffer);
    *limit = GetBufferLimit(env, nioBuffer);
    *elementSizeShift = GetBufferElementSizeShift(env, nioBuffer);
    return (*env)->GetLongField(env, nioBuffer, JniConstants_NioBuffer_address(env));
}
