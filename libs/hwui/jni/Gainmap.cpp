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

#include "SkColorType.h"
#include "SkGainmapInfo.h"

#ifdef __ANDROID__
#include <binder/Parcel.h>
#endif

#include "Bitmap.h"
#include "GraphicsJNI.h"
#include "ScopedParcel.h"
#include "graphics_jni_helpers.h"

namespace android {

static jclass gGainmap_class;
static jmethodID gGainmap_constructorMethodID;

using namespace uirenderer;

static Gainmap* fromJava(jlong gainmap) {
    return reinterpret_cast<Gainmap*>(gainmap);
}

static SkGainmapInfo::BaseImageType baseImageTypeFromJava(jint direction) {
    switch (direction) {
        case 0:
            return SkGainmapInfo::BaseImageType::kSDR;
        case 1:
            return SkGainmapInfo::BaseImageType::kHDR;
        default:
            LOG_ALWAYS_FATAL("Unrecognized Gainmap direction: %d", direction);
    }
}

static jint baseImageTypeToJava(SkGainmapInfo::BaseImageType type) {
    switch (type) {
        case SkGainmapInfo::BaseImageType::kSDR:
            return 0;
        case SkGainmapInfo::BaseImageType::kHDR:
            return 1;
        default:
            LOG_ALWAYS_FATAL("Unrecognized base image: %d", type);
    }
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

    {
        // Scope to guard the release of nativeBitmap
        auto nativeBitmap = gainmap->bitmap;
        const int createFlags = getCreateFlags(nativeBitmap);
        jGainmapImage = bitmap::createBitmap(env, nativeBitmap.release(), createFlags);
    }

    // Grab a ref for the jobject
    gainmap->incStrong(0);
    jobject obj = env->NewObject(gGainmap_class, gGainmap_constructorMethodID, jGainmapImage,
                                 gainmap.get());

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

jlong Gainmap_createEmpty(JNIEnv*, jobject) {
    Gainmap* gainmap = new Gainmap();
    gainmap->incStrong(0);
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(gainmap));
}

jlong Gainmap_createCopy(JNIEnv*, jobject, jlong sourcePtr) {
    Gainmap* gainmap = new Gainmap();
    gainmap->incStrong(0);
    if (sourcePtr) {
        Gainmap* src = fromJava(sourcePtr);
        gainmap->info = src->info;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(gainmap));
}

static void Gainmap_setBitmap(JNIEnv* env, jobject, jlong gainmapPtr, jobject jBitmap) {
    android::Bitmap* bitmap = GraphicsJNI::getNativeBitmap(env, jBitmap);
    fromJava(gainmapPtr)->bitmap = sk_ref_sp(bitmap);
}

static void Gainmap_setRatioMin(JNIEnv*, jobject, jlong gainmapPtr, jfloat r, jfloat g, jfloat b) {
    fromJava(gainmapPtr)->info.fGainmapRatioMin = {r, g, b, 1.f};
}

static void Gainmap_getRatioMin(JNIEnv* env, jobject, jlong gainmapPtr, jfloatArray components) {
    const auto value = fromJava(gainmapPtr)->info.fGainmapRatioMin;
    jfloat buf[3]{value.fR, value.fG, value.fB};
    env->SetFloatArrayRegion(components, 0, 3, buf);
}

static void Gainmap_setRatioMax(JNIEnv*, jobject, jlong gainmapPtr, jfloat r, jfloat g, jfloat b) {
    fromJava(gainmapPtr)->info.fGainmapRatioMax = {r, g, b, 1.f};
}

static void Gainmap_getRatioMax(JNIEnv* env, jobject, jlong gainmapPtr, jfloatArray components) {
    const auto value = fromJava(gainmapPtr)->info.fGainmapRatioMax;
    jfloat buf[3]{value.fR, value.fG, value.fB};
    env->SetFloatArrayRegion(components, 0, 3, buf);
}

static void Gainmap_setGamma(JNIEnv*, jobject, jlong gainmapPtr, jfloat r, jfloat g, jfloat b) {
    fromJava(gainmapPtr)->info.fGainmapGamma = {r, g, b, 1.f};
}

static void Gainmap_getGamma(JNIEnv* env, jobject, jlong gainmapPtr, jfloatArray components) {
    const auto value = fromJava(gainmapPtr)->info.fGainmapGamma;
    jfloat buf[3]{value.fR, value.fG, value.fB};
    env->SetFloatArrayRegion(components, 0, 3, buf);
}

static void Gainmap_setEpsilonSdr(JNIEnv*, jobject, jlong gainmapPtr, jfloat r, jfloat g,
                                  jfloat b) {
    fromJava(gainmapPtr)->info.fEpsilonSdr = {r, g, b, 1.f};
}

static void Gainmap_getEpsilonSdr(JNIEnv* env, jobject, jlong gainmapPtr, jfloatArray components) {
    const auto value = fromJava(gainmapPtr)->info.fEpsilonSdr;
    jfloat buf[3]{value.fR, value.fG, value.fB};
    env->SetFloatArrayRegion(components, 0, 3, buf);
}

static void Gainmap_setEpsilonHdr(JNIEnv*, jobject, jlong gainmapPtr, jfloat r, jfloat g,
                                  jfloat b) {
    fromJava(gainmapPtr)->info.fEpsilonHdr = {r, g, b, 1.f};
}

static void Gainmap_getEpsilonHdr(JNIEnv* env, jobject, jlong gainmapPtr, jfloatArray components) {
    const auto value = fromJava(gainmapPtr)->info.fEpsilonHdr;
    jfloat buf[3]{value.fR, value.fG, value.fB};
    env->SetFloatArrayRegion(components, 0, 3, buf);
}

static void Gainmap_setDisplayRatioHdr(JNIEnv*, jobject, jlong gainmapPtr, jfloat max) {
    fromJava(gainmapPtr)->info.fDisplayRatioHdr = max;
}

static jfloat Gainmap_getDisplayRatioHdr(JNIEnv*, jobject, jlong gainmapPtr) {
    return fromJava(gainmapPtr)->info.fDisplayRatioHdr;
}

static void Gainmap_setDisplayRatioSdr(JNIEnv*, jobject, jlong gainmapPtr, jfloat min) {
    fromJava(gainmapPtr)->info.fDisplayRatioSdr = min;
}

static jfloat Gainmap_getDisplayRatioSdr(JNIEnv*, jobject, jlong gainmapPtr) {
    return fromJava(gainmapPtr)->info.fDisplayRatioSdr;
}

static void Gainmap_setAlternativeColorSpace(JNIEnv*, jobject, jlong gainmapPtr,
                                             jlong colorSpacePtr) {
    auto colorSpace = GraphicsJNI::getNativeColorSpace(colorSpacePtr);
    fromJava(gainmapPtr)->info.fGainmapMathColorSpace = colorSpace;
}

static jobject Gainmap_getAlternativeColorSpace(JNIEnv* env, jobject, jlong gainmapPtr) {
    const auto javaGainmap = fromJava(gainmapPtr);
    auto colorSpace = javaGainmap->info.fGainmapMathColorSpace.get();
    if (colorSpace == nullptr) {
        return nullptr;
    }

    auto colorType = javaGainmap->bitmap->colorType();
    // A8 bitmaps don't support colorspaces, but an alternative colorspace is
    // still valid for configuring the gainmap math, so use RGBA8888 instead.
    if (colorType == kAlpha_8_SkColorType) {
        colorType = kRGBA_8888_SkColorType;
    }
    return GraphicsJNI::getColorSpace(env, colorSpace, colorType);
}

static void Gainmap_setDirection(JNIEnv*, jobject, jlong gainmapPtr, jint direction) {
    fromJava(gainmapPtr)->info.fBaseImageType = baseImageTypeFromJava(direction);
}

static jint Gainmap_getDirection(JNIEnv* env, jobject, jlong gainmapPtr) {
    return baseImageTypeToJava(fromJava(gainmapPtr)->info.fBaseImageType);
}

// ----------------------------------------------------------------------------
// Serialization
// ----------------------------------------------------------------------------

static void Gainmap_writeToParcel(JNIEnv* env, jobject, jlong nativeObject, jobject parcel) {
#ifdef __ANDROID__  // Layoutlib does not support parcel
    if (parcel == NULL) {
        ALOGD("write null parcel\n");
        return;
    }
    ScopedParcel p(env, parcel);
    SkGainmapInfo info = fromJava(nativeObject)->info;
    // write gainmap to parcel
    // ratio min
    p.writeFloat(info.fGainmapRatioMin.fR);
    p.writeFloat(info.fGainmapRatioMin.fG);
    p.writeFloat(info.fGainmapRatioMin.fB);
    // ratio max
    p.writeFloat(info.fGainmapRatioMax.fR);
    p.writeFloat(info.fGainmapRatioMax.fG);
    p.writeFloat(info.fGainmapRatioMax.fB);
    // gamma
    p.writeFloat(info.fGainmapGamma.fR);
    p.writeFloat(info.fGainmapGamma.fG);
    p.writeFloat(info.fGainmapGamma.fB);
    // epsilonsdr
    p.writeFloat(info.fEpsilonSdr.fR);
    p.writeFloat(info.fEpsilonSdr.fG);
    p.writeFloat(info.fEpsilonSdr.fB);
    // epsilonhdr
    p.writeFloat(info.fEpsilonHdr.fR);
    p.writeFloat(info.fEpsilonHdr.fG);
    p.writeFloat(info.fEpsilonHdr.fB);
    // display ratio sdr
    p.writeFloat(info.fDisplayRatioSdr);
    // display ratio hdr
    p.writeFloat(info.fDisplayRatioHdr);
    // base image type
    p.writeInt32(static_cast<int32_t>(info.fBaseImageType));
#else
    doThrowRE(env, "Cannot use parcels outside of Android!");
#endif
}

static void Gainmap_readFromParcel(JNIEnv* env, jobject, jlong nativeObject, jobject parcel) {
#ifdef __ANDROID__  // Layoutlib does not support parcel
    if (parcel == NULL) {
        jniThrowNullPointerException(env, "parcel cannot be null");
        return;
    }
    ScopedParcel p(env, parcel);

    SkGainmapInfo info;
    info.fGainmapRatioMin = {p.readFloat(), p.readFloat(), p.readFloat(), 1.f};
    info.fGainmapRatioMax = {p.readFloat(), p.readFloat(), p.readFloat(), 1.f};
    info.fGainmapGamma = {p.readFloat(), p.readFloat(), p.readFloat(), 1.f};
    info.fEpsilonSdr = {p.readFloat(), p.readFloat(), p.readFloat(), 1.f};
    info.fEpsilonHdr = {p.readFloat(), p.readFloat(), p.readFloat(), 1.f};
    info.fDisplayRatioSdr = p.readFloat();
    info.fDisplayRatioHdr = p.readFloat();
    info.fBaseImageType = static_cast<SkGainmapInfo::BaseImageType>(p.readInt32());

    fromJava(nativeObject)->info = info;
#else
    jniThrowRuntimeException(env, "Cannot use parcels outside of Android");
#endif
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

static const JNINativeMethod gGainmapMethods[] = {
        {"nGetFinalizer", "()J", (void*)Gainmap_getNativeFinalizer},
        {"nCreateEmpty", "()J", (void*)Gainmap_createEmpty},
        {"nCreateCopy", "(J)J", (void*)Gainmap_createCopy},
        {"nSetBitmap", "(JLandroid/graphics/Bitmap;)V", (void*)Gainmap_setBitmap},
        {"nSetRatioMin", "(JFFF)V", (void*)Gainmap_setRatioMin},
        {"nGetRatioMin", "(J[F)V", (void*)Gainmap_getRatioMin},
        {"nSetRatioMax", "(JFFF)V", (void*)Gainmap_setRatioMax},
        {"nGetRatioMax", "(J[F)V", (void*)Gainmap_getRatioMax},
        {"nSetGamma", "(JFFF)V", (void*)Gainmap_setGamma},
        {"nGetGamma", "(J[F)V", (void*)Gainmap_getGamma},
        {"nSetEpsilonSdr", "(JFFF)V", (void*)Gainmap_setEpsilonSdr},
        {"nGetEpsilonSdr", "(J[F)V", (void*)Gainmap_getEpsilonSdr},
        {"nSetEpsilonHdr", "(JFFF)V", (void*)Gainmap_setEpsilonHdr},
        {"nGetEpsilonHdr", "(J[F)V", (void*)Gainmap_getEpsilonHdr},
        {"nSetDisplayRatioHdr", "(JF)V", (void*)Gainmap_setDisplayRatioHdr},
        {"nGetDisplayRatioHdr", "(J)F", (void*)Gainmap_getDisplayRatioHdr},
        {"nSetDisplayRatioSdr", "(JF)V", (void*)Gainmap_setDisplayRatioSdr},
        {"nGetDisplayRatioSdr", "(J)F", (void*)Gainmap_getDisplayRatioSdr},
        {"nSetAlternativeColorSpace", "(JJ)V", (void*)Gainmap_setAlternativeColorSpace},
        {"nGetAlternativeColorSpace", "(J)Landroid/graphics/ColorSpace;",
         (void*)Gainmap_getAlternativeColorSpace},
        {"nSetDirection", "(JI)V", (void*)Gainmap_setDirection},
        {"nGetDirection", "(J)I", (void*)Gainmap_getDirection},
        {"nWriteGainmapToParcel", "(JLandroid/os/Parcel;)V", (void*)Gainmap_writeToParcel},
        {"nReadGainmapFromParcel", "(JLandroid/os/Parcel;)V", (void*)Gainmap_readFromParcel},
};

int register_android_graphics_Gainmap(JNIEnv* env) {
    gGainmap_class = MakeGlobalRefOrDie(env, FindClassOrDie(env, "android/graphics/Gainmap"));
    gGainmap_constructorMethodID =
            GetMethodIDOrDie(env, gGainmap_class, "<init>", "(Landroid/graphics/Bitmap;J)V");
    return android::RegisterMethodsOrDie(env, "android/graphics/Gainmap", gGainmapMethods,
                                         NELEM(gGainmapMethods));
}

}  // namespace android
