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

#include "android_view_RenderNodeAnimator.h"

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

RenderNodeAnimator::RenderNodeAnimator(JNIEnv* env, jobject weakThis,
                RenderProperty property, DeltaValueType deltaType, float delta)
        : RenderPropertyAnimator(property, deltaType, delta) {
    mWeakThis = env->NewGlobalRef(weakThis);
    env->GetJavaVM(&mJvm);
}

RenderNodeAnimator::~RenderNodeAnimator() {
    JNIEnv* env = getEnv(mJvm);
    env->DeleteGlobalRef(mWeakThis);
    mWeakThis = NULL;
}

void RenderNodeAnimator::callOnFinished() {
    JNIEnv* env = getEnv(mJvm);
    env->CallStaticVoidMethod(
            gRenderNodeAnimatorClassInfo.clazz,
            gRenderNodeAnimatorClassInfo.callOnFinished,
            mWeakThis);
}

static jlong createAnimator(JNIEnv* env, jobject clazz, jobject weakThis,
        jint property, jint deltaType, jfloat deltaValue) {
    LOG_ALWAYS_FATAL_IF(property < 0 || property > RenderNodeAnimator::ALPHA,
            "Invalid property %d", property);
    LOG_ALWAYS_FATAL_IF(deltaType != RenderPropertyAnimator::DELTA
            && deltaType != RenderPropertyAnimator::ABSOLUTE,
            "Invalid delta type %d", deltaType);

    RenderNodeAnimator* animator = new RenderNodeAnimator(env, weakThis,
            static_cast<RenderPropertyAnimator::RenderProperty>(property),
            static_cast<RenderPropertyAnimator::DeltaValueType>(deltaType),
            deltaValue);
    animator->incStrong(0);
    return reinterpret_cast<jlong>( animator );
}

static void setDuration(JNIEnv* env, jobject clazz, jlong animatorPtr, jint duration) {
    LOG_ALWAYS_FATAL_IF(duration < 0, "Duration cannot be negative");
    RenderNodeAnimator* animator = reinterpret_cast<RenderNodeAnimator*>(animatorPtr);
    animator->setDuration(duration);
}

static void unref(JNIEnv* env, jobject clazz, jlong objPtr) {
    VirtualLightRefBase* obj = reinterpret_cast<VirtualLightRefBase*>(objPtr);
    obj->decStrong(0);
}

#endif

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/RenderNodeAnimator";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nCreateAnimator", "(Ljava/lang/ref/WeakReference;IIF)J", (void*) createAnimator },
    { "nSetDuration", "(JI)V", (void*) setDuration },
    { "nUnref", "(J)V", (void*) unref },
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
            "callOnFinished", "(Ljava/lang/ref/WeakReference;)V");

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}


} // namespace android
