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

#include <GLES/gl.h>
#include <Mesh.h>
#include <SkMesh.h>

#include "GraphicsJNI.h"
#include "graphics_jni_helpers.h"

namespace android {

sk_sp<SkMesh::VertexBuffer> genVertexBuffer(JNIEnv* env, jobject buffer, int size,
                                            jboolean isDirect) {
    auto buff = ScopedJavaNioBuffer(env, buffer, size, isDirect);
    auto vertexBuffer = SkMesh::MakeVertexBuffer(nullptr, buff.data(), size);
    return vertexBuffer;
}

sk_sp<SkMesh::IndexBuffer> genIndexBuffer(JNIEnv* env, jobject buffer, int size,
                                          jboolean isDirect) {
    auto buff = ScopedJavaNioBuffer(env, buffer, size, isDirect);
    auto indexBuffer = SkMesh::MakeIndexBuffer(nullptr, buff.data(), size);
    return indexBuffer;
}

static jlong make(JNIEnv* env, jobject, jlong meshSpec, jint mode, jobject vertexBuffer,
                  jboolean isDirect, jint vertexCount, jint vertexOffset, jint left, jint top,
                  jint right, jint bottom) {
    auto skMeshSpec = sk_ref_sp(reinterpret_cast<SkMeshSpecification*>(meshSpec));
    sk_sp<SkMesh::VertexBuffer> skVertexBuffer =
            genVertexBuffer(env, vertexBuffer, vertexCount * skMeshSpec->stride(), isDirect);
    auto skRect = SkRect::MakeLTRB(left, top, right, bottom);
    auto mesh = SkMesh::Make(skMeshSpec, SkMesh::Mode(mode), skVertexBuffer, vertexCount,
                             vertexOffset, nullptr, skRect)
                        .mesh;
    auto meshPtr = std::make_unique<MeshWrapper>(MeshWrapper{mesh, MeshUniformBuilder(skMeshSpec)});
    return reinterpret_cast<jlong>(meshPtr.release());
}

static jlong makeIndexed(JNIEnv* env, jobject, jlong meshSpec, jint mode, jobject vertexBuffer,
                         jboolean isVertexDirect, jint vertexCount, jint vertexOffset,
                         jobject indexBuffer, jboolean isIndexDirect, jint indexCount,
                         jint indexOffset, jint left, jint top, jint right, jint bottom) {
    auto skMeshSpec = sk_ref_sp(reinterpret_cast<SkMeshSpecification*>(meshSpec));
    sk_sp<SkMesh::VertexBuffer> skVertexBuffer =
            genVertexBuffer(env, vertexBuffer, vertexCount * skMeshSpec->stride(), isVertexDirect);
    sk_sp<SkMesh::IndexBuffer> skIndexBuffer =
            genIndexBuffer(env, indexBuffer, indexCount * gIndexByteSize, isIndexDirect);
    auto skRect = SkRect::MakeLTRB(left, top, right, bottom);
    auto mesh = SkMesh::MakeIndexed(skMeshSpec, SkMesh::Mode(mode), skVertexBuffer, vertexCount,
                                    vertexOffset, skIndexBuffer, indexCount, indexOffset, nullptr,
                                    skRect)
                        .mesh;
    auto meshPtr = std::make_unique<MeshWrapper>(MeshWrapper{mesh, MeshUniformBuilder(skMeshSpec)});
    return reinterpret_cast<jlong>(meshPtr.release());
}

static void updateMesh(JNIEnv* env, jobject, jlong meshWrapper, jboolean indexed) {
    auto wrapper = reinterpret_cast<MeshWrapper*>(meshWrapper);
    auto mesh = wrapper->mesh;
    if (indexed) {
        wrapper->mesh = SkMesh::MakeIndexed(sk_ref_sp(mesh.spec()), mesh.mode(),
                                            sk_ref_sp(mesh.vertexBuffer()), mesh.vertexCount(),
                                            mesh.vertexOffset(), sk_ref_sp(mesh.indexBuffer()),
                                            mesh.indexCount(), mesh.indexOffset(),
                                            wrapper->builder.fUniforms, mesh.bounds())
                                .mesh;
    } else {
        wrapper->mesh = SkMesh::Make(sk_ref_sp(mesh.spec()), mesh.mode(),
                                     sk_ref_sp(mesh.vertexBuffer()), mesh.vertexCount(),
                                     mesh.vertexOffset(), wrapper->builder.fUniforms, mesh.bounds())
                                .mesh;
    }
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

static void updateFloatUniforms(JNIEnv* env, jobject, jlong uniBuilder, jstring uniformName,
                                jfloat value1, jfloat value2, jfloat value3, jfloat value4,
                                jint count) {
    auto* builder = reinterpret_cast<MeshUniformBuilder*>(uniBuilder);
    ScopedUtfChars name(env, uniformName);
    const float values[4] = {value1, value2, value3, value4};
    nativeUpdateFloatUniforms(env, builder, name.c_str(), values, count, false);
}

static void updateFloatArrayUniforms(JNIEnv* env, jobject, jlong uniBuilder, jstring jUniformName,
                                     jfloatArray jvalues, jboolean isColor) {
    auto builder = reinterpret_cast<MeshUniformBuilder*>(uniBuilder);
    ScopedUtfChars name(env, jUniformName);
    AutoJavaFloatArray autoValues(env, jvalues, 0, kRO_JNIAccess);
    nativeUpdateFloatUniforms(env, builder, name.c_str(), autoValues.ptr(), autoValues.length(),
                              isColor);
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

static void updateIntUniforms(JNIEnv* env, jobject, jlong uniBuilder, jstring uniformName,
                              jint value1, jint value2, jint value3, jint value4, jint count) {
    auto builder = reinterpret_cast<MeshUniformBuilder*>(uniBuilder);
    ScopedUtfChars name(env, uniformName);
    const int values[4] = {value1, value2, value3, value4};
    nativeUpdateIntUniforms(env, builder, name.c_str(), values, count);
}

static void updateIntArrayUniforms(JNIEnv* env, jobject, jlong uniBuilder, jstring uniformName,
                                   jintArray values) {
    auto builder = reinterpret_cast<MeshUniformBuilder*>(uniBuilder);
    ScopedUtfChars name(env, uniformName);
    AutoJavaIntArray autoValues(env, values, 0);
    nativeUpdateIntUniforms(env, builder, name.c_str(), autoValues.ptr(), autoValues.length());
}

static void MeshWrapper_destroy(MeshWrapper* wrapper) {
    delete wrapper;
}

static jlong getMeshFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&MeshWrapper_destroy));
}

static const JNINativeMethod gMeshMethods[] = {
        {"nativeGetFinalizer", "()J", (void*)getMeshFinalizer},
        {"nativeMake", "(JILjava/nio/Buffer;ZIIIIII)J", (void*)make},
        {"nativeMakeIndexed", "(JILjava/nio/Buffer;ZIILjava/nio/ShortBuffer;ZIIIIII)J",
         (void*)makeIndexed},
        {"nativeUpdateMesh", "(JZ)V", (void*)updateMesh},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[FZ)V", (void*)updateFloatArrayUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;FFFFI)V", (void*)updateFloatUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[I)V", (void*)updateIntArrayUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;IIIII)V", (void*)updateIntUniforms}};

int register_android_graphics_Mesh(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/Mesh", gMeshMethods, NELEM(gMeshMethods));
    return 0;
}

}  // namespace android
