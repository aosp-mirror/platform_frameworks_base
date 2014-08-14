/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include "jni.h"
#include "GraphicsJNI.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <Animator.h>
#include <Interpolator.h>
#include <RenderProperties.h>

namespace android {

using namespace uirenderer;

static struct {
    jclass clazz;

    jmethodID callOnFinished;
} gRenderNodeAnimatorClassInfo;

#ifdef USE_OPENGL_RENDERER

static JNIEnv* getEnv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return 0;
    }
    return env;
}

class AnimationListenerBridge : public AnimationListener {
public:
    // This holds a strong reference to a Java WeakReference<T> object. This avoids
    // cyclic-references-of-doom. If you think "I know, just use NewWeakGlobalRef!"
    // then you end up with basically a PhantomReference, which is totally not
    // what we want.
    AnimationListenerBridge(JNIEnv* env, jobject finishListener) {
        mFinishListener = env->NewGlobalRef(finishListener);
        env->GetJavaVM(&mJvm);
    }

    virtual ~AnimationListenerBridge() {
        if (mFinishListener) {
            onAnimationFinished(NULL);
        }
    }

    virtual void onAnimationFinished(BaseRenderNodeAnimator*) {
        LOG_ALWAYS_FATAL_IF(!mFinishListener, "Finished listener twice?");
        JNIEnv* env = getEnv(mJvm);
        env->CallStaticVoidMethod(
                gRenderNodeAnimatorClassInfo.clazz,
                gRenderNodeAnimatorClassInfo.callOnFinished,
                mFinishListener);
        releaseJavaObject();
    }

private:
    void releaseJavaObject() {
        JNIEnv* env = getEnv(mJvm);
        env->DeleteGlobalRef(mFinishListener);
        mFinishListener = NULL;
    }

    JavaVM* mJvm;
    jobject mFinishListener;
};

static inline RenderPropertyAnimator::RenderProperty toRenderProperty(jint property) {
    LOG_ALWAYS_FATAL_IF(property < 0 || property > RenderPropertyAnimator::ALPHA,
            "Invalid property %d", property);
    return static_cast<RenderPropertyAnimator::RenderProperty>(property);
}

static inline CanvasPropertyPaintAnimator::PaintField toPaintField(jint field) {
    LOG_ALWAYS_FATAL_IF(field < 0
            || field > CanvasPropertyPaintAnimator::ALPHA,
            "Invalid paint field %d", field);
    return static_cast<CanvasPropertyPaintAnimator::PaintField>(field);
}

static jlong createAnimator(JNIEnv* env, jobject clazz,
        jint propertyRaw, jfloat finalValue) {
    RenderPropertyAnimator::RenderProperty property = toRenderProperty(propertyRaw);
    BaseRenderNodeAnimator* animator = new RenderPropertyAnimator(property, finalValue);
    return reinterpret_cast<jlong>( animator );
}

static jlong createCanvasPropertyFloatAnimator(JNIEnv* env, jobject clazz,
        jlong canvasPropertyPtr, jfloat finalValue) {
    CanvasPropertyPrimitive* canvasProperty = reinterpret_cast<CanvasPropertyPrimitive*>(canvasPropertyPtr);
    BaseRenderNodeAnimator* animator = new CanvasPropertyPrimitiveAnimator(canvasProperty, finalValue);
    return reinterpret_cast<jlong>( animator );
}

static jlong createCanvasPropertyPaintAnimator(JNIEnv* env, jobject clazz,
        jlong canvasPropertyPtr, jint paintFieldRaw,
        jfloat finalValue) {
    CanvasPropertyPaint* canvasProperty = reinterpret_cast<CanvasPropertyPaint*>(canvasPropertyPtr);
    CanvasPropertyPaintAnimator::PaintField paintField = toPaintField(paintFieldRaw);
    BaseRenderNodeAnimator* animator = new CanvasPropertyPaintAnimator(
            canvasProperty, paintField, finalValue);
    return reinterpret_cast<jlong>( animator );
}

static jlong createRevealAnimator(JNIEnv* env, jobject clazz,
        jint centerX, jint centerY, jfloat startRadius, jfloat endRadius) {
    BaseRenderNodeAnimator* animator = new RevealAnimator(centerX, centerY, startRadius, endRadius);
    return reinterpret_cast<jlong>( animator );
}

