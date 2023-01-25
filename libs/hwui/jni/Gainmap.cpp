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

#include <Gainmap.h>

#include "Bitmap.h"
#include "GraphicsJNI.h"
#include "graphics_jni_helpers.h"

namespace android {

static jclass gGainmap_class;
static jmethodID gGainmap_constructorMethodID;

using namespace uirenderer;

static Gainmap* fromJava(jlong gainmap) {
    return reinterpret_cast<Gainmap*>(gainmap);
}

static int getCreateFlags(const sk_sp<Bitmap>& bitmap) {
    int flags = 0;
    if (bitmap->info().alphaType() == kPremul_SkAlphaType) {
        flags |= android::bitmap::kBitmapCreateFlag_Premultiplied;
    }
    if (!bitmap->isImmutable()) {
        flags |= android::bitmap::kBitmapCreateFlag_Mutable;
    }
    return flags;
}

jobject Gainmap_extractFromBitmap(JNIEnv* env, const Bitmap& bitmap) {
    auto gainmap = bitmap.gainmap();
    jobject jGainmapImage;
    size_t allocationSize;

    {
        // Scope to guard the release of nativeBitmap
        auto nativeBitmap = gainmap->bitmap;
        const int createFlags = getCreateFlags(nativeBitmap);
        allocationSize = nativeBitmap->getAllocationByteCount();
        jGainmapImage = bitmap::createBitmap(env, nativeBitmap.release(), createFlags);
    }

    // Grab a ref for the jobject
    gainmap->incStrong(0);
    jobject obj = env->NewObject(gGainmap_class, gGainmap_constructorMethodID, jGainmapImage,
                                 gainmap.get(), allocationSize + sizeof(Gainmap), true);

    if (env->ExceptionCheck() != 0) {
        // sadtrombone
        gainmap->decStrong(0);
        ALOGE("*** Uncaught exception returned from Java call!\n");
        env->ExceptionDescribe();
    }
    return obj;
}

static void Gainmap_destructor(Gainmap* gainmap) {
    gainmap->decStrong(0);
}

static jlong Gainmap_getNativeFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&Gainmap_destructor));
}

static void Gainmap_setGainmapMax(JNIEnv*, jobject, jlong gainmapPtr, jfloat r, jfloat g,
                                  jfloat b) {
    fromJava(gainmapPtr)->info.fLogRatioMax = {r, g, b, 1.f};
}

static void Gainmap_getGainmapMax(JNIEnv* env, jobject, jlong gainmapPtr, jfloatArray components) {
    const auto ratioMax = fromJava(gainmapPtr)->info.fLogRatioMax;
    jfloat buf[3]{ratioMax.fR, ratioMax.fG, ratioMax.fB};
    env->SetFloatArrayRegion(components, 0, 3, buf);
}

static void Gainmap_setHdrRatioMax(JNIEnv*, jobject, jlong gainmapPtr, jfloat max) {
    fromJava(gainmapPtr)->info.fHdrRatioMax = max;
}

static jfloat Gainmap_getHdrRatioMax(JNIEnv*, jobject, jlong gainmapPtr) {
    return fromJava(gainmapPtr)->info.fHdrRatioMax;
}

static void Gainmap_setHdrRatioMin(JNIEnv*, jobject, jlong gainmapPtr, jfloat min) {
    fromJava(gainmapPtr)->info.fHdrRatioMin = min;
}

static jfloat Gainmap_getHdrRatioMin(JNIEnv*, jobject, jlong gainmapPtr) {
    return fromJava(gainmapPtr)->info.fHdrRatioMin;
}

static const JNINativeMethod gGainmapMethods[] = {
        {"nGetFinalizer", "()J", (void*)Gainmap_getNativeFinalizer},
        {"nSetGainmapMax", "(JFFF)V", (void*)Gainmap_setGainmapMax},
        {"nGetGainmapMax", "(J[F)V", (void*)Gainmap_getGainmapMax},
        {"nSetHdrRatioMax", "(JF)V", (void*)Gainmap_setHdrRatioMax},
        {"nGetHdrRatioMax", "(J)F", (void*)Gainmap_getHdrRatioMax},
        {"nSetHdrRatioMin", "(JF)V", (void*)Gainmap_setHdrRatioMin},
        {"nGetHdrRatioMin", "(J)F", (void*)Gainmap_getHdrRatioMin},
};

int register_android_graphics_Gainmap(JNIEnv* env) {
    gGainmap_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Gainmap"));
    gGainmap_constructorMethodID =
            GetMethodIDOrDie(env, gGainmap_class, "<init>", "(Landroid/graphics/Bitmap;JIZ)V");
    return android::RegisterMethodsOrDie(env, "android/graphics/Gainmap", gGainmapMethods,
                                         NELEM(gGainmapMethods));
}

}  // namespace android
