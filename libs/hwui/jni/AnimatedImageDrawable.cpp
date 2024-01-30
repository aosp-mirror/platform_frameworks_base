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

#include <SkAndroidCodec.h>
#include <SkAnimatedImage.h>
#include <SkColorFilter.h>
#include <SkEncodedImageFormat.h>
#include <SkPicture.h>
#include <SkPictureRecorder.h>
#include <SkRect.h>
#include <SkRefCnt.h>
#include <hwui/AnimatedImageDrawable.h>
#include <hwui/Canvas.h>
#include <hwui/ImageDecoder.h>
#include <utils/Looper.h>

#include "ColorFilter.h"
#include "GraphicsJNI.h"
#include "ImageDecoder.h"
#include "Utils.h"

using namespace android;

static jclass gAnimatedImageDrawableClass;
static jmethodID gAnimatedImageDrawable_callOnAnimationEndMethodID;

// Note: jpostProcess holds a handle to the ImageDecoder.
static jlong AnimatedImageDrawable_nCreate(JNIEnv* env, jobject /*clazz*/,
                                           jlong nativeImageDecoder, jobject jpostProcess,
                                           jint width, jint height, jlong colorSpaceHandle,
                                           jboolean extended, jobject jsubset) {
    if (nativeImageDecoder == 0) {
        doThrowIOE(env, "Cannot create AnimatedImageDrawable from null!");
        return 0;
    }

    auto* imageDecoder = reinterpret_cast<ImageDecoder*>(nativeImageDecoder);
    SkIRect subset;
    if (jsubset) {
        GraphicsJNI::jrect_to_irect(env, jsubset, &subset);
    } else {
        subset = SkIRect::MakeWH(width, height);
    }

    bool hasRestoreFrame = false;
    if (imageDecoder->mCodec->getEncodedFormat() != SkEncodedImageFormat::kWEBP) {
        const int frameCount = imageDecoder->mCodec->codec()->getFrameCount();
        for (int i = 0; i < frameCount; ++i) {
            SkCodec::FrameInfo frameInfo;
            if (!imageDecoder->mCodec->codec()->getFrameInfo(i, &frameInfo)) {
                doThrowIOE(env, "Failed to read frame info!");
                return 0;
            }
            if (frameInfo.fDisposalMethod == SkCodecAnimation::DisposalMethod::kRestorePrevious) {
                hasRestoreFrame = true;
                break;
            }
        }
    }

    auto info = imageDecoder->mCodec->getInfo().makeWH(width, height)
        .makeColorSpace(GraphicsJNI::getNativeColorSpace(colorSpaceHandle));
    if (extended) {
        info = info.makeColorType(kRGBA_F16_SkColorType);
    }

    size_t bytesUsed = info.computeMinByteSize();
    // SkAnimatedImage has one SkBitmap for decoding, plus an extra one if there is a
    // kRestorePrevious frame. AnimatedImageDrawable has two SkPictures storing the current
    // frame and the next frame. (The former assumes that the image is animated, and the
    // latter assumes that it is drawn to a hardware canvas.)
    bytesUsed *= hasRestoreFrame ? 4 : 3;
    sk_sp<SkPicture> picture;
    if (jpostProcess) {
        SkRect bounds = SkRect::MakeWH(subset.width(), subset.height());

        SkPictureRecorder recorder;
        SkCanvas* skcanvas = recorder.beginRecording(bounds);
        std::unique_ptr<Canvas> canvas(Canvas::create_canvas(skcanvas));
        postProcessAndRelease(env, jpostProcess, std::move(canvas));
        if (env->ExceptionCheck()) {
            return 0;
        }
        picture = recorder.finishRecordingAsPicture();
        bytesUsed += picture->approximateBytesUsed();
    }

    SkEncodedImageFormat format = imageDecoder->mCodec->getEncodedFormat();
    sk_sp<SkAnimatedImage> animatedImg = SkAnimatedImage::Make(std::move(imageDecoder->mCodec),
                                                               info, subset,
                                                               std::move(picture));
    if (!animatedImg) {
        doThrowIOE(env, "Failed to create drawable");
        return 0;
    }

    bytesUsed += sizeof(animatedImg.get());

    sk_sp<AnimatedImageDrawable> drawable(
            new AnimatedImageDrawable(std::move(animatedImg), bytesUsed, format));
    return reinterpret_cast<jlong>(drawable.release());
}

static void AnimatedImageDrawable_destruct(AnimatedImageDrawable* drawable) {
    SkSafeUnref(drawable);
}

static jlong AnimatedImageDrawable_nGetNativeFinalizer(JNIEnv* /*env*/, jobject /*clazz*/) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&AnimatedImageDrawable_destruct));
}

// Java's FINISHED relies on this being -1
static_assert(SkAnimatedImage::kFinished == -1);

static jlong AnimatedImageDrawable_nDraw(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                         jlong canvasPtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    auto* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    return (jlong) canvas->drawAnimatedImage(drawable);
}

static void AnimatedImageDrawable_nSetAlpha(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                            jint alpha) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->setStagingAlpha(alpha);
}

static jlong AnimatedImageDrawable_nGetAlpha(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->getStagingAlpha();
}

static void AnimatedImageDrawable_nSetColorFilter(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                                  jlong nativeFilter) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    auto filter = uirenderer::ColorFilter::fromJava(nativeFilter);
    auto skColorFilter = filter != nullptr ? filter->getInstance() : sk_sp<SkColorFilter>();
    drawable->setStagingColorFilter(skColorFilter);
}

static jboolean AnimatedImageDrawable_nIsRunning(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->isRunning();
}

