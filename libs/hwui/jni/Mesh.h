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

#ifndef FRAMEWORKS_BASE_LIBS_HWUI_JNI_MESH_H_
#define FRAMEWORKS_BASE_LIBS_HWUI_JNI_MESH_H_

#include <SkMesh.h>
#include <jni.h>

#include <utility>

#include "graphics_jni_helpers.h"

#define gIndexByteSize 2

// A smart pointer that provides read only access to Java.nio.Buffer. This handles both
// direct and indrect buffers, allowing access to the underlying data in both
// situations. If passed a null buffer, we will throw NullPointerException,
// and c_data will return nullptr.
//
// This class draws from com_google_android_gles_jni_GLImpl.cpp for Buffer to void *
// conversion.
class ScopedJavaNioBuffer {
public:
    ScopedJavaNioBuffer(JNIEnv* env, jobject buffer, jint size, jboolean isDirect)
            : mEnv(env), mBuffer(buffer) {
        if (buffer == nullptr) {
            mDataBase = nullptr;
            mData = nullptr;
            jniThrowNullPointerException(env);
        } else {
            mArray = (jarray) nullptr;
            if (isDirect) {
                mData = getDirectBufferPointer(mEnv, mBuffer);
            } else {
                mData = setIndirectData(size);
            }
        }
    }

    ScopedJavaNioBuffer(ScopedJavaNioBuffer&& rhs) noexcept { *this = std::move(rhs); }

    ~ScopedJavaNioBuffer() { reset(); }

    void reset() {
        if (mDataBase) {
            releasePointer(mEnv, mArray, mDataBase, JNI_FALSE);
            mDataBase = nullptr;
        }
    }

    ScopedJavaNioBuffer& operator=(ScopedJavaNioBuffer&& rhs) noexcept {
        if (this != &rhs) {
            reset();

            mEnv = rhs.mEnv;
            mBuffer = rhs.mBuffer;
            mDataBase = rhs.mDataBase;
            mData = rhs.mData;
            mArray = rhs.mArray;
            rhs.mEnv = nullptr;
            rhs.mData = nullptr;
            rhs.mBuffer = nullptr;
            rhs.mArray = nullptr;
            rhs.mDataBase = nullptr;
        }
        return *this;
    }

    const void* data() const { return mData; }

private:
    /**
     * This code is taken and modified from com_google_android_gles_jni_GLImpl.cpp to extract data
     * from a java.nio.Buffer.
     */
    void* getDirectBufferPointer(JNIEnv* env, jobject buffer) {
        if (buffer == nullptr) {
            return nullptr;
        }

        jint position;
        jint limit;
        jint elementSizeShift;
        jlong pointer;
        pointer = jniGetNioBufferFields(env, buffer, &position, &limit, &elementSizeShift);
        if (pointer == 0) {
            jniThrowException(mEnv, "java/lang/IllegalArgumentException",
                              "Must use a native order direct Buffer");
            return nullptr;
        }
        pointer += position << elementSizeShift;
        return reinterpret_cast<void*>(pointer);
    }

    static void releasePointer(JNIEnv* env, jarray array, void* data, jboolean commit) {
        env->ReleasePrimitiveArrayCritical(array, data, commit ? 0 : JNI_ABORT);
    }

    static void* getPointer(JNIEnv* env, jobject buffer, jarray* array, jint* remaining,
                            jint* offset) {
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
    void* setIndirectData(jint size) {
        jint exception;
        const char* exceptionType;
        const char* exceptionMessage;
        jint bufferOffset = (jint)0;
        jint remaining;
        void* tempData;

        if (mBuffer) {
            tempData =
                    (void*)getPointer(mEnv, mBuffer, (jarray*)&mArray, &remaining, &bufferOffset);
            if (remaining < size) {
                exception = 1;
                exceptionType = "java/lang/IllegalArgumentException";
                exceptionMessage = "remaining() < size < needed";
                goto exit;
            }
        }
        if (mBuffer && tempData == nullptr) {
            mDataBase = (char*)mEnv->GetPrimitiveArrayCritical(mArray, (jboolean*)0);
            tempData = (void*)(mDataBase + bufferOffset);
        }
        return tempData;
    exit:
        if (mArray) {
            releasePointer(mEnv, mArray, (void*)(mDataBase), JNI_FALSE);
        }
        if (exception) {
            jniThrowException(mEnv, exceptionType, exceptionMessage);
        }
        return nullptr;
    }

    JNIEnv* mEnv;

    // Java Buffer data
    void* mData;
    jobject mBuffer;

    // Indirect Buffer Data
    jarray mArray;
    char* mDataBase;
};

class MeshUniformBuilder {
public:
    struct MeshUniform {
        template <typename T>
        std::enable_if_t<std::is_trivially_copyable<T>::value, MeshUniform> operator=(
                const T& val) {
            if (!fVar) {
                SkDEBUGFAIL("Assigning to missing variable");
            } else if (sizeof(val) != fVar->sizeInBytes()) {
                SkDEBUGFAIL("Incorrect value size");
            } else {
                memcpy(SkTAddOffset<void>(fOwner->writableUniformData(), fVar->offset), &val,
                       szeof(val));
            }
        }

        MeshUniform& operator=(const SkMatrix& val) {
            if (!fVar) {
                SkDEBUGFAIL("Assigning to missing variable");
            } else if (fVar->sizeInBytes() != 9 * sizeof(float)) {
                SkDEBUGFAIL("Incorrect value size");
            } else {
                float* data =
                        SkTAddOffset<float>(fOwner->writableUniformData(), (ptrdiff_t)fVar->offset);
                data[0] = val.get(0);
                data[1] = val.get(3);
                data[2] = val.get(6);
                data[3] = val.get(1);
                data[4] = val.get(4);
                data[5] = val.get(7);
                data[6] = val.get(2);
                data[7] = val.get(5);
                data[8] = val.get(8);
            }
            return *this;
        }

        template <typename T>
        bool set(const T val[], const int count) {
            static_assert(std::is_trivially_copyable<T>::value, "Value must be trivial copyable");
            if (!fVar) {
                SkDEBUGFAIL("Assigning to missing variable");
                return false;
            } else if (sizeof(T) * count != fVar->sizeInBytes()) {
                SkDEBUGFAIL("Incorrect value size");
                return false;
            } else {
                memcpy(SkTAddOffset<void>(fOwner->writableUniformData(), fVar->offset), val,
                       sizeof(T) * count);
            }
            return true;
        }

        MeshUniformBuilder* fOwner;
        const SkRuntimeEffect::Uniform* fVar;
    };
    MeshUniform uniform(std::string_view name) { return {this, fMeshSpec->findUniform(name)}; }

    explicit MeshUniformBuilder(sk_sp<SkMeshSpecification> meshSpec) {
        fMeshSpec = sk_sp(meshSpec);
        fUniforms = (SkData::MakeZeroInitialized(meshSpec->uniformSize()));
    }

    sk_sp<SkData> fUniforms;

private:
    void* writableUniformData() {
        if (!fUniforms->unique()) {
            fUniforms = SkData::MakeWithCopy(fUniforms->data(), fUniforms->size());
        }
        return fUniforms->writable_data();
    }

    sk_sp<SkMeshSpecification> fMeshSpec;
};

struct MeshWrapper {
    SkMesh mesh;
    MeshUniformBuilder builder;
};
#endif  // FRAMEWORKS_BASE_LIBS_HWUI_JNI_MESH_H_
