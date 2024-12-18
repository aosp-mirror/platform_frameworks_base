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

#include <Mesh.h>
#include <SkMesh.h>
#include <jni.h>

#include <utility>

#include "BufferUtils.h"
#include "GraphicsJNI.h"
#include "graphics_jni_helpers.h"

#define gIndexByteSize 2

namespace android {

static jlong make(JNIEnv* env, jobject, jlong meshSpec, jint mode, jobject vertexBuffer,
                  jboolean isDirect, jint vertexCount, jint vertexOffset, jfloat left, jfloat top,
                  jfloat right, jfloat bottom) {
    auto skMeshSpec = sk_ref_sp(reinterpret_cast<SkMeshSpecification*>(meshSpec));
    size_t bufferSize = vertexCount * skMeshSpec->stride();
    auto buffer = copyJavaNioBufferToVector(env, vertexBuffer, bufferSize, isDirect);
    if (env->ExceptionCheck()) {
        return 0;
    }
    auto skRect = SkRect::MakeLTRB(left, top, right, bottom);
    auto meshPtr = new Mesh(skMeshSpec, static_cast<SkMesh::Mode>(mode), std::move(buffer),
                            vertexCount, vertexOffset, skRect);
    auto [valid, msg] = meshPtr->validate();
    if (!valid) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", msg.c_str());
    }
    return reinterpret_cast<jlong>(meshPtr);
}

static jlong makeIndexed(JNIEnv* env, jobject, jlong meshSpec, jint mode, jobject vertexBuffer,
                         jboolean isVertexDirect, jint vertexCount, jint vertexOffset,
                         jobject indexBuffer, jboolean isIndexDirect, jint indexCount,
                         jint indexOffset, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    auto skMeshSpec = sk_ref_sp(reinterpret_cast<SkMeshSpecification*>(meshSpec));
    auto vertexBufferSize = vertexCount * skMeshSpec->stride();
    auto indexBufferSize = indexCount * gIndexByteSize;
    auto vBuf = copyJavaNioBufferToVector(env, vertexBuffer, vertexBufferSize, isVertexDirect);
    if (env->ExceptionCheck()) {
        return 0;
    }
    auto iBuf = copyJavaNioBufferToVector(env, indexBuffer, indexBufferSize, isIndexDirect);
    if (env->ExceptionCheck()) {
        return 0;
    }
    auto skRect = SkRect::MakeLTRB(left, top, right, bottom);
    auto meshPtr =
            new Mesh(skMeshSpec, static_cast<SkMesh::Mode>(mode), std::move(vBuf), vertexCount,
                     vertexOffset, std::move(iBuf), indexCount, indexOffset, skRect);
    auto [valid, msg] = meshPtr->validate();
    if (!valid) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", msg.c_str());
    }

    return reinterpret_cast<jlong>(meshPtr);
}

static inline int ThrowIAEFmt(JNIEnv* env, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int ret = jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", fmt, args);
    va_end(args);
    return ret;
}

static bool isIntUniformType(const SkRuntimeEffect::Uniform::Type& type) {
    switch (type) {
        case SkRuntimeEffect::Uniform::Type::kFloat:
        case SkRuntimeEffect::Uniform::Type::kFloat2:
        case SkRuntimeEffect::Uniform::Type::kFloat3:
        case SkRuntimeEffect::Uniform::Type::kFloat4:
        case SkRuntimeEffect::Uniform::Type::kFloat2x2:
        case SkRuntimeEffect::Uniform::Type::kFloat3x3:
        case SkRuntimeEffect::Uniform::Type::kFloat4x4:
            return false;
        case SkRuntimeEffect::Uniform::Type::kInt:
        case SkRuntimeEffect::Uniform::Type::kInt2:
        case SkRuntimeEffect::Uniform::Type::kInt3:
        case SkRuntimeEffect::Uniform::Type::kInt4:
            return true;
    }
}

