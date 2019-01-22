/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "jni.h"
#include "GraphicsJNI.h"
#include "core_jni_helpers.h"

#include "SkColorSpace.h"

using namespace android;

static skcms_Matrix3x3 getNativeXYZMatrix(JNIEnv* env, jfloatArray xyzD50) {
    skcms_Matrix3x3 xyzMatrix;
    jfloat* array = env->GetFloatArrayElements(xyzD50, NULL);
    xyzMatrix.vals[0][0] = array[0];
    xyzMatrix.vals[1][0] = array[1];
    xyzMatrix.vals[2][0] = array[2];
    xyzMatrix.vals[0][1] = array[3];
    xyzMatrix.vals[1][1] = array[4];
    xyzMatrix.vals[2][1] = array[5];
    xyzMatrix.vals[0][2] = array[6];
    xyzMatrix.vals[1][2] = array[7];
    xyzMatrix.vals[2][2] = array[8];
    env->ReleaseFloatArrayElements(xyzD50, array, 0);
    return xyzMatrix;
}

///////////////////////////////////////////////////////////////////////////////

sk_sp<SkColorSpace> GraphicsJNI::getNativeColorSpace(jlong colorSpaceHandle) {
    if (colorSpaceHandle == 0) return nullptr;
    return sk_ref_sp(reinterpret_cast<SkColorSpace*>(colorSpaceHandle));
}

static void unref_colorSpace(SkColorSpace* cs) {
    SkSafeUnref(cs);
}

static jlong ColorSpace_getNativeFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&unref_colorSpace));
}

static jlong ColorSpace_creator(JNIEnv* env, jobject, jfloat a, jfloat b, jfloat c,
        jfloat d, jfloat e, jfloat f, jfloat g, jfloatArray xyzD50) {
    skcms_TransferFunction p;
    p.a = a;
    p.b = b;
    p.c = c;
    p.d = d;
    p.e = e;
    p.f = f;
    p.g = g;
    skcms_Matrix3x3 xyzMatrix = getNativeXYZMatrix(env, xyzD50);

    return reinterpret_cast<jlong>(SkColorSpace::MakeRGB(p, xyzMatrix).release());
}

static const JNINativeMethod gColorSpaceRgbMethods[] = {
    {   "nativeGetNativeFinalizer", "()J", (void*)ColorSpace_getNativeFinalizer },
    {   "nativeCreate", "(FFFFFFF[F)J", (void*)ColorSpace_creator }
};

namespace android {

int register_android_graphics_ColorSpace(JNIEnv* env) {
    return android::RegisterMethodsOrDie(env, "android/graphics/ColorSpace$Rgb",
                                         gColorSpaceRgbMethods, NELEM(gColorSpaceRgbMethods));
}

}; // namespace android
