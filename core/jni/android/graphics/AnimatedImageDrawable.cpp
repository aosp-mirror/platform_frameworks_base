/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "GraphicsJNI.h"
#include "ImageDecoder.h"
#include "core_jni_helpers.h"

#include <hwui/Canvas.h>
#include <SkAndroidCodec.h>
#include <SkAnimatedImage.h>
#include <SkColorFilter.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>

using namespace android;

struct AnimatedImageDrawable {
    sk_sp<SkAnimatedImage> mDrawable;
    SkPaint                mPaint;
};

// Note: jpostProcess holds a handle to the ImageDecoder.
static jlong AnimatedImageDrawable_nCreate(JNIEnv* env, jobject /*clazz*/,
                                           jlong nativeImageDecoder, jobject jpostProcess,
                                           jint width, jint height, jobject jsubset) {
    if (nativeImageDecoder == 0) {
        doThrowIOE(env, "Cannot create AnimatedImageDrawable from null!");
        return 0;
    }

    auto* imageDecoder = reinterpret_cast<ImageDecoder*>(nativeImageDecoder);
    auto info = imageDecoder->mCodec->getInfo();
    const SkISize scaledSize = SkISize::Make(width, height);
    SkIRect subset;
    if (jsubset) {
        GraphicsJNI::jrect_to_irect(env, jsubset, &subset);
    } else {
        subset = SkIRect::MakeWH(width, height);
    }

    sk_sp<SkPicture> picture;
    if (jpostProcess) {
        SkRect bounds = SkRect::MakeWH(subset.width(), subset.height());

        SkPictureRecorder recorder;
        SkCanvas* skcanvas = recorder.beginRecording(bounds);
        std::unique_ptr<Canvas> canvas(Canvas::create_canvas(skcanvas));
        postProcessAndRelease(env, jpostProcess, std::move(canvas), bounds.width(),
                              bounds.height());
        if (env->ExceptionCheck()) {
            return 0;
        }
        picture = recorder.finishRecordingAsPicture();
    }

    std::unique_ptr<AnimatedImageDrawable> drawable(new AnimatedImageDrawable);
    drawable->mDrawable = SkAnimatedImage::Make(std::move(imageDecoder->mCodec),
                scaledSize, subset, std::move(picture));
    if (!drawable->mDrawable) {
        doThrowIOE(env, "Failed to create drawable");
        return 0;
    }
    drawable->mDrawable->start();

    return reinterpret_cast<jlong>(drawable.release());
}

static void AnimatedImageDrawable_destruct(AnimatedImageDrawable* drawable) {
    delete drawable;
}

static jlong AnimatedImageDrawable_nGetNativeFinalizer(JNIEnv* /*env*/, jobject /*clazz*/) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&AnimatedImageDrawable_destruct));
}

static jlong AnimatedImageDrawable_nDraw(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                         jlong canvasPtr, jlong msecs) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    double timeToNextUpdate = drawable->mDrawable->update(msecs);
    auto* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    canvas->drawAnimatedImage(drawable->mDrawable.get(), 0, 0, &drawable->mPaint);
    return (jlong) timeToNextUpdate;
}

static void AnimatedImageDrawable_nSetAlpha(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                            jint alpha) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->mPaint.setAlpha(alpha);
}

static jlong AnimatedImageDrawable_nGetAlpha(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->mPaint.getAlpha();
}

static void AnimatedImageDrawable_nSetColorFilter(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                                  jlong nativeFilter) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    auto* filter = reinterpret_cast<SkColorFilter*>(nativeFilter);
    drawable->mPaint.setColorFilter(sk_ref_sp(filter));
}

static jboolean AnimatedImageDrawable_nIsRunning(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->mDrawable->isRunning();
}

static void AnimatedImageDrawable_nStart(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->mDrawable->start();
}

static void AnimatedImageDrawable_nStop(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->mDrawable->stop();
}

static long AnimatedImageDrawable_nNativeByteSize(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    // FIXME: Report the size of the internal SkBitmap etc.
    return sizeof(drawable);
}

static const JNINativeMethod gAnimatedImageDrawableMethods[] = {
    { "nCreate",             "(JLandroid/graphics/ImageDecoder;IILandroid/graphics/Rect;)J", (void*) AnimatedImageDrawable_nCreate },
    { "nGetNativeFinalizer", "()J",                                                          (void*) AnimatedImageDrawable_nGetNativeFinalizer },
    { "nDraw",               "(JJJ)J",                                                       (void*) AnimatedImageDrawable_nDraw },
    { "nSetAlpha",           "(JI)V",                                                        (void*) AnimatedImageDrawable_nSetAlpha },
    { "nGetAlpha",           "(J)I",                                                         (void*) AnimatedImageDrawable_nGetAlpha },
    { "nSetColorFilter",     "(JJ)V",                                                        (void*) AnimatedImageDrawable_nSetColorFilter },
    { "nIsRunning",          "(J)Z",                                                         (void*) AnimatedImageDrawable_nIsRunning },
    { "nStart",              "(J)V",                                                         (void*) AnimatedImageDrawable_nStart },
    { "nStop",               "(J)V",                                                         (void*) AnimatedImageDrawable_nStop },
    { "nNativeByteSize",     "(J)J",                                                         (void*) AnimatedImageDrawable_nNativeByteSize },
};

int register_android_graphics_drawable_AnimatedImageDrawable(JNIEnv* env) {
    return android::RegisterMethodsOrDie(env, "android/graphics/drawable/AnimatedImageDrawable",
            gAnimatedImageDrawableMethods, NELEM(gAnimatedImageDrawableMethods));
}