static void setStartValue(JNIEnv* env, jobject clazz, jlong animatorPtr, jfloat startValue) {
    BaseRenderNodeAnimator* animator = reinterpret_cast<BaseRenderNodeAnimator*>(animatorPtr);
    animator->setStartValue(startValue);
}

static void setDuration(JNIEnv* env, jobject clazz, jlong animatorPtr, jlong duration) {
    LOG_ALWAYS_FATAL_IF(duration < 0, "Duration cannot be negative");
    BaseRenderNodeAnimator* animator = reinterpret_cast<BaseRenderNodeAnimator*>(animatorPtr);
    animator->setDuration(duration);
}

static jlong getDuration(JNIEnv* env, jobject clazz, jlong animatorPtr) {
    BaseRenderNodeAnimator* animator = reinterpret_cast<BaseRenderNodeAnimator*>(animatorPtr);
    return static_cast<jlong>(animator->duration());
}

static void setStartDelay(JNIEnv* env, jobject clazz, jlong animatorPtr, jlong startDelay) {
    LOG_ALWAYS_FATAL_IF(startDelay < 0, "Start delay cannot be negative");
    BaseRenderNodeAnimator* animator = reinterpret_cast<BaseRenderNodeAnimator*>(animatorPtr);
    animator->setStartDelay(startDelay);
}

static jlong getStartDelay(JNIEnv* env, jobject clazz, jlong animatorPtr) {
    BaseRenderNodeAnimator* animator = reinterpret_cast<BaseRenderNodeAnimator*>(animatorPtr);
    return static_cast<jlong>(animator->startDelay());
}

static void setInterpolator(JNIEnv* env, jobject clazz, jlong animatorPtr, jlong interpolatorPtr) {
    BaseRenderNodeAnimator* animator = reinterpret_cast<BaseRenderNodeAnimator*>(animatorPtr);
    Interpolator* interpolator = reinterpret_cast<Interpolator*>(interpolatorPtr);
    animator->setInterpolator(interpolator);
}

static void start(JNIEnv* env, jobject clazz, jlong animatorPtr, jobject finishListener) {
    BaseRenderNodeAnimator* animator = reinterpret_cast<BaseRenderNodeAnimator*>(animatorPtr);
    if (finishListener) {
        animator->setListener(new AnimationListenerBridge(env, finishListener));
    }
    animator->start();
}

static void end(JNIEnv* env, jobject clazz, jlong animatorPtr) {
    BaseRenderNodeAnimator* animator = reinterpret_cast<BaseRenderNodeAnimator*>(animatorPtr);
    animator->end();
}

#endif

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/RenderNodeAnimator";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nCreateAnimator", "(IF)J", (void*) createAnimator },
    { "nCreateCanvasPropertyFloatAnimator", "(JF)J", (void*) createCanvasPropertyFloatAnimator },
    { "nCreateCanvasPropertyPaintAnimator", "(JIF)J", (void*) createCanvasPropertyPaintAnimator },
    { "nCreateRevealAnimator", "(IIFF)J", (void*) createRevealAnimator },
    { "nSetStartValue", "(JF)V", (void*) setStartValue },
    { "nSetDuration", "(JJ)V", (void*) setDuration },
    { "nGetDuration", "(J)J", (void*) getDuration },
    { "nSetStartDelay", "(JJ)V", (void*) setStartDelay },
    { "nSetInterpolator", "(JJ)V", (void*) setInterpolator },
    { "nStart", "(JLandroid/view/RenderNodeAnimator;)V", (void*) start },
    { "nEnd", "(J)V", (void*) end },
#endif
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className);

#define GET_STATIC_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

int register_android_view_RenderNodeAnimator(JNIEnv* env) {
    FIND_CLASS(gRenderNodeAnimatorClassInfo.clazz, kClassPathName);
    gRenderNodeAnimatorClassInfo.clazz = jclass(env->NewGlobalRef(gRenderNodeAnimatorClassInfo.clazz));

    GET_STATIC_METHOD_ID(gRenderNodeAnimatorClassInfo.callOnFinished, gRenderNodeAnimatorClassInfo.clazz,
            "callOnFinished", "(Landroid/view/RenderNodeAnimator;)V");

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}


} // namespace android
