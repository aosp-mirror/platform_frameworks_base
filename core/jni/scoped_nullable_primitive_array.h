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

#ifndef SCOPED_NULLABLE_PRIMITIVE_ARRAY_H
#define SCOPED_NULLABLE_PRIMITIVE_ARRAY_H

#include <jni.h>

namespace android {

#define ARRAY_TRAITS(ARRAY_TYPE, POINTER_TYPE, NAME)                                  \
class NAME ## ArrayTraits {                                                           \
public:                                                                               \
    static constexpr void getArrayRegion(JNIEnv* env, ARRAY_TYPE array, size_t start, \
                                         size_t len, POINTER_TYPE out) {              \
        env->Get ## NAME ## ArrayRegion(array, start, len, out);                      \
    }                                                                                 \
                                                                                      \
    static constexpr POINTER_TYPE getArrayElements(JNIEnv* env, ARRAY_TYPE array) {   \
        return env->Get ## NAME ## ArrayElements(array, nullptr);                     \
    }                                                                                 \
                                                                                      \
    static constexpr void releaseArrayElements(JNIEnv* env, ARRAY_TYPE array,         \
                                               POINTER_TYPE buffer, jint mode) {      \
        env->Release ## NAME ## ArrayElements(array, buffer, mode);                   \
    }                                                                                 \
};                                                                                    \

ARRAY_TRAITS(jbooleanArray, jboolean*, Boolean)
ARRAY_TRAITS(jbyteArray, jbyte*, Byte)
ARRAY_TRAITS(jcharArray, jchar*, Char)
ARRAY_TRAITS(jdoubleArray, jdouble*, Double)
ARRAY_TRAITS(jfloatArray, jfloat*, Float)
ARRAY_TRAITS(jintArray, jint*, Int)
ARRAY_TRAITS(jlongArray, jlong*, Long)
ARRAY_TRAITS(jshortArray, jshort*, Short)

#undef ARRAY_TRAITS

template<typename JavaArrayType, typename PrimitiveType, class Traits, size_t preallocSize = 10>
class ScopedArrayRO {
public:
    ScopedArrayRO(JNIEnv* env, JavaArrayType javaArray) : mEnv(env), mJavaArray(javaArray) {
        if (mJavaArray == nullptr) {
            mSize = 0;
            mRawArray = nullptr;
        } else {
            mSize = mEnv->GetArrayLength(mJavaArray);
            if (mSize <= preallocSize) {
                Traits::getArrayRegion(mEnv, mJavaArray, 0, mSize, mBuffer);
                mRawArray = mBuffer;
            } else {
                mRawArray = Traits::getArrayElements(mEnv, mJavaArray);
            }
        }
    }

    ~ScopedArrayRO() {
        if (mRawArray != nullptr && mRawArray != mBuffer) {
            Traits::releaseArrayElements(mEnv, mJavaArray, mRawArray, JNI_ABORT);
        }
    }

    const PrimitiveType* get() const { return mRawArray; }
    const PrimitiveType& operator[](size_t n) const { return mRawArray[n]; }
    size_t size() const { return mSize; }

private:
    JNIEnv* const mEnv;
    JavaArrayType mJavaArray;
    PrimitiveType* mRawArray;
    size_t mSize;
    PrimitiveType mBuffer[preallocSize];
    DISALLOW_COPY_AND_ASSIGN(ScopedArrayRO);
};

// ScopedNullable***ArrayRO provide convenient read-only access to Java array from JNI code.
// These accept nullptr. In that case, get() returns nullptr and size() returns 0.
using ScopedNullableBooleanArrayRO = ScopedArrayRO<jbooleanArray, jboolean, BooleanArrayTraits>;
using ScopedNullableByteArrayRO = ScopedArrayRO<jbyteArray, jbyte, ByteArrayTraits>;
using ScopedNullableCharArrayRO = ScopedArrayRO<jcharArray, jchar, CharArrayTraits>;
using ScopedNullableDoubleArrayRO = ScopedArrayRO<jdoubleArray, jdouble, DoubleArrayTraits>;
using ScopedNullableFloatArrayRO = ScopedArrayRO<jfloatArray, jfloat, FloatArrayTraits>;
using ScopedNullableIntArrayRO = ScopedArrayRO<jintArray, jint, IntArrayTraits>;
using ScopedNullableLongArrayRO = ScopedArrayRO<jlongArray, jlong, LongArrayTraits>;
using ScopedNullableShortArrayRO = ScopedArrayRO<jshortArray, jshort, ShortArrayTraits>;

}  // namespace android

#endif  // SCOPED_NULLABLE_PRIMITIVE_ARRAY_H
