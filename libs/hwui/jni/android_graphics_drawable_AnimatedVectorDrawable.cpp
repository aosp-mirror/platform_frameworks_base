/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "android/log.h"

#include "GraphicsJNI.h"

#include "Animator.h"
#include "Interpolator.h"
#include "PropertyValuesAnimatorSet.h"
#include "PropertyValuesHolder.h"
#include "VectorDrawable.h"

namespace android {
using namespace uirenderer;
using namespace VectorDrawable;

static struct {
    jclass clazz;
    jmethodID callOnFinished;
} gVectorDrawableAnimatorClassInfo;

static JNIEnv* getEnv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return 0;
    }
    return env;
}

static AnimationListener* createAnimationListener(JNIEnv* env, jobject finishListener, jint id) {
    class AnimationListenerBridge : public AnimationListener {
    public:
        AnimationListenerBridge(JNIEnv* env, jobject finishListener, jint id) {
            mFinishListener = env->NewGlobalRef(finishListener);
            env->GetJavaVM(&mJvm);
            mId = id;
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
                    gVectorDrawableAnimatorClassInfo.clazz,
                    gVectorDrawableAnimatorClassInfo.callOnFinished,
                    mFinishListener, mId);
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
        jint mId;
    };
    return new AnimationListenerBridge(env, finishListener, id);
}

static void addAnimator(JNIEnv*, jobject, jlong animatorSetPtr, jlong propertyHolderPtr,
        jlong interpolatorPtr, jlong startDelay, jlong duration, jint repeatCount,
        jint repeatMode) {
    PropertyValuesAnimatorSet* set = reinterpret_cast<PropertyValuesAnimatorSet*>(animatorSetPtr);
    PropertyValuesHolder* holder = reinterpret_cast<PropertyValuesHolder*>(propertyHolderPtr);
    Interpolator* interpolator = reinterpret_cast<Interpolator*>(interpolatorPtr);
    RepeatMode mode = static_cast<RepeatMode>(repeatMode);
    set->addPropertyAnimator(holder, interpolator, startDelay, duration, repeatCount, mode);
}

static jlong createAnimatorSet(JNIEnv*, jobject) {
    PropertyValuesAnimatorSet* animatorSet = new PropertyValuesAnimatorSet();
    return reinterpret_cast<jlong>(animatorSet);
}

static void setVectorDrawableTarget(JNIEnv*, jobject,jlong animatorPtr, jlong vectorDrawablePtr) {
    VectorDrawable::Tree* tree = reinterpret_cast<VectorDrawable::Tree*>(vectorDrawablePtr);
    PropertyValuesAnimatorSet* set = reinterpret_cast<PropertyValuesAnimatorSet*>(animatorPtr);
    set->setVectorDrawable(tree);
}

static jlong createGroupPropertyHolder(JNIEnv*, jobject, jlong nativePtr, jint propertyId,
        jfloat startValue, jfloat endValue) {
    VectorDrawable::Group* group = reinterpret_cast<VectorDrawable::Group*>(nativePtr);
    GroupPropertyValuesHolder* newHolder = new GroupPropertyValuesHolder(group, propertyId,
            startValue, endValue);
    return reinterpret_cast<jlong>(newHolder);
}

static jlong createPathDataPropertyHolder(JNIEnv*, jobject, jlong nativePtr, jlong startValuePtr,
        jlong endValuePtr) {
    VectorDrawable::Path* path = reinterpret_cast<VectorDrawable::Path*>(nativePtr);
    PathData* startData = reinterpret_cast<PathData*>(startValuePtr);
    PathData* endData = reinterpret_cast<PathData*>(endValuePtr);
    PathDataPropertyValuesHolder* newHolder = new PathDataPropertyValuesHolder(path,
            startData, endData);
    return reinterpret_cast<jlong>(newHolder);
}

static jlong createPathColorPropertyHolder(JNIEnv*, jobject, jlong nativePtr, jint propertyId,
        int startValue, jint endValue) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(nativePtr);
    FullPathColorPropertyValuesHolder* newHolder = new FullPathColorPropertyValuesHolder(fullPath,
            propertyId, startValue, endValue);
    return reinterpret_cast<jlong>(newHolder);
}

static jlong createPathPropertyHolder(JNIEnv*, jobject, jlong nativePtr, jint propertyId,
        float startValue, jfloat endValue) {
    VectorDrawable::FullPath* fullPath = reinterpret_cast<VectorDrawable::FullPath*>(nativePtr);
    FullPathPropertyValuesHolder* newHolder = new FullPathPropertyValuesHolder(fullPath,
            propertyId, startValue, endValue);
    return reinterpret_cast<jlong>(newHolder);
}

