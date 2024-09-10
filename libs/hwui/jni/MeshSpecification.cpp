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

#include <SkMesh.h>

#include "GraphicsJNI.h"
#include "graphics_jni_helpers.h"

namespace android {

using Attribute = SkMeshSpecification::Attribute;
using Varying = SkMeshSpecification::Varying;

static struct {
    jclass clazz{};
    jfieldID type{};
    jfieldID offset{};
    jfieldID name{};
} gAttributeInfo;

static struct {
    jclass clazz{};
    jfieldID type{};
    jfieldID name{};
} gVaryingInfo;

std::vector<Attribute> extractAttributes(JNIEnv* env, jobjectArray attributes) {
    int size = env->GetArrayLength(attributes);
    std::vector<Attribute> attVector;
    attVector.reserve(size);
    for (int i = 0; i < size; i++) {
        jobject attribute = env->GetObjectArrayElement(attributes, i);
        auto name = (jstring)env->GetObjectField(attribute, gAttributeInfo.name);
        auto attName = ScopedUtfChars(env, name);
        Attribute temp{Attribute::Type(env->GetIntField(attribute, gAttributeInfo.type)),
                       static_cast<size_t>(env->GetIntField(attribute, gAttributeInfo.offset)),
                       SkString(attName.c_str())};
        attVector.push_back(std::move(temp));
    }
    return attVector;
}

std::vector<Varying> extractVaryings(JNIEnv* env, jobjectArray varyings) {
    int size = env->GetArrayLength(varyings);
    std::vector<Varying> varyVector;
    varyVector.reserve(size);
    for (int i = 0; i < size; i++) {
        jobject varying = env->GetObjectArrayElement(varyings, i);
        auto name = (jstring)env->GetObjectField(varying, gVaryingInfo.name);
        auto varyName = ScopedUtfChars(env, name);
        Varying temp{Varying::Type(env->GetIntField(varying, gVaryingInfo.type)),
                     SkString(varyName.c_str())};
        varyVector.push_back(std::move(temp));
    }

    return varyVector;
}

static jlong Make(JNIEnv* env, jobject thiz, jobjectArray attributeArray, jint vertexStride,
                  jobjectArray varyingArray, jstring vertexShader, jstring fragmentShader) {
    auto attributes = extractAttributes(env, attributeArray);
    auto varyings = extractVaryings(env, varyingArray);
    auto skVertexShader = ScopedUtfChars(env, vertexShader);
    auto skFragmentShader = ScopedUtfChars(env, fragmentShader);
    auto meshSpecResult = SkMeshSpecification::Make(attributes, vertexStride, varyings,
                                                    SkString(skVertexShader.c_str()),
                                                    SkString(skFragmentShader.c_str()));
    if (meshSpecResult.specification.get() == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", meshSpecResult.error.c_str());
    }

    return reinterpret_cast<jlong>(meshSpecResult.specification.release());
}

static jlong MakeWithCS(JNIEnv* env, jobject thiz, jobjectArray attributeArray, jint vertexStride,
                        jobjectArray varyingArray, jstring vertexShader, jstring fragmentShader,
                        jlong colorSpace) {
    auto attributes = extractAttributes(env, attributeArray);
    auto varyings = extractVaryings(env, varyingArray);
    auto skVertexShader = ScopedUtfChars(env, vertexShader);
    auto skFragmentShader = ScopedUtfChars(env, fragmentShader);
    auto meshSpecResult = SkMeshSpecification::Make(
            attributes, vertexStride, varyings, SkString(skVertexShader.c_str()),
            SkString(skFragmentShader.c_str()), GraphicsJNI::getNativeColorSpace(colorSpace));

    if (meshSpecResult.specification.get() == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", meshSpecResult.error.c_str());
    }

    return reinterpret_cast<jlong>(meshSpecResult.specification.release());
}

static jlong MakeWithAlpha(JNIEnv* env, jobject thiz, jobjectArray attributeArray,
                           jint vertexStride, jobjectArray varyingArray, jstring vertexShader,
                           jstring fragmentShader, jlong colorSpace, jint alphaType) {
    auto attributes = extractAttributes(env, attributeArray);
    auto varyings = extractVaryings(env, varyingArray);
    auto skVertexShader = ScopedUtfChars(env, vertexShader);
    auto skFragmentShader = ScopedUtfChars(env, fragmentShader);
    auto meshSpecResult = SkMeshSpecification::Make(
            attributes, vertexStride, varyings, SkString(skVertexShader.c_str()),
            SkString(skFragmentShader.c_str()), GraphicsJNI::getNativeColorSpace(colorSpace),
            SkAlphaType(alphaType));

    if (meshSpecResult.specification.get() == nullptr) {
        jniThrowException(env, "java/lang/IllegalArgumentException", meshSpecResult.error.c_str());
    }

    return reinterpret_cast<jlong>(meshSpecResult.specification.release());
}

static void MeshSpecification_safeUnref(SkMeshSpecification* meshSpec) {
    SkSafeUnref(meshSpec);
}

static jlong getMeshSpecificationFinalizer(CRITICAL_JNI_PARAMS) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&MeshSpecification_safeUnref));
}

static const JNINativeMethod gMeshSpecificationMethods[] = {
        {"nativeGetFinalizer", "()J", (void*)getMeshSpecificationFinalizer},
        {"nativeMake",
         "([Landroid/graphics/MeshSpecification$Attribute;I[Landroid/graphics/"
         "MeshSpecification$Varying;"
         "Ljava/lang/String;Ljava/lang/String;)J",
         (void*)Make},
        {"nativeMakeWithCS",
         "([Landroid/graphics/MeshSpecification$Attribute;I"
         "[Landroid/graphics/MeshSpecification$Varying;Ljava/lang/String;Ljava/lang/String;J)J",
         (void*)MakeWithCS},
        {"nativeMakeWithAlpha",
         "([Landroid/graphics/MeshSpecification$Attribute;I"
         "[Landroid/graphics/MeshSpecification$Varying;Ljava/lang/String;Ljava/lang/String;JI)J",
         (void*)MakeWithAlpha}};

int register_android_graphics_MeshSpecification(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/MeshSpecification",
                                  gMeshSpecificationMethods, NELEM(gMeshSpecificationMethods));

    gAttributeInfo.clazz = env->FindClass("android/graphics/MeshSpecification$Attribute");
    gAttributeInfo.type = env->GetFieldID(gAttributeInfo.clazz, "mType", "I");
    gAttributeInfo.offset = env->GetFieldID(gAttributeInfo.clazz, "mOffset", "I");
    gAttributeInfo.name = env->GetFieldID(gAttributeInfo.clazz, "mName", "Ljava/lang/String;");

    gVaryingInfo.clazz = env->FindClass("android/graphics/MeshSpecification$Varying");
    gVaryingInfo.type = env->GetFieldID(gVaryingInfo.clazz, "mType", "I");
    gVaryingInfo.name = env->GetFieldID(gVaryingInfo.clazz, "mName", "Ljava/lang/String;");
    return 0;
}

}  // namespace android