static jboolean AnimatedImageDrawable_nStart(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->start();
}

static jboolean AnimatedImageDrawable_nStop(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->stop();
}

// Java's LOOP_INFINITE relies on this being the same.
static_assert(SkCodec::kRepetitionCountInfinite == -1);

static jint AnimatedImageDrawable_nGetRepeatCount(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->getRepetitionCount();
}

static void AnimatedImageDrawable_nSetRepeatCount(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                                  jint loopCount) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->setRepetitionCount(loopCount);
}

class InvokeListener : public MessageHandler {
public:
    InvokeListener(JNIEnv* env, jobject javaObject) {
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&mJvm) != JNI_OK);
        mCallbackRef = env->NewGlobalRef(javaObject);
    }

    ~InvokeListener() override {
        auto* env = requireEnv(mJvm);
        env->DeleteGlobalRef(mCallbackRef);
    }

    virtual void handleMessage(const Message&) override {
        auto* env = get_env_or_die(mJvm);
        env->CallStaticVoidMethod(gAnimatedImageDrawableClass,
                                  gAnimatedImageDrawable_callOnAnimationEndMethodID, mCallbackRef);
    }

private:
    JavaVM* mJvm;
    jobject mCallbackRef;
};

class JniAnimationEndListener : public OnAnimationEndListener {
public:
    JniAnimationEndListener(sp<Looper>&& looper, JNIEnv* env, jobject javaObject) {
        mListener = new InvokeListener(env, javaObject);
        mLooper = std::move(looper);
    }

    void onAnimationEnd() override { mLooper->sendMessage(mListener, 0); }

private:
    sp<InvokeListener> mListener;
    sp<Looper> mLooper;
};

static void AnimatedImageDrawable_nSetOnAnimationEndListener(JNIEnv* env, jobject /*clazz*/,
                                                             jlong nativePtr, jobject jdrawable) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    if (!jdrawable) {
        drawable->setOnAnimationEndListener(nullptr);
    } else {
        sp<Looper> looper = Looper::getForThread();
        if (!looper.get()) {
            doThrowISE(env,
                       "Must set AnimatedImageDrawable's AnimationCallback on a thread with a "
                       "looper!");
            return;
        }

        drawable->setOnAnimationEndListener(
                std::make_unique<JniAnimationEndListener>(std::move(looper), env, jdrawable));
    }
}

static jlong AnimatedImageDrawable_nNativeByteSize(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    return drawable->byteSize();
}

static void AnimatedImageDrawable_nSetMirrored(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                               jboolean mirrored) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    drawable->setStagingMirrored(mirrored);
}

static void AnimatedImageDrawable_nSetBounds(JNIEnv* env, jobject /*clazz*/, jlong nativePtr,
                                             jobject jrect) {
    auto* drawable = reinterpret_cast<AnimatedImageDrawable*>(nativePtr);
    SkRect rect;
    GraphicsJNI::jrect_to_rect(env, jrect, &rect);
    drawable->setStagingBounds(rect);
}

static const JNINativeMethod gAnimatedImageDrawableMethods[] = {
        {"nCreate", "(JLandroid/graphics/ImageDecoder;IIJZLandroid/graphics/Rect;)J",
         (void*)AnimatedImageDrawable_nCreate},
        {"nGetNativeFinalizer", "()J", (void*)AnimatedImageDrawable_nGetNativeFinalizer},
        {"nDraw", "(JJ)J", (void*)AnimatedImageDrawable_nDraw},
        {"nSetAlpha", "(JI)V", (void*)AnimatedImageDrawable_nSetAlpha},
        {"nGetAlpha", "(J)I", (void*)AnimatedImageDrawable_nGetAlpha},
        {"nSetColorFilter", "(JJ)V", (void*)AnimatedImageDrawable_nSetColorFilter},
        {"nIsRunning", "(J)Z", (void*)AnimatedImageDrawable_nIsRunning},
        {"nStart", "(J)Z", (void*)AnimatedImageDrawable_nStart},
        {"nStop", "(J)Z", (void*)AnimatedImageDrawable_nStop},
        {"nGetRepeatCount", "(J)I", (void*)AnimatedImageDrawable_nGetRepeatCount},
        {"nSetRepeatCount", "(JI)V", (void*)AnimatedImageDrawable_nSetRepeatCount},
        {"nSetOnAnimationEndListener", "(JLjava/lang/ref/WeakReference;)V",
         (void*)AnimatedImageDrawable_nSetOnAnimationEndListener},
        {"nNativeByteSize", "(J)J", (void*)AnimatedImageDrawable_nNativeByteSize},
        {"nSetMirrored", "(JZ)V", (void*)AnimatedImageDrawable_nSetMirrored},
        {"nSetBounds", "(JLandroid/graphics/Rect;)V", (void*)AnimatedImageDrawable_nSetBounds},
};

int register_android_graphics_drawable_AnimatedImageDrawable(JNIEnv* env) {
    gAnimatedImageDrawableClass = reinterpret_cast<jclass>(env->NewGlobalRef(
            FindClassOrDie(env, "android/graphics/drawable/AnimatedImageDrawable")));
    gAnimatedImageDrawable_callOnAnimationEndMethodID =
            GetStaticMethodIDOrDie(env, gAnimatedImageDrawableClass, "callOnAnimationEnd",
                                   "(Ljava/lang/ref/WeakReference;)V");

    return android::RegisterMethodsOrDie(env, "android/graphics/drawable/AnimatedImageDrawable",
            gAnimatedImageDrawableMethods, NELEM(gAnimatedImageDrawableMethods));
}