static jlong createRootAlphaPropertyHolder(JNIEnv*, jobject, jlong nativePtr, jfloat startValue,
        float endValue) {
    VectorDrawable::Tree* tree = reinterpret_cast<VectorDrawable::Tree*>(nativePtr);
    RootAlphaPropertyValuesHolder* newHolder = new RootAlphaPropertyValuesHolder(tree,
            startValue, endValue);
    return reinterpret_cast<jlong>(newHolder);
}
static void setFloatPropertyHolderData(JNIEnv* env, jobject, jlong propertyHolderPtr,
        jfloatArray srcData, jint length) {
    jfloat* propertyData = env->GetFloatArrayElements(srcData, nullptr);
    PropertyValuesHolderImpl<float>* holder =
            reinterpret_cast<PropertyValuesHolderImpl<float>*>(propertyHolderPtr);
    holder->setPropertyDataSource(propertyData, length);
    env->ReleaseFloatArrayElements(srcData, propertyData, JNI_ABORT);
}

static void setIntPropertyHolderData(JNIEnv* env, jobject, jlong propertyHolderPtr,
        jintArray srcData, jint length) {
    jint* propertyData = env->GetIntArrayElements(srcData, nullptr);
    PropertyValuesHolderImpl<int>* holder =
            reinterpret_cast<PropertyValuesHolderImpl<int>*>(propertyHolderPtr);
    holder->setPropertyDataSource(propertyData, length);
    env->ReleaseIntArrayElements(srcData, propertyData, JNI_ABORT);
}

static void start(JNIEnv* env, jobject, jlong animatorSetPtr, jobject finishListener, jint id) {
    PropertyValuesAnimatorSet* set = reinterpret_cast<PropertyValuesAnimatorSet*>(animatorSetPtr);
    AnimationListener* listener = createAnimationListener(env, finishListener, id);
    set->start(listener);
}

static void reverse(JNIEnv* env, jobject, jlong animatorSetPtr, jobject finishListener, jint id) {
    PropertyValuesAnimatorSet* set = reinterpret_cast<PropertyValuesAnimatorSet*>(animatorSetPtr);
    AnimationListener* listener = createAnimationListener(env, finishListener, id);
    set->reverse(listener);
}

static void end(JNIEnv*, jobject, jlong animatorSetPtr) {
    PropertyValuesAnimatorSet* set = reinterpret_cast<PropertyValuesAnimatorSet*>(animatorSetPtr);
    set->end();
}

static void reset(JNIEnv*, jobject, jlong animatorSetPtr) {
    PropertyValuesAnimatorSet* set = reinterpret_cast<PropertyValuesAnimatorSet*>(animatorSetPtr);
    set->reset();
}

static const JNINativeMethod gMethods[] = {
    {"nCreateAnimatorSet", "()J", (void*)createAnimatorSet},
    {"nSetVectorDrawableTarget", "(JJ)V", (void*)setVectorDrawableTarget},
    {"nAddAnimator", "(JJJJJII)V", (void*)addAnimator},
    {"nSetPropertyHolderData", "(J[FI)V", (void*)setFloatPropertyHolderData},
    {"nSetPropertyHolderData", "(J[II)V", (void*)setIntPropertyHolderData},
    {"nStart", "(JLandroid/graphics/drawable/AnimatedVectorDrawable$VectorDrawableAnimatorRT;I)V", (void*)start},
    {"nReverse", "(JLandroid/graphics/drawable/AnimatedVectorDrawable$VectorDrawableAnimatorRT;I)V", (void*)reverse},

    // ------------- @FastNative -------------------

    {"nCreateGroupPropertyHolder", "(JIFF)J", (void*)createGroupPropertyHolder},
    {"nCreatePathDataPropertyHolder", "(JJJ)J", (void*)createPathDataPropertyHolder},
    {"nCreatePathColorPropertyHolder", "(JIII)J", (void*)createPathColorPropertyHolder},
    {"nCreatePathPropertyHolder", "(JIFF)J", (void*)createPathPropertyHolder},
    {"nCreateRootAlphaPropertyHolder", "(JFF)J", (void*)createRootAlphaPropertyHolder},
    {"nEnd", "(J)V", (void*)end},
    {"nReset", "(J)V", (void*)reset},
};

const char* const kClassPathName = "android/graphics/drawable/AnimatedVectorDrawable$VectorDrawableAnimatorRT";
int register_android_graphics_drawable_AnimatedVectorDrawable(JNIEnv* env) {
    gVectorDrawableAnimatorClassInfo.clazz = FindClassOrDie(env, kClassPathName);
    gVectorDrawableAnimatorClassInfo.clazz = MakeGlobalRefOrDie(env,
            gVectorDrawableAnimatorClassInfo.clazz);

    gVectorDrawableAnimatorClassInfo.callOnFinished = GetStaticMethodIDOrDie(
            env, gVectorDrawableAnimatorClassInfo.clazz, "callOnFinished",
            "(Landroid/graphics/drawable/AnimatedVectorDrawable$VectorDrawableAnimatorRT;I)V");
    return RegisterMethodsOrDie(env, "android/graphics/drawable/AnimatedVectorDrawable",
            gMethods, NELEM(gMethods));
}

}; // namespace android