static void nativeUpdateFloatUniforms(JNIEnv* env, MeshUniformBuilder* builder,
                                      const char* uniformName, const float values[], int count,
                                      bool isColor) {
    MeshUniformBuilder::MeshUniform uniform = builder->uniform(uniformName);
    if (uniform.fVar == nullptr) {
        ThrowIAEFmt(env, "unable to find uniform named %s", uniformName);
    } else if (isColor != ((uniform.fVar->flags & SkRuntimeEffect::Uniform::kColor_Flag) != 0)) {
        if (isColor) {
            jniThrowExceptionFmt(
                    env, "java/lang/IllegalArgumentException",
                    "attempting to set a color uniform using the non-color specific APIs: %s %x",
                    uniformName, uniform.fVar->flags);
        } else {
            ThrowIAEFmt(env,
                        "attempting to set a non-color uniform using the setColorUniform APIs: %s",
                        uniformName);
        }
    } else if (isIntUniformType(uniform.fVar->type)) {
        ThrowIAEFmt(env, "attempting to set a int uniform using the setUniform APIs: %s",
                    uniformName);
    } else if (!uniform.set<float>(values, count)) {
        ThrowIAEFmt(env, "mismatch in byte size for uniform [expected: %zu actual: %zu]",
                    uniform.fVar->sizeInBytes(), sizeof(float) * count);
    }
}

static void updateFloatUniforms(JNIEnv* env, jobject, jlong meshWrapper, jstring uniformName,
                                jfloat value1, jfloat value2, jfloat value3, jfloat value4,
                                jint count) {
    auto* wrapper = reinterpret_cast<Mesh*>(meshWrapper);
    ScopedUtfChars name(env, uniformName);
    const float values[4] = {value1, value2, value3, value4};
    nativeUpdateFloatUniforms(env, wrapper->uniformBuilder(), name.c_str(), values, count, false);
}

static void updateFloatArrayUniforms(JNIEnv* env, jobject, jlong meshWrapper, jstring jUniformName,
                                     jfloatArray jvalues, jboolean isColor) {
    auto wrapper = reinterpret_cast<Mesh*>(meshWrapper);
    ScopedUtfChars name(env, jUniformName);
    AutoJavaFloatArray autoValues(env, jvalues, 0, kRO_JNIAccess);
    nativeUpdateFloatUniforms(env, wrapper->uniformBuilder(), name.c_str(), autoValues.ptr(),
                              autoValues.length(), isColor);
}

static void nativeUpdateIntUniforms(JNIEnv* env, MeshUniformBuilder* builder,
                                    const char* uniformName, const int values[], int count) {
    MeshUniformBuilder::MeshUniform uniform = builder->uniform(uniformName);
    if (uniform.fVar == nullptr) {
        ThrowIAEFmt(env, "unable to find uniform named %s", uniformName);
    } else if (!isIntUniformType(uniform.fVar->type)) {
        ThrowIAEFmt(env, "attempting to set a non-int uniform using the setIntUniform APIs: %s",
                    uniformName);
    } else if (!uniform.set<int>(values, count)) {
        ThrowIAEFmt(env, "mismatch in byte size for uniform [expected: %zu actual: %zu]",
                    uniform.fVar->sizeInBytes(), sizeof(float) * count);
    }
}

static void updateIntUniforms(JNIEnv* env, jobject, jlong meshWrapper, jstring uniformName,
                              jint value1, jint value2, jint value3, jint value4, jint count) {
    auto wrapper = reinterpret_cast<Mesh*>(meshWrapper);
    ScopedUtfChars name(env, uniformName);
    const int values[4] = {value1, value2, value3, value4};
    nativeUpdateIntUniforms(env, wrapper->uniformBuilder(), name.c_str(), values, count);
}

static void updateIntArrayUniforms(JNIEnv* env, jobject, jlong meshWrapper, jstring uniformName,
                                   jintArray values) {
    auto wrapper = reinterpret_cast<Mesh*>(meshWrapper);
    ScopedUtfChars name(env, uniformName);
    AutoJavaIntArray autoValues(env, values, 0);
    nativeUpdateIntUniforms(env, wrapper->uniformBuilder(), name.c_str(), autoValues.ptr(),
                            autoValues.length());
}

static void MeshWrapper_destroy(Mesh* wrapper) {
    delete wrapper;
}

static jlong getMeshFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&MeshWrapper_destroy));
}

static const JNINativeMethod gMeshMethods[] = {
        {"nativeGetFinalizer", "()J", (void*)getMeshFinalizer},
        {"nativeMake", "(JILjava/nio/Buffer;ZIIFFFF)J", (void*)make},
        {"nativeMakeIndexed", "(JILjava/nio/Buffer;ZIILjava/nio/ShortBuffer;ZIIFFFF)J",
         (void*)makeIndexed},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[FZ)V", (void*)updateFloatArrayUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;FFFFI)V", (void*)updateFloatUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[I)V", (void*)updateIntArrayUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;IIIII)V", (void*)updateIntUniforms}};

int register_android_graphics_Mesh(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/Mesh", gMeshMethods, NELEM(gMeshMethods));
    return 0;
}

}  // namespace android